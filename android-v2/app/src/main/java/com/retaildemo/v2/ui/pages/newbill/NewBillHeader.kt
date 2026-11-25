package com.retaildemo.v2.ui.pages.newbill

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retaildemo.v2.data.local.entity.CustomerEntity
import com.retaildemo.v2.ui.theme.Border
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.PrimaryLight
import com.retaildemo.v2.ui.theme.SurfaceSubtle
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.TextWeak
import com.retaildemo.v2.ui.theme.Warning
import com.retaildemo.v2.ui.theme.WarningLight
import com.retaildemo.v2.ui.viewmodel.DraftInfo
import com.retaildemo.v2.ui.window.SanxAlertDialog
import com.retaildemo.v2.util.CustomerDirectory

/**
 * 记一笔页头族（TASK-02 巨石解耦：从 NewBillPage 平移，几何与文案零改动）。
 * 页头 = 返回 + 标题 + 客户胶囊（快捷动作入口）；另含连扫条、草稿横幅、搜索/扫码条、选客户弹窗。
 */
@Composable
fun NewBillTopBar(
    customerName: String,
    onBack: () -> Unit,
    onPickCustomer: () -> Unit,
) {
    Column(
        modifier = Modifier
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextMain)
            }
            Column {
                // 02 稿 3:4 顶栏：标题 18 Bold「新建单据」+ 动态时间副标（单据 · HH:mm）
                Text("新建单据", fontSize = Design.FontXl.sp, fontWeight = FontWeight.Bold, color = TextMain)
                val timeStr = remember {
                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                }
                Text("开单台 · $timeStr", fontSize = Design.FontXs.sp, color = TextSecondary)
            }
            Spacer(Modifier.weight(1f))
            // 02 稿：客户胶囊在顶栏（点开客户选择）
            Surface(
                shape = RoundedCornerShape(Design.PillRadius.dp),
                color = PrimaryLight,
                modifier = Modifier.clickable(onClick = onPickCustomer),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 12.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
                ) {
                    Text(
                        text = customerName,
                        fontSize = Design.FontMdPlus2.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp),
                    )
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = "选择客户",
                        tint = Primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // 2026-09-19 老板令「以设计稿为准」：移除稿外「结算属性胶囊行」（收款/现金/POS/备注/需开票）。
        // 设计 PNG y101..120dp 为纯留白（Gate 0 · 22_newbill_chips_removal.md）；
        // 收款方式/备注/开票能力保留在账单详情「更多操作」（BillRepo.updatePayMethodAndNote），不丢功能。
    }
}

/**
 * 02 稿连扫条（画布 3:4 / 02_newbill.png）：浅橙底 + 连扫指引 + 右侧件数。
 * 点它 = 直接回到连扫会话（不用先退出再进）。
 */
@Composable
fun NewBillScanBanner(
    totalQty: Int,
    onScan: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Design.CardRadius.dp))
            .background(PrimaryLight)
            .clickable(onClick = onScan)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Primary),
        )
        Spacer(Modifier.width(9.dp))
        Column {
            Text(
                "连扫中，扫一个进一个",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primary,
            )
            Text(
                "不用退出，扫完直接存单",
                fontSize = 11.sp,
                color = Primary.copy(alpha = 0.85f),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            "$totalQty 件",
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Primary,
        )
    }
}

/** 草稿恢复横幅（上次退出自动暂存的单，进来先问要不要接着开）。 */
@Composable
fun NewBillDraftBanner(
    draft: DraftInfo,
    onRestore: () -> Unit,
    onDiscard: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = WarningLight),
        modifier = Modifier.drawBehind {
            drawRoundRect(
                color = Warning.copy(alpha = 0.4f),
                cornerRadius = CornerRadius(14.dp.toPx()),
                style = Stroke(width = 1.dp.toPx()),
            )
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("未完成的草稿单", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextMain)
                Text(
                    "${draft.customerName} · ${draft.itemCount}件 · 暂存",
                    fontSize = 11.sp,
                    color = TextSecondary,
                )
            }
            TextButton(onClick = onRestore) {
                Text("恢复", color = Primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            TextButton(onClick = onDiscard) {
                Text("放弃", color = TextWeak, fontSize = 12.sp)
            }
        }
    }
}

/** 搜索与扫码录单条（02 稿 3:4）：橙描边搜索框 + 识别手写码入口 + 连续扫码方块。 */
@Composable
fun NewBillSearchScanRow(
    query: String,
    onQueryChange: (String) -> Unit,
    onScan: () -> Unit,
    onTextScan: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 6.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("搜品名 / 规格 / 拼音缩写 / 条码", fontSize = 13.sp, color = TextWeak) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = Primary) },
            trailingIcon = {
                IconButton(onClick = onTextScan) {
                    Icon(Icons.Default.Edit, contentDescription = "识别手写码", tint = Primary, modifier = Modifier.size(18.dp))
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Primary,
                unfocusedBorderColor = Border,
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                cursorColor = Primary,
            ),
        )
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Primary,
            modifier = Modifier
                .size(46.dp)
                .clickable(onClick = onScan),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = "连续扫码", tint = Color.White, modifier = Modifier.size(21.dp))
            }
        }
    }
}

/**
 * S19 选客户弹窗（从 NewBillPage 平移）：搜索姓名/拼音缩写/电话 + 最近记账快捷 + 散客置顶 +
 * 搜不到就地新建。
 *
 * ⚠️ 2026-09-22 TASK-02 唯一改动：删掉客户列表自己那层 `Modifier.verticalScroll`。
 * [SanxAlertDialog] 的 text 槽**本身就是一个滚动容器**（SanxWindow.kt 里
 * `Box().weight(1f, fill=false).verticalScroll(...)`），列表再套一层就是 rule 14②
 * 「禁止在弹窗或未受限容器内二次挂载 verticalScroll」——内层拿到无限高度、
 * 两层手势互抢。现在整窗只滚一次（稿口径不变）。
 */
@Composable
fun NewBillCustomerPickerDialog(
    customers: List<CustomerEntity>,
    recentNames: List<String>,
    onPick: (String) -> Unit,
    onCreateAndPick: (String) -> Unit,
    onLoadRecent: () -> Unit,
    onDismiss: () -> Unit,
) {
    var keyword by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { onLoadRecent() }
    val directory = remember(customers, keyword) {
        CustomerDirectory.sort(CustomerDirectory.filter(customers, keyword))
            .filter { it.name != "散客" }
    }
    val exactExists = remember(customers, keyword) {
        val k = keyword.trim()
        k.isNotEmpty() && customers.any { it.name.equals(k, ignoreCase = true) }
    }
    fun choose(name: String) {
        onPick(name)
        onDismiss()
    }
    SanxAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择开单客户", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    placeholder = { Text("搜姓名 / 拼音 / 电话") },
                    singleLine = true,
                )
                Text(
                    "散客（现结）",
                    fontWeight = FontWeight.Medium,
                    color = Primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { choose("散客") }
                        .padding(vertical = 8.dp),
                )
                if (keyword.isBlank() && recentNames.isNotEmpty()) {
                    Text("最近记账", fontSize = 11.sp, color = TextWeak)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        recentNames.forEach { name ->
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = PrimaryLight,
                                modifier = Modifier.clickable { choose(name) },
                            ) {
                                Text(
                                    name,
                                    fontSize = 12.sp,
                                    color = Primary,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (keyword.isNotBlank() && directory.isEmpty()) {
                        Text("没有匹配「${keyword.trim()}」的客户", fontSize = 12.sp, color = TextWeak)
                    }
                    directory.forEach { c ->
                        CustomerPickRow(c) { choose(c.name) }
                    }
                    // 搜不到精确同名 → 就地新建（原「输入新客户名称→保存并选用」收编进同一搜索框）
                    if (keyword.trim().isNotEmpty() && !exactExists) {
                        Text(
                            "＋ 新建客户「${keyword.trim()}」并选用",
                            color = Primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onCreateAndPick(keyword)
                                    onDismiss()
                                }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            }
        },
        dismissText = "取消",
    )
}

/** S19：选客户弹窗结果行——名称 + 临时/类别标 + 电话·联系人副行。 */
@Composable
private fun CustomerPickRow(c: CustomerEntity, onPick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = TextMain,
                )
                if (c.pendingSync) {
                    Spacer(Modifier.width(5.dp))
                    Surface(color = WarningLight, shape = RoundedCornerShape(4.dp)) {
                        Text(
                            "临时",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Warning,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
                c.category?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.width(5.dp))
                    Surface(color = SurfaceSubtle, shape = RoundedCornerShape(4.dp)) {
                        Text(
                            it,
                            fontSize = 10.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            val sub = listOfNotNull(
                c.phone.takeIf { it.isNotBlank() },
                c.contacts?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Spacer(Modifier.height(1.dp))
                Text(sub, fontSize = 11.sp, color = TextWeak, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
