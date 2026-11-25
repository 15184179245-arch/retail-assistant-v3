package com.retaildemo.v2.ui.pages

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.retaildemo.v2.ui.components.sxElevation
import com.retaildemo.v2.R
import com.retaildemo.v2.ui.theme.SxElevation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.retaildemo.v2.data.local.entity.PrintJobEntity
import com.retaildemo.v2.ui.components.EditPrintJobDialog
import com.retaildemo.v2.ui.components.EmptyState
import com.retaildemo.v2.ui.components.retaildemoTopBar
import com.retaildemo.v2.ui.components.ScanFeedback
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
import com.retaildemo.v2.ui.theme.TextWeak
import com.retaildemo.v2.ui.viewmodel.PrintQueueViewModel

/**
 * 打印任务队列（S5 打印场景矩阵 + 打印队列）。
 * 管理本地 Room 持久化待出签队列，支持 6 大业务场景集中出签、单项重试与异常排查。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintQueuePage(
    onBack: () -> Unit,
    viewModel: PrintQueueViewModel = hiltViewModel(),
) {
    val rawJobs by viewModel.rawJobs.collectAsState()
    val filteredJobs by viewModel.filteredJobs.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val isPrinting by viewModel.isPrinting.collectAsState()
    val feedback by viewModel.feedback.collectAsState()
    val context = LocalContext.current

    val doneCount = rawJobs.count { it.status == PrintJobEntity.STATUS_DONE }
    val failedCount = rawJobs.count { it.status == PrintJobEntity.STATUS_FAILED }
    var editingJob by remember { mutableStateOf<PrintJobEntity?>(null) }

    LaunchedEffect(feedback) {
        feedback?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearFeedback()
        }
    }

    Scaffold(
        topBar = {
            retaildemoTopBar(
                title = "打印任务队列",
                subtitle = "本地出签队列 · 自动去重合并",
                onBack = onBack,
                actions = {
                    if (doneCount > 0) {
                        TextButton(onClick = {
                            ScanFeedback.click(context)
                            viewModel.clearDone()
                        }) {
                            // U2 浅色顶栏后白字隐身，改品牌色（与打印机管理页「打印队列」同批修复）
                            Text("清空已完成", color = Primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
            )
        },
        bottomBar = {
                Surface(
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .sxElevation(SxElevation.L4, docked = true),
                ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            ScanFeedback.click(context)
                            viewModel.printAllPending()
                        },
                        enabled = pendingCount > 0 && !isPrinting,
                        shape = RoundedCornerShape(Design.ButtonRadius.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(Design.MinTouchTarget.dp),
                    ) {
                        if (isPrinting) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("正在逐张出签...", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_print_label),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (pendingCount > 0) "全部打印（待出签 $pendingCount 张）" else "暂无待出签任务",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        },
        containerColor = Design.PageBg,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))

            // 36 三态合辑 · 离线条：网络标签机断网暂不可达，任务保留本地队列
            val isOnline by viewModel.isOnline.collectAsState()
            if (!isOnline) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
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
                        text = "离线 · 网络标签机暂不可达，任务已排队",
                        fontSize = Design.FontSm.sp,
                        color = TextSecondary,
                    )
                }
            }

            // 队列状态统计筛选 Pills
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                QueueFilterPill(
                    label = "全部",
                    count = rawJobs.size,
                    color = TextMain,
                    isSelected = filter == "all",
                    onClick = {
                        ScanFeedback.click(context)
                        viewModel.setFilter("all")
                    },
                    modifier = Modifier.weight(1f),
                )
                QueueFilterPill(
                    label = "待出签",
                    count = pendingCount,
                    color = Primary,
                    isSelected = filter == "pending",
                    onClick = {
                        ScanFeedback.click(context)
                        viewModel.setFilter("pending")
                    },
                    modifier = Modifier.weight(1f),
                )
                QueueFilterPill(
                    label = "已出签",
                    count = doneCount,
                    color = Success,
                    isSelected = filter == "done",
                    onClick = {
                        ScanFeedback.click(context)
                        viewModel.setFilter("done")
                    },
                    modifier = Modifier.weight(1f),
                )
                QueueFilterPill(
                    label = "失败",
                    count = failedCount,
                    color = Danger,
                    isSelected = filter == "failed",
                    onClick = {
                        ScanFeedback.click(context)
                        viewModel.setFilter("failed")
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(12.dp))

            if (filteredJobs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        illustrationRes = R.drawable.ill_empty_bill,
                        title = when (filter) {
                            "pending" -> "暂无待打印任务"
                            "done" -> "暂无已完成任务"
                            "failed" -> "没有失败任务"
                            else -> "打印队列为空"
                        },
                        subtitle = "在商品详情、查价多选、货位理货、扫码筐或盘点时，均可一键将价签/货位标签加入此队列集中出签",
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    items(filteredJobs, key = { it.id }) { job ->
                        PrintJobCard(
                            job = job,
                            isPrinting = isPrinting,
                            onQtyChange = { delta -> viewModel.updateQty(job.id, (job.qty + delta).coerceAtLeast(1)) },
                            onEdit = { editingJob = job },
                            onPrintSingle = { viewModel.printSingle(job) },
                            onRetry = {
                                ScanFeedback.click(context)
                                viewModel.retryJob(job.id)
                            },
                            onDelete = {
                                ScanFeedback.click(context)
                                viewModel.deleteJob(job.id)
                            },
                        )
                    }
                }
            }
        }
    }

    // 编辑打印内容弹窗（品名/规格/价格/条码/单位/份数全字段随时可改）
    editingJob?.let { job ->
        EditPrintJobDialog(
            job = job,
            onDismiss = { editingJob = null },
            onSave = { name, spec, sellPrice, barcode, unit, qty ->
                viewModel.updateContent(job.id, name, spec, sellPrice, barcode, unit, qty)
                editingJob = null
            },
        )
    }
}

@Composable
private fun QueueFilterPill(
    label: String,
    count: Int,
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(Design.CardRadius.dp),
        color = if (isSelected) color.copy(alpha = 0.12f) else Design.CardSurface,
        border = BorderStroke(if (isSelected) 1.5.dp else 0.5.dp, if (isSelected) color else Border),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Text(text = "$count", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = color)
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) color else TextSecondary,
            )
        }
    }
}

@Composable
private fun PrintJobCard(
    job: PrintJobEntity,
    isPrinting: Boolean = false,
    onQtyChange: ((Int) -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onPrintSingle: (() -> Unit)? = null,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val isDone = job.status == PrintJobEntity.STATUS_DONE
    val isFailed = job.status == PrintJobEntity.STATUS_FAILED

    val statusBg = when {
        isDone -> SuccessLight
        isFailed -> DangerLight
        else -> PrimaryLight
    }
    val statusColor = when {
        isDone -> Success
        isFailed -> Danger
        else -> Primary
    }

    Card(
        shape = RoundedCornerShape(Design.CardRadius.dp),
        colors = CardDefaults.cardColors(containerColor = Design.CardSurface),
        border = BorderStroke(0.5.dp, if (isFailed) Danger.copy(alpha = 0.4f) else Border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().sxElevation(SxElevation.L2),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 类型图标
                Surface(
                    shape = CircleShape,
                    color = statusBg,
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when {
                                job.sourceScene == PrintJobEntity.SCENE_SHELF -> Icons.Default.QrCode
                                isDone -> Icons.Default.CheckCircle
                                isFailed -> Icons.Default.Error
                                else -> Icons.Default.LocalOffer
                            },
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Spacer(Modifier.width(10.dp))

                // 商品名与规格
                Column(Modifier.weight(1f)) {
                    Text(
                        text = job.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (job.sellPrice > 0) {
                            Text(
                                text = "¥%.2f".format(job.sellPrice),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Danger,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
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
                        val bc = job.barcode?.takeIf { it.isNotBlank() } ?: job.sku
                        if (bc.isNotBlank()) {
                            Text(
                                text = bc,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextWeak,
                            )
                        }
                    }
                }

                // 操作按钮：失败项提供重试，任意项可删除
                if (isFailed) {
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(containerColor = Danger),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Icon(Icons.Default.Refresh, null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重试", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(4.dp))
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "删除",
                        tint = TextWeak,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 交互操作栏：未完成项支持调张数、改内容、打本条
            if (!isDone) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // 数量步进器（加减按钮，最低 1 份）
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = SurfaceSubtle,
                        border = BorderStroke(0.5.dp, Border),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { onQtyChange?.invoke(-1) },
                                enabled = job.qty > 1,
                                modifier = Modifier.size(28.dp),
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
                                modifier = Modifier.padding(horizontal = 6.dp),
                            )
                            IconButton(
                                onClick = { onQtyChange?.invoke(1) },
                                modifier = Modifier.size(28.dp),
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

                    Spacer(Modifier.width(8.dp))

                    // 改内容按钮
                    OutlinedButton(
                        onClick = { onEdit?.invoke() },
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(0.5.dp, Primary.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Icon(Icons.Default.Edit, null, tint = Primary, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("改内容", fontSize = 11.sp, color = Primary, fontWeight = FontWeight.Medium)
                    }

                    if (!isFailed) {
                        Spacer(Modifier.width(6.dp))
                        Button(
                            onClick = { onPrintSingle?.invoke() },
                            enabled = !isPrinting,
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryLight),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp),
                        ) {
                            Text("打本条", fontSize = 11.sp, color = Primary, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    // 来源场景胶囊
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = SurfaceSubtle,
                    ) {
                        Text(
                            text = PrintJobEntity.sceneLabel(job.sourceScene),
                            fontSize = 10.sp,
                            color = TextWeak,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    }
                }
            } else {
                // 已完成项展示元信息
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = SurfaceSubtle,
                        ) {
                            Text(
                                text = PrintJobEntity.sceneLabel(job.sourceScene),
                                fontSize = 11.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = SurfaceSubtle,
                        ) {
                            Text(
                                text = "已打印 ${job.qty} 张",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextMain,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(Design.PillRadius.dp),
                        color = statusBg,
                    ) {
                        Text(
                            text = "已出签 ✓",
                            color = statusColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}
