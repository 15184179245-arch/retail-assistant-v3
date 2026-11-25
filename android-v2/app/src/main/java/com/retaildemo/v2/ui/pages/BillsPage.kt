package com.retaildemo.v2.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.retaildemo.v2.R
import com.retaildemo.v2.data.local.entity.BillEntity
import com.retaildemo.v2.ui.components.EmptyState
import com.retaildemo.v2.ui.components.NumberField
import com.retaildemo.v2.ui.components.PrimaryActionButton
import com.retaildemo.v2.ui.components.ScanFeedback
import com.retaildemo.v2.ui.components.directTap
import com.retaildemo.v2.ui.components.staggeredAppear
import com.retaildemo.v2.ui.pages.bills.BillCardItem
import com.retaildemo.v2.ui.pages.bills.BillFilterBar
import com.retaildemo.v2.ui.pages.bills.BillsTab
import com.retaildemo.v2.ui.pages.bills.StatusPillToggle
import com.retaildemo.v2.ui.pages.bills.SwipeDeleteBillRow
import com.retaildemo.v2.ui.pages.bills.TodayStatsSection
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.BorderStrong
import com.retaildemo.v2.ui.theme.SurfaceSubtle
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextWeak
import com.retaildemo.v2.ui.viewmodel.BillUiModel
import com.retaildemo.v2.ui.viewmodel.BillsViewModel
import com.retaildemo.v2.ui.window.SanxAlertDialog

/**
 * 记账首页：模块化重构。
 * B12 两态闭环（2026-09-26 老板令，废除逐条扫码录入管家婆主路径）：
 * 1. 今日统计大卡。
 * 2. 日常两块：挂账（stage=draft，个人单只显示本人）/ 待出库（stage=ready，全店可见+同步收银端插件）；
 *    已出库（paid）退居历史块。
 * 3. 挂账单点开详情 → 补全三件（串货标注/收款方式/凭证）→ 标待出库；
 *    逐条大二维码退居**待出库详情**兜底（插件不通时还能扫枪）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillsPage(
    onNewBill: () -> Unit,
    onSettings: () -> Unit,
    onOpenBill: (String) -> Unit = {},
    onOpenCloseDay: () -> Unit = {},
    viewModel: BillsViewModel = hiltViewModel(),
) {
    val bills by viewModel.filteredBills.collectAsState()
    val dateFilter by viewModel.dateFilter.collectAsState()
    val mineOnly by viewModel.mineOnly.collectAsState()
    val stats by viewModel.todayStats.collectAsState()
    // M2 空库就地恢复（重装/换机后本地全空时给找回入口）
    val restoring by viewModel.restoring.collectAsState()
    val restoreMsg by viewModel.restoreMsg.collectAsState()

    // B12 两态：日常账单只分「挂账(本人) / 待出库(全店)」两块；已出库退居历史筛选
    var tab by remember { mutableStateOf(BillsTab.DRAFT) }
    var invoiceOnly by remember { mutableStateOf(false) }
    var tempCodeOnly by remember { mutableStateOf(false) }  // B7 待补码挂单筛选
    var editTarget by remember { mutableStateOf<Pair<BillUiModel, Int>?>(null) }
    // M13 侧滑删除：单开互斥（展开行的 id）+ 塌陷退场中的行 id（真删在 onCollapsed 里执行）
    var openSwipeRowId by remember { mutableStateOf<String?>(null) }
    var deletingId by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    // 滚动即收起（M13 步骤③「点卡片以外任意处回弹」的滚动面）
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) openSwipeRowId = null
    }

    val uiBills = bills.orEmpty()
    // 2026-09-19 性能收口：三段筛选 remember 化——此前每次重组都全量 filter（含
    // tempCodeOnly 的 items.any 嵌套扫描），转场期间每帧重算，账单多时雪上加霜
    // B12 分区：挂账=stage=draft 且**只显示本人**（有名册 id 按 owner_id，老单按名兜底）；
    // 待出库=stage=ready，**全店可见**（收银端插件同步领同一口径）；已出库=paid 只进历史块。
    val draftMine = remember(uiBills) {
        uiBills.filter {
            it.status == BillEntity.STATUS_PENDING &&
                it.stage == BillEntity.STAGE_DRAFT &&
                viewModel.isMine(it)
        }
    }
    val ready = remember(uiBills) {
        uiBills.filter {
            it.status == BillEntity.STATUS_PENDING && it.stage == BillEntity.STAGE_READY
        }
    }
    val paid = remember(uiBills) { uiBills.filter { it.status == BillEntity.STATUS_PAID } }
    val current = remember(uiBills, tab, invoiceOnly, tempCodeOnly) {
        val base = when (tab) {
            BillsTab.DRAFT -> draftMine
            BillsTab.READY -> ready
            BillsTab.PAID -> paid
        }
        var list = base
        // B7 待补码：挂了临时码(TMP-)的单
        if (tempCodeOnly) list = list.filter { b -> b.items.any { it.tempCode != null } }
        if (invoiceOnly) list = list.filter { it.invoiceNeeded }
        list
    }

    // 01 稿页头副标：「9月14日 周日 · 今日 6 单」
    val todayLabel = remember {
        java.text.SimpleDateFormat("M月d日 E", java.util.Locale.CHINA).format(java.util.Date())
    }

    // 顶部穿透（与底部悬浮底栏同款架构）：单据列表铺满全屏，上滑时卡片从顶部 chrome
    // （页头 + 今日合计 + 筛选胶囊 + 离线条）底下穿过消失；chrome 用不透明背景压住卡片，
    // 底边 12dp 同色羽化让消失线「溶入」背景，不再出现视口中段的硬切截断。
    // chrome 高度 onSizeChanged 动态实测（离线条出现/消失、字号缩放自适应），内容层顶端让位 +12dp。
    val density = LocalDensity.current
    var topChromePx by remember { mutableStateOf(0) }
    val listTopPad = with(density) { topChromePx.toDp() } + 12.dp
    val isOnline by viewModel.isOnline.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ---- 内容层：三态，顶部让位给 chrome（实测高度 + 12dp 间距），底部 90dp 避让悬浮底栏 ----
        when {
            bills == null -> {
                // 36 三态合辑：列表加载骨架（行结构复制单据卡），呼吸 1.6s；禁转圈盖全屏
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = listTopPad)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    repeat(3) { BillCardSkeleton() }
                }
            }
            current.isEmpty() -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = listTopPad + 20.dp),
                ) {
                    EmptyState(
                        illustrationRes = R.drawable.ill_empty_bill,
                        title = when (tab) {
                            BillsTab.PAID -> "暂无已出库账单"
                            BillsTab.READY -> "暂无待出库账单"
                            BillsTab.DRAFT -> "暂无挂账账单"
                        },
                        subtitle = when (tab) {
                            BillsTab.PAID -> "管家婆录完后标记出库的账单在此展示"
                            BillsTab.READY -> "补全三件（串货标注/收款方式/凭证）标待出库的账单在此展示，收银端可领"
                            BillsTab.DRAFT -> "店员开单后先在此挂账（个人单），补全三件后标待出库"
                        },
                        actionText = if (tab == BillsTab.DRAFT) "快速记一笔（支持连续扫码）" else null,
                        onAction = if (tab == BillsTab.DRAFT) onNewBill else null,
                    )
                    if (tab == BillsTab.DRAFT) {
                        if (uiBills.isEmpty()) {
                            // M2：本地一张单都没有（首装/重装/换机）——就地给「从服务器找回」，
                            // 别让店员把「本地空」误读成「账没了」；本地有单时不再打扰。
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { viewModel.restoreBills() },
                                enabled = !restoring,
                                shape = RoundedCornerShape(Design.PillRadius.dp),
                            ) {
                                if (restoring) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Primary,
                                    )
                                } else {
                                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(6.dp))
                                Text("从服务器恢复账单", fontSize = 13.sp)
                            }
                            restoreMsg?.let {
                                Spacer(Modifier.height(6.dp))
                                Text(it, fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }
            else -> {
                // 01 稿：单据列表=滚动视口，卡间距 10；顶部穿透进 chrome、底部留白防遮挡
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = listTopPad, bottom = 90.dp),
                ) {
                    itemsIndexed(current, key = { _, bill -> bill.id }) { billIndex, bill ->
                        // M13 侧滑删除：左滑 88dp 露删除区，行内两击确认，塌陷退场后真删
                        SwipeDeleteBillRow(
                            open = openSwipeRowId == bill.id,
                            onOpen = { openSwipeRowId = bill.id },
                            onClose = { if (openSwipeRowId == bill.id) openSwipeRowId = null },
                            collapsing = deletingId == bill.id,
                            onConfirmDelete = { deletingId = bill.id },
                            onCollapsed = {
                                deletingId = null
                                openSwipeRowId = null
                                viewModel.deleteBill(bill.id)
                            },
                        ) {
                            // staggeredAppear 索引直取：旧写法 current.indexOf(bill) 每行
                            // O(n) 全表 equals 扫描，整列 O(n²)，滚动/重组时白烧 CPU
                            Box(modifier = Modifier.staggeredAppear(billIndex)) {
                                BillCardItem(
                                    bill = bill,
                                    onDelete = {},
                                    onEditItem = { idx -> editTarget = bill to idx },
                                    onOpenBill = {
                                        openSwipeRowId = null  // 离开本页前收起，返回不残留展开态
                                        onOpenBill(bill.id)
                                    },
                                    onDeleteItem = { idx -> viewModel.removeItem(bill.id, idx) },
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }

        // ---- 顶部 chrome 层：不透明背景压住穿过的卡片；自身无点击消费，手指压在此处仍可拖动列表 ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { topChromePx = it.height }
                .chromeBackground(MaterialTheme.colorScheme.background),
        ) {
            // 01 稿页头（3:2）：24sp 大标题 + 日期单数副标 + 橙色扫码钮（记一笔主入口）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = Design.SpaceLg.dp, vertical = Design.SpaceSm.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "记账",
                        fontSize = Design.FontXxl.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMain,
                    )
                    Text(
                        text = "$todayLabel · 今日 ${stats.count + stats.todayPendingCount} 单",
                        fontSize = Design.FontSm.sp,
                        color = TextWeak,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                    .background(Primary)
                    .directTap(
                        sfx = ScanFeedback.Sfx.KEY,
                        onClick = {
                            openSwipeRowId = null
                            onNewBill()
                        },
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    // 画布导出的 ic_scan_bill 是「完整按钮图元」：自带 44dp 圆角橙底 + 白色扫码图形。
                    // 若再叠加 tint（白/橙）会把整张矢量连底色一起改色，出现"白块吞底"。
                    // 故此处 Color.Unspecified：让矢量按导出原色（橙底 + 白图形）1:1 呈现。
                    Icon(
                        painter = painterResource(R.drawable.ic_scan_bill),
                        contentDescription = "扫码记一笔",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }

            // chrome 下半段与列表同宽（水平 16，与 01 稿 358 卡边距一致）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(10.dp))
                TodayStatsSection(stats)

                Spacer(Modifier.height(14.dp))
                StatusPillToggle(
                    draftCount = draftMine.size,
                    readyCount = ready.size,
                    paidCount = paid.size,
                    selected = tab,
                    onSelect = {
                        openSwipeRowId = null  // chrome 交互也按「点卡片以外」收起侧滑行
                        tab = it
                    },
                )

                // 反哺恢复（2026-09-19）：日期/只看我的/需开票筛选——重设计时 BillFilterBar
                // 调用被丢（状态与组件都在、调用没了），VM filteredBills 的过滤逻辑完好。
                BillFilterBar(
                    dateFilter = dateFilter,
                    onDateChange = { viewModel.setDateFilter(it) },
                    mineOnly = mineOnly,
                    onMineChange = { viewModel.setMineOnly(it) },
                    invoiceOnly = invoiceOnly,
                    onInvoiceChange = { invoiceOnly = it },
                )

                // 36 三态合辑 · 离线条（M3 降级）：断网照常记账，联网自动补传
                if (!isOnline) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
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
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    // 编辑单项数量与价格弹窗
    editTarget?.let { (bill, idx) ->
        val item = bill.items.getOrNull(idx)
        if (item != null) {
            EditItemDialog(
                name = item.name,
                currentQty = item.qty,
                currentPrice = item.price,
                onConfirm = { newQty, newPrice ->
                    viewModel.updateItem(bill.id, idx, newQty, newPrice)
                    editTarget = null
                },
                onDismiss = { editTarget = null },
            )
        }
    }
}

@Composable
private fun EditItemDialog(
    name: String,
    currentQty: Int,
    currentPrice: Double,
    onConfirm: (Int, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var qtyStr by remember { mutableStateOf(currentQty.toString()) }
    var priceStr by remember { mutableStateOf("%.2f".format(currentPrice)) }

    SanxAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改商品", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                NumberField(
                    value = qtyStr,
                    onValueChange = { qtyStr = it },
                    label = "数量",
                    decimals = false,
                )
                NumberField(
                    value = priceStr,
                    onValueChange = { priceStr = it },
                    label = "单价 (¥)",
                )
            }
        },
        confirmText = "保存",
        confirmEnabled = (qtyStr.toIntOrNull() ?: currentQty) >= 1 && (priceStr.toDoubleOrNull() ?: currentPrice) >= 0,
        onConfirm = {
            val qty = qtyStr.toIntOrNull() ?: currentQty
            val price = priceStr.toDoubleOrNull() ?: currentPrice
            if (qty >= 1 && price >= 0) onConfirm(qty, price)
        },
        dismissText = "取消",
    )
}

/** 36 三态合辑：单据列表加载骨架（行结构复制单据卡：瓷片+名称/元信息+出码胶囊），呼吸 1.6s（M7）。 */
@Composable
private fun BillCardSkeleton() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(Design.CardRadius.dp)),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(SurfaceSubtle, RoundedCornerShape(12.dp)),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(13.dp)
                    .background(BorderStrong.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(10.dp)
                    .background(SurfaceSubtle, RoundedCornerShape(4.dp)),
            )
        }
        Box(
            modifier = Modifier
                .size(width = 88.dp, height = 24.dp)
                .background(SurfaceSubtle, RoundedCornerShape(8.dp)),
        )
    }
}

/**
 * 顶部 chrome 背景（穿透配套）：实体背景色铺满自身，底边之下再向下渐隐一段同色羽化，
 * 让上滑穿过的单据卡在没入 chrome 之前先「溶入」背景，消除视口边缘的硬切截断线。
 * 羽化默认 12dp，与内容层顶端让位的 12dp 间距正好重合：初始态首卡不被羽化罩到，
 * 滚动时卡片经过羽化带逐渐淡出。
 */
private fun Modifier.chromeBackground(color: Color, feather: Dp = 12.dp): Modifier = drawBehind {
    drawRect(color)
    val featherPx = feather.toPx()
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(color, color.copy(alpha = 0f)),
            startY = size.height,
            endY = size.height + featherPx,
        ),
        topLeft = Offset(x = 0f, y = size.height),
        size = Size(width = size.width, height = featherPx),
    )
}
