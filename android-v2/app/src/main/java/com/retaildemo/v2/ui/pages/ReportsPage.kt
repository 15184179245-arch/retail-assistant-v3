package com.retaildemo.v2.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.retaildemo.v2.ui.components.EmptyState
import com.retaildemo.v2.ui.components.PriceText
import com.retaildemo.v2.ui.components.sxElevation
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.PrimaryLight
import com.retaildemo.v2.ui.theme.SxElevation
import com.retaildemo.v2.ui.theme.SurfaceSubtle
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.TextWeak
import com.retaildemo.v2.ui.theme.Warning
import com.retaildemo.v2.ui.viewmodel.NamedAmount
import com.retaildemo.v2.ui.viewmodel.ReportUi
import com.retaildemo.v2.ui.viewmodel.ReportsRange
import com.retaildemo.v2.ui.viewmodel.ReportsViewModel

/**
 * B14 多维度报表页：时间段（今天/近7天/本月/全部）× 维度（店员/客户/品类/收款方式/Top商品）。
 * 数据全部来自本地账单（离线可用）；口径=已出库净额（退货冲减），挂账单不计营收。
 */
@Composable
fun ReportsPage(
    onBack: () -> Unit,
    viewModel: ReportsViewModel = hiltViewModel(),
) {
    val report by viewModel.report.collectAsState()
    val range by viewModel.range.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 顶栏
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextMain)
            }
            Column {
                Text("多维度报表", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextMain)
                Text(
                    "口径：已出库净额 · 退货冲减 · 挂账另计",
                    fontSize = 11.sp,
                    color = TextWeak,
                    lineHeight = 15.sp,
                )
            }
        }

        // 时间维度 chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReportsRange.entries.forEach { r ->
                val selected = range == r
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Design.PillRadius.dp))
                        .background(if (selected) PrimaryLight else SurfaceSubtle)
                        .clickable { viewModel.setRange(r) }
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = r.label,
                        color = if (selected) Primary else TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }

        if (report.rangeCount == 0 && report.pendingAmount == 0.0) {
            EmptyState(
                illustrationRes = com.retaildemo.v2.R.drawable.ill_empty_bill,
                title = "该时段暂无出库账单",
                subtitle = "出库的账单会自动进入报表（挂账单不计营收）",
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 6.dp, bottom = 24.dp, start = 16.dp, end = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { KpiCard(report) }
            if (report.staffPerf.isNotEmpty()) {
                item { DimCard("按店员", report.staffPerf, showCount = true) }
            }
            if (report.topCustomers.isNotEmpty()) {
                item { DimCard("按客户 · 消费 Top10", report.topCustomers, showCount = true) }
            }
            if (report.categoryMix.isNotEmpty()) {
                item { DimCard("按品类", report.categoryMix) }
            }
            if (report.payMethodMix.isNotEmpty()) {
                item { DimCard("按收款方式", report.payMethodMix, showCount = true) }
            }
            if (report.topProductsByQty.isNotEmpty()) {
                item { DimCard("商品销量 Top10", report.topProductsByQty, showQty = true) }
            }
            if (report.topProductsByAmount.isNotEmpty()) {
                item { DimCard("商品销售额 Top10", report.topProductsByAmount) }
            }
        }
    }
}

/** 期间 KPI 卡：营业额 + 单量 + 退货冲减 + 挂账合计。 */
@Composable
private fun KpiCard(report: ReportUi) {
    Card(
        shape = RoundedCornerShape(Design.CardRadius.dp),
        colors = CardDefaults.cardColors(containerColor = Design.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .sxElevation(SxElevation.L3),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${report.range.label}营业额",
                        fontSize = 12.sp,
                        color = TextWeak,
                        lineHeight = 16.sp,
                    )
                    PriceText(
                        price = report.rangeAmount,
                        color = Danger,
                        integerSize = 26.sp,
                        symbolSize = 14.sp,
                        decimalSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "已出库 ${report.rangeCount} 单",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        lineHeight = 16.sp,
                    )
                    if (report.returnAmount > 0.0) {
                        Text(
                            "退货冲减 ¥%.2f".format(report.returnAmount),
                            fontSize = 12.sp,
                            color = Warning,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceSubtle, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "挂账合计（不在营业额内）",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    lineHeight = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                PriceText(
                    price = report.pendingAmount,
                    color = TextMain,
                    integerSize = 16.sp,
                    symbolSize = 10.sp,
                    decimalSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** 维度卡：标题 + 行列表（名称 + 可选数量/单数 + 金额）。 */
@Composable
private fun DimCard(
    title: String,
    rows: List<NamedAmount>,
    showCount: Boolean = false,
    showQty: Boolean = false,
) {
    Card(
        shape = RoundedCornerShape(Design.CardRadius.dp),
        colors = CardDefaults.cardColors(containerColor = Design.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .sxElevation(SxElevation.L2),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextMain,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(6.dp))
            rows.forEachIndexed { idx, row ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                ) {
                    Text(
                        "%2d".format(idx + 1),
                        fontSize = 11.sp,
                        color = TextWeak,
                        lineHeight = 15.sp,
                        modifier = Modifier.width(20.dp),
                    )
                    Text(
                        row.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 17.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (showQty && row.qty != 0) {
                        Text(
                            "x${row.qty}",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            lineHeight = 16.sp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    if (showCount && row.count != 0) {
                        Text(
                            "${row.count} 单",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            lineHeight = 16.sp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    PriceText(
                        price = row.amount,
                        color = if (row.amount < 0) Danger else TextMain,
                        integerSize = 14.sp,
                        symbolSize = 10.sp,
                        decimalSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
