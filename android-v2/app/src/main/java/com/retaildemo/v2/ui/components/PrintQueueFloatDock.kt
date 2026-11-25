package com.retaildemo.v2.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.retaildemo.v2.ui.theme.SxElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.retaildemo.v2.data.local.entity.PrintJobEntity
import com.retaildemo.v2.ui.theme.Border
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.PrimaryLight
import com.retaildemo.v2.ui.theme.SurfaceSubtle
import com.retaildemo.v2.ui.window.SanxAlertDialog
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.TextWeak
import com.retaildemo.v2.ui.viewmodel.PrintQueueViewModel

/**
 * 打印队列浮窗入口（2026-09-10 老板令「打印队列做成浮窗样式，随时查看/随时打印/随时更改数量与内容」）：
 * - 只要有待出签任务（pendingCount > 0），屏幕右下角自动浮现「待出签胶囊」；
 * - 点开即弹出半屏浮窗面板，无需离开当前页面（商品详情/查价页均可原地操作）；
 * - 浮窗内直接：加减张数、改品名规格价格条码、打单条、一键全打；出完签自动隐身。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintQueueFloatDock(
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
    vm: PrintQueueViewModel = hiltViewModel(),
) {
    val pendingJobs by vm.pendingJobs.collectAsState()
    val pendingCount by vm.pendingCount.collectAsState()
    val isPrinting by vm.isPrinting.collectAsState()
    val feedback by vm.feedback.collectAsState()
    val context = LocalContext.current

    var showSheet by remember { mutableStateOf(false) }
    var editingJob by remember { mutableStateOf<PrintJobEntity?>(null) }

    val totalCopies = remember(pendingJobs) { pendingJobs.sumOf { it.qty } }

    LaunchedEffect(feedback) {
        feedback?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearFeedback()
        }
    }

    // 悬浮胶囊：仅在有待出签任务时出现
    AnimatedVisibility(
        visible = pendingCount > 0 && !showSheet,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier.padding(end = 16.dp, bottom = bottomInset + 76.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Primary,
            border = BorderStroke(1.5.dp, Color.White),
            modifier = Modifier
                .sxElevation(SxElevation.L4, shape = RoundedCornerShape(24.dp), background = Primary)
                .clickable { showSheet = true },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Print,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "待出签",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(4.dp))
                Surface(
                    shape = CircleShape,
                    color = Color.White,
                    modifier = Modifier.size(20.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = if (pendingCount > 99) "99+" else "$pendingCount",
                            color = Primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                        )
                    }
                }
            }
        }
    }

    // 浮窗面板（U8 Flyme 小窗：把手拖拽 + 贴边 + 点外关闭）
    if (showSheet) {
        com.retaildemo.v2.ui.window.SanxSmallWindow(
            onDismiss = { showSheet = false },
            title = "待出签任务",
            subtitle = "${pendingJobs.size} 款商品 · 共 $totalCopies 张价签",
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
            ) {
                // 待出签列表
                if (pendingJobs.isEmpty()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                    ) {
                        Text("当前没有待出签任务", color = TextWeak, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp),
                    ) {
                        items(pendingJobs, key = { it.id }) { job ->
                            PrintQueueItemCard(
                                job = job,
                                isPrinting = isPrinting,
                                onQtyChange = { delta -> vm.updateQty(job.id, (job.qty + delta).coerceAtLeast(1)) },
                                onEdit = { editingJob = job },
                                onPrintSingle = { vm.printSingle(job) },
                                onDelete = { vm.deleteJob(job.id) },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 底部操作栏：一键全部打印
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "合计 $totalCopies 张",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Danger,
                        )
                        Text(
                            text = "含 ${pendingJobs.size} 款商品",
                            fontSize = 11.sp,
                            color = TextWeak,
                        )
                    }

                    Button(
                        onClick = { vm.printAllPending() },
                        enabled = pendingJobs.isNotEmpty() && !isPrinting,
                        shape = RoundedCornerShape(Design.ButtonRadius.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                        modifier = Modifier.height(44.dp),
                    ) {
                        if (isPrinting) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("出签中…", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.Print, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("全部出签 ($totalCopies)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // 编辑打印内容弹窗（品名/规格/价格/条码/单位/数量随时可改）
    editingJob?.let { job ->
        EditPrintJobDialog(
            job = job,
            onDismiss = { editingJob = null },
            onSave = { name, spec, sellPrice, barcode, unit, qty ->
                vm.updateContent(job.id, name, spec, sellPrice, barcode, unit, qty)
                editingJob = null
            },
        )
    }
}

/** 浮窗与全页共用的任务卡片 */
@Composable
private fun PrintQueueItemCard(
    job: PrintJobEntity,
    isPrinting: Boolean,
    onQtyChange: (Int) -> Unit,
    onEdit: () -> Unit,
    onPrintSingle: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(Design.CardRadius.dp),
        colors = CardDefaults.cardColors(containerColor = Design.CardSurface),
        border = BorderStroke(0.5.dp, Border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().sxElevation(SxElevation.L2),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // 第 1 行：品名 + 价格 + 删除
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = job.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = TextMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (job.sellPrice > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "¥%.2f".format(job.sellPrice),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Danger,
                    )
                }
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "移除",
                        tint = TextWeak,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // 第 2 行：规格 + 条码/编码
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (!job.spec.isNullOrBlank()) {
                    Text(
                        text = job.spec,
                        fontSize = 11.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                val code = job.barcode?.takeIf { it.isNotBlank() } ?: job.sku
                if (code.isNotBlank()) {
                    Text(
                        text = code,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextWeak,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // 第 3 行：加减步进器 + 改内容 + 打本条
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 数量加减步进器（数量随时可改，老板核心诉求）
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = SurfaceSubtle,
                    border = BorderStroke(0.5.dp, Border),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp),
                    ) {
                        IconButton(
                            onClick = { onQtyChange(-1) },
                            enabled = job.qty > 1,
                            modifier = Modifier.size(26.dp),
                        ) {
                            Icon(
                                Icons.Default.Remove,
                                contentDescription = "减少",
                                tint = if (job.qty > 1) TextMain else TextWeak,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        Text(
                            text = "${job.qty} 张",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMain,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        IconButton(
                            onClick = { onQtyChange(1) },
                            modifier = Modifier.size(26.dp),
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "增加",
                                tint = TextMain,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                // 改内容按钮
                OutlinedButton(
                    onClick = onEdit,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(0.5.dp, Primary.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Icon(Icons.Default.Edit, null, tint = Primary, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("改内容", fontSize = 11.sp, color = Primary, fontWeight = FontWeight.Medium)
                }

                Spacer(Modifier.width(6.dp))

                // 打本条按钮
                Button(
                    onClick = onPrintSingle,
                    enabled = !isPrinting,
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryLight),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Text("打本条", fontSize = 11.sp, color = Primary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * 通用编辑打印内容弹窗：品名、规格、价格、条码、单位、张数全字段随时可改
 */
@Composable
fun EditPrintJobDialog(
    job: PrintJobEntity,
    onDismiss: () -> Unit,
    onSave: (name: String, spec: String?, sellPrice: Double, barcode: String?, unit: String?, qty: Int) -> Unit,
) {
    var name by remember(job) { mutableStateOf(job.name) }
    var spec by remember(job) { mutableStateOf(job.spec.orEmpty()) }
    var priceText by remember(job) { mutableStateOf(if (job.sellPrice > 0) "%.2f".format(job.sellPrice) else "") }
    var barcode by remember(job) { mutableStateOf(job.barcode.orEmpty()) }
    var unit by remember(job) { mutableStateOf(job.unit.orEmpty()) }
    var qty by remember(job) { mutableIntStateOf(job.qty.coerceAtLeast(1)) }

    SanxAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "修改打印内容",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextMain,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("商品品名") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        value = priceText,
                        onValueChange = { priceText = it },
                        label = "打印售价 (¥)",
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                    )

                    OutlinedTextField(
                        value = spec,
                        onValueChange = { spec = it },
                        label = { Text("规格型号") },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = barcode,
                        onValueChange = { barcode = it },
                        label = { Text("条码 / 编号") },
                        placeholder = { Text(job.sku) },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                    )

                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it },
                        label = { Text("单位") },
                        placeholder = { Text("个/米/箱") },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(0.6f),
                    )
                }

                // 份数加减
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    Text("打印份数", fontSize = 13.sp, color = TextMain, modifier = Modifier.weight(1f))
                    // 方案 A 去套娃（画布 66:1138 方案注记：份数行去掉描边 Surface，只留标签 + 步进器与 4dp 网格）
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { if (qty > 1) qty-- },
                                enabled = qty > 1,
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(Icons.Default.Remove, null, modifier = Modifier.size(16.dp))
                            }
                            Text(
                                text = "$qty 张",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            IconButton(
                                onClick = { qty++ },
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmText = "保存并应用",
        onConfirm = {
            val p = priceText.toDoubleOrNull() ?: job.sellPrice
            onSave(
                name.trim().ifBlank { job.name },
                spec.trim().ifBlank { null },
                p,
                barcode.trim().ifBlank { null },
                unit.trim().ifBlank { null },
                qty,
            )
        },
        dismissText = "取消",
    )
}
