package com.retaildemo.v2

import com.retaildemo.v2.data.remote.ProductDto
import com.retaildemo.v2.data.repo.SaleFreq
import com.retaildemo.v2.data.repo.saleFreqLookup
import com.retaildemo.v2.ui.pages.relativeDayLabel
import com.retaildemo.v2.ui.pages.saleFreqText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 05 查价页「昨天出 3 件」文案与双键查找表单测。
 *
 * 重点守两条容易出错的语义：
 * ① 相对天数按**自然日**算，不是「距今小时数 ÷ 24」——否则昨晚 23 点卖的货第二天凌晨会说成「今天」；
 * ② 没卖过 / 件数为 0 一律**不显示**，绝不能渲染「出 0 件」。
 */
class SaleFreqTextTest {

    private val today = LocalDate.of(2026, 9, 17)

    // ---- 日期前缀 ----

    @Test
    fun `当天是今天`() {
        assertEquals("今天", relativeDayLabel("2026-09-17", today))
    }

    @Test
    fun `前一天是昨天`() {
        assertEquals("昨天", relativeDayLabel("2026-09-16", today))
    }

    @Test
    fun `更早按N天前`() {
        assertEquals("3 天前", relativeDayLabel("2026-09-14", today))
        assertEquals("73 天前", relativeDayLabel("2026-07-06", today))
    }

    @Test
    fun `未来日期归今天不显示负数天`() {
        assertEquals("今天", relativeDayLabel("2026-09-20", today))
    }

    @Test
    fun `日期脏或缺失返回null`() {
        assertNull(relativeDayLabel(null, today))
        assertNull(relativeDayLabel("", today))
        assertNull(relativeDayLabel("   ", today))
        assertNull(relativeDayLabel("2026年09月16日", today))
        assertNull(relativeDayLabel("乱码", today))
    }

    @Test
    fun `日期两端空白可容错`() {
        assertEquals("昨天", relativeDayLabel(" 2026-09-16 ", today))
    }

    // ---- 文案 ----

    @Test
    fun `昨天出N件`() {
        assertEquals("昨天出 3 件", saleFreqText("2026-09-16", 3.0, today))
    }

    @Test
    fun `N天前出M件`() {
        assertEquals("3 天前出 6 件", saleFreqText("2026-09-14", 6.0, today))
    }

    @Test
    fun `散装小数件数保留一位`() {
        assertEquals("昨天出 2.5 件", saleFreqText("2026-09-16", 2.5, today))
    }

    @Test
    fun `整数件数不带小数点`() {
        assertEquals("昨天出 12 件", saleFreqText("2026-09-16", 12.0, today))
    }

    @Test
    fun `没卖过或零件数不显示`() {
        assertNull("件数缺失=没卖过", saleFreqText("2026-09-16", null, today))
        assertNull("0 件是脏数据，不渲染「出 0 件」", saleFreqText("2026-09-16", 0.0, today))
        assertNull("负数件数同样不显示", saleFreqText("2026-09-16", -1.0, today))
        assertNull("日期缺失无法组句", saleFreqText(null, 3.0, today))
        assertNull("日期脏无法组句", saleFreqText("乱码", 3.0, today))
    }

    // ---- 双键查找表 ----

    @Test
    fun `sku与条码双键都能命中`() {
        val table = saleFreqLookup(
            listOf(ProductDto(sku = "3.01.00065", barcode = "30100065", last_sale_day = "2026-09-16", last_sale_qty = 3.0))
        )
        assertEquals(3.0, table["3.01.00065"]!!.qty!!, 1e-9)
        assertEquals("账单键是条码时也要命中", 3.0, table["30100065"]!!.qty!!, 1e-9)
    }

    @Test
    fun `没有频次的商品不进表`() {
        assertTrue(saleFreqLookup(listOf(ProductDto(sku = "A", barcode = "B"))).isEmpty())
    }

    @Test
    fun `只有日期没有件数也算有效记录`() {
        val table = saleFreqLookup(listOf(ProductDto(sku = "A", last_sale_day = "2026-09-16")))
        assertEquals("2026-09-16", table["A"]?.day)
        assertNull(table["A"]?.qty)
    }

    @Test
    fun `空串键不进表`() {
        val table = saleFreqLookup(listOf(ProductDto(sku = "  ", barcode = "", last_sale_day = "2026-09-16", last_sale_qty = 1.0)))
        assertTrue(table.isEmpty())
    }

    @Test
    fun `条码不覆盖已被sku占用的键`() {
        // 两个商品共用同一条码时，先来的 sku 键不能被后来的覆盖
        val table = saleFreqLookup(
            listOf(
                ProductDto(sku = "SKU-1", barcode = "SHARED", last_sale_day = "2026-09-16", last_sale_qty = 3.0),
                ProductDto(sku = "SKU-2", barcode = "SHARED", last_sale_day = "2026-09-10", last_sale_qty = 9.0),
            )
        )
        assertEquals("共享条码保留先到的记录", 3.0, table["SHARED"]!!.qty!!, 1e-9)
        assertEquals("各自 sku 键互不影响", 9.0, table["SKU-2"]!!.qty!!, 1e-9)
    }

    @Test
    fun `查找表值与后端语义一致`() {
        val f = SaleFreq("2026-09-16", 3.0)
        assertEquals("昨天出 3 件", saleFreqText(f.day, f.qty, today))
    }
}
