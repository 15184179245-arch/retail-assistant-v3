package com.retaildemo.v2

import com.retaildemo.v2.data.local.entity.BillEntity
import com.retaildemo.v2.data.repo.BillTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 账单业务状态机：status 只允许 pending/paid 两个值（旧工程 pending_sync 混用 bug 的回归测试）。 */
class BillStatusTest {

    @Test
    fun `normalize keeps paid`() {
        assertEquals(BillEntity.STATUS_PAID, BillEntity.normalizeStatus("paid"))
    }

    @Test
    fun `normalize folds every other value into pending`() {
        // 旧工程把 pending_sync/pending 等多种值写进 status 造成列表错乱；
        // 新工程任何脏值都必须折叠到 pending。
        assertEquals(BillEntity.STATUS_PENDING, BillEntity.normalizeStatus("pending"))
        assertEquals(BillEntity.STATUS_PENDING, BillEntity.normalizeStatus("pending_sync"))
        assertEquals(BillEntity.STATUS_PENDING, BillEntity.normalizeStatus(""))
        assertEquals(BillEntity.STATUS_PENDING, BillEntity.normalizeStatus(null))
        assertEquals(BillEntity.STATUS_PENDING, BillEntity.normalizeStatus("PAID"))
    }

    @Test
    fun `status constants are exactly the two business values`() {
        assertEquals("pending", BillEntity.STATUS_PENDING)
        assertEquals("paid", BillEntity.STATUS_PAID)
    }
}

/** 时间契约：服务端 bills_sync 只认 YYYY-MM-DD 开头的 createdAt 才计入当日统计。 */
class BillTimeTest {

    @Test
    fun `now format starts with yyyy-MM-dd`() {
        val now = BillTime.now()
        assertTrue(now.length >= 19)
        assertTrue(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""").matches(now))
    }

    @Test
    fun `sameDay matches by date prefix`() {
        assertTrue(BillTime.sameDay("2026-08-31 10:00:00", "2026-08-31"))
        assertFalse(BillTime.sameDay("2026-08-30 10:00:00", "2026-08-31"))
        assertFalse(BillTime.sameDay("", "2026-08-31"))
    }

    @Test
    fun `getDelayInfo returns null for paid bills`() {
        val info = BillTime.getDelayInfo("2026-08-31 10:00:00", BillEntity.STATUS_PAID)
        org.junit.Assert.assertNull(info)
    }

    @Test
    fun `getDelayInfo marks overnight pending bill as danger`() {
        val info = BillTime.getDelayInfo("2020-01-01 10:00:00", BillEntity.STATUS_PENDING)
        org.junit.Assert.assertNotNull(info)
        assertEquals(com.retaildemo.v2.data.repo.BillDelayLevel.DANGER, info?.level)
        assertEquals("隔夜未录", info?.text)
    }

    @Test
    fun `getDelayInfo returns normal for recent bill`() {
        val recent = BillTime.format(java.util.Date(System.currentTimeMillis() - 5 * 60 * 1000))
        val info = BillTime.getDelayInfo(recent, BillEntity.STATUS_PENDING)
        org.junit.Assert.assertNotNull(info)
        assertEquals(com.retaildemo.v2.data.repo.BillDelayLevel.NORMAL, info?.level)
        assertTrue(info!!.text.startsWith("挂账"))
    }
}
