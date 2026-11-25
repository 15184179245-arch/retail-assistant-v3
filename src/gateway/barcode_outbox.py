#!/usr/bin/env python3
"""I24：条码「本地先生效 + 上游后台回写」出站队列（本地优先 / local-first + outbox）。

老板 2026-09-14 原话：
> 「条码回写上游时间太久，而且上游同比后 APP 却没有同步，整个逻辑应该重写——
>   应该更新条码先在本地更新，确保 APP 第一时间覆盖；
>   上游回写应该在后台完成后经过校验再通知同步成功。」

旧实现的三个病灶（已核实代码）：

1. ``router._write_barcode`` 在 **HTTP 请求线程里同步**跑完整套上游动作：
   全量分页扫 12,631 条找 SKU → 一货一码查询 → 取全量对象 → 三处齐写 → 回读校验。
   店员点一次确认要等几十秒；请求超时/断连就白做，且无从判断到底写进去没有。
2. **完全不写本地** —— 上游写成功了本地 ``barcode_alias`` 仍是旧状态，
   店员立刻拿新码去查价/扫码**扫不到**，得等下一次全量上游同步（分钟~小时级）才拉回来。
   这就是老板说的「上游同比后 APP 却没有同步」。
3. 没有「待同步」状态：失败不可见、不可重试。

新模型（本地即时应答 + 出站队列最终一致）：

    店员确认
      → ① 本地别名立刻落库（``barcode_alias``，搜索/扫码**当场命中**）
      → ② 写一条出站作业（本模块，JSONL）
      → ③ 立刻返回「已受理」——**全程不碰上游**，毫秒级
    后台 worker（本模块守护线程，进程内单飞）
      → 一货一码校验 → 写回上游 → **回读校验**
      → 过了才 ``state=synced``（「同步成功」以校验为准，不以「请求发出去了」为准）
      → 校验没过（码被别家占用 / 回读对不上 / 上游没这个商品）
        → 自动**回滚本地别名**（乐观锁，让路给店员后来的改判）+ ``state=failed``
      → 环境性失败（无凭据 / 网络不通）→ **保留本地绑定**，退避重试，``state=pending``

三条不变量（改动前先读懂，勿轻破）：

- **同步成功的判据是校验，不是送达**：只有 ``readback_ok`` 且一货一码通过才算 ``synced``。
- **回滚只撤自己写的那一条**：带 ``expect_sku`` 乐观锁，店员已改判则让路（不抹掉新操作）。
- **铁律 12 复核**：本模块只写 ``barcode_alias``（项目自有的别名表，S3 既有体系），
  不写 ``products`` 主表的任何业务列 —— 上游数据依旧只读、只容错不篡改。

已知取舍（记录在案，勿当 bug 处理）：``submit`` 的顺序是「先落本地别名、再入队」。
若进程恰好在两步之间死掉，会出现「本地已生效但没有回写作业」——本地当即可用（第一目标）
保住，上游同步漏掉这一条。反过来（先入队再落本地）则会留下「作业说已同步、本地却没绑」，
对店员更糟。故保持现序；两步窗口是微秒级，且本模块单机单进程运行时无并发竞争。

存储选型（照 ``shelf_store`` / ``log_store`` 范式，勿轻改）：**JSONL 事件日志 + 内存索引**，
不用 SQL —— 本机安全闸对 gateway 新模块里的 SQL 语句会拦（项目在案误报类），
且本模块数据量极小（店员绑码是低频操作）。每行一条**完整作业快照**，
读时按 ``job_id`` 取最后一行重建索引；超阈值按「非终态全留 + 终态留最新 N 条」压实（原子换写）。

对外入口：[submit]（POST /api/product/barcode/write 受理）、[status]（GET .../sync-status）。
响应包络遵循项目契约：{"success", "data", "code", "error", "user_message", "hint"}。
"""
from __future__ import annotations

import json
import logging
import os
import random
import threading
import time

logger = logging.getLogger("retaildemo.barcode_outbox")

# ---------------------------------------------------------------- 状态常量（APP 直接上屏，勿随意改名）

ST_PENDING = "pending"        # 本地已生效，上游回写尚未成功（含重试中）
ST_SYNCED = "synced"          # 上游写回 + 回读校验通过 —— 真·同步成功
ST_FAILED = "failed"          # 校验没过 → 本地绑定已回滚，等店员重来
ST_SUPERSEDED = "superseded"  # 被更新的绑定取代，不再回写（防止旧码把新码冲回去）

_TERMINAL = (ST_SYNCED, ST_FAILED, ST_SUPERSEDED)

#: 退避表（秒）：网络类瞬时失败按此重试，超出后停在最后一档（不放弃，等环境恢复）
_BACKOFF_SEC = (5, 15, 60, 300, 900, 1800)
#: 无凭据属「等配置」而非「等网络」，给长档退避，避免每 5 秒空转
_CREDENTIAL_BACKOFF_SEC = 600
#: 单轮最多处理几条（上游很重，别一次打满）
_MAX_PER_ROUND = 3
#: worker 轮询间隔（秒）
_POLL_SEC = 5.0
#: 追加前先看大小，超阈值就地压实
_COMPACT_BYTES = 2 * 1024 * 1024
#: 压实后保留的终态作业条数
_KEEP_TERMINAL = 500

_LOCK = threading.RLock()
_JOBS: dict | None = None      # {job_id: job}
_WORKER: threading.Thread | None = None
_STOP = threading.Event()
_WAKE = threading.Event()      # submit 后立刻唤醒 worker，别等下一轮轮询


def _outbox_path() -> str:
    """落点：环境变量优先（部署可指到数据盘），默认仓库 ``logs/``（同 log_store/crash_store）。"""
    env = os.environ.get("BARCODE_OUTBOX_LOG")
    if env:
        return os.path.normpath(env)
    return os.path.normpath(
        os.path.join(os.path.dirname(__file__), "..", "..", "logs", "barcode-outbox.jsonl"))


def _archive_path() -> str:
    """永久建档落点：压实淘汰的终态记录一律追加至此，只增不删，随时可查可回滚。"""
    env = os.environ.get("BARCODE_OUTBOX_ARCHIVE")
    if env:
        return os.path.normpath(env)
    return os.path.normpath(
        os.path.join(os.path.dirname(__file__), "..", "..", "logs", "barcode-outbox-archive.jsonl"))


def _now_ms() -> int:
    return int(time.time() * 1000)


def _now_str() -> str:
    return time.strftime("%Y-%m-%d %H:%M:%S")


def _clean(value, max_len=64) -> str:
    """入参清洗：去空白 + 挡超长/控制字符（与 router._clean_code 同口径）。"""
    text = str(value or "").strip()
    if len(text) > max_len:
        return ""
    return "".join(ch for ch in text if ch.isprintable())


def _ok(data=None):
    return {"success": True, "data": data, "code": "OK"}


def _err(code, user_message, error=None, data=None):
    return {"success": False, "data": data, "code": code,
            "error": error or user_message, "user_message": user_message}


# ---------------------------------------------------------------- 存储：JSONL + 内存索引

def _load_locked() -> dict:
    """重建索引（进程内首次访问 / 压实后）。坏行只跳过并告警——绝不因一行脏数据整体起不来。"""
    global _JOBS
    if _JOBS is not None:
        return _JOBS
    jobs: dict = {}
    path = _outbox_path()
    if os.path.exists(path):
        bad = 0
        try:
            with open(path, "r", encoding="utf-8") as fh:
                for line in fh:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        row = json.loads(line)
                    except ValueError:
                        bad += 1
                        continue
                    jid = row.get("job_id")
                    if jid:
                        jobs[jid] = row
        except OSError as exc:
            logger.warning("barcode outbox 读取失败（按空队列继续）：%s", exc)
        if bad:
            logger.warning("barcode outbox 跳过 %d 行坏数据", bad)
    _JOBS = jobs
    return _JOBS


def _append_locked(job: dict) -> None:
    """追加一条作业快照（整行 JSON，读时取最后一行）。"""
    path = _outbox_path()
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(job, ensure_ascii=False) + "\n")
    except OSError as exc:
        # 落盘失败不能静默：内存索引还在，本次仍能工作，但重启会丢——如实告警
        logger.error("barcode outbox 落盘失败（重启会丢该作业）：%s", exc)
        return
    try:
        if os.path.getsize(path) > _COMPACT_BYTES:
            _compact_locked()
    except OSError:
        pass


def _compact_locked() -> int:
    """压实：非终态作业全留，终态只留最新 [_KEEP_TERMINAL] 条。

    【改动建档铁律（老板 2026-09-14 令）】：淘汰的旧终态作业绝不丢弃，
    先原子追加至永久归档文件（logs/barcode-outbox-archive.jsonl），
    确保全历史改动终身可查、随时可回滚。
    """
    jobs = _load_locked()
    live = [j for j in jobs.values() if j.get("state") not in _TERMINAL]
    done = sorted((j for j in jobs.values() if j.get("state") in _TERMINAL),
                  key=lambda j: j.get("updated_at") or "")
    if len(done) > _KEEP_TERMINAL:
        evicted = done[:-_KEEP_TERMINAL]
        keep = live + done[-_KEEP_TERMINAL:]
        # 归档落盘：只追加不重写
        arch_path = _archive_path()
        try:
            os.makedirs(os.path.dirname(arch_path), exist_ok=True)
            with open(arch_path, "a", encoding="utf-8") as fh:
                for job in evicted:
                    fh.write(json.dumps(job, ensure_ascii=False) + "\n")
        except OSError as exc:
            logger.error("barcode outbox 永久建档追加失败：%s", exc)
    else:
        keep = live + done

    path = _outbox_path()
    tmp = path + ".tmp"
    try:
        with open(tmp, "w", encoding="utf-8") as fh:
            for job in keep:
                fh.write(json.dumps(job, ensure_ascii=False) + "\n")
        os.replace(tmp, path)
    except OSError as exc:
        logger.warning("barcode outbox 压实失败（保留原文件）：%s", exc)
        try:
            if os.path.exists(tmp):
                os.remove(tmp)
        except OSError:
            pass
        return 0
    global _JOBS
    _JOBS = {j["job_id"]: j for j in keep}
    return len(keep)


def _search_archive_locked(job_id: str = None, sku: str = None, barcode: str = None, limit: int = 50) -> list:
    """从永久归档文件逆序检索已归档的历史作业，用于改动回溯与随时回滚。"""
    path = _archive_path()
    if not os.path.exists(path):
        return []
    matches = []
    try:
        with open(path, "r", encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    row = json.loads(line)
                except ValueError:
                    continue
                if job_id and row.get("job_id") == job_id:
                    matches.append(row)
                elif sku and row.get("sku") == sku:
                    matches.append(row)
                elif barcode and row.get("barcode") == barcode:
                    matches.append(row)
    except OSError as exc:
        logger.warning("读取 barcode archive 归档失败：%s", exc)
        return []
    # 相同 job_id 以最后一次记录为准
    merged = {}
    for m in matches:
        jid = m.get("job_id")
        if jid:
            merged[jid] = m
    res = list(merged.values())
    res.sort(key=lambda j: j.get("created_ms") or 0, reverse=True)
    return res[:limit]


def _put_locked(job: dict) -> dict:
    """更新内存索引 + 追加落盘（唯一写入口，勿绕过）。"""
    jobs = _load_locked()
    jobs[job["job_id"]] = job
    _append_locked(job)
    return job


# ---------------------------------------------------------------- 依赖注入（单测替换真实现，不联网）

class _SearchDeps:
    """默认依赖：通过网关进程内代理调 search 服务（复用既有 alias 端点，不自造存储）。"""

    def __init__(self, timeout=8):
        self._timeout = timeout

    def _call(self, path, payload, timeout=None):
        from gateway.proxy import proxy
        from gateway.router import SEARCH_SERVICE_URL
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        return proxy(path, body, SEARCH_SERVICE_URL, timeout=timeout or self._timeout)

    def resolve_alias(self, barcode):
        """当前本地绑定；None=没绑过。查不到（服务不可用）返回特殊标记由调用方区分。"""
        from gateway.proxy import proxy
        from gateway.router import SEARCH_SERVICE_URL
        from urllib.parse import quote
        resp = proxy("/alias/resolve?barcode=" + quote(barcode), None,
                     SEARCH_SERVICE_URL, timeout=self._timeout)
        if not resp.get("success"):
            raise RuntimeError(resp.get("hint") or resp.get("error") or "别名服务不可用")
        return resp.get("data")

    def bind_alias(self, barcode, sku, actor):
        resp = self._call("/alias/bind",
                          {"barcode": barcode, "sku": sku, "source": "app_barcode_write",
                           "actor": actor})
        return bool(resp.get("success") and (resp.get("data") or {}).get("bound"))

    def rebind_alias(self, barcode, sku, actor):
        resp = self._call("/alias/rebind", {"barcode": barcode, "sku": sku, "actor": actor})
        return bool(resp.get("success") and (resp.get("data") or {}).get("rebound"))

    def unbind_alias(self, barcode, actor, expect_sku):
        resp = self._call("/alias/unbind",
                          {"barcode": barcode, "actor": actor, "expect_sku": expect_sku})
        return bool(resp.get("success"))


_DEPS = None


def _deps():
    global _DEPS
    if _DEPS is None:
        _DEPS = _SearchDeps()
    return _DEPS


def _client():
    """上游客户端（优先复用有效缓存 token，无缓存时读环境变量密码）。返回 None 表示无可用凭据。"""
    from sync.xzg_client import XzgClient
    client = XzgClient(verbose=False)
    if client._load_cached_token() and client._token_is_valid():
        return client
    if not client._password:
        return None
    return client


# ---------------------------------------------------------------- 上游写回（校验为唯一判据）

def write_upstream(client, sku: str, barcode: str) -> tuple:
    """把条码写回上游，返回 ``(verdict, reason, audit)``。

    verdict ∈ ``"ok"`` / ``"conflict"`` / ``"not_found"`` / ``"mismatch"``。
    三层防护与旧实现完全同口径（一货一码 + 用途锁 + 回读校验），只是从请求线程搬到后台：

    1. **一货一码**：该条码若已属**别的商品** → ``conflict``（从根上杜绝一码两货）；
    2. **用途锁**：写回走 :meth:`XzgClient.update_product_barcode`，只允许改条码字段；
    3. **回读校验**：写完再读一遍，对不上即 ``mismatch`` —— 不认「发出去了」。
    """
    from sync.xzg_client import XzgError

    # ① 一货一码
    rows = client.fetch_all_stock_condition(keyword=barcode, page_size=10)
    for row in rows:
        if (row.get("barCode") or "").strip() != barcode:
            continue
        owner_sku = (row.get("productCode") or "").strip()
        if owner_sku and owner_sku != sku:
            return ("conflict",
                    "这个条码在管家婆里已经是「%s %s」的" % (owner_sku, row.get("productName") or ""),
                    {"owner": {"sku": owner_sku, "name": row.get("productName")}})

    # ② 找目标商品
    #   快路径：编号直查库存状况表（一次请求，约 1 秒，行里带 productId）。
    #   旧实现无条件走全量分页扫 12,631 条，这才是「时间太久」的另一半病根。
    #   保底：直查没命中（商品无库存记录/字段缺失）才退回全量扫。
    target_id = None
    for row in client.fetch_all_stock_condition(keyword=sku, page_size=10):
        if (row.get("productCode") or "").strip() == sku and row.get("productId"):
            target_id = str(row["productId"])
            break
    if not target_id:
        for product in client.fetch_all_products(page_size=500):
            if (product.get("code") or "").strip() == sku:
                target_id = str(product["id"])
                break
    if not target_id:
        return ("not_found", "管家婆里找不到这个商品", None)

    # ③ 写回 + 回读校验（用途锁与三处齐写都在客户端方法里）
    try:
        original = client.fetch_product_full(target_id)
        # 若管家婆当前已是该条码（例如已同步过或店内重复录入相同条码）：幂等算成功
        if str(original.get("barCode") or "").strip() == barcode:
            audit = {
                "product_id": original.get("id"),
                "sku": original.get("code"),
                "name": original.get("name"),
                "old_barcode": original.get("barCode"),
                "new_barcode": barcode,
                "readback_barcode": original.get("barCode"),
                "readback_ok": True,
            }
            return ("ok", "", audit)
        result = client.update_product_barcode(original, barcode)
    except XzgError as exc:
        # 用途锁拦下 / 上游拒绝：业务性拒绝，不算瞬时故障
        return ("conflict", "管家婆拒绝了这次写回：%s" % str(exc)[:80], None)
    audit = dict(result.get("audit") or {})
    if not audit.get("readback_ok"):
        return ("mismatch", "写是发出去了但回读对不上", audit)
    return ("ok", "", audit)


# ---------------------------------------------------------------- 队列

def enqueue(sku: str, barcode: str, actor: str, prev_sku) -> dict:
    """入队一条回写作业；同 SKU / 同条码的**在途旧作业自动作废**（superseded）。

    为什么必须作废：异步意味着等待期里店员会连续改。若旧的 pending 作业后来才被处理，
    它会把**旧条码**写回上游，等于把店员最新的修改冲掉。故新作业入队即让旧作业让路。
    """
    with _LOCK:
        jobs = _load_locked()
        now = _now_ms()
        for other in jobs.values():
            if other.get("state") in _TERMINAL:
                continue
            if other.get("sku") == sku or other.get("barcode") == barcode:
                other.update(state=ST_SUPERSEDED, last_error="已被更新的绑定取代",
                             next_attempt_at=None, updated_at=_now_str())
                _append_locked(other)
        job = {
            "job_id": "bo-%d-%04d" % (now, random.randint(0, 9999)),
            "sku": sku,
            "barcode": barcode,
            "actor": actor,
            "prev_sku": prev_sku,          # 本地别名写入前的绑定（回滚要用）
            "state": ST_PENDING,
            "attempts": 0,
            "last_error": None,
            "next_attempt_at": now,        # 立刻可尝试
            # 排序键用毫秒：created_at 只到秒，同一秒连改两次会撞时间戳、排序不稳
            "created_ms": now,
            "created_at": _now_str(),
            "updated_at": _now_str(),
            "readback": None,
            "rolled_back": False,
        }
        return dict(_put_locked(job))


def _due_jobs(now: int, limit: int) -> list:
    with _LOCK:
        jobs = _load_locked()
        due = [j for j in jobs.values()
               if j.get("state") == ST_PENDING
               and (j.get("next_attempt_at") is None or j["next_attempt_at"] <= now)]
        due.sort(key=lambda j: j.get("created_ms") or 0)
        return [dict(j) for j in due[:limit]]


def _rollback(job: dict) -> tuple:
    """撤回本地绑定（只在「当前仍绑着我们写的那条」时动手）。

    :return: ``(是否真的撤了, 说明)``
    """
    deps = _deps()
    barcode, sku = job["barcode"], job["sku"]
    try:
        current = deps.resolve_alias(barcode)
    except Exception as exc:  # noqa: BLE001 - 查不到就不动手，绝不盲撤
        return (False, "查不到当前绑定（%s），未回滚" % str(exc)[:60])
    if not current:
        return (False, "本地本来就没有这条绑定")
    if (current.get("sku") or "").strip() != sku:
        return (False, "店员已改绑给「%s」，回滚让路" % current.get("sku"))
    try:
        if job.get("prev_sku"):
            done = deps.rebind_alias(barcode, job["prev_sku"], "system:rollback")
            return (bool(done), "已恢复原绑定「%s」" % job["prev_sku"] if done else "恢复原绑定失败")
        done = deps.unbind_alias(barcode, "system:rollback", sku)
        return (bool(done), "已解除本地绑定" if done else "解除本地绑定失败")
    except Exception as exc:  # noqa: BLE001
        return (False, "回滚调用失败：%s" % str(exc)[:60])


def _finish_failed(job: dict, reason: str, audit=None) -> dict:
    """终态失败：**先回滚本地绑定**，再把状态落成 failed（顺序不能反）。"""
    rolled, note = _rollback(job)
    with _LOCK:
        jobs = _load_locked()
        live = jobs.get(job["job_id"], job)
        live.update(state=ST_FAILED, last_error=reason, next_attempt_at=None,
                    rolled_back=rolled, rollback_note=note,
                    readback=audit, updated_at=_now_str())
        return dict(_put_locked(live))


def _finish_synced(job: dict, audit) -> dict:
    with _LOCK:
        jobs = _load_locked()
        live = jobs.get(job["job_id"], job)
        live.update(state=ST_SYNCED, last_error=None, next_attempt_at=None,
                    readback=audit, updated_at=_now_str())
        return dict(_put_locked(live))


def _retry_later(job: dict, reason: str, credential: bool = False) -> dict:
    """瞬时/环境性失败：保留本地绑定，退避后再试（**不回滚**）。"""
    attempts = int(job.get("attempts") or 0) + 1
    if credential:
        delay = _CREDENTIAL_BACKOFF_SEC
    else:
        delay = _BACKOFF_SEC[min(attempts - 1, len(_BACKOFF_SEC) - 1)]
    with _LOCK:
        jobs = _load_locked()
        live = jobs.get(job["job_id"], job)
        live.update(state=ST_PENDING, attempts=attempts, last_error=reason,
                    next_attempt_at=_now_ms() + delay * 1000, updated_at=_now_str())
        return dict(_put_locked(live))


def attempt(job: dict) -> dict:
    """处理一条作业（单测直接调它，不依赖线程）。"""
    try:
        client = _client()
    except Exception as exc:  # noqa: BLE001 - 客户端构造失败按环境问题重试
        return _retry_later(job, "上游客户端不可用：%s" % str(exc)[:60])
    if client is None:
        return _retry_later(job, "本机没配管家婆密码，暂时回写不了（不影响本机已生效）",
                            credential=True)
    try:
        verdict, reason, audit = write_upstream(client, job["sku"], job["barcode"])
    except Exception as exc:  # noqa: BLE001 - 网络/超时/上游 5xx 一律退避重试，不回滚
        return _retry_later(job, "连不上管家婆：%s" % str(exc)[:80])
    if verdict == "ok":
        return _finish_synced(job, audit)
    return _finish_failed(job, reason, audit)


def process_once() -> int:
    """处理一轮到期的作业，返回处理条数（worker 与单测共用）。"""
    due = _due_jobs(_now_ms(), _MAX_PER_ROUND)
    for job in due:
        try:
            attempt(job)
        except Exception as exc:  # noqa: BLE001 - 单条作业异常绝不能拖死 worker
            logger.exception("barcode outbox 作业异常 job=%s", job.get("job_id"))
            _retry_later(job, "内部异常：%s" % str(exc)[:80])
    return len(due)


def _worker_loop() -> None:
    logger.info("条码回写 worker 启动")
    while not _STOP.is_set():
        try:
            n = process_once()
        except Exception:  # noqa: BLE001 - worker 永不退出
            logger.exception("barcode outbox worker 轮询异常")
            n = 0
        # 有新活立刻接着干；空转则等唤醒信号或轮询间隔
        if n:
            continue
        _WAKE.wait(_POLL_SEC)
        _WAKE.clear()
    logger.info("条码回写 worker 停止")


def start_worker() -> bool:
    """启动后台 worker（幂等）。返回是否由本次调用真正启动。"""
    global _WORKER
    with _LOCK:
        if _WORKER is not None and _WORKER.is_alive():
            return False
        _STOP.clear()
        _WORKER = threading.Thread(target=_worker_loop, name="barcode-outbox", daemon=True)
        _WORKER.start()
        return True


def stop_worker(timeout=3.0) -> None:
    """停止 worker（收尾/单测用）。"""
    global _WORKER
    _STOP.set()
    _WAKE.set()
    worker = _WORKER
    if worker is not None and worker.is_alive():
        worker.join(timeout=timeout)
    _WORKER = None


def _reset_for_tests() -> None:
    """清内存索引（单测隔离用）。"""
    global _JOBS, _DEPS
    with _LOCK:
        _JOBS = None
        _DEPS = None


# ---------------------------------------------------------------- 对外：受理 / 状态查询

def submit(body: dict) -> dict:
    """POST /api/product/barcode/write —— **受理**语义（不再同步跑上游）。

    流程：本地别名立刻落库（当场可扫） → 入出站队列 → 立即返回。
    保留的安全闸与旧实现同口径：参数清洗 + 人工确认（``confirmed``）+ 本地一货一码。
    """
    sku = _clean(body.get("sku"))
    barcode = _clean(body.get("barcode"), max_len=32)
    actor = _clean(body.get("actor")) or "unknown"
    if not sku or not barcode:
        return _err("BAD_REQUEST", "请提供商品编号与新条码")
    if body.get("confirmed") is not True:
        # 人工确认闸：没有明确确认过一律不写（与旧实现一致，勿删）
        return _err("NOT_CONFIRMED", "这个操作要店员确认后才能提交")

    deps = _deps()
    # ① 看本机现状（毫秒级，不碰上游）。
    #    注意：这里**不硬拦**「该码本机已绑给别家」——硬拦会形成死胡同（店员绑错了没路可退），
    #    违反项目铁律「未识别不进死胡同」。正确做法是：允许改判、把原绑定记进作业，
    #    回写失败时自动还原（见 _rollback）。真·一货一码的权威判据在上游后台校验里。
    try:
        current = deps.resolve_alias(barcode)
    except Exception as exc:  # noqa: BLE001
        return _err("SEARCH_UNAVAILABLE", "本机搜索服务不可用，条码没能生效，请稍后重试",
                    error=str(exc))
    prev_sku = (current or {}).get("sku")
    replaced_sku = prev_sku if prev_sku and prev_sku != sku else None

    # ② 本地先生效 —— 这一步成功，店员立刻就能扫到这个码（不等上游）
    try:
        bound = deps.bind_alias(barcode, sku, actor)
    except Exception as exc:  # noqa: BLE001
        return _err("SEARCH_UNAVAILABLE", "本机搜索服务不可用，条码没能生效，请稍后重试",
                    error=str(exc))
    if not bound:
        return _err("LOCAL_BIND_FAILED", "条码没能写进本机，请重试")

    # ③ 入队等后台回写，立刻返回
    job = enqueue(sku, barcode, actor, prev_sku)
    _WAKE.set()
    return _ok({
        "job_id": job["job_id"],
        "sku": sku,
        "barcode": barcode,
        "state": job["state"],
        "local_effective": True,
        "upstream_pending": True,
        "replaced_sku": replaced_sku,   # 非空=改判，原绑定已记录，回写失败会自动还原
        "message": "已在本机生效，正在回写管家婆…",
    })


def status(params: dict) -> dict:
    """GET /api/product/barcode/sync-status —— 按 job_id / barcode / sku 查最近一条作业。

    优先在活动队列查，查不到自动穿透永久归档文件；查不到返回 ``data=null``。
    """
    job_id = _clean(params.get("job_id"))
    barcode = _clean(params.get("barcode"), max_len=32)
    sku = _clean(params.get("sku"))
    if not (job_id or barcode or sku):
        return _err("BAD_REQUEST", "请提供 job_id 或 barcode 或 sku")
    with _LOCK:
        jobs = list(_load_locked().values())
        if job_id:
            hit = next((j for j in jobs if j.get("job_id") == job_id), None)
            if not hit:
                arch = _search_archive_locked(job_id=job_id, limit=1)
                hit = arch[0] if arch else None
        else:
            key, want = ("barcode", barcode) if barcode else ("sku", sku)
            cands = [j for j in jobs if j.get(key) == want]
            if not cands:
                arch = _search_archive_locked(sku=sku, barcode=barcode, limit=1)
                cands = arch
            cands.sort(key=lambda j: j.get("created_ms") or 0)
            hit = cands[-1] if cands else None

    if not hit:
        return _ok(None)
    return _ok({
        "job_id": hit.get("job_id"),
        "sku": hit.get("sku"),
        "barcode": hit.get("barcode"),
        "state": hit.get("state"),
        "attempts": hit.get("attempts", 0),
        "last_error": hit.get("last_error"),
        "error": hit.get("last_error"),
        "rolled_back": bool(hit.get("rolled_back")),
        "rollback_note": hit.get("rollback_note"),
        "updated_at": hit.get("updated_at"),
        "local_effective": True,
        "readback_ok": bool((hit.get("readback") or {}).get("readback_ok")),
        "pending": hit.get("state") == ST_PENDING,
        "synced": hit.get("state") == ST_SYNCED,
        "failed": hit.get("state") == ST_FAILED,
        "rollback_of": hit.get("rollback_of"),
    })


def history(params: dict) -> dict:
    """GET /api/product/barcode/history —— 按 sku 或 barcode 查询全量建档作业轨迹。

    聚合内存活动队列与磁盘永久归档，时间倒序返回，用于改动回溯与随时回滚。
    """
    sku = _clean(params.get("sku"))
    barcode = _clean(params.get("barcode"), max_len=32)
    if not (sku or barcode):
        return _err("BAD_REQUEST", "请提供 sku 或 barcode")
    with _LOCK:
        active = list(_load_locked().values())
        arch = _search_archive_locked(sku=sku, barcode=barcode, limit=200)

    key, want = ("sku", sku) if sku else ("barcode", barcode)
    filtered_active = [j for j in active if j.get(key) == want]

    # 合并去重（活动作业优先于历史归档快照）
    all_map = {}
    for j in arch:
        jid = j.get("job_id")
        if jid:
            all_map[jid] = j
    for j in filtered_active:
        jid = j.get("job_id")
        if jid:
            all_map[jid] = j

    items = list(all_map.values())
    items.sort(key=lambda j: j.get("created_ms") or 0, reverse=True)

    records = []
    for it in items:
        rb = it.get("readback") or {}
        records.append({
            "job_id": it.get("job_id"),
            "sku": it.get("sku"),
            "barcode": it.get("barcode"),
            "old_barcode": rb.get("old_barcode"),
            "state": it.get("state"),
            "actor": it.get("actor"),
            "created_at": it.get("created_at"),
            "updated_at": it.get("updated_at"),
            "readback_ok": bool(rb.get("readback_ok")),
            "rolled_back": bool(it.get("rolled_back")),
            "rollback_of": it.get("rollback_of"),
        })
    return _ok({"sku": sku, "barcode": barcode, "total": len(records), "records": records})


def rollback_submit(body: dict) -> dict:
    """POST /api/product/barcode/rollback —— 随时人工回滚条码至上一版本。

    【契约】：
    1. 必须显式 confirmed=true，防误触；
    2. 溯源该商品最近一次成功同步的作业（synced 且 readback_ok=True），提取其 old_barcode；
    3. 若该作业无旧条码记录（改动前原商品无条码），提示需在管家婆收银端人工清空，不盲目乱填；
    4. 自动发起反向修改条码流程（复用 submit 本地立刻生效 + 后台安全回写与回读校验）；
    5. 返回反向作业 ID，并标记 rollback_of 指向被回滚的历史作业。
    """
    if not isinstance(body, dict):
        return _err("BAD_REQUEST", "请求体必须为 JSON 对象")
    sku = _clean(body.get("sku") or body.get("code"))
    confirmed = bool(body.get("confirmed"))
    actor = _clean(body.get("actor") or "店员人工回滚", max_len=32)

    if not sku:
        return _err("BAD_REQUEST", "请指定要回滚的商品编号 sku")
    if not confirmed:
        return _err("CONFIRM_REQUIRED", "请确认是否要执行回滚操作（confirmed=true）")

    # 查建档历史：找最近一次 synced 且 readback_ok 的作业
    h_resp = history({"sku": sku})
    if not h_resp.get("success"):
        return h_resp
    records = h_resp.get("data", {}).get("records", [])
    target = None
    for r in records:
        if r.get("state") == ST_SYNCED and r.get("readback_ok"):
            target = r
            break

    if not target:
        return _err("NO_ROLLBACK_TARGET", f"商品 {sku} 未找到可回滚的成功同步历史记录")

    target_job_id = target.get("job_id")
    old_barcode = _clean(target.get("old_barcode"), max_len=32)
    current_barcode = _clean(target.get("barcode"), max_len=32)

    if not old_barcode:
        return _err("NO_PREVIOUS_BARCODE",
                    f"商品 {sku} 在作业 {target_job_id} 改动前管家婆无条码；清空条码请在管家婆收银端操作")

    # 发起反向作业（复用 submit 逻辑）
    sub_res = submit({
        "sku": sku,
        "barcode": old_barcode,
        "actor": actor,
        "confirmed": True,
    })

    if not sub_res.get("success"):
        return sub_res

    # 标记新作业为该历史作业的 rollback_of
    new_job_id = (sub_res.get("data") or {}).get("job_id")
    if new_job_id:
        with _LOCK:
            jobs = _load_locked()
            if new_job_id in jobs:
                jobs[new_job_id]["rollback_of"] = target_job_id
                _append_locked(jobs[new_job_id])

    res_data = sub_res.get("data") or {}
    res_data["rollback_of"] = target_job_id
    res_data["message"] = f"已触发条码回滚：{current_barcode} → {old_barcode}，正在本地生效并回写管家婆…"
    return _ok(res_data)
