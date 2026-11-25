package com.retaildemo.v2.ui.pages.newbill

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import com.retaildemo.v2.data.local.entity.BillEntity
import com.retaildemo.v2.ui.theme.PrimaryLight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retaildemo.v2.ui.components.PriceText
import com.retaildemo.v2.ui.components.ctaGradient
import com.retaildemo.v2.ui.components.sxElevation
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.SxElevation
import com.retaildemo.v2.ui.theme.Success
import com.retaildemo.v2.ui.theme.SurfaceSubtle
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.TextWeak

/**
 * 开单页「键盘 + 结算」区（TASK-02 从 NewBillPage 平移，几何与文案零改动）：
 * 货筐合计行、贴底九宫格改价键盘、底部结算栏。
 *
 * 九宫格是 02 稿 3:289 的标准 4×4 键阵（1 2 3 ⌫ / 4 5 6 00 / 7 8 9 . / +1 0 +10 完成），
 * 键值→单价文本的换算收在纯函数 [applyPadKey]（组件只画键，不藏算法）。
 */

/** 02 稿合计栏（02_newbill.png）：紧贴连扫横幅下方，左侧件数种数，右侧大墨黑合计。 */
@Composable
fun NewBillCartTotalRow(
    totalQty: Int,
    kinds: Int,
    totalAmount: Double,
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp),
    ) {
        Text(
            text = "共 $totalQty 件 · $kinds 种商品",
            fontSize = 13.sp,
            color = TextSecondary,
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 3.dp),
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "合计 ¥ ",
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 3.dp),
            )
            val totalStr = "%.2f".format(totalAmount)
            val dotIdx = totalStr.indexOf('.')
            val intPart = if (dotIdx >= 0) totalStr.substring(0, dotIdx) else totalStr
            val decPart = if (dotIdx >= 0) totalStr.substring(dotIdx) else ".00"
            Text(
                text = intPart,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TextMain,
            )
            Text(
                text = decPart,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextMain,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
}

/** B15 收款行（开单页）：收款方式三选一 + 顺手拍凭证——「收款 · 出库」的口径来源。 */
@Composable
fun NewBillPayRow(
    payMethod: String?,
    onPayChange: (String?) -> Unit,
    receiptPhoto: String?,
    onPickPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            text = "收款",
            fontSize = 12.sp,
            color = TextWeak,
            lineHeight = 16.sp,
        )
        listOf<Pair<String, String>>(
            "现金" to BillEntity.PAY_METHOD_CASH,
            "POS" to BillEntity.PAY_METHOD_POS,
            "备注" to BillEntity.PAY_METHOD_OTHER,
        ).forEach { (label, value) ->
                val selected = payMethod == value
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Design.PillRadius.dp))
                        .background(if (selected) PrimaryLight else SurfaceSubtle)
                        .clickable { onPayChange(if (selected) null else value) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        color = if (selected) Primary else TextSecondary,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = onPickPhoto,
            shape = RoundedCornerShape(Design.PillRadius.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.height(30.dp),
        ) {
            Text(
                if (receiptPhoto.isNullOrBlank()) "拍凭证" else "凭证 ✓",
                fontSize = 11.sp,
                color = if (receiptPhoto.isNullOrBlank()) Primary else Success,
            )
        }
    }
}

/** 底部结算栏（02 稿 P1 + B15 双按钮）：大字合计 + U5「并入挂账单」+「保存挂账」/「收款 · 出库」。 */
@Composable
fun NewBillSettleBar(
    totalQty: Int,
    totalAmount: Double,
    canMerge: Boolean,
    canReady: Boolean,
    onMerge: () -> Unit,
    onSave: () -> Unit,
    onSaveReady: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = Color.White,
        modifier = Modifier
            .navigationBarsPadding()
            .sxElevation(SxElevation.L4, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), docked = true),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "已选 $totalQty 件商品" + if (canReady) " · 可出库" else " · 挂账",
                    fontSize = 11.sp,
                    color = TextWeak,
                )
                Spacer(Modifier.height(2.dp))
                PriceText(
                    price = totalAmount,
                    integerSize = Design.FontDisplay.sp,
                    symbolSize = Design.FontSm.sp,
                    decimalSize = Design.FontSm.sp,
                    color = TextMain,
                )
            }

            // U5：所扫商品也可并入已有挂账单
            if (canMerge) {
                OutlinedButton(
                    onClick = onMerge,
                    shape = RoundedCornerShape(Design.ButtonRadius.dp),
                    border = BorderStroke(1.dp, Primary),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.height(50.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.MergeType,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text("并入", color = Primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(Modifier.width(8.dp))
            }

            // B15 双按钮：「保存挂账」（入口不设防，落 draft）+「收款 · 出库」（过三件闸直接 ready）。
            // 选了收款方式时出库钮才可用——没选收款就是挂账，语义与硬闸一致。
            OutlinedButton(
                onClick = onSave,
                enabled = totalQty > 0,
                shape = RoundedCornerShape(Design.ButtonRadius.dp),
                border = BorderStroke(1.dp, Primary),
                modifier = Modifier.height(50.dp),
            ) {
                Text("保存挂账", color = Primary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onSaveReady,
                enabled = totalQty > 0 && canReady,
                shape = RoundedCornerShape(Design.ButtonRadius.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    disabledContainerColor = SurfaceSubtle,
                ),
                modifier = Modifier
                    .height(50.dp)
                    .ctaGradient(RoundedCornerShape(Design.ButtonRadius.dp), enabled = totalQty > 0 && canReady),
            ) {
                Text(
                    if (canReady) "收款 · 出库" else "选收款出库",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 02 稿 P1 / 画布 3:289：贴底改价面板与标准 4x4 大按键改价键盘。
 * 第一行：1 2 3 ⌫
 * 第二行：4 5 6 00
 * 第三行：7 8 9 .
 * 第四行：+1 0 +10 完成
 */
@Composable
fun NewBillPriceEditKeypad(
    name: String,
    cost: Double?,
    text: String,
    onCancel: () -> Unit,
    onKey: (String) -> Unit,
    onDone: () -> Unit,
) {
    val price = text.toDoubleOrNull()
    val effCost = cost ?: 0.0
    val margin = price?.let { it - effCost }
    val belowCost = price != null && effCost > 0.0 && price < effCost
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = Color.White,
        modifier = Modifier
            .navigationBarsPadding()
            .sxElevation(SxElevation.L4, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), docked = true),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            // 02 稿 3:4 键盘顶沿：品名 + 成本毛利状态 + 取消（对齐 02_newbill.png 原图）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val marginText = when {
                        belowCost -> "低于成本 · 成本 ¥%.2f · 毛利 ¥%.2f".format(effCost, margin ?: 0.0)
                        effCost > 0.0 && price != null && price > 0.0 -> {
                            val pct = ((price - effCost) / price * 100).coerceAtLeast(0.0)
                            "成本 ¥%.2f · 毛利 %.0f%% (¥%.2f)".format(effCost, pct, price - effCost)
                        }
                        effCost > 0.0 -> "成本 ¥%.2f".format(effCost)
                        else -> "未设成本"
                    }
                    Text(
                        text = marginText,
                        fontSize = 12.sp,
                        color = if (belowCost) Danger else Primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
                TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("取消", fontSize = 13.sp, color = TextWeak)
                }
            }

            // 02 稿标准 4x4 改价键盘矩阵 (3:289)
            val rows = listOf(
                listOf(Triple("1", "1", false), Triple("2", "2", false), Triple("3", "3", false), Triple("⌫", "BKSP", false)),
                listOf(Triple("4", "4", false), Triple("5", "5", false), Triple("6", "6", false), Triple("00", "00", false)),
                listOf(Triple("7", "7", false), Triple("8", "8", false), Triple("9", "9", false), Triple(".", ".", false)),
                listOf(Triple("+1", "+1", false), Triple("0", "0", false), Triple("+10", "+10", false), Triple("完成", "DONE", true)),
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                rows.forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        row.forEach { (label, action, isDone) ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isDone) Primary else SurfaceSubtle,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .clickable {
                                        if (isDone) onDone() else onKey(action)
                                    },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = label,
                                        fontSize = if (isDone) 18.sp else if (label.length > 2) 16.sp else 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isDone) Color.White else TextMain,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 九宫格键指令 → 单价文本（纯函数便于回测）：支持 0-9、小数点、BKSP、CLEAR、00、+1、+10。 */
internal fun applyPadKey(current: String, key: String): String = when {
    key == "BKSP" -> current.dropLast(1)
    key == "CLEAR" -> ""
    key == "." -> when {
        current.contains('.') -> current
        current.isBlank() -> "0."
        else -> current + "."
    }
    key == "00" -> when {
        current.isBlank() || current == "0" || current == "0.00" -> "0"
        current.contains('.') && current.substringAfter('.').length >= 1 -> current
        else -> current + "00"
    }
    key == "+1" -> {
        val v = current.toDoubleOrNull() ?: 0.0
        val n = v + 1.0
        if (n % 1.0 == 0.0) n.toInt().toString() else "%.2f".format(n)
    }
    key == "+10" -> {
        val v = current.toDoubleOrNull() ?: 0.0
        val n = v + 10.0
        if (n % 1.0 == 0.0) n.toInt().toString() else "%.2f".format(n)
    }
    key.length == 1 && key[0] in '0'..'9' -> when {
        current == "0" || current == "0.00" || current == "0.0" -> key
        current.contains('.') && current.substringAfter('.').length >= 2 -> current
        else -> current + key
    }
    else -> current
}
