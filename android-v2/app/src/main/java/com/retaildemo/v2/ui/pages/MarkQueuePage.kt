package com.retaildemo.v2.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.retaildemo.v2.ui.components.sxElevation
import androidx.compose.ui.graphics.Color
import com.retaildemo.v2.R
import com.retaildemo.v2.ui.theme.SxElevation
import com.retaildemo.v2.data.local.entity.DataMarkEntity
import com.retaildemo.v2.data.local.entity.ProductSupplementEntity
import com.retaildemo.v2.ui.components.EmptyState
import com.retaildemo.v2.ui.components.retaildemoTopBar
import com.retaildemo.v2.ui.components.ScanFeedback
import com.retaildemo.v2.ui.theme.Border
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.viewmodel.MarkQueueViewModel

/**
 * I4 标记队列（我的页入口）：open/exported/resolved 三组 + 本地补录清单。
 * 店员打标记 → 专员导出 CSV 照单去管家婆调 → 同步回来人工点「已解决」。
 * 导出用系统分享；状态流转 resolve/reopen 全在行内完成。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkQueuePage(onBack: () -> Unit, viewModel: MarkQueueViewModel = hiltViewModel()) {
    val open by viewModel.openMarks.collectAsState(emptyList())
    val exported by viewModel.exportedMarks.collectAsState(emptyList())
    val resolved by viewModel.resolvedMarks.collectAsState(emptyList())
    val supplements by viewModel.supplements.collectAsState(emptyList())
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = { retaildemoTopBar(title = "数据标记", onBack = onBack) },
    ) { padding ->
        if (open.isEmpty() && exported.isEmpty() && resolved.isEmpty() && supplements.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EmptyState(
                    illustrationRes = R.drawable.ill_empty_stock,
                    title = "暂无数据标记",
                    subtitle = "货架前发现价格/库存不对，在商品详情页「标记问题」顺手标",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                // 稿（画布 10 扫码筐）：5 张卡 y=252..556，卡高 66，节距 76 → **卡距 10**。
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                if (open.isNotEmpty()) {
                    item {
                        GroupHeader(
                            title = "待处理（${open.size}）",
                            action = "导出 CSV",
                            onAction = {
                                ScanFeedback.click(context)
                                viewModel.exportOpen(context, open)
                            },
                        )
                    }
                    items(open, key = { it.id }) { m ->
                        MarkRow(
                            m = m,
                            primary = "已解决" to {
                                ScanFeedback.click(context)
                                viewModel.resolve(m.id)
                            },
                        )
                    }
                }
                if (exported.isNotEmpty()) {
                    item { GroupHeader(title = "已导出待确认（${exported.size}）", action = null, onAction = {}) }
                    items(exported, key = { it.id }) { m ->
                        MarkRow(
                            m = m,
                            primary = "已解决" to {
                                ScanFeedback.click(context)
                                viewModel.resolve(m.id)
                            },
                            secondary = "退回" to {
                                ScanFeedback.click(context)
                                viewModel.reopen(m.id)
                            },
                        )
                    }
                }
                if (resolved.isNotEmpty()) {
                    item { GroupHeader(title = "已解决（${resolved.size}）", action = null, onAction = {}) }
                    items(resolved, key = { it.id }) { m ->
                        MarkRow(
                            m = m,
                            primary = "退回" to {
                                ScanFeedback.click(context)
                                viewModel.reopen(m.id)
                            },
                        )
                    }
                }
                if (supplements.isNotEmpty()) {
                    item {
                        GroupHeader(
                            title = "本地补录（${supplements.size}）",
                            action = "导出补录",
                            onAction = {
                                ScanFeedback.click(context)
                                viewModel.exportSupplements(context)
                            },
                        )
                    }
                    items(supplements, key = { it.sku }) { s -> SupplementRow(s) }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun GroupHeader(title: String, action: String?, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (action != null) {
            OutlinedButton(onClick = onAction) { Text(action, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun MarkRow(
    m: DataMarkEntity,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>? = null,
) {
    Card(
        shape = RoundedCornerShape(Design.CardRadius.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.sxElevation(SxElevation.L2),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    m.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    DataMarkEntity.typeLabel(m.markType),
                    fontSize = 11.sp,
                    color = Primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (m.note.isNotBlank()) {
                Text(m.note, fontSize = 12.sp, color = TextSecondary)
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.sku, fontSize = 11.sp, color = TextSecondary, modifier = Modifier.weight(1f))
                Text(
                    "by ${m.createdBy.ifBlank { "店员" }} · ${m.createdAt.take(16)}",
                    fontSize = 11.sp,
                    color = TextSecondary,
                )
                secondary?.let { (label, fn) -> TextButton(onClick = fn) { Text(label, fontSize = 12.sp) } }
                TextButton(onClick = primary.second) { Text(primary.first, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun SupplementRow(s: ProductSupplementEntity) {
    Card(
        shape = RoundedCornerShape(Design.CardRadius.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.sxElevation(SxElevation.L2),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.name.ifBlank { s.sku },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text("本地补", fontSize = 11.sp, color = Primary, fontWeight = FontWeight.Bold)
            }
            val parts = listOfNotNull(
                s.brand?.takeIf { it.isNotBlank() }?.let { "品牌 $it" },
                s.spec?.takeIf { it.isNotBlank() }?.let { "规格 $it" },
                s.note?.takeIf { it.isNotBlank() },
                s.pendingBarcode?.takeIf { it.isNotBlank() }?.let { "待绑码 $it" },
            )
            if (parts.isNotEmpty()) {
                Text(parts.joinToString(" · "), fontSize = 12.sp, color = TextSecondary)
            }
            Text(
                "by ${s.updatedBy.ifBlank { "店员" }} · ${s.updatedAt.take(16)}",
                fontSize = 11.sp,
                color = TextSecondary,
            )
        }
    }
}
