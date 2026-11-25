package com.retaildemo.v2

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.retaildemo.v2.data.local.entity.BillItem
import com.retaildemo.v2.ui.pages.marginPct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 成本价快照（B1）：老 JSON 兼容、roundtrip、改价不改快照、毛利口径。 */
class BillItemCostSnapTest {

    private val gson = Gson()

    @Test
    fun `老 JSON 无 costPriceSnap 字段反序列化不崩且为 null`() {
        val legacy = """[{"barcode":"1","sku":"S1","name":"灯头","spec":"","unit":"个","qty":2,"price":5.0,"costPrice":3.0}]"""
        val back: List<BillItem> = gson.fromJson(legacy, object : TypeToken<List<BillItem>>() {}.type)
        assertEquals(1, back.size)
        assertNull(back[0].costPriceSnap)
        assertEquals(3.0, back[0].costPrice, 1e-9)
    }

    @Test
    fun `新账单快照 roundtrip 等值`() {
        val item = BillItem("1", "S1", "闸阀", "6分", "个", 1, 20.0, 12.5, costPriceSnap = 12.5)
        val back: List<BillItem> = gson.fromJson(gson.toJson(listOf(item)), object : TypeToken<List<BillItem>>() {}.type)
        assertEquals(12.5, back[0].costPriceSnap!!, 1e-9)
    }

    @Test
    fun `改价后快照不变金额更新`() {
        val old = BillItem("1", "S1", "闸阀", "6分", "个", 1, 20.0, 12.5, costPriceSnap = 12.5)
        val updated = old.copy(qty = 2, price = 9.9)
        assertEquals(old.costPriceSnap, updated.costPriceSnap)
        assertEquals(12.5, updated.costPriceSnap!!, 1e-9)
        assertEquals(9.9, updated.price, 1e-9)
        assertEquals(19.8, updated.subtotal, 1e-9)
    }

    @Test
    fun `毛利口径：正常为正、售价0返null、低于成本为负`() {
        assertEquals(37, marginPct(20.0, 12.5))
        assertNull(marginPct(0.0, 12.5))
        assertEquals(-56, marginPct(8.0, 12.5))
        assertNull(marginPct(20.0, 0.0))
    }
}
