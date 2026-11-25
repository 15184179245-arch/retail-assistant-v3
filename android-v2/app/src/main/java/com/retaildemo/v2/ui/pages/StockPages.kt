package com.retaildemo.v2.ui.pages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.retaildemo.v2.ui.components.sxElevation
import com.retaildemo.v2.R
import com.retaildemo.v2.ui.theme.SxElevation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.retaildemo.v2.data.remote.LowStockDto
import com.retaildemo.v2.data.remote.MovementDto
import com.retaildemo.v2.ui.components.EmptyState
import com.retaildemo.v2.ui.components.PriceText
import com.retaildemo.v2.ui.components.retaildemoTopBar
import com.retaildemo.v2.ui.components.TopBarStyle
import com.retaildemo.v2.ui.theme.Border
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.DangerLight
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.PrimaryLight
import com.retaildemo.v2.ui.theme.Success
import com.retaildemo.v2.ui.theme.SuccessLight
import com.retaildemo.v2.ui.theme.SurfaceSubtle
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.data.repo.BillTime
import com.retaildemo.v2.ui.theme.TextWeak
import com.retaildemo.v2.ui.theme.Warning
import com.retaildemo.v2.ui.theme.WarningLight
import com.retaildemo.v2.ui.viewmodel.StockViewModel

/** 库存预警：低库存 / 零库存 两个 tab。 */
@Composable
fun LowStockPage(onBack: () -> Unit, viewModel: StockViewModel = hiltViewModel()) {
    val low by viewModel.low.collectAsState()
    val zero by viewModel.zero.collectAsState()
    // 合并为「全部」+ chips 子集过滤（稿 3:1687 四状态 chips：全部 / 已归零 / 紧张 / 冻结）
    var filter by remember { mutableStateOf(LowFilter.ALL) }
    val allItems = remember(low, zero) { zero + low }
    val frozenCount = remember(allItems) { allItems.count { it.stock_qty >= 5 } } // 阈值≥5 视作冻结（待补）
    val list = when (filter) {
        LowFilter.ALL -> allItems
        LowFilter.ZERO -> zero
        LowFilter.TIGHT -> low.filter { it.stock_qty in 0.01..4.99 }
        LowFilter.FROZEN -> low.filter { it.stock_qty >= 5 }
    }

    Scaffold(
        containerColor = Design.PageBg,
        topBar = {
            retaildemoTopBar(
                title = "库存预警",
                subtitle = "${zero.size} 件已归零 · ${low.size} 件紧张",
                onBack = onBack,
                actions = {
                    // 稿 3:1687 顶栏右上「一键补货」CTA
                    Surface(
                        color = Primary,
                        shape = RoundedCornerShape(Design.PillRadius.dp),
                        modifier = Modifier
                            .height(36.dp)
                            .clickable { /* TODO: 进入补货流程 */ },
                    ) {
                        Text(
                            "一键补货",
                            color = Color.White,
                            fontSize = Design.FontSm.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = Design.SpaceMd.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 稿 3:1687：4 状态 chips（全部 N / 已归零 N / 紧张 N / 冻结 N）
            LowFilterChips(
                selected = filter,
                allCount = allItems.size,
                zeroCount = zero.size,
                tightCount = low.count { it.stock_qty in 0.01..4.99 },
                frozenCount = frozenCount,
                onSelect = { filter = it },
            )
            if (list.isEmpty()) {
                EmptyState(
                    illustrationRes = R.drawable.ill_empty_stock,
                    title = "暂无预警货品", subtitle = "当前库存状况良好，未达到预警阈值")
            } else {
                LazyColumn(
                    // 稿（画布 17 库存预警）：6 张卡 y=170..600，卡高 76，节距 86 → **卡距 10**。
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(16.dp),
                    modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                ) {
                    items(list, key = { it.id }) { item ->
                        LowStockItemCard(item = item)
                    }
                }
            }
        }
    }
}

private enum class LowFilter { ALL, ZERO, TIGHT, FROZEN }

@Composable
private fun LowFilterChips(
    selected: LowFilter,
    allCount: Int,
    zeroCount: Int,
    tightCount: Int,
    frozenCount: Int,
    onSelect: (LowFilter) -> Unit,
) {
    val items = listOf(
        LowFilter.ALL to ("全部 $allCount" to "全部"),
        LowFilter.ZERO to ("已归零 $zeroCount" to "已归零"),
        LowFilter.TIGHT to ("紧张 $tightCount" to "紧张"),
        LowFilter.FROZEN to ("冻结 $frozenCount" to "冻结"),
    )
    Surface(color = Color.White, modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            items.forEach { (f, label) ->
                val isSel = f == selected
                Surface(
                    color = if (isSel) PrimaryLight else SurfaceSubtle,
                    shape = RoundedCornerShape(Design.PillRadius.dp),
                    modifier = Modifier.clickable { onSelect(f) },
                ) {
                    Text(
                        label.first,
                        fontSize = Design.FontSm.sp,
                        lineHeight = 18.sp,
                        color = if (isSel) Primary else TextSecondary,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LowStockItemCard(item: LowStockDto) {
    val status = when {
        item.stock_qty == 0.0 -> LowStatus.ZERO
        item.stock_qty < 5 -> LowStatus.TIGHT
        else -> LowStatus.FROZEN
    }
    val letter = item.name.firstOrNull()?.toString() ?: "?"
    val avatarBg = when (status) {
        LowStatus.ZERO -> DangerLight
        LowStatus.TIGHT -> WarningLight
        LowStatus.FROZEN -> PrimaryLight
    }
    val avatarFg = when (status) {
        LowStatus.ZERO -> Danger
        LowStatus.TIGHT -> Warning
        LowStatus.FROZEN -> Primary
    }
    val statusLabel = when (status) {
        LowStatus.ZERO -> "已归零"
        LowStatus.TIGHT -> "紧张"
        LowStatus.FROZEN -> "冻结"
    }
    val statusBg = avatarBg
    val statusFg = avatarFg

    Card(
        shape = RoundedCornerShape(Design.CardRadius.dp),
        colors = CardDefaults.cardColors(containerColor = Design.CardSurface),
        border = BorderStroke(0.5.dp, Border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().sxElevation(SxElevation.L2),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp),
        ) {
            // 稿 3:1687 左侧 letter avatar（暖色填充 + 名字首字）
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = avatarBg,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        letter,
                        fontSize = Design.FontMd.sp,
                        lineHeight = 20.sp,
                        color = avatarFg,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    fontSize = Design.FontMd.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                // 稿：1楼 · A架-02 · 预警线 10
                val sub = buildString {
                    append("1楼 · ")
                    append(item.location ?: "未分配")
                    append(" · 预警线 ")
                    append(item.stock_qty.toInt() + 10)
                }
                Text(
                    sub,
                    fontSize = Design.FontXs.sp,
                    lineHeight = 15.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        item.stock_qty.toInt().toString(),
                        fontSize = 22.sp,
                        lineHeight = 26.sp,
                        color = if (status == LowStatus.ZERO) Danger else TextMain,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        item.unit ?: "件",
                        fontSize = Design.FontXs.sp,
                        lineHeight = 18.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = statusBg,
                ) {
                    Text(
                        statusLabel,
                        fontSize = Design.FontXs.sp,
                        lineHeight = 15.sp,
                        color = statusFg,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

private enum class LowStatus { ZERO, TIGHT, FROZEN }

/** 出入库记录列表。 */
@Composable
fun MovementsPage(onBack: () -> Unit, viewModel: StockViewModel = hiltViewModel()) {
    val movements by viewModel.movements.collectAsState()
    Scaffold(
        containerColor = Design.PageBg,
        topBar = {
            retaildemoTopBar(
                title = "出入库明细",
                subtitle = "流水核算 · 调账与开单全链路",
                onBack = onBack,
            )
        },
    ) { padding ->
        // 36 三态合辑 · 离线条：断网就地降级（M3），照常记账不转圈
        val isOnline by viewModel.isOnline.collectAsState()
        if (!isOnline) {
            MovementOfflineBar()
        }
        if (movements.isEmpty()) {
            Column(Modifier.padding(padding)) {
                EmptyState(
                    illustrationRes = R.drawable.ill_empty_bill,
                    title = "暂无出入库记录", subtitle = "管家婆核销开单与调账操作将实时记录在此")
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(16.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .navigationBarsPadding(),
            ) {
                // 27 对齐：流水按日期分组（今天/昨天/更早回退显示日期），数据结构取自 created_at 前缀
                val today = BillTime.today()
                itemsIndexed(movements, key = { _, m -> m.id }) { idx, m ->
                    val day = (m.created_at ?: "").take(10)
                    val prevDay = movements.getOrNull(idx - 1)?.let { (it.created_at ?: "").take(10) } ?: day
                    if (idx == 0 || day != prevDay) {
                        Text(
                            text = if (day == today) "今天" else day,
                            fontSize = Design.FontSm.sp,
                            color = TextWeak,
                            modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 4.dp),
                        )
                    }
                    MovementItemCard(m)
                }
            }
        }
    }
}

@Composable
private fun MovementItemCard(m: MovementDto) {
    val isIn = m.change_qty >= 0

    Card(
        shape = RoundedCornerShape(Design.CardRadius.dp),
        colors = CardDefaults.cardColors(containerColor = Design.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().sxElevation(SxElevation.L2),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp),
        ) {
            // 变动方向图标指示
            Surface(
                shape = CircleShape,
                color = if (isIn) SuccessLight else DangerLight,
                modifier = Modifier.size(38.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (isIn) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                        contentDescription = null,
                        tint = if (isIn) Success else Danger,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    m.product_name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = SurfaceSubtle,
                    ) {
                        Text(
                            m.movement_type.ifBlank { if (isIn) "入库" else "出库" },
                            fontSize = 11.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    if (!m.note.isNullOrBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            m.note,
                            fontSize = 11.sp,
                            color = TextWeak,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "${m.operator ?: "店员"} · ${(m.created_at ?: "").take(16)}",
                    fontSize = 11.sp,
                    color = TextWeak,
                )
            }

            Spacer(Modifier.width(10.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    (if (isIn) "+" else "") + "${m.change_qty}",
                    color = if (isIn) Success else Danger,
                    fontWeight = FontWeight.Black,
                    fontSize = 17.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "结余 ${m.new_stock_qty}",
                    fontSize = 12.sp,
                    color = TextSecondary,
                )
            }
        }
    }
}

/** 36 三态合辑 · 离线条（M3 降级）：断网就地显示，不转圈；口径=本地优先照常记账。 */
@Composable
private fun MovementOfflineBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(SurfaceSubtle, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(TextSecondary, CircleShape),
        )
        Text(
            text = "离线 · 照常记账，联网自动补传",
            fontSize = Design.FontSm.sp,
            color = TextSecondary,
        )
    }
}
