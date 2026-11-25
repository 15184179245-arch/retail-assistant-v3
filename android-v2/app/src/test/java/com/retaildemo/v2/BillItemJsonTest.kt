package com.retaildemo.v2

import com.google.gson.Gson
import com.retaildemo.v2.data.local.entity.BillItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** BillItem JSON 序列化 roundtrip（items 存 Room 文本列的契约）。 */
class BillItemJsonTest {

    private val gson = Gson()

    @Test
    fun `serialize and deserialize roundtrip`() {
        val items = listOf(
            BillItem("1073670", "SKU1", "灯带头", "4分", "个", 3, 10.5, 6.0),
            BillItem("custom_1", "", "水龙头配件", "自定义", "件", 1, 15.0, 0.0),
        )
        val json = gson.toJson(items)
        val back: List<BillItem> = gson.fromJson(json, object : TypeToken<List<BillItem>>() {}.type)
        assertEquals(2, back.size)
        assertEquals(items[0], back[0])
        assertEquals(items[1], back[1])
    }

    @Test
    fun `subtotal is qty times price`() {
        val item = BillItem("123", "", "自攻丝", "", "盒", 4, 2.5, 0.0)
        assertEquals(10.0, item.subtotal, 1e-9)
    }

    @Test
    fun `empty list roundtrip`() {
        val json = gson.toJson(emptyList<BillItem>())
        val back: List<BillItem> = gson.fromJson(json, object : TypeToken<List<BillItem>>() {}.type)
        assertTrue(back.isEmpty())
    }
}

private typealias TypeToken<T> = com.google.gson.reflect.TypeToken<T>
