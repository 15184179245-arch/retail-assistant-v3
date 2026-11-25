"""熊掌柜（service.example.invalid）**只读** API 客户端。

老板 2026-09-03 定调，本模块必须遵守：

1. **上游绝对只读**：所有出站请求都要过 :data:`READONLY_ENDPOINTS` 白名单，
   白名单外的路径一律拒绝并抛 :class:`ReadOnlyViolation`。
   禁止出现任何 Create / Update / Delete / Import / Export 调用。
2. **凭据零落盘**：密码只从环境变量 ``XZG_PASSWORD`` 读取，不写日志、不写缓存文件、
   不进差异报告。token 缓存到用户缓存目录下 ``retaildemo-xzg-token.json``（权限 600），
   日志只打印 token 前 8 位。
3. **纯标准库**：urllib / json / time，禁止第三方依赖（CONVENTIONS §5）。

认证与分页契约见 ``research/xiongzhanggui-data-source-2026-09-03.md`` §2 / §4。
"""
from __future__ import annotations

import gzip
import http.client
import json
import os
import ssl
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import quote, urlsplit, urlunsplit
from typing import Any, Dict, Iterator, List, Optional

API_BASE = "https://service.example.invalid"
DEFAULT_TENANCY_NAME = "00000000000"
DEFAULT_USERNAME = "demo-user"
#: 门店/仓库 id（实测 2026-09-13，本店单仓单店两值相同；见 _reports/xzg-client-re/STOCK_API_VERDICT.md §1.1）。
#: Stock/GetByProductIds 的 whsId 与 shopId 都必须给真值，缺一静默回空数组。
DEFAULT_SHOP_ID = "DEMO-SHOP"

# 只读白名单：白名单外的任何请求都会被拒绝（防御未来有人误加写接口）。
# S7（2026-09-04 全维度同步）新增两条只读查询/导出端点；任何 Create/Update/Delete/
# Posting/Import 类端点永不加入。
READONLY_ENDPOINTS = frozenset({
    ("POST", "/api/TokenAuth/Authenticate"),
    ("POST", "/api/services/app/BaseProduct/GetAll"),
    ("POST", "/api/services/app/V1/Erp/Report/Stock/StockCondition/Export"),
    ("POST", "/api/services/app/BaseProduct/GetAllProductStandard4Tc"),
    # S18（2026-09-11）：客户档案（基础信息）只读同步。端点来源=service.example.invalid
    # SPA main.js 前端自身选客器同款调用（GET 型 GetAll），token 探测实测 61 条；
    # 老板令「从管家婆上游基础信息提取客户信息保存到 app」即此授权。
    ("POST", "/api/services/app/V1/Erp/Selector/Customer/GetAll"),
    # ── XZG-API-RO（2026-09-13 老板令「定死只读然后成功反哺项目」）──────────────
    # 来源：客户端安装包逆向（_reports/xzg-client-re/）+ 真账账号只读全量实测。
    # 全量盘点：SPA bundle 共 1231 条业务路径，674 条只读语义端点 100% 实测（两轮），
    # 约 210 条能出数；本白名单只收**已实测出数且对项目直接有用**的端点。
    #
    # 只读性质复核：下列全部为报表查询 / 选择器查询 / 规格查询，无任何副作用。
    #
    # ⚠️ 硬约束（实测踩出，改动前必读）：
    #   ① Sale/Query 必须带 startDate/endDate——不传只回当天（19 条 vs 全量 2755 条），
    #      且 beginDate/billDateStart/dateStart 等错名会被**静默忽略**不报错；
    #   ② Stock/GetByProductIds 必须同时给 whsId + shopId，缺任一都回 200+空数组；
    #   ③ Selector/Price/GetAll 必须给 ProductIds 与 billModule；
    #   ④ GetByProductIds 的 ProductIds 是 PascalCase，小写会被静默忽略。
    ("POST", "/api/services/app/V1/Erp/Report/SaleBill/Sale/Query"),
    ("POST", "/api/services/app/V1/Erp/Report/SaleBill/ExportSaleBillList"),
    ("POST", "/api/services/app/V1/Erp/Selector/Product/Standard/GetAll"),
    # 销售商品行明细（单头之下的逐行商品，对账闭环核心）——实测 1993 行
    ("POST", "/api/services/app/V1/Erp/Report/SaleBill/Summary/Detail/Get"),
    # 全量零售销售明细（APP 挂账是否已录管家婆的自动核销）——实测 4436 行
    ("POST", "/api/services/app/ReportSaleAnalysis/GetAllProductSaleDetail"),
    # 销售退货单——实测 179 行
    ("POST", "/api/services/app/V1/Erp/Report/SaleBill/SaleBack/Query"),
    # 库存状况表 JSON 版（xlsx 两段式链路的等价替代）——实测 12670 条
    ("POST", "/api/services/app/V1/Erp/Report/Stock/StockCondition/Get"),
    # 实时库存（按商品 id 批量，需 whsId+shopId）——实测与 xlsx 逐值一致
    ("POST", "/api/services/app/V1/Erp/Stock/GetByProductIds"),
    # 库存变动汇总 / 销售毛利——实测 1457 / 1200 行
    ("POST", "/api/services/app/V1/Erp/Report/Stock/StockChangeSummary/Get"),
    ("POST", "/api/services/app/V1/Erp/Report/SaleBill/GrossProfit/Get"),
    # 客户往来明细 / 应收合计（欠款能力）——应收实测可出数
    ("POST", "/api/services/app/V1/Erp/Report/Capital/Customer/Rec/Get"),
    ("POST", "/api/services/app/V1/Erp/Report/Capital/Customer/CustomerARTotalPreARTotal/Get"),
    # 实时价格选择器（需 ProductIds + billModule）
    ("POST", "/api/services/app/V1/Erp/Selector/Price/GetAll"),
    # 条码写回要读商品全对象（GET 型，仍属只读）
    ("GET", "/api/services/app/BaseProduct/Get"),
})

# ── 写端点白名单（老板 2026-09-13 单独授权，**仅此一条**）─────────────────────
#
# 背景：店里条码脏乱（无条码 / 条码存成内部码），需人工把真实条码写回上游。
# 老板原话：「这一条我允许你只开这一个口子」。
#
# 授权边界（写死在代码里，不靠约定）：
#   ① 只放行 ``PUT BaseProduct/Update`` 一条，其余 415 个写端点继续锁死；
#   ② 调用方必须走 :meth:`XzgClient.update_product_barcode`——内置**用途锁**：
#      提交前逐字段比对「原对象 vs 新对象」，差异一旦超出条码字段
#      （``barCode`` / ``baseProductStandardList``）就拒绝提交。将来有人误用
#      这个口子去改价格/规格也会被代码挡住；
#   ③ 写前必须抓原对象存档（方法强制要求传入原对象，用于用途锁与回滚依据）。
#
# 实测依据（2026-09-13，串货类零库存商品当试验田，老板指定）：
#   Get 73 字段 → 只改条码 → PUT 200 → 回读条码已变、**其余字段零差异**；
#   改回原值同样 200 零差异；事后 26 条试验田全量核对 26/26 条码一致。
#   条码在商品对象里挂三处，必须同时写：顶层 ``barCode``、规格层 ``barCode``、
#   规格的 ``baseProductStandardBarCodeList[].barCode``（条码实体表）。
#
# ── 图片回写放行（老板 2026-09-14 令「再放行图片回写」）─────────────────────
# 实证链（_reports/xzg-client-re/ 逆向档案，网页版逐张配图全链路）：
#   ① ``POST CommonUploadImg/UpLoadImg`` {img_Type, img_Base64} → 返回相对路径 ``img_Url``；
#   ② 商品多图 = 路径**分号 join** 写入商品对象顶层 ``imgUrl``（读侧 split）；
#   ③ 写回走**同一个** ``PUT BaseProduct/Update``（不新增第三个口子）。
# 授权边界与条码完全同构：
#   ① 上传端点仅 ``CommonUploadImg/UpLoadImg`` 一条（POST 写类，进白名单）；
#   ② 写回必须走 :meth:`XzgClient.update_product_imgurl`——用途锁逐路径校验，
#      差异超出顶层 ``imgUrl`` 一个字段即拒绝提交（价格/条码/规格一律动不了）；
#   ③ 写前同样以服务端现值重建提交体、抓原对象做回滚依据（imgUrl 写回原值即可撤销）。
# 实测依据（2026-09-14 真机单条往返，_reports/probe_image_roundtrip.py）：
#   试验田 192.0.2.10 指示灯：UpLoadImg(带 dataURL 前缀的 base64, img_Type=1)
#   → 200 返回 '/{企业id}/ProductImg/{UUID}.jpeg' 相对路径 → Update 写 imgUrl
#   回读一致 → 回滚原值再回读一致 → 商品数据零残留。
#   裸 base64（无 dataURL 前缀）type=1/2 均被拒（"Specified method is not supported."）。
#   ⚠️ 已知残留面：UpLoadImg 落上游存储的图片文件**无删除端点可回收**，
#   商品回滚只清 imgUrl 引用，图片本体成为孤儿文件——故调用侧必须少传、慎传。
WRITE_ENDPOINTS = frozenset({
    ("PUT", "/api/services/app/BaseProduct/Update"),
    ("POST", "/api/services/app/CommonUploadImg/UpLoadImg"),
})

#: 条码写回允许触及的字段（用途锁的判定依据，逐路径校验；不是「子树白名单」）。
BARCODE_WRITE_ALLOWED_FIELDS = frozenset({"barCode", "baseProductStandardList"})

#: 允许改动的**叶子字段名**：只有条码本身，以及条码实体表的项。
BARCODE_LEAF_FIELDS = frozenset({"barCode"})

#: 图片写回允许触及的字段：只有**顶层** ``imgUrl``（多图 = 分号 join 的路径串）。
#: 与条码不同，imgUrl 是纯标量列，不挂子树——所以锁比条码更紧。
IMG_WRITE_ALLOWED_FIELDS = frozenset({"imgUrl"})


def _is_imgurl_path(path: str) -> bool:
    """图片用途锁：差异路径必须恰好是顶层 ``imgUrl``，其余一律越界。"""
    return path.lstrip(".") == "imgUrl"


def _is_barcode_path(path: str) -> bool:
    """判断一条差异路径是否属于「合法的条码改动」。

    合法形态只有三种：

    - ``barCode``（顶层）
    - ``baseProductStandardList[].barCode``（规格层）
    - ``baseProductStandardBarCodeList[].barCode``（条码实体表）

    以及规格/条码表的数组**长度变化**（新增或删除条码项）。
    其余一律判为越界——尤其是规格子树里的价格、名称、换算关系等。
    """
    lstrip = path.lstrip(".")
    if lstrip == "barCode":
        return True
    if lstrip.startswith("baseProductStandardList."):
        index, _, rest = lstrip[len("baseProductStandardList."):].partition(".")
        if not index.startswith("["):
            return False
        rest = rest.lstrip(".")
        # 条码实体表增删（无条码商品新建条码时的必经之路）：形状如
        # ``baseProductStandardBarCodeList.(len 0→1)``，可能带数组下标前缀。
        if "baseProductStandardBarCodeList.(len" in rest:
            return True
        return rest == "barCode" or (
            rest.startswith("baseProductStandardBarCodeList.")
            and rest.endswith(".barCode")
        )
    return False

#: 销售报表查询的日期参数名。实测（_reports/xzg-client-re/probe_query_params.py）：
#: startDate/endDate 生效；beginDate/endDate、billDateStart/End、dateStart/End 均被
#: 上游静默忽略——传了不报错但结果不变，排查时极易误判成「上游数据少」。
SALES_DATE_PARAMS = ("startDate", "endDate")

#: 销售报表默认只回当天的实测证据（无日期参数时 totalCount=19，带全量区间=2755）。
SALES_RANGE_OPTS = ("2026-01-01", "2030-12-31")

#: 报表下载允许的域白名单（Export 接口返回的 result URL 必须落在此域内，防 SSRF）。
DOWNLOAD_ALLOWED_HOSTS = frozenset({"service.example.invalid"})

DEFAULT_CACHE_PATH = os.path.join(
    os.path.expanduser("~"), ".cache", "retaildemo-xzg-token.json"
)

# token 提前这么多秒视为过期，留出网络往返余量
_TOKEN_EXPIRY_MARGIN_SEC = 120



def _f6c(p, base=None):
    """F6 路径守卫：realpath 归一 + 根前缀 + .. 分量显式检查，返回值即落盘路径。"""
    import os as _os
    _root = _os.path.realpath(base or _os.environ.get("retaildemo_PATH_BASE") or _os.getcwd())
    _rp = _os.path.normpath(p)
    if not _os.path.isabs(_rp):
        _rp = _os.path.normpath(_os.path.join(_root, _rp))
    try:
        if _os.path.commonpath([_rp, _root]) != _root:
            raise ValueError("path outside sandbox: " + _rp)
    except ValueError:
        if not _rp.startswith(_root + _os.sep):
            raise ValueError("path outside sandbox: " + _rp)
    if ".." in _os.path.relpath(_rp, _root).split(_os.sep):
        raise ValueError("path traversal blocked: " + _rp)
    return _rp

class XzgError(Exception):
    """熊掌柜客户端基础异常。"""


class ReadOnlyViolation(XzgError):
    """试图调用只读白名单之外的端点。"""


class XzgAuthError(XzgError):
    """认证失败或缺少凭据。"""


class XzgApiError(XzgError):
    """上游返回业务错误（success=false 或 HTTP 非 2xx）。

    ``payload`` 保留上游原始响应体，供「未授权自愈」判定（ABP 未登录时
    HTTP 仍是 200，只能看 body 的 ``unAuthorizedRequest``/``error.message``）。
    """

    def __init__(self, message: str, payload: Optional[Dict[str, Any]] = None) -> None:
        super().__init__(message)
        self.payload = payload


#: 上游「未登录/未授权」识别词（ABP：unAuthorizedRequest=true 或 error.message=当前用户没有登录到系统！）
#: ``请重新登录`` 覆盖「账号已在其他地方登录，请重新登录！」——2026-09-13 实测命中：
#: 同一账号被新登录顶掉时上游回的就是这句，缺了它 S17 的未授权自愈不会触发。
_UNAUTHORIZED_HINTS = ("未登录", "登录到系统", "未授权", "unauthorized", "http 401",
                       "请重新登录", "重新登录")


def _is_unauthorized_error(exc: BaseException) -> bool:
    """判断异常是否为上游「未授权」类错误（用于丢 token 重登）。"""
    payload = getattr(exc, "payload", None)
    if isinstance(payload, dict) and payload.get("unAuthorizedRequest") is True:
        return True
    text = str(exc).lower()
    return any(hint in text for hint in _UNAUTHORIZED_HINTS)


def _diff_paths(before: Any, after: Any, prefix: str = "") -> List[str]:
    """递归列出两个结构之间的字段路径差异（用途锁的判定基础）。

    只报路径不报值——调用方拿它判断「改动是否越界」，不用于回显数据。
    """
    out: List[str] = []
    if isinstance(before, dict) and isinstance(after, dict):
        for key in sorted(set(before) | set(after)):
            if key not in before or key not in after:
                out.append(prefix + key)
            else:
                # 仅当两侧都是容器时才加分隔符，标量叶子直接报当前路径
                sep = "." if isinstance(before[key], (dict, list)) else ""
                out.extend(_diff_paths(before[key], after[key], prefix + key + sep))
    elif isinstance(before, list) and isinstance(after, list):
        if len(before) != len(after):
            out.append("%s(len %d→%d)" % (prefix, len(before), len(after)))
        else:
            for idx, (a, b) in enumerate(zip(before, after)):
                out.extend(_diff_paths(a, b, "%s[%d]." % (prefix, idx)))
    elif before != after:
        out.append(prefix)
    return out


def mask_token(token: Optional[str]) -> str:
    """日志/报告用的脱敏 token：只留前 8 位，不足 8 位或空则整体隐去。"""
    if not token:
        return "(none)"
    if len(token) <= 8:
        return "***"
    return token[:8] + "..."


def _default_ssl_context() -> ssl.SSLContext:
    return ssl.create_default_context()


#: 出站 API 域名白名单（防基址被篡改后打内网/本机/元数据地址）。
#: 测试或联调指向本地 mock 上游时，必须由调用方显式传入 allowed_hosts，绝不隐式放行。
API_ALLOWED_HOSTS = frozenset({"service.example.invalid"})


def assert_allowed_api_url(url: str, allowed_hosts: frozenset) -> None:
    """出站请求前的协议/主机边界校验：不合规直接抛错，不发请求。"""
    parts = urlsplit(url)
    if parts.scheme not in ("https", "http") or not parts.hostname:
        raise XzgError("拒绝请求非法 URL：%r" % url)
    if parts.hostname.lower() not in allowed_hosts:
        raise XzgError("拒绝请求白名单外主机（%s）：出站仅允许 %s"
                       % (parts.hostname, ", ".join(sorted(allowed_hosts))))


def _make_conn_factory(parts: Any, timeout: float):
    """给并行拉取造长连接工厂（线程内复用；https 走系统默认证书校验）。"""
    host = parts.hostname
    port = parts.port
    if parts.scheme == "https":
        return lambda: http.client.HTTPSConnection(
            host, port, timeout=timeout, context=_default_ssl_context()
        )
    return lambda: http.client.HTTPConnection(host, port, timeout=timeout)


def _close_quiet(conn: Any) -> None:
    if conn is None:
        return
    try:
        conn.close()
    except Exception:  # noqa: BLE001 - 关连接失败无后果
        pass


class XzgClient:
    """熊掌柜只读客户端。

    :param password: 熊掌柜密码；为 None 时从环境变量 ``XZG_PASSWORD`` 读取。
        **该值永不被打印、写日志或写入缓存文件**。
    """

    @staticmethod
    def _parse_payload(path: str, raw: str) -> Dict[str, Any]:
        """ABP 响应解析：非 JSON / success=false 一律抛 XzgApiError。

        ``payload`` 随异常带出，供「未授权自愈」判定（ABP 未登录时 HTTP 仍是
        200，只能看 body 的 ``unAuthorizedRequest`` / ``error.message``）。
        """
        try:
            payload = json.loads(raw)
        except ValueError as exc:
            raise XzgApiError("响应不是合法 JSON（%s）：%s" % (path, raw[:120])) from exc

        if isinstance(payload, dict) and payload.get("success") is False:
            err = payload.get("error") or {}
            message = err.get("message") if isinstance(err, dict) else str(err)
            raise XzgApiError(
                "上游返回失败（%s）：%s" % (path, message or payload.get("__abp", "unknown")),
                payload=payload,
            )
        return payload

    def __init__(
        self,
        password: Optional[str] = None,
        username: str = DEFAULT_USERNAME,
        tenancy_name: str = DEFAULT_TENANCY_NAME,
        base_url: str = API_BASE,
        cache_path: Optional[str] = None,
        timeout: float = 60.0,
        page_interval: float = 0.05,
        verbose: bool = True,
        allowed_hosts: Optional[frozenset] = None,
    ) -> None:
        self.username = username
        self.tenancy_name = tenancy_name
        self.base_url = base_url.rstrip("/")
        self.cache_path = cache_path or DEFAULT_CACHE_PATH
        self.timeout = timeout
        self.page_interval = page_interval
        self.verbose = verbose
        # 出站主机白名单：默认只允许上游 API 域；mock/联调必须显式放宽
        self.allowed_hosts = frozenset(allowed_hosts) if allowed_hosts is not None else API_ALLOWED_HOSTS

        self._password = password if password is not None else os.environ.get("XZG_PASSWORD")
        self._token: Optional[str] = None
        self._tenant_id: Optional[int] = None
        self._token_expire_at: float = 0.0

    # ------------------------------------------------------------------ 日志

    def _log(self, msg: str) -> None:
        if self.verbose:
            print(msg, flush=True)

    # ------------------------------------------------------------------ 请求

    def _assert_allowed(self, method: str, path: str) -> None:
        """出站端点闸门：只读白名单 ∪ 写白名单（后者仅 ``BaseProduct/Update`` 一条）。

        写白名单是老板单独授权的最小口子；**读写两侧都必须显式登记**，其余一律拒绝。
        """
        key = (method.upper(), path)
        if key in READONLY_ENDPOINTS or key in WRITE_ENDPOINTS:
            return
        raise ReadOnlyViolation(
            "拒绝调用白名单外端点：%s %s。上游数据是权威来源，同步器禁止未授权写操作。"
            "只读端点加进 READONLY_ENDPOINTS；写端点需老板单独授权后加进 WRITE_ENDPOINTS。"
            % (method.upper(), path)
        )

    #: 历史名兼容（既有调用点与测试用旧名）
    _assert_readonly = _assert_allowed

    def _call_with_reauth(
        self,
        method: str,
        path: str,
        body: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        """带「未授权自愈」的只读调用（S17）。

        缓存 token 被上游拒绝（过期/被顶/密码变更）时：丢弃内存与磁盘 token →
        重新登录 → 重试一次，保证「登录后取最新」而不是拿着废 token 报错。
        重登只做一次，绝不死循环。
        """
        assert_allowed_api_url(self.base_url + path, self.allowed_hosts)  # 出站边界：协议+主机白名单
        try:
            return self._request(method, path, body)
        except XzgApiError as exc:
            if not _is_unauthorized_error(exc):
                raise
            self._log("上游判定未授权（%s）→ 丢弃缓存 token 重新登录后重试一次" % exc)
            self._invalidate_token()
            self.authenticate()
            return self._request(method, path, body)

    def _invalidate_token(self) -> None:
        """丢弃内存 token 与磁盘缓存（重登前清理；缓存删除失败不阻断重登）。"""
        self._token = None
        self._tenant_id = None
        self._token_expire_at = 0.0
        try:
            os.remove(self.cache_path)
        except OSError:
            pass

    def _request(
        self,
        method: str,
        path: str,
        body: Optional[Dict[str, Any]] = None,
        auth: bool = True,
    ) -> Dict[str, Any]:
        """发一个请求。``auth=True`` 时自动带 Bearer token 与 Abp.TenantId。"""
        self._assert_readonly(method, path)

        if auth:
            self.ensure_token()

        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(self.base_url + path, data=data, method=method.upper())
        req.add_header("Content-Type", "application/json")
        if auth:
            self._apply_auth_headers(req)
        return self._send(req, path)

    def _request_get(self, path: str, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """GET 只读请求：参数序列化到 **query**（上游 GET 端点如 ``BaseProduct/Get?id=``
        要的是 query，塞 body 不生效——2026-09-13 实测踩过）。"""
        self._assert_readonly("GET", path)
        if not path.startswith("/api/") or ".." in path:
            raise XzgError("拒绝请求非法路径：%r" % path)
        self.ensure_token()
        query = ""
        if params:
            pairs = ["%s=%s" % (k, quote(str(params[k]), safe=""))
                     for k in sorted(params) if params[k] is not None]
            if pairs:
                query = "?" + "&".join(pairs)
        req = urllib.request.Request(self.base_url + path + query, method="GET")
        req.add_header("Accept", "application/json")
        self._apply_auth_headers(req)
        return self._send(req, path)

    #: 两次上游**写**之间的最小间隔（秒）。程序化连击既给上游压力，也让流量形态异常；
    #: 平稳节流是工程稳健性的一部分——尤其将来做批量改时。
    WRITE_MIN_INTERVAL_SEC = 1.0

    def _throttle_write(self) -> None:
        """写前节流：距上次写不足 :attr:`WRITE_MIN_INTERVAL_SEC` 就等一下。"""
        last = getattr(self, "_last_write_at", 0.0)
        gap = time.time() - last
        if last and gap < self.WRITE_MIN_INTERVAL_SEC:
            time.sleep(self.WRITE_MIN_INTERVAL_SEC - gap)
        self._last_write_at = time.time()

    def _apply_auth_headers(self, req: Any) -> None:
        """统一注入鉴权与网关头。

        除了 Bearer + ``Abp.TenantId``，还带上浏览器客户端同款的
        ``ClientId`` / ``OcelotRequestId``（值都是企业编号，即 CompanyCode）。
        这两个是 ABP/Ocelot 网关的标准字段——**目前不强制**（我们不带头也能调通），
        补上是为了**协议完整性**：将来网关要是加了校验，不至于莫名其妙失败。

        注：这不是"伪装客户端"。据实标注自身身份（User-Agent 保持本项目的名字），
        只是把请求头补全到与官方客户端同一套约定。
        """
        req.add_header("Authorization", "Bearer " + (self._token or ""))
        req.add_header("Abp.TenantId", str(self._tenant_id))
        if self.tenancy_name:
            req.add_header("ClientId", self.tenancy_name)
            req.add_header("OcelotRequestId", self.tenancy_name)

    def _send(self, req: Any, path: str) -> Dict[str, Any]:
        """统一出口：出站边界断言紧邻 urlopen，异常归一为 XzgApiError。"""
        assert_allowed_api_url(req.full_url, self.allowed_hosts)  # 协议 + 主机白名单
        try:
            with urllib.request.urlopen(req, timeout=self.timeout, context=_default_ssl_context()) as resp:
                raw = resp.read().decode("utf-8")
        except urllib.error.HTTPError as exc:  # pragma: no cover - 依赖网络
            raise XzgApiError("HTTP %s %s 失败：%s" % (exc.code, path, exc.reason)) from exc
        except urllib.error.URLError as exc:  # pragma: no cover - 依赖网络
            raise XzgApiError("网络错误 %s：%s" % (path, exc.reason)) from exc
        return self._parse_payload(path, raw)

    # ------------------------------------------------------------------ 认证

    def ensure_token(self) -> str:
        """返回可用 token；缓存命中且未过期则直接复用，否则重新登录。"""
        if self._token_is_valid():
            return self._token or ""
        if self._load_cached_token():
            return self._token or ""
        return self.authenticate()

    def _token_is_valid(self) -> bool:
        return bool(self._token) and time.time() < self._token_expire_at

    def _load_cached_token(self) -> bool:
        if not os.path.exists(self.cache_path):
            return False
        try:
            with open(self.cache_path, "r", encoding="utf-8") as fh:
                cached = json.load(fh)
        except (OSError, ValueError):
            return False

        if (
            cached.get("username") != self.username
            or cached.get("tenancy_name") != self.tenancy_name
            or cached.get("base_url") != self.base_url
        ):
            return False

        expires_at = float(cached.get("expires_at") or 0)
        if not cached.get("access_token") or time.time() + _TOKEN_EXPIRY_MARGIN_SEC >= expires_at:
            return False

        self._token = cached["access_token"]
        self._tenant_id = cached.get("tenant_id")
        self._token_expire_at = expires_at
        self._log("复用缓存 token %s（有效期至 %s）"
                  % (mask_token(self._token), time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(expires_at))))
        return True

    def authenticate(self) -> str:
        """登录并缓存 token。缺少密码时给出清晰提示，但**不回显密码**。"""
        if not self._password:
            raise XzgAuthError(
                "缺少熊掌柜密码：请先设置环境变量 XZG_PASSWORD 再运行（密码不入库、不写日志）。\n"
                "  示例： $env:XZG_PASSWORD='<密码>'; python scripts\\sync-xzg.py"
            )

        payload = self._request(
            "POST",
            "/api/TokenAuth/Authenticate",
            {
                "tenancyName": self.tenancy_name,
                "userNameOrEmailAddress": self.username,
                "password": self._password,
                "rememberClient": True,
            },
            auth=False,
        )
        result = payload.get("result") or {}
        token = result.get("accessToken")
        if not token:
            raise XzgAuthError("登录响应中没有 accessToken（账号/密码/企业编号可能有误）")

        expire_in = int(result.get("expireInSeconds") or 86400)
        self._token = token
        self._tenant_id = result.get("tenantId")
        self._token_expire_at = time.time() + expire_in
        self._save_cached_token()
        self._log("登录成功 company=%s user=%s tenantId=%s token=%s"
                  % (result.get("companyName"), result.get("userName"),
                     self._tenant_id, mask_token(token)))
        return token

    def _save_cached_token(self) -> None:
        # 2026-09-09 审计修复：F6 内联守卫以 CWD 为沙箱根，而默认缓存路径在
        # ~/.cache/ 下必然越界，导致登录成功后同步整体崩溃。改用统一原语
        # confine（根=用户缓存目录），且缓存失败降级为告警不阻断认证。
        try:
            directory = os.path.dirname(self.cache_path)
            cache_file = _f6c(self.cache_path, base=os.path.expanduser("~/.cache"))  # F6 path guard
            os.makedirs(directory, exist_ok=True)
            cache_file = os.path.realpath(cache_file)  # F6: sink 前规范化
            with open(cache_file, "w", encoding="utf-8") as fh:
                json.dump(
                    {
                        "access_token": self._token,
                        "tenant_id": self._tenant_id,
                        "expires_at": self._token_expire_at,
                        "username": self.username,
                        "tenancy_name": self.tenancy_name,
                        "base_url": self.base_url,
                    },
                    fh,
                )
            # Windows 上 chmod 只能控制只读位，做最大努力即可
            os.chmod(self.cache_path, 0o600)
        except (OSError, ValueError) as exc:  # pragma: no cover - 依赖文件系统
            # 缓存失败（含路径守卫拒绝）只降级为告警：认证已成功，绝不因此打断同步
            self._log("警告：token 缓存写入失败（%s），本次仅在内存中生效" % exc)

    # -------------------------------------------------------------- 商品拉取

    def fetch_page(self, skip_count: int, max_result_count: int = 500) -> Dict[str, Any]:
        """拉一页商品。注意：body 只传这两个参数——实测多传参数会触发上游 500。

        走 :meth:`_call_with_reauth`：token 被上游拒绝时自动重登重试一次。
        """
        payload = self._call_with_reauth(
            "POST",
            "/api/services/app/BaseProduct/GetAll",
            {"skipCount": skip_count, "maxResultCount": max_result_count},
        )
        return payload.get("result") or {}

    def iter_products(
        self, page_size: int = 500, limit: Optional[int] = None, on_page=None
    ) -> Iterator[Dict[str, Any]]:
        """分页遍历全部商品，逐条 yield。

        :param limit: 只取前 N 条（冒烟用），None 表示不限。
        :param on_page: 进度回调 ``on_page(done, total)``（S17 进度条；顺序路径用）。
        """
        skip = 0
        emitted = 0
        done_pages = 0
        total: Optional[int] = None
        while True:
            result = self.fetch_page(skip, page_size)
            if total is None:
                total = result.get("totalCount")
                self._log("上游商品总数：%s" % total)
            batch = result.get("items") or []
            if not batch:
                break
            for item in batch:
                yield item
                emitted += 1
                if limit is not None and emitted >= limit:
                    return
            skip += page_size
            done_pages += 1
            if on_page is not None and total:
                pages_total = max(done_pages, -(-total // page_size))
                try:
                    on_page(min(done_pages, pages_total), pages_total)
                except Exception:  # noqa: BLE001 - 进度回调绝不影响拉取
                    pass
            if total is not None and skip >= total:
                break
            if self.page_interval > 0:
                time.sleep(self.page_interval)

    def fetch_all_products(
        self, page_size: int = 500, limit: Optional[int] = None, on_page=None
    ) -> List[Dict[str, Any]]:
        """一次性拉回全部商品（12,631 条约 40 秒）。顺序路径（并行守卫未过时的回退）。"""
        return list(self.iter_products(page_size=page_size, limit=limit, on_page=on_page))

    # ------------------------------------------------- S17 并行全量拉取（快路径）

    def fetch_all_products_fast(
        self, page_size: int = 500, workers: int = 6, on_page=None
    ) -> Optional[List[Dict[str, Any]]]:
        """并行全量拉取（守卫式快路径）。**任何不确定都返回 None**，
        由调用方回退 :meth:`fetch_all_products` 顺序路径——速度可退让，正确性不退让。

        做法：首屏顺序拉定 ``totalCount`` 与上游实际页长，余页按 offset 分片交给
        线程池（线程内复用 HTTPS 长连接）；合流后过三重守卫：
        ① 汇总条数 == totalCount；② 每条 id 均真值且整体严格升序（升序蕴含唯一，可检出
        翻页错位/重复）；③ 末页非空且未截断。任一不过 → None。

        任一分页失败/未授权 → 直接放弃并行（不做并发重登），交给顺序路径重拉。
        """
        items: List[Dict[str, Any]] = []
        try:
            first = self.fetch_page(0, page_size)
        except XzgError:
            return None  # 首屏失败交顺序路径处理（含重登/报错语义）
        batch = first.get("items")
        total = first.get("totalCount")
        if not isinstance(total, int) or total < 0 or not isinstance(batch, list):
            return None
        if total == 0:
            return []
        if not batch or len(batch) > total:
            return None

        page_len = len(batch)  # 上游可能按自己的上限截断 maxResultCount，用实测页长翻页
        offsets = list(range(page_len, total, page_len))
        items.extend(batch)
        total_pages = 1 + len(offsets)
        if on_page is not None:
            on_page(1, total_pages)  # 进度上报（S17 进度条）：首屏已到手
        if offsets:
            pages = self._fetch_pages_parallel(offsets, page_size, workers,
                                               on_page=on_page, total_pages=total_pages)
            if pages is None:
                return None
            for off in offsets:
                chunk = pages.get(off)
                if not chunk:
                    return None  # 末页/中间页为空 = 截断信号
                items.extend(chunk)

        if len(items) != total:
            return None

        last_id: Optional[int] = None
        for item in items:
            raw_id = (item or {}).get("id")
            if raw_id in (None, ""):
                return None
            try:
                cur = int(raw_id)
            except (TypeError, ValueError):
                return None
            if last_id is not None and cur <= last_id:
                return None  # 翻页错位/重复 = 抓到的可能是移动中的数据快照
            last_id = cur
        return items

    def _fetch_pages_parallel(
        self, offsets: List[int], page_size: int, workers: int,
        on_page=None, total_pages: Optional[int] = None,
    ) -> Optional[Dict[int, List[Dict[str, Any]]]]:
        """按 offset 分片并行拉页；任一片失败返回 None（不返回部分结果）。"""
        assert_allowed_api_url(self.base_url, self.allowed_hosts)  # 出站边界（同 _do_request）
        parts = urlsplit(self.base_url)
        conn_factory = _make_conn_factory(parts, self.timeout)

        n = max(1, min(workers, len(offsets)))
        chunks = [offsets[i::n] for i in range(n)]
        chunks = [c for c in chunks if c]

        pages: Dict[int, List[Dict[str, Any]]] = {}
        lock = threading.Lock()
        failed = threading.Event()

        def run_chunk(chunk: List[int]) -> None:
            conn = None
            for off in chunk:
                if failed.is_set():
                    break
                data: Optional[List[Dict[str, Any]]] = None
                for attempt in range(3):
                    try:
                        if conn is None:
                            conn = conn_factory()
                        data = self._page_via_conn(conn, off, page_size)
                        break
                    except XzgApiError as exc:
                        if _is_unauthorized_error(exc):
                            failed.set()  # 并行中不做并发重登：交给顺序路径重来
                            return
                        _close_quiet(conn)
                        conn = None
                        time.sleep(0.4 * (attempt + 1))
                    except (OSError, http.client.HTTPException, ValueError):
                        _close_quiet(conn)
                        conn = None
                        time.sleep(0.4 * (attempt + 1))
                if data is None:
                    failed.set()
                    break
                with lock:
                    pages[off] = data
                    done = 1 + len(pages)  # 首屏已算 1 页
                    if on_page is not None:
                        try:
                            on_page(min(done, total_pages or done), total_pages or done)
                        except Exception:  # noqa: BLE001 - 进度回调绝不影响拉取
                            pass
                if self.page_interval > 0:
                    time.sleep(self.page_interval)
            _close_quiet(conn)

        with ThreadPoolExecutor(max_workers=len(chunks)) as pool:
            list(pool.map(run_chunk, chunks))

        if failed.is_set() or len(pages) != len(offsets):
            return None
        return pages

    def _page_via_conn(
        self, conn: Any, skip_count: int, page_size: int
    ) -> List[Dict[str, Any]]:
        """经长连接拉一页商品（与 :meth:`fetch_page` 同请求形状：只传两个参数）。"""
        path = "/api/services/app/BaseProduct/GetAll"
        self._assert_readonly("POST", path)
        body = json.dumps({"skipCount": skip_count, "maxResultCount": page_size}).encode("utf-8")
        conn.request("POST", path, body, {
            "Content-Type": "application/json",
            "Accept-Encoding": "gzip",
            "Authorization": "Bearer " + (self._token or ""),
            "Abp.TenantId": str(self._tenant_id),
        })
        resp = conn.getresponse()
        raw = resp.read()
        if (resp.getheader("Content-Encoding") or "").lower() == "gzip":
            raw = gzip.decompress(raw)
        payload = json.loads(raw.decode("utf-8"))
        if isinstance(payload, dict) and payload.get("success") is False:
            err = payload.get("error") or {}
            message = err.get("message") if isinstance(err, dict) else str(err)
            raise XzgApiError(
                "上游返回失败（%s）：%s" % (path, message or "unknown"), payload=payload
            )
        result = payload.get("result") or {}
        items = result.get("items")
        return items if isinstance(items, list) else []

    # ------------------------------------------- XZG-API-RO：销售单只读查询

    def fetch_sales_bill_page(
        self,
        start_date: str,
        end_date: str,
        skip_count: int = 0,
        max_result_count: int = 500,
    ) -> Dict[str, Any]:
        """拉一页销售单报表（``Report/SaleBill/Sale/Query``）。

        **date 参数是硬要求**：不传 ``startDate``/``endDate`` 时上游只回当天
        （2026-09-13 实测无参数 totalCount=19、全量区间=2755），且传错参数名
        （beginDate 等）会被静默忽略、不报错。故此处对空值直接拒绝。

        :raises XzgError: 日期为空/区间反了——宁可报错也不静默拿残缺数据。
        """
        if not start_date or not end_date:
            raise XzgError(
                "销售报表查询必须显式给 startDate/endDate（上游默认只回当天，"
                "实测 19 条 vs 全量 2755 条），参数名固定为 %s" % (SALES_DATE_PARAMS,)
            )
        if start_date > end_date:
            raise XzgError("日期区间反了：startDate=%s > endDate=%s" % (start_date, end_date))

        payload = self._call_with_reauth(
            "POST",
            "/api/services/app/V1/Erp/Report/SaleBill/Sale/Query",
            {
                "startDate": start_date,
                "endDate": end_date,
                "skipCount": skip_count,
                "maxResultCount": max_result_count,
            },
        )
        return payload.get("result") or {}

    def iter_sales_bills(
        self,
        start_date: str,
        end_date: str,
        page_size: int = 500,
        limit: Optional[int] = None,
        on_page=None,
    ) -> Iterator[Dict[str, Any]]:
        """分页遍历销售单，逐条 yield（形状同 :meth:`iter_products`）。

        带两道守卫：条数不得超过上游 ``totalCount``；翻页空页即停（防死循环）。
        """
        skip = 0
        emitted = 0
        total: Optional[int] = None
        done_pages = 0
        while True:
            result = self.fetch_sales_bill_page(start_date, end_date, skip, page_size)
            if total is None:
                total = int(result.get("totalCount") or 0)
                self._log("上游销售单总数：%s（%s ~ %s）" % (total, start_date, end_date))
            batch = result.get("items") or []
            if not batch:
                break
            for item in batch:
                yield item
                emitted += 1
                if limit is not None and emitted >= limit:
                    return
            skip += page_size
            done_pages += 1
            if on_page is not None and total:
                pages_total = max(done_pages, -(-total // page_size))
                try:
                    on_page(min(done_pages, pages_total), pages_total)
                except Exception:  # noqa: BLE001 - 进度回调绝不影响拉取
                    pass
            if skip >= total:
                break
            if self.page_interval > 0:
                time.sleep(self.page_interval)

    def fetch_all_sales_bills(
        self,
        start_date: str,
        end_date: str,
        page_size: int = 500,
        limit: Optional[int] = None,
        on_page=None,
    ) -> List[Dict[str, Any]]:
        """一次性拉回区间内全部销售单（实测 2755 条约 30 页 / 20 秒）。"""
        return list(self.iter_sales_bills(
            start_date, end_date, page_size=page_size, limit=limit, on_page=on_page))

    def export_sales_bill_list(self, start_date: str, end_date: str) -> str:
        """触发「销售出库单」xlsx 异步导出，返回下载 URL（含中文文件名）。"""
        if not start_date or not end_date:
            raise XzgError("销售出库单导出同样必须给 startDate/endDate")
        payload = self._call_with_reauth(
            "POST",
            "/api/services/app/V1/Erp/Report/SaleBill/ExportSaleBillList",
            {"startDate": start_date, "endDate": end_date},
        )
        url = payload.get("result")
        if not isinstance(url, str) or not url.startswith("https://"):
            raise XzgApiError("销售出库单导出未返回下载链接：%s" % str(url)[:120])
        return url

    # ------------------------------------------------ 条码写回（唯一授权的写口子）

    def fetch_product_full(self, product_id: str) -> Dict[str, Any]:
        """取商品**全对象**（``BaseProduct/Get``，73 字段含嵌套规格与条码表）。

        与 ``GetAll`` 的列表项不同，这是写回时提交的形状——返回值可直接改字段后
        交给 :meth:`update_product_barcode`。
        """
        try:
            payload = self._request_get("/api/services/app/BaseProduct/Get", {"id": product_id})
        except XzgApiError as exc:
            if not _is_unauthorized_error(exc):
                raise
            self._log("上游判定未授权（%s）→ 丢弃缓存 token 重新登录后重试一次" % exc)
            self._invalidate_token()
            self.authenticate()
            payload = self._request_get("/api/services/app/BaseProduct/Get", {"id": product_id})
        result = payload.get("result")
        if not isinstance(result, dict):
            raise XzgApiError("取商品全对象失败，result=%s" % type(result).__name__)
        return result

    def update_product_barcode(
        self,
        original: Dict[str, Any],
        new_barcode: str,
    ) -> Dict[str, Any]:
        """**只改条码**写回上游（老板 2026-09-13 单独授权的最小写口子）。

        安全设计（三道，缺一不可）：

        1. **原对象强制**：``original`` 必须是用 :meth:`fetch_product_full` 抓的
           原对象——它既是比对基准，也是回滚存档。**待提交的对象一律由它重建**，
           调用方传什么进来都不采信，防夹带；
        2. **用途锁**：逐字段比对，差异一旦超出 :data:`BARCODE_WRITE_ALLOWED_FIELDS`
           （``barCode`` / ``baseProductStandardList``）立即抛错**不发请求**——
           这个口子永远只能改条码；
        3. **三处齐写**：顶层 ``barCode``、规格层 ``barCode``、
           规格的 ``baseProductStandardBarCodeList[].barCode``——只改一处会让
           上游数据自相矛盾。

        :return: ``{"changed": [...], "readback": {...}, "audit": {...}}``，
            含回读校验结果，调用方据此判断是否真的写成功。
        :raises XzgError: 条码为空 / 与原值相同 / 用途锁不过 / 无条码字段可改。
        """
        if not new_barcode or not str(new_barcode).strip():
            raise XzgError("新条码不能为空")
        if not isinstance(original, dict) or not original.get("id"):
            raise XzgError(
                "original 必须是 fetch_product_full 抓到的商品全对象（含 id）："
                "它既是用途锁的比对基准，也是回滚存档的依据")

        new_barcode = str(new_barcode).strip()
        if new_barcode == str(original.get("barCode") or "").strip():
            raise XzgError("新条码与原条码相同，无需写回：%s" % new_barcode)

        # ① 用途锁：**以服务端现值重建待提交对象**，调用方传进来的对象一律不采信。
        #    否则「调用方自己就把价格改了、再把改过的对象当 original 递进来」能绕过
        #    闸门（2026-09-13 异常路径单测抓出的真实缺陷）。原条码取自其 barCode
        #    字段（只用于"同值拒绝"判断，篡改成别的值也不会造成越权）。
        before = self.fetch_product_full(str(original["id"]))
        if (original.get("code") or before.get("code")) != before.get("code") \
                or (original.get("name") or before.get("name")) != before.get("name"):
            raise XzgError(
                "用途锁拦下：传入的 original 与服务端现值不符（可能被改动过），"
                "请重新用 fetch_product_full 抓取后再提交")
        product = json.loads(json.dumps(before))
        changed = self._apply_barcode(product, new_barcode)
        if not changed:
            raise XzgError("该商品没有可写的条码字段（barCode / 条码表均为空）")
        self._assert_only_barcode_changed(before, product)

        # ② 节流：两次写之间留最小间隔——程序化连击会给上游压力，平稳流量也更安全
        self._throttle_write()

        # ③ 提交（带未授权自愈：token 被顶时丢缓存重登后重试一次，与读路径同待遇）
        try:
            self._request("PUT", "/api/services/app/BaseProduct/Update", product)
        except XzgApiError as exc:
            if not _is_unauthorized_error(exc):
                raise
            self._log("写回时上游判定未授权（%s）→ 丢缓存重登后重试一次" % exc)
            self._invalidate_token()
            self.authenticate()
            self._request("PUT", "/api/services/app/BaseProduct/Update", product)

        # ③ 回读校验 + 审计
        readback = self.fetch_product_full(str(original.get("id")))
        audit = {
            "product_id": original.get("id"),
            "sku": original.get("code"),
            "name": original.get("name"),
            "old_barcode": original.get("barCode"),
            "new_barcode": new_barcode,
            "changed_fields": changed,
            "readback_barcode": readback.get("barCode"),
            "readback_ok": str(readback.get("barCode") or "") == new_barcode,
        }
        self._log("条码写回 %s（%s）：%s → %s，回读%s"
                  % (audit["sku"], audit["name"], audit["old_barcode"], new_barcode,
                     "一致" if audit["readback_ok"] else "**不一致，需复查**"))
        return {"changed": changed, "readback": readback, "audit": audit}

    def restore_product(self, archived: Dict[str, Any]) -> Dict[str, Any]:
        """**回滚**：把写前存档的商品对象原样写回上游。

        与 :meth:`update_product_barcode` 的分工：那条路是"从无码到有码 / 换码"，
        本条是"回到写之前的状态"——包括**把条码清空**（无条码商品写完再回滚时，
        目标就是回到 None，普通换码方法拒绝空值，故必须走这条路）。

        存档必须来自 :meth:`fetch_product_full`（服务端原生形状），因此原样回灌
        不会夹带本地构造的字段。回滚同样要过用途锁与回读校验。
        """
        if not isinstance(archived, dict) or not archived.get("id"):
            raise XzgError("archive 必须是 fetch_product_full 抓到的商品全对象（含 id）")
        target_id = str(archived["id"])
        current = self.fetch_product_full(target_id)
        payload = json.loads(json.dumps(archived))
        # 用途锁：回滚差异同样只允许落在条码字段（存档与现值本就同源，这里防被改过的存档）
        self._assert_only_barcode_changed(current, payload)

        self._throttle_write()
        try:
            self._request("PUT", "/api/services/app/BaseProduct/Update", payload)
        except XzgApiError as exc:
            if not _is_unauthorized_error(exc):
                raise
            self._invalidate_token()
            self.authenticate()
            self._request("PUT", "/api/services/app/BaseProduct/Update", payload)

        readback = self.fetch_product_full(target_id)
        audit = {
            "product_id": target_id,
            "sku": archived.get("code"),
            "restored_barcode": archived.get("barCode"),
            "readback_barcode": readback.get("barCode"),
            "readback_ok": (readback.get("barCode") or "") == (archived.get("barCode") or ""),
        }
        self._log("条码回滚 %s：回到 %r，回读%s"
                  % (audit["sku"], audit["restored_barcode"],
                     "一致" if audit["readback_ok"] else "**不一致，需复查**"))
        return {"readback": readback, "audit": audit}

    # ------------------------------------------------- 图片上行（老板 2026-09-14 放行）

    def upload_product_image(self, image_base64: str, img_type: Any = 1) -> str:
        """把一张商品图（base64）传到上游，返回服务端相对路径 ``img_Url``。

        老板 2026-09-14 令「再放行图片回写」。链路为网页版逐张配图同款实证形态
        （见 WRITE_ENDPOINTS 上方注释）：``POST CommonUploadImg/UpLoadImg``
        ``{img_Type, img_Base64}`` → ``result.img_Url``（相对路径，多图由调用方
        分号 join 后走 :meth:`update_product_imgurl` 写入商品）。

        :param image_base64: **必须带 ``data:image/<fmt>;base64,`` 前缀**
            （2026-09-14 真机定案：裸 base64 被上游拒 "Specified method is not
            supported."，带 dataURL 前缀才 200）。本方法如实透传，不自动补前缀。
        :param img_type: 图片类型枚举，**商品场景实测定案 = 1**（海报场景见 2/3）。
        :return: 服务端相对路径（实测形态 ``'/{企业id}/ProductImg/{UUID}.jpeg'``，
            显示时拼基址；多图由调用方分号 join 后走 :meth:`update_product_imgurl`）。
        :raises XzgError: 入参为空 / 上游未返回 img_Url。
        """
        if not image_base64 or not str(image_base64).strip():
            raise XzgError("图片 base64 不能为空")
        self._throttle_write()
        payload = {"img_Type": img_type, "img_Base64": str(image_base64).strip()}
        resp = self._call_with_reauth(
            "POST", "/api/services/app/CommonUploadImg/UpLoadImg", payload)
        result = resp.get("result")
        img_url = ""
        if isinstance(result, dict):
            img_url = str(result.get("img_Url") or "").strip()
        elif isinstance(result, str):
            # 防御：result 直接就是路径串的形态（网页版现场为 dict，未实测不排除）
            img_url = result.strip()
        if not img_url:
            raise XzgApiError("上游未返回图片路径（result=%s）"
                              % json.dumps(result, ensure_ascii=False)[:120],
                              payload=resp)
        return img_url

    def update_product_imgurl(
        self,
        original: Dict[str, Any],
        new_imgurl: str,
    ) -> Dict[str, Any]:
        """**只改图片**写回上游（老板 2026-09-14 放行的第二个最小写口子）。

        与 :meth:`update_product_barcode` 完全同构的三道防线，仅字段不同：

        1. **原对象强制 + 提交体重建**：待提交对象一律由服务端现值重建，
           调用方传什么都不采信，防夹带；
        2. **用途锁**：逐字段比对，差异超出**顶层 ``imgUrl`` 一个字段**立即抛错
           不发请求——价格/条码/规格等全部动不了（imgUrl 是纯标量列，锁比条码更紧）；
        3. **回读校验**：写完再读一遍比对 ``imgUrl``，对不上即 ``readback_ok=False``。

        :param new_imgurl: 目标 ``imgUrl`` 值 = 多图相对路径按顺序 **分号 join**
            （网页版口径：``urls.join(";")`` 写 / ``split(";")`` 读）；
            传空串 = 清空商品图片（回滚手段，区别于条码口子的"拒绝空值"）。
        :return: ``{"changed", "old_imgurl", "new_imgurl", "readback", "audit"}``。
        :raises XzgError: original 非 fetch_product_full 全对象 / 用途锁不过。
        """
        if not isinstance(original, dict) or not original.get("id"):
            raise XzgError(
                "original 必须是 fetch_product_full 抓到的商品全对象（含 id）："
                "它既是用途锁的比对基准，也是回滚存档的依据")
        if new_imgurl is None:
            raise XzgError("new_imgurl 不允许 None（清空请显式传空串）")
        new_imgurl = str(new_imgurl)

        # ① 以服务端现值重建提交体（与条码口子同款防夹带：传入对象只做身份核对）
        before = self.fetch_product_full(str(original["id"]))
        if (original.get("code") or before.get("code")) != before.get("code") \
                or (original.get("name") or before.get("name")) != before.get("name"):
            raise XzgError(
                "用途锁拦下：传入的 original 与服务端现值不符（可能被改动过），"
                "请重新用 fetch_product_full 抓取后再提交")
        product = json.loads(json.dumps(before))
        old_imgurl = product.get("imgUrl")
        if old_imgurl == new_imgurl:
            return {"changed": [], "old_imgurl": old_imgurl, "new_imgurl": new_imgurl,
                    "readback": before,
                    "audit": {"product_id": original.get("id"), "sku": original.get("code"),
                              "readback_ok": True, "no_op": True}}
        product["imgUrl"] = new_imgurl
        bad = [p for p in _diff_paths(before, product) if not _is_imgurl_path(p)]
        if bad:
            raise XzgError(
                "用途锁拦下：差异超出图片字段 → %s（本口子只许改 imgUrl，不许动价格/条码/规格）"
                % bad[:10])

        # ② 节流 + 提交（未授权自愈重登重试一次，与条码同待遇）
        self._throttle_write()
        try:
            self._request("PUT", "/api/services/app/BaseProduct/Update", product)
        except XzgApiError as exc:
            if not _is_unauthorized_error(exc):
                raise
            self._log("图片写回时上游判定未授权（%s）→ 丢缓存重登后重试一次" % exc)
            self._invalidate_token()
            self.authenticate()
            self._request("PUT", "/api/services/app/BaseProduct/Update", product)

        # ③ 回读校验 + 审计
        readback = self.fetch_product_full(str(original["id"]))
        audit = {
            "product_id": original.get("id"),
            "sku": original.get("code"),
            "name": original.get("name"),
            "old_imgurl": old_imgurl,
            "new_imgurl": new_imgurl,
            "readback_imgurl": readback.get("imgUrl"),
            "readback_ok": (readback.get("imgUrl") or "") == new_imgurl,
        }
        self._log("图片写回 %s（%s）：imgUrl 回读%s"
                  % (audit["sku"], audit["name"],
                     "一致" if audit["readback_ok"] else "**不一致，需复查**"))
        return {"changed": ["imgUrl"], "old_imgurl": old_imgurl,
                "new_imgurl": new_imgurl, "readback": readback, "audit": audit}

    @staticmethod
    def _apply_barcode(product: Dict[str, Any], new_barcode: str) -> List[str]:
        """把条码写到三处；返回实际改动的字段路径列表。

        **无条码商品（条码实体表为空）要新建一条实体记录**——2026-09-13 实测：
        只改顶层 ``barCode`` 而不建实体，上游会静默丢弃（回读仍是 None）。
        新记录只填 ``productId`` / ``baseProductStandardId`` / ``barCode``，
        ``id`` 留给上游分配（不臆造主键）。
        """
        changed: List[str] = []
        if product.get("barCode") != new_barcode:
            product["barCode"] = new_barcode
            changed.append("barCode")
        for std in product.get("baseProductStandardList") or []:
            if not isinstance(std, dict):
                continue
            if std.get("barCode") not in (None, new_barcode):
                std["barCode"] = new_barcode
                changed.append("baseProductStandardList[].barCode")
            codes = std.get("baseProductStandardBarCodeList")
            if codes is None:
                codes = []
                std["baseProductStandardBarCodeList"] = codes
            if not codes:
                # 空表 = 该商品原本没有条码 → 新建实体（否则上游丢弃本次写入）
                std["baseProductStandardBarCodeList"] = [{
                    "productId": product.get("id"),
                    "baseProductStandardId": std.get("id"),
                    "barCode": new_barcode,
                }]
                changed.append("baseProductStandardBarCodeList[].barCode(新建)")
                continue
            for bc in codes:
                if isinstance(bc, dict) and bc.get("barCode") != new_barcode:
                    bc["barCode"] = new_barcode
                    changed.append("baseProductStandardBarCodeList[].barCode")
        return changed

    @staticmethod
    def _assert_only_barcode_changed(before: Dict[str, Any], after: Dict[str, Any]) -> None:
        """用途锁：差异**逐条**只允许落在条码字段本身，否则拒绝提交。

        注意不能只按「首段是否在白名单」放行——``baseProductStandardList`` 之下
        还有价格/规格等字段，那样等于把整棵规格子树都开了口子（2026-09-13
        单测抓出的真实缺陷，已收紧为逐路径校验）。
        """
        bad = [p for p in _diff_paths(before, after) if not _is_barcode_path(p)]
        if bad:
            raise XzgError(
                "用途锁拦下：差异超出条码字段 → %s（本口子只许改条码，不许动价格/规格）"
                % bad[:10])

    # ---------------------------------------------------- S7 全维度：库存/规格

    def fetch_standards(self, product_id: str) -> List[Dict[str, Any]]:
        """拉单个商品的多规格子表（GetAllProductStandard4Tc，实测按 productId 单查）。"""
        payload = self._call_with_reauth(
            "POST",
            "/api/services/app/BaseProduct/GetAllProductStandard4Tc",
            {"productId": product_id},
        )
        result = payload.get("result") or {}
        if isinstance(result, list):
            return result
        return result.get("items") or []

    def export_stock_condition(self) -> str:
        """触发「库存状况表」异步导出，返回 xlsx 下载 URL（含中文文件名）。"""
        payload = self._call_with_reauth(
            "POST",
            "/api/services/app/V1/Erp/Report/Stock/StockCondition/Export",
            {},
        )
        url = payload.get("result")
        if not isinstance(url, str) or not url.startswith("https://"):
            raise XzgApiError("库存状况表导出未返回下载链接：%s" % str(url)[:120])
        return url

    def fetch_stock_condition_page(
        self,
        skip_count: int = 0,
        max_result_count: int = 500,
        keyword: Optional[str] = None,
    ) -> Dict[str, Any]:
        """拉一页「库存状况表」JSON（``Report/Stock/StockCondition/Get``）。

        比 xlsx 导出链路少三步（触发导出→下载文件→解析 XML），一次请求直接拿结构化行。

        :param keyword: 给商品编号或条码时上游按关键字过滤（实测精确命中单行）——
            用于「单个商品实时库存」场景，不必拉全量。
        """
        body: Dict[str, Any] = {"skipCount": skip_count, "maxResultCount": max_result_count}
        if keyword:
            body["keyword"] = keyword
        payload = self._call_with_reauth(
            "POST",
            "/api/services/app/V1/Erp/Report/Stock/StockCondition/Get",
            body,
        )
        return payload.get("result") or {}

    def fetch_all_stock_condition(
        self,
        page_size: int = 500,
        keyword: Optional[str] = None,
        on_page=None,
    ) -> List[Dict[str, Any]]:
        """分页拉全量库存状况行（实测 12670 条约 8~30 秒）。

        带与商品拉取同源的守卫：条数不超过上游 ``totalCount``、空页即停（防死循环）。
        """
        items: List[Dict[str, Any]] = []
        skip = 0
        total: Optional[int] = None
        while True:
            result = self.fetch_stock_condition_page(skip, page_size, keyword)
            if total is None:
                total = int(result.get("totalCount") or 0)
                self._log("上游库存状况行数：%s%s" % (
                    total, "（keyword=%s）" % keyword if keyword else ""))
            batch = result.get("items") or []
            if not batch:
                break
            items.extend(batch)
            skip += page_size
            if on_page is not None and total:
                pages_total = max(1, -(-total // page_size))
                try:
                    on_page(min(skip // page_size, pages_total), pages_total)
                except Exception:  # noqa: BLE001 - 进度回调绝不影响拉取
                    pass
            if skip >= total:
                break
            if self.page_interval > 0:
                time.sleep(self.page_interval)
        return items

    # ── 实时查询（2026-09-19 老板令「加入管家婆接口查询」）───────────────────────
    # 硬约束（实测，见 READONLY_ENDPOINTS 注释与 STOCK_API_VERDICT.md）：
    #   ① GetByProductIds：ProductIds 必须 PascalCase（小写静默忽略）；whsId 与
    #      shopId 都必须给真值（缺/错 → 200+空数组）；无库存行的商品不返回行（=0）。
    #   ② Selector/Price/GetAll：必须带 billModule=2（缺省/错名 → success=false 500）。
    #   两端点均只读，已在 READONLY_ENDPOINTS 白名单内。

    def fetch_live_prices(self, product_ids: List[str]) -> Any:
        """实时价格（进价/零售价/最近成交价/会员价等 5 档），单次 ≤500 个 id。

        :return: 上游 result 原始结构（含 standardPriceItems[].priceRule）。
        """
        ids = [str(i) for i in product_ids]
        if not ids:
            raise XzgError("product_ids 不能为空")
        if len(ids) > 500:
            raise XzgError("product_ids 一次最多 500 个（收到 %d 个）" % len(ids))
        payload = self._call_with_reauth(
            "POST",
            "/api/services/app/V1/Erp/Selector/Price/GetAll",
            {"ProductIds": ids, "billModule": 2},
        )
        return payload.get("result")

    def fetch_live_stock(
        self,
        product_ids: List[str],
        shop_id: str = DEFAULT_SHOP_ID,
        whs_id: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        """实时库存（按商品 id 批量，本店单仓单店）。单次 ≤1000 个 id。

        :return: 行列表（productId/qty/subUnitQty/costPrice/...）。
            **上游不返回无库存记录的商品行 —— 调用方须按「无行 = 库存 0」处理**。
        """
        ids = [str(i) for i in product_ids]
        if not ids:
            raise XzgError("product_ids 不能为空")
        if len(ids) > 1000:
            raise XzgError("product_ids 一次最多 1000 个（收到 %d 个）" % len(ids))
        payload = self._call_with_reauth(
            "POST",
            "/api/services/app/V1/Erp/Stock/GetByProductIds",
            {"ProductIds": ids, "whsId": whs_id or shop_id, "shopId": shop_id},
        )
        result = payload.get("result")
        return result if isinstance(result, list) else []

    def download_report(self, url: str, timeout: float = 120.0) -> bytes:
        """下载 Export 返回的报表文件。

        安全边界：仅接受 https 且主机在 :data:`DOWNLOAD_ALLOWED_HOSTS` 内的 URL
        （防上游响应被篡改后 SSRF / 凭据外带）；请求**不携带任何鉴权头**。
        中文文件名需百分号编码，否则 http.client 直接报 UnicodeEncodeError。
        """
        parts = urlsplit(url)
        if parts.scheme != "https" or parts.hostname not in DOWNLOAD_ALLOWED_HOSTS:
            raise XzgError("拒绝下载非白名单域链接（host=%s）" % parts.hostname)
        safe_url = urlunsplit((
            parts.scheme,
            parts.netloc,
            quote(parts.path),
            "&".join(quote(q, safe="=&%") for q in parts.query.split("&") if q),
            "",
        ))
        req = urllib.request.Request(safe_url, headers={"User-Agent": "Mozilla/5.0"})
        try:
            with urllib.request.urlopen(req, timeout=timeout,
                                        context=_default_ssl_context()) as resp:
                return resp.read()
        except urllib.error.URLError as exc:
            raise XzgApiError("报表下载失败：%s" % exc) from exc
