package com.retailportfolio.v2.util

/**
 * S17：库存数量显示格式化（纯函数，单测锁定）。
 *
 * 上游业务系统上游散装商品库存是小数（0.4 捆 / 12.5 米），但绝大多数是整数——
 * 整数不带 ".0" 尾巴，小数原样保留（准确性：绝不四舍五入成整数）。
 */
fun formatQty(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
