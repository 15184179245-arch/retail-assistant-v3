# -*- coding: utf-8 -*-
"""agent_coord 单元测试：在岗感知 / 资源锁 / 留言板 / 任务卡认领 / 夜车锁合并。

跑法：python -m unittest discover -s src -p "test_*.py"
协调根/夜车目录均指向临时目录（retaildemo_COORD_DIR / retaildemo_NIGHTLOCK_DIR），不碰真实 .agents。
"""
import os
import tempfile
import threading
import time
import unittest
from pathlib import Path

from utils import agent_coord as ac


class CoordTestBase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.coord_dir = Path(self._tmp.name) / "coord"
        self.night_dir = Path(self._tmp.name) / "reports"
        self.coord_dir.mkdir()
        self.night_dir.mkdir()
        os.environ["retaildemo_COORD_DIR"] = str(self.coord_dir)
        os.environ["retaildemo_NIGHTLOCK_DIR"] = str(self.night_dir)
        self.addCleanup(self._tmp.cleanup)
        self.addCleanup(os.environ.pop, "retaildemo_COORD_DIR", None)
        self.addCleanup(os.environ.pop, "retaildemo_NIGHTLOCK_DIR", None)


class LockTests(CoordTestBase):
    def test_领锁释放往返(self):
        ac.claim("file:src/utils/x.py", "a1", why="测试")
        self.assertIsNotNone(ac.check("file:src/utils/x.py"))
        ac.release("file:src/utils/x.py", "a1")
        self.assertIsNone(ac.check("file:src/utils/x.py"))

    def test_双人抢锁_后者拿到持有人信息(self):
        ac.claim("task:S7", "a1", why="先行")
        with self.assertRaises(ac.Conflict) as ctx:
            ac.claim("task:S7", "a2")
        self.assertEqual(ctx.exception.info.get("owner"), "a1")
        self.assertEqual(ctx.exception.info.get("why"), "先行")

    def test_锁过期后可重新领取(self):
        ac.claim("svc:backend", "a1", ttl=0.05, why="瞬时")
        time.sleep(0.15)
        self.assertIsNone(ac.check("svc:backend"))  # check 内部按过期视为空闲
        d = ac.claim("svc:backend", "a2")
        self.assertEqual(d["owner"], "a2")

    def test_file资源反斜杠与正斜杠同一把锁(self):
        ac.claim(r"file:src\utils\y.py", "a1")
        with self.assertRaises(ac.Conflict):
            ac.claim("file:src/utils/y.py", "a2")

    def test_file资源越界拒绝(self):
        for bad in ("file:../evil.py", "file:C:/Windows/evil.py", "file:.../x", "file:src/../../x"):
            with self.assertRaises(ac.CoordError, msg=bad):
                ac.claim(bad, "a1")

    def test_非持有人不能释放(self):
        ac.claim("db:wujin.db", "a1")
        with self.assertRaises(ac.CoordError):
            ac.release("db:wujin.db", "a2")
        self.assertIsNotNone(ac.check("db:wujin.db"))

    def test_等待释放_超时与成功(self):
        ac.claim("git:head", "a1", ttl=60)
        with self.assertRaises(ac.TimeoutWaiting):
            ac.wait_free("git:head", timeout=0.3)
        ac.release("git:head", "a1")
        self.assertTrue(ac.wait_free("git:head", timeout=2.0))

    def test_并发抢锁只有一人成功(self):
        n, ok, lock = 8, [], threading.Lock()
        barrier = threading.Barrier(n)

        def worker(i):
            barrier.wait()
            try:
                ac.claim("port:18767", f"w{i}", why="并发")
                with lock:
                    ok.append(f"w{i}")
            except ac.Conflict:
                pass

        ts = [threading.Thread(target=worker, args=(i,)) for i in range(n)]
        [t.start() for t in ts]
        [t.join() for t in ts]
        self.assertEqual(len(ok), 1)

    def test_steal_活班锁拒绝_过期锁放行并留言(self):
        ac.register("a1")
        ac.claim("dev:emulator", "a1", ttl=60, why="占用中")
        with self.assertRaises(ac.Conflict):
            ac.steal("dev:emulator", "a2", reason="测试")
        # 把锁改成过期（直接改 expires 模拟时间流逝）
        from utils.agent_coord import _lock_path
        d = ac._read_json(_lock_path("dev:emulator"))
        d["expires"] = time.time() - 1
        ac._write_json(_lock_path("dev:emulator"), d)
        nd = ac.steal("dev:emulator", "a2", reason="死班接管")
        self.assertEqual(nd["stolen_from"], "a1")
        inbox_a1 = ac.inbox("a1")
        self.assertTrue(any(m["kind"] == "alert" for m in inbox_a1))


class PresenceTests(CoordTestBase):
    def test_登记心跳收班往返(self):
        ac.register("b1", task="F6", branch="codex/f6")
        names = [r["name"] for r in ac.who()]
        self.assertIn("b1", names)
        ac.claim("file:docs/tasks.yaml", "b1", ttl=300)
        r = ac.unregister("b1")
        self.assertEqual(r["released"], ["file:docs/tasks.yaml"])
        self.assertIsNone(ac.check("file:docs/tasks.yaml"))
        self.assertNotIn("b1", [x["name"] for x in ac.who()])

    def test_同id活班拒绝双开(self):
        import subprocess
        import sys
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(5)"])
        try:
            ac.register("dup", pid=child.pid)
            with self.assertRaises(ac.CoordError):
                ac.register("dup")  # 对方是活进程，同 id 双开应拒绝
        finally:
            child.kill()
            child.wait()
            ac.unregister("dup")

    def test_心跳续期自己的锁(self):
        ac.register("h1")
        ac.claim("port:18601", "h1", ttl=30.0)
        time.sleep(0.2)
        d = ac.heartbeat("h1")
        self.assertLessEqual(time.time() - d["last_seen"], 1.0)
        locks = [lk for lk in ac.list_locks() if lk["res"] == "port:18601"]
        self.assertGreater(locks[0]["expires"], time.time() + 20)  # 已按原租约续期
        ac.unregister("h1")


class MessageTests(CoordTestBase):
    def test_留言投递已读游标(self):
        ac.send("a1", "b2", "请释放 file:x", kind="yield-request")
        ac.send("a1", "all", "全员注意：要切分支的走 worktree")
        ac.send("b2", "a1", "反向不该出现在b2收件箱")
        msgs = ac.inbox("b2")
        self.assertEqual(len(msgs), 2)
        self.assertEqual({m["kind"] for m in msgs}, {"yield-request", "note"})
        taken = ac.inbox("b2", take=True)
        self.assertEqual(len(taken), 2)
        self.assertEqual(ac.inbox("b2"), [])  # 游标已推进

    def test_seq并发唯一(self):
        n = 8
        barrier = threading.Barrier(n)
        seqs = []

        def worker(i):
            barrier.wait()
            seqs.append(ac.send(f"s{i}", "all", f"m{i}")["seq"])

        ts = [threading.Thread(target=worker, args=(i,)) for i in range(n)]
        [t.start() for t in ts]
        [t.join() for t in ts]
        self.assertEqual(sorted(seqs), list(range(1, n + 1)))

    def test_非法留言类型拒绝(self):
        with self.assertRaises(ac.CoordError):
            ac.send("a", "b", "x", kind="spam")


class NightLockMergeTests(CoordTestBase):
    def test_who合并夜车锁与存活判定(self):
        fresh = self.night_dir / "night-lock-t1.json"
        fresh.write_text('{"name":"t1","card":"F1收尾"}', encoding="utf-8")
        stale = self.night_dir / "night-lock-t2.json"
        stale.write_text('{"name":"t2","card":"夜车"}', encoding="utf-8")
        old = time.time() - 900
        os.utime(stale, (old, old))
        rows = {r["name"]: r for r in ac.who()}
        self.assertTrue(rows["t1"]["alive"])
        self.assertFalse(rows["t2"]["alive"])
        self.assertEqual(rows["t1"]["kind"], "night")


class TaskClaimTests(CoordTestBase):
    def test_领卡互斥与释放(self):
        ac.claim_task("S7", "a1")
        with self.assertRaises(ac.Conflict):
            ac.claim_task("S7", "a2")
        ac.release("task:S7", "a1")
        ac.claim_task("S7", "a2")


class SituationTests(CoordTestBase):
    def test_总览含四个区块(self):
        ac.register("c1", task="T")
        ac.claim("file:CHANGELOG.md", "c1", ttl=300, why="短租约")
        ac.send("other", "c1", "hi")
        s = ac.situation("c1")
        for marker in ("多班在岗总览", "资源锁", "未读留言", "近期事件", "file:CHANGELOG.md"):
            self.assertIn(marker, s)
        ac.unregister("c1")


class PidAliveTests(unittest.TestCase):
    def test_本进程活着_空pid保守视为活(self):
        self.assertTrue(ac.pid_alive(os.getpid()))
        self.assertTrue(ac.pid_alive(None))


class DeadOwnerTests(CoordTestBase):
    """C1: 死主锁可辨、可一步代清（原 steal+release 两步舞）。"""

    def test_锁列表标注持锁进程死活(self):
        ac.claim("file:dead.txt", "ghost", why="c")
        from unittest import mock
        with mock.patch.object(ac, "pid_alive", return_value=False):
            lk = {x["res"]: x for x in ac.list_locks()}["file:dead.txt"]
            self.assertFalse(lk["owner_alive"])
        lk2 = {x["res"]: x for x in ac.list_locks()}["file:dead.txt"]
        self.assertTrue(lk2["owner_alive"])  # 真实 pid=本进程，应为活

    def test_死主锁可被他人一步代清(self):
        from unittest import mock
        ac.claim("file:orphan.txt", "ghost", why="c")
        with mock.patch.object(ac, "pid_alive", return_value=False):
            r = ac.release("file:orphan.txt", "cleaner")
        self.assertTrue(r["released"])
        self.assertTrue(r.get("orphan"))
        self.assertEqual(r.get("former_owner"), "ghost")
        self.assertIsNone(ac.check("file:orphan.txt"))

    def test_活主锁他人释放仍被拒(self):
        from unittest import mock
        ac.claim("file:live.txt", "live1", why="c")
        with mock.patch.object(ac, "pid_alive", return_value=True):
            with self.assertRaises(ac.CoordError):
                ac.release("file:live.txt", "someoneelse")
        self.assertIsNotNone(ac.check("file:live.txt"))


if __name__ == "__main__":
    unittest.main()
