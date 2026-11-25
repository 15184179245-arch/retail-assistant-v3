package com.retaildemo.v2.util

import com.retaildemo.v2.data.local.entity.CustomerEntity

/**
 * S19 客户目录纯函数：过滤与排序（选客户弹窗与客户对账页共用）。
 *
 * 背景：S18 同步进来 61 条管家婆档案后，customers 表不再是「本地零星几个」，
 * 按建档时间倒排的旧口径对整批同秒插入的上游行毫无意义——需要真正的搜索与稳定排序。
 * 上游 pinYinCode（如 LNJSCGJMYYXGS）让店里查价页的拼音缩写范式在选客户上直接复用。
 */
object CustomerDirectory {

    /**
     * 关键词过滤：姓名 / 电话 / 拼音缩写（均 contains，姓名与拼音忽略大小写）。
     * 空关键词原样返回。本地客户 pinyin 为 null 安全跳过。
     */
    fun filter(list: List<CustomerEntity>, keyword: String): List<CustomerEntity> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return list
        return list.filter { c ->
            c.name.contains(kw, ignoreCase = true) ||
                c.phone.contains(kw) ||
                (!c.pinyin.isNullOrBlank() && c.pinyin.contains(kw, ignoreCase = true))
        }
    }

    /**
     * 目录排序（稳定可预期，搜索结果也走同一排序）：
     * ① 散客（现结默认）置顶；② 临时待关联（pendingSync=true，需人工处理）；
     * ③ 上游档案按编号（0001, 0002…=店里管家婆的编号习惯）；④ 本地新建按建档时间倒序。
     */
    fun sort(list: List<CustomerEntity>): List<CustomerEntity> {
        val sanke = list.filter { it.name == "散客" }
        val rest = list.filter { it.name != "散客" }
        val pending = rest.filter { it.pendingSync }.sortedByDescending { it.createdAt }
        val upstream = rest.filter { !it.pendingSync && it.upstreamId != null }
            .sortedBy { it.upstreamCode ?: "~~~~" }
        val local = rest.filter { !it.pendingSync && it.upstreamId == null }
            .sortedByDescending { it.createdAt }
        return sanke + pending + upstream + local
    }
}
