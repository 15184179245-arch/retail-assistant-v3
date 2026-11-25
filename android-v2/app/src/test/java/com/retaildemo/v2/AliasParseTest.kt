package com.retaildemo.v2

import com.google.gson.Gson
import com.retaildemo.v2.data.remote.ProductDto
import com.retaildemo.v2.data.repo.parseSearchJson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S3：search data 兼容解析——常规数组 / 一码多货 {multi,candidates} 对象（parseSearchJson 纯函数）。 */
class AliasParseTest {

    private val gson = Gson()

    @Test
    fun `常规数组解析为结果列表且multi为空`() {
        val arr = gson.toJsonTree(listOf(
            mapOf("id" to 1, "sku" to "192.0.2.10", "name" to "按钮", "barcode" to "6901"),
        ))
        val (list, multi) = parseSearchJson(arr)
        assertEquals(1, list.size)
        assertEquals("按钮", list[0].name)
        assertNull(multi)
    }

    @Test
    fun `multi对象解析出候选列表`() {
        val obj = gson.toJsonTree(mapOf(
            "multi" to true,
            "candidates" to listOf(
                mapOf("id" to 1, "sku" to "192.0.2.10", "name" to "按钮"),
                mapOf("id" to 2, "sku" to "192.0.2.10", "name" to "按钮"),
            ),
        ))
        val (list, multi) = parseSearchJson(obj)
        assertTrue(list.isEmpty())
        assertEquals(2, multi?.size)
        assertEquals("192.0.2.10", multi?.get(1)?.sku)
    }

    @Test
    fun `空与null安全`() {
        val (l1, m1) = parseSearchJson(null)
        assertTrue(l1.isEmpty()); assertNull(m1)
        val (l2, m2) = parseSearchJson(JsonParser.parseString("null"))
        assertTrue(l2.isEmpty()); assertNull(m2)
    }
}
