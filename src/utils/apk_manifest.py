"""APK 清单版本号解析：直接从 APK 文件本身读 versionCode / versionName（纯标准库）。

背景（2026-09-10 F12）：发版长期依赖两处手工登记——gradle 的 versionCode 与
data/app_version.json——包换了号没换，APP 端 /api/app/version 一比较就报
「已是最新版本」，新功能推不下去。本模块是 `aapt dump badging` 的 stdlib 等价
实现，让网关以「下载正本 APK」为唯一事实源：包里是什么版本就对外说什么版本。

解析范围：APK zip 内的 AndroidManifest.xml（AXML 二进制），只取 manifest 根元素
两个属性——android:versionCode（TypedValue INT_DEC(0x10)/INT_HEX(0x11) 的 data，
少数构建器写成字符串）、android:versionName（rawValue 指向字符串池）。
chunk 布局口径同 aapt2 / androguard：RES_XML_TYPE(0x0003) 文件头 +
ResStringPool_header(0x0001) + ResXMLTree node(0x0102 start-element)。
解析失败一律返回 (None, None)，由调用方走回退链（app_version.json →
build.gradle.kts），绝不抛异常阻断发版查询端点。
"""
import struct
import zipfile

_RES_STRING_POOL_TYPE = 0x0001
_RES_XML_START_ELEMENT_TYPE = 0x0102
_UTF8_FLAG = 0x100

_TYPE_INT_DEC = 0x10
_TYPE_INT_HEX = 0x11
_TYPE_STRING = 0x03

_ANDROID_NS = "http://schemas.android.com/apk/res/android"

_NO_INDEX = 0xFFFFFFFF


def _u16(buf, off):
    return struct.unpack_from("<H", buf, off)[0]


def _u32(buf, off):
    return struct.unpack_from("<I", buf, off)[0]


def _read_u8_varint(buf, off):
    """UTF-8 池长度前缀：最高位为续位时为 2 字节（高 7 位 << 8 | 低位）。"""
    b = buf[off]
    if b & 0x80:
        return ((b & 0x7F) << 8) | buf[off + 1], 2
    return b, 1


def _read_u16_varint(buf, off):
    """UTF-16 池长度前缀：最高位为续位时为 2 个 u16（高 15 位 << 16 | 低位）。"""
    v = _u16(buf, off)
    if v & 0x8000:
        return ((v & 0x7FFF) << 16) | _u16(buf, off + 2), 4
    return v, 2


def _parse_string_pool(buf, off):
    """解析 off 处的字符串池 chunk → (字符串列表, 下一 chunk 偏移)。"""
    chunk_size = _u32(buf, off + 4)
    string_count = _u32(buf, off + 8)
    flags = _u32(buf, off + 16)
    strings_start = _u32(buf, off + 20)
    is_utf8 = bool(flags & _UTF8_FLAG)
    strings = []
    base = off + strings_start
    for i in range(string_count):
        rel = _u32(buf, off + 28 + 4 * i)
        p = base + rel
        if is_utf8:
            _, n1 = _read_u8_varint(buf, p)
            blen, n2 = _read_u8_varint(buf, p + n1)
            raw = bytes(buf[p + n1 + n2: p + n1 + n2 + blen])
            strings.append(raw.decode("utf-8", "replace"))
        else:
            clen, n = _read_u16_varint(buf, p)
            raw = bytes(buf[p + n: p + n + clen * 2])
            strings.append(raw.decode("utf-16-le", "replace"))
    return strings, off + chunk_size


def _safe_str(strings, idx):
    if idx is None or idx == _NO_INDEX or not (0 <= idx < len(strings)):
        return None
    return strings[idx]


def _attr_value(strings, raw, dtype, data):
    """属性取值：rawValue 优先（字符串池直指），否则按 typedValue 类型解。"""
    if raw != _NO_INDEX:
        return _safe_str(strings, raw)
    if dtype in (_TYPE_INT_DEC, _TYPE_INT_HEX):
        return data
    if dtype == _TYPE_STRING:
        return _safe_str(strings, data)
    return None


def _parse_manifest_axml(xml):
    """AXML 字节 → {"versionCode": int|str|None, "versionName": str|None}。结构非法抛 ValueError。"""
    if len(xml) < 8 or _u16(xml, 0) != 0x0003:
        raise ValueError("not an AXML document")
    total = _u32(xml, 4)
    strings = None
    result = {"versionCode": None, "versionName": None}
    off = _u16(xml, 2)  # 文件头 headerSize，通常 8
    while off + 8 <= min(total, len(xml)):
        ctype = _u16(xml, off)
        if ctype == _RES_STRING_POOL_TYPE and strings is None:
            strings, off = _parse_string_pool(xml, off)
            continue
        if ctype == _RES_XML_START_ELEMENT_TYPE:
            # node 头 16B（type/headerSize/size/lineNumber/comment）+ attrExt：
            # +16 ns、+20 name、+24 attributeStart、+26 attributeSize、+28 attributeCount
            if _safe_str(strings, _u32(xml, off + 20)) == "manifest":
                attr_start = _u16(xml, off + 24)
                attr_size = _u16(xml, off + 26)
                attr_count = _u16(xml, off + 28)
                first = off + 16 + attr_start
                for i in range(attr_count):
                    base = first + i * attr_size
                    if base + 20 > len(xml):
                        break
                    ns = _u32(xml, base)
                    if ns != _NO_INDEX and _safe_str(strings, ns) != _ANDROID_NS:
                        continue
                    name = _safe_str(strings, _u32(xml, base + 4))
                    if name in result:
                        result[name] = _attr_value(
                            strings,
                            _u32(xml, base + 8),
                            xml[base + 15],
                            _u32(xml, base + 16),
                        )
                break
        off += _u32(xml, off + 4)
    return result


def read_apk_version(apk_path):
    """APK 路径 → (versionCode: int|None, versionName: str|None)。

    任何失败（文件缺失/坏 zip/结构不识别）返回 (None, None)，不抛异常。
    """
    try:
        with zipfile.ZipFile(apk_path) as z:
            xml = z.read("AndroidManifest.xml")
        m = _parse_manifest_axml(xml)
        code = m.get("versionCode")
        if isinstance(code, str):
            try:
                code = int(code.strip())
            except ValueError:
                code = None
        elif isinstance(code, int):
            if code < 0:
                code = None
        else:
            code = None
        name = m.get("versionName")
        name = str(name) if name else None
        return code, name
    except Exception:
        return None, None
