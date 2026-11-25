package com.retaildemo.v2.ui.pages

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.retaildemo.v2.ui.components.sxElevation
import com.retaildemo.v2.R
import com.retaildemo.v2.ui.theme.SxElevation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.retaildemo.v2.data.remote.CellProductDto
import com.retaildemo.v2.ui.components.EmptyState
import com.retaildemo.v2.ui.components.NumberField
import com.retaildemo.v2.ui.components.NumberPadPanel
import com.retaildemo.v2.ui.components.retaildemoTopBar
import com.retaildemo.v2.ui.components.ScanFeedback
import com.retaildemo.v2.ui.components.TopBarStyle
import com.retaildemo.v2.ui.theme.Border
import com.retaildemo.v2.ui.theme.BorderStrong
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.DangerLight
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.Success
import com.retaildemo.v2.ui.theme.SuccessLight
import com.retaildemo.v2.ui.theme.SurfaceSubtle
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.TextWeak
import com.retaildemo.v2.ui.theme.Warning
import com.retaildemo.v2.ui.theme.WarningLight
import com.retaildemo.v2.ui.viewmodel.StocktakeViewModel

/**
 * 货位盘点（PDA 工业巡检范式）：
 * 扫/输货位码 → 载入货位绑定的商品矩阵 → 录入实盘数 → 实时计算盘盈/盘亏 → 差异行一键提交系统调账。
 */
@Composable
fun StocktakePage(
    onBack: () -> Unit,
    viewModel: StocktakeViewModel = hiltViewModel(),
) {
    val cellCode by viewModel.cellCode.collectAsState()
    val items by viewModel.items.collectAsState()
    val counts by viewModel.counts.collectAsState()
    val summary by viewModel.summary.collectAsState()

    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var input by remember { mutableStateOf(cellCode) }

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val barcode = result.data?.getStringExtra(ScannerActivity.EXTRA_BARCODE)
        if (!barcode.isNullOrBlank()) {
            input = barcode.trim()
            viewModel.loadCell(input)
            ScanFeedback.success(context)
        }
    }

    // I19 盘点现场拍照补全商品图：内置相机（CameraX，CameraCaptureContract）直写 photos/ 待传文件，
    // 拍完即传（失败自动留待补传）；相机权限由 CameraCaptureActivity 自行申请。
    var captureFile by remember { mutableStateOf<java.io.File?>(null) }
    val takePhoto = rememberLauncherForActivityResult(CameraCaptureContract()) { ok ->
        val f = captureFile
        captureFile = null
        if (ok && f != null && f.exists() && f.length() > 0) viewModel.capturePhoto(f)
    }
    fun startStocktakeCapture(productId: Long) {
        ScanFeedback.click(context)
        val f = com.retaildemo.v2.data.repo.ProductPhotoStore.newTargetFile(context, productId)
        captureFile = f
        takePhoto.launch(f)
    }

    // 统计盘点进度
    val checkedCount = items.count { counts.containsKey(it.id) }
    val diffItems = items.mapNotNull { cp ->
        val counted = counts[cp.id] ?: return@mapNotNull null
        val diff = counted - cp.stock_qty
        if (diff != 0.0) diff else null
    }

    Scaffold(
        containerColor = Design.PageBg,
        topBar = {
            retaildemoTopBar(
                title = "货位盘点",
                subtitle = if (cellCode.isNotBlank()) "当前货位: $cellCode" else "PDA 巡检 · 实物核对",
                onBack = onBack,
            )
        },
        bottomBar = {
            if (items.isNotEmpty()) {
                Surface(
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .sxElevation(SxElevation.L4, docked = true),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        // 内置数字键盘：实盘数字段在可滚动列表里，键盘固定在此处（列表可滚，字段不被遮）
                        NumberPadPanel()
                        Button(
                            onClick = {
                                viewModel.commitDifferences()
                                // 语义纠偏：盘点差异提交=提交类动作（此前借用扫码成功铃音）
                                ScanFeedback.play(context, ScanFeedback.Sfx.COMMIT_OK)
                            },
                            enabled = diffItems.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (diffItems.isNotEmpty()) Danger else Primary,
                            ),
                            shape = RoundedCornerShape(Design.ButtonRadius.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(Design.MinTouchTarget.dp),
                        ) {
                            Text(
                                if (diffItems.isNotEmpty()) "提交 ${diffItems.size} 处盘点差异调账" else "全部一致（无须调账）",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))

            // 货位搜索/扫码卡
            Card(
                shape = RoundedCornerShape(Design.CardRadius.dp),
                colors = CardDefaults.cardColors(containerColor = Design.CardSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth().sxElevation(SxElevation.L2),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(10.dp),
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("输入或扫描货位码，如 1F-A-01", fontSize = 13.sp, color = TextWeak) },
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = Primary) },
                        trailingIcon = {
                            IconButton(onClick = { scanLauncher.launch(ScannerActivity.intent(context)) }) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = "扫描货位码", tint = Primary)
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            focusManager.clearFocus()
                            viewModel.loadCell(input.trim())
                        }),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = BorderStrong,
                            focusedContainerColor = Design.PageBg,
                            unfocusedContainerColor = Design.PageBg,
                        ),
                        modifier = Modifier.weight(1f),
                    )

                    Spacer(Modifier.width(8.dp))

                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.loadCell(input.trim())
                        },
                        shape = RoundedCornerShape(Design.ButtonRadius.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        modifier = Modifier.height(52.dp),
                    ) {
                        Text("载入", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // 结果反馈横幅
            summary?.let { (msg, isSuccess) ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSuccess) SuccessLight else DangerLight,
                    border = BorderStroke(0.5.dp, if (isSuccess) Success.copy(alpha = 0.3f) else Danger.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (isSuccess) Success else Danger,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            msg,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isSuccess) Success else Danger,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            if (items.isEmpty()) {
                EmptyState(
                    illustrationRes = R.drawable.ill_empty_shelf,
                    title = "输入或扫码载入货位",
                    subtitle = "系统将自动拉取该货位绑定的全部商品清单，支持逐项核对并直接调账",
                )
            } else {
                // 货位盘点进度统计栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "货位商品 (${items.size} 种)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMain,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "已核 $checkedCount/${items.size}",
                            fontSize = 12.sp,
                            color = TextSecondary,
                        )
                        if (diffItems.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = DangerLight,
                            ) {
                                Text(
                                    "${diffItems.size} 处异常",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Danger,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    // 稿（画布 15 盘点）：6 张卡 y=203..578，卡高 65，节距 75 → **卡距 10**。
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 80.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { it.pl_id }) { cp ->
                        StocktakeItemCard(
                            item = cp,
                            counted = counts[cp.id],
                            onCountChange = { viewModel.setCount(cp.id, it) },
                            onCountClear = { viewModel.clearCount(cp.id) },
                            onPrint = {
                                ScanFeedback.click(context)
                                viewModel.enqueueStocktakePrint(cp)
                            },
                            onCapture = { startStocktakeCapture(cp.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StocktakeItemCard(
    item: CellProductDto,
    counted: Int?,
    onCountChange: (Int) -> Unit,
    onCountClear: () -> Unit = {},
    onPrint: () -> Unit = {},
    onCapture: () -> Unit = {},
) {
    val diff = counted?.let { it - item.stock_qty }

    Card(
        shape = RoundedCornerShape(Design.CardRadius.dp),
        colors = CardDefaults.cardColors(containerColor = Design.CardSurface),
        border = BorderStroke(0.5.dp, if (diff != null && diff != 0.0) Danger.copy(alpha = 0.4f) else Border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().sxElevation(SxElevation.L2),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
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
                                "账面库存: ${item.stock_qty} ${item.unit ?: ""}",
                                fontSize = 11.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        if (!item.barcode.isNullOrBlank()) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                item.barcode,
                                fontSize = 11.sp,
                                color = TextWeak,
                            )
                        }
                    }
                }

                // 盘点差异标签
                when {
                    counted == null -> {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = SurfaceSubtle,
                        ) {
                            Text(
                                "待核实",
                                fontSize = 11.sp,
                                color = TextWeak,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                    diff == 0.0 -> {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = SuccessLight,
                        ) {
                            Text(
                                "✓ 一致",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Success,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                    diff != null && diff > 0 -> {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = WarningLight,
                        ) {
                            Text(
                                "盘盈 +$diff",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Warning,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                    diff != null && diff < 0 -> {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = DangerLight,
                        ) {
                            Text(
                                "盘亏 $diff",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Danger,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // 快捷实数录入行
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // I19 现场拍照补图（图标钮省横宽：盘点快捷行已四控件挤满）
                OutlinedButton(
                    onClick = onCapture,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(0.5.dp, Primary.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(38.dp),
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = "拍照补图", tint = Primary, modifier = Modifier.size(16.dp))
                }

                Spacer(Modifier.width(8.dp))

                // 巡检补打价签
                OutlinedButton(
                    onClick = onPrint,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(0.5.dp, Primary.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(38.dp),
                ) {
                    Icon(Icons.Default.Print, contentDescription = null, tint = Primary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("补打", fontSize = 12.sp, color = Primary)
                }

                Spacer(Modifier.width(8.dp))

                // 快速对齐账面按钮
                OutlinedButton(
                    // 账面含小数（散装）时实数输入只能取整——截断，与 v17 迁移前行为一致
                    onClick = { onCountChange(item.stock_qty.toInt()) },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(0.5.dp, BorderStrong),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(38.dp),
                ) {
                    Text("与账面一致", fontSize = 12.sp, color = TextSecondary)
                }

                Spacer(Modifier.width(10.dp))

                // 实数输入框
                NumberField(
                    value = counted?.toString() ?: "",
                    // 退格删空 / C 清空 = 回到「未盘」；不能落成 0（否则显示 0、退格清不掉，
                    // 0 还会被当成已盘写进提交清单）
                    onValueChange = { v ->
                        if (v.isBlank()) onCountClear() else v.toIntOrNull()?.let(onCountChange)
                    },
                    label = "实盘数",
                    decimals = false,
                    placeholder = "填实数",
                    labelSize = 11.sp,
                    shape = RoundedCornerShape(8.dp),
                    borderColor = BorderStrong,
                    modifier = Modifier.width(100.dp).height(50.dp),
                )
            }
        }
    }
}
