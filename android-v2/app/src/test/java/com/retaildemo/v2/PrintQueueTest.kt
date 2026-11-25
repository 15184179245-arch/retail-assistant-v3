package com.retaildemo.v2

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.retaildemo.v2.data.local.AppDatabase
import com.retaildemo.v2.data.local.entity.PrintJobEntity
import com.retaildemo.v2.data.remote.ApiEnvelope
import com.retaildemo.v2.data.remote.PrintResult
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.data.repo.PrinterConfig
import com.retaildemo.v2.data.repo.PrintRepo
import com.retaildemo.v2.print.LabelTemplates
import io.mockk.coEvery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import io.mockk.mockk
import org.robolectric.RobolectricTestRunner
import retrofit2.Response
import java.net.ConnectException

/**
 * S5 打印任务队列测试：
 * 1. Room 持久化与状态机（pending -> done / failed -> retry）
 * 2. 队列入队去重合并（同 pending SKU 累加数量，已完成不合并）
 * 3. 失败达 3 次标红并流转至 failed 状态
 * 4. 便携打印机参数化渲染（纸宽与浓度参数落盘进入 TSPL）
 */
@RunWith(RobolectricTestRunner::class)
class PrintQueueTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: PrintRepo
    private val api: retaildemoApi = mockk(relaxed = true)

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        repo = PrintRepo(api, db.printJobDao(), context)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `入队去重：同pending SKU合并累加qty，不同SKU新增条目`() = runTest {
        val job1 = PrintJobEntity(
            sku = "SKU-001",
            name = "热镀锌膨胀螺栓 M8x80",
            spec = "M8x80",
            barcode = "690111111111",
            sellPrice = 1.50,
            qty = 2,
            sourceScene = PrintJobEntity.SCENE_DETAIL,
        )
        repo.enqueue(job1)

        val list1 = repo.getPendingJobs()
        assertEquals(1, list1.size)
        assertEquals(2, list1.first().qty)

        // 再次入队相同 SKU，自动合并累加
        val job1Again = PrintJobEntity(
            sku = "SKU-001",
            name = "热镀锌膨胀螺栓 M8x80",
            spec = "M8x80",
            barcode = "690111111111",
            sellPrice = 1.50,
            qty = 3,
            sourceScene = PrintJobEntity.SCENE_MULTI,
        )
        repo.enqueue(job1Again)

        val list2 = repo.getPendingJobs()
        assertEquals(1, list2.size)
        assertEquals(5, list2.first().qty)

        // 插入不同 SKU，创建独立任务
        val job2 = PrintJobEntity(
            sku = "SKU-002",
            name = "生料带 15mm",
            spec = "15mm*15m",
            barcode = "690222222222",
            sellPrice = 2.00,
            qty = 1,
            sourceScene = PrintJobEntity.SCENE_INBOUND,
        )
        repo.enqueue(job2)

        val list3 = repo.getPendingJobs()
        assertEquals(2, list3.size)
    }

    @Test
    fun `已出签任务不参与新入队合并，重新建单`() = runTest {
        val dao = db.printJobDao()
        val job = PrintJobEntity(
            sku = "SKU-003",
            name = "PPR直接 20mm",
            sellPrice = 1.2,
            qty = 2,
        )
        val id = dao.insert(job)
        dao.updateStatus(id, PrintJobEntity.STATUS_DONE)

        // 再次入队相同 SKU，由于已有单处于 done 状态，必须新增独立 pending 任务
        val newId = repo.enqueue(job.copy(qty = 1))
        assertTrue(newId != id)

        val pending = dao.getPendingJobs()
        assertEquals(1, pending.size)
        assertEquals(1, pending.first().qty)
    }

    @Test
    fun `打印失败重试3次转为failed状态，单项重试重置为pending`() = runTest {
        val dao = db.printJobDao()
        val job = PrintJobEntity(
            sku = "SKU-004",
            name = "绝缘胶布 黑",
            sellPrice = 3.5,
            qty = 1,
        )
        val id = dao.insert(job)

        // 第 1 次失败 -> fail_count=1, 状态仍为 pending
        dao.recordFailure(id, PrintJobEntity.STATUS_PENDING)
        var current = dao.getPendingJobs().single()
        assertEquals(1, current.failCount)
        assertEquals(PrintJobEntity.STATUS_PENDING, current.status)

        // 第 2 次失败 -> fail_count=2, 状态仍为 pending
        dao.recordFailure(id, PrintJobEntity.STATUS_PENDING)
        current = dao.getPendingJobs().single()
        assertEquals(2, current.failCount)
        assertEquals(PrintJobEntity.STATUS_PENDING, current.status)

        // 第 3 次失败 -> fail_count=3, 标为 failed
        dao.recordFailure(id, PrintJobEntity.STATUS_FAILED)
        assertTrue(dao.getPendingJobs().isEmpty())
        val failedList = dao.getFailedJobs()
        assertEquals(1, failedList.size)
        assertEquals(3, failedList.first().failCount)
        assertEquals(PrintJobEntity.STATUS_FAILED, failedList.first().status)

        // 用户在界面点击「重试」-> 状态恢复 pending, fail_count 重置为 0
        repo.retryJob(id)
        val retried = dao.getPendingJobs().single()
        assertEquals(0, retried.failCount)
        assertEquals(PrintJobEntity.STATUS_PENDING, retried.status)
    }

    @Test
    fun `清空已完成记录：只清除done，保留pending与failed`() = runTest {
        val dao = db.printJobDao()
        val idPending = dao.insert(PrintJobEntity(sku = "P1", name = "待打", status = PrintJobEntity.STATUS_PENDING))
        val idDone = dao.insert(PrintJobEntity(sku = "D1", name = "已打", status = PrintJobEntity.STATUS_DONE))
        val idFailed = dao.insert(PrintJobEntity(sku = "F1", name = "失败", status = PrintJobEntity.STATUS_FAILED))

        repo.clearDone()

        val all = dao.observeAll().first()
        assertEquals(2, all.size)
        assertTrue(all.any { it.id == idPending && it.status == PrintJobEntity.STATUS_PENDING })
        assertTrue(all.any { it.id == idFailed && it.status == PrintJobEntity.STATUS_FAILED })
        assertTrue(all.none { it.id == idDone })
    }

    @Test
    fun `便携机参数化模板：纸宽与浓度参数正确注入TSPL`() {
        // 测试 80mm 宽幅便携标签机，浓度 12
        val items = listOf(
            LabelTemplates.PriceTagItem(name = "角阀", price = 15.0, sku = "JF-01", barcode = "690999"),
        )
        val tsplPriceTag = LabelTemplates.priceTag(items, copies = 2, paperWidth = 80, density = 12)
        assertTrue(tsplPriceTag.contains("SIZE 80 mm,50 mm")) // F11：80mm 宽纸默认高 50mm
        assertTrue(tsplPriceTag.contains("DENSITY 12"))
        assertTrue(tsplPriceTag.contains("PRINT 2"))

        // 测试货位标签 50mm 纸宽，浓度 10
        val tsplCell = LabelTemplates.cellLabel(cellCode = "A-01", copies = 1, paperWidth = 50, density = 10)
        assertTrue(tsplCell.contains("SIZE 50 mm,20 mm"))
        assertTrue(tsplCell.contains("DENSITY 10"))
        assertTrue(tsplCell.contains("A-01"))

        // 测试货架标签 70mm 纸宽，浓度 6
        val tsplShelf = LabelTemplates.shelfLabel(
            zoneName = "水暖区",
            rackName = "A架",
            floorTag = "第1层",
            copies = 1,
            paperWidth = 70,
            density = 6,
        )
        assertTrue(tsplShelf.contains("SIZE 70 mm,50 mm")) // F11：70mm 宽纸默认高 50mm
        assertTrue(tsplShelf.contains("DENSITY 6"))
        assertTrue(tsplShelf.contains("水暖区"))
    }

    @Test
    fun `第二备用打印机持久化配置存取验证`() {
        assertNull(repo.secondPrinter())

        val backupConfig = PrinterConfig(
            kind = "bluetooth",
            name = "汉印 HM-A300",
            address = "02:00:00:00:00:01",
            paperWidth = 80,
            paperHeight = 50, // F11：纸高随配置持久化
            density = 10,
        )
        repo.setSecondPrinter(backupConfig)

        val retrieved = repo.secondPrinter()
        assertNotNull(retrieved)
        assertEquals("汉印 HM-A300", retrieved!!.name)
        assertEquals("02:00:00:00:00:01", retrieved.address)
        assertEquals(80, retrieved.paperWidth)
        assertEquals(50, retrieved.paperHeight)
        assertEquals(10, retrieved.density)

        // 清除
        repo.setSecondPrinter(null)
        assertNull(repo.secondPrinter())
    }

    @Test
    fun `随时更改数量与内容_落库生效`() = runTest {
        val id = repo.enqueue(
            PrintJobEntity(
                sku = "SKU-EDIT-01",
                name = "原品名",
                spec = "原规格",
                sellPrice = 10.0,
                qty = 1,
            ),
        )

        // 更改数量
        repo.updateJobQty(id, 5)
        var job = repo.getPendingJobs().first { it.id == id }
        assertEquals(5, job.qty)

        // 数量不得低于 1
        repo.updateJobQty(id, -2)
        job = repo.getPendingJobs().first { it.id == id }
        assertEquals(1, job.qty)

        // 更改打印内容（品名/规格/价格/条码/份数全字段随时可改，老板核心诉求）
        repo.updateJobContent(
            id = id,
            name = "改后品名",
            spec = "改后规格",
            sellPrice = 15.5,
            barcode = "69999999",
            qty = 3,
        )
        job = repo.getPendingJobs().first { it.id == id }
        assertEquals("改后品名", job.name)
        assertEquals("改后规格", job.spec)
        assertEquals(15.5, job.sellPrice, 0.001)
        assertEquals("69999999", job.barcode)
        assertEquals(3, job.qty)
    }

    @Test
    fun `observePendingJobs流仅发射待出签任务`() = runTest {
        val id1 = repo.enqueue(PrintJobEntity(sku = "P1", name = "待出1", qty = 1))
        val id2 = repo.enqueue(PrintJobEntity(sku = "P2", name = "待出2", qty = 2))

        var pending = repo.observePendingJobs().first()
        assertEquals(2, pending.size)

        // 单条标记完成或删除后流自动响应
        db.printJobDao().updateStatus(id1, PrintJobEntity.STATUS_DONE)
        pending = repo.observePendingJobs().first()
        assertEquals(1, pending.size)
        assertEquals(id2, pending.first().id)
    }

    // ==================== 2026-09-11 排障：货架码打印失败只见「打印失败」不见原因 ====================

    private fun shelfJob(cell: String) = PrintJobEntity(
        sku = "CELL:$cell",
        name = "货位标签 $cell",
        barcode = cell,
        sellPrice = 0.0,
        qty = 1,
        sourceScene = PrintJobEntity.SCENE_SHELF,
    )

    @Test
    fun `打印失败必带原因_未设默认打印机时透出具体错误`() = runTest {
        // 旧队列实现把 IllegalStateException 吞成裸 false，界面只见「打印失败，已计入重试」
        repo.enqueue(shelfJob("A-01"))
        val job = repo.getPendingJobs().single()

        val outcome = repo.printSingleJob(job)
        assertFalse(outcome.success)
        assertTrue(outcome.error!!.contains("请先设置默认打印机"))

        // 失败照旧进重试状态机：fail_count=1 且仍 pending（3 次转 failed 逻辑不变）
        val after = repo.getPendingJobs().single()
        assertEquals(1, after.failCount)
        assertEquals(PrintJobEntity.STATUS_PENDING, after.status)
    }

    @Test
    fun `批量出签失败汇总带首个失败原因`() = runTest {
        repo.enqueue(shelfJob("B-02"))

        val outcome = repo.printAllPending()
        assertEquals(0, outcome.successCount)
        assertEquals(1, outcome.failCount)
        assertTrue(outcome.firstError!!.contains("请先设置默认打印机"))
        assertTrue(outcome.firstError!!.contains("货位标签 B-02"))
    }

    @Test
    fun `网络路径打印服务不可达_报出可达性原因`() = runTest {
        repo.setDefaultPrinter(
            PrinterConfig(kind = "network", name = "网络标签机", address = "192.0.2.99", port = 9100),
        )
        coEvery { api.sendPrintJob(any()) } throws ConnectException("Connection refused")
        repo.enqueue(shelfJob("A-01"))

        val outcome = repo.printSingleJob(repo.getPendingJobs().single())
        assertFalse(outcome.success)
        assertTrue(outcome.error!!.contains("打印服务不可达"))
        assertTrue(outcome.error!!.contains("Connection refused"))
    }

    @Test
    fun `网络路径服务端返回失败_透出服务端原因`() = runTest {
        repo.setDefaultPrinter(
            PrinterConfig(kind = "network", name = "网络标签机", address = "192.0.2.99", port = 9100),
        )
        coEvery { api.sendPrintJob(any()) } returns Response.success(
            ApiEnvelope(success = false, error = "打印机连接超时", user_message = "标签机不在线"),
        )
        repo.enqueue(shelfJob("C-03"))

        val outcome = repo.printSingleJob(repo.getPendingJobs().single())
        assertFalse(outcome.success)
        assertTrue(outcome.error!!.contains("打印服务返回失败"))
        assertTrue(outcome.error!!.contains("标签机不在线"))

        // 成功路径回归：服务端 success=true 时任务标 done（首次失败 1 次仍 pending，重试语义不变）
        coEvery { api.sendPrintJob(any()) } returns Response.success(
            ApiEnvelope(success = true, data = PrintResult(success = true, printed = 1)),
        )
        val okOutcome = repo.printSingleJob(repo.getPendingJobs().single())
        assertTrue(okOutcome.success)
        assertTrue(repo.getPendingJobs().isEmpty())
    }
}
