"""熊掌柜商品 → ``wujin.db.products`` 字段映射（**纯函数**，不碰网络与数据库）。

业务语义（老板 2026-09-03 确认，不可违背）
------------------------------------------------

- ``code``    = **入库系统分配的编号**  → 本地 ``sku``（同步主键）
- ``barCode`` = **商品原包装自带的条码** → 本地 ``barcode``

二者是**两套独立体系，不存在任何推导关系**：

- 严禁使用 ``code`` 推导 / 校验 / 补全 ``barCode``
- 严禁为无条码商品生成兜底条码（早期"code 去掉点当条码"方案已作废）

数据纪律
--------

- 上游字段为空 → 映射为 ``None``，落库即 NULL。**不臆造、不推断、不补默认值**
- 无条码（约 31%）是散装 / 非原包装商品（如按斤卖的螺丝、散装胶布）的
  **正常业务状态**，用 :data:`FLAG_NO_BARCODE` 标记但**不算脏数据、不报警**
- 脏条码（``"0"`` / 科学计数法 / 含非数字 / 长度异常）**原样保留**，只做标记；
  归一化必须显式传 ``normalize_dirty=True``（对应 CLI ``--normalize-dirty``）
- 成本价 / 售价原样浮点，**不四舍五入**（上游 ``13.2133333333`` 就存 ``13.2133333333``）
"""
from __future__ import annotations

import os
import re
import sys
from decimal import Decimal, InvalidOperation
from typing import Any, Dict, List, Optional, Tuple

_SRC_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _SRC_DIR not in sys.path:
    sys.path.insert(0, _SRC_DIR)

from utils.pinyintools import full_pinyin, initials  # noqa: E402

# --------------------------------------------------------------------------
# 列归属
# --------------------------------------------------------------------------

#: 本地自有字段，由 APP 维护（货位绑定 / 预警配置 / search 分词）。
#: GetAll 商品档案同步**一律不许写**这些列。
#: 注：``stock_qty`` 于 2026-09-04 老板拍板改判为**上游权威列**（对账信息一律以管家婆为准），
#: 但改由「库存状况表」通道单独写入（见 :mod:`xzg_stock`）；GetAll 映射仍不产出该列。
LOCAL_OWNED_COLUMNS: Tuple[str, ...] = (
    "id",
    "min_stock",
    "warehouse",
    "location",
    "jieba_tokens",
)

#: 上游权威字段：新增与更新都写。
UPSTREAM_COLUMNS: Tuple[str, ...] = (
    "sku",
    "barcode",
    "name",
    "spec",
    # 2026-09-19 事故：上游「型号」列（雷士等品牌填的是出厂货号 DD.00000001）
    # 从未入映射 → 本地永远搜不到。与 spec（规格）是两个独立字段，都必须收。
    "model",
    "unit",
    "category",
    "brand",
    "cost_price",
    "sell_price",
    "pinyin",
    "initials",
)

#: 仅新增行写入的字段。``description`` 本地现值是「从Excel合并导入」，
#: 属于数据来源标注而非上游业务数据，已有行保持不动。
INSERT_ONLY_COLUMNS: Tuple[str, ...] = ("description",)

#: 上游 remark 为空时，新增行写入的来源标注（本地元数据，非业务数据）。
INSERT_DESCRIPTION_DEFAULT = "熊掌柜同步"

# --------------------------------------------------------------------------
# 条码标记
# --------------------------------------------------------------------------

#: 无条码：散装 / 非原包装商品的正常状态。
FLAG_NO_BARCODE = "NO_BARCODE"

#: 脏数据标记：需要人工核对。
FLAG_DIRTY_ZERO = "DIRTY_ZERO"
FLAG_DIRTY_SCIENTIFIC = "DIRTY_SCIENTIFIC"
FLAG_DIRTY_NON_NUMERIC = "DIRTY_NON_NUMERIC"
FLAG_DIRTY_LEN_SHORT = "DIRTY_LEN_SHORT"
FLAG_DIRTY_LEN_LONG = "DIRTY_LEN_LONG"

#: 无名称：无法落库（products.name NOT NULL）。
FLAG_NO_NAME = "DIRTY_NO_NAME"

#: 多规格商品：实测上游全为单规格，出现则需要人工确认取哪个规格。
FLAG_MULTI_STANDARD = "MULTI_STANDARD"

#: 上游已停用（isActive=false）。
FLAG_INACTIVE = "UPSTREAM_INACTIVE"

DIRTY_FLAGS = frozenset({
    FLAG_DIRTY_ZERO,
    FLAG_DIRTY_SCIENTIFIC,
    FLAG_DIRTY_NON_NUMERIC,
    FLAG_DIRTY_LEN_SHORT,
    FLAG_DIRTY_LEN_LONG,
    FLAG_NO_NAME,
})

#: 需要人工核对的标记（脏数据 + 多规格）。无条码不在此列。
REVIEW_FLAGS = DIRTY_FLAGS | {FLAG_MULTI_STANDARD}

_SCIENTIFIC_RE = re.compile(r"^\d+(?:\.\d+)?[eE][+-]?\d+$")
_MIN_BARCODE_LEN = 6
_MAX_BARCODE_LEN = 18


def as_text(value: Any) -> str:
    """上游值 → 去除首尾空白的字符串；``None`` 返回空串。"""
    if value is None:
        return ""
    return str(value).strip()


def as_float(value: Any) -> Optional[float]:
    """价格字段 → float；无法解析时返回 ``None``（**不做任何推断与补值**）。"""
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def classify_barcode(raw: Any) -> Tuple[str, Tuple[str, ...]]:
    """判断条码形态，返回 ``(原始文本, 标记元组)``。

    - 标记为空元组 = 干净条码
    - 只含 :data:`FLAG_NO_BARCODE` = 无条码（正常业务状态，**不是脏数据**）
    - 其余标记为脏数据，需人工核对（值本身**原样保留**，此处不做任何修改）
    """
    text = as_text(raw)
    if text == "":
        return "", (FLAG_NO_BARCODE,)

    if _SCIENTIFIC_RE.match(text):
        # 上游 .NET 把长数字序列化成了科学计数法，如 1.024372422813E+18
        return text, (FLAG_DIRTY_SCIENTIFIC,)

    if text.lstrip("0") == "":
        return text, (FLAG_DIRTY_ZERO,)

    if not text.isdigit():
        return text, (FLAG_DIRTY_NON_NUMERIC,)

    if len(text) < _MIN_BARCODE_LEN:
        return text, (FLAG_DIRTY_LEN_SHORT,)
    if len(text) > _MAX_BARCODE_LEN:
        return text, (FLAG_DIRTY_LEN_LONG,)
    return text, ()


def normalize_barcode(raw: Any) -> str:
    """把科学计数法形态还原成整数字符串，其余形态仅去空白。

    **只在用户显式开启归一化时调用**（CLI ``--normalize-dirty``）；
    即便执行了归一化，结果仍会写入报告供人工核对。
    """
    text = as_text(raw)
    if _SCIENTIFIC_RE.match(text):
        try:
            return str(int(Decimal(text)))
        except (InvalidOperation, ValueError):
            return text
    return text


def _primary_standard(raw: Dict[str, Any]) -> Tuple[Dict[str, Any], int]:
    """取主规格。实测上游 ``isMultipleStandard`` 全为 false（均为单规格）。"""
    standards: List[Dict[str, Any]] = list(raw.get("baseProductStandardList") or [])
    if not standards:
        return {}, 0
    return standards[0], len(standards)


def map_product(raw: Dict[str, Any], normalize_dirty: bool = False) -> Dict[str, Any]:
    """把一条上游商品映射成本地列值。

    返回 dict 含两部分：

    - 本地列名 → 值（``None`` 表示上游为空，落库即 NULL）
    - ``_meta`` → ``{"barcode_raw", "barcode_flags", "standard_count",
      "is_active", "upstream_id"}``，供差异报告使用，不落库
    """
    std, std_count = _primary_standard(raw)

    name = as_text(raw.get("name"))
    spec = as_text(raw.get("spec"))

    # 条码：优先取主表，为空才回退到规格子表。**不做任何兜底生成**。
    raw_barcode = raw.get("barCode")
    if as_text(raw_barcode) == "":
        raw_barcode = std.get("barCode")
    barcode_text, barcode_flags = classify_barcode(raw_barcode)
    if normalize_dirty and barcode_text:
        barcode_text = normalize_barcode(barcode_text)

    # 拼音沿用 scripts/fix-pinyin.py 的口径：name + ' ' + spec
    pinyin_source = (name + " " + spec).strip()

    flags = list(barcode_flags)
    if name == "":
        flags.append(FLAG_NO_NAME)
    if std_count > 1:
        flags.append(FLAG_MULTI_STANDARD)
    if raw.get("isActive") is False:
        flags.append(FLAG_INACTIVE)

    return {
        "sku": as_text(raw.get("code")) or None,
        "barcode": barcode_text or None,
        "name": name or None,
        "spec": spec or None,
        "model": as_text(raw.get("model")) or None,
        "unit": as_text(std.get("standardName")) or None,
        "category": as_text(raw.get("productCategoryIdName")) or None,
        "brand": as_text(raw.get("brandName")) or None,
        "cost_price": as_float(std.get("buyingPrice")),
        "sell_price": as_float(std.get("retailPrice")),
        "description": as_text(raw.get("remark")) or None,
        "pinyin": full_pinyin(pinyin_source) or None,
        "initials": initials(pinyin_source) or None,
        "_meta": {
            "barcode_raw": as_text(raw_barcode),
            "barcode_flags": tuple(barcode_flags),
            "flags": tuple(flags),
            "standard_count": std_count,
            "is_active": raw.get("isActive") is not False,
            "upstream_id": as_text(raw.get("id")) or None,
        },
    }
