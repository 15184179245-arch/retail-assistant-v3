package com.retaildemo.v2.data.repo

/** 跨页面一次性关键词（商品详情「相关知识」→ 知识库预填搜索词）。 */
object PendingKeywordHolder {
    @Volatile var pendingKeyword: String? = null

    fun consume(): String? {
        val v = pendingKeyword
        pendingKeyword = null
        return v
    }
}
