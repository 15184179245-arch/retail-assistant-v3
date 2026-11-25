package com.retaildemo.v2.ui.agent.a2ui

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V7 spike 的渲染层单测（离线、无模拟器）：协议解析 + 安全闸门 + 数据绑定。
 * 重点是第 2 条验收——catalog 外组件与越权样式必须被拦，且拦得住要留痕。
 */
class A2uiParserTest {

    /** JSONL 的铁律：一条报文一行。真实报文由 scripts 生成时也严格单行。 */
    private val jsonl = listOf(
        """{"version":"v0.9.1","createSurface":{"surfaceId":"s","catalogId":"retaildemo-catalog-v1"}}""",
        """{"version":"v0.9.1","updateDataModel":{"surfaceId":"s","path":"/title","value":"库存预警"}}""",
        """{"version":"v0.9.1","updateDataModel":{"surfaceId":"s","path":"/stat","value":{"count":8}}}""",
        """{"version":"v0.9.1","updateDataModel":{"surfaceId":"s","path":"/rows","value":[{"name":"单芯铜塑线","barcode":"1005010020","stock_qty":0.01}]}}""",
        """{"version":"v0.9.1","updateComponents":{"surfaceId":"s","components":[""" +
            """{"id":"root","component":"Card","child":"col"},""" +
            """{"id":"col","component":"Column","children":["title","ghost","tbl"]},""" +
            """{"id":"title","component":"Text","variant":"h3","text":{"path":"/title"}},""" +
            """{"id":"tbl","component":"Table","rows":{"path":"/rows"},"columns":[{"key":"name","label":"品名"}]},""" +
            """{"id":"evil","component":"Iframe","url":"https://example.com"},""" +
            """{"id":"styled","component":"Text","text":"自带样式","color":"#FF00FF","fontSize":40,"lineHeight":99}""" +
            "]}}",
    ).joinToString("\n")

    @Test
    fun `catalog 外组件整块拒渲染并留痕`() {
        val s = A2ui.parse(jsonl)
        assertEquals(listOf("Iframe (#evil)"), s.rejected)
        assertNull("Iframe 不许进节点表", s.nodes["evil"])
    }

    @Test
    fun `报文自带的样式数值一律剥离 只留令牌可解释的字段`() {
        val s = A2ui.parse(jsonl)
        assertEquals(3, s.stripped.size)
        val styled = s.nodes.getValue("styled")
        assertTrue(styled.props.entrySet().none { it.key in A2ui.BANNED_STYLE_KEYS })
        assertEquals("自带样式", styled.props.get("text").asString)
    }

    @Test
    fun `邻接表建树 child 与 children 都认 悬空引用剔掉`() {
        val s = A2ui.parse(jsonl)
        val root = s.root
        assertNotNull(root)
        assertEquals("Card", root!!.type)
        assertEquals(listOf("col"), root.childIds) // "child" 单数形式
        val col = s.nodes.getValue("col")
        assertEquals(listOf("title", "tbl"), col.childIds) // "ghost" 不存在 → 被剔
        assertEquals(listOf("Column (#col) → 缺失子节点 #ghost"), s.dangling)
    }

    @Test
    fun `JSON Pointer 绑定 绝对路径走数据模型`() {
        val s = A2ui.parse(jsonl)
        val title = s.nodes.getValue("title")
        assertEquals(
            "库存预警",
            A2ui.resolve(title.props, "text", null, s.dataModel)?.asString,
        )
    }

    @Test
    fun `行上下文绑定 相对路径取本行字段`() {
        val s = A2ui.parse(jsonl)
        val tbl = s.nodes.getValue("tbl")
        val row0 = A2ui.resolve(tbl.props, "rows", null, s.dataModel)!!.asJsonArray.get(0).asJsonObject
        assertEquals("单芯铜塑线", A2ui.pointerData(row0, "name")?.asString)
        assertEquals("1005010020", A2ui.pointerData(row0, "barcode")?.asString)
    }

    @Test
    fun `缺失字段与脏路径返回 null 不抛异常`() {
        val s = A2ui.parse(jsonl)
        val row0 = A2ui.resolve(s.nodes.getValue("tbl").props, "rows", null, s.dataModel)!!
            .asJsonArray.get(0).asJsonObject
        assertNull(A2ui.pointerData(row0, "no_such_column"))
        assertNull(A2ui.pointerData(row0, "/deep/missing/deeper"))
        assertNull(A2ui.resolve(row0, "location", null, s.dataModel))
    }

    /** 模拟器实测抓到的真 bug：StatGrid 把 {"path":…} 当字面量画了出来。 */
    @Test
    fun `胖组件的 value 绑定必须解析成数据模型里的真值`() {
        val withStats = jsonl + "\n" + """
            {"version":"v0.9.1","updateComponents":{"surfaceId":"s","components":[{"id":"stats","component":"StatGrid","items":[{"label":"预警条目","value":{"path":"/stat/count"},"tone":"danger"}]}]}}
        """.trimIndent()
        val s = A2ui.parse(withStats)
        val item = s.nodes.getValue("stats").props.getAsJsonArray("items").get(0).asJsonObject
        val resolved = A2ui.resolve(item, "value", null, s.dataModel)
        assertEquals("绑定未解引用", 8, resolved?.asInt)
        assertNotEquals("""{"path":"/stat/count"}""", resolved.toString())
    }

    @Test
    fun `对象型路径绑定返回空而不抛异常`() {
        val props = JsonParser.parseString("""{"value":{"path":{"path":"/stat/count"}}}""").asJsonObject
        val data = JsonParser.parseString("""{"stat":{"count":8}}""").asJsonObject
        assertNull(A2ui.resolve(props, "value", null, data))
    }

    @Test
    fun `报文换行成多行时必须留痕 不许静默少一块`() {
        val pretty = jsonl.replace(
            """{"version":"v0.9.1","updateComponents""",
            "{\n  \"version\":\"v0.9.1\",\n  \"updateComponents",
        )
        val s = A2ui.parse(pretty)
        assertTrue("畸形行未留痕：${s.malformed}", s.malformed.isNotEmpty())
        assertTrue(s.malformed.first().startsWith("第 "))
    }
}
