package com.retaildemo.v2.ui.pages.bills

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retaildemo.v2.data.local.entity.BillEntity
import com.retaildemo.v2.data.local.entity.BillItem
import com.retaildemo.v2.data.repo.ReceiptPhotoStore
import com.retaildemo.v2.ui.components.ScanFeedback
import com.retaildemo.v2.ui.pages.ScannerActivity
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.PrimaryLight
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.Warning
import com.retaildemo.v2.ui.window.SanxAlertDialog
import com.retaildemo.v2.ui.window.SanxDialogBlockButton

/**
 * 出库核销拦截确认弹窗（老板 2026-09-07 铁律）：
 * 1. 缺失商品编号必须全部补全（支持扫码枪/手工输入）；
 * 2. 收款方式开单时可不选，出库时必须强制选择（现金 / POS / 备注）；
 * 3. 若为 POS 收款，必须强制拍照/上传小票凭证照片。
 */
@Composable
fun FillMissingSkuDialog(
    billId: String,
    currentPayMethod: String? = null,
    currentNote: String = "",
    currentReceiptPhoto: String? = null,
    missingItems: List<Pair<Int, BillItem>>, // Pair(itemIndexInBill, BillItem)
    onDismiss: () -> Unit,
    onConfirm: (skuMap: Map<Int, String>, payMethod: String, note: String, receiptPhoto: String?) -> Unit,
) {
    val context = LocalContext.current
    val skuMap = remember { mutableStateMapOf<Int, String>() }
    var payMethod by remember { mutableStateOf(currentPayMethod) }
    var note by remember { mutableStateOf(currentNote) }
    var receiptPhoto by remember { mutableStateOf(currentReceiptPhoto) }
    var scanTargetIndex by remember { mutableStateOf<Int?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val barcode = result.data?.getStringExtra(ScannerActivity.EXTRA_BARCODE)
        if (!barcode.isNullOrBlank() && scanTargetIndex != null) {
            ScanFeedback.success(context)
            skuMap[scanTargetIndex!!] = barcode.trim()
            scanTargetIndex = null
        }
    }

    val photoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val bmp = runCatching {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bmp != null) {
                receiptPhoto = ReceiptPhotoStore.saveBitmap(context, billId, bmp)
                errorMsg = null
            }
        }
    }

    SanxAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("出库前核销确认", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextMain)
                Spacer(Modifier.height(4.dp))
                Text(
                    "录入管家婆完成出库前，须确认收款方式与商品编号：",
                    fontSize = 12.sp,
                    color = TextSecondary,
                )
            }
        },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            ) {
                // 收款方式选择（强制必选）
                item {
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = Design.CardSurface),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("收款方式 (出库必选)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextMain)
                                if (payMethod.isNullOrBlank()) {
                                    Spacer(Modifier.width(6.dp))
                                    Text("待选", fontSize = 11.sp, color = Warning, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    BillEntity.PAY_METHOD_CASH to "现金",
                                    BillEntity.PAY_METHOD_POS to "POS",
                                    BillEntity.PAY_METHOD_OTHER to "备注",
                                ).forEach { (k, label) ->
                                    PayChip(
                                        label = label,
                                        selected = payMethod == k,
                                        onClick = {
                                            payMethod = k
                                            errorMsg = null
                                        },
                                    )
                                }
                            }
                            if (payMethod == BillEntity.PAY_METHOD_OTHER) {
                                OutlinedTextField(
                                    value = note,
                                    onValueChange = {
                                        note = it
                                        errorMsg = null
                                    },
                                    placeholder = { Text("填写例外收款说明（如微信私转老板、客户晚结等）", fontSize = 12.sp) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = TextStyle(fontSize = 12.sp),
                                    shape = RoundedCornerShape(6.dp),
                                )
                            }
                        }
                    }
                }

                // POS 小票凭据照片（若为 POS 强制必传）
                if (payMethod == BillEntity.PAY_METHOD_POS) {
                    item {
                        // 方案 A 去套娃（画布 66:1162 方案注记：凭证区不再套带框 Card，改「分组小标题 + 状态字」）：
                        // 未传告警由右侧 danger 状态字表达（66:1180），不再靠整张卡描边变红。
                        Card(
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("POS刷卡小票凭证 (必传)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextMain)
                                    Spacer(Modifier.weight(1f))
                                    if (receiptPhoto.isNullOrBlank()) {
                                        Text("必须上传照片", fontSize = 11.sp, color = Danger, fontWeight = FontWeight.Bold)
                                    } else {
                                        Text("已上传 ✓", fontSize = 11.sp, color = Primary, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!receiptPhoto.isNullOrBlank()) {
                                        val bmp = remember(receiptPhoto) {
                                            runCatching {
                                                BitmapFactory.decodeFile(ReceiptPhotoStore.path(context, receiptPhoto!!))
                                            }.getOrNull()
                                        }
                                        if (bmp != null) {
                                            Image(
                                                bitmap = bmp.asImageBitmap(),
                                                contentDescription = "POS小票",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.size(52.dp).clickable { photoLauncher.launch("image/*") },
                                            )
                                            Spacer(Modifier.width(8.dp))
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = { photoLauncher.launch("image/*") },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(if (receiptPhoto.isNullOrBlank()) "拍照 / 上传小票凭证" else "重选小票照片")
                                    }
                                }
                            }
                        }
                    }
                }

                // 未编号商品补全列表（若有）
                if (missingItems.isNotEmpty()) {
                    item {
                        Text(
                            "待补编号商品 (${missingItems.size} 件)：",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Warning,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    items(missingItems, key = { it.first }) { (idx, item) ->
                        Card(
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = Design.CardSurface),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        item.name,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        color = TextMain,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (item.spec.isNotBlank()) {
                                        Text(item.spec, fontSize = 12.sp, color = TextSecondary)
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    // 画布 37：缺失项徽标——空=「编码待补」Warning；已填=「已补 ✓」Primary
                                    if (skuMap[idx].isNullOrBlank()) {
                                        Text("编码待补", fontSize = 11.sp, color = Warning, fontWeight = FontWeight.SemiBold)
                                    } else {
                                        Text("已补 ✓", fontSize = 11.sp, color = Primary, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                Text(
                                    "${item.qty} ${item.unit} · 售价 ¥%.2f".format(item.price),
                                    fontSize = 12.sp,
                                    color = TextSecondary,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = skuMap[idx] ?: "",
                                        onValueChange = {
                                            skuMap[idx] = it
                                            errorMsg = null
                                        },
                                        label = { Text("商品编号 (必填)") },
                                        placeholder = { Text("管家婆编码/扫条码") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        textStyle = TextStyle(fontSize = 13.sp),
                                        shape = RoundedCornerShape(8.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    IconButton(
                                        onClick = {
                                            scanTargetIndex = idx
                                            scanLauncher.launch(ScannerActivity.intent(context))
                                        },
                                    ) {
                                        Icon(Icons.Default.QrCodeScanner, contentDescription = "扫码输入", tint = Primary)
                                    }
                                }
                            }
                        }
                    }
                }

                if (errorMsg != null) {
                    item {
                        Text(errorMsg!!, color = Danger, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        // 画布 37 表单弹窗板规：确认键 Primary（实体稿误标 Danger，以板规文字为准）；
        // 竖排块状钮：确认在上（Primary 加粗），逃生口「稍后再出库」在下（GrayChip）
        actionsColumn = {
            SanxDialogBlockButton(
                text = "确认核销并出库",
                container = Primary,
                textColor = androidx.compose.ui.graphics.Color.White,
                bold = true,
                enabled = true,
            ) {
                if (payMethod.isNullOrBlank()) {
                    errorMsg = "出库必须选择收款方式（现金 / POS / 备注）！"
                    return@SanxDialogBlockButton
                }
                if (payMethod == BillEntity.PAY_METHOD_OTHER && note.trim().isBlank()) {
                    errorMsg = "选择备注收款时，必须填写例外说明！"
                    return@SanxDialogBlockButton
                }
                if (payMethod == BillEntity.PAY_METHOD_POS && receiptPhoto.isNullOrBlank()) {
                    errorMsg = "POS收款必须上传刷卡小票照片！"
                    return@SanxDialogBlockButton
                }
                val unfilled = missingItems.filter { (idx, _) -> skuMap[idx].isNullOrBlank() }
                if (unfilled.isNotEmpty()) {
                    errorMsg = "仍有 ${unfilled.size} 件商品未输入编号，请补齐！"
                    return@SanxDialogBlockButton
                }
                onConfirm(skuMap.toMap(), payMethod!!, note.trim(), receiptPhoto)
            }
            SanxDialogBlockButton(
                text = "稍后再出库",
                container = Design.GrayChip,
                textColor = TextSecondary,
            ) { onDismiss() }
        },
    )
}

/** 画布 37 收款方式 chip：选中=PrimaryLight 底 + Primary 文「现金 ✓」；未选=GrayChip 底 + 次级文。圆角 8 · 内距 10/5。 */
@Composable
private fun PayChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) PrimaryLight else Design.GrayChip)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            if (selected) "$label ✓" else label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Primary else TextSecondary,
        )
    }
}
