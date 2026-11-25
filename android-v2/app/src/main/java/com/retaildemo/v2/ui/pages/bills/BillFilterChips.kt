package com.retaildemo.v2.ui.pages.bills

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retaildemo.v2.ui.components.ChipCloud
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.PrimaryLight
import com.retaildemo.v2.ui.theme.SurfaceSubtle
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.TextWeak

/** B12 日常账单三段：挂账（本人）/ 待出库（全店）/ 已出库（历史筛选）。 */
enum class BillsTab { DRAFT, READY, PAID }

/**
 * B12 分段切换：旧两段「挂账/已出库」扩成三段——
 * 挂账=个人单（只显示本人）、待出库=三件齐进全店+同步收银端插件、已出库=历史。
 * 01 稿 3:2 分段行（独立胶囊 chip + 右侧「最新在前」提示）保持不变。
 */
@Composable
fun StatusPillToggle(
    draftCount: Int,
    readyCount: Int,
    paidCount: Int,
    selected: BillsTab,
    onSelect: (BillsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SegmentPill(
            label = "挂账 $draftCount",
            selected = selected == BillsTab.DRAFT,
            onClick = { onSelect(BillsTab.DRAFT) },
        )
        Spacer(Modifier.width(Design.SpaceSm.dp))
        SegmentPill(
            label = "待出库 $readyCount",
            selected = selected == BillsTab.READY,
            onClick = { onSelect(BillsTab.READY) },
        )
        Spacer(Modifier.width(Design.SpaceSm.dp))
        SegmentPill(
            label = "已出库 $paidCount",
            selected = selected == BillsTab.PAID,
            onClick = { onSelect(BillsTab.PAID) },
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "最新在前",
            color = TextWeak,
            fontSize = Design.FontXs.sp,
        )
    }
}

/** 01 稿分段胶囊（canvas_01.svg 3:2）：h32 / rx 16 / 水平 13；选中=PrimaryLight 底橙字，未选中=SurfaceSubtle 底次级字。 */
@Composable
private fun SegmentPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) PrimaryLight else SurfaceSubtle)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Primary else TextSecondary,
            fontWeight = FontWeight.SemiBold,
            fontSize = Design.FontMdPlus2.sp,
            maxLines = 1,
        )
    }
}

/** U2 v2 无边框填充 chips 配色（与查价/开单页一致）。 */
@Composable
private fun filledChipColors(selected: Boolean) = FilterChipDefaults.filterChipColors(
    containerColor = SurfaceSubtle,
    labelColor = TextSecondary,
    selectedContainerColor = PrimaryLight,
    selectedLabelColor = Primary,
)

@Composable
private fun filledChipBorder(selected: Boolean) = FilterChipDefaults.filterChipBorder(
    borderColor = Color.Transparent,
    selectedBorderColor = Color.Transparent,
    borderWidth = 0.dp,
    selectedBorderWidth = 0.dp,
    enabled = true,
    selected = selected,
)

/** 账单日期与范围快捷筛选行。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BillFilterBar(
    dateFilter: String?,
    onDateChange: (String?) -> Unit,
    mineOnly: Boolean,
    onMineChange: (Boolean) -> Unit,
    invoiceOnly: Boolean,
    onInvoiceChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dayFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
    val today = dayFmt.format(java.util.Date())
    val yesterday = dayFmt.format(java.util.Date(System.currentTimeMillis() - 86_400_000L))

    // 用 ChipCloud（FlowRow）而不是 Row：这一行 chip 的总宽在 360dp 屏上已经吃掉约 294dp，
    // 只剩 34dp 给「需开票」——Row + weight 垫片会把最后一颗 chip 压成逐字竖排的
    // 三行「需/开/票」（真机截图实锤）。chip 云在放不下时自然折行，
    // 任意字阶/屏宽都不再挤压；筛选行本来就该是「chip 云」而不是「chip 队列」。
    ChipCloud(modifier = modifier.fillMaxWidth()) {
        listOf("全部" to null, "今天" to today, "昨天" to yesterday).forEach { (label, day) ->
            val selected = dateFilter == day
            FilterChip(
                selected = selected,
                onClick = { onDateChange(day) },
                label = { Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) },
                shape = RoundedCornerShape(Design.PillRadius.dp),
                colors = filledChipColors(selected),
                border = filledChipBorder(selected),
            )
        }

        listOf("全店" to false, "我的" to true).forEach { (label, mine) ->
            val selected = mineOnly == mine
            FilterChip(
                selected = selected,
                onClick = { onMineChange(mine) },
                label = { Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) },
                shape = RoundedCornerShape(Design.PillRadius.dp),
                colors = filledChipColors(selected),
                border = filledChipBorder(selected),
            )
        }

        FilterChip(
            selected = invoiceOnly,
            onClick = { onInvoiceChange(!invoiceOnly) },
            label = { Text("需开票", fontSize = 12.sp, fontWeight = if (invoiceOnly) FontWeight.Bold else FontWeight.Medium) },
            shape = RoundedCornerShape(Design.PillRadius.dp),
            colors = filledChipColors(invoiceOnly),
            border = filledChipBorder(invoiceOnly),
        )
    }
}
