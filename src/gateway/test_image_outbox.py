"""I26：商品图片「显式回传 + 上游后台回写」队列单测。

跑法：``python -m unittest discover -s src -p "test_*.py"``

纪律：**不联网**——注入假 inventory + 假上游客户端（真机定案的方法签名），
只测状态机、压缩纯函数与编排。核心断言对应老板 2026-09-14 的要求：

1. **受理毫秒级**：受理路径只查本机照片，一次上游请求都不发；确认闸硬拦；
2. **回读校验为唯一判据**：只有回读通过才算 ``synced``；没过自动撤引用并 ``failed``；
3. **环境性失败退避重试、不回滚不重传**：已上传的 url 记住了，重试绝不重复上传
   （上游图片文件删不掉，每重传一次就多一张孤儿）；
4. **合并策略**：保留上游已有 imgUrl 条目、只追加本次，绝不覆盖清空；
5. **压缩**：小图直通不二次劣化；大图缩到长边 ≤1280 且 ≤256KB；PNG 转 JPEG；
6. **孤儿最小化**：商品定位失败/照片缺失发生在任何上传**之前**。
"""
from __future__ import annotations

import io as _io
import os
import sys
import tempfile
import shutil
import unittest
from unittest import mock

_SRC = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _SRC not in sys.path:
    sys.path.insert(0, _SRC)

from gateway import image_outbox as m  # noqa: E402
from sync.xzg_client import XzgError  # noqa: E402

SKU = "192.0.2.10"
PID = 42


# ---------------------------------------------------------------- 假依赖

class _FakeDeps:
    """假 inventory：只管 get_product_images 那份元数据。"""

    def __init__(self, rows=None, error=None):
        self.rows = rows if rows is not None else []
        self.error = error
        self.calls = []

    def images_of(self, product_id):
        self.calls.append(product_id)
        if self.error:
            raise RuntimeError(self.error)
        return [dict(r) for r in self.rows]


class _FakeClient:
    """假上游（方法签名与 xzg_client 真实现一致）。

    ``calls`` 记录调用序列，用来证明「受理不碰上游」「定位失败不上传」等不变量。
    """

    def __init__(self, stock_rows=None, password="pw", before_imgurl="",
                 upload_urls=None, upload_error=None, update_ok=True, update_error=None):
        self._password = password
        self._stock = stock_rows if stock_rows is not None else [
            {"productCode": SKU, "productId": 900}]
        self.current = before_imgurl
        self.upload_urls = list(upload_urls or [])
        self.upload_error = upload_error
        self.update_ok = update_ok
        self.update_error = update_error
        self.calls = []
        self.updates = []          # 每次 update_product_imgurl 写入的值
        self._n = 0

    def fetch_all_stock_condition(self, keyword=None, page_size=10):
        self.calls.append("stock")
        if not keyword:
            return list(self._stock)
        return [r for r in self._stock if (r.get("productCode") or "") == keyword]

    def fetch_all_products(self, page_size=500):
        self.calls.append("products")
        return []

    def fetch_product_full(self, pid):
        self.calls.append("full")
        return {"id": pid, "code": SKU, "name": "试验商品", "imgUrl": self.current}

    def upload_product_image(self, image_base64, img_type=1):
        self.calls.append("upload")
        if self.upload_error:
            raise self.upload_error
        # 真机定案契约（probe_image_roundtrip.py）：裸 base64 会被拒，必须带 dataURL 前缀
        assert image_base64.startswith("data:image/jpeg;base64,"), \
            "UpLoadImg 必须带 dataURL 前缀（裸 base64 实测被拒）"
        if self.upload_urls:
            return self.upload_urls.pop(0)
        self._n += 1
        return "/corp/ProductImg/u%d.jpeg" % self._n

    def update_product_imgurl(self, original, new_imgurl):
        self.calls.append("update")
        self.updates.append(new_imgurl)
        if self.update_error:
            raise self.update_error
        ok = bool(self.update_ok)
        self.current = new_imgurl if ok else "回读漂移值"
        return {"audit": {"readback_ok": ok, "readback_imgurl": self.current,
                          "old_imgurl": original.get("imgUrl"), "new_imgurl": new_imgurl}}


# ---------------------------------------------------------------- 基座

class _Base(unittest.TestCase):
    """临时队列文件 + 临时图片目录（真 JPEG/PNG 由 PIL 现场生成）。"""

    @classmethod
    def setUpClass(cls):
        from PIL import Image
        cls.img_dir = tempfile.mkdtemp(prefix="i26-imgs-")
        Image.new("RGB", (300, 300), (120, 60, 200)).save(
            os.path.join(cls.img_dir, "a.jpg"), "JPEG", quality=90)
        Image.new("RGB", (100, 100), (10, 200, 10)).save(
            os.path.join(cls.img_dir, "b.png"), "PNG")

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.img_dir, ignore_errors=True)

    def setUp(self):
        self.log_dir = tempfile.mkdtemp(prefix="i26-log-")
        self.log = os.path.join(self.log_dir, "image-outbox.jsonl")
        patcher = mock.patch.dict(os.environ, {
            "IMAGE_OUTBOX_LOG": self.log, "IMAGES_DIR": self.img_dir})
        patcher.start()
        self.addCleanup(patcher.stop)
        self.addCleanup(shutil.rmtree, self.log_dir, True)
        m._reset_for_tests()
        self.addCleanup(m._reset_for_tests)

    def _use(self, deps, client):
        mock.patch.object(m, "_deps", lambda: deps).start()
        mock.patch.object(m, "_client", lambda: client).start()

    def _rows(self):
        """两张合规照片：7=主图(jpg)、8=副图(png)，文件名与 cls.img_dir 里的实物对应。"""
        return [
            {"id": 7, "filename": "a.jpg", "url": "/images/a.jpg", "is_primary": 1},
            {"id": 8, "filename": "b.png", "url": "/images/b.png", "is_primary": 0},
        ]

    def _seed(self, client, ids=(7,), before=""):
        deps = _FakeDeps(rows=self._rows())
        self._use(deps, client)
        return m.enqueue_push(SKU, PID, list(ids), "tester")


# ---------------------------------------------------------------- 受理

class SubmitSpec(_Base):
    def test_accepted_without_touching_upstream(self):
        """受理毫秒级：只查本机照片，一次上游请求都不发。"""
        deps = _FakeDeps(rows=self._rows())
        client = _FakeClient()
        self._use(deps, client)
        r = m.submit({"sku": SKU, "product_id": PID, "confirmed": True})
        self.assertTrue(r["success"], r)
        self.assertEqual(r["data"]["state"], m.ST_PENDING)
        self.assertEqual(r["data"]["image_ids"], [7])   # 默认只选主图（宁少勿滥）
        self.assertTrue(r["data"]["upstream_pending"])
        self.assertEqual(client.calls, [])              # 受理路径没碰上游
        self.assertEqual(deps.calls, [PID])

    def test_confirm_gate(self):
        """确认闸硬拦：没明确 confirmed=True 一律不受理（上游图片只进不出）。"""
        self._use(_FakeDeps(rows=self._rows()), _FakeClient())
        for body in ({"sku": SKU, "product_id": PID},
                     {"sku": SKU, "product_id": PID, "confirmed": "true"},
                     {"sku": SKU, "product_id": PID, "confirmed": False}):
            r = m.submit(body)
            self.assertFalse(r["success"])
            self.assertEqual(r["code"], "NOT_CONFIRMED")

    def test_bad_request(self):
        self._use(_FakeDeps(rows=self._rows()), _FakeClient())
        self.assertEqual(m.submit({"sku": "", "product_id": PID, "confirmed": True})["code"],
                         "BAD_REQUEST")
        self.assertEqual(m.submit({"sku": SKU, "product_id": 0, "confirmed": True})["code"],
                         "BAD_REQUEST")
        self.assertEqual(m.submit({"sku": SKU, "product_id": PID, "confirmed": True,
                                   "image_ids": list(range(1, 11))})["code"],
                         "BAD_REQUEST")   # 一次最多 9 张

    def test_default_primary_fallback_earliest(self):
        """没标主图的旧数据取最早一张（inventory 按 created_at DESC 排，末行最旧）。"""
        rows = [{"id": 3, "filename": "a.jpg", "is_primary": 0},
                {"id": 4, "filename": "b.png", "is_primary": 0}]
        self._use(_FakeDeps(rows=rows), _FakeClient())
        r = m.submit({"sku": SKU, "product_id": PID, "confirmed": True})
        self.assertEqual(r["data"]["image_ids"], [4])

    def test_missing_image_rejected_now(self):
        """请求的照片本机没有 → 当场拒（不留到队列里几轮后才失败）。"""
        self._use(_FakeDeps(rows=self._rows()), _FakeClient())
        r = m.submit({"sku": SKU, "product_id": PID, "confirmed": True, "image_ids": [7, 99]})
        self.assertEqual(r["code"], "IMAGE_NOT_FOUND")

    def test_no_images(self):
        self._use(_FakeDeps(rows=[]), _FakeClient())
        self.assertEqual(m.submit({"sku": SKU, "product_id": PID, "confirmed": True})["code"],
                         "NO_IMAGES")

    def test_inventory_down_rejected_not_queued(self):
        self._use(_FakeDeps(error="inventory 挂了"), _FakeClient())
        r = m.submit({"sku": SKU, "product_id": PID, "confirmed": True})
        self.assertEqual(r["code"], "INVENTORY_UNAVAILABLE")
        self.assertIsNone(m.status({"sku": SKU})["data"])

    def test_supersede_previous_pending(self):
        """在途旧回传必须作废：否则旧一批图会被后追加进 imgUrl。"""
        self._use(_FakeDeps(rows=self._rows()), _FakeClient())
        first = m.enqueue_push(SKU, PID, [7], "a")
        second = m.enqueue_push(SKU, PID, [8], "a")
        self.assertEqual(m.status({"job_id": first["job_id"]})["data"]["state"],
                         m.ST_SUPERSEDED)
        self.assertEqual(m.status({"job_id": second["job_id"]})["data"]["state"],
                         m.ST_PENDING)

    def test_queue_persists_to_jsonl(self):
        self._use(_FakeDeps(rows=self._rows()), _FakeClient())
        job = m.enqueue_push(SKU, PID, [7], "a")
        m._reset_for_tests()   # 模拟进程重启
        self.assertEqual(m.status({"job_id": job["job_id"]})["data"]["state"], m.ST_PENDING)


# ---------------------------------------------------------------- 压缩（纯函数）

class PrepareUploadSpec(unittest.TestCase):
    def _size(self, data):
        from PIL import Image
        with Image.open(_io.BytesIO(data)) as im:
            return im.format, max(im.size)

    def test_small_jpeg_passthrough(self):
        """已达标原图直通：字节原样，不做二次劣化。"""
        from PIL import Image
        buf = _io.BytesIO()
        Image.new("RGB", (300, 300), (9, 9, 9)).save(buf, "JPEG", quality=90)
        out, mime, note = m._prepare_upload(buf.getvalue())
        self.assertEqual(out, buf.getvalue())
        self.assertEqual(mime, "image/jpeg")
        self.assertIn("直通", note)

    def test_large_jpeg_downscaled_and_ladder(self):
        """大图：长边必缩到 ≤1280；若连 q55 都压不进 256KB，必须是阶梯走到底的 q55。"""
        import re
        from PIL import Image
        noise = Image.frombytes("RGB", (2400, 1600), os.urandom(2400 * 1600 * 3))
        buf = _io.BytesIO()
        noise.save(buf, "JPEG", quality=92)
        raw = buf.getvalue()
        self.assertGreater(len(raw), m._TARGET_BYTES)   # 前提：原图确实超标
        out, mime, note = m._prepare_upload(raw)
        fmt, side = self._size(out)
        self.assertEqual(fmt, "JPEG")
        self.assertLessEqual(side, m._MAX_SIDE)         # 长边钳到 1280
        self.assertLess(len(out), len(raw))             # 确实在缩体积
        self.assertIn("压缩至", note)
        q = int(re.search(r"q(\d+)$", note).group(1))
        if len(out) > m._TARGET_BYTES:                  # 没进目标就必须已降到最低档
            self.assertEqual(q, m._QUALITY_LADDER[-1])

    def test_png_converted_to_jpeg(self):
        from PIL import Image
        buf = _io.BytesIO()
        Image.new("RGB", (100, 100), (1, 2, 3)).save(buf, "PNG")
        out, mime, _ = m._prepare_upload(buf.getvalue())
        self.assertEqual(mime, "image/jpeg")
        self.assertEqual(self._size(out)[0], "JPEG")

    def test_garbage_and_gif_rejected(self):
        from PIL import Image
        buf = _io.BytesIO()
        Image.new("RGB", (10, 10)).save(buf, "GIF")
        with self.assertRaises(ValueError):
            m._prepare_upload(b"\x00\x01not an image at all")
        with self.assertRaises(ValueError):
            m._prepare_upload(buf.getvalue())


# ---------------------------------------------------------------- 回写编排

class PushAttemptSpec(_Base):
    def test_happy_path_synced_with_dataurl(self):
        """回读通过才算 synced；上传走 dataURL 契约；审计留 before/new。"""
        client = _FakeClient(before_imgurl="")
        job = self._seed(client, ids=(7, 8))
        done = m.attempt(dict(job))
        self.assertEqual(done["state"], m.ST_SYNCED)
        self.assertIn("update", client.calls)
        audit = done["readback"]
        self.assertTrue(audit["readback_ok"])
        self.assertEqual(audit["before_imgurl"], "")
        self.assertEqual(audit["new_imgurl"],
                         "/corp/ProductImg/u1.jpeg;/corp/ProductImg/u2.jpeg")
        self.assertEqual([e["image_id"] for e in audit["uploaded"]], [7, 8])

    def test_merge_keeps_existing_entries(self):
        """合并策略：上游已配的图保留，本次追加在后面，绝不覆盖清空。"""
        client = _FakeClient(before_imgurl="/corp/old.jpeg")
        job = self._seed(client, ids=(7,))
        done = m.attempt(dict(job))
        self.assertEqual(done["state"], m.ST_SYNCED)
        self.assertEqual(client.updates[0], "/corp/old.jpeg;/corp/ProductImg/u1.jpeg")

    def test_not_found_never_uploads(self):
        """上游找不到商品 → 一张都不传（孤儿最小化：定位在上传之前）。"""
        client = _FakeClient(stock_rows=[])
        job = self._seed(client, ids=(7,))
        done = m.attempt(dict(job))
        self.assertEqual(done["state"], m.ST_FAILED)
        self.assertIn("找不到", done["last_error"])
        self.assertNotIn("upload", client.calls)

    def test_missing_file_fails_before_upload(self):
        rows = [{"id": 9, "filename": "gone.jpg", "is_primary": 1}]
        self._use(_FakeDeps(rows=rows), (client := _FakeClient()))
        job = m.enqueue_push(SKU, PID, [9], "a")
        done = m.attempt(dict(job))
        self.assertEqual(done["state"], m.ST_FAILED)
        self.assertNotIn("upload", client.calls)

    def test_dirty_filename_in_db_rejected(self):
        """DB 混进脏文件名（穿越/带空格怪名）→ 白名单挡下当缺失处理，不读盘不上传。"""
        rows = [{"id": 7, "filename": "evil name.jpg", "is_primary": 1}]
        self._use(_FakeDeps(rows=rows), (client := _FakeClient()))
        job = m.enqueue_push(SKU, PID, [7], "a")
        done = m.attempt(dict(job))
        self.assertEqual(done["state"], m.ST_FAILED)
        self.assertNotIn("upload", client.calls)

    def test_upload_rejected_is_business_failure(self):
        client = _FakeClient(upload_error=XzgError("图片太大"))
        job = self._seed(client, ids=(7,))
        done = m.attempt(dict(job))
        self.assertEqual(done["state"], m.ST_FAILED)
        self.assertIn("拒绝", done["last_error"])

    def test_readback_mismatch_restores_reference(self):
        """回读对不上 → failed，并把 imgUrl 引用恢复原样（文件撤不掉但引用回滚）。"""
        client = _FakeClient(before_imgurl="/corp/old.jpeg", update_ok=False)
        job = self._seed(client, ids=(7,))
        done = m.attempt(dict(job))
        self.assertEqual(done["state"], m.ST_FAILED)
        self.assertEqual(client.updates,
                         ["/corp/old.jpeg;/corp/ProductImg/u1.jpeg", "/corp/old.jpeg"])
        self.assertTrue(done["readback"]["cleanup_ok"])

    def test_network_failure_retries_not_fails(self):
        """网络类异常（非 XzgError）→ 退避重试，不落 failed。"""
        client = _FakeClient(upload_error=RuntimeError("timed out"))
        job = self._seed(client, ids=(7,))
        done = m.attempt(dict(job))
        self.assertEqual(done["state"], m.ST_PENDING)
        self.assertEqual(done["attempts"], 1)
        self.assertIn("连不上", done["last_error"])

    def test_no_password_credentials_backoff(self):
        """真 _client 在没有 XZG_PASSWORD 时返回 None（真实现口径），按等配置长退避。"""
        self._use(_FakeDeps(rows=self._rows()), None)   # client=None ⇒ 没配密码
        job = m.enqueue_push(SKU, PID, [7], "a")
        done = m.attempt(dict(job))
        self.assertEqual(done["state"], m.ST_PENDING)
        self.assertIn("密码", done["last_error"])
        self.assertGreater(done["next_attempt_at"], m._now_ms())

    def _patch_uploaded(self, job_id, uploaded):
        """模拟「上一轮已传到一半崩了」：直接改内存作业快照，返回新快照给 attempt。"""
        with m._LOCK:
            live = m._load_locked()[job_id]
            live["uploaded_urls"] = uploaded
            return dict(live)

    def test_retry_never_reuploads(self):
        """断点续传：已上传 url 记在作业里，重试不重传（孤儿文件删不掉）。"""
        client = _FakeClient(before_imgurl="")
        job = self._seed(client, ids=(7,))
        snapshot = self._patch_uploaded(
            job["job_id"], [{"image_id": 7, "url": "/corp/resume.jpeg", "note": "上次已传"}])
        client.upload_error = AssertionError("重试不得重传")
        done = m.attempt(snapshot)
        self.assertEqual(done["state"], m.ST_SYNCED)
        self.assertNotIn("upload", client.calls)
        self.assertEqual(client.updates, ["/corp/resume.jpeg"])

    def test_no_op_when_already_present(self):
        """要传的 url 上游已在 → 不写不传，直接 synced（no_op）。"""
        client = _FakeClient(before_imgurl="/corp/resume.jpeg")
        job = self._seed(client, ids=(7,))
        snapshot = self._patch_uploaded(
            job["job_id"], [{"image_id": 7, "url": "/corp/resume.jpeg", "note": ""}])
        client.upload_error = AssertionError("不该再上传")
        done = m.attempt(snapshot)
        self.assertEqual(done["state"], m.ST_SYNCED)
        self.assertNotIn("update", client.calls)
        self.assertTrue(done["readback"]["no_op"])

    def test_inventory_down_is_env_failure_retry(self):
        """店内 inventory 抖动是环境问题：退避，不落 failed。"""
        client = _FakeClient()
        self._use(_FakeDeps(error="inventory 重启中"), client)
        job = m.enqueue_push(SKU, PID, [7], "a")
        done = m.attempt(dict(job))
        self.assertEqual(done["state"], m.ST_PENDING)
        self.assertNotIn("upload", client.calls)


# ---------------------------------------------------------------- 撤销

class RollbackSpec(_Base):
    def _synced_push(self, before="/corp/old.jpeg"):
        client = _FakeClient(before_imgurl=before)
        job = self._seed(client, ids=(7,))
        done = m.attempt(dict(job))
        self.assertEqual(done["state"], m.ST_SYNCED)
        return client, done

    def test_rollback_gates(self):
        client, push = self._synced_push()
        self.assertEqual(m.rollback_submit({"job_id": push["job_id"]})["code"],
                         "NOT_CONFIRMED")
        self.assertEqual(m.rollback_submit({"job_id": "io-nope", "confirmed": True})["code"],
                         "NOT_FOUND")

    def test_rollback_restores_and_stamps(self):
        """撤销写回原 imgUrl；成功后给原作业盖「已撤销」章（留痕闭环）。"""
        client, push = self._synced_push(before="/corp/old.jpeg")
        r = m.rollback_submit({"job_id": push["job_id"], "confirmed": True})
        self.assertTrue(r["success"], r)
        rb_id = r["data"]["job_id"]
        done = m.attempt(dict(m._load_locked()[rb_id]))
        self.assertEqual(done["state"], m.ST_SYNCED)
        self.assertEqual(client.updates[-1], "/corp/old.jpeg")
        stamped = m.status({"job_id": push["job_id"]})["data"]
        self.assertTrue(stamped["rolled_back"])
        self.assertIn(rb_id, stamped["rollback_note"] or "")
        # 再撤 → 已撤过
        self.assertEqual(m.rollback_submit({"job_id": push["job_id"],
                                            "confirmed": True})["code"],
                         "ALREADY_ROLLED_BACK")

    def test_rollback_supersedes_pending_push(self):
        """撤销赢了：同商品在途回传让路，否则撤销后又被旧回传把图追加回去。"""
        client, push = self._synced_push()
        pending = m.enqueue_push(SKU, PID, [8], "a")
        m.rollback_submit({"job_id": push["job_id"], "confirmed": True})
        self.assertEqual(m.status({"job_id": pending["job_id"]})["data"]["state"],
                         m.ST_SUPERSEDED)


# ---------------------------------------------------------------- 状态查询 / worker

class StatusSpec(_Base):
    def test_lookup_modes(self):
        self._use(_FakeDeps(rows=self._rows()), _FakeClient())
        j1 = m.enqueue_push(SKU, PID, [7], "a")
        j2 = m.enqueue_push(SKU, 99, [7], "a")   # 同 SKU 的新作业（入队更晚）
        self.assertEqual(m.status({"job_id": j1["job_id"]})["data"]["job_id"], j1["job_id"])
        self.assertEqual(m.status({"sku": SKU})["data"]["job_id"], j2["job_id"])
        self.assertIsNone(m.status({"sku": "no-such"})["data"])
        self.assertEqual(m.status({})["code"], "BAD_REQUEST")

    def test_status_view_shape(self):
        client = _FakeClient()
        job = self._seed(client, ids=(7,))
        m.attempt(dict(job))
        view = m.status({"job_id": job["job_id"]})["data"]
        for key in ("job_id", "state", "synced", "pending", "failed", "readback_ok",
                    "uploaded_count", "uploaded_urls", "before_imgurl", "new_imgurl"):
            self.assertIn(key, view)
        self.assertTrue(view["synced"])
        self.assertEqual(view["uploaded_count"], 1)


class WorkerSpec(_Base):
    def test_process_once_drains_due(self):
        client = _FakeClient()
        self._seed(client, ids=(7,))
        self.assertGreaterEqual(m.process_once(), 1)
        view = m.status({"sku": SKU})["data"]
        self.assertTrue(view["synced"])
        self.assertEqual(m.process_once(), 0)   # 没有到期作业了

    def test_worker_start_stop_idempotent(self):
        self.addCleanup(m.stop_worker)
        mock.patch.object(m, "process_once", lambda: 0).start()
        self.assertTrue(m.start_worker())
        self.assertFalse(m.start_worker())      # 幂等：不双开
        m.stop_worker()


if __name__ == "__main__":
    unittest.main()
