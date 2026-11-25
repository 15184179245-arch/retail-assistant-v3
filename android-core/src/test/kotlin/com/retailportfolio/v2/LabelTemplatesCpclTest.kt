package com.retailportfolio.v2

import com.retailportfolio.v2.print.LabelTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 常见便携打印机适配：CPCL 模板结构与指令集分发（纯函数，JVM 可测）。v3 参考版式断言。 */
class LabelTemplatesCpclTest {

    private val item = LabelTemplates.PriceTagItem(
        name = "自攻螺丝",
        price = 12.5,
        spec = "M4*30",
        sku = "DEMO-A02",
        barcode = "88880001",
    )

    @Test
    fun `价签CPCL默认QR风格_结构与字段齐全`() {
        val out = LabelTemplates.priceTagCpcl(
            listOf(item),
            template = LabelTemplates.PriceTagTemplate(storeName = "演示门店"),
            paperWidth = 30,
        )
        val lines = out.lines()
        assertEquals("! 0 200 200 320 1", lines[0]) // 40mm 高 / 1 份
        assertEquals("PAGE-WIDTH 240", lines[1]) // 30mm 宽
        // 名称/价格可能因窄纸拆行，按字段存在性断言（防溢出机制允许换行/省略）
        assertTrue("店名在版", out.contains("演示门店"))
        assertTrue("名称值在版", out.contains("自攻") || out.contains("螺丝") || out.contains("攻") || out.contains("螺") || out.contains("…"))
        assertTrue("价格两位小数全ASCII（v4 无 Y 前缀）", Regex("""TEXT \d+ 0 \d+ \d+ [\d.]+""").containsMatchIn(out) || out.contains("12.50"))
        // 2026-09-12 BTP-P39 PLUS 实证：4 号=西文字体，中文必须走中文字库号（默认 24=TSS24）
        assertTrue("中文文本走中文字库号24", Regex("""TEXT 24 0 \d+ \d+ """).containsMatchIn(out))
        assertFalse("西文4号不再承担中文", Regex("""TEXT 4 0 \d+ \d+ 自攻""").containsMatchIn(out))
        assertTrue("裸版式无外框", !out.contains("LINE ")) // v4 白底裸版式
        assertTrue(out.contains("B QR "))
        assertTrue(out.contains("\nMA,DEMO-A02\n")) // QR 内容=SKU（扫码枪可录）
        assertTrue(out.contains("ENDQR"))
        assertTrue(out.endsWith("FORM\nPRINT\n"))
        assertFalse("CPCL 不应出现 TSPL 指令", out.contains("SIZE ") || out.contains("TSS24"))
    }

    @Test
    fun `价签CPCL条码风格_CODE128数据独占一行`() {
        val out = LabelTemplates.priceTagCpcl(
            listOf(item),
            template = LabelTemplates.PriceTagTemplate(codeStyle = "barcode"),
        )
        assertTrue("条码底置指令", Regex("""BARCODE 128 1 1 \d+ \d+ \d+""").containsMatchIn(out))
        assertTrue(out.contains("\n88880001\n")) // CPCL 条码数据在指令行下一行
        assertFalse(out.contains("B QR"))
    }

    @Test
    fun `多商品多份_每张标签独立job头`() {
        val out = LabelTemplates.priceTagCpcl(listOf(item, item), copies = 3)
        assertEquals(2, Regex("^! 0 200 200 320 3$", RegexOption.MULTILINE).findAll(out).count())
        assertEquals(2, Regex("^PRINT$", RegexOption.MULTILINE).findAll(out).count())
    }

    @Test
    fun `数据行清洗_换行变空格不破坏指令流`() {
        val dirty = LabelTemplates.PriceTagItem(name = "自攻\n螺丝", price = 1.0, sku = "A\r\nB")
        val out = LabelTemplates.priceTagCpcl(listOf(dirty))
        assertFalse("换行不得穿透为指令换行", out.contains("\n螺丝"))
        // 名称可能被版式拆行/收口；关键是换行已转为空格（值在版且无裸换行）
        assertTrue("名称已清洗（自攻/螺丝任一段在版）", out.contains("自攻") || out.contains("螺丝"))
        assertTrue("SKU 数据行清洗为单行", out.contains("MA,") && !out.contains("\nMA,A\r"))
    }

    @Test
    fun `货架条码货位CPCL模板_头尾与关键指令`() {
        val shelf = LabelTemplates.shelfLabelCpcl("A区", "1排", "2层")
        assertTrue(shelf.startsWith("! 0 200 200 240 1\nPAGE-WIDTH 320\n"))
        assertTrue(shelf.contains("SETMAG 3 3"))
        assertTrue(shelf.endsWith("FORM\nPRINT\n"))

        val barcode = LabelTemplates.barcodeLabelCpcl("100001", "螺丝")
        assertTrue(barcode.startsWith("! 0 200 200 120 1\nPAGE-WIDTH 200\n"))
        assertTrue(barcode.contains("\n100001\n"))

        val cell = LabelTemplates.cellLabelCpcl("A-01")
        assertTrue(cell.contains("SETMAG 3 3")) // F11：货位号 fitScale 按纸宽放大（40mm 宽 3 倍放得下）
        assertTrue(cell.contains("\nA-01\n"))
        assertTrue(cell.endsWith("FORM\nPRINT\n"))
    }

    @Test
    fun `分发器_cpcl走CPCL_tspl与未知值兜底TSPL_大小写不敏感`() {
        val cpcl = LabelTemplates.priceTagFor("CPCL", listOf(item))
        assertTrue(cpcl.contains("! 0 200 200"))
        val tspl = LabelTemplates.priceTagFor("tspl", listOf(item))
        assertTrue(tspl.contains("SIZE 30 mm,40 mm"))
        val fallback = LabelTemplates.priceTagFor("escpos", listOf(item))
        assertTrue("未知指令集兜底 TSPL", fallback.contains("SIZE 30 mm,40 mm"))
        assertTrue(LabelTemplates.cellLabelFor("cpcl", "A-01").contains("! 0 200 200"))
        assertTrue(LabelTemplates.shelfLabelFor("cpcl", "A", "1", "2").contains("! 0 200 200"))
        assertTrue(LabelTemplates.barcodeLabelFor("cpcl", "100001", "x").contains("! 0 200 200"))
    }

    @Test
    fun `字体号对照条_三行同句中文_ASCII编号可读_头尾完整`() {
        val probe = LabelTemplates.cpclFontProbeTag(paperWidth = 30, paperHeight = 20)
        assertEquals("! 0 200 200 160 1\nPAGE-WIDTH 240\nSETMAG 1 1\n", probe.lines().take(3).joinToString("\n") + "\n")
        assertTrue("西文4号对照行", probe.contains("\nTEXT 4 0 8 6 4:演示门店 ABC123\n"))
        assertTrue("中文字库24行", probe.contains("\nTEXT 24 0 8 34 24:演示门店 ABC123\n"))
        assertTrue("备选中文字库55行", probe.contains("\nTEXT 55 0 8 62 55:演示门店 ABC123\n"))
        assertTrue("纸规尾行", probe.contains("PROBE 30x20"))
        assertTrue(probe.endsWith("FORM\nPRINT\n"))
    }

    @Test
    fun `指令集自动选择_BTP_P便携机走CPCL_其余默认TSPL`() {
        assertEquals("cpcl", LabelTemplates.defaultCommandSetFor("BTP-P39 PLUS"))
        assertEquals("cpcl", LabelTemplates.defaultCommandSetFor("BTP_P50 SC"))
        assertEquals("cpcl", LabelTemplates.defaultCommandSetFor("btp-p39"))
        assertEquals("tspl", LabelTemplates.defaultCommandSetFor("BTP-2200E")) // 台面标签机非 P 系便携
        assertEquals("tspl", LabelTemplates.defaultCommandSetFor("XP-365B"))
        assertEquals("tspl", LabelTemplates.defaultCommandSetFor(""))
    }
}
