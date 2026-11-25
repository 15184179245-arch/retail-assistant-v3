package com.retaildemo.v2.ui.pages.scanner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retaildemo.v2.ui.components.PrimaryActionButton
import com.retaildemo.v2.ui.components.ScanFeedback
import com.retaildemo.v2.ui.components.directTap
import com.retaildemo.v2.ui.theme.Danger
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.NightWarning

/**
 * 回执卡：把「盲扫计数」换成「刚扫那件」的大回执（对标快递交接的「扫到即回执」）。
 *
 * 从 `ScannerActivity` 里搬出来（TASK-02 解耦）：卡片本身不持有状态，
 * 三态的颜色/文案/金额**全部**由 [ScanReceiptSpec] 供，组件只负责摆。
 *
 * ★ 2026-09-26 加「每扫一件确认一次」（老板令）：
 *   传了 [onConfirm] / [onDiscard] 时，卡片底部多出一行**裁决钮**（不算 / 计入）——
 *   这正是 M0 待确认态的样子；点「计入」才真的算进这一单。
 *   不传则与从前完全一样（M1/M2/M3 那些"已经裁决好"的态不需要钮）。
 *
 * @param confirmHint 卡片上的一行提示（目前用于「上一件没确认 · 没算」的告知）——
 *   **顶替必须说出来**：静默作废是最危险的失败型（店员以为算了、实际没算）。
 *
 * ⚠️ 文案与画布的差异（2026-09-20 修正，保持）：画布 M2 写「在清单里点那一行改」，
 *    但**扫码页不拥有数量**（数量归「记一笔」的货筐），所以指向真正能改的地方。
 */
@Composable
fun ScanReceiptCard(
    receipt: ScanReceipt,
    modifier: Modifier = Modifier,
    confirmHint: String? = null,
    onConfirm: (() -> Unit)? = null,
    onDiscard: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = ScanReceiptSpec.cardTint(receipt.state),
        modifier = modifier.width(358.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                ReceiptPill(
                    text = ScanReceiptSpec.pill(receipt.state),
                    color = ScanReceiptSpec.accent(receipt.state),
                    textColor = ScanReceiptSpec.pillText(receipt.state),
                )
                // M2 右侧「未计入」：§5.5 与画布 56:796 同一行右侧的实测徽标（另两态返回 null）
                val trailing = ScanReceiptSpec.trailing(receipt)
                if (trailing != null) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        trailing,
                        color = ScanReceiptSpec.accent(receipt.state),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                receipt.name,
                color = Color.White,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ScanReceiptSpec.detail(receipt),
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val amount = ScanReceiptSpec.amountOf(receipt)
                if (amount != null) {
                    Text(
                        "¥$amount",
                        color = Danger,
                        fontSize = 16.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            // §5.5 文案修正的第三行：只有 M2 有「去哪加数量」的出路，M1/M3 该函数返回 null。
            val wayToFix = ScanReceiptSpec.hint(receipt)
            if (wayToFix != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    wayToFix,
                    color = ScanReceiptSpec.accent(receipt.state),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (confirmHint != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    confirmHint,
                    color = NightWarning,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // ── 裁决钮：只有 M0 待确认才给（调用方传了回调才出现）─────────────
            if (onConfirm != null && onDiscard != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(Design.ButtonRadius.dp),
                        color = Color.White.copy(alpha = 0.10f),
                        border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.30f)),
                        modifier = Modifier
                            .width(96.dp)
                            .height(ConfirmButtonHeight)
                            .directTap(sfx = ScanFeedback.Sfx.UNBIND, onClick = onDiscard),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Text(
                                "不算",
                                color = Color.White,
                                fontSize = Design.FontMd.sp,
                                lineHeight = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    PrimaryActionButton(
                        text = "计入",
                        onClick = onConfirm,
                        // 落筐声：这一下东西才真的进了这一单（与"读到"的嘀区分开）
                        sfx = ScanFeedback.Sfx.ITEM_ADD,
                        containerColor = ScanReceiptSpec.accent(receipt.state),
                        height = ConfirmButtonHeight,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 裁决钮高（dp）—— 卡内动作行，比底栏主 CTA 小一档，但仍在 44dp 触控底线上。 */
private val ConfirmButtonHeight = 44.dp

@Composable
private fun ReceiptPill(text: String, color: Color, textColor: Color) {
    Surface(shape = RoundedCornerShape(11.dp), color = color) {
        Text(
            text,
            color = textColor,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
