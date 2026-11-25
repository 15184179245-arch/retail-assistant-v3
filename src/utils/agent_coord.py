# -*- coding: utf-8 -*-
"""多 agent 并行开发协调库（纯标准库）。

解决：多班 agent 在同一共享工作树并行施工时互相感知、互相通信、资源互斥，
避免同时改同一文件 / 领同一张卡 / 同时动 git 或后端服务而打架。

四类原语（全部落盘在协调根目录，默认 <repo>/.agents/，不入库）：
  presence  在岗登记与心跳（register/heartbeat/unregister/who）
  lock      资源锁（claim/release/check/wait/steal，O_EXCL 原子创建 + TTL + 死班接管）
  message   班间留言板（send/inbox，单调 seq 号 + 每班已读游标）
  task      任务卡认领（claim_task，res=task:<ID>，与 docs/tasks.yaml 领卡流程配合）

在岗总览会合并读取 _reports/night-lock-*.json（夜车锁登记协议，只读不写），
与夜车哨兵生态互补：night-lock 管「你是谁、死后怎么复活」，
本库管「你现在占着什么、别人怎么实时找到你」。

安全设计（Mimosa 闸门要求）：
  1) 外部输入（资源名/agent id）永不参与路径拼接——磁盘文件名只用
     sha256 十六进制摘要；file: 资源名仅作逻辑标识存于 JSON 载荷，
     归一化走纯 Path API + relative_to，必须落在仓库内。
  2) 全部文件 IO 收口到 _coord_open / _validated_coord_path 单一入口：
     先 resolve + relative_to(coord_root) 包容校验，紧邻 open，越界即拒。
  3) 全库不做 rename/replace 类落盘：载荷单次直写（写方=持有人本人），
     读侧小退避重试容忍瞬时半行；空文件=崩溃幽灵，gc 回收、claim 重建。
  4) 遍历/清理只处理「十六进制名 + 父目录归属」双校验过的文件。

协议全文：docs/MULTI_AGENT.md
CLI 用法：python scripts/agent_coord.py <subcommand> -h
"""
from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import os
import re
import socket
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path, PurePosixPath
from typing import IO, Optional

__all__ = [
    "CoordError", "Conflict", "TimeoutWaiting",
    "register", "heartbeat", "unregister", "who",
    "claim", "release", "check", "list_locks", "wait_free", "steal",
    "claim_task", "send", "inbox", "recent_events", "situation",
    "main",
]

VERSION = 1
PRESENCE_TTL = 180.0          # 在岗心跳：3 分钟没刷新视为离线
LOCK_TTL = 3600.0             # 资源锁默认租约 1 小时
SHORT_LEASE_TTL = 300.0       # 共享文档（tasks.yaml/CHANGELOG 等）短租约
CRIT_BREAK_AGE = 10.0         # 临界区锁文件超过 10 秒视为残留，可破
WAIT_POLL = 0.5               # wait_free 轮询间隔
NIGHT_LOCK_FRESH = 12 * 60.0  # 夜车锁 mtime 12 分钟内 = 活班（与夜车协议一致）
HEX_NAME_RE = re.compile(r"[0-9a-f]{16}\.json")  # 本库生成的数据文件名形态

TZ = timezone(timedelta(hours=8))

EXIT_OK = 0
EXIT_USAGE = 2
EXIT_CONFLICT = 3    # 资源被他人持有：读 holder 信息去协商或等待
EXIT_TIMEOUT = 4     # wait 超时


class CoordError(Exception):
    """协调操作失败（用法/状态错误）。"""


class Conflict(CoordError):
    """资源正被他人持有。带 holder 信息。"""

    def __init__(self, info: dict):
        self.info = info
        super().__init__(f"资源被持有: {info.get('res')} owner={info.get('owner')}")


class TimeoutWaiting(CoordError):
    """wait 等待释放超时。"""


# ---------------------------------------------------------------- 基础设施

def _repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def coord_root() -> Path:
    """协调根目录；测试/多实例用 retaildemo_COORD_DIR 覆盖（必须为绝对路径）。"""
    env = os.environ.get("retaildemo_COORD_DIR")
    if env:
        p = Path(env)
        if not p.is_absolute():
            raise CoordError("retaildemo_COORD_DIR 必须是绝对路径")
        return p.resolve()
    return (_repo_root() / ".agents").resolve()


def _night_root() -> Path:
    env = os.environ.get("retaildemo_NIGHTLOCK_DIR")
    if env:
        p = Path(env)
        if not p.is_absolute():
            raise CoordError("retaildemo_NIGHTLOCK_DIR 必须是绝对路径")
        return p.resolve()
    return (_repo_root() / "_reports").resolve()


def _validated_coord_path(path: Path) -> Path:
    """resolve + 包容校验：目标必须落在协调根内，越界即拒（返回已解析路径）。"""
    try:
        resolved = path.resolve()
        resolved.relative_to(coord_root())
    except (ValueError, OSError):
        raise CoordError(f"协调路径越界: {path}")
    return resolved


def _coord_open(path: Path, mode: str) -> IO:
    """协调根内文件的统一打开入口：包容校验紧邻 open。"""
    resolved = _validated_coord_path(path)
    return open(resolved, mode, encoding="utf-8")


def _now() -> float:
    return time.time()


def _iso(ts: float) -> str:
    return datetime.fromtimestamp(ts, TZ).strftime("%Y-%m-%d %H:%M:%S")


def _ensure_dirs() -> None:
    (coord_root() / "locks").mkdir(parents=True, exist_ok=True)
    (coord_root() / "agents").mkdir(parents=True, exist_ok=True)


def _hex_name(kind: str, key: str) -> str:
    """外部输入 → 磁盘文件名：只允许十六进制摘要，用户输入永不成为路径段。"""
    return hashlib.sha256((kind + ":" + key).encode("utf-8")).hexdigest()[:16] + ".json"


def _guarded_iter(dirname: str):
    """遍历协调子目录里本库创建的数据文件；名字形态+父目录归属双校验。"""
    base = coord_root() / dirname
    if not base.is_dir():
        return
    try:
        base_resolved = base.resolve()
    except OSError:
        return
    for p in sorted(base.glob("*.json")):
        if not HEX_NAME_RE.fullmatch(p.name):
            continue
        try:
            if p.resolve().parent != base_resolved:
                continue
        except OSError:
            continue
        yield base, p


def _read_json(path: Path, retries: int = 6, delay: float = 0.05,
               require_coord: bool = True) -> dict:
    """读 JSON；对端可能正在写（半行），小退避重试。空文件抛 OSError。

    require_coord=False 仅用于读协调根之外的受信登记（夜车锁），只 resolve 不做包容校验。
    """
    last: Optional[Exception] = None
    for _ in range(retries):
        try:
            if require_coord:
                f = _coord_open(path, "r")
            else:
                f = open(path.resolve(), "r", encoding="utf-8")
            with f:
                text = f.read()
            if not text.strip():
                raise OSError("empty coordination file")
            return json.loads(text)
        except json.JSONDecodeError as e:
            last = e
            time.sleep(delay)
        except OSError as e:
            last = e
            time.sleep(delay)
    raise CoordError(f"读取失败 {path}: {last}")


def _write_json(path: Path, data: dict) -> None:
    """单次直写小载荷（写方=该文件唯一属主；读侧重试容忍瞬时半行）。"""
    with _coord_open(path, "w") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def _append_jsonl(path: Path, obj: dict) -> None:
    with _coord_open(path, "a") as f:
        f.write(json.dumps(obj, ensure_ascii=False) + "\n")


def pid_alive(pid: Optional[int]) -> bool:
    """进程是否存活。判定不了时保守返回 True（宁可当活班，不误抢）。"""
    if not pid or pid <= 0:
        return True
    if pid == os.getpid():
        return True
    if sys.platform == "win32":
        try:
            k32 = ctypes.windll.kernel32  # type: ignore[attr-defined]
            PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
            STILL_ACTIVE = 259
            h = k32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, int(pid))
            if not h:
                return False
            try:
                code = ctypes.c_ulong()
                if k32.GetExitCodeProcess(h, ctypes.byref(code)):
                    return code.value == STILL_ACTIVE
                return True
            finally:
                k32.CloseHandle(h)
        except Exception:
            return True
    try:
        os.kill(int(pid), 0)
        return True
    except ProcessLookupError:
        return False
    except OSError:
        return True


def _default_agent_id() -> str:
    return os.environ.get("retaildemo_AGENT_ID") or f"agent-{os.getpid()}"


def _norm_file_res(res: str) -> str:
    """file: 资源归一化为仓库相对 POSIX 路径（仅逻辑标识，不用于拼路径）。

    纯 Path API + relative_to 包容校验：必须落在仓库内且无全点号路径段。
    """
    if not res.startswith("file:"):
        return res
    try:
        rp = Path(res[len("file:"):])
        resolved = (rp if rp.is_absolute() else _repo_root() / rp).resolve()
        rel = resolved.relative_to(_repo_root())
    except (ValueError, OSError):
        raise CoordError(f"file: 资源必须解析为仓库内路径: {res}")
    if any(set(seg) == {"."} for seg in rel.parts):
        raise CoordError(f"file: 资源路径含非法段: {res}")
    return "file:" + PurePosixPath(*rel.parts).as_posix()


def _gc() -> None:
    """清过期锁、过期/空幽灵 presence（所有命令入口都先跑，代价 = 一次 listdir）。"""
    now = _now()
    for base, p in _guarded_iter("locks"):
        try:
            if p.stat().st_size == 0:  # 崩溃幽灵：O_EXCL 后未写完即死
                p.unlink(missing_ok=True)
                continue
            d = _read_json(p, retries=2)
            if d.get("expires", 0) < now:
                p.unlink(missing_ok=True)
        except (CoordError, OSError):
            continue
    for base, p in _guarded_iter("agents"):
        try:
            if p.stat().st_size == 0:
                p.unlink(missing_ok=True)
                continue
            d = _read_json(p, retries=2)
            if d.get("last_seen", 0) + d.get("ttl", PRESENCE_TTL) < now:
                p.unlink(missing_ok=True)
        except (CoordError, OSError):
            continue


def _read_jsonl(path: Path) -> list:
    out = []
    try:
        with _coord_open(path, "r") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    out.append(json.loads(line))
                except json.JSONDecodeError:
                    continue
    except OSError:
        pass
    return out


# ------------------------------------------------ 临界区（seq/留言/事件 互斥）

class _Critical:
    """O_EXCL 锁文件实现的进程间临界区。

    Windows 语义：文件被别的线程/进程打开时不可删除——因此
    ① 只按 mtime 超龄判定残留（持有人已死）才可破，绝不因等待超时强拆活锁；
    ② unlink 一律容忍 OSError（对方还开着 = 它活着，退避重试即可）。
    """

    def __init__(self, timeout: float = 5.0):
        self.path = coord_root() / "_crit.lock"
        self.timeout = timeout

    def __enter__(self):
        _ensure_dirs()
        deadline = _now() + self.timeout
        while True:
            try:
                self.fd = os.open(str(self.path), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
                return self
            except FileExistsError:
                broke = False
                try:
                    if _now() - self.path.stat().st_mtime > CRIT_BREAK_AGE:
                        try:
                            self.path.unlink()
                            broke = True
                        except OSError:
                            pass  # 对方还开着=活班，按正常冲突退避
                except OSError:
                    pass
                if broke:
                    continue
                if _now() > deadline:
                    raise CoordError("协调临界区忙（持有人活班未让出），稍后重试")
                time.sleep(0.02)

    def __exit__(self, *exc):
        try:
            os.close(self.fd)
        except OSError:
            pass
        try:
            self.path.unlink()
        except OSError:
            pass
        return False


def _next_seq_and_append(record: dict) -> int:
    """在临界区内：分配单调 seq 并追加留言。"""
    root = coord_root()
    seqfile = root / "seq"
    seq = 1
    if seqfile.is_file():
        try:
            seq = int(seqfile.read_text(encoding="utf-8").strip() or "1")
        except ValueError:
            seq = 1
    record["seq"] = seq
    _append_jsonl(root / "messages.jsonl", record)
    seqfile.write_text(str(seq + 1), encoding="utf-8")
    _append_event("message", f"{record['from']} -> {record['to']} ({record['kind']})")
    return seq


def _append_event(action: str, detail: str) -> None:
    """纯审计追加：不加临界区（send 的 seq 路径已在临界区内，事件再入会自锁）。

    极端并发下单行可能交错，读侧按行 JSON 解析跳过坏行，审计可容忍。
    """
    try:
        _append_jsonl(coord_root() / "events.jsonl", {
            "ts": _now(), "action": action, "detail": detail,
        })
    except OSError:
        pass  # 事件日志失败不影响主流程


# ---------------------------------------------------------------- presence

def register(agent: str, *, task: str = "", branch: str = "", note: str = "",
             ttl: float = PRESENCE_TTL, pid: Optional[int] = None) -> dict:
    """在岗登记（开工第一步）。同 id 活班已在岗且不是本进程 → 拒绝，防止双开同名。"""
    _ensure_dirs()
    _gc()
    path = coord_root() / "agents" / _hex_name("agent", agent)
    if path.is_file():
        old = _read_json(path)
        my_pid = pid if pid is not None else os.getpid()
        if old.get("pid") != my_pid and pid_alive(old.get("pid")) \
                and old.get("last_seen", 0) + old.get("ttl", PRESENCE_TTL) > _now():
            raise CoordError(f"agent id '{agent}' 已有活班在岗(pid={old.get('pid')})，请换一个 id")
    now = _now()
    d = {"v": VERSION, "agent": agent, "pid": pid if pid is not None else os.getpid(),
         "host": socket.gethostname(), "task": task, "branch": branch, "note": note,
         "ttl": ttl, "registered": now, "last_seen": now}
    _write_json(path, d)
    _append_event("register", f"{agent} task={task or '-'} branch={branch or '-'}")
    return d


def heartbeat(agent: str, *, task: Optional[str] = None,
              note: Optional[str] = None) -> dict:
    """刷新在岗心跳，并顺带续期自己持有的全部资源锁。"""
    _ensure_dirs()
    path = coord_root() / "agents" / _hex_name("agent", agent)
    if not path.is_file():
        raise CoordError(f"'{agent}' 未登记，先 register")
    d = _read_json(path)
    now = _now()
    d["last_seen"] = now
    if task is not None:
        d["task"] = task
    if note is not None:
        d["note"] = note
    _write_json(path, d)
    for lk in list_locks():
        if lk.get("owner") == agent:
            lk["expires"] = now + lk.get("ttl", LOCK_TTL)
            _write_json(coord_root() / "locks" / _hex_name("lock", lk["res"]), lk)
    return d


def unregister(agent: str) -> dict:
    """收班：释放自己全部锁 + 删除在岗登记。"""
    released = []
    for lk in list_locks():
        if lk.get("owner") == agent:
            release(lk["res"], agent)
            released.append(lk["res"])
    path = coord_root() / "agents" / _hex_name("agent", agent)
    if path.is_file():
        path.unlink(missing_ok=True)
    _append_event("unregister", f"{agent} released={released or '-'}")
    return {"agent": agent, "released": released}


def who() -> list:
    """谁在岗：coord presence + 夜车锁（night-lock-*.json，只读合并）。"""
    _gc()
    now = _now()
    out = []
    for base, p in _guarded_iter("agents"):
        try:
            d = _read_json(p, retries=2)
        except CoordError:
            continue
        age = now - d.get("last_seen", 0)
        out.append({"kind": "coord", "name": d.get("agent", "?"), "pid": d.get("pid"),
                    "task": d.get("task", ""), "branch": d.get("branch", ""),
                    "heartbeat_age_min": round(age / 60, 1),
                    "alive": age <= d.get("ttl", PRESENCE_TTL)})
    try:
        night_files = sorted(_night_root().glob("night-lock-*.json"))
    except OSError:
        night_files = []
    for p in night_files:
        if "legacy" in p.name:
            continue
        try:
            d = _read_json(p, retries=2, require_coord=False)
            age = now - p.stat().st_mtime
        except (CoordError, OSError):
            continue
        out.append({"kind": "night", "name": d.get("name", p.stem),
                    "pid": None, "task": d.get("card", ""),
                    "branch": d.get("branch", ""), "owner": d.get("owner", ""),
                    "heartbeat_age_min": round(age / 60, 1),
                    "alive": age <= NIGHT_LOCK_FRESH})
    return out


# ---------------------------------------------------------------- locks

def _lock_path(res: str) -> Path:
    return coord_root() / "locks" / _hex_name("lock", res)


def claim(res: str, owner: str, *, ttl: float = LOCK_TTL, why: str = "",
          task: str = "", pid: Optional[int] = None) -> dict:
    """领资源锁。O_EXCL 原子创建；被占抛 Conflict（带 holder 信息）。"""
    _ensure_dirs()
    _gc()
    res = _norm_file_res(res)
    # 2026-09-09 审计：统一原语守卫（返回值即建锁路径），_validated_coord_path 语义不变
    from utils.guards import confine
    path = Path(confine(str(_lock_path(res)), base=str(coord_root())))
    now = _now()
    d = {"v": VERSION, "res": res, "owner": owner,
         "pid": pid if pid is not None else os.getpid(),
         "task": task, "why": why, "ttl": ttl,
         "acquired": now, "expires": now + ttl, "host": socket.gethostname()}
    try:
        fd = os.open(str(path), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(d, f, ensure_ascii=False, indent=2)
    except FileExistsError:
        try:
            holder = _read_json(path, retries=2)
        except CoordError:
            try:
                ghost = path.is_file() and path.stat().st_size == 0
            except OSError:
                ghost = False
            if ghost:  # 崩溃幽灵锁，重建
                path.unlink(missing_ok=True)
                return claim(res, owner, ttl=ttl, why=why, task=task, pid=pid)
            raise
        if holder.get("res") != res:  # 摘要碰撞（工程上不可能）：当无主处理重建
            path.unlink(missing_ok=True)
            return claim(res, owner, ttl=ttl, why=why, task=task, pid=pid)
        raise Conflict(holder)
    _append_event("claim", f"{owner} + {res} ({why or '-'}) ttl={int(ttl)}s")
    return d


def release(res: str, owner: str) -> dict:
    """释放自己的锁；非持有人释放无效（防误删他人锁）。
    C1 例外：持锁进程已死（pid 不存在）时，任何在岗班可一步代清（原需 steal+release 两步）。"""
    res = _norm_file_res(res)
    path = _lock_path(res)
    if not path.is_file():
        return {"res": res, "released": False, "reason": "不存在（已过期或从未领取）"}
    d = _read_json(path)
    if d.get("owner") != owner:
        if pid_alive(d.get("pid")):
            raise CoordError(f"锁不属于你：{res} 持有人={d.get('owner')}")
        path.unlink(missing_ok=True)
        _append_event("release-orphan",
                      f"{owner} 代清 {res}（原主 {d.get('owner')} pid={d.get('pid')} 已死）")
        return {"res": res, "released": True, "orphan": True,
                "former_owner": d.get("owner")}
    path.unlink(missing_ok=True)
    _append_event("release", f"{owner} - {res}")
    return {"res": res, "released": True}


def check(res: str) -> Optional[dict]:
    """谁持有该资源？空闲返回 None。"""
    res = _norm_file_res(res)
    path = _lock_path(res)
    if not path.is_file():
        return None
    d = _read_json(path)
    if d.get("expires", 0) < _now():
        return None
    return d


def list_locks() -> list:
    out = []
    now = _now()
    for base, p in _guarded_iter("locks"):
        try:
            d = _read_json(p, retries=2)
        except CoordError:
            continue
        d["alive"] = d.get("expires", 0) >= now
        d["owner_alive"] = pid_alive(d.get("pid"))
        out.append(d)
    return out


def wait_free(res: str, *, timeout: float = 120.0) -> bool:
    """阻塞等待资源空闲；超时抛 TimeoutWaiting。"""
    res = _norm_file_res(res)
    deadline = _now() + timeout
    while True:
        if check(res) is None:
            return True
        if _now() >= deadline:
            raise TimeoutWaiting(f"等待 {res} 超时({int(timeout)}s)")
        time.sleep(WAIT_POLL)


def steal(res: str, owner: str, *, reason: str = "") -> dict:
    """接管死班/过期锁：仅当锁已过期，或持有人进程已死时允许。自动给原持有人留言。"""
    res = _norm_file_res(res)
    path = _lock_path(res)
    if not path.is_file():
        return claim(res, owner, why=f"steal(原锁不存在): {reason}")
    d = _read_json(path)
    expired = d.get("expires", 0) < _now()
    dead = not pid_alive(d.get("pid"))
    if not expired and not dead:
        raise Conflict(d)
    old = d.get("owner")
    now = _now()
    nd = dict(d)
    nd.update({"owner": owner, "pid": os.getpid(), "stolen_from": old,
               "stolen_reason": reason, "acquired": now,
               "expires": now + d.get("ttl", LOCK_TTL)})
    _write_json(path, nd)
    _append_event("steal", f"{owner} 接管 {res} (原持有人={old}, 过期={expired}, 进程死={dead})")
    if old:
        send("coordinator", old, f"你的锁 {res} 已被 {owner} 接管（原因：{reason or '未说明'}）。"
                                 f"若你仍在岗请重新 claim 并保持心跳。", kind="alert")
    return nd


def claim_task(task_id: str, owner: str, *, ttl: float = 4 * 3600.0, why: str = "") -> dict:
    """认领任务卡（res=task:<ID>）。领卡第一步，防两班领同一张卡。"""
    return claim(f"task:{task_id}", owner, ttl=ttl, why=why or "领卡施工",
                 task=task_id)


# ---------------------------------------------------------------- messages

def send(from_: str, to: str, text: str, kind: str = "note") -> dict:
    """给某班（或 all）留言。kind: note/ask/yield-request/handoff/alert。"""
    if kind not in ("note", "ask", "yield-request", "handoff", "alert"):
        raise CoordError(f"未知留言类型 {kind}")
    _ensure_dirs()
    rec = {"ts": _now(), "from": from_, "to": to, "kind": kind, "text": text}
    with _Critical():
        seq = _next_seq_and_append(rec)
    return dict(rec, seq=seq)


def _cursor_path(agent: str) -> Path:
    return coord_root() / "agents" / _hex_name("cursor", agent)


def inbox(agent: str, *, take: bool = False) -> list:
    """我的未读留言（to=我 或 all，不含自己发的）。take=True 推进已读游标。"""
    cursor = 0
    cp = _cursor_path(agent)
    if cp.is_file():
        try:
            cursor = int(_read_json(cp).get("seq", 0))
        except (CoordError, ValueError):
            cursor = 0
    unread = [m for m in _read_jsonl(coord_root() / "messages.jsonl")
              if m.get("seq", 0) > cursor and m.get("from") != agent
              and m.get("to") in (agent, "all")]
    if take and unread:
        _ensure_dirs()
        _write_json(cp, {"agent": agent, "seq": unread[-1]["seq"]})
    return unread


def recent_events(n: int = 15) -> list:
    return _read_jsonl(coord_root() / "events.jsonl")[-n:]


# ---------------------------------------------------------------- 总览

def _fmt_row(cols: list, widths: list) -> str:
    return "  ".join(str(c)[:w].ljust(w) for c, w in zip(cols, widths))


def situation(agent: Optional[str] = None) -> str:
    """作战室总览：谁在岗 / 谁占着什么 / 我的未读 / 近期事件。一次调用感知全局。"""
    _gc()
    L: list = []
    L.append(f"== 多班在岗总览 @ {_iso(_now())} ==")
    rows = who()
    if rows:
        L.append(_fmt_row(["班", "源", "活", "心跳龄", "任务/方向"], [14, 6, 5, 9, 52]))
        for r in rows:
            L.append(_fmt_row([r["name"], r["kind"], "●" if r["alive"] else "○",
                               f"{r['heartbeat_age_min']}min", r.get("task", "")],
                              [14, 6, 5, 9, 52]))
    else:
        L.append("（无在岗登记）")
    L.append("")
    L.append("== 资源锁 ==")
    locks = [lk for lk in list_locks() if lk.get("alive")]
    if locks:
        L.append(_fmt_row(["资源", "持有人", "剩余", "用途"], [44, 18, 10, 30]))
        for lk in locks:
            owner = lk.get("owner", "")
            if not lk.get("owner_alive", True):
                owner += "(死,可release)"
            L.append(_fmt_row([lk["res"], owner,
                               f"{int((lk['expires'] - _now()) / 60)}min",
                               lk.get("why", "")], [44, 18, 10, 30]))
    else:
        L.append("（无活动锁）")
    if agent:
        unread = inbox(agent)
        L.append("")
        L.append(f"== {agent} 的未读留言（{len(unread)}）==")
        for m in unread[-8:]:
            L.append(f"  #{m['seq']} [{m['kind']}] {m['from']} -> {m['to']}: {m['text'][:90]}")
    L.append("")
    L.append("== 近期事件 ==")
    for e in recent_events(8):
        L.append(f"  {_iso(e['ts'])} {e['action']}: {e['detail'][:80]}")
    return "\n".join(L)


# ---------------------------------------------------------------- CLI

def _out(s: str) -> None:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    print(s)


def main(argv: Optional[list] = None) -> int:
    ap = argparse.ArgumentParser(prog="agent_coord", description="多agent并行开发协调 CLI（协议: docs/MULTI_AGENT.md）")
    sub = ap.add_subparsers(dest="cmd", required=True)

    sp = sub.add_parser("register", help="在岗登记（开工第一步）")
    sp.add_argument("--id", default=None, help="本班 agent id（建议=任务id或锁名，全程一致）")
    sp.add_argument("--task", default=""); sp.add_argument("--branch", default="")
    sp.add_argument("--note", default="")

    sp = sub.add_parser("heartbeat", help="刷新心跳+续自己的锁")
    sp.add_argument("--id", default=None)
    sp.add_argument("--task", default=None); sp.add_argument("--note", default=None)

    sp = sub.add_parser("unregister", help="收班（释放全部锁+删登记）")
    sp.add_argument("--id", default=None)

    sp = sub.add_parser("who", help="谁在岗（含夜车锁）")

    sp = sub.add_parser("claim", help="领资源锁 res 形如 file:路径 / dir:目录 / task:ID / db:名 / svc:backend / port:N / git:head / git:index / dev:emulator")
    sp.add_argument("--id", default=None)
    sp.add_argument("res"); sp.add_argument("--ttl", type=float, default=LOCK_TTL)
    sp.add_argument("--why", default=""); sp.add_argument("--task", default="")

    sp = sub.add_parser("release", help="释放自己的锁")
    sp.add_argument("--id", default=None); sp.add_argument("res")

    sp = sub.add_parser("check", help="查谁持有资源"); sp.add_argument("res")

    sp = sub.add_parser("locks", help="列全部活动锁")

    sp = sub.add_parser("wait", help="等资源空闲")
    sp.add_argument("--id", default=None)
    sp.add_argument("res")
    sp.add_argument("--timeout", type=float, default=120.0)

    sp = sub.add_parser("steal", help="接管过期/死班锁")
    sp.add_argument("--id", default=None)
    sp.add_argument("res"); sp.add_argument("--reason", default="")

    sp = sub.add_parser("claim-task", help="认领任务卡")
    sp.add_argument("--id", default=None)
    sp.add_argument("task_id"); sp.add_argument("--ttl", type=float, default=4 * 3600.0)

    sp = sub.add_parser("send", help="留言")
    sp.add_argument("--id", default=None)
    sp.add_argument("--to", required=True); sp.add_argument("--kind", default="note")
    sp.add_argument("--text", required=True)

    sp = sub.add_parser("inbox", help="我的未读留言")
    sp.add_argument("--id", default=None)
    sp.add_argument("--take", action="store_true", help="读后推进已读游标")

    sp = sub.add_parser("situation", help="总览：在岗+锁+未读+事件")
    sp.add_argument("--id", default=None)

    args = ap.parse_args(argv)
    me = getattr(args, "id", None)

    try:
        if args.cmd == "register":
            me = me or _default_agent_id()
            _out(json.dumps(register(me, task=args.task, branch=args.branch, note=args.note),
                            ensure_ascii=False))
        elif args.cmd == "heartbeat":
            heartbeat(me or _default_agent_id(), task=args.task, note=args.note)
            _out(f"heartbeat ok @ {_iso(_now())}")
        elif args.cmd == "unregister":
            r = unregister(me or _default_agent_id())
            _out(f"收班：释放 {len(r['released'])} 把锁 {r['released'] or ''}")
        elif args.cmd == "who":
            for r in who():
                _out(_fmt_row([r["name"], r["kind"], "●" if r["alive"] else "○",
                               f"{r['heartbeat_age_min']}min", r.get("task", "")],
                              [14, 6, 5, 9, 52]))
        elif args.cmd == "claim":
            d = claim(args.res, me or _default_agent_id(), ttl=args.ttl,
                      why=args.why, task=args.task)
            _out(f"已领锁 {d['res']}（{int(args.ttl)}s 租约，心跳会自动续期）")
        elif args.cmd == "release":
            r = release(args.res, me or _default_agent_id())
            _out(f"{args.res}: {'已释放' if r['released'] else r['reason']}")
        elif args.cmd == "check":
            d = check(args.res)
            _out("空闲" if d is None else json.dumps(d, ensure_ascii=False))
        elif args.cmd == "locks":
            for lk in list_locks():
                _out(_fmt_row([lk["res"], lk.get("owner", ""),
                               "live" if lk.get("alive") else "expired", lk.get("why", "")],
                              [44, 14, 8, 40]))
        elif args.cmd == "wait":
            wait_free(args.res, timeout=args.timeout)
            _out(f"{args.res} 已空闲，可 claim")
        elif args.cmd == "steal":
            d = steal(args.res, me or _default_agent_id(), reason=args.reason)
            _out(f"已接管 {d['res']}（原持有人={d.get('stolen_from')}，已给对方留言）")
        elif args.cmd == "claim-task":
            d = claim_task(args.task_id, me or _default_agent_id(), ttl=args.ttl)
            _out(f"已认领任务卡 {d['res']}。接着按 AUTOPILOT §1-6 把 tasks.yaml 该卡置 in_progress（编辑前先 claim file:docs/tasks.yaml）")
        elif args.cmd == "send":
            r = send(me or _default_agent_id(), args.to, args.text, kind=args.kind)
            _out(f"已投递 #{r['seq']} -> {args.to}")
        elif args.cmd == "inbox":
            msgs = inbox(me or _default_agent_id(), take=args.take)
            for m in msgs:
                _out(f"#{m['seq']} [{m['kind']}] {m['from']} -> {m['to']}: {m['text']}")
            if not msgs:
                _out("(无未读)")
        elif args.cmd == "situation":
            _out(situation(me))
        return EXIT_OK
    except Conflict as e:
        d = e.info
        _out(f"[CONFLICT] {d.get('res')} 被持有：owner={d.get('owner')} pid={d.get('pid')} "
             f"剩余{int((d.get('expires', 0) - _now()) / 60)}min why={d.get('why', '')}\n"
             f"处置：python scripts/agent_coord.py send --to {d.get('owner')} --kind yield-request --text \"...\" "
             f"或 wait {d.get('res')} 或（对方死班）steal")
        return EXIT_CONFLICT
    except TimeoutWaiting as e:
        _out(f"[TIMEOUT] {e}")
        return EXIT_TIMEOUT
    except CoordError as e:
        _out(f"[ERROR] {e}")
        return EXIT_USAGE


if __name__ == "__main__":
    sys.exit(main())
