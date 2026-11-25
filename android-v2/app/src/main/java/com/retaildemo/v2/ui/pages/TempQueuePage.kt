package com.retaildemo.v2.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.retaildemo.v2.ui.components.sxElevation
import com.retaildemo.v2.ui.theme.SxElevation
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.retaildemo.v2.R
import com.retaildemo.v2.data.local.entity.TempProductEntity
import com.retaildemo.v2.ui.components.EmptyState
import com.retaildemo.v2.ui.components.retaildemoTopBar
import com.retaildemo.v2.ui.components.ScanFeedback
import com.retaildemo.v2.ui.theme.Border
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.Warning
import com.retaildemo.v2.ui.window.SanxAlertDialog
import com.retaildemo.v2.ui.viewmodel.TempQueueViewModel

/**
 * B3 待补全队列：店里无码记账的临时商品清单（type=custom）。
 * 老板/店员对着这份清单在管家婆正式建档，拿到真实编码后删除对应临时记录即可（闭环回写在 B4 之后按需求再做）。
 * 串货不在此列（reimburse 走 B4 台账）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TempQueuePage(onBack: () -> Unit, viewModel: TempQueueViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsState(emptyList())
    var pendingResolve by remember { mutableStateOf<TempProductEntity?>(null) }
    val context = LocalContext.current
    Scaffold(
        topBar = { retaildemoTopBar(title = "待补全队列", onBack = onBack) },
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EmptyState(
                    illustrationRes = R.drawable.ill_empty_bill,
                    title = "暂无待补全商品",
                    subtitle = "无码货记账后会出现在这里，建档拿到正式编码后可删除",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        "共 ${items.size} 条 · 在管家婆建档后回正式码，再删除本条",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                items(items, key = { it.tempCode }) { tp ->
                    TempQueueRow(tp, onResolve = {
                        ScanFeedback.click(context)
                        pendingResolve = tp
                    })
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    pendingResolve?.let { tp ->
        SanxAlertDialog(
            onDismissRequest = { pendingResolve = null },
            title = { Text("确认销账？", style = MaterialTheme.typography.titleSmall) },
            text = {
                Text(
                    "「${tp.name}」在管家婆建好正式档案了？\n" +
                        "将删除临时记录 ${tp.tempCode}（已开的账单历史不受影响，仍保留该临时码可追溯）。",
                    fontSize = 13.sp,
                )
            },
            confirmText = "已建档，删除",
            confirmDanger = true,
            onConfirm = {
                ScanFeedback.click(context)
                viewModel.resolve(tp.tempCode)
                pendingResolve = null
            },
            dismissText = "先留着",
            onDismiss = {
                ScanFeedback.click(context)
                pendingResolve = null
            },
        )
    }
}

@Composable
private fun TempQueueRow(tp: TempProductEntity, onResolve: () -> Unit) {
    Card(
        shape = RoundedCornerShape(Design.CardRadius.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.sxElevation(SxElevation.L2),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tp.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "¥%.2f".format(tp.sellPrice),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tp.tempCode,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Warning,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    tp.createdAt,
                    fontSize = 11.sp,
                    color = TextSecondary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "by ${tp.createdBy.ifBlank { "店员" }}",
                    fontSize = 11.sp,
                    color = Primary,
                )
                TextButton(onClick = onResolve) { Text("已建档", fontSize = 12.sp) }
            }
            if (!tp.spec.isNullOrBlank()) {
                Text(tp.spec, fontSize = 11.sp, color = TextSecondary)
            }
        }
    }
}
