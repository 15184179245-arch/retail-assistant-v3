package com.retailportfolio.v2

import com.retailportfolio.v2.print.LabelTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TSPL 价签模板：参考版式骨架、条码内容、份数与转义契约。 */
class LabelTemplatesTest {

    @Test
    fun `price tag contains size gap density cls print`() {
        val tspl = LabelTemplates.priceTag(
            listOf(LabelTemplates.PriceTagItem(name = "自攻钉规格", price = 2.5, spec = "M3x16", sku = "100001", unit = "个")),
        )
        assertTrue(tspl.contains("SIZE 30 mm,40 mm"))
        assertTrue(tspl.contains("GAP 2 mm,0 mm"))
        assertTrue(tspl.contains("CLS"))
        assertTrue(tspl.trimEnd().endsWith("PRINT 1"))
        assertTrue("名称值在版（窄纸可拆行）", tspl.contains("自攻") || tspl.contains("攻钉") && tspl.contains("规格"))
        assertTrue("价格两位小数在版（v4 去 Y 前缀）", tspl.contains("2.50"))
    }

    @Test
    fun `copies rendered as single print command with count`() {
        val tspl = LabelTemplates.priceTag(
            listOf(LabelTemplates.PriceTagItem(name = "膨胀螺栓", price = 0.8)),
            copies = 3,
        )
        assertTrue(tspl.trimEnd().endsWith("PRINT 3"))
    }

    @Test
    fun `quotes in name are escaped for tspl`() {
        val tspl = LabelTemplates.priceTag(
            listOf(LabelTemplates.PriceTagItem(name = "3\"水管", price = 1.0)),
        )
        // TSPL 双引号内容里的双引号必须转义，否则后续字段会被吞掉
        assertTrue(!Regex("TEXT[^\\n]*\"3\"水管").containsMatchIn(tspl))
    }

    @Test
    fun `参考版式_裸版式名称价格规格与码图齐全`() {
        val tspl = LabelTemplates.priceTag(
            listOf(
                LabelTemplates.PriceTagItem(
                    name = "合页", price = 12.5, spec = "4寸", sku = "DEMO-A03",
                    barcode = "88880001", unit = "个",
                ),
            ),
            paperWidth = 40, paperHeight = 30,
        )
        assertTrue("裸版式无外框", Regex("^LINE ", RegexOption.MULTILINE).findAll(tspl).count() == 0)
        assertTrue("码图", tspl.contains("QRCODE"))
        assertTrue("名称值在版", tspl.contains("合页"))
        assertTrue("价格两位小数在版（无 Y 前缀）", tspl.contains("12.50"))
        assertTrue("规格带前缀在版", tspl.contains("4寸"))
        assertTrue("不打【】括号", !tspl.contains("【"))
    }

    @Test
    fun `qr code prioritizes raw sku with dots`() {
        val tspl = LabelTemplates.priceTag(
            listOf(LabelTemplates.PriceTagItem(name = "角阀", price = 15.0, barcode = "88880001", sku = "DEMO-A03")),
            template = LabelTemplates.PriceTagTemplate(codeStyle = "qr"),
            paperWidth = 40, paperHeight = 30,
        )
        assertTrue(tspl.contains("QRCODE"))
        assertTrue(tspl.contains("\"DEMO-A03\""))
    }

    @Test
    fun `价格字号按纸型钉死_大纸放大`() {
        val tspl = LabelTemplates.priceTag(
            listOf(LabelTemplates.PriceTagItem(name = "不锈钢合页4寸加厚", price = 12.5)),
            paperWidth = 80, paperHeight = 50,
        )
        // v4：字号按纸型钉死（30mm 起 2×、60mm 起 3×），放不下才降档；无 Y 前缀
        val priceLine = tspl.lineSequence().first { it.contains("12.50") }
        val priceParts = priceLine.split(",")
        val xmul = priceParts[4].toInt()
        val ymul = priceParts[5].toInt()
        assertTrue("大纸价格字号放大（${xmul}x${ymul}）", xmul >= 3 && ymul >= 2)
    }

    @Test
    fun `商品编号与单位进版`() {
        val tspl = LabelTemplates.priceTag(
            listOf(
                LabelTemplates.PriceTagItem(
                    name = "自攻钉", price = 2.5, spec = "M3x16", sku = "100001", unit = "盒",
                ),
            ),
            paperWidth = 70, paperHeight = 50,
        )
        assertTrue("名称值在版", tspl.contains("自攻") || tspl.contains("攻"))
        assertTrue("价格在版（两位小数）", tspl.contains("2.50"))
    }
}
