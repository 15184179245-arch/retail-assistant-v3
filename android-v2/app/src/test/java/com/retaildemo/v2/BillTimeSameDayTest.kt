package com.retaildemo.v2

import com.retaildemo.v2.data.repo.BillTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 排雷 M5（2026-09-09）：sameDay 归一化日期比较，异常格式不再误判、解析失败回退前缀不崩。 */
class BillTimeSameDayTest {

    @Test
    fun `同日完整时间戳判同日`() {
        assertTrue(BillTime.sameDay("2026-09-09 08:00:00", "2026-09-09"))
    }

    @Test
    fun `隔夜挂单判跨日`() {
        assertFalse(BillTime.sameDay("2026-09-08 23:59:59", "2026-09-09"))
    }

    @Test
    fun `ISO_T分隔符判同日`() {
        assertTrue(BillTime.sameDay("2026-09-09T08:00:00", "2026-09-09"))
    }

    @Test
    fun `非零填充日期归一化后判同日`() {
        assertTrue(BillTime.sameDay("2026-9-9 8:00:00", "2026-09-09"))
    }

    @Test
    fun `无法解析的格式回退前缀比较不崩`() {
        assertTrue(BillTime.sameDay("2026年09月09日", "2026年09月09日"))
        assertFalse(BillTime.sameDay("2026年09月08日", "2026年09月09日"))
    }
}
