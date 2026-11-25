package com.retailportfolio.v2

import com.retailportfolio.v2.print.LabelTemplates
import com.retailportfolio.v2.print.LabelTemplates.Op
import com.retailportfolio.v2.print.LabelTemplates.dispW
import com.retailportfolio.v2.print.LabelTemplates.labelText
import com.retailportfolio.v2.print.LabelTemplates.planPriceTagOps
import com.retailportfolio.v2.print.LabelTemplates.qrModules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 价签版式（v4 白底裸版式定稿）：无外框、无【】括号、不打字段名；名称/价格等比例放大；
 * 价格底对齐、单位紧跟右侧；码图横版右下/竖版居中，边长按真实模块数。
 * 钉死四件事：纸规适配、防溢出（任何元素不打飞）、面积利用（字号尽量大）、双渲染几何一致。
 * 与 `src/print/print_server.py` 的 `_price_tag_ops` 同参数同几何（服务器侧同款断言在 test_price_tag_layout.py）。
 */
class LabelTemplatesSizeTest {

    private val item = LabelTemplates.PriceTagItem(
        name = "304不锈钢外六角螺栓",
        price = 12.5,
        spec = "M8×35 全螺纹",
        sku = "DEMO-A01",
        barcode = "88880001",
        unit = "个",
    )

    private fun txts(ops: List<Op>) = ops.filterIsInstance<Op.Txt>()

    // ── 纸规适配 ──

    @Test
    fun `七种预设纸规_SIZE行按宽x高输出`() {
        for (s in LabelTemplates.PRESET_SIZES) {
            val out = LabelTemplates.priceTag(listOf(item), paperWidth = s.widthMm, paperHeight = s.heightMm)
            assertEquals(
                "纸规 ${s.widthMm}×${s.heightMm}",
                "SIZE ${s.widthMm} mm,${s.heightMm} mm",
                out.lineSequence().first(),
            )
        }
    }

    @Test
    fun `纸高缺省_按纸宽取默认_兼容老配置`() {
        assertEquals("SIZE 40 mm,30 mm", LabelTemplates.priceTag(listOf(item), paperWidth = 40).lineSequence().first())
        assertEquals("SIZE 30 mm,40 mm", LabelTemplates.priceTag(listOf(item), paperWidth = 30).lineSequence().first())
        assertEquals(30, LabelTemplates.heightFor(50))
        assertEquals(50, LabelTemplates.heightFor(70))
        assertEquals(40, LabelTemplates.heightFor(99))
        assertEquals(
            "显式纸高优先",
            "SIZE 50 mm,40 mm",
            LabelTemplates.priceTag(listOf(item), paperWidth = 50, paperHeight = 40).lineSequence().first(),
        )
    }

    // ── 参考版式骨架 ──

    @Test
    fun `参考版式_裸版式_名称与价格行在版_码图靠右`() {
        val ops = planPriceTagOps(item, LabelTemplates.PriceTagTemplate(), 40, 30)
        assertEquals("裸版式无外框", 0, ops.filterIsInstance<Op.Ln>().size)
        val qr = ops.filterIsInstance<Op.Qr>().single()
        assertTrue("码图在右半边（右下角）", qr.x >= 320 / 2)
        val texts = txts(ops).map { it.text }
        assertTrue("名称值在版", texts.any { it.contains("304") })
        assertTrue("价格在版（两位小数）", texts.any { it == "12.50" })
        assertTrue("不打括号/字段名", texts.none { it.contains("【") || it.contains("零售价") || it.contains("商品名称") })
    }

    // ── 防溢出（核心诉求） ──

    @Test
    fun `长名称_按值列宽换行不截断`() {
        val long = item.copy(name = "不锈钢外六角螺栓加长型螺母")
        val lines = txts(planPriceTagOps(long, LabelTemplates.PriceTagTemplate(), 60, 40)).map { it.text }
        assertTrue("名称分段在版", lines.any { it.contains("不锈钢外六角") || it.contains("外六角") })
    }

    @Test
    fun `超长名称_纯函数换行_末行省略号收口`() {
        // 纯函数口径（与 Python _fit_lines 同款断言）：超容量时末行省略号
        val lines = LabelTemplates.fitLines("样例DEMO-1山形铁票夹140mm袋装4只装彩色涂层钢板可订做定制加长", 224, 2)
        assertEquals(2, lines.size)
        assertTrue("超容量时末行省略号", lines.last().endsWith("…"))
    }

    @Test
    fun `超长名称_版式层关键字提取_名称不整块丢弃`() {
        // v4：「2 行预算都装不下」的超长名走 shortName 关键字提取（展示层专用，绝不回写库——铁律 12）
        val veryLong = item.copy(name = "样例DEMO-1山形铁票夹140mm袋装4只装彩色涂层钢板可订做定制加长")
        val lines = txts(planPriceTagOps(veryLong, LabelTemplates.PriceTagTemplate(), 30, 40)).map { it.text }
        assertTrue("名称（或其关键字提取）在版", lines.any { it.contains("样例") || it.contains("铁票夹") })
    }

    @Test
    fun `价格_长价格自动缩号保完整数字`() {
        val big = item.copy(price = 123456.78)
        val price = txts(planPriceTagOps(big, LabelTemplates.PriceTagTemplate(), 40, 30))
            .first { Regex("""^\d+\.\d{2}$""").matches(it.text) }
        assertEquals("价格完整未截断", "123456.78", price.text)
    }

    @Test
    fun `名称与价格_等比例放大_不做横向拉伸`() {
        // v4 定稿（测试反馈否过两轮横向拉伸——只会把字拉变形）：等比例放大，xmul 与 mul 恒一致
        val ops = planPriceTagOps(item, LabelTemplates.PriceTagTemplate(), 70, 50)
        val name = txts(ops).first { it.text.contains("304") }
        assertTrue("名称在版", name.text.contains("304"))
        assertEquals("名称等比（无横向拉伸）", name.xmul, name.mul)
        val price = txts(ops).first { Regex("""^\d+\.\d{2}$""").matches(it.text) }
        assertEquals("价格等比", price.xmul, price.mul)
        assertTrue("价格大字（${price.mul}x）", price.mul >= 2)
    }

    // ── 面积利用 ──

    @Test
    fun `大纸字号放大_小纸保底_面积利用`() {
        val big = txts(planPriceTagOps(item, LabelTemplates.PriceTagTemplate(), 80, 50)).maxOf { it.mul }
        val small = txts(planPriceTagOps(item, LabelTemplates.PriceTagTemplate(), 30, 40)).maxOf { it.mul }
        assertTrue("大纸最大字号($big) > 小纸($small)", big > small)
    }

    @Test
    fun `竖版码图尽量大且不挤掉文字`() {
        val ops = planPriceTagOps(item, LabelTemplates.PriceTagTemplate(), 30, 40)
        val qr = ops.filterIsInstance<Op.Qr>().single()
        assertTrue("码图格宽 ≥2（≈2.5mm 起可扫）", qr.cell >= 2)
        assertTrue("名称值行在版（码图未挤掉文字）", txts(ops).any { it.text.contains("304") })
    }

    // ── 全纸规全风格边界（防溢出总闸） ──

    @Test
    fun `全纸规全风格_所有元素在纸边界内`() {
        val longName = item.copy(
            name = "样例DEMO-1山形铁票夹140mm袋装4只装彩色涂层钢板",
            spec = "合成长规格示例",
            price = 123456.78,
        )
        val noUnit = item.copy(unit = "")
        for (s in LabelTemplates.PRESET_SIZES) {
            val w = s.widthMm * 8
            val h = s.heightMm * 8
            for (style in listOf("qr", "barcode")) {
                val t = LabelTemplates.PriceTagTemplate(
                    storeName = "演示门店",
                    codeStyle = style,
                    priceScale = 4,
                    footer = "正品保障",
                )
                for (it0 in listOf(item, longName, noUnit)) {
                    for (op in planPriceTagOps(it0, t, s.widthMm, s.heightMm)) {
                        when (op) {
                            is Op.Txt -> {
                                // 标签+值并排允许贴到内沿，但绝不越纸
                                assertTrue("Txt 横向越界 ${s} ${op.text}", op.x >= 4 && op.x + dispW(op.text) * op.xmul <= w)
                                assertTrue("Txt 纵向越界 ${s} ${op.text}", op.y >= 0 && op.y + 24 * maxOf(op.mul, op.xmul) <= h)
                            }
                            is Op.Ln -> assertTrue("Ln 越界 ${s}", op.x1 >= 0 && op.x2 <= w && op.y1 in 0 until h)
                            is Op.Qr -> assertTrue(
                                "Qr 越界 ${s}",
                                op.x >= 0 && op.y >= 0 &&
                                    op.x + qrModules(op.data) * op.cell <= w &&
                                    op.y + qrModules(op.data) * op.cell <= h,
                            )
                            is Op.Bar -> assertTrue(
                                "Bar 越界 ${s}",
                                op.x >= 0 && op.y >= 0 && op.y + op.h <= h && op.narrow in 1..3,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `任何商品信息不被整块丢弃_空值行自动让位`() {
        val withSpec = txts(planPriceTagOps(item, LabelTemplates.PriceTagTemplate(), 60, 40)).map { it.text }
        assertTrue("规格或名称在版", withSpec.any { it.contains("M8") || it.contains("格") || it.contains("规格") || it.contains("名") || it.contains("称") })
        val noSpec = txts(planPriceTagOps(item.copy(spec = ""), LabelTemplates.PriceTagTemplate(), 40, 30)).map { it.text }
        assertFalse("空规格不占行", noSpec.any { it.contains("规格") })
    }

    // ── 双渲染一致性 ──

    @Test
    fun `CPCL与TSPL同纸规同几何`() {
        val tspl = LabelTemplates.priceTag(listOf(item), paperWidth = 40, paperHeight = 30)
        val cpcl = LabelTemplates.priceTagCpcl(listOf(item), paperWidth = 40, paperHeight = 30)
        assertEquals("! 0 200 200 240 1", cpcl.lineSequence().first())
        assertEquals("PAGE-WIDTH 320", cpcl.lineSequence().elementAt(1))
        val tsplQrX = Regex("QRCODE (\\d+),").find(tspl)!!.groupValues[1]
        val cpclQrX = Regex("B QR (\\d+) ").find(cpcl)!!.groupValues[1]
        assertEquals("QR x 坐标双渲染一致", tsplQrX, cpclQrX)
        assertTrue("CPCL 不含 TSPL 指令", !cpcl.contains("SIZE ") && !cpcl.contains("TSS24"))
    }

    @Test
    fun `货架货位标签_纸高参数生效`() {
        assertTrue(LabelTemplates.shelfLabel("A区", "3排", "2层", paperWidth = 50, paperHeight = 40).startsWith("SIZE 50 mm,40 mm"))
        assertTrue(LabelTemplates.shelfLabelCpcl("A区", "3排", "2层", paperHeight = 40).startsWith("! 0 200 200 320 1"))
        assertTrue(LabelTemplates.cellLabel("A-01", paperWidth = 60, paperHeight = 40).startsWith("SIZE 60 mm,40 mm"))
        assertTrue("货位默认 40×20", LabelTemplates.cellLabel("A-01").startsWith("SIZE 40 mm,20 mm"))
        assertTrue("大纸宽货位号放大", LabelTemplates.cellLabel("A-01", paperWidth = 60, paperHeight = 40).contains("\"TSS24.BF2\",0,3,3"))
    }

    @Test
    fun `标签自适应_窄纸短标签保值完整`() {
        assertTrue("窄列标签让步", labelText("商品名称", "自攻钉", 150).length < "【商品名称】".length)
        assertEquals("宽列保完整标签", "【商品名称】", labelText("商品名称", "自攻钉", 400))
    }
}
