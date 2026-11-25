import time
import threading

_circuit_breaker: dict[str, dict] = {}
_cb_lock = threading.Lock()
_CB_FAIL_THRESHOLD = 3
_CB_RESET_SECONDS = 30


def cb_record_failure(service_url: str):
    now = time.time()
    with _cb_lock:
        state = _circuit_breaker.get(service_url)
        if state is None:
            _circuit_breaker[service_url] = {"failures": 1, "last_failure": now, "open": False}
        else:
            if now - state["last_failure"] > _CB_RESET_SECONDS:
                state["failures"] = 1
                state["open"] = False
            else:
                state["failures"] += 1
                if state["failures"] >= _CB_FAIL_THRESHOLD:
                    state["open"] = True
            state["last_failure"] = now


def cb_record_success(service_url: str):
    with _cb_lock:
        state = _circuit_breaker.get(service_url)
        if state:
            state["failures"] = 0
            state["open"] = False


def cb_is_open(service_url: str) -> bool:
    with _cb_lock:
        state = _circuit_breaker.get(service_url)
        if state is None:
            return False
        if not state["open"]:
            return False
        now = time.time()
        if now - state["last_failure"] > _CB_RESET_SECONDS:
            state["open"] = False
            state["failures"] = 0
            return False
        return True
