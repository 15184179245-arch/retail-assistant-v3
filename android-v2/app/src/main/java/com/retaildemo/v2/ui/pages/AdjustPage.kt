package com.retaildemo.v2.ui.pages

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.retaildemo.v2.ui.components.sxElevation
import com.retaildemo.v2.ui.theme.SxElevation
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
import com.retaildemo.v2.ui.components.BigNumberKeyboard
import com.retaildemo.v2.ui.components.NumberPadInput
import com.retaildemo.v2.ui.components.PriceText
import com.retaildemo.v2.ui.components.retaildemoTopBar
import com.retaildemo.v2.ui.components.ScanFeedback
import com.retaildemo.v2.ui.components.TopBarStyle
import com.retaildemo.v2.ui.theme.Border
import com.retaildemo.v2.ui.theme.BorderStrong
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
import com.retaildemo.v2.ui.theme.Warning
import com.retaildemo.v2.ui.theme.WarningLight
import com.retaildemo.v2.ui.viewmodel.AdjustViewModel

/**
 * 库存调账（PDA 工业范式）：
 * 扫码 / 查商品 → 选入库或出库 → 大按键输入数量 → 实时预测调后库存 → 选原因 → 一键提交。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdjustPage(
    onBack: () -> Unit,
    viewModel: AdjustViewModel = hiltViewModel(),
) {
    val hit by viewModel.hit.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val context = LocalContext.current

    var barcodeInput by remember { mutableStateOf("") }
    var qtyText by remember { mutableStateOf("") }
    var isIn by remember { mutableStateOf(true) }
    var reason by remember { mutableStateOf("") }
    var resultMsg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val barcode = result.data?.getStringExtra(ScannerActivity.EXTRA_BARCODE)
        if (!barcode.isNullOrBlank()) {
            barcodeInput = barcode.trim()
            viewModel.lookup(barcode.trim())
            ScanFeedback.success(context)
        }
    }

    val parsedQty = qtyText.toIntOrNull() ?: 0
    val projectedStock = hit?.let {
        if (isIn) it.stockQty + parsedQty else it.stockQty - parsedQty
    }

    Scaffold(
        containerColor = Design.PageBg,
        topBar = {
            retaildemoTopBar(
                title = "库存调账",
                subtitle = "出入库校准 · 报损盘盈修正",
                onBack = onBack,
            )
        },
        bottomBar = {
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
                    Button(
                        onClick = {
                            if (hit == null || parsedQty <= 0) {
                                resultMsg = "请选择商品并输入有效数量" to false
                                // 语义纠偏：校验不过=「警告（提示但不阻断）」，不是"扫码失败"
                                ScanFeedback.play(context, ScanFeedback.Sfx.WARN)
                                return@Button
                            }
                            viewModel.submit(parsedQty, isIn, reason.ifBlank { if (isIn) "日常入库" else "日常出库" }) { ok ->
                                resultMsg = (if (ok) "调账已成功落库 ✓" else "调账失败，请检查网关连接") to ok
                                if (ok) {
                                    qtyText = ""
                                    reason = ""
                                    // 语义纠偏：调账落库=提交类动作
                                    ScanFeedback.play(context, ScanFeedback.Sfx.COMMIT_OK)
                                } else {
                                    ScanFeedback.play(context, ScanFeedback.Sfx.WARN)
                                }
                            }
                        },
                        enabled = hit != null && !busy && parsedQty > 0,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isIn) Primary else Danger,
                        ),
                        shape = RoundedCornerShape(Design.ButtonRadius.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(Design.MinTouchTarget.dp),
                    ) {
                        Text(
                            when {
                                busy -> "正在提交至服务端…"
                                hit == null -> "请先扫码或查询商品"
                                parsedQty <= 0 -> "请输入调账数量"
                                isIn -> "确认入库 +$parsedQty 件"
                                else -> "确认出库 -$parsedQty 件"
                            },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .padding(bottom = 80.dp),
        ) {
            // 搜索或扫码卡
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
                        value = barcodeInput,
                        onValueChange = {
                            barcodeInput = it
                            viewModel.lookup(it)
                        },
                        placeholder = { Text("扫码或输入商品条码 / 编码", fontSize = 13.sp, color = TextWeak) },
                        leadingIcon = { Icon(Icons.Default.QrCodeScanner, null, tint = Primary) },
                        singleLine = true,
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
                        onClick = { scanLauncher.launch(ScannerActivity.intent(context)) },
                        shape = RoundedCornerShape(Design.ButtonRadius.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        modifier = Modifier.height(52.dp),
                    ) {
                        Text("扫码", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }

            // 提示/结果消息
            resultMsg?.let { (msg, isOk) ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isOk) SuccessLight else DangerLight,
                    border = BorderStroke(0.5.dp, if (isOk) Success.copy(alpha = 0.3f) else Danger.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            if (isOk) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (isOk) Success else Danger,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            msg,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isOk) Success else Danger,
                        )
                    }
                }
            }

            // 匹配到的商品卡
            hit?.let { p ->
                Card(
                    shape = RoundedCornerShape(Design.CardRadius.dp),
                    colors = CardDefaults.cardColors(containerColor = Design.CardSurface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier.fillMaxWidth().sxElevation(SxElevation.L2),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                p.name,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMain,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            PriceText(price = p.sellPrice, fontSize = 17.sp, color = Danger)
                        }

                        Spacer(Modifier.height(6.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = PrimaryLight,
                            ) {
                                Text(
                                    "当前库存: ${com.retaildemo.v2.util.formatQty(p.stockQty)} ${p.unit ?: ""}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                            if (!p.barcode.isNullOrBlank()) {
                                Text(
                                    "条码: ${p.barcode}",
                                    fontSize = 12.sp,
                                    color = TextWeak,
                                )
                            }
                        }
                    }
                }
            }

            // 调账方向与数量看板
            Card(
                shape = RoundedCornerShape(Design.CardRadius.dp),
                colors = CardDefaults.cardColors(containerColor = Design.CardSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth().sxElevation(SxElevation.L2),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // 调账方向切换 Pills
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isIn) SuccessLight else SurfaceSubtle,
                            border = BorderStroke(1.dp, if (isIn) Success else Color.Transparent),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clickable {
                                    isIn = true
                                    ScanFeedback.tick(context)
                                },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Icon(Icons.Default.ArrowDownward, null, tint = if (isIn) Success else TextWeak, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("入库增加 (+)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (isIn) Success else TextSecondary)
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (!isIn) DangerLight else SurfaceSubtle,
                            border = BorderStroke(1.dp, if (!isIn) Danger else Color.Transparent),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clickable {
                                    isIn = false
                                    ScanFeedback.tick(context)
                                },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Icon(Icons.Default.ArrowUpward, null, tint = if (!isIn) Danger else TextWeak, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("出库扣减 (-)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (!isIn) Danger else TextSecondary)
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // 数量大屏与预测库存
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Design.PageBg, RoundedCornerShape(10.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("调账数量", fontSize = 12.sp, color = TextSecondary)
                            Text(
                                (if (isIn) "+" else "-") + (if (qtyText.isBlank()) "0" else qtyText),
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Black,
                                color = if (isIn) Success else Danger,
                            )
                        }

                        projectedStock?.let { ps ->
                            Column(horizontalAlignment = Alignment.End) {
                                Text("调整后预测库存", fontSize = 11.sp, color = TextWeak)
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (ps < 0) DangerLight else SuccessLight,
                                ) {
                                    Text(
                                        "$ps 件",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (ps < 0) Danger else Success,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // 经典数字大键盘（按键语义与全 APP 统一走 NumberPadInput）。
                    // 调账数量是整数件：不显示小数点（点了会造出 "1.5" 这种 toIntOrNull()=null 的假值，
                    // 界面显示 1.5 但提交值算 0）；值实时生效，✓ 键不显示（死键）。
                    BigNumberKeyboard(
                        onKey = { key -> qtyText = NumberPadInput.apply(qtyText, key, decimals = false) },
                        showDecimal = false,
                        showDone = false,
                    )
                }
            }

            // 快捷原因标签与备注
            Card(
                shape = RoundedCornerShape(Design.CardRadius.dp),
                colors = CardDefaults.cardColors(containerColor = Design.CardSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth().sxElevation(SxElevation.L2),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("调账原因", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                    Spacer(Modifier.height(8.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        listOf("盘点差异", "到货入库", "残损报废", "样品领用", "散客退货", "其他修正").forEach { opt ->
                            val isSelected = reason == opt
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSelected) PrimaryLight else SurfaceSubtle,
                                border = BorderStroke(0.5.dp, if (isSelected) Primary else Border),
                                modifier = Modifier.clickable {
                                    reason = opt
                                    ScanFeedback.tick(context)
                                },
                            ) {
                                Text(
                                    opt,
                                    fontSize = 12.sp,
                                    color = if (isSelected) Primary else TextSecondary,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        placeholder = { Text("填写或选择原因备注（如：搬运破损）", fontSize = 12.sp, color = TextWeak) },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = BorderStrong,
                            focusedContainerColor = Design.PageBg,
                            unfocusedContainerColor = Design.PageBg,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
