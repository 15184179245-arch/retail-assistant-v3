package com.retaildemo.v2

import com.retaildemo.v2.print.LabelTemplates
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I20 位置标签版式（货架 / 层 / 格）。
 *
 * 最关键的一条断言：**条码里编码的是稳定 tagId（`SX:xxxx`），不是名字**。
 * 若有人把名字写进条码，改名就得全店重贴 —— 这条测试就是防这个回退的护栏。
 */
class ShelfLocationLabelTest {

    private val code = "SX:AB23CD45"

    @Test
    fun `locationLabel puts custom name as big text and stable code in barcode`() {
        val out = LabelTemplates.locationLabel(name = "1层", code = code, sub = "A架")

        assertTrue("应是指令体", out.contains("SIZE"))
        assertTrue("自定义名字要在标签上", out.contains("1层"))
        assertTrue("上级路径小字", out.contains("A架"))
        assertTrue("条码内容必须是稳定码", out.contains(code))
        assertTrue("要有条码指令", out.contains("BARCODE"))
        assertTrue("要有打印指令", out.contains("PRINT 1"))
    }

    @Test
    fun `barcode carries tagId rather than the display name`() {
        val out = LabelTemplates.locationLabel(name = "门口那排", code = code)
        // 名字只在文字指令里出现，绝不出现在 BARCODE 行
        val barcodeLine = out.lines().first { it.startsWith("BARCODE") }
        assertTrue(barcodeLine.contains(code))
        assertFalse("名字不能进条码（改名会全店重贴）", barcodeLine.contains("门口那排"))
    }

    @Test
    fun `locationLabel omits sub line when there is no room`() {
        // 极窄/极矮纸规：条码区优先保底，上级路径行应被让掉
        val out = LabelTemplates.locationLabel(
            name = "底层",
            code = code,
            sub = "这是一个很长的上级路径不该出现",
            paperWidth = 40,
            paperHeight = 12,
        )
        assertTrue(out.contains("BARCODE"))
        assertFalse(out.contains("这是一个很长的上级路径不该出现"))
    }

    @Test
    fun `locationLabelFor dispatches to cpcl when printer uses cpcl`() {
        val cpcl = LabelTemplates.locationLabelFor("CPCL", "左", code, "A架 · 1层")
        assertTrue(cpcl.startsWith("!"))
        assertTrue(cpcl.contains(code))

        val tspl = LabelTemplates.locationLabelFor("TSPL", "左", code, "A架 · 1层")
        assertTrue(tspl.contains("SIZE"))
        assertTrue(tspl.contains(code))
    }

    @Test
    fun `blank name falls back to the code so the label is never empty`() {
        val out = LabelTemplates.locationLabel(name = "", code = code)
        assertTrue(out.contains(code))
    }

    @Test
    fun `locationLabel supports custom free-form names with spaces and punctuation`() {
        // 「完全自定义命名」：名字里带空格、中文标点、括号都不能让指令串崩
        val out = LabelTemplates.locationLabel(name = "东墙那排（水管）", code = code)
        assertTrue(out.contains("东墙那排"))
        assertTrue(out.contains(code))
        // 指令行数不应被换行污染：每行都是一条指令（TSPL 结尾换行天然产生末尾空串，先去掉再判）
        assertTrue(out.trimEnd().lines().none { it.isBlank() })
    }
}
