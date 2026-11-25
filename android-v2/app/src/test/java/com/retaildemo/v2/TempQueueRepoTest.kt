package com.retaildemo.v2

import com.retaildemo.v2.data.local.AppDatabase
import com.retaildemo.v2.data.local.dao.TempProductDao
import com.retaildemo.v2.data.local.entity.TempProductEntity
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.repo.TempProductRepo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B3 队列数据层：自定义落 reimburse=none（进队列），串货落 pending（走 B4 台账），
 * 临时码序号来自当日计数+1，销账即按码删除。
 */
class TempQueueRepoTest {

    private fun repo(dao: TempProductDao): TempProductRepo {
        val config = mockk<AppConfig>()
        every { config.staffName } returns "小王"
        return TempProductRepo(dao, config)
    }

    @Test
    fun `自定义通道 落 none 进队列 临时码取当日计数加一`() = runTest {
        val dao = mockk<TempProductDao>(relaxed = true)
        val cap = slot<TempProductEntity>()
        coEvery { dao.countMatching(any()) } returns 3
        coEvery { dao.insert(capture(cap)) } returns Unit
        val tp = repo(dao).add("custom", "螺丝", "M8", 5.0, null, null)
        assertEquals("none", tp.reimburseStatus)
        assertEquals("小王", tp.createdBy)
        assertEquals("M8", tp.spec)
        assertNull(tp.costPrice)
        assertTrue(tp.tempCode.contains("-c004"))
    }

    @Test
    fun `串货通道 落 pending 凭证与进价保留`() = runTest {
        val dao = mockk<TempProductDao>(relaxed = true)
        coEvery { dao.countMatching(any()) } returns 0
        val tp = repo(dao).add(
            "strade", "闸阀", null, 30.0, 22.0, null,
            sourceVendor = "甲店", stradePrice = 22.0, voucherPhoto = "voucher_1.jpg",
        )
        assertEquals("pending", tp.reimburseStatus)
        assertEquals("甲店", tp.sourceVendor)
        assertEquals("voucher_1.jpg", tp.voucherPhoto)
        assertTrue(tp.tempCode.contains("-s001"))
    }

    @Test
    fun `销账按临时码删除`() = runTest {
        val dao = mockk<TempProductDao>(relaxed = true)
        repo(dao).delete("TMP-20260904-c004")
        coVerify(exactly = 1) { dao.deleteByCode("TMP-20260904-c004") }
    }
}
