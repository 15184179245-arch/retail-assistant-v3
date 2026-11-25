package com.retaildemo.v2

import com.retaildemo.v2.data.local.entity.CustomerEntity
import com.retaildemo.v2.util.CustomerDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S19 客户目录纯函数：过滤（姓名/电话/拼音缩写）与排序（散客→临时→上游按编号→本地按建档时间）。
 * 选客户弹窗与客户对账页共用同一对函数，这里锁死口径。
 */
class CustomerDirectoryTest {

    private fun cust(
        id: String,
        name: String,
        phone: String = "",
        pinyin: String? = null,
        upstreamId: String? = null,
        upstreamCode: String? = null,
        pendingSync: Boolean = false,
        createdAt: String = "2026-09-01 08:00:00",
        category: String? = null,
    ) = CustomerEntity(
        id = id, name = name, phone = phone, createdAt = createdAt,
        pendingSync = pendingSync, upstreamId = upstreamId, upstreamCode = upstreamCode,
        pinyin = pinyin, category = category,
    )

    // ---------------- filter ----------------

    @Test
    fun `过滤 空关键词原样返回`() {
        val list = listOf(cust("1", "散客"), cust("2", "张三"))
        assertEquals(list, CustomerDirectory.filter(list, ""))
        assertEquals(list, CustomerDirectory.filter(list, "   "))
    }

    @Test
    fun `过滤 姓名忽略大小写`() {
        val list = listOf(cust("1", "辽宁聚顺昌"), cust("2", "张三"))
        assertEquals(listOf("1"), CustomerDirectory.filter(list, "聚顺").map { it.id })
        assertEquals(listOf("2"), CustomerDirectory.filter(list, "张三").map { it.id })
    }

    @Test
    fun `过滤 电话命中`() {
        val list = listOf(cust("1", "张三", phone = "00000000000"), cust("2", "李四", phone = ""))
        assertEquals(listOf("1"), CustomerDirectory.filter(list, "139").map { it.id })
        assertTrue(CustomerDirectory.filter(list, "110").isEmpty())
    }

    @Test
    fun `过滤 拼音缩写忽略大小写且本地 null 拼音安全`() {
        val list = listOf(
            cust("1", "辽宁聚顺昌国际贸易有限公司", pinyin = "LNJSCGJMYYXGS"),
            cust("2", "本地老王"),
        )
        assertEquals(listOf("1"), CustomerDirectory.filter(list, "lnjsc").map { it.id })
        assertEquals(listOf("1"), CustomerDirectory.filter(list, "LNJSC").map { it.id })
        // 本地客户无拼音：按姓名仍可搜
        assertEquals(listOf("2"), CustomerDirectory.filter(list, "老王").map { it.id })
    }

    // ---------------- sort ----------------

    @Test
    fun `排序 散客置顶_临时其次_上游按编号_本地按建档倒序`() {
        val list = listOf(
            cust("u2", "示例零售新帝斯", upstreamId = "3", upstreamCode = "0003"),
            cust("l1", "本地甲", pendingSync = false, upstreamId = null, createdAt = "2026-09-02 08:00:00"),
            cust("u1", "散客", upstreamId = "1", upstreamCode = "0001"),
            cust("l2", "本地乙", pendingSync = false, upstreamId = null, createdAt = "2026-09-05 08:00:00"),
            cust("t1", "临时小刘", pendingSync = true, upstreamId = null, createdAt = "2026-09-03 08:00:00"),
            cust("u3", "辽宁聚顺昌", upstreamId = "2", upstreamCode = "0002"),
        )
        assertEquals(
            listOf("u1", "t1", "u3", "u2", "l2", "l1"),
            CustomerDirectory.sort(list).map { it.id },
        )
    }

    @Test
    fun `排序 无散客无临时时上游编号升序在前`() {
        val list = listOf(
            cust("l1", "本地甲", upstreamId = null, createdAt = "2026-09-09 08:00:00"),
            cust("u2", "客户乙", upstreamId = "22", upstreamCode = "0002"),
            cust("u1", "客户甲", upstreamId = "11", upstreamCode = "0001"),
        )
        assertEquals(listOf("u1", "u2", "l1"), CustomerDirectory.sort(list).map { it.id })
    }
}
