"""Run the synthetic V3 demonstration on the local computer."""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import sys
from urllib.parse import parse_qs, urlsplit
import webbrowser

ROOT = Path(__file__).resolve().parent
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT / "src"))
from portfolio import demonstration


class DemoHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        # Refuse cross-origin names targeting the local service.
        if self.headers.get("Host") != f"127.0.0.1:{self.server.server_port}":
            self.send_error(400)
            return
        url = urlsplit(self.path)
        path = url.path
        if path == "/":
            self.reply("text/html; charset=utf-8", (ROOT / "index.html").read_bytes())
        elif path == "/api/demo":
            options = parse_qs(url.query, keep_blank_values=True)
            query = options.get("q", ["演示耗材"])[0][:80]
            wider = options.get("size", ["50"])[0] == "60"
            result = demonstration(query=query, wider=wider)
            self.reply("application/json; charset=utf-8",
                       json.dumps(result, ensure_ascii=False).encode("utf-8"))
        else:
            self.send_error(404)

    def reply(self, media_type, payload):
        self.send_response(200)
        self.send_header("Content-Type", media_type)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *_):
        pass


def main():
    with ThreadingHTTPServer(("127.0.0.1", 0), DemoHandler) as server:
        url = f"http://127.0.0.1:{server.server_port}/"
        print(f"V3 演示已启动：{url}  按 Ctrl+C 停止。", flush=True)
        if "--no-browser" not in sys.argv:
            webbrowser.open(url)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
