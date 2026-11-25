package com.retaildemo.v2.util

/**
 * 二维码内容口径（老板 2026-09-12 拍板：扫码枪必须直接扫出带点 SKU 录入管家婆）：
 * 一律优先管家婆原始 SKU（带点，如 192.0.2.10）；无 SKU 才兜底条码（无码货临时码场景）。
 * 全 APP 出码点共用：账单全屏大码（QrOverlay）、商品详情页码、打印价签二维码样式。
 */
fun qrContentOf(sku: String?, barcode: String?): String =
    sku?.trim()?.takeIf { it.isNotEmpty() } ?: barcode.orEmpty().trim()
