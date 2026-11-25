#!/usr/bin/env python3
"""示例零售 - API网关 (port 18767)
Routes:
  /api/search    -> search service (:18601)
  /api/knowledge -> knowledge service (:18602)
  /api/chat      -> chat service (:18603)
  /api/bills/sync -> local JSON storage (multi-device bill sync)
  /health        -> self check
"""
import logging
import os
import signal
import sys
from http.server import HTTPServer
from socketserver import ThreadingMixIn

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from utils.errors import setup_logging, load_env
from gateway.router import LISTEN_HOST, LISTEN_PORT
from gateway.auth import _init_employees_db
from gateway.bills_sync import init_bills_db
from gateway.device_registry import init_devices_db
from gateway.crash_store import crash_log_path
from gateway.handler import GatewayHandler

load_env()


class ThreadedGatewayHTTPServer(ThreadingMixIn, HTTPServer):
    allow_reuse_address = True
    daemon_threads = True


def _run_gateway():
    setup_logging("gateway")
    init_bills_db()
    _init_employees_db()
    init_devices_db()
    logging.info(f"crash reports -> {crash_log_path()}")
    # I24：条码回写 worker（本地先生效 → 后台回写上游 → 回读校验通过才算同步成功）。
    # 必须在 load_env() 之后起——它要读 .env 里的管家婆密码；没有密码也只是退避重试，
    # 本地绑定不受影响。worker 自身吞异常，不会拖垮网关。
    try:
        from gateway import barcode_outbox
        barcode_outbox.start_worker()
    except Exception:
        logging.exception("条码回写 worker 启动失败（本地条码仍即时生效，仅上游回写暂停）")
    # I26：商品图片回传 worker（人工确认受理 → 压缩上传 → 合并写回 imgUrl → 回读校验）。
    # 与条码 worker 同口径：只在有排队作业时干活，没凭据只是退避重试，吞异常不拖垮网关。
    try:
        from gateway import image_outbox
        image_outbox.start_worker()
    except Exception:
        logging.exception("图片回传 worker 启动失败（已受理的作业留在队列，等 worker 恢复后继续）")
    server = ThreadedGatewayHTTPServer((LISTEN_HOST, LISTEN_PORT), GatewayHandler)
    logging.info(f"Listening on {LISTEN_HOST}:{LISTEN_PORT}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        logging.info("Shutting down...")
    except Exception as e:
        logging.exception(f"Server crashed: {e}")
    finally:
        server.server_close()


if __name__ == "__main__":
    _run_gateway()
