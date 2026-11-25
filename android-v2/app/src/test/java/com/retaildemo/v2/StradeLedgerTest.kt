package com.retaildemo.v2

import com.google.gson.Gson
import com.retaildemo.v2.data.remote.AliasResolveDto
import com.retaildemo.v2.data.repo.ExportUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B4 串货台账：CSV 导出（BOM+转义）与别名解析包络解析。 */
class StradeLedgerTest {

    private val gson = Gson()

    // ---------- CSV（复用 I4 ExportUtils.toCsv） ----------

    @Test
    fun `csv首字节为UTF8_BOM且表头完整`() {
        val csv = ExportUtils.toCsv(listOf("商家", "品名"), listOf(listOf("五金店", "自攻螺丝")))
        assertTrue("首字符必须是 BOM", csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("商家,品名"))
        assertTrue(csv.contains("五金店,自攻螺丝"))
    }

    @Test
    fun `csv特殊字符正确转义`() {
        val csv = ExportUtils.toCsv(
            listOf("品名", "备注"),
            listOf(listOf("螺丝,5寸", "说\"好\"的\n第二行")),
        )
        assertTrue(csv.contains("\"螺丝,5寸\""))
        assertTrue(csv.contains("\"说\"\"好\"\"的\n第二行\""))
    }

    @Test
    fun `普通字段不加引号`() {
        val csv = ExportUtils.toCsv(listOf("品名"), listOf(listOf("自攻螺丝")))
        assertEquals("\uFEFF品名\n自攻螺丝\n", csv)
    }

    // ---------- 别名解析包络（GET api/alias/resolve） ----------

    @Test
    fun `resolve命中解析出正式码`() {
        val json = """
            {"success":true,"data":{"barcode":"TMP-20260908-s001","sku":"192.0.2.10",
            "source":"strade","created_by":"老王","created_at":"2026-09-08 02:27:00"},"error":null}
        """.trimIndent()
        val env = gson.fromJson(json, com.retaildemo.v2.data.remote.ApiEnvelope::class.java)
        assertTrue(env.success)
        val dto = gson.fromJson(gson.toJson(env.data), AliasResolveDto::class.java)
        assertEquals("192.0.2.10", dto.sku)
        assertEquals("strade", dto.source)
        assertEquals("TMP-20260908-s001", dto.barcode)
    }

    @Test
    fun `resolve未绑定data为null`() {
        val json = """{"success":true,"data":null,"error":null}"""
        val env = gson.fromJson(json, com.retaildemo.v2.data.remote.ApiEnvelope::class.java)
        assertTrue(env.success)
        assertNull(env.data)
    }
}
