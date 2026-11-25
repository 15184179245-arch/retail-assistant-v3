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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.retaildemo.v2.ui.components.sxElevation
import com.retaildemo.v2.R
import com.retaildemo.v2.ui.theme.SxElevation
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.retaildemo.v2.ui.components.EmptyState
import com.retaildemo.v2.ui.components.NumberField
import com.retaildemo.v2.ui.components.NumberPadInput
import com.retaildemo.v2.ui.components.PriceText
import com.retaildemo.v2.ui.components.retaildemoTopBar
import com.retaildemo.v2.ui.components.TopBarStyle
import com.retaildemo.v2.ui.theme.Border
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.PrimaryLight
import com.retaildemo.v2.ui.theme.SurfaceSubtle
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.TextWeak
import com.retaildemo.v2.ui.theme.Warning
import com.retaildemo.v2.ui.theme.WarningLight
import com.retaildemo.v2.ui.viewmodel.CustomersViewModel
import com.retaildemo.v2.ui.window.SanxAlertDialog

/** 客户列表：新建 + 搜索 + 欠款合计一览。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomersPage(onBack: () -> Unit, viewModel: CustomersViewModel = hiltViewModel()) {
    val customers by viewModel.customers.collectAsState(initial = emptyList())
    val debts by viewModel.debtByCustomer.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    // 稿 3:1803：排序 chips 取代搜索框（按欠款/按名称/月结3）
    var sortKey by remember { mutableStateOf(CustomerSort.BY_DEBT) }
    val shown = remember(customers, sortKey) {
        when (sortKey) {
            CustomerSort.BY_DEBT -> customers.sortedByDescending { debts[it.name] ?: 0.0 }
            CustomerSort.BY_NAME -> com.retaildemo.v2.util.CustomerDirectory.sort(customers)
            CustomerSort.BY_TERM -> customers.sortedBy { it.creditLimit } // 临时用额度代理「月结」
        }
    }
    val totalDebt = remember(debts) { debts.values.sum() }

    Scaffold(
        topBar = {
            retaildemoTopBar(
                title = "客户与欠款",
                subtitle = "欠款合计 ¥%,.2f · %d 位客户".format(totalDebt, customers.size),
                onBack = onBack,
                actions = {
                    // 稿 3:1803 顶栏右上「对账」CTA（浅底胶囊）
                    Surface(
                        color = WarningLight,
                        shape = RoundedCornerShape(Design.PillRadius.dp),
                        modifier = Modifier
                            .height(36.dp)
                            .clickable { /* TODO: 进入对账单分享 */ },
                    ) {
                        Text(
                            "对账",
                            color = Warning,
                            fontSize = Design.FontSm.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = Design.SpaceMd.dp),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAdd = true },
                containerColor = Primary,
                modifier = Modifier.navigationBarsPadding(),
            ) {
                Icon(Icons.Default.Add, "新建客户", tint = Color.White)
            }
        },
    ) { padding ->
        if (customers.isEmpty()) {
            Column(Modifier.padding(padding)) {
                EmptyState(
                    illustrationRes = R.drawable.ill_empty_debt,
                    title = "还没有客户",
                    subtitle = "点右下角新建客户；记一笔时选择客户即可挂账追溯",
                )
            }
        } else {
            LazyColumn(
                // 稿（画布 19，帧 [481,7544]）：6 张卡 y=163/242/…/558，卡高 69，
                // 节距 79 → **卡距 10**。与 01 记账（节距 128/154 − 卡高 118/144 = 10）同口径。
                // 原写 8.dp 是漏项，非有意（同屏其它间距 8 是「组内间距」，列表卡距另有基准 10）。
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            ) {
                item {
                    // 稿：chips 行 y=119，顶部无额外留白（appBar 底即 103）。
                    // 原来两端各塞 8.dp 会把 chips 推低 16dp 且把首卡推低 26dp。
                    CustomerSortChips(
                        selected = sortKey,
                        onSelect = { sortKey = it },
                    )
                    Spacer(Modifier.height(16.dp))
                }
                items(shown, key = { it.id }) { c ->
                    val debt = debts[c.name] ?: 0.0
                    CustomerDebtCard(c = c, debt = debt)
                }
                item { Spacer(Modifier.height(80.dp).navigationBarsPadding()) }
            }
        }
    }

    if (showAdd) {
        AddCustomerDialog(
            onDismiss = { showAdd = false },
            onConfirm = { name, phone, limit ->
                viewModel.addCustomer(name, phone, limit)
                showAdd = false
            },
        )
    }

    // 打开详情
    val openId by viewModel.openCustomerId.collectAsState()
    openId?.let {
        CustomerDetailDialog(
            viewModel = viewModel,
            onClose = { viewModel.open(null) },
        )
    }
}

private enum class CustomerSort { BY_DEBT, BY_NAME, BY_TERM }

/** 稿 3:1803 排序 chips：按欠款 / 按名称 / 月结 3 */
@Composable
private fun CustomerSortChips(
    selected: CustomerSort,
    onSelect: (CustomerSort) -> Unit,
) {
    val items = listOf(
        CustomerSort.BY_DEBT to "按欠款",
        CustomerSort.BY_NAME to "按名称",
        CustomerSort.BY_TERM to "月结 3",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (s, label) ->
            val isSel = s == selected
            Surface(
                color = if (isSel) PrimaryLight else SurfaceSubtle,
                shape = RoundedCornerShape(Design.PillRadius.dp),
                modifier = Modifier.clickable { onSelect(s) },
            ) {
                Text(
                    label,
                    fontSize = Design.FontSm.sp,
                    lineHeight = 18.sp,
                    color = if (isSel) Primary else TextSecondary,
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** 稿 3:1803 客户卡片：letter avatar + 「X 天前拿货 · 月结 N 天」+ 大字红价 + 「明细」action */
@Composable
private fun CustomerDebtCard(
    c: com.retaildemo.v2.data.local.entity.CustomerEntity,
    debt: Double,
) {
    val letter = c.name.firstOrNull()?.toString() ?: "?"
    // 简易推断：建档时长 → "X 天前"；信用额度 > 0 → "月结 30 天" 否则 "现结"
    val daysOld = runCatching {
        val created = java.time.LocalDate.parse(c.createdAt.take(10))
        java.time.temporal.ChronoUnit.DAYS.between(created, java.time.LocalDate.now()).toInt()
    }.getOrDefault(0).coerceAtLeast(0)
    val termLabel = when {
        c.creditLimit > 5000 -> "月结 30 天"
        c.creditLimit > 0 -> "月结 15 天"
        else -> "现结"
    }
    Card(
        shape = RoundedCornerShape(Design.CardRadius.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .sxElevation(SxElevation.L2)
            .clickable { /* TODO: 打开明细 */ },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp),
        ) {
            // 稿：左侧 letter avatar（姓氏首字 + 暖色填充）
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = PrimaryLight,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        letter,
                        fontSize = Design.FontMdPlus.sp,
                        lineHeight = 22.sp,
                        color = Primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    c.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = Design.FontMd.sp,
                    lineHeight = 22.sp,
                    color = TextMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${daysOld} 天前拿货 · $termLabel",
                    fontSize = Design.FontXs.sp,
                    lineHeight = 15.sp,
                    color = TextSecondary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (debt > 0) {
                    PriceText(
                        price = debt,
                        integerSize = 18.sp,
                        decimalSize = 13.sp,
                        symbolSize = 13.sp,
                        color = Danger,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Text(
                        "¥0",
                        fontSize = Design.FontMd.sp,
                        lineHeight = 22.sp,
                        color = TextWeak,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "明细",
                    fontSize = Design.FontSm.sp,
                    lineHeight = 18.sp,
                    color = Primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun AddCustomerDialog(onDismiss: () -> Unit, onConfirm: (String, String, Double) -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf("") }
    SanxAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建客户", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("客户名") }, singleLine = true,
                    shape = RoundedCornerShape(Design.ButtonRadius.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                NumberField(
                    value = phone, onValueChange = { phone = it },
                    label = "电话（可选）", decimals = false,
                    // 手机号 11 位 > 金额的 9 位默认上限，不放开这里根本填不完
                    maxIntDigits = NumberPadInput.MAX_PHONE_DIGITS,
                    shape = RoundedCornerShape(Design.ButtonRadius.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                NumberField(
                    value = limit, onValueChange = { limit = it },
                    label = "信用额度（0 = 不限）",
                    shape = RoundedCornerShape(Design.ButtonRadius.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmText = "保存",
        confirmEnabled = name.isNotBlank(),
        onConfirm = { onConfirm(name.trim(), phone.trim(), limit.toDoubleOrNull() ?: 0.0) },
        dismissText = "取消",
    )
}

/** 客户详情弹窗：历史账单 + 欠款 + 对账单分享 + 信用提醒。 */
@Composable
private fun CustomerDetailDialog(
    viewModel: CustomersViewModel,
    onClose: () -> Unit,
) {
    val detail by viewModel.currentDetail.collectAsState()
    val context = LocalContext.current
    var promoteMode by remember { mutableStateOf(false) }
    var formalName by remember { mutableStateOf("") }
    val isTemp = detail?.customer?.pendingSync == true
    SanxAlertDialog(
        onDismissRequest = onClose,
        title = { Text(detail?.customer?.name ?: "客户", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val debt = detail?.debt ?: 0.0
                val limit = detail?.customer?.creditLimit ?: 0.0
                if (isTemp) {
                    Surface(
                        color = WarningLight,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            "临时客户（未在管家婆建档）",
                            fontSize = 11.sp,
                            color = Warning,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("挂账欠款", color = TextSecondary, modifier = Modifier.weight(1f))
                    PriceText(
                        price = debt,
                        integerSize = 18.sp,
                        decimalSize = 13.sp,
                        symbolSize = 12.sp,
                        color = Danger,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (limit > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("信用额度", color = TextSecondary, modifier = Modifier.weight(1f))
                        Text("¥%.2f".format(limit), color = TextMain, fontWeight = FontWeight.Medium)
                        if (debt > limit) {
                            Spacer(Modifier.width(6.dp))
                            Text("已超限！", color = Danger, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
                if (isTemp) {
                    if (promoteMode) {
                        OutlinedTextField(
                            value = formalName, onValueChange = { formalName = it },
                            label = { Text("管家婆正式客户名") }, singleLine = true,
                            shape = RoundedCornerShape(Design.ButtonRadius.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "确认后将以此名替换临时客户，历史账单归属一并切换。",
                            fontSize = 11.sp,
                            color = TextSecondary,
                        )
                        Button(
                            shape = RoundedCornerShape(Design.ButtonRadius.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary),
                            onClick = {
                                val id = detail?.customer?.id
                                if (id != null && formalName.isNotBlank()) {
                                    viewModel.promoteCustomer(id, formalName)
                                    onClose()
                                }
                            },
                        ) { Text("确认转正") }
                    } else {
                        TextButton(onClick = { formalName = detail?.customer?.name ?: ""; promoteMode = true }) {
                            Text("关联档案（管家婆建档后回填正式名）", color = Primary)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(color = Border)
                Spacer(Modifier.height(4.dp))
                Text("历史账单 (${detail?.bills?.size ?: 0})", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = TextMain)
                detail?.bills?.take(8)?.forEach { bill ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp),
                    ) {
                        Text(
                            "${bill.createdAt.take(10)} ${if (bill.status == "pending") "挂账" else "已出库"}",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        Text("¥%.2f".format(bill.totalAmount), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextMain)
                    }
                }
            }
        },
        confirmText = "分享对账单",
        onConfirm = {
            val text = viewModel.buildStatement()
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, text)
            }
            context.startActivity(android.content.Intent.createChooser(intent, "分享对账单"))
        },
        dismissText = "关闭",
    )
}
