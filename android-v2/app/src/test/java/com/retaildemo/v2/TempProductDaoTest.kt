package com.retaildemo.v2

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.retaildemo.v2.data.local.AppDatabase
import com.retaildemo.v2.data.local.entity.BillEntity
import com.retaildemo.v2.data.local.entity.TempProductEntity
import com.retaildemo.v2.data.local.entity.BillItem
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
 * B3 真 Room 内存库验证：temp_products 表结构完整可读写，
 * 队列查询天然按 type 隔离（custom 清单不含串货），
 * 挂临时码的账单落库回读不丢 tempCode。
 */
@RunWith(RobolectricTestRunner::class)
class TempProductDaoTest {

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

    private fun temp(
        code: String,
        type: String,
        name: String = "螺丝",
        reimburse: String = "none",
    ) = TempProductEntity(
        tempCode = code, type = type, name = name, spec = "M8",
        sellPrice = 5.0, costPrice = null, note = null,
        sourceVendor = "甲店".takeIf { type == "strade" },
        stradePrice = if (type == "strade") 3.0 else null,
        voucherPhoto = if (type == "strade") "v_1.jpg" else null,
        reimburseStatus = reimburse,
        createdBy = "小王", createdAt = "2026-09-04 16:00",
    )

    @Test
    fun `队列只含 custom 串货不串台`() = runTest {
        val dao = db.tempProductDao()
        dao.insert(temp("TMP-20260904-c001", "custom"))
        dao.insert(temp("TMP-20260904-s001", "strade", name = "闸阀"))

        val queue = dao.listByType("custom")
        assertEquals(listOf("TMP-20260904-c001"), queue.map { it.tempCode })
        // 队列流同样只吐 custom（页面数据源）
        assertEquals(1, dao.observeByType("custom").first().size)
        // 当日计数跨类型（号段共享一个序列）
        assertEquals(2, dao.countMatching("TMP-20260904-%"))
    }

    @Test
    fun `销账删除后队列清空 账单历史保留临时码`() = runTest {
        val dao = db.tempProductDao()
        dao.insert(temp("TMP-20260904-c002", "custom"))
        assertEquals(1, dao.listByType("custom").size)

        // 销账 = 建档后删除临时记录
        dao.deleteByCode("TMP-20260904-c002")
        assertTrue(dao.listByType("custom").isEmpty())
        assertNull(dao.getByCode("TMP-20260904-c002"))

        // 账单行挂 tempCode 落库回读不丢（历史可追溯）
        val items = listOf(BillItem("", "", "螺丝", "M8", "件", 1, 5.0, 0.0, tempCode = "TMP-20260904-c002"))
        val json = org.json.JSONArray(
            items.joinToString(",", "[", "]") {
                org.json.JSONObject().apply {
                    put("barcode", it.barcode); put("sku", it.sku); put("name", it.name)
                    put("spec", it.spec); put("unit", it.unit); put("qty", it.qty)
                    put("price", it.price); put("costPrice", it.costPrice)
                    put("tempCode", it.tempCode)
                }.toString()
            },
        ).toString()
        db.billDao().upsert(
            BillEntity(
                id = "B-TEST-1", customerName = "散客", totalAmount = 5.0, paidAmount = null,
                status = BillEntity.STATUS_PENDING, items = json, note = "", staff = "小王",
                createdAt = "2026-09-04 16:00", updatedAt = "2026-09-04 16:00",
                paidAt = null, needsSync = false,
            ),
        )
        val back = db.billDao().getById("B-TEST-1")!!
        assertTrue(back.items.contains("TMP-20260904-c002"))
    }
}
