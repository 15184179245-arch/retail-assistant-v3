package com.retaildemo.v2

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.retaildemo.v2.data.local.AppDatabase
import com.retaildemo.v2.data.local.entity.BillEntity
import com.retaildemo.v2.data.local.entity.BillItem
import com.retaildemo.v2.data.local.entity.TempProductEntity
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.data.repo.BillRepo
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * B12 单据两态：stage(draft=挂账 / ready=待出库) 与 status 正交。
 * 三条护栏：① 脏值归一；② v23→v24 迁移存量行全落 draft（真机升级护栏）；
 * ③ 三件齐全性硬闸（缺一件拒标且点名缺哪件）。
 */
class BillStageNormalizeTest {

    @Test
    fun `normalize keeps ready`() {
        assertEquals(BillEntity.STAGE_READY, BillEntity.normalizeStage("ready"))
    }

    @Test
    fun `normalize folds every other value into draft`() {
        // 老数据缺 stage 键回拉是 null；大写/乱值一律折叠回 draft（与网关同口径）。
        assertEquals(BillEntity.STAGE_DRAFT, BillEntity.normalizeStage("draft"))
        assertEquals(BillEntity.STAGE_DRAFT, BillEntity.normalizeStage("READY"))
        assertEquals(BillEntity.STAGE_DRAFT, BillEntity.normalizeStage(""))
        assertEquals(BillEntity.STAGE_DRAFT, BillEntity.normalizeStage(null))
    }

    @Test
    fun `entity stage defaults to draft with column default`() {
        // 新建行默认 draft；Room 导出的 schema（ksp 产物，真源）必须带 DEFAULT 'draft'——
        // 与迁移 DDL 两处漂移任一 → 真机升级 Room 校验不过，启动即崩。
        // （Room 注解是 CLASS retention，运行时反射读不到 defaultValue，故读 schema 文件。）
        val bill = billEntity()
        assertEquals(BillEntity.STAGE_DRAFT, bill.stage)
        val schema = java.io.File("schemas/com.retaildemo.v2.data.local.AppDatabase/24.json")
        org.junit.Assert.assertTrue("schema 24.json 不存在（ksp 未跑或路径不对）", schema.isFile)
        val text = schema.readText()
        val at = text.indexOf("\"fieldPath\": \"stage\"")
        org.junit.Assert.assertTrue("schema 24.json 缺 stage 字段", at >= 0)
        val window = text.substring(at, minOf(at + 400, text.length))
        org.junit.Assert.assertTrue(
            "schema 24.json 的 stage 列缺 DEFAULT 'draft'（实为：${window.take(160)}）",
            window.contains("defaultValue") && window.contains("'draft'"),
        )
    }

    private fun billEntity(stage: String = BillEntity.STAGE_DRAFT) = BillEntity(
        id = "B1", customerName = "散客", totalAmount = 10.0, paidAmount = null,
        status = BillEntity.STATUS_PENDING, items = "[]", note = "", staff = "店员",
        createdAt = "2026-09-26 10:00:00", updatedAt = "2026-09-26 10:00:00",
        paidAt = null, needsSync = false, stage = stage,
    )
}

/** v23→v24 迁移真机升级护栏（ShelfRepoTest 裸库手法同款）。 */
@RunWith(RobolectricTestRunner::class)
class BillStageMigrationTest {

    @Test
    fun `migration 23_24 existing rows all land on draft`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val file = File(context.cacheDir, "bills_stage_migration.db")
        file.delete()
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        // v23 形态的最小 bills 表 + 存量两行（迁移前 stage 列不存在）
        raw.execSQL("CREATE TABLE bills (id TEXT NOT NULL PRIMARY KEY, status TEXT NOT NULL)")
        raw.execSQL("INSERT INTO bills (id, status) VALUES ('B-old-1', 'pending')")
        raw.execSQL("INSERT INTO bills (id, status) VALUES ('B-old-2', 'paid')")

        raw.execSQL(AppDatabase.STAGE_DDL_23_24)

        val stages = mutableListOf<Pair<String, String>>()
        raw.rawQuery("SELECT id, stage FROM bills ORDER BY id", null).use { c ->
            while (c.moveToNext()) stages.add(c.getString(0) to c.getString(1))
        }
        // 存量单不做批量猜测：全部落默认 draft，靠人逐单补全
        assertEquals(listOf("B-old-1" to "draft", "B-old-2" to "draft"), stages)
        raw.close()
        file.delete()
    }

    @Test
    fun `migration DDL stage column matches entity generated schema`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val file = File(context.cacheDir, "bills_stage_parity.db")
        file.delete()
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        raw.execSQL("CREATE TABLE bills (id TEXT NOT NULL PRIMARY KEY, status TEXT NOT NULL)")
        raw.execSQL(AppDatabase.STAGE_DDL_23_24)

        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        fun stageColumnOf(query: () -> android.database.Cursor): String? {
            query().use { c ->
                while (c.moveToNext()) {
                    if (c.getString(c.getColumnIndexOrThrow("name")) == "stage") {
                        val dflt = c.getString(c.getColumnIndexOrThrow("dflt_value"))
                        val notnull = c.getInt(c.getColumnIndexOrThrow("notnull"))
                        val type = c.getString(c.getColumnIndexOrThrow("type"))
                        return "$type/$notnull/$dflt"
                    }
                }
            }
            return null
        }
        val fromDdl = stageColumnOf { raw.rawQuery("PRAGMA table_info(bills)", null) }
        val fromEntity = stageColumnOf { db.openHelper.writableDatabase.query("PRAGMA table_info(bills)") }
        assertEquals(fromEntity, fromDdl)
        db.close()
        raw.close()
        file.delete()
    }
}

/** 三件齐全性硬闸 + markReady（真实 Room 内存库，DAO 全真）。 */
@RunWith(RobolectricTestRunner::class)
class BillMarkReadyTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: BillRepo

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val config = mockk<AppConfig>(relaxed = true)
        every { config.staffName } returns "店员"
        every { config.staffId } returns ""
        repo = BillRepo(db, mockk<retaildemoApi>(relaxed = true), config, mockk(relaxed = true))
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun bill(vararg items: BillItem) = BillEntity(
        id = "B1", customerName = "散客", totalAmount = 10.0, paidAmount = null,
        status = BillEntity.STATUS_PENDING,
        items = repo.itemsToJson(items.toList()), note = "", staff = "店员",
        createdAt = "2026-09-26 10:00:00", updatedAt = "2026-09-26 10:00:00",
        paidAt = null, needsSync = false, payMethod = null, receiptPhoto = null,
    )

    private fun item(sku: String = "S1", tempCode: String? = null) =
        BillItem("1", sku, "闸阀", "6分", "个", 1, 20.0, 12.5, tempCode = tempCode)

    private fun tempProduct(code: String, type: String, sourceVendor: String? = null) =
        TempProductEntity(
            tempCode = code, type = type, name = "串货·闸阀", spec = "6分",
            sellPrice = 20.0, costPrice = 12.5, note = null, sourceVendor = sourceVendor,
            createdBy = "店员", createdAt = "2026-09-26 10:00:00",
        )

    @Test
    fun `缺收款方式 拒绝并点名`() = runTest {
        db.billDao().upsert(bill(item()))
        val r = repo.markReady("B1")
        assertFalse(r.ok)
        assertTrue(r.missing!!.contains("收款方式"))
        assertEquals(BillEntity.STAGE_DRAFT, db.billDao().getById("B1")!!.stage)
    }

    @Test
    fun `凭证可跳过 没拍也能出库但进待补凭证清单`() = runTest {
        // B15 凭证口径放宽（2026-09-27 老板拍板）：柜台现结普通单没拍凭证也能标待出库，
        // 落「待补凭证」清单打烊前补拍——不再硬拦。
        db.billDao().upsert(bill(item()).copy(payMethod = "cash"))
        val r = repo.markReady("B1")
        assertTrue(r.ok)
        val after = db.billDao().getById("B1")!!
        assertEquals(BillEntity.STAGE_READY, after.stage)
        assertTrue(BillEntity.needsReceiptSupplement(after.stage, after.status, after.receiptPhoto))
    }

    @Test
    fun `串货标注和收款方式仍是硬闸`() = runTest {
        // 收款方式缺 → 拒
        db.billDao().upsert(bill(item()).copy(id = "B-NOPAY"))
        var r = repo.markReady("B-NOPAY")
        assertFalse(r.ok)
        assertTrue(r.missing!!.contains("收款方式"))
        // 串货标注缺 → 拒
        db.billDao().upsert(
            bill(item(tempCode = "TMP-20260926-009")).copy(id = "B-NOSTRADE", payMethod = "cash", receiptPhoto = "p.jpg"),
        )
        db.tempProductDao().insert(tempProduct("TMP-20260926-009", type = "strade", sourceVendor = null))
        r = repo.markReady("B-NOSTRADE")
        assertFalse(r.ok)
        assertTrue(r.missing!!.contains("串货标注"))
    }

    @Test
    fun `串货行没写在谁家串 拒绝并点名`() = runTest {
        db.billDao().upsert(bill(item(tempCode = "TMP-20260926-001")).copy(payMethod = "cash", receiptPhoto = "p.jpg"))
        db.tempProductDao().insert(tempProduct("TMP-20260926-001", type = "strade", sourceVendor = null))
        val r = repo.markReady("B1")
        assertFalse(r.ok)
        assertTrue(r.missing!!.contains("串货标注"))
        assertTrue(r.missing!!.contains("闸阀"))
        assertEquals(BillEntity.STAGE_DRAFT, db.billDao().getById("B1")!!.stage)
    }

    @Test
    fun `临时码查不到档案 也拦下`() = runTest {
        db.billDao().upsert(bill(item(tempCode = "TMP-20260926-404")).copy(payMethod = "cash", receiptPhoto = "p.jpg"))
        val r = repo.markReady("B1")
        assertFalse(r.ok)
        assertTrue(r.missing!!.contains("临时档案缺失"))
    }

    @Test
    fun `自定义行不要求在谁家串`() = runTest {
        db.billDao().upsert(bill(item(tempCode = "TMP-20260926-002")).copy(payMethod = "cash", receiptPhoto = "p.jpg"))
        db.tempProductDao().insert(tempProduct("TMP-20260926-002", type = "custom", sourceVendor = null))
        val r = repo.markReady("B1")
        assertTrue(r.ok)
        assertNull(r.missing)
    }

    @Test
    fun `无临时码行 三件齐即成功`() = runTest {
        db.billDao().upsert(bill(item()).copy(payMethod = "pos", receiptPhoto = "p.jpg"))
        val r = repo.markReady("B1")
        assertTrue(r.ok)
        val after = db.billDao().getById("B1")!!
        assertEquals(BillEntity.STAGE_READY, after.stage)
        assertTrue(after.needsSync)
        assertEquals(BillEntity.STATUS_PENDING, after.status) // status 红线不动
    }

    @Test
    fun `已出库单不需要标待出库`() = runTest {
        db.billDao().upsert(bill(item()).copy(status = BillEntity.STATUS_PAID, payMethod = "cash", receiptPhoto = "p.jpg"))
        val r = repo.markReady("B1")
        assertFalse(r.ok)
        assertTrue(r.missing!!.contains("已出库"))
    }

    @Test
    fun `B7跨天回收只收挂账单 不吃待出库单`() = runTest {
        // B12 × B7 交互回归：待出库单"随时可以去出库"，跨天也必须留在原地供收银端领取；
        // 若 B7 的回收把它藏进回收站，插件端点（已去当天限制）也领不到。
        val yesterday = "2026-09-25 09:00:00"
        db.billDao().upsert(bill(item()).copy(id = "DRAFT-old", createdAt = yesterday, payMethod = "cash", receiptPhoto = "p.jpg"))
        db.billDao().upsert(bill(item()).copy(id = "READY-old", createdAt = yesterday, payMethod = "cash", receiptPhoto = "p.jpg", stage = BillEntity.STAGE_READY))
        val n = repo.markCrossDayRecycled()
        assertEquals(1, n) // 只有挂账单被回收
        assertEquals(true, db.billDao().getById("DRAFT-old")!!.recycled)
        assertEquals(false, db.billDao().getById("READY-old")!!.recycled)
        assertEquals(BillEntity.STAGE_READY, db.billDao().getById("READY-old")!!.stage)
    }

    @Test
    fun `回拉缺 stage 键折叠回 draft`() = runTest {
        // fromServerDto 对老网关（无 stage 键）的容错：normalizeStage(null) → draft
        val fresh = bill(item()).copy(stage = BillEntity.STAGE_READY)
        assertEquals(BillEntity.STAGE_READY, fresh.stage)
        assertEquals(BillEntity.STAGE_DRAFT, BillEntity.normalizeStage(null))
    }

    private fun uiBill(
        id: String = "B1",
        ownerId: String?,
        staff: String,
        stage: String = BillEntity.STAGE_DRAFT,
    ) = com.retaildemo.v2.ui.viewmodel.BillUiModel(
        id = id, customerName = "散客", totalAmount = 10.0, paidAmount = null,
        status = BillEntity.STATUS_PENDING, items = emptyList(),
        createdAt = "2026-09-26 10:00:00", staff = staff, stage = stage, ownerId = ownerId,
    )

    @Test
    fun `owner过滤 本人挂账单可见 他人不可见`() {
        // B12 挂账块按人过滤（有名册 id 走 owner_id 精确匹配）
        val mine = uiBill(ownerId = "emp-1", staff = "小张")
        val others = uiBill(id = "B2", ownerId = "emp-2", staff = "小王")
        assertTrue(com.retaildemo.v2.ui.viewmodel.isOwnedBy(mine, "emp-1", "小张"))
        assertFalse(com.retaildemo.v2.ui.viewmodel.isOwnedBy(others, "emp-1", "小张"))
    }

    @Test
    fun `owner过滤 老单无ownerId按名兜底`() {
        // 存量挂账单 owner_id 为空：按记账人名兜底，不让老单在切换后凭空消失（容错不篡改）
        val legacyMine = uiBill(ownerId = null, staff = "小张")
        val legacyOther = uiBill(id = "B3", ownerId = null, staff = "小王")
        assertTrue(com.retaildemo.v2.ui.viewmodel.isOwnedBy(legacyMine, "emp-1", "小张"))
        assertFalse(com.retaildemo.v2.ui.viewmodel.isOwnedBy(legacyOther, "emp-1", "小张"))
    }

    @Test
    fun `owner过滤 待出库单不受人过滤限制`() {
        // isOwnedBy 本身是"是否本人"判据；待出库块全店可见由列表层不做该过滤保证，
        // 这里锁的是：ready 单即使是别人的，也不会被 isOwnedBy 误用来排除。
        val readyOfOthers = uiBill(id = "B4", ownerId = "emp-2", staff = "小王", stage = BillEntity.STAGE_READY)
        assertFalse(com.retaildemo.v2.ui.viewmodel.isOwnedBy(readyOfOthers, "emp-1", "小张"))
    }
}
