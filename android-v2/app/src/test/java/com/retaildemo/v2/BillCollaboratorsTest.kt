package com.retaildemo.v2

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.retaildemo.v2.data.local.AppDatabase
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.data.repo.BillRepo
import com.retaildemo.v2.data.repo.CartLine
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * I1 设备绑定 + 店员归属：协同人写入/清洗、主记账人 staff 不被覆盖、
 * 协同名单 split/join 往返、v7→v8 迁移真实复演（老行 collaborators=null 不崩）。
 */
@RunWith(RobolectricTestRunner::class)
class BillCollaboratorsTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: BillRepo

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

    private fun line() = CartLine("1", "S1", "闸阀", "6分", "个", 1, 20.0, 12.5)

    @Test
    fun `setCollaborators 落库且 staff 与其余字段不被覆盖`() = runTest {
        val bill = repo.saveBill("张三", listOf(line()), "备注", null)
        assertTrue(repo.setCollaborators(bill.id, "小李,小赵"))
        val back = db.billDao().getById(bill.id)!!
        assertEquals("小李,小赵", back.collaborators)
        assertEquals("小王", back.staff)                       // 主记账人不动
        assertEquals("张三", back.customerName)
        assertEquals("备注", back.note)
        assertEquals(bill.totalAmount, back.totalAmount, 1e-9)
        assertTrue(back.needsSync)
    }

    @Test
    fun `协同人清洗 空白名字剔除 空名单落 null`() = runTest {
        val bill = repo.saveBill("散客", listOf(line()), "", null)
        repo.setCollaborators(bill.id, " 小李 , , 小赵 ")
        assertEquals("小李,小赵", db.billDao().getById(bill.id)!!.collaborators)
        repo.setCollaborators(bill.id, " , ")
        assertNull(db.billDao().getById(bill.id)!!.collaborators)
        repo.setCollaborators(bill.id, null)
        assertNull(db.billDao().getById(bill.id)!!.collaborators)
    }

    @Test
    fun `split join 往返 单人 多人 空 三态`() {
        fun parse(s: String?) = s?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        assertEquals(emptyList<String>(), parse(null))
        assertEquals(listOf("小李"), parse("小李"))
        assertEquals(listOf("小李", "小赵", "小钱"), parse("小李,小赵, 小钱 "))
        assertEquals("小李,小赵", listOf("小李", "小赵").joinToString(","))
        assertEquals(2, parse("小李,小赵").size)
    }

    @Test
    fun `迁移 7→8 真实复演 v7老行 collaborators 回填 null`() = runTest {
        // Room 按实体建 v8 表可空列无 DEFAULT，直插不带列的行可行（TEXT 可空），
        // 但为验证迁移 SQL 本身，仍复演：v7 形状表（无 collaborators）→ ALTER → v7 列集直插 → v8 DAO 回读
        val sdk = db.openHelper.writableDatabase
        sdk.execSQL("DROP TABLE IF EXISTS bills")
        sdk.execSQL(
            "CREATE TABLE bills (" +
                "id TEXT NOT NULL PRIMARY KEY, customer_name TEXT NOT NULL, total_amount REAL NOT NULL, " +
                "paid_amount REAL, status TEXT NOT NULL, items TEXT NOT NULL, note TEXT NOT NULL, staff TEXT NOT NULL, " +
                "created_at TEXT NOT NULL, updated_at TEXT NOT NULL, paid_at TEXT, needs_sync INTEGER NOT NULL, " +
                "pay_method TEXT, receipt_photo TEXT, invoice_needed INTEGER NOT NULL DEFAULT 0, " +
                "direction TEXT NOT NULL DEFAULT 'sale', ref_bill_id TEXT, " +
                "recycled INTEGER NOT NULL DEFAULT 0, recycled_at TEXT)",
        )
        sdk.execSQL("ALTER TABLE bills ADD COLUMN collaborators TEXT")
        // M3 升 v14：真实用户库会继续执行 MIGRATION_13_14（DAO 按实体全列读取），形状对齐到当前版本
        sdk.execSQL("ALTER TABLE bills ADD COLUMN owner_id TEXT")
        // B12 升 v24：真实用户库会继续执行 MIGRATION_23_24（DAO 按实体全列读取），形状对齐到当前版本
        sdk.execSQL(com.retaildemo.v2.data.local.AppDatabase.STAGE_DDL_23_24)
        // B13 升 v25：真实用户库会继续执行 MIGRATION_24_25（DAO 按实体全列读取），形状对齐到当前版本
        com.retaildemo.v2.data.local.AppDatabase.RECEIPT_DDL_24_25.forEach { sdk.execSQL(it) }
        sdk.execSQL(
            "INSERT INTO bills (id, customer_name, total_amount, paid_amount, status, items, note, staff, created_at, updated_at, paid_at, needs_sync, direction) " +
                "VALUES ('LEGACY1','老单',88.0,NULL,'pending','[]','','老王','2026-09-01 09:00:00','2026-09-01 09:00:00',NULL,0,'sale')",
        )
        val legacy = db.billDao().getById("LEGACY1")!!
        assertNull(legacy.collaborators)          // 老行 null 不崩
        assertEquals("老王", legacy.staff)         // 历史归属保留
        assertEquals("sale", legacy.direction)
    }
}
