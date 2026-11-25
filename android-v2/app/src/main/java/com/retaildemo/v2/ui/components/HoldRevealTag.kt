package com.retaildemo.v2.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.retaildemo.v2.ui.theme.Border
import com.retaildemo.v2.ui.theme.Card
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.SxElevation
import com.retaildemo.v2.ui.theme.TextSecondary

/**
 * =====================================================================================
 * 长按透出浮签（2026-09-14 · U12）——红线 3「商业底价掩码」的正确呈现位置
 *
 * **为什么要有这个文件（老板一语点破的原矛盾）**：
 * 「我必须按住数字才能显示，可我一按，手指不正好把数字盖住了吗？」
 * 是真的矛盾，而且根因很清楚：**把「触发区」和「显示区」放在了同一个位置**——
 * 手指按在 `¥ •••` 上，真值就地替换，落点就是指尖垫的正下方。
 * 顺带还有第二个毛病：就地替换会改宽度（`¥ •••` 三个点宽 → `¥12.80` 宽一倍），
 * 行内布局跟着跳一下。
 *
 * **结论（设计原则，别忘）**：防窥靠的是**时序**（按住才有、松手就没），不是位置。
 * 「按住 N 毫秒」这个机制本身就防住了顾客侧窥，真值显示在哪与防窥无关。
 * 所以真值必须挪到手指之外——本组件把它放在**被按元素正上方**，指尖垫够不着。
 * 同一个问题的工业标准解法是 Android 系统文本放大镜（长按选词时弹出的那个气泡），
 * 做法完全一致；M3 的 TooltipBox 也证明了「长按期间弹 Popup 不会打断手势」。
 *
 * 用 Popup 而不是就地 offset：浮签要跳出父容器的裁剪与滚动（页面在 LazyColumn /
 * 卡片里，就地偏移会被裁掉一半）。
 * =====================================================================================
 */

/**
 * 浮签与锚点之间的间隙（dp）。
 *
 * 依据：成人指尖垫半径约 9mm（≈34dp），手套状态更胖；而锚点已被
 * [atLeastTouchTarget] 撑到 ≥48dp，指尖垫中心≈元素中心 → 元素上沿往上
 * 34 − 24 = 10dp 之内仍可能被指尖压到。取 32dp 才能在指尖外留出可见余量。
 */
const val REVEAL_TAG_GAP_DP = 32

/** 浮签贴屏幕边缘的最小留白（dp），别把字顶到屏幕框上。 */
const val REVEAL_TAG_MARGIN_DP = 12

/**
 * 浮签横向落点（纯函数，可单测）：以锚点为中心对齐，再整体钳进屏幕内。
 *
 * 为什么以锚点为中心对齐：店员按住的是"进货价 `¥ •••`"那一格，浮签正对上方
 * 才会被认成"这一行的数字"，而不是飘在别处。
 */
fun revealTagX(
    anchorLeft: Int,
    anchorWidth: Int,
    tagWidth: Int,
    windowWidth: Int,
    margin: Int,
): Int {
    val centered = anchorLeft + (anchorWidth - tagWidth) / 2
    val maxLeft = (windowWidth - tagWidth - margin).coerceAtLeast(margin)
    return centered.coerceIn(margin, maxLeft)
}

/**
 * 浮签纵向落点（纯函数，可单测）：**优先锚点上方**；上方放不下（锚点靠屏幕顶部）
 * 翻到下方，上下都不够就贴住上边再钳进屏幕。
 *
 * 参数全是像素；[gap] 就是让开指尖的间隙。
 */
fun revealTagY(
    anchorTop: Int,
    anchorBottom: Int,
    tagHeight: Int,
    windowHeight: Int,
    gap: Int,
    margin: Int,
): Int {
    val above = anchorTop - tagHeight - gap
    if (above >= margin) return above
    val below = anchorBottom + gap
    val maxTop = (windowHeight - tagHeight - margin).coerceAtLeast(margin)
    return below.coerceIn(margin, maxTop)
}

/** 把 [revealTagX] / [revealTagY] 接到 Compose 的 Popup 定位协议上。 */
private class RevealTagPositionProvider(
    private val gapPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        revealTagX(
            anchorLeft = anchorBounds.left,
            anchorWidth = anchorBounds.width,
            tagWidth = popupContentSize.width,
            windowWidth = windowSize.width,
            margin = marginPx,
        ),
        revealTagY(
            anchorTop = anchorBounds.top,
            anchorBottom = anchorBounds.bottom,
            tagHeight = popupContentSize.height,
            windowHeight = windowSize.height,
            gap = gapPx,
            margin = marginPx,
        ),
    )
}

/**
 * 按住期间浮在手指上方的真值浮签。
 *
 * @param label 浮签里的名目（如「进货价」）——真值离开原位后就失去了上下文，
 *              一个光秃秃的 `¥12.80` 飘在空中会让人不敢信；内容自带名目的（如「成本 ¥12.80」）传 null。
 * @param content 真值内容（沿用各页面原有样式，不另造一套）。
 */
@Composable
fun HoldRevealTag(label: String?, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val shape = RoundedCornerShape(Design.CardRadius.dp)
    Popup(
        popupPositionProvider = RevealTagPositionProvider(
            gapPx = with(density) { REVEAL_TAG_GAP_DP.dp.roundToPx() },
            marginPx = with(density) { REVEAL_TAG_MARGIN_DP.dp.roundToPx() },
        ),
        properties = PopupProperties(
            focusable = false,
            dismissOnClickOutside = false,
            dismissOnBackPress = false,
        ),
    ) {
        Surface(
            shape = shape,
            color = Card,
            border = BorderStroke(0.5.dp, Border),
            modifier = Modifier.sxElevation(SxElevation.L2, shape = shape, hairline = false),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (label != null) {
                    Text(
                        label,
                        fontSize = 11.sp,
                        color = TextSecondary,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(2.dp))
                }
                content()
            }
        }
    }
}
