package com.retaildemo.v2

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.retaildemo.v2.data.local.AppDatabase
import com.retaildemo.v2.data.local.entity.PrintJobEntity
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.data.repo.PrintRepo
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 排雷 M3（2026-09-09）：出队并发互斥。
 * 4 个并发 dequeue 串行推进：前 3 次 fail_count 1→2→3 转 failed，
 * 第 4 次读到空 pending 返回 false。旧实现并发读同一 pending 快照，
 * fail_count 会乱跳（4+）、状态不可预期，同一任务被重复执行。
 */
@RunWith(RobolectricTestRunner::class)
class PrintQueueConcurrencyTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: PrintRepo
    private val api: retaildemoApi = mockk(relaxed = true)

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = PrintRepo(api, db.printJobDao(), context)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `并发出队串行推进——失败恰计3次并转failed`() = runTest {
        repo.enqueue(
            PrintJobEntity(
                sku = "SKU-CONC",
                name = "并发测试价签",
                spec = "",
                barcode = "",
                sellPrice = 1.0,
                qty = 1,
                sourceScene = PrintJobEntity.SCENE_DETAIL,
            ),
        )
        // 打印机未配置 → executeJob 抛 IllegalStateException → 每次尝试按失败计
        val results = (1..4).map { async(Dispatchers.IO) { repo.dequeueAndPrintNext() } }.awaitAll()
        assertEquals(listOf(false, false, false, false), results)
        val failed = repo.getFailedJobs()
        assertEquals(1, failed.size)
        assertEquals(3, failed.first().failCount)
        assertEquals(0, repo.getPendingJobs().size)
    }

    @Test
    fun `串行双出队——成功后队列为空第二次返回false`() = runTest {
        // 网络打印器 + mock api 成功 → 第一次出队成功标 done，第二次返回 false
        repo.setDefaultPrinter(
            com.retaildemo.v2.data.repo.PrinterConfig(
                kind = "network", name = "n", address = "192.0.2.10", port = 9100,
            ),
        )
        coEvery { api.sendPrintJob(any()) } returns retrofit2.Response.success(
            com.retaildemo.v2.data.remote.ApiEnvelope(success = true, data = com.retaildemo.v2.data.remote.PrintResult(success = true)),
        )
        repo.enqueue(
            PrintJobEntity(
                sku = "SKU-OK",
                name = "价签",
                spec = "",
                barcode = "",
                sellPrice = 1.0,
                qty = 1,
                sourceScene = PrintJobEntity.SCENE_DETAIL,
            ),
        )
        val first = repo.dequeueAndPrintNext()
        val second = repo.dequeueAndPrintNext()
        assertEquals(true, first)
        assertEquals(false, second)
        assertEquals(0, repo.getPendingJobs().size)
    }
}
