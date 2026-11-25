package com.retaildemo.v2.ui.components

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.Primary
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.retaildemo.v2.data.local.entity.BillItem
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.util.qrContentOf
import com.retaildemo.v2.ui.theme.Success
import com.retaildemo.v2.ui.theme.SuccessLight
import com.retaildemo.v2.ui.theme.SurfaceSubtle

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/** 生成二维码位图（内容=条码/SKU文本，H 级容错，对齐 PWA QRDisplay）。 */
fun generateQrBitmap(content: String, size: Int = 620): Bitmap? {
    if (content.isBlank()) return null
    return try {
        val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(
                    x, y,
                    if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE,
                )
            }
        }
        bmp
    } catch (_: Exception) {
        null
    }
}

/**
 * 核心交互：账单商品逐条全屏大二维码，供扫描枪录入管家婆。
 * 上下滑动垂直平滑翻页动画 / 左右按钮平滑滑动切换，第 N/M 提示，点空白或 × 关闭。
 * 支持完成录入一键标记已出库闭环。
 * 二维码全面采用管家婆原始 SKU 编号（如 192.0.2.10），无 SKU 时兜底条形码。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun QrOverlay(
    items: List<BillItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onMarkPaid: (() -> Unit)? = null,
    /** 03 稿：逐条录入状态读取（index → recorded）。空实现 = 恒为未录。 */
    recordedOf: (Int) -> Boolean = { false },
    /** 03 稿：切换当前条的录入状态（null = 不显示切换按钮）。 */
    onToggleRecorded: ((Int) -> Unit)? = null,
    /** 04 稿：顶部右侧单据标签（如「单据 01 · 老张五金」）；空串不显示。 */
    headerLabel: String = "",
    /** 04 稿：全部条目标记已录后的回调（用于自动标记已出库；可空）。 */
    onAllRecorded: (() -> Unit)? = null,
) {
    if (items.isEmpty()) return

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, items.lastIndex),
        pageCount = { items.size },
    )
    val coroutineScope = rememberCoroutineScope()
    val winEnter = com.retaildemo.v2.ui.window.rememberWindowEnter()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Design.ScrimInk), // 04 稿：全屏沉浸暗底（冷墨蓝黑，非半透明）
            contentAlignment = Alignment.Center,
        ) {
            LaunchedEffect(items, initialIndex) {
                val target = initialIndex.coerceIn(0, items.lastIndex)
                if (pagerState.currentPage != target) {
                    pagerState.scrollToPage(target)
                }
            }

            // 04 稿：顶部 ✕ + 单据标签 + 「第 X 件/共 N 件 · 已扫进管家婆 K 件」+ 橙色进度条
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = 34.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "关闭", tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    if (headerLabel.isNotEmpty()) {
                        Text(
                            headerLabel,
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(horizontal = 24.dp)) {
                    Text(
                        "第 ${pagerState.currentPage + 1} 件",
                        color = Color.White,
                        fontSize = 15.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        " / 共 ${items.size} 件",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    val doneCount = items.indices.count { recordedOf(it) }
                    Text(
                        "已扫进管家婆 $doneCount 件",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                // 04 稿进度条：高 6dp rx3（画布 y155 h6，此前 4dp 偏细）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.15f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((pagerState.currentPage + 1f) / items.size)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Primary),
                    )
                }
            }

            // 04 稿：左右轻划切换传送带（手势跟手、物理弹簧阻尼吸附、平滑动画切换）
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            ) { page ->
                val item = items[page]
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    // 04 稿结构（画布 3:8）：白码卡 286×286 rx24、内边距 27，只装 232×232 二维码；
                    // 品名/价格/条码在卡外深底上（卡底 520 → 品名 581 → 页点 710，均画布锚点）。
                    // ⚠️ 白字压白卡不可读 = 容器结构错误，勿再把文字塞回卡内。
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = winEnter.scale.value
                                scaleY = winEnter.scale.value
                                alpha = winEnter.alpha.value
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {}, // 阻断点击卡片区域触发关闭
                            ),
                    ) {
                        // 优先使用管家婆原始 SKU 编号（如 192.0.2.10），无 SKU 时兜底 barcode
                        val qrCode = qrContentOf(item.sku, item.barcode)
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = Color.White,
                        ) {
                            // ⚠️ 内衬 27dp 必须放在 Surface 内容内侧：挂 modifier 上会变成
                            //    外边距（暗底上不可见），白卡只剩 232dp（实测踩坑 2026-09-19）
                            Box(modifier = Modifier.padding(27.dp)) {
                                val bmp = remember(qrCode) { generateQrBitmap(qrCode) }
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = "商品二维码 $qrCode",
                                        modifier = Modifier.size(232.dp),
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(232.dp)
                                            .background(SurfaceSubtle),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("无条码/SKU", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(34.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = item.name,
                                color = Color.White,
                                fontSize = 20.sp,
                                lineHeight = 24.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                modifier = Modifier.basicMarquee(),
                            )
                            if (recordedOf(page)) {
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = SuccessLight,
                                ) {
                                    Text(
                                        "已录 ✓",
                                        color = Success,
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        // 04 稿：×N @单价 灰 + 小计红
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "×${item.qty} @¥%.2f".format(item.price),
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 13.sp,
                                lineHeight = 16.sp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "¥%.2f".format(item.subtotal),
                                color = Danger,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                lineHeight = 20.sp,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = qrCode,
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White.copy(alpha = 0.55f),
                        )
                    }
                }
            }

            // 04 稿：进度点（当前=橙色长点）+ 上一件/下一件胶囊 + 逐条标记 + 完成提示
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items.indices.forEach { i ->
                        val cur = i == pagerState.currentPage
                        Box(
                            modifier = Modifier
                                .size(width = if (cur) 20.dp else 6.dp, height = 6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (cur) Primary else Color.White.copy(alpha = 0.35f)),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            if (pagerState.currentPage > 0) {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                }
                            }
                        },
                        enabled = pagerState.currentPage > 0,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White,
                            disabledContentColor = Color.White.copy(alpha = 0.35f),
                        ),
                        shape = RoundedCornerShape(23.dp),
                        modifier = Modifier.height(46.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("上一件", fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = {
                            if (pagerState.currentPage < items.lastIndex) {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            }
                        },
                        enabled = pagerState.currentPage < items.lastIndex,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White,
                            disabledContainerColor = Color.Transparent,
                            disabledContentColor = Color.White.copy(alpha = 0.4f),
                        ),
                        shape = RoundedCornerShape(23.dp),
                        modifier = Modifier
                            .height(46.dp)
                            .ctaGradient(RoundedCornerShape(23.dp), enabled = pagerState.currentPage < items.lastIndex),
                    ) {
                        Text("下一件", fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
                        Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
                // 03/04 稿：逐条录入状态切换（扫枪录入管家婆后随手标记，驱动进度条）
                if (onToggleRecorded != null) {
                    Spacer(Modifier.height(12.dp))
                    val recorded = recordedOf(pagerState.currentPage)
                    OutlinedButton(
                        onClick = { onToggleRecorded(pagerState.currentPage) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (recorded) Success else Color.Black.copy(alpha = 0.3f),
                            contentColor = Color.White,
                        ),
                        border = if (recorded) null else BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(Design.PillRadius.dp),
                        modifier = Modifier.height(38.dp),
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (recorded) "此件已录入管家婆 ✓（点撤销）" else "此件已扫进管家婆 · 标记已录",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
                if (onMarkPaid != null && pagerState.currentPage == items.lastIndex) {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            onMarkPaid()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Success),
                        shape = RoundedCornerShape(Design.PillRadius.dp),
                        modifier = Modifier.height(40.dp),
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("录入完成 · 标记出库", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "左右轻划也能切下一件 · 扫完最后一件自动标记已出库",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}
