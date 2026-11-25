#!/usr/bin/env python3
"""
示例零售全能助手 - 统一服务端 (All-In-One v3)
将 8 个后端微服务（Gateway/Search/Knowledge/Chat/Inventory/Print/Mirror/Asr）整合在单一进程并发运行。
支持直接 Python 运行，亦可被 PyInstaller 编译为单文件 EXE（retaildemo-server.exe）。
"""

import argparse
import logging
import os
import signal
import socket
import sys
import threading
import time

# Ensure src root is on sys.path
if getattr(sys, "frozen", False):
    # PyInstaller bundle directory
    bundle_dir = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(sys.executable)))
    exe_dir = os.path.dirname(os.path.abspath(sys.executable))
    sys.path.insert(0, bundle_dir)
    sys.path.insert(0, os.path.join(bundle_dir, "src"))
else:
    src_dir = os.path.dirname(os.path.abspath(__file__))
    repo_root = os.path.abspath(os.path.join(src_dir, ".."))
    sys.path.insert(0, src_dir)
    sys.path.insert(0, repo_root)
    exe_dir = repo_root


def _setup_environment(custom_data_dir: str | None = None) -> str:
    """Detect and bind data, logs, and env paths."""
    base_dir = exe_dir

    # 1. Determine data directory
    if custom_data_dir:
        data_dir = os.path.abspath(custom_data_dir)
    else:
        # Check adjacent ./data or parent ./data
        candidate_data = os.path.join(base_dir, "data")
        if os.path.exists(candidate_data):
            data_dir = candidate_data
        else:
            # Fallback to current working directory ./data
            cwd_data = os.path.abspath("data")
            data_dir = cwd_data if os.path.exists(cwd_data) else candidate_data

    # 2. Determine logs directory
    log_dir = os.path.join(base_dir, "logs")
    if not os.path.exists(os.path.dirname(log_dir)):
        log_dir = os.path.abspath("logs")

    os.makedirs(data_dir, exist_ok=True)
    os.makedirs(log_dir, exist_ok=True)
    images_dir = os.path.join(data_dir, "images")
    os.makedirs(images_dir, exist_ok=True)

    # 3. Export environment variables
    os.environ.setdefault("WUJIN_DB_PATH", os.path.join(data_dir, "wujin.db"))
    os.environ.setdefault("CHAT_DB_PATH", os.path.join(data_dir, "chat.db"))
    os.environ.setdefault("INVENTORY_DB_PATH", os.path.join(data_dir, "inventory.db"))
    os.environ.setdefault("BILLS_DB_PATH", os.path.join(data_dir, "bills.db"))
    os.environ.setdefault("IMAGES_DIR", images_dir)
    os.environ.setdefault("retaildemo_LOG_DIR", log_dir)

    # 4. Load .env file if available
    env_file = os.path.join(base_dir, ".env")
    if not os.path.exists(env_file):
        env_file = os.path.abspath(".env")
    if os.path.exists(env_file):
        try:
            with open(env_file, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if line and not line.startswith("#") and "=" in line:
                        k, v = line.split("=", 1)
                        k = k.strip()
                        v = v.strip().strip('"').strip("'")
                        if k and not os.environ.get(k):
                            os.environ[k] = v
        except Exception as e:
            logging.warning(f"Failed to parse {env_file}: {e}")

    return data_dir


def _is_port_open(host: str, port: int, timeout: float = 0.5) -> bool:
    """Check if a TCP port is open and accepting connections."""
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except (OSError, ConnectionRefusedError):
        return False


def main():
    parser = argparse.ArgumentParser(description="示例零售全能助手 - 统一服务端 (All-In-One v3)")
    parser.add_argument("--host", default="0.0.0.0", help="监听地址 (默认 0.0.0.0)")
    parser.add_argument("--data-dir", default=None, help="数据目录 (默认自动关联 ./data)")
    parser.add_argument("--with-tunnel", action="store_true", help="同时拉起 SakuraFrp 外网穿透隧道")
    args = parser.parse_args()

    # Configure logging
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] [%(threadName)s] %(message)s",
        datefmt="%H:%M:%S",
    )

    data_dir = _setup_environment(args.data_dir)

    # Import microservice components
    try:
        from utils.errors import setup_logging
        from search.search_server import ThreadedSearchHTTPServer, SearchHandler, db_warmup
        from knowledge.knowledge_server import ThreadedKnowledgeHTTPServer, KnowledgeHandler
        from chat.chat_server import ThreadedHTTPServer as ThreadedChatHTTPServer, ChatHandler
        from inventory.inventory_server import ThreadedInventoryHTTPServer, InventoryHandler, init_db as init_inventory_db
        from print.print_server import ThreadedPrintHTTPServer, PrintHandler, _process_queue
        from mirror.mirror_server import ThreadedMirrorHTTPServer, MirrorHandler, init_db as init_mirror_db
        from asr.asr_server import ThreadingHTTPServer as ThreadedAsrHTTPServer, AsrHandler
        from gateway.gateway import ThreadedGatewayHTTPServer, GatewayHandler, init_bills_db, _init_employees_db
        from gateway.device_registry import init_devices_db
    except ImportError as e:
        logging.error(f"Failed to import server modules: {e}")
        sys.exit(1)

    logging.info(f"Initializing databases in: {data_dir}")

    # Database warmups and column validations
    try:
        db_warmup()
    except Exception as e:
        logging.warning(f"Search DB warmup: {e}")

    try:
        init_inventory_db()
    except Exception as e:
        logging.warning(f"Inventory DB init: {e}")

    try:
        init_bills_db()
        _init_employees_db()
        init_devices_db()
    except Exception as e:
        logging.warning(f"Gateway DB init: {e}")

    try:
        init_mirror_db()
    except Exception as e:
        logging.warning(f"Mirror DB init: {e}")

    # Instantiate HTTP servers
    host = args.host
    search_server = ThreadedSearchHTTPServer((host, 18601), SearchHandler)
    knowledge_server = ThreadedKnowledgeHTTPServer((host, 18602), KnowledgeHandler)
    chat_server = ThreadedChatHTTPServer((host, 18603), ChatHandler)
    inventory_server = ThreadedInventoryHTTPServer((host, 18604), InventoryHandler)
    print_server = ThreadedPrintHTTPServer((host, 18605), PrintHandler)
    mirror_server = ThreadedMirrorHTTPServer((host, 18606), MirrorHandler)
    asr_server = ThreadedAsrHTTPServer((host, 18607), AsrHandler)
    gateway_server = ThreadedGatewayHTTPServer((host, 18767), GatewayHandler)

    servers = [
        ("Search", 18601, search_server),
        ("Knowledge", 18602, knowledge_server),
        ("Chat", 18603, chat_server),
        ("Inventory", 18604, inventory_server),
        ("Print", 18605, print_server),
        ("Mirror", 18606, mirror_server),
        ("Asr", 18607, asr_server),
        ("Gateway", 18767, gateway_server),
    ]

    # Start print background queue processor
    queue_thread = threading.Thread(target=_process_queue, name="PrintQueue", daemon=True)
    queue_thread.start()

    # Start server threads
    server_threads = []
    for name, port, srv in servers:
        t = threading.Thread(target=srv.serve_forever, name=f"{name}:{port}", daemon=True)
        t.start()
        server_threads.append(t)

    # Wait briefly and verify all ports are listening
    time.sleep(1.0)
    statuses = {}
    for name, port, _ in servers:
        statuses[port] = _is_port_open("127.0.0.1", port)

    # Optional: launch tunnel if requested
    tunnel_msg = "未启用"
    if args.with_tunnel:
        if os.name != "nt":
            # F12（2026-09-11）：tunnel.ps1 是 Windows 件，非 Windows 明确跳过不报错
            tunnel_msg = "跳过（tunnel.ps1 仅 Windows；Linux 隧道方案见 docs/linux-migration.md）"
        else:
            try:
                import subprocess
                tunnel_script = os.path.join(exe_dir, "scripts", "tunnel.ps1")
                if os.path.exists(tunnel_script):
                    # CREATE_NO_WINDOW (0x08000000): this all-in-one entrypoint ships as a
                    # windowed EXE (PyInstaller, dist\retaildemo-server.exe, double-clickable).
                    # A windowed parent has NO console, so a plain Popen of powershell would
                    # allocate a VISIBLE console window on the desktop. Force it hidden.
                    # (2026-09-14, same class of bug as scripts\tunnel.ps1.)
                    _no_window = 0x08000000 if os.name == "nt" else 0
                    subprocess.Popen(
                        ["powershell", "-ExecutionPolicy", "Bypass", "-File", tunnel_script, "start"],
                        creationflags=_no_window,
                    )
                    tunnel_msg = "已触发启动 (https://service.example.invalid)"
                else:
                    tunnel_msg = "未找到 scripts/tunnel.ps1"
            except Exception as e:
                tunnel_msg = f"启动失败: {e}"

    # Print dashboard banner
    banner = f"""
======================================================================
  示例零售全能助手 - 统一服务端 (All-In-One v3)
======================================================================
  [1/8] 检索服务 (Search)     : http://127.0.0.1:18601  [{"UP" if statuses.get(18601) else "DOWN"}]
  [2/8] 知识库服务 (Knowledge): http://127.0.0.1:18602  [{"UP" if statuses.get(18602) else "DOWN"}]
  [3/8] AI问答服务 (Chat)     : http://127.0.0.1:18603  [{"UP" if statuses.get(18603) else "DOWN"}]
  [4/8] 货架库存 (Inventory)  : http://127.0.0.1:18604  [{"UP" if statuses.get(18604) else "DOWN"}]
  [5/8] 打印服务 (Print)      : http://127.0.0.1:18605  [{"UP" if statuses.get(18605) else "DOWN"}]
  [6/8] 镜像同步 (Mirror)     : http://127.0.0.1:18606  [{"UP" if statuses.get(18606) else "DOWN"}]
  [7/8] 语音识别 (Asr)        : http://127.0.0.1:18607  [{"UP" if statuses.get(18607) else "DOWN"}]
  [8/8] API网关 (Gateway)     : http://127.0.0.1:18767  [{"UP" if statuses.get(18767) else "DOWN"}]
----------------------------------------------------------------------
  >>> 经营管理中枢 (Web SPA) : http://127.0.0.1:18767/
  >>> 健康检查端点 (Health)  : http://127.0.0.1:18767/health
  >>> APK 下载直链 (App)     : http://127.0.0.1:18767/retaildemo-v2.apk
  >>> 数据根目录 (Data Dir)  : {data_dir}
  >>> 公网穿透状态 (Tunnel)  : {tunnel_msg}
======================================================================
  服务正在稳定运行中，按 Ctrl+C 退出程序...
"""
    print(banner)

    # Graceful shutdown handler on SIGINT / SIGTERM
    stop_event = threading.Event()

    def _sig_handler(signum, frame):
        print("\n收到退出信号，正在安全关闭全部微服务...")
        stop_event.set()

    try:
        signal.signal(signal.SIGINT, _sig_handler)
        signal.signal(signal.SIGTERM, _sig_handler)
    except Exception:
        pass

    try:
        while not stop_event.is_set():
            time.sleep(0.5)
    except KeyboardInterrupt:
        print("\n正在停止服务...")
    finally:
        for name, port, srv in servers:
            try:
                srv.shutdown()
                srv.server_close()
            except Exception:
                pass
        print("全部服务已安全退出。")


if __name__ == "__main__":
    main()
