import threading

_phone_cmd_lock = threading.Lock()
_phone_commands = {}
_phone_cmd_counter = 0
_PHONE_ID = "meizu-18"
_PHONE_CMD_MAX_LEN = 1000
_PHONE_CMD_BLOCKED_CHARS = set(';|&`$(){}<>!')
# 2026-09-09 审计：结果无人领取（phone_get_result 不清理）会无界增长，超限丢最旧
_PHONE_CMD_MAX_ENTRIES = 200


def phone_queue_command(cmd: str) -> int:
    global _phone_cmd_counter
    with _phone_cmd_lock:
        _phone_cmd_counter += 1
        cid = _phone_cmd_counter
        _phone_commands[cid] = {"cmd": cmd, "result": None, "done": False}
        while len(_phone_commands) > _PHONE_CMD_MAX_ENTRIES:
            _phone_commands.pop(min(_phone_commands), None)
        return cid


def phone_poll() -> dict:
    with _phone_cmd_lock:
        for cid, entry in list(_phone_commands.items()):
            if not entry["done"]:
                entry["done"] = True
                return {"id": cid, "cmd": entry["cmd"]}
        return {}


def phone_submit_result(cid: int, result: str):
    with _phone_cmd_lock:
        if cid in _phone_commands:
            _phone_commands[cid]["result"] = result


def phone_get_result(cid: int) -> dict:
    with _phone_cmd_lock:
        entry = _phone_commands.get(cid)
        if not entry:
            return {"error": "unknown command id"}
        return {"id": cid, "cmd": entry["cmd"], "result": entry["result"], "done": True}
