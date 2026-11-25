import logging
import socket
from gateway.router import LABEL_WIDTH_MM, LABEL_HEIGHT_MM
from utils.guards import safe_lan_host, safe_port


def tspl_price_tag(name: str, price: str, sku: str, spec: str = "") -> str:
    name_disp = (name[:10] + "..") if len(name) > 10 else name
    spec_disp = spec[:12] if spec else ""
    sku_disp = sku[:20]
    lines = [
        "SIZE %d mm, %d mm" % (LABEL_WIDTH_MM, LABEL_HEIGHT_MM),
        "GAP 2 mm, 0 mm",
        "CLS",
        'TEXT 15,5,"TSS24.BF",0,1,1,"示例零售"',
        'TEXT 15,25,"TSS24.BF",0,1,1,"%s"' % name_disp,
    ]
    if spec_disp:
        lines.append('TEXT 15,45,"TSS24.BF",0,1,1,"%s"' % spec_disp)
    price_y = 45 if not spec_disp else 65
    lines.append('TEXT 15,%d,"TSS24.BF",0,2,2,"¥%s"' % (price_y, price))
    lines.append('TEXT 15,95,"TSS24.BF",0,1,1,"%s"' % sku_disp)
    lines.append('BARCODE 15,110,"128",0,1,0,30,10,"%s"' % sku_disp)
    lines.append("PRINT 1")
    return "\n".join(lines)


def send_tspl(ip: str, port: int, data: str) -> dict:
    # 2026-09-10 排雷②配套：本函数是网关唯一的打印机 socket 出口（/print/ping 与
    # 直印共用），与打印服务同一套内网防护——未鉴权面不得充当任意地址/端口的跳板
    try:
        ip = safe_lan_host(ip)
        port = safe_port(port)
    except ValueError as e:
        logging.warning("blocked non-LAN printer target %r:%r (%s)", ip, port, e)
        return {"success": False, "error": "目标不是内网打印机地址，已拦截"}
    sock = None
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(10)
        sock.connect((ip, port))
        sock.sendall(data.encode("utf-8"))
        sock.shutdown(socket.SHUT_WR)
        resp = sock.recv(4096)
        return {"success": True, "sent": len(data), "response": resp.decode("utf-8", errors="replace")[:200]}
    except Exception as e:
        return {"success": False, "error": str(e)}
    finally:
        if sock:
            sock.close()
