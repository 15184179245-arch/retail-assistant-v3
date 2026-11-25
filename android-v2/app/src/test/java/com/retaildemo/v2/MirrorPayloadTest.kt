package com.retaildemo.v2

import com.retaildemo.v2.data.local.entity.BillDraftEntity
import com.retaildemo.v2.data.local.entity.CustomerEntity
import com.retaildemo.v2.data.local.entity.DataMarkEntity
import com.retaildemo.v2.data.local.entity.ProductSupplementEntity
import com.retaildemo.v2.data.local.entity.ProductViewEntity
import com.retaildemo.v2.data.local.entity.SearchHistoryEntity
import com.retaildemo.v2.data.local.entity.TempProductEntity
import com.retaildemo.v2.sync.MirrorPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S13：镜像载荷组装契约——snake_case 键、白名单表、照片不外发（键集=mirror 服务契约）。 */
class MirrorPayloadTest {

    @Test
    fun `载荷含七表且键为服务端契约名`() {
        val payload = MirrorPayload.build(
            device = "dev1",
            tempProducts = listOf(TempProductEntity(tempCode = "TMP-1", type = "strade", name = "无码货",
                spec = null, sellPrice = 10.0, costPrice = null, note = null,
                createdBy = "s", createdAt = "2026-09-09 10:00:00")),
            dataMarks = listOf(DataMarkEntity(id = 1, sku = "A1", barcode = "b1", name = "x", createdAt = "t")),
            supplements = listOf(ProductSupplementEntity(sku = "A1", updatedAt = "t")),
            productViews = listOf(ProductViewEntity(barcode = "b", name = "生料带", spec = "100m", sellPrice = 4.0, viewedAt = 9)),
            searchHistory = listOf(SearchHistoryEntity(keyword = "fsjb", searchedAt = 8)),
            drafts = listOf(BillDraftEntity(id = 1, customerName = "老李", items = "[]", savedAt = 7)),
            customers = listOf(CustomerEntity(id = "c1", name = "老李", phone = "138", createdAt = "t")),
        )
        val tables = payload.tables
        assertEquals(
            setOf("temp_products", "data_marks", "product_supplements", "product_views",
                "search_history", "bill_draft", "customers"),
            tables.keys,
        )
        assertEquals("dev1", payload.device)
        val tmp = tables["temp_products"]!!.first()
        assertTrue(tmp.containsKey("reimburse_status"))
        assertFalse("照片文件名不外发（契约键集不含）", tmp.containsKey("voucher_photo"))
        val view = tables["product_views"]!!.first()
        assertEquals("生料带", view["name"])
        assertEquals(9L, view["viewed_at"])
    }

    @Test
    fun `空表也保留键_服务端幂等全量覆盖`() {
        val payload = MirrorPayload.build("d", emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList())
        assertTrue(payload.tables.values.all { it.isEmpty() })
        assertEquals(7, payload.tables.size)
    }
}
