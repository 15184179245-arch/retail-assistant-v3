package com.retaildemo.v2

import com.retaildemo.v2.data.local.entity.BillEntity
import com.retaildemo.v2.data.local.entity.BillItem
import com.retaildemo.v2.ui.viewmodel.ReportsRange
import com.retaildemo.v2.ui.viewmodel.computeReportUi
import com.retaildemo.v2.ui.viewmodel.rangeStartDayOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B14 多维度报表聚合回归（纯函数，口径纪律）：
 * - 营业额只计已出库(paid)；挂账单不计营收（另列挂账合计）；
 * - 退货单 direction=return 金额取负冲减，returnAmount 单独给出；
 * - 回收站单(recycled)一律排除；
 * - 时间维度：今天/近7天/本月/全部 边界正确；
 * - 维度：店员/客户/品类(CategoryMap 未命中→其他)/收款方式/Top 商品。
 */
class ReportMultiDimTest {

    private fun item(
        name: String,
        qty: Int,
        price: Double,
    ) = BillItem(barcode = "1", sku = "S", name = name, spec = "", unit = "个", qty = qty, price = price, costPrice = 0.0)

    private fun bill(
        id: String,
        createdAt: String,
        status: String = BillEntity.STATUS_PAID,
        items: List<BillItem> = listOf(item("内六角螺丝", 2, 5.0)),
        staff: String = "小张",
        customer: String = "散客",
        payMethod: String? = "cash",
        direction: String = "sale",
        recycled: Boolean = false,
        total: Double = items.sumOf { it.subtotal },
    ) = BillEntity(
        id = id, customerName = customer, totalAmount = total, paidAmount = null,
        status = status, items = com.google.gson.Gson().toJson(items), note = "", staff = staff,
        createdAt = createdAt, updatedAt = createdAt, paidAt = null, needsSync = false,
        payMethod = payMethod, direction = direction, recycled = recycled,
    )

    private val itemsOf = { b: BillEntity ->
        runCatching {
            val t = object : com.google.gson.reflect.TypeToken<List<BillItem>>() {}.type
            com.google.gson.Gson().fromJson<List<BillItem>>(b.items, t) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    // ---------------- 时间维度边界 ----------------

    @Test
    fun `rangeStartDay 各维度下限正确`() {
        assertEquals("2026-09-26", rangeStartDayOf(ReportsRange.TODAY, "2026-09-26", "2026-09-01"))
        assertEquals("2026-09-20", rangeStartDayOf(ReportsRange.LAST7, "2026-09-26", "2026-09-01"))
        assertEquals("2026-09-01", rangeStartDayOf(ReportsRange.MONTH, "2026-09-26", "2026-09-01"))
        assertEquals("", rangeStartDayOf(ReportsRange.ALL, "2026-09-26", "2026-09-01"))
        // 跨月回退：9/26 的近7天 = 9/20（同月）；3/5 的近7天 = 2/27（平月）
        assertEquals("2026-02-27", rangeStartDayOf(ReportsRange.LAST7, "2026-03-05", "2026-03-01"))
        // 跨年回退：1/4 的近7天 = 去年 12/29
        assertEquals("2025-12-29", rangeStartDayOf(ReportsRange.LAST7, "2026-01-04", "2026-01-01"))
        // 闰年 2 月：2024/3/3 的近7天 = 2/26（闰年 29 天）
        assertEquals("2024-02-26", rangeStartDayOf(ReportsRange.LAST7, "2024-03-03", "2024-03-01"))
    }

    @Test
    fun `today 只含当天 paid 单`() {
        val bills = listOf(
            bill("A", "2026-09-26 10:00:00"),
            bill("B", "2026-09-25 10:00:00"),
            bill("C", "2026-09-26 23:59:59", status = BillEntity.STATUS_PENDING),
        )
        val r = computeReportUi(bills, itemsOf, ReportsRange.TODAY, "2026-09-26", "2026-09-01")
        assertEquals(1, r.rangeCount)
        assertEquals(10.0, r.rangeAmount, 1e-9)
    }

    @Test
    fun `last7 含恰好第7天的单`() {
        val bills = listOf(
            bill("A", "2026-09-20 00:00:01"),
            bill("B", "2026-09-19 23:59:59"),   // 7 天下限之外
            bill("C", "2026-09-26 12:00:00"),
        )
        val r = computeReportUi(bills, itemsOf, ReportsRange.LAST7, "2026-09-26", "2026-09-01")
        assertEquals(2, r.rangeCount)
    }

    // ---------------- 口径纪律 ----------------

    @Test
    fun `挂账不计营业额 另列挂账合计且不受时间维度影响`() {
        val bills = listOf(
            bill("A", "2026-09-26 10:00:00", status = BillEntity.STATUS_PENDING),
            bill("B", "2020-01-01 10:00:00", status = BillEntity.STATUS_PENDING, total = 88.0),
            bill("C", "2026-09-26 10:00:00", total = 30.0),
        )
        val r = computeReportUi(bills, itemsOf, ReportsRange.TODAY, "2026-09-26", "2026-09-01")
        assertEquals(30.0, r.rangeAmount, 1e-9)
        assertEquals(1, r.rangeCount)
        // 挂账合计含全部时段的 pending：A(10) + B(88) = 98，不受 TODAY 过滤影响
        assertEquals(98.0, r.pendingAmount, 1e-9)
    }

    @Test
    fun `退货冲减 returnAmount 单独给出`() {
        val bills = listOf(
            bill("A", "2026-09-26 10:00:00", total = 100.0),
            bill("R", "2026-09-26 11:00:00", total = 30.0, direction = "return"),
        )
        val r = computeReportUi(bills, itemsOf, ReportsRange.TODAY, "2026-09-26", "2026-09-01")
        assertEquals(70.0, r.rangeAmount, 1e-9)
        assertEquals(30.0, r.returnAmount, 1e-9)
        assertEquals(2, r.rangeCount)
    }

    @Test
    fun `回收站单一律排除`() {
        val bills = listOf(
            bill("A", "2026-09-26 10:00:00", recycled = true),
            bill("B", "2026-09-26 10:00:00", status = BillEntity.STATUS_PENDING, recycled = true, total = 55.0),
        )
        val r = computeReportUi(bills, itemsOf, ReportsRange.TODAY, "2026-09-26", "2026-09-01")
        assertEquals(0, r.rangeCount)
        assertEquals(0.0, r.pendingAmount, 1e-9)
    }

    @Test
    fun `all 维度不设时间过滤`() {
        val bills = listOf(
            bill("A", "2020-01-01 10:00:00"),
            bill("B", "2026-09-26 10:00:00"),
        )
        val r = computeReportUi(bills, itemsOf, ReportsRange.ALL, "2026-09-26", "2026-09-01")
        assertEquals(2, r.rangeCount)
        // 两张都是默认行 2×5=10 → 全部维度合计 20
        assertEquals(20.0, r.rangeAmount, 1e-9)
    }

    // ---------------- 维度聚合 ----------------

    @Test
    fun `按店员聚合排序`() {
        val bills = listOf(
            bill("A", "2026-09-26 10:00:00", staff = "小张", total = 30.0),
            bill("B", "2026-09-26 11:00:00", staff = "小李", total = 50.0),
            bill("C", "2026-09-26 12:00:00", staff = "小李", total = 20.0),
        )
        val r = computeReportUi(bills, itemsOf, ReportsRange.TODAY, "2026-09-26", "2026-09-01")
        assertEquals(listOf("小李", "小张"), r.staffPerf.map { it.name })
        assertEquals(70.0, r.staffPerf[0].amount, 1e-9)
        assertEquals(2, r.staffPerf[0].count)
    }

    @Test
    fun `按客户聚合 Top10 截断`() {
        val bills = (1..12).map { i ->
            bill("B$i", "2026-09-26 10:00:00", customer = "客户$i", total = i * 10.0)
        }
        val r = computeReportUi(bills, itemsOf, ReportsRange.TODAY, "2026-09-26", "2026-09-01")
        assertEquals(10, r.topCustomers.size)
        assertEquals("客户12", r.topCustomers[0].name)
        assertEquals(120.0, r.topCustomers[0].amount, 1e-9)
    }

    @Test
    fun `按品类聚合 CategoryMap 命中与未命中归其他`() {
        val bills = listOf(
            bill("A", "2026-09-26 10:00:00", items = listOf(item("内六角螺丝M8", 2, 5.0))),   // 紧固件
            bill("B", "2026-09-26 11:00:00", items = listOf(item("神秘外星货", 1, 8.0))),     // 其他
            bill("C", "2026-09-26 12:00:00", items = listOf(item("羊角锤", 1, 20.0))),        // 手动工具
        )
        val r = computeReportUi(bills, itemsOf, ReportsRange.TODAY, "2026-09-26", "2026-09-01")
        // 金额降序：手动工具 20 > 紧固件 10（"内六角螺丝"长词优先于"内六角"）> 其他 8
        assertEquals(listOf("手动工具", "紧固件", "其他"), r.categoryMix.map { it.name })
        assertEquals(20.0, r.categoryMix[0].amount, 1e-9)
        assertEquals(10.0, r.categoryMix[1].amount, 1e-9)
        assertEquals(8.0, r.categoryMix[2].amount, 1e-9)
    }

    @Test
    fun `按收款方式聚合 历史空值默认现金`() {
        val bills = listOf(
            bill("A", "2026-09-26 10:00:00", payMethod = "cash", total = 10.0),
            bill("B", "2026-09-26 11:00:00", payMethod = "pos", total = 20.0),
            bill("C", "2026-09-26 12:00:00", payMethod = null, total = 5.0),   // 老单默认现金
        )
        val r = computeReportUi(bills, itemsOf, ReportsRange.TODAY, "2026-09-26", "2026-09-01")
        val cash = r.payMethodMix.first { it.name == "现金" }
        val pos = r.payMethodMix.first { it.name == "POS" }
        assertEquals(15.0, cash.amount, 1e-9)
        assertEquals(20.0, pos.amount, 1e-9)
    }

    @Test
    fun `Top 商品按数量与金额两榜`() {
        val bills = listOf(
            bill("A", "2026-09-26 10:00:00", items = listOf(item("扎带", 100, 0.5), item("电钻", 1, 300.0))),
            bill("B", "2026-09-26 11:00:00", items = listOf(item("扎带", 50, 0.5))),
        )
        val r = computeReportUi(bills, itemsOf, ReportsRange.TODAY, "2026-09-26", "2026-09-01")
        assertEquals("扎带", r.topProductsByQty[0].name)
        assertEquals(150, r.topProductsByQty[0].qty)
        assertEquals("电钻", r.topProductsByAmount[0].name)
        assertEquals(300.0, r.topProductsByAmount[0].amount, 1e-9)
        assertTrue(r.topProductsByQty.size <= 10)
    }

    @Test
    fun `退货单商品行金额取负`() {
        val bills = listOf(
            bill("R", "2026-09-26 10:00:00", items = listOf(item("内六角螺丝", -2, 5.0)), direction = "return"),
        )
        val r = computeReportUi(bills, itemsOf, ReportsRange.TODAY, "2026-09-26", "2026-09-01")
        val row = r.topProductsByAmount.single()
        assertEquals(-10.0, row.amount, 1e-9)
        val cat = r.categoryMix.single { it.name == "紧固件" }
        assertEquals(-10.0, cat.amount, 1e-9)
    }
}
