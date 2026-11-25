package com.retaildemo.v2

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.retaildemo.v2.data.local.AppDatabase
import com.retaildemo.v2.data.local.entity.BillEntity
import com.retaildemo.v2.data.local.entity.BillItem
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.data.repo.BillRepo
import com.retaildemo.v2.data.repo.BillTime
import com.retaildemo.v2.ui.viewmodel.computeReportUi
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * B7 打烊闭环单测：
 * 1. 跨天判定——昨天 pending 自动进回收站，今天/已出库不动
 * 2. 出库三选一凭据校验（缺编号/缺收款/POS缺小票 皆拦）
 * 3. 报表口径——回收站单与挂单不计入营业额
 */
@RunWith(RobolectricTestRunner::class)
class CloseDayTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: BillRepo

    private val today = BillTime.today()
    private val yesterday = BillTime.today().let {
        java.time.LocalDate.parse(it).minusDays(1).toString()
    }

    private fun bill(id: String, createdAt: String, status: String, recycled: Boolean = false) =
        BillEntity(
            id = id, customerName = "散客", totalAmount = 10.0, paidAmount = 10.0,
            status = status, items = "[]", note = "", staff = "f7tester",
            createdAt = "$createdAt 12:00:00", updatedAt = "$createdAt 12:00:00",
            paidAt = null, needsSync = false, recycled = recycled,
        )

    private fun item(sku: String) = BillItem(
        barcode = "", sku = sku, name = "x", spec = "", unit = "个",
        qty = 1, price = 10.0, costPrice = 5.0,
    )

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = BillRepo(db, mockk<retaildemoApi>(relaxed = true), mockk<AppConfig>(relaxed = true), mockk<com.retaildemo.v2.data.repo.AppLogger>(relaxed = true))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `跨天判定_昨天pending进回收站_今天与paid不动`() = runTest {
        db.billDao().upsert(bill("y1", yesterday, BillEntity.STATUS_PENDING))
        db.billDao().upsert(bill("t1", today, BillEntity.STATUS_PENDING))
        db.billDao().upsert(bill("p1", yesterday, BillEntity.STATUS_PAID))

        repo.markCrossDayRecycled()

        val rows = db.billDao().observeAll().first()
        assertEquals(true, rows.first { it.id == "y1" }.recycled)
        assertEquals(false, rows.first { it.id == "t1" }.recycled)
        assertEquals(false, rows.first { it.id == "p1" }.recycled)
    }

    @Test
    fun `恢复_回当天待结算`() = runTest {
        val b = bill("y1", yesterday, BillEntity.STATUS_PENDING).copy(recycled = true)
        db.billDao().upsert(b)

        repo.restore("y1")

        val restored = db.billDao().getById("y1")!!
        assertEquals(BillEntity.STATUS_PENDING, restored.status)
        assertEquals(false, restored.recycled)
        assertTrue(restored.createdAt.startsWith(BillTime.today()))
    }

    @Test
    fun `出库三选一_缺一即拦`() {
        val okItems = listOf(item(sku = "A1"))
        val missingSku = listOf(item(sku = ""))

        // 现金标注 ✓
        assertTrue(repo.canMarkPaid(okItems, "cash", null))
        // POS + 小票 ✓
        assertTrue(repo.canMarkPaid(okItems, "pos", "p.jpg"))
        // POS 无小票 ✗
        assertFalse(repo.canMarkPaid(okItems, "pos", null))
        // 缺收款 ✗
        assertFalse(repo.canMarkPaid(okItems, null, null))
        // 有缺失编号（待补码）✗
        assertFalse(repo.canMarkPaid(missingSku, "cash", null))
    }

    @Test
    fun `报表口径_回收站单与挂单不计入营业额`() {
        val todayStr = BillTime.today()
        val bills = listOf(
            bill("paid", todayStr, BillEntity.STATUS_PAID),
            bill("recycled", todayStr, BillEntity.STATUS_PAID).copy(recycled = true),
            bill("pending1", todayStr, BillEntity.STATUS_PENDING),
        )
        val ui = computeReportUi(
            bills, itemsOf = { emptyList() },
            range = com.retaildemo.v2.ui.viewmodel.ReportsRange.TODAY,
            today = todayStr, monthStart = todayStr.take(8) + "01",
        )
        assertEquals(1, ui.rangeCount)
        assertEquals(10.0, ui.rangeAmount, 1e-9)
        // 欠款合计仍统计挂单（债是真的），但回收站的已出库单不再进报表
        assertEquals(10.0, ui.pendingAmount, 1e-9)
    }
}
