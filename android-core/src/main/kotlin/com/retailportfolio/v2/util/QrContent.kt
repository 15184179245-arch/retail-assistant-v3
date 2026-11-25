package com.retailportfolio.v2.util

/**
 * 二维码内容口径（测试反馈 2026-09-12 拍板：扫码枪必须直接扫出带点 SKU 录入上游业务系统）：
 * 一律优先上游业务系统原始 SKU（带点，如 DEMO-A03）；无 SKU 才兜底条码（无码货临时码场景）。
 * 全 APP 出码点共用：账单全屏大码（QrOverlay）、商品详情页码、打印价签二维码样式。
 */
fun qrContentOf(sku: String?, barcode: String?): String =
    sku?.trim()?.takeIf { it.isNotEmpty() } ?: barcode.orEmpty().trim()
