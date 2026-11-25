package com.retaildemo.v2.data.repo

/** 商品详情「记一笔」跨页传参：NewBillPage 初始化时消费。 */
object PendingProductHolder {
    @Volatile var pendingBarcode: String? = null

    fun consume(): String? {
        val v = pendingBarcode
        pendingBarcode = null
        return v
    }
}
