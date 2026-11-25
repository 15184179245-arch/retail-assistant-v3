package com.retaildemo.v2.ui.pages

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.retaildemo.v2.data.local.entity.TempProductEntity
import com.retaildemo.v2.data.repo.ExportUtils
import com.retaildemo.v2.R
import com.retaildemo.v2.data.repo.NetworkMonitor
import com.retaildemo.v2.ui.components.EmptyState
import com.retaildemo.v2.ui.components.retaildemoTopBar
import com.retaildemo.v2.ui.components.ScanFeedback
import com.retaildemo.v2.ui.theme.SurfaceSubtle
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.viewmodel.StradeLedgerViewModel
import com.retaildemo.v2.ui.window.SanxAlertDialog

private val TABS = listOf("pending" to "待报销", "done" to "待入库")

/** B4 串货台账：待报销（核销）/ 待入库（回写正式码）+ CSV 导出。红线：只导出，不写管家婆。 */
@Composable
fun StradeLedgerPage(onBack: () -> Unit, viewModel: StradeLedgerViewModel = hiltViewModel()) {
    val tab = viewModel.tab
    val rows = viewModel.rows
    val busy = viewModel.busy
    val msg = viewModel.msg
    val context = LocalContext.current
    var bindTarget by remember { mutableStateOf<TempProductEntity?>(null) }

    // B4 修复：进页必须拉一次数据——此前 refresh 只在点 tab 时触发，首次进入恒为空列表
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = { retaildemoTopBar(title = "串货台账", onBack = onBack) },
    ) { padding ->
        // 36 三态合辑 · 离线条：串货台账依赖网络导出与回写，断网就地提示
        val isOnline by viewModel.isOnline.collectAsState()
        if (!isOnline) {
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
                    text = "离线 · 串货数据照常可查，联网后可导出回写",
                    fontSize = 12.sp,
                    color = TextSecondary,
                )
            }
        }
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = TABS.indexOfFirst { it.first == tab }) {
                TABS.forEach { (key, label) ->
                    Tab(
                        selected = tab == key,
                        onClick = {
                            ScanFeedback.click(context)
                            viewModel.switchTab(key)
                        },
                        text = { Text(label, fontSize = 14.sp) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (tab == "pending") "共 ${rows.size} 条 · 垫钱已报销的点「核销」"
                    else "共 ${rows.size} 条 · 管家婆建档后「回写正式码」",
                    fontSize = 12.sp,
                    color = TextSecondary,
                )
                TextButton(
                    enabled = rows.isNotEmpty() && !busy,
                    onClick = {
                        ScanFeedback.click(context)
                        val csv = ExportUtils.toCsv(
                            headers = listOf("商家", "品名", "规格", "串货价", "售价", "凭证图", "垫钱人", "日期"),
                            rows = rows.map {
                                listOf(
                                    it.sourceVendor ?: "", it.name, it.spec ?: "",
                                    it.stradePrice?.toString() ?: "", it.sellPrice.toString(),
                                    it.voucherPhoto ?: "", it.createdBy, it.createdAt,
                                )
                            },
                        )
                        ExportUtils.writeCsvCache(context, "strade_ledger_${tab}.csv", csv)
                        android.widget.Toast.makeText(context, "CSV 已导出到应用缓存，可从文件管理分享", android.widget.Toast.LENGTH_LONG).show()
                    },
                ) { Text("导出 CSV", fontSize = 13.sp) }
            }
            if (rows.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    EmptyState(
                        illustrationRes = R.drawable.ill_empty_debt,
                        title = if (tab == "pending") "暂无待报销串货" else "暂无待入库串货",
                        subtitle = if (tab == "pending") "记串货账后出现在这里"
                        else "核销后在这里回写正式码（管家婆建档完成）",
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rows, key = { it.tempCode }) { tp ->
                        StradeRowCard(
                            tp = tp,
                            tab = tab,
                            onWriteOff = {
                                ScanFeedback.click(context)
                                viewModel.writeOff(tp.tempCode)
                            },
                            onBind = {
                                ScanFeedback.click(context)
                                bindTarget = tp
                            },
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    msg?.let { m ->
        SanxAlertDialog(
            onDismissRequest = { viewModel.clearMsg() },
            title = { Text("提示", style = MaterialTheme.typography.titleSmall) },
            text = { Text(m, fontSize = 13.sp) },
            confirmText = "好",
            onConfirm = { viewModel.clearMsg() },
        )
    }

    bindTarget?.let { tp ->
        var codeInput by remember(tp) { mutableStateOf("") }
        SanxAlertDialog(
            onDismissRequest = { bindTarget = null },
            title = { Text("回写正式码", style = MaterialTheme.typography.titleSmall) },
            text = {
                Column {
                    Text(
                        "「${tp.name}」已在管家婆建档？输入正式编码（条码或编号），" +
                            "历史账单将显示正式码可追溯。",
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = codeInput,
                        onValueChange = { codeInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("正式码（可扫可输）", fontSize = 13.sp) },
                        singleLine = true,
                    )
                }
            },
            confirmText = "回写",
            confirmEnabled = codeInput.isNotBlank() && !busy,
            onConfirm = {
                ScanFeedback.click(context)
                viewModel.bindOfficialCode(tp.tempCode, codeInput)
                bindTarget = null
            },
            dismissText = "取消",
        )
    }
}

@Composable
private fun StradeRowCard(
    tp: TempProductEntity,
    tab: String,
    onWriteOff: () -> Unit,
    onBind: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(tp.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append("临时码 ${tp.tempCode}")
                    tp.sourceVendor?.let { append(" · 在${it}串") }
                    tp.stradePrice?.let { append(" · 垫 ${it}元") }
                    append(" · 售 ${tp.sellPrice}元")
                    tp.createdBy?.let { append(" · 垫钱人 ${it}") }
                },
                fontSize = 12.sp,
                color = TextSecondary,
            )
            tp.voucherPhoto?.let {
                Spacer(Modifier.height(2.dp))
                Text("凭证：$it", fontSize = 11.sp, color = TextSecondary, textAlign = TextAlign.Start)
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (tab == "pending") {
                    TextButton(onClick = onWriteOff) { Text("核销（已报销）", fontSize = 13.sp) }
                } else {
                    TextButton(onClick = onBind) { Text("回写正式码", fontSize = 13.sp) }
                }
            }
        }
    }
}
