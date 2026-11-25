package com.retaildemo.v2

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.retaildemo.v2.data.local.AppDatabase
import com.retaildemo.v2.data.local.entity.BillEntity
import com.retaildemo.v2.data.local.entity.BillItem
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.data.repo.BillRepo
import com.retaildemo.v2.data.repo.CartLine
import com.retaildemo.v2.ui.viewmodel.computeReportUi
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * B6 退货 = 负数单：
 * ① 报表冲减口径（纯函数 computeReportUi：paid-only + return 取负 + 挂单不计营业额）；
 * ② createReturnBill 负数单生成（qty/total 负、refBillId、status=paid、越界拒绝）；
 * ③ recentSales 只回已出库正单；
 * ④ v6→v7 迁移后老行 direction 默认 'sale'（SQL 直插不带新列实证 DEFAULT 生效）。
 */
@RunWith(RobolectricTestRunner::class)
class ReturnBillTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: BillRepo
    private val gson = Gson()

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        val config = mockk<AppConfig>()
        every { config.staffName } returns "小王"
        // M3：BillRepo.saveBill 会读 staffId 落 owner_id；空串=未绑名册 → owner_id null
        every { config.staffId } returns ""
        repo = BillRepo(db, mockk<retaildemoApi>(), config, mockk<com.retaildemo.v2.data.repo.AppLogger>(relaxed = true))
    }

    @After
    fun tearDown() = db.close()

    private fun billEntity(
        id: String,
        total: Double,
        status: String,
        direction: String = "sale",
        items: List<BillItem> = listOf(BillItem("bc1", "sku1", "闸阀", "6分", "个", 1, total, 5.0)),
    ) = BillEntity(
        id = id, customerName = "张三", totalAmount = total, paidAmount = total,
        status = status, items = gson.toJson(items), note = "", staff = "小王",
        createdAt = "2026-09-04 10:00:00", updatedAt = "2026-09-04 10:00:00",
        paidAt = "2026-09-04 10:00:00", needsSync = false, direction = direction,
    )

    private val itemsParser: (BillEntity) -> List<BillItem> = { gson.fromJson(it.items, Array<BillItem>::class.java).toList() }

    @Test
    fun `冲减口径 正单100 退货-30 挂单50 → 营业额70 挂账50`() {
        val sale = billEntity("S1", 100.0, BillEntity.STATUS_PAID)
        // 真实退货行 qty 取负（createReturnBill 行为），价格保持正、行小计为负
        val ret = billEntity("R1", -30.0, BillEntity.STATUS_PAID, direction = "return",
            items = listOf(BillItem("bc1", "sku1", "闸阀", "6分", "个", -1, 30.0, 5.0)))
        val pending = billEntity("P1", 50.0, BillEntity.STATUS_PENDING,
            items = listOf(BillItem("bc1", "sku1", "闸阀", "6分", "个", 5, 10.0, 5.0)))
        val r = computeReportUi(
            listOf(sale, ret, pending), itemsParser,
            range = com.retaildemo.v2.ui.viewmodel.ReportsRange.ALL,
            today = "2026-09-04", monthStart = "2026-09-01",
        )
        assertEquals(70.0, r.rangeAmount, 1e-9)
        assertEquals(30.0, r.returnAmount, 1e-9)
        assertEquals(50.0, r.pendingAmount, 1e-9)
        // 热销数量冲减：闸阀 +1 -1 = 0（挂单的 5 不计，挂单未出库）
        val qty = r.topProductsByQty.firstOrNull { it.name == "闸阀" }?.qty ?: 0
        assertEquals(0, qty)
    }

    @Test
    fun `生成退货单 数量取负 关联原单 默认已出库 原单不动`() = runTest {
        val orig = repo.saveBill("张三", listOf(CartLine("bc1", "sku1", "闸阀", "6分", "个", 3, 20.0, 12.0)), "", null, payMethod = "cash")
        repo.toggleStatus(orig.id) // → paid（出库已强制收款方式三选一，测试须先给 payMethod）

        val ret = repo.createReturnBill(orig.id, "sku1", "bc1", 2)
        assertNotNull(ret)
        ret!!.let {
            assertEquals("return", it.direction)
            assertEquals(orig.id, it.refBillId)
            assertEquals(BillEntity.STATUS_PAID, it.status)
            assertEquals(-40.0, it.totalAmount, 1e-9)     // 2 × 20 取负
            assertNotNull(it.paidAt)
            val item = repo.itemsOf(it).single()
            assertEquals(-2, item.qty)
            assertEquals(20.0, item.price, 1e-9)           // 单价保持正，行小计为负
        }
        // 原单数量/金额不动
        val back = db.billDao().getById(orig.id)!!
        assertEquals(60.0, back.totalAmount, 1e-9)
        // 原单被退记录反查命中
        assertEquals(listOf(ret.id), repo.referencingBills(orig.id).map { it.id })
    }

    @Test
    fun `退货数量越界与未命中 sku 被拒`() = runTest {
        val orig = repo.saveBill("张三", listOf(CartLine("bc1", "sku1", "闸阀", "6分", "个", 2, 20.0, 12.0)), "", null, payMethod = "cash")
        repo.toggleStatus(orig.id)
        assertNull(repo.createReturnBill(orig.id, "sku1", "bc1", 3))    // 超销量
        assertNull(repo.createReturnBill(orig.id, "sku1", "bc1", 0))    // 非法数量
        assertNull(repo.createReturnBill(orig.id, "other", "other", 1)) // 未命中行
        assertNull(repo.createReturnBill("no-such", "sku1", "bc1", 1))  // 原单不存在
    }

    @Test
    fun `最近销售只回已出库正单`() = runTest {
        val paid1 = repo.saveBill("张三", listOf(CartLine("bc1", "sku1", "闸阀", "6分", "个", 1, 20.0, 12.0)), "", null, payMethod = "cash")
        repo.toggleStatus(paid1.id)
        val pending = repo.saveBill("李四", listOf(CartLine("bc1", "sku1", "闸阀", "6分", "个", 1, 20.0, 12.0)), "", null)
        // 退货单本身不算销售
        repo.createReturnBill(paid1.id, "sku1", "bc1", 1)

        val sales = repo.recentSales("sku1", "bc1")
        assertEquals(listOf(paid1.id), sales.map { it.id })
        assertTrue(pending.id !in sales.map { it.id })
    }

    @Test
    fun `迁移 6→7 真实复演 v6老行 direction 回填 sale`() = runTest {
        // Room 按实体建的 v7 表不带列默认值（Kotlin 默认不进 schema），直插老行必炸——
        // 真实升级路径是 ALTER 带 DEFAULT，这里复演：手工 v6 形状表 → 原样执行 MIGRATION_6_7 两条 ALTER → v6 列集插入
        val sdk = db.openHelper.writableDatabase
        sdk.execSQL("DROP TABLE IF EXISTS bills")
        sdk.execSQL(
            "CREATE TABLE bills (" +
                "id TEXT NOT NULL PRIMARY KEY, customer_name TEXT NOT NULL, total_amount REAL NOT NULL, " +
                "paid_amount REAL, status TEXT NOT NULL, items TEXT NOT NULL, note TEXT NOT NULL, staff TEXT NOT NULL, " +
                "created_at TEXT NOT NULL, updated_at TEXT NOT NULL, paid_at TEXT, needs_sync INTEGER NOT NULL, " +
                "pay_method TEXT, receipt_photo TEXT, invoice_needed INTEGER NOT NULL DEFAULT 0, " +
                "recycled INTEGER NOT NULL DEFAULT 0, recycled_at TEXT)",
        )
        sdk.execSQL("ALTER TABLE bills ADD COLUMN direction TEXT NOT NULL DEFAULT 'sale'")
        sdk.execSQL("ALTER TABLE bills ADD COLUMN ref_bill_id TEXT")
        // I1 升 v8：真实用户库会继续执行 MIGRATION_7_8（DAO 按实体全列读取），形状对齐到当前版本
        sdk.execSQL("ALTER TABLE bills ADD COLUMN collaborators TEXT")
        // M3 升 v14：真实用户库会继续执行 MIGRATION_13_14（DAO 按实体全列读取），形状对齐到当前版本
        sdk.execSQL("ALTER TABLE bills ADD COLUMN owner_id TEXT")
        // B12 升 v24：真实用户库会继续执行 MIGRATION_23_24（DAO 按实体全列读取），形状对齐到当前版本
        sdk.execSQL(com.retaildemo.v2.data.local.AppDatabase.STAGE_DDL_23_24)
        // B13 升 v25：真实用户库会继续执行 MIGRATION_24_25（DAO 按实体全列读取），形状对齐到当前版本
        com.retaildemo.v2.data.local.AppDatabase.RECEIPT_DDL_24_25.forEach { sdk.execSQL(it) }
        sdk.execSQL(
            "INSERT INTO bills (id, customer_name, total_amount, paid_amount, status, items, note, staff, created_at, updated_at, paid_at, needs_sync) " +
                "VALUES ('LEGACY1','老账单',66.0,NULL,'pending','[]','','小王','2026-09-01 09:00:00','2026-09-01 09:00:00',NULL,0)",
        )
        val legacy = db.billDao().getById("LEGACY1")!!   // DAO 走 v7 全列读取，证明迁移后 schema 兼容
        assertEquals("sale", legacy.direction)
        assertNull(legacy.refBillId)
        assertEquals(66.0, legacy.totalAmount, 1e-9)
    }
}
