package com.retaildemo.v2.ui.viewmodel

import com.retaildemo.v2.data.local.CategoryMap
import com.retaildemo.v2.data.local.entity.BillEntity
import com.retaildemo.v2.data.local.entity.BillItem

/** 报表时间维度（B14）。 */
enum class ReportsRange(val label: String) {
    TODAY("今天"),
    LAST7("近7天"),
    MONTH("本月"),
    ALL("全部"),
}

/** 一个维度行：名称 + 净额（退货已冲减）+ 单数/数量（视维度而异）。 */
data class NamedAmount(
    val name: String,
    val amount: Double,
    val count: Int = 0,
    val qty: Int = 0,
)

/**
 * 多维度报表聚合结果（B14）。
 * 口径（延续 B6 报表纪律，全部纯函数可单测）：
 * - 营业额只计**已出库(paid)**——挂单不计（"钱的真相在管家婆"）；
 * - 退货单 direction=return 取负冲减，[returnAmount] 单独给出冲减绝对值；
 * - 回收站单(recycled)不计入任何口径；
 * - [pendingAmount] 挂账合计不受时间维度影响（对账语义：现在还欠多少）。
 */
data class ReportUi(
    val range: ReportsRange = ReportsRange.TODAY,
    val rangeStartDay: String = "",
    /** 期间营业额（paid 净额，退货冲减后）。 */
    val rangeAmount: Double = 0.0,
    /** 期间出库单量（含退货单）。 */
    val rangeCount: Int = 0,
    /** 期间退货冲减绝对值（0=无退货）。 */
    val returnAmount: Double = 0.0,
    /** 挂账合计（全部 pending 未回收，不随时间维度）。 */
    val pendingAmount: Double = 0.0,
    /** 按店员（期间内，净额降序）。 */
    val staffPerf: List<NamedAmount> = emptyList(),
    /** 按客户（期间内，净额降序 Top10）。 */
    val topCustomers: List<NamedAmount> = emptyList(),
    /** 按品类（期间内，净额降序；商品名经 CategoryMap 细分，未命中归"其他"）。 */
    val categoryMix: List<NamedAmount> = emptyList(),
    /** 按收款方式（期间内，现金/POS/备注例外…，净额降序）。 */
    val payMethodMix: List<NamedAmount> = emptyList(),
    /** Top 商品按销量（件数）。 */
    val topProductsByQty: List<NamedAmount> = emptyList(),
    /** Top 商品按销售额。 */
    val topProductsByAmount: List<NamedAmount> = emptyList(),
)

/** 某单净额：退货取负（qty 为正存、金额随负），默认取 paidAmount（实收）兜底 totalAmount。 */
internal fun netOf(b: BillEntity): Double {
    val amt = b.paidAmount ?: b.totalAmount
    return if (b.direction == "return") -kotlin.math.abs(amt) else amt
}

/** 时间下限：TODAY=今天零点起；LAST7=7 天前的零点起（字符串前缀比较成立）；MONTH=本月 1 号起；ALL=不限。 */
internal fun rangeStartDayOf(range: ReportsRange, today: String, monthStart: String): String = when (range) {
    ReportsRange.TODAY -> today
    ReportsRange.LAST7 -> run {
        // today 为 yyyy-MM-dd，回退 6 天（含今天共 7 天）；纯字符串算术防时区/解析坑
        val p = today.split("-")
        if (p.size != 3) return@run ""
        var y = p[0].toIntOrNull() ?: return@run ""
        var m = p[1].toIntOrNull() ?: return@run ""
        var d = (p[2].toIntOrNull() ?: return@run "") - 6
        while (d < 1) {
            m -= 1
            if (m < 1) { m = 12; y -= 1 }
            d += when (m) {
                1, 3, 5, 7, 8, 10, 12 -> 31
                4, 6, 9, 11 -> 30
                else -> if ((y % 4 == 0 && y % 100 != 0) || y % 400 == 0) 29 else 28
            }
        }
        "%04d-%02d-%02d".format(y, m, d)
    }
    ReportsRange.MONTH -> monthStart
    ReportsRange.ALL -> ""
}

/**
 * B14 多维度报表聚合（纯函数）。
 * 单据来源=本地 Room 全量账单（离线可用）；金额与维度全部在此一次算清，UI 只读不推导。
 */
fun computeReportUi(
    bills: List<BillEntity>,
    itemsOf: (BillEntity) -> List<BillItem>,
    range: ReportsRange,
    today: String,
    monthStart: String,
): ReportUi {
    val rangeStart = rangeStartDayOf(range, today, monthStart)
    // B7：回收站单不计入任何报表口径
    val inRange = bills
        .filter { !it.recycled }
        .filter { rangeStart.isEmpty() || it.createdAt >= rangeStart }
    val paidBills = inRange.filter { it.status == BillEntity.STATUS_PAID }
    val pending = bills.filter { it.status == BillEntity.STATUS_PENDING && !it.recycled }

    val positives = paidBills.filter { it.direction != "return" }
    val returns = paidBills.filter { it.direction == "return" }

    fun aggBy(keyOf: (BillEntity) -> String): List<NamedAmount> =
        paidBills
            .groupBy { keyOf(it).ifBlank { "未记名" } }
            .map { (name, list) ->
                NamedAmount(
                    name = name,
                    amount = list.sumOf { netOf(it) },
                    count = list.size,
                )
            }
            .sortedByDescending { it.amount }

    val staff = aggBy { it.staff.ifBlank { "店员" } }
    val customers = aggBy { it.customerName.trim().ifBlank { "散客" } }.take(10)

    val payMix = paidBills
        .groupBy { BillEntity.payMethodLabel(it.payMethod) }
        .map { (name, list) -> NamedAmount(name = name, amount = list.sumOf { netOf(it) }, count = list.size) }
        .sortedByDescending { it.amount }

    val qtyMap = HashMap<String, Int>()
    val amtMap = HashMap<String, Double>()
    paidBills.forEach { bill ->
        itemsOf(bill).forEach { item ->
            val key = item.name.ifBlank { item.sku.ifBlank { "未知商品" } }
            // 行金额与行数量**原样累计**：退货行的 qty 由 createReturnBill 取负、行小计随之
            // 为负——符号已在行上，绝不再按单方向翻一次（双重取负会把退货洗回正数）。
            qtyMap[key] = (qtyMap[key] ?: 0) + item.qty
            amtMap[key] = (amtMap[key] ?: 0.0) + item.subtotal
        }
    }
    val topByQty = qtyMap.entries
        .sortedByDescending { it.value }.take(10)
        .map { NamedAmount(name = it.key, amount = amtMap[it.key] ?: 0.0, qty = it.value) }
    val topByAmount = amtMap.entries
        .sortedByDescending { it.value }.take(10)
        .map { NamedAmount(name = it.key, amount = it.value, qty = qtyMap[it.key] ?: 0) }

    // 品类：商品行金额按行归属（商品名 → CategoryMap 细分），未命中归"其他"；
    // 退货行小计已带负号（同上，行上符号为准），原样累计即自动冲减。
    val catMap = HashMap<String, Double>()
    paidBills.forEach { bill ->
        itemsOf(bill).forEach { item ->
            val cat = CategoryMap.refine(item.name) ?: "其他"
            catMap[cat] = (catMap[cat] ?: 0.0) + item.subtotal
        }
    }
    val categories = catMap.entries
        .map { NamedAmount(name = it.key, amount = it.value) }
        .filter { kotlin.math.abs(it.amount) > 0.001 }
        .sortedByDescending { it.amount }

    return ReportUi(
        range = range,
        rangeStartDay = rangeStart,
        rangeAmount = paidBills.sumOf { netOf(it) },
        rangeCount = paidBills.size,
        returnAmount = returns.sumOf { -netOf(it) },
        pendingAmount = pending.sumOf { it.totalAmount },
        staffPerf = staff,
        topCustomers = customers,
        categoryMix = categories,
        payMethodMix = payMix,
        topProductsByQty = topByQty,
        topProductsByAmount = topByAmount,
    )
}
