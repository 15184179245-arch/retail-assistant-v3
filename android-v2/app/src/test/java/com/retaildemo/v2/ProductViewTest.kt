package com.retaildemo.v2

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.retaildemo.v2.data.local.AppDatabase
import com.retaildemo.v2.data.local.entity.BillDraftEntity
import com.retaildemo.v2.data.local.entity.ProductViewEntity
import kotlinx.coroutines.flow.first
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
 * B8 夜间队列移植（T10 最近浏览 + T1 草稿）真 Room 内存库验证。
 * 迁移说明：MIGRATION_9_10 为纯 CREATE TABLE IF NOT EXISTS product_views（老表零触碰），
 * 表结构正确性由本内存库建表+读写覆盖（同 DataMarkTest 对 8→9 的处理）。
 */
@RunWith(RobolectricTestRunner::class)
class ProductViewTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun view(
        barcode: String,
        at: Long,
        name: String = "自攻丝螺丝",
    ) = ProductViewEntity(barcode = barcode, name = name, spec = "M4×20", sellPrice = 0.5, viewedAt = at)

    @Test
    fun `最近浏览按时间倒序且详情重开刷新排序`() = runTest {
        val dao = db.productViewDao()
        dao.upsert(view("A", at = 1_000))
        dao.upsert(view("B", at = 2_000))
        dao.upsert(view("C", at = 3_000))
        assertEquals(listOf("C", "B", "A"), dao.observeRecent().first().map { it.barcode })

        // 重开 A 详情 → upsert REPLACE 刷新 viewed_at，排到最前
        dao.upsert(view("A", at = 4_000))
        assertEquals(listOf("A", "C", "B"), dao.observeRecent().first().map { it.barcode })
        assertEquals(3, dao.observeRecent().first().size)
    }

    @Test
    fun `最近浏览最多保留20条`() = runTest {
        val dao = db.productViewDao()
        repeat(25) { i -> dao.upsert(view("BCD-$i", at = i.toLong())) }
        val recent = dao.observeRecent().first()
        assertEquals(20, recent.size)
        assertEquals("BCD-24", recent.first().barcode) // 最新在前
        assertEquals("BCD-5", recent.last().barcode) // 最旧 0-4 被挤出
        assertTrue(recent.none { it.barcode == "BCD-0" })
    }

    @Test
    fun `草稿单行语义 save覆盖-clear清空（T1 退出暂存恢复放弃）`() = runTest {
        val dao = db.draftDao()
        assertNull(dao.get())

        dao.save(BillDraftEntity(customerName = "老张", items = "[{\"barcode\":\"A\"}]", savedAt = 1_000))
        val first = dao.get()
        assertEquals("老张", first?.customerName)

        // 退出再暂存 → 同 id=1 REPLACE 覆盖为最新一条
        dao.save(BillDraftEntity(customerName = "李姐", items = "[]", savedAt = 2_000))
        assertEquals("李姐", dao.get()?.customerName)
        assertEquals(2_000L, dao.get()?.savedAt)

        // 恢复/放弃都走 clear → 回到无草稿
        dao.clear()
        assertNull(dao.get())
    }
}
