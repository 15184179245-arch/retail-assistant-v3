package com.retaildemo.v2.ui.pages.newbill

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retaildemo.v2.data.local.entity.BillEntity
import com.retaildemo.v2.data.repo.ReceiptPhotoStore
import com.retaildemo.v2.ui.components.NumberField
import com.retaildemo.v2.ui.components.sxElevation
import com.retaildemo.v2.ui.pages.marginPct
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.SxElevation
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.window.SanxAlertDialog

/** B3 无码货双通道表单：custom=店内无码杂件，strade=串货。串货五项必填（规格/售价/来源/串货价/凭证），商品编号选填出库前补齐（2026-09-10 老板口径）。 */
@Composable
fun NoCodeDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (type: String, name: String, spec: String?, priceText: String, qty: Int, costText: String?, sourceVendor: String, stradePriceText: String, voucherPhoto: String?, sku: String?) -> Unit,
) {
    val context = LocalContext.current
    var type by remember { mutableStateOf("custom") }
    var name by remember { mutableStateOf(defaultName) }
    var spec by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("1") }
    var cost by remember { mutableStateOf("") }
    var vendor by remember { mutableStateOf("") }
    var stradePrice by remember { mutableStateOf("") }
    var voucher by remember { mutableStateOf<String?>(null) }
    var sku by remember { mutableStateOf("") }

    val voucherLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val bmp = runCatching {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bmp != null) voucher = ReceiptPhotoStore.saveBitmap(context, "voucher", bmp)
        }
    }

    SanxAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (type == "custom") "店内无码货" else "非店内商品 / 串货", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == "custom",
                        onClick = { type = "custom" },
                        label = { Text("店里无码") },
                    )
                    FilterChip(
                        selected = type == "strade",
                        onClick = { type = "strade" },
                        label = { Text("外面串货") },
                    )
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("品名 (必填)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = spec, onValueChange = { spec = it },
                    label = { Text(if (type == "strade") "型号规格 (必填)" else "型号规格 (可选)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        modifier = Modifier.weight(1f),
                        value = price, onValueChange = { price = it },
                        label = "售价 (必填)",
                    )
                    NumberField(
                        modifier = Modifier.weight(1f),
                        value = qty, onValueChange = { qty = it },
                        label = "数量", decimals = false,
                    )
                }
                if (type == "custom") {
                    NumberField(
                        value = cost, onValueChange = { cost = it },
                        label = "成本价 (可选)",
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField(
                            modifier = Modifier.weight(1f),
                            value = stradePrice, onValueChange = { stradePrice = it },
                            label = "串货价(进价，必填)",
                        )
                        OutlinedTextField(
                            modifier = Modifier.weight(1f),
                            value = vendor, onValueChange = { vendor = it },
                            label = { Text("串货来源 (必填)") }, singleLine = true,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = { voucherLauncher.launch("image/*") }) {
                            Text(if (voucher != null) "垫付凭证已选 ✓" else "上传垫付凭证 (必传)")
                        }
                    }
                }
                OutlinedTextField(
                    value = sku, onValueChange = { sku = it },
                    label = { Text("商品编号 (选填，出库前补齐即可)") },
                    placeholder = { Text("管家婆商品编码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmText = "添加至清单",
        onConfirm = {
            val q = qty.toIntOrNull() ?: 1
            onConfirm(type, name, spec, price, q, cost, vendor, stradePrice, voucher, sku.trim().ifBlank { null })
        },
        dismissText = "取消",
    )
}

/**
 * U5「并入已有挂账单」选择弹窗（2026-09-22 从 NewBillPage 巨石拆出，几何与文案零改动）：
 * 把当前货筐整体追加到某张挂账 —— 原扫码筐「追加并入」能力。
 *
 * 列表用 LazyColumn + heightIn 封顶（不是 verticalScroll）：
 * [SanxAlertDialog] 的 text 槽本身已是滚动容器，列表再挂一层滚动就是 rule 14② 禁的双层嵌套。
 */
@Composable
fun NewBillMergeIntoBillDialog(
    totalQty: Int,
    pendingBills: List<BillEntity>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    SanxAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("并入已有挂账单", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column {
                Text(
                    "把当前 $totalQty 件商品追加到下面哪张单？",
                    fontSize = 12.sp,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.heightIn(max = 320.dp),
                ) {
                    items(pendingBills.take(8), key = { it.id }) { bill ->
                        Card(
                            shape = RoundedCornerShape(Design.ShapeLg.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            modifier = Modifier.fillMaxWidth().sxElevation(SxElevation.L2, shape = RoundedCornerShape(Design.ShapeLg.dp)),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        bill.customerName.ifBlank { "散客" },
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextMain,
                                    )
                                    Text(
                                        "单号: ${bill.id.takeLast(8)} · 当前合计 ¥%.2f".format(bill.totalAmount),
                                        fontSize = 11.sp,
                                        color = TextSecondary,
                                    )
                                }
                                OutlinedButton(
                                    onClick = { onPick(bill.id) },
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(0.5.dp, Primary),
                                    modifier = Modifier.height(34.dp),
                                ) {
                                    Text("追加并入", fontSize = 12.sp, color = Primary, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        },
        dismissText = "取消",
    )
}
