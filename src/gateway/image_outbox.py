#!/usr/bin/env python3
"""I26：商品图片「APP 显式回传 + 上游后台回写」出站队列（照 I24 barcode_outbox 范式）。

老板 2026-09-14 原话：
> 「落地到 app 和后端服务器 app 本地缓存图片，保证质量的同时尽量压缩缩减同步时间，
>   同步过程参考新条码同步方案。」

链路契约 = 真机单条实测定案（``_reports/probe_image_roundtrip.py``，2026-09-14）：

    UpLoadImg {img_Type:1, img_Base64:"data:image/jpeg;base64,..."}
      → 返回相对路径 '/{企业id}/ProductImg/{UUID}.jpeg'
    BaseProduct/Update 把多图相对路径**分号串**写进 ``imgUrl``（写端点与条码回写同一个，已放行）

与条码同款的模型（本地优先 + 出站队列最终一致）：

    店员在详情页点「回传管家婆」并二次确认
      → ① 受理：毫秒级——只查本机 inventory 确认照片存在，**一次上游请求都不发**
      → ② 写一条出站作业（本模块，JSONL），立即返回「已受理」
    后台 worker（守护线程，进程内单飞）
      → 先定位上游商品（**在上传任何图片之前**——商品不存在就一张都不传，少留孤儿）
      → 逐张：本机文件 → Pillow 压缩（见〔压缩〕）→ dataURL → UpLoadImg
      → 合并 imgUrl（**保留上游已有条目、追加本次**，绝不覆盖别人配过的图）
      → update_product_imgurl（用途锁只许顶层 imgUrl）→ **回读校验**
      → 过了才 ``state=synced``；没过则尝试把 imgUrl 写回原值（撤引用）并 ``failed``
      → 环境性失败（无凭据 / 网络 / inventory 不可用）→ 退避重试，
        **已上传成功的 url 记在作业里，重试不重传**（UpLoadImg 的孤儿文件删不掉，宁少勿滥）

〔压缩〕老板点名「保质 + 缩同步时间」：
- 原图已是 JPEG、长边 ≤1280 且 ≤256KB → **原图直通**（不做二次劣化）；
- 否则 Pillow（项目已声明依赖；官方网页版逐张配图同为 quality≈0.7 口径）：
  长边缩到 ≤1280、q78 起步阶梯降到 55 兜底、目标 ≤256KB；PNG 统一转 JPEG。

三条不变量（改动前先读懂，勿轻破）：

- **同步成功的判据是回读校验，不是送达**（与 I24 同口径）。
- **上游图片存储只进不出**：UpLoadImg 无删除端点，回滚只能撤 ``imgUrl`` 引用，
  图片本体成孤儿文件——所以入口必须店员显式发起、默认只传主图、审计里如实留痕。
- **铁律 12 复核**：本模块对上游只写 ``imgUrl`` 一个字段（用途锁在客户端里结构挡住
  价格/条码/规格）；对店内 ``product_images`` / ``data/images`` **只读不写**。

存储选型与 barcode_outbox 完全一致：JSONL 事件日志 + 内存索引（本机安全闸对
gateway 新模块的 SQL 会拦，且本模块数据量极小）。
对外入口：[submit]（POST /api/product/image/push 受理）、[rollback_submit]（POST .../rollback）、
[status]（GET .../sync-status）。响应包络：{"success","data","code","error","user_message","hint"}。
"""
from __future__ import annotations

import base64
import json
import logging
import os
import random
import re
import threading
import time

logger = logging.getLogger("retaildemo.image_outbox")

# ---------------------------------------------------------------- 状态常量（与 barcode_outbox 同名，APP 直接上屏）

ST_PENDING = "pending"        # 已受理，上游回写尚未成功（含重试中）
ST_SYNCED = "synced"          # 上游写回 + 回读校验通过 —— 真·同步成功
ST_FAILED = "failed"          # 业务性失败（找不到商品/图、上游拒绝、回读对不上）
ST_SUPERSEDED = "superseded"  # 被更新的操作取代，不再执行

_TERMINAL = (ST_SYNCED, ST_FAILED, ST_SUPERSEDED)

#: 退避表（秒）：网络类瞬时失败按此重试，超出后停在最后一档（不放弃，等环境恢复）
_BACKOFF_SEC = (5, 15, 60, 300, 900, 1800)
#: 无凭据属「等配置」而非「等网络」，给长档退避，避免每 5 秒空转
_CREDENTIAL_BACKOFF_SEC = 600
#: 单轮最多处理几条（图片回写含上传+更新+回读，比条码更重，给得更少）
_MAX_PER_ROUND = 2
#: worker 轮询间隔（秒）
_POLL_SEC = 5.0
#: 追加前先看大小，超阈值就地压实
_COMPACT_BYTES = 2 * 1024 * 1024
#: 压实后保留的终态作业条数
_KEEP_TERMINAL = 500
#: 单次回传最多几张（防误发批量；宁少勿滥——上游图片文件删不掉）
_MAX_IMAGES_PER_PUSH = 9

# ---- 压缩口径（〔压缩〕见模块 docstring）
_MAX_SIDE = 1280
_TARGET_BYTES = 256 * 1024
_QUALITY_LADDER = (78, 70, 62, 55)

#: 文件名白名单：与 gateway.handler._IMAGE_NAME_RE 同口径（inventory 生成的都是这种名）
_NAME_RE = re.compile(r"[A-Za-z0-9_\-]{1,96}\.(jpg|jpeg|png|gif|webp)", re.IGNORECASE)

_LOCK = threading.RLock()
_JOBS: dict | None = None      # {job_id: job}
_WORKER: threading.Thread | None = None
_STOP = threading.Event()
_WAKE = threading.Event()      # submit 后立刻唤醒 worker，别等下一轮轮询


def _outbox_path() -> str:
    """落点：环境变量优先（部署可指到数据盘），默认仓库 ``logs/``（同 barcode_outbox）。"""
    env = os.environ.get("IMAGE_OUTBOX_LOG")
    if env:
        return os.path.normpath(env)
    return os.path.normpath(
        os.path.join(os.path.dirname(__file__), "..", "..", "logs", "image-outbox.jsonl"))


def _archive_path() -> str:
    """永久建档落点：压实淘汰的终态记录一律追加至此，只增不删，随时可查可回滚。"""
    env = os.environ.get("IMAGE_OUTBOX_ARCHIVE")
    if env:
        return os.path.normpath(env)
    return os.path.normpath(
        os.path.join(os.path.dirname(__file__), "..", "..", "logs", "image-outbox-archive.jsonl"))


def _now_ms() -> int:
    return int(time.time() * 1000)


def _now_str() -> str:
    return time.strftime("%Y-%m-%d %H:%M:%S")


def _clean(value, max_len=64) -> str:
    """入参清洗：去空白 + 挡超长/控制字符（与 barcode_outbox 同口径）。"""
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
# （与 barcode_outbox 同一套范式：坏行只跳过、整行快照、读时取最后一条、原子压实）

def _load_locked() -> dict:
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
            logger.warning("image outbox 读取失败（按空队列继续）：%s", exc)
        if bad:
            logger.warning("image outbox 跳过 %d 行坏数据", bad)
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
        logger.error("image outbox 落盘失败（重启会丢该作业）：%s", exc)
        return
    try:
        if os.path.getsize(path) > _COMPACT_BYTES:
            _compact_locked()
    except OSError:
        pass


def _compact_locked() -> int:
    """压实：非终态作业全留，终态只留最新 [_KEEP_TERMINAL] 条。

    【改动建档铁律（老板 2026-09-14 令）】：淘汰的旧终态作业绝不丢弃，
    先原子追加至永久归档文件（logs/image-outbox-archive.jsonl），
    确保全历史改动终身可查、随时可回滚。
    """
    jobs = _load_locked()
    live = [j for j in jobs.values() if j.get("state") not in _TERMINAL]
    done = sorted((j for j in jobs.values() if j.get("state") in _TERMINAL),
                  key=lambda j: j.get("updated_at") or "")
    if len(done) > _KEEP_TERMINAL:
        evicted = done[:-_KEEP_TERMINAL]
        keep = live + done[-_KEEP_TERMINAL:]
        arch_path = _archive_path()
        try:
            os.makedirs(os.path.dirname(arch_path), exist_ok=True)
            with open(arch_path, "a", encoding="utf-8") as fh:
                for job in evicted:
                    fh.write(json.dumps(job, ensure_ascii=False) + "\n")
        except OSError as exc:
            logger.error("image outbox 永久建档追加失败：%s", exc)
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
        logger.warning("image outbox 压实失败（保留原文件）：%s", exc)
        try:
            if os.path.exists(tmp):
                os.remove(tmp)
        except OSError:
            pass
        return 0
    global _JOBS
    _JOBS = {j["job_id"]: j for j in keep}
    return len(keep)


def _search_archive_locked(job_id: str = None, sku: str = None, limit: int = 50) -> list:
    """从永久归档文件逆序检索图片作业，用于随时回滚与建档溯源。"""
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
    except OSError as exc:
        logger.warning("读取 image archive 归档失败：%s", exc)
        return []
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

class _InventoryDeps:
    """默认依赖：通过网关进程内代理调 inventory 服务读照片元数据（**只读**，不自造存储）。"""

    def __init__(self, timeout=8):
        self._timeout = timeout

    def images_of(self, product_id: int) -> list:
        """本机某商品的照片列表（inventory product_images 表）。失败抛错由调用方分类。"""
        from gateway.proxy import proxy
        from gateway.router import INVENTORY_SERVICE_URL
        resp = proxy("/product/images?product_id=%d" % int(product_id), None,
                     INVENTORY_SERVICE_URL, timeout=self._timeout)
        if not resp.get("success"):
            raise RuntimeError(resp.get("hint") or resp.get("error") or "图片服务不可用")
        rows = resp.get("data")
        return rows if isinstance(rows, list) else []


_DEPS = None


def _deps():
    global _DEPS
    if _DEPS is None:
        _DEPS = _InventoryDeps()
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


# ---------------------------------------------------------------- 本机照片：路径守卫 + 压缩

def _safe_image_path(filename: str):
    """inventory 记录的文件名 → data/images 下真实路径；不合规/不存在一律 None。

    文件名虽然来自自家 DB，仍按 URL 输入同等对待：白名单 + F6 路径守卫
    （与 handler._resolve_image_path 同一把尺子）。
    """
    name = os.path.basename(str(filename or ""))
    if not _NAME_RE.fullmatch(name):
        return None
    from gateway.handler import _f6c  # 复用项目统一的 F6 守卫（懒加载避免导入环）
    root = os.path.realpath(os.environ.get("IMAGES_DIR", "/data/images"))
    try:
        p = _f6c(os.path.join(root, name), base=root)
    except ValueError:
        return None
    return p if p and os.path.isfile(p) else None


def _prepare_upload(raw: bytes) -> tuple:
    """出传前的压缩决策（纯函数，单测直接喂字节）。返回 ``(bytes, mime, note)``。

    口径见模块 docstring〔压缩〕：已达标原图直通；否则缩边 + 质量阶梯。
    :raises ValueError: 非 JPEG/PNG 或解不开（业务性拒绝，别硬传）。
    """
    try:
        import io
        from PIL import Image
    except ImportError:  # Pillow 理论上必在（环境声明依赖）；真缺了就如实直通
        return raw, "image/jpeg", "Pillow 不可用，原图直通"
    try:
        img = Image.open(io.BytesIO(raw))
        fmt = (img.format or "").upper()
    except Exception:  # noqa: BLE001 - 解不开就是坏图
        raise ValueError("文件不是可解析的图片")
    if fmt not in ("JPEG", "PNG"):
        raise ValueError("只支持 JPEG/PNG（当前 %s）" % (fmt or "未知"))
    if fmt == "JPEG" and len(raw) <= _TARGET_BYTES and max(img.size) <= _MAX_SIDE:
        return raw, "image/jpeg", "原图已达标直通（%dkB）" % (len(raw) // 1024)
    img = img.convert("RGB")
    if max(img.size) > _MAX_SIDE:
        img.thumbnail((_MAX_SIDE, _MAX_SIDE), Image.LANCZOS)
    best = b""
    for quality in _QUALITY_LADDER:
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=quality, optimize=True)
        best = buf.getvalue()
        if len(best) <= _TARGET_BYTES:
            break
    return best, "image/jpeg", "压缩至 %dkB q%d" % (len(best) // 1024, quality)


# ---------------------------------------------------------------- 上游编排（校验为唯一判据）

def _resolve_target(client, sku: str):
    """sku → 上游 productId：快路径直查库存状况表（约 1 秒），没命中才全量扫保底。

    与 barcode_outbox.write_upstream 同口径——这条快路径是「时间太久」的另一半病根。
    """
    for row in client.fetch_all_stock_condition(keyword=sku, page_size=10):
        if (row.get("productCode") or "").strip() == sku and row.get("productId"):
            return str(row["productId"])
    for product in client.fetch_all_products(page_size=500):
        if (product.get("code") or "").strip() == sku:
            return str(product["id"])
    return None


def _images_index(rows: list) -> dict:
    """inventory 行 → {image_id: row}（id 统一成 int，脏行只跳过）。"""
    out = {}
    for row in rows or []:
        try:
            out[int(row.get("id"))] = row
        except (TypeError, ValueError):
            continue
    return out


def _record_partial(job_id: str, uploaded: list) -> None:
    """把「已上传成功」逐张落进作业快照——退避重试据此**不重传**（孤儿文件删不掉）。"""
    with _LOCK:
        live = _load_locked().get(job_id)
        if live is not None:
            live["uploaded_urls"] = list(uploaded)
            _append_locked(live)


def _push_upstream(client, job: dict) -> tuple:
    """执行一条回传作业，返回 ``(verdict, reason, audit)``；verdict ∈ ok/conflict/not_found/mismatch。

    顺序刻意是「先定位商品 → 后上传图片」：商品不存在就一张都不传，孤儿文件最少。
    """
    from sync.xzg_client import XzgError

    # ① 本机照片定位（inventory 元数据 + data/images 文件）
    try:
        rows = _deps().images_of(job["product_id"])
    except Exception as exc:  # noqa: BLE001 - 店内服务抖动是环境问题，退避再来
        raise RuntimeError("本机图片服务不可用：%s" % str(exc)[:60])
    index = _images_index(rows)
    wanted = [int(i) for i in job.get("image_ids") or []]
    missing = [i for i in wanted if i not in index]
    if missing:
        return ("not_found", "有照片在本机已不存在（可能刚被删除），本次没传",
                {"missing": missing})

    # ② 先定位上游商品（在任何上传之前）
    target_id = _resolve_target(client, job["sku"])
    if not target_id:
        return ("not_found", "管家婆里找不到这个商品", None)
    original = client.fetch_product_full(target_id)
    before_imgurl = original.get("imgUrl") or ""
    existing = [u.strip() for u in before_imgurl.split(";") if u.strip()]

    # ③ 逐张：本机文件 → 压缩 → UpLoadImg（成功的立刻记进作业，重试不重传）
    uploaded = [e for e in (job.get("uploaded_urls") or []) if e.get("url")]
    done_ids = {int(e["image_id"]) for e in uploaded if str(e.get("image_id", "")).isdigit()}
    notes = []
    for image_id in wanted:
        if image_id in done_ids:
            continue
        path = _safe_image_path(index[image_id].get("filename"))
        if not path:
            return ("not_found", "照片文件在本机缺失或不合规，本次没传",
                    {"image_id": image_id, "uploaded": uploaded})
        try:
            with open(path, "rb") as fh:
                raw = fh.read()
            payload, mime, note = _prepare_upload(raw)
        except ValueError as exc:
            return ("conflict", "这张照片传不了：%s" % str(exc)[:60],
                    {"image_id": image_id, "uploaded": uploaded})
        data_url = "data:%s;base64,%s" % (mime, base64.b64encode(payload).decode("ascii"))
        try:
            url = client.upload_product_image(data_url, img_type=1)
        except XzgError as exc:
            # 上游明确拒绝该图（业务性）；网络类异常不是 XzgError，会向上传给 attempt 退避
            return ("conflict", "管家婆拒绝了这张图片：%s" % str(exc)[:80],
                    {"image_id": image_id, "uploaded": uploaded})
        uploaded.append({"image_id": image_id, "url": url, "note": note})
        notes.append(note)
        _record_partial(job["job_id"], uploaded)

    # ④ 合并 imgUrl：保留上游已有条目、追加本次（绝不覆盖清空别人配过的图）
    to_add = [e["url"] for e in uploaded if e["url"] not in existing]
    new_imgurl = ";".join(existing + to_add)
    audit = {"product_id": target_id, "sku": job["sku"],
             "before_imgurl": before_imgurl, "new_imgurl": new_imgurl,
             "uploaded": uploaded, "notes": notes}
    if new_imgurl == before_imgurl:
        audit["readback_ok"] = True
        audit["no_op"] = True
        return ("ok", "", audit)

    # ⑤ 写回（用途锁只许顶层 imgUrl）+ 回读校验；校验没过尝试把引用恢复原样
    try:
        result = client.update_product_imgurl(original, new_imgurl)
    except XzgError as exc:
        return ("conflict", "管家婆拒绝了这次回传：%s" % str(exc)[:80],
                {"product_id": target_id, "uploaded": uploaded})
    audit.update(result.get("audit") or {})
    if not audit.get("readback_ok"):
        try:
            client.update_product_imgurl(client.fetch_product_full(target_id), before_imgurl)
            audit["cleanup_ok"] = True
            reason = "写是发出去了但回读对不上，已把图片引用恢复原样（图片文件在上游删不掉）"
        except Exception:  # noqa: BLE001 - 恢复失败也照样报失败，别把异常吞成成功
            audit["cleanup_ok"] = False
            reason = "写是发出去了但回读对不上，且恢复原引用也没成功，请在管家婆里核一下图片"
        return ("mismatch", reason, audit)
    return ("ok", "", audit)


def _rollback_upstream(client, job: dict) -> tuple:
    """撤销一条已同步的回传：把 imgUrl 写回该作业记录的原值（只撤引用）。"""
    from sync.xzg_client import XzgError
    target_id = _resolve_target(client, job["sku"])
    if not target_id:
        return ("not_found", "管家婆里找不到这个商品", None)
    original = client.fetch_product_full(target_id)
    target_value = job.get("target_imgurl") or ""
    try:
        result = client.update_product_imgurl(original, target_value)
    except XzgError as exc:
        return ("conflict", "管家婆拒绝了这次撤销：%s" % str(exc)[:80], None)
    audit = dict(result.get("audit") or {})
    if not audit.get("readback_ok"):
        return ("mismatch", "撤销写回后回读对不上，请在管家婆里核一下图片", audit)
    return ("ok", "", audit)


# ---------------------------------------------------------------- 队列

def enqueue_push(sku: str, product_id: int, image_ids: list, actor: str) -> dict:
    """入队一条回传作业；同 SKU 的**在途回传旧作业自动作废**（superseded）。

    与条码同理：异步等待期里店员会重复点，旧作业若后跑会把旧一批图追加进去。
    回滚作业（kind=rollback）只被同 push_job_id 的新回滚取代，不与回传互冲。
    """
    with _LOCK:
        jobs = _load_locked()
        now = _now_ms()
        for other in jobs.values():
            if other.get("state") in _TERMINAL:
                continue
            if other.get("kind") == "push" and other.get("sku") == sku:
                other.update(state=ST_SUPERSEDED, last_error="已被更新的回传取代",
                             next_attempt_at=None, updated_at=_now_str())
                _append_locked(other)
        job = {
            "job_id": "io-%d-%04d" % (now, random.randint(0, 9999)),
            "kind": "push",
            "sku": sku,
            "product_id": int(product_id),
            "image_ids": [int(i) for i in image_ids],
            "actor": actor,
            "state": ST_PENDING,
            "attempts": 0,
            "last_error": None,
            "next_attempt_at": now,
            "created_at": _now_str(),
            "updated_at": _now_str(),
            "uploaded_urls": [],     # 断点续传依据：重试不重传
            "readback": None,
            "rolled_back": False,
            "rollback_job_id": None,
        }
        return dict(_put_locked(job))


def enqueue_rollback(sku: str, product_id: int, push_job_id: str,
                     target_imgurl: str, actor: str) -> dict:
    """入队一条撤销作业：把 imgUrl 恢复到 push 作业的原值；同 push 作业在途撤销只留最新一条。"""
    with _LOCK:
        jobs = _load_locked()
        now = _now_ms()
        for other in jobs.values():
            if other.get("state") in _TERMINAL:
                continue
            if other.get("kind") == "rollback" and other.get("push_job_id") == push_job_id:
                other.update(state=ST_SUPERSEDED, last_error="已被更新的撤销取代",
                             next_attempt_at=None, updated_at=_now_str())
                _append_locked(other)
            # 撤销赢了：同商品的在途回传让路（否则撤销后又被旧回传把图追加回去）
            if other.get("kind") == "push" and other.get("sku") == sku:
                other.update(state=ST_SUPERSEDED, last_error="被撤销操作取代",
                             next_attempt_at=None, updated_at=_now_str())
                _append_locked(other)
        job = {
            "job_id": "io-%d-%04d" % (now, random.randint(0, 9999)),
            "kind": "rollback",
            "push_job_id": push_job_id,
            "sku": sku,
            "product_id": int(product_id),
            "target_imgurl": target_imgurl,
            "actor": actor,
            "state": ST_PENDING,
            "attempts": 0,
            "last_error": None,
            "next_attempt_at": now,
            "created_at": _now_str(),
            "updated_at": _now_str(),
            "readback": None,
        }
        return dict(_put_locked(job))


def _due_jobs(now: int, limit: int) -> list:
    with _LOCK:
        jobs = _load_locked()
        due = [j for j in jobs.values()
               if j.get("state") == ST_PENDING
               and (j.get("next_attempt_at") is None or j["next_attempt_at"] <= now)]
        due.sort(key=lambda j: j.get("created_at") or "")
        return [dict(j) for j in due[:limit]]


def _finish_failed(job: dict, reason: str, audit=None) -> dict:
    """业务性失败：图片没有「本地先生效」一说（照片本来就在店内服务器），直接落 failed。"""
    with _LOCK:
        live = _load_locked().get(job["job_id"], job)
        live.update(state=ST_FAILED, last_error=reason, next_attempt_at=None,
                    readback=audit, updated_at=_now_str())
        return dict(_put_locked(live))


def _finish_synced(job: dict, audit) -> dict:
    with _LOCK:
        jobs = _load_locked()
        live = jobs.get(job["job_id"], job)
        live.update(state=ST_SYNCED, last_error=None, next_attempt_at=None,
                    readback=audit, updated_at=_now_str())
        # 撤销成功 → 给原回传作业盖「已撤销」章（回滚留痕闭环）
        if job.get("kind") == "rollback" and job.get("push_job_id"):
            push = jobs.get(job["push_job_id"])
            if push is not None:
                push.update(rolled_back=True,
                            rollback_note="已由撤销作业 %s 恢复原图片引用" % job["job_id"],
                            rollback_job_id=job["job_id"], updated_at=_now_str())
                _append_locked(push)
        return dict(_put_locked(live))


def _retry_later(job: dict, reason: str, credential: bool = False) -> dict:
    """瞬时/环境性失败：退避后再试（uploaded_urls 已逐张落盘，重试不重传）。"""
    attempts = int(job.get("attempts") or 0) + 1
    if credential:
        delay = _CREDENTIAL_BACKOFF_SEC
    else:
        delay = _BACKOFF_SEC[min(attempts - 1, len(_BACKOFF_SEC) - 1)]
    with _LOCK:
        live = _load_locked().get(job["job_id"], job)
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
        return _retry_later(job, "本机没配管家婆密码，暂时回传不了", credential=True)
    runner = _rollback_upstream if job.get("kind") == "rollback" else _push_upstream
    try:
        verdict, reason, audit = runner(client, job)
    except Exception as exc:  # noqa: BLE001 - 网络/超时/上游 5xx 一律退避重试
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
            logger.exception("image outbox 作业异常 job=%s", job.get("job_id"))
            _retry_later(job, "内部异常：%s" % str(exc)[:80])
    return len(due)


def _worker_loop() -> None:
    logger.info("图片回传 worker 启动")
    while not _STOP.is_set():
        try:
            n = process_once()
        except Exception:  # noqa: BLE001 - worker 永不退出
            logger.exception("image outbox worker 轮询异常")
            n = 0
        if n:
            continue
        _WAKE.wait(_POLL_SEC)
        _WAKE.clear()
    logger.info("图片回传 worker 停止")


def start_worker() -> bool:
    """启动后台 worker（幂等）。返回是否由本次调用真正启动。"""
    global _WORKER
    with _LOCK:
        if _WORKER is not None and _WORKER.is_alive():
            return False
        _STOP.clear()
        _WORKER = threading.Thread(target=_worker_loop, name="image-outbox", daemon=True)
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


# ---------------------------------------------------------------- 对外：受理 / 撤销 / 状态查询

def _parse_ids(value):
    """image_ids 入参：数组/单值 → 去重去脏的 int 列表（保序）。"""
    if value is None:
        return []
    items = value if isinstance(value, (list, tuple)) else [value]
    out, seen = [], set()
    for item in items:
        try:
            i = int(item)
        except (TypeError, ValueError):
            continue
        if i > 0 and i not in seen:
            seen.add(i)
            out.append(i)
    return out


def submit(body: dict) -> dict:
    """POST /api/product/image/push —— **受理**语义（一次上游请求都不发，毫秒级）。

    安全闸与条码同口径：参数清洗 + 人工确认（``confirmed``）；
    再加一道本机校验：请求的照片必须真在 inventory 元数据里（当场说「图没了」，
    而不是排队几轮后才失败）。默认口径：**不传 image_ids 时只选主图**（宁少勿滥）。
    """
    sku = _clean(body.get("sku"))
    actor = _clean(body.get("actor")) or "unknown"
    raw_pid = body.get("product_id") if "product_id" in body else body.get("productId")
    try:
        product_id = int(raw_pid or 0)
    except (TypeError, ValueError):
        product_id = 0
    if not sku or product_id <= 0:
        return _err("BAD_REQUEST", "请提供商品编号与本机商品 id")
    if body.get("confirmed") is not True:
        # 人工确认闸：没有明确确认过一律不传（上游图片存储只进不出，闸门不可省）
        return _err("NOT_CONFIRMED", "回传管家婆要店员确认后才能提交")
    ids = _parse_ids(body.get("image_ids"))
    if len(ids) > _MAX_IMAGES_PER_PUSH:
        return _err("BAD_REQUEST", "一次最多回传 %d 张" % _MAX_IMAGES_PER_PUSH)

    try:
        rows = _deps().images_of(product_id)
    except Exception as exc:  # noqa: BLE001
        return _err("INVENTORY_UNAVAILABLE", "本机图片服务不可用，没能受理，请稍后重试",
                    error=str(exc))
    index = _images_index(rows)
    if not ids:
        primary = next((i for i, r in index.items() if int(r.get("is_primary") or 0) == 1), None)
        if primary is None and rows:
            # 没标主图的旧数据：inventory 按 created_at DESC 排，末行即最早一张
            try:
                primary = int(rows[-1].get("id"))
            except (TypeError, ValueError):
                primary = None
        if primary is None:
            return _err("NO_IMAGES", "这个商品本机还没有照片，先拍照再来回传")
        ids = [primary]
    missing = [i for i in ids if i not in index]
    if missing:
        return _err("IMAGE_NOT_FOUND", "有照片在本机已不存在（可能刚被删除），请刷新照片再试",
                    data={"missing": missing})

    job = enqueue_push(sku, product_id, ids, actor)
    _WAKE.set()
    return _ok({
        "job_id": job["job_id"],
        "sku": sku,
        "product_id": product_id,
        "image_ids": ids,
        "state": job["state"],
        "upstream_pending": True,
        "message": "已受理，正在后台回传管家婆（%d 张）…" % len(ids),
    })


def rollback_submit(body: dict) -> dict:
    """POST /api/product/image/rollback —— 撤销一条已同步的回传（撤 imgUrl 引用）。

    诚实边界：图片文件在上游存储里**删不掉**，撤销只恢复写回前的 imgUrl。
    """
    job_id = _clean(body.get("job_id"))
    actor = _clean(body.get("actor")) or "unknown"
    if not job_id:
        return _err("BAD_REQUEST", "请提供要撤销的回传作业 job_id")
    if body.get("confirmed") is not True:
        return _err("NOT_CONFIRMED", "撤销也要店员确认")
    with _LOCK:
        push = _load_locked().get(job_id)
        if not push:
            arch = _search_archive_locked(job_id=job_id, limit=1)
            push = arch[0] if arch else None
        push = dict(push) if push else None
    if not push or push.get("kind") != "push":
        return _err("NOT_FOUND", "找不到这条回传记录")
    if push.get("state") != ST_SYNCED:
        return _err("NOT_SYNCED", "这条回传还没同步成功，不用撤销")
    if push.get("rolled_back"):
        return _err("ALREADY_ROLLED_BACK", "这条回传已经撤销过了")
    audit = push.get("readback") or {}
    if "old_imgurl" not in audit and "before_imgurl" not in audit:
        return _err("NO_SNAPSHOT", "这条记录没存到写回前的图片引用，无法自动撤销")
    target = audit.get("old_imgurl")
    if target is None:
        target = audit.get("before_imgurl")
    job = enqueue_rollback(push["sku"], push["product_id"], job_id,
                           target or "", actor)
    _WAKE.set()
    return _ok({
        "job_id": job["job_id"],
        "push_job_id": job_id,
        "state": job["state"],
        "message": "已受理，正在后台恢复原图片引用（已上传的图片文件在上游删不掉）…",
    })


def status(params: dict) -> dict:
    """GET /api/product/image/sync-status —— 按 job_id / sku / product_id 查最近一条作业。

    查不到返回 ``data=null``（APP 显示「已受理」即可，不是错误）。
    """
    job_id = _clean(params.get("job_id"))
    sku = _clean(params.get("sku"))
    try:
        product_id = int(params.get("product_id") or 0)
    except (TypeError, ValueError):
        product_id = 0
    if not (job_id or sku or product_id):
        return _err("BAD_REQUEST", "请提供 job_id 或 sku 或 product_id")
    with _LOCK:
        jobs = list(_load_locked().values())
        if job_id:
            hit = next((j for j in jobs if j.get("job_id") == job_id), None)
            if not hit:
                arch = _search_archive_locked(job_id=job_id, limit=1)
                hit = arch[0] if arch else None
            return _ok(_status_view(hit) if hit else None)
        cands = [j for j in jobs
                 if (sku and j.get("sku") == sku)
                 or (product_id and j.get("product_id") == product_id)]
        if not cands:
            arch = _search_archive_locked(sku=sku, limit=1)
            cands = arch
        cands.sort(key=lambda j: j.get("created_at") or "")
        return _ok(_status_view(cands[-1]) if cands else None)


def _status_view(hit: dict) -> dict:
    uploaded = hit.get("uploaded_urls") or []
    audit = hit.get("readback") or {}
    return {
        "job_id": hit.get("job_id"),
        "kind": hit.get("kind"),
        "sku": hit.get("sku"),
        "product_id": hit.get("product_id"),
        "image_ids": hit.get("image_ids") or [],
        "state": hit.get("state"),
        "attempts": hit.get("attempts", 0),
        "last_error": hit.get("last_error"),
        "error": hit.get("last_error"),
        "uploaded_count": len(uploaded),
        "uploaded_urls": [e.get("url") for e in uploaded],
        "notes": audit.get("notes") or [],
        "readback_ok": bool(audit.get("readback_ok")),
        "before_imgurl": audit.get("before_imgurl", audit.get("old_imgurl")),
        "new_imgurl": audit.get("new_imgurl"),
        "rolled_back": bool(hit.get("rolled_back")),
        "rollback_note": hit.get("rollback_note"),
        "updated_at": hit.get("updated_at"),
        "pending": hit.get("state") == ST_PENDING,
        "synced": hit.get("state") == ST_SYNCED,
        "failed": hit.get("state") == ST_FAILED,
    }
