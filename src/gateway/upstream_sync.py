"""S17：网关进程内「真实上游同步」作业 —— 点击 → 登录管家婆 → 拉当场最新 → 比对 → 仅变更落库。

红线（与 ``scripts/sync-xzg.py`` 完全同口径，不得在此模块定义新策略）：

1. **上游绝对只读**：全部出站请求仍走 :mod:`sync.xzg_client` 的只读白名单；
   凭据只从环境变量读，不落日志、不落库、不进状态文件。
2. **任何阶段失败 = 整体失败且零落库**：先把商品 + 库存报表两条差异全部算完，
   再进入写阶段；写阶段前必须备份，备份失败即中止。
3. **无变更不写库、不建备份**（避免每次点击都堆一个 36MB 备份目录）。
4. 落库策略复用 :mod:`sync.xzg_sync`（S1 覆盖策略）与 :mod:`sync.xzg_stock`
   （S7 报表口径：成本/库存以上游为准），本模块只负责编排与留痕。

并发模型：进程内单飞（同一时刻至多一个作业），后台守护线程执行，
APP 通过 ``/api/sync/upstream/status`` 轮询阶段与统计。
"""
from __future__ import annotations

import hashlib
import json
import logging
import os
import sqlite3
import sys
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional

_SRC_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _SRC_ROOT not in sys.path:  # 允许以独立模块方式导入（单测/一次性脚本）
    sys.path.insert(0, _SRC_ROOT)

from sync.xzg_stock import (  # noqa: E402
    StockDiff,
    apply_stock,
    diff_stock,
    parse_stock_condition,
    parse_stock_condition_json,
)
from sync.xzg_sync import (  # noqa: E402
    DiffResult,
    apply_diff,
    diff_products,
    ensure_unique_index,
    load_local_rows,
)
from sync.xzg_customers import (  # noqa: E402
    apply_customers,
    diff_customers,
    ensure_customers_table,
    fetch_customers,
    load_local_customers,
    map_customer,
)

logger = logging.getLogger("retaildemo.upstream_sync")

#: 阶段名（APP 直接上屏，勿随意改名）
PHASE_IDLE = "idle"
PHASE_FETCH = "fetch_products"
PHASE_STOCK = "fetch_stock"
PHASE_COMPARE = "compare"
PHASE_BACKUP = "backup"
PHASE_APPLY = "apply"
PHASE_DONE = "done"
PHASE_FAILED = "failed"

#: 单次作业墙钟上限（秒）：超时按失败处理，绝不让同步无限挂着。
MAX_JOB_SECONDS = float(os.environ.get("UPSTREAM_SYNC_MAX_SECONDS", "900"))


def _repo_root() -> str:
    return os.path.dirname(_SRC_ROOT)


def _data_dir() -> str:
    return os.path.join(_repo_root(), "data")


def safe_path(path: str, root: str) -> str:
    """F6 路径守卫：realpath 归一 + 根前缀 + ``..`` 分量显式检查，返回值即落盘路径。"""
    root_real = os.path.realpath(root)
    candidate = os.path.normpath(path)
    if not os.path.isabs(candidate):
        candidate = os.path.normpath(os.path.join(root_real, candidate))
    real = os.path.realpath(candidate)
    if real != root_real and not real.startswith(root_real + os.sep):
        raise ValueError("path outside sandbox: " + real)
    if ".." in os.path.relpath(real, root_real).split(os.sep):
        raise ValueError("path traversal blocked: " + real)
    return real


def _now_ms() -> int:
    return int(time.time() * 1000)


def _now_str() -> str:
    return time.strftime("%Y-%m-%d %H:%M:%S")


def default_db_path() -> str:
    """商品库路径：环境变量优先（与六个服务同源），否则 <repo>/data/wujin.db。"""
    env = os.environ.get("WUJIN_DB_PATH")
    if env:
        return env
    return safe_path(os.path.join(_data_dir(), "wujin.db"), _repo_root())


def status_path_for(db_path: str) -> str:
    """状态文件固定落在商品库同目录（data/），文件名是常量、不含任何用户输入。"""
    data_dir = os.path.realpath(os.path.dirname(os.path.abspath(db_path)))
    return safe_path(os.path.join(data_dir, "upstream-sync-status.json"), data_dir)


@dataclass
class UpstreamFetch:
    """一次上游拉取的结果（fetcher 的返回契约，单测注入假实现同此形状）。"""

    items: List[Dict[str, Any]]
    #: 库存状况表：JSON 通道（XZG-API-RO 默认）给 stock_items；xlsx 老通道给 stock_blob。
    #: 两者二选一，parse 阶段按哪个非空走哪条解析——产出同一种 StockRow。
    stock_items: Optional[List[Dict[str, Any]]] = None
    stock_blob: Optional[bytes] = None
    total_count: Optional[int] = None
    fetch_path: str = "sequential"  # parallel | sequential
    fetch_seconds: float = 0.0
    stock_rows: int = 0
    extra: Dict[str, Any] = field(default_factory=dict)
    #: S18 客户档案（基础信息）原始 item 列表；与商品同批拉取、同批落库。
    customers: List[Dict[str, Any]] = field(default_factory=list)


def fetch_upstream(
    client: Any = None,
    page_size: int = 500,
    workers: int = 6,
    with_stock: bool = True,
    progress: Optional[Callable[..., None]] = None,
) -> UpstreamFetch:
    """登录（令牌失效自动重登）→ 并行全量拉商品 → 库存状况表（成本/库存权威口径）。

    并行路径带三重守卫（条数/序号/截断），守卫不过自动回退顺序全量路径——
    宁可慢，不可错。``progress(phase, done, total)`` 实时上报分页进度（APP 进度条）。
    """
    from sync.xzg_client import XzgClient

    client = client or XzgClient(verbose=False)

    def on_page(done: int, total: int) -> None:
        if progress:
            progress(PHASE_FETCH, done, total)

    if progress:
        progress(PHASE_FETCH, 0, None)

    t0 = time.time()
    items = client.fetch_all_products_fast(page_size=page_size, workers=workers, on_page=on_page)
    fetch_path = "parallel"
    if items is None:
        fetch_path = "sequential"
        logger.warning("并行拉取守卫未过/不可用 → 回退顺序全量路径（准确性不变）")
        items = client.fetch_all_products(page_size=page_size, on_page=on_page)
    fetch_seconds = time.time() - t0

    stock_blob: Optional[bytes] = None
    stock_items: Optional[List[Dict[str, Any]]] = None
    stock_rows_n = 0
    if with_stock:
        if progress:
            progress(PHASE_STOCK, None, None)
        # XZG-API-RO：默认走 JSON（一次请求直接拿结构化行，少三步链路）。
        # 上游 JSON 不可用（异常/空结果）时**回退 xlsx 两段式**——宁可慢，不可无库存。
        try:
            stock_items = client.fetch_all_stock_condition(page_size=page_size)
            if not stock_items:
                raise RuntimeError("库存状况 JSON 返回 0 行")
            stock_rows_n = -1  # 解析在比对阶段完成；此处先记录「已取得」
        except Exception as exc:  # noqa: BLE001 - 任何异常都回退老通道
            logger.warning("库存 JSON 通道不可用（%s）→ 回退 xlsx 两段式导出", exc)
            stock_items = None
            url = client.export_stock_condition()
            stock_blob = client.download_report(url)
            stock_rows_n = -1  # 解析在比对阶段完成；此处先记录「已下载」

    # S18 客户档案（基础信息）：与商品同批拉取。拉取失败向上抛=整体失败零落库
    # （与「任何阶段失败=整体失败」红线同口径）。
    customer_items = fetch_customers(client)

    return UpstreamFetch(
        items=items,
        stock_items=stock_items,
        stock_blob=stock_blob,
        total_count=len(items),
        fetch_path=fetch_path,
        fetch_seconds=round(fetch_seconds, 3),
        stock_rows=stock_rows_n,
        extra={"stock_bytes": len(stock_blob or b""),
               "stock_source": "json" if stock_items is not None else "xlsx"},
        customers=customer_items,
    )


# ---------------------------------------------------------------------------
# 备份（写库前强制；stdlib 在线备份，跨平台）
# ---------------------------------------------------------------------------

def backup_wujin(db_path: str, root: str, note: str = "pre-app-sync") -> str:
    """写库前备份 ``wujin.db``（sqlite online backup）。返回备份目录。

    目录布局与 ``scripts/backup-db.ps1`` 对齐（``backup-<时间戳>-<note>/`` +
    SHA256SUMS.txt），但用纯标准库实现，Linux 迁移后同样可用。
    """
    backup_root = safe_path(root, root)
    note = "".join(ch for ch in note if ch.isalnum() or ch in "-_")[:32] or "backup"
    dest = safe_path(os.path.join(backup_root, "backup-%s-%s" % (time.strftime("%Y%m%d-%H%M%S"), note)),
                     backup_root)
    os.makedirs(dest, exist_ok=True)
    target = safe_path(os.path.join(dest, "wujin.db"), dest)

    src = sqlite3.connect(db_path, timeout=30)
    try:
        dst = sqlite3.connect(target)
        try:
            src.backup(dst)
        finally:
            dst.close()
    finally:
        src.close()

    digest = hashlib.sha256()
    with open(target, "rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    sums_file = safe_path(os.path.join(dest, "SHA256SUMS.txt"), dest)
    with open(sums_file, "w", encoding="utf-8") as fh:
        fh.write("%s  wujin.db\n" % digest.hexdigest())
    logger.info("写库前备份完成：%s", dest)
    return dest


# ---------------------------------------------------------------------------
# 一次性作业（fetcher 可注入；单测直接调用）
# ---------------------------------------------------------------------------

def run_once(
    fetcher: Callable[[], UpstreamFetch],
    db_path: str,
    root: str,
    progress: Optional[Callable[[str], None]] = None,
    do_backup: bool = True,
) -> Dict[str, Any]:
    """执行一次「真实上游同步」。返回统计 dict；任何异常向上抛（调用方标失败）。

    顺序：拉取（登录+全量+报表）→ 两条差异全部算完（**纯计算，不写库**）→
    有变更才「备份 → 落库」；无变更零写、零备份。
    """
    t0 = time.time()

    def phase(name: str, done: Optional[int] = None, total: Optional[int] = None) -> None:
        if progress:
            try:
                progress(name, done, total)
            except Exception:  # noqa: BLE001 - 阶段回调绝不影响作业
                pass

    if not os.path.isfile(db_path):
        raise RuntimeError("商品库不存在，拒绝同步（防误建空库）：%s" % db_path)

    fetch = fetcher()
    if fetch is None or not isinstance(fetch.items, list):
        raise RuntimeError("上游拉取返回非法结果（未拿到商品列表）")
    if not fetch.items:
        raise RuntimeError("上游返回 0 条商品：拒绝落库（防上游异常空表清库）")

    phase(PHASE_COMPARE)
    conn = sqlite3.connect(db_path, timeout=30)
    try:
        conn.execute("PRAGMA busy_timeout=30000")
        local_rows, without_sku = load_local_rows(conn)

        diff: DiffResult = diff_products(
            local_rows,
            fetch.items,
            truncated=False,
            upstream_count=len(fetch.items),
            # S7 拍板：成本价权威在「库存状况表」（移动加权），GetAll 通道让位
            exclude_cols=("cost_price",),
        )
        diff.local_without_sku = without_sku

        stock_diff: Optional[StockDiff] = None
        stock_rows = None
        if fetch.stock_items is not None:
            stock_rows, _bad_rows = parse_stock_condition_json(fetch.stock_items)
        elif fetch.stock_blob:
            stock_rows, _bad_rows = parse_stock_condition(fetch.stock_blob)
        if stock_rows is not None:
            if not stock_rows:
                raise RuntimeError("库存状况表解析为 0 行：拒绝落库（防报表异常刷库存）")
            local_for_stock = {
                str(r[0]).strip(): {"name": r[1], "cost_price": r[2], "stock_qty": r[3]}
                for r in conn.execute(
                    "select sku, name, cost_price, stock_qty from products "
                    "where sku is not null and trim(sku) <> ''")
            }
            stock_diff = diff_stock(local_for_stock, stock_rows)
            fetch.stock_rows = len(stock_rows)

        changed_skus = {c["sku"] for c in diff.updates} | {m["sku"] for m in diff.inserts}
        if stock_diff is not None:
            changed_skus |= {c["sku"] for c in stock_diff.cost_changes}
            changed_skus |= {c["sku"] for c in stock_diff.stock_changes}

        # S18 客户档案：与商品/库存同一「先算全部差异，再决定写不写」纪律（纯计算，不写库）。
        # 注意：表可能尚不存在（首次同步前/零变更历史）——load_local_customers 对缺表返回空 dict，
        # 全部按新增处理；建表动作放在 apply 阶段，保证「无变更零写库」（S17 红线）不被破坏。
        customer_rows = [map_customer(item)[0] for item in fetch.customers]
        cust_diff = diff_customers(load_local_customers(conn), customer_rows)

        stats: Dict[str, Any] = {
            "fetched": len(fetch.items),
            "fetch_path": fetch.fetch_path,
            "fetch_seconds": fetch.fetch_seconds,
            "stock_bytes": fetch.extra.get("stock_bytes", 0),
            "stock_source": fetch.extra.get("stock_source", "unknown"),
            "stock_rows": fetch.stock_rows,
            "inserts": len(diff.inserts),
            "updates": len({c["sku"] for c in diff.updates}),
            "changed_skus": len(changed_skus),
            "cost_updated": len(stock_diff.cost_changes) if stock_diff else 0,
            "stock_updated": len(stock_diff.stock_changes) if stock_diff else 0,
            "suspicious": len(diff.suspicious),
            "dirty_barcodes": len(diff.dirty),
            "customers_fetched": len(customer_rows),
            "customers_inserted": 0,
            "customers_updated": 0,
            "customers_suspicious": len(cust_diff.suspicious),
            "backup_dir": "",
            "applied": False,
        }

        if not changed_skus and not cust_diff.changed:
            stats["total_seconds"] = round(time.time() - t0, 3)
            return stats

        if do_backup:
            phase(PHASE_BACKUP)
            stats["backup_dir"] = backup_wujin(db_path, root)

        phase(PHASE_APPLY)
        index_created = ensure_unique_index(conn)
        applied = apply_diff(conn, diff)
        stats["index_created"] = index_created
        stats["inserted_rows"] = applied.inserted
        stats["updated_rows"] = applied.updated
        stats["fts_rebuilt"] = applied.fts_rebuilt
        if stock_diff is not None and stock_rows is not None:
            stock_applied = apply_stock(conn, stock_diff, stock_rows, apply=True)
            stats["cost_updated_applied"] = stock_applied.cost_updated
            stats["stock_updated_applied"] = stock_applied.stock_updated
            stats["snapshot_rows"] = stock_applied.snapshot_rows
        ensure_customers_table(conn)
        cust_applied = apply_customers(conn, cust_diff)
        stats["customers_inserted"] = cust_applied.inserted
        stats["customers_updated"] = cust_applied.updated
        conn.commit()
        stats["applied"] = True
        stats["total_seconds"] = round(time.time() - t0, 3)
        return stats
    finally:
        conn.close()


# ---------------------------------------------------------------------------
# 单飞作业壳（进程内）
# ---------------------------------------------------------------------------

_state_lock = threading.Lock()
_state: Dict[str, Any] = {
    "running": False,
    "phase": PHASE_IDLE,
    "started_at_ms": 0,
    "finished_at_ms": 0,
    "ok": None,
    "error": "",
    "stats": {},
    "server_time_ms": 0,
    "elapsed_seconds": 0,
    # S17 进度条：done/total 只在能给出真实计数时非空（目前=商品分页），
    # 其它阶段为 None，APP 按阶段权重画进度。
    "progress": {"phase": PHASE_IDLE, "done": None, "total": None},
}


def get_status(status_path: Optional[str] = None) -> Dict[str, Any]:
    """当前状态。

    进程内一旦有过作业（started/finished 非零）就以内存态为准；
    只有**冷启动**（进程重启后还没跑过任何作业）才回落到磁盘最后一次状态，
    且此时「正在运行」必然为假（进程都重启了，不存在活着的作业）。
    """
    with _state_lock:
        snap = dict(_state)
    cold = not snap.get("started_at_ms") and not snap.get("finished_at_ms")
    if cold and status_path:
        root = os.path.dirname(os.path.realpath(status_path))
        try:
            disk_file = safe_path(status_path, root)
        except ValueError:
            disk_file = ""
        if disk_file and os.path.exists(disk_file):
            try:
                with open(disk_file, "r", encoding="utf-8") as fh:
                    disk = json.load(fh)
                if isinstance(disk, dict):
                    disk["running"] = False
                    disk["server_time_ms"] = _now_ms()
                    return disk
            except (OSError, ValueError):
                pass
    snap["server_time_ms"] = _now_ms()
    return snap


def _persist(status_path: Optional[str]) -> None:
    if not status_path:
        return
    root = os.path.dirname(os.path.realpath(status_path))
    try:
        with _state_lock:
            payload = dict(_state)
        target = safe_path(status_path, root)
        tmp = safe_path(target + ".tmp", root)
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, ensure_ascii=False, indent=2)
        os.replace(tmp, target)
    except (OSError, ValueError) as exc:  # pragma: no cover - 磁盘异常不阻断同步
        logger.warning("同步状态落盘失败：%s", exc)


def start_sync(
    fetcher: Optional[Callable[[], UpstreamFetch]] = None,
    db_path: Optional[str] = None,
    root: Optional[str] = None,
    status_path: Optional[str] = None,
    do_backup: bool = True,
) -> Dict[str, Any]:
    """启动一次真实上游同步（单飞）。返回 ``{"starting": bool, ...status}``。"""
    db_path = db_path or default_db_path()
    root = root or _repo_root()
    status_path = status_path or status_path_for(db_path)

    with _state_lock:
        if _state.get("running"):
            return {"starting": False, "already_running": True, **dict(_state)}
        _state.update({
            "running": True,
            "phase": PHASE_FETCH,
            "started_at_ms": _now_ms(),
            "finished_at_ms": 0,
            "ok": None,
            "error": "",
            "stats": {},
            "server_time_ms": _now_ms(),
            "elapsed_seconds": 0,
            "progress": {"phase": PHASE_FETCH, "done": 0, "total": None},
        })
    _persist(status_path)

    def progress(name: str, done: Optional[int] = None, total: Optional[int] = None) -> None:
        with _state_lock:
            _state["phase"] = name
            _state["server_time_ms"] = _now_ms()
            _state["progress"] = {"phase": name, "done": done, "total": total}
        _persist(status_path)

    def job() -> None:
        started = time.time()
        try:
            stats = run_once(
                fetcher or (lambda: fetch_upstream(progress=progress)),
                db_path=db_path, root=root, progress=progress, do_backup=do_backup,
            )
            ok, error = True, ""
        except Exception as exc:  # noqa: BLE001 - 作业失败必须落状态，不能掀线程
            logger.exception("上游同步失败")
            stats, ok, error = {}, False, str(exc)
        elapsed = time.time() - started
        with _state_lock:
            _state.update({
                "running": False,
                "phase": PHASE_DONE if ok else PHASE_FAILED,
                "finished_at_ms": _now_ms(),
                "ok": ok,
                "error": error,
                "stats": stats,
                "server_time_ms": _now_ms(),
                "elapsed_seconds": round(elapsed, 3),
            })
        _persist(status_path)

    threading.Thread(target=job, name="upstream-sync", daemon=True).start()
    return {"starting": True, **get_status()}
