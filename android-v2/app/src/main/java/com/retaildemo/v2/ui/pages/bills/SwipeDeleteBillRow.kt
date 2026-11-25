package com.retaildemo.v2.ui.pages.bills

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retaildemo.v2.ui.components.ScanFeedback
import com.retaildemo.v2.ui.theme.Design
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 吸附锚点：闭合（0）/ 展开（-88dp，红底全露）。 */
private enum class SwipeAnchor { Closed, Open }

/** 删除触点宽（M13 74:28：x=270 w=88，即卡右缘滑开 88dp）。 */
private val DeleteZoneWidth = 88.dp
/** 吸附阈值：位移 > 锚距 40% 松手吸附展开，否则回弹（M13 步骤②）。 */
private const val OpenThresholdRatio = 0.4f
/** 确认态无操作自动回退时长（M13 步骤④）。 */
private const val ConfirmRevertMs = 3_000L

/**
 * 01 单据行 · 侧滑删除（M13 规范板 74:37 + 实体稿 74:35；SVG 真源 design/canvas-exports/74_35-*.svg）。
 *
 * 交互契约（逐条取自 M13）：
 * · 左滑露出 88dp 删除区：红底 [Design.DeleteZone]，22dp 白描边垃圾桶 + 12sp Medium「删除」，
 *   触点内 gap 6 垂直水平双居中（SVG 实测 icon 段 y36.5..58.5 / 文字段 y64.5..81.5）；
 * · 卡片只做 translateX、1:1 跟手；吸附弹簧 = Nav（[Design.SpringDampingNav]/[Design.SpringStiffnessNav]，0.90/380）；
 * · 行内两击确认，不弹二次确认框：首击「删除」→ ±4dp 抖 2 次（tween 无过冲，等效 Danger 语义）+
 *   [ScanFeedback.Sfx.WARN]（音效+80ms 震动 = haptic warning），文案切「确认删除」；再击真删；3s 无操作回退确认态；
 * · 删除退场：整行高度塌陷 + 淡出 160ms accelerate（FastOutLinearIn），播完回调 [onCollapsed] 由页面真删数据。
 *   归属约定：组件只上报——再击时回调 [onConfirmDelete]，页面置 collapsing=true，塌陷动画仅由
 *   `LaunchedEffect(collapsing)` 单方驱动，杜绝双动画/双回调；
 * · 收起：拖回闭合 / 他行展开互斥 / 列表滚动 / 点本行卡片（拦截为仅收起，不进详情）。
 * · 无障碍：删除触点挂 custom action「删除」直入确认流程；「键盘模式行尾常显删除图标」因 01 屏无输入场景暂缓（在册遗留）。
 *
 * 页面接线（BillsPage）：open = openSwipeRowId == bill.id（互斥即收起信号）；onCollapsed 里调 viewModel.deleteBill。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeDeleteBillRow(
    open: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    collapsing: Boolean,
    onConfirmDelete: () -> Unit,
    onCollapsed: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var confirmPending by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val collapse = remember { Animatable(1f) }

    val deleteZonePx = with(density) { DeleteZoneWidth.toPx() }
    val flingVelocityPx = with(density) { 125.dp.toPx() }
    // foundation 1.6.8：构造器带 anchors、仅单个 animationSpec（decay 参数 1.7 才有），吸附/回弹同用 Nav 弹簧
    val state = remember(deleteZonePx) {
        AnchoredDraggableState(
            initialValue = SwipeAnchor.Closed,
            anchors = DraggableAnchors {
                SwipeAnchor.Closed at 0f
                SwipeAnchor.Open at -deleteZonePx
            },
            positionalThreshold = { totalDistance -> totalDistance * OpenThresholdRatio },
            velocityThreshold = { flingVelocityPx },
            animationSpec = spring(
                dampingRatio = Design.SpringDampingNav,
                stiffness = Design.SpringStiffnessNav,
            ),
        )
    }

    // 展开态上报 / 拖回闭合上报（页面凭此做互斥与清账；初始 Closed 的 onClose 由页面幂等守卫吸收）
    LaunchedEffect(state) {
        snapshotFlow { state.currentValue }.collect { value ->
            when (value) {
                SwipeAnchor.Open -> onOpen()
                SwipeAnchor.Closed -> onClose()
            }
        }
    }
    // 他行展开（open 翻 false）→ 本行回弹；回弹同时撤销确认态
    LaunchedEffect(open) {
        if (!open) {
            confirmPending = false
            if (!collapsing && state.currentValue != SwipeAnchor.Closed) {
                state.animateTo(SwipeAnchor.Closed)
            }
        }
    }
    // 确认态 3s 无操作自动回退
    LaunchedEffect(confirmPending, open) {
        if (confirmPending && open) {
            delay(ConfirmRevertMs)
            confirmPending = false
        }
    }
    // 塌陷+淡出（160ms accelerate）→ 播完交页面真删。唯一驱动方，见归属约定
    LaunchedEffect(collapsing) {
        if (collapsing && collapse.value == 1f) {
            confirmPending = false
            collapse.animateTo(0f, tween(durationMillis = 160, easing = FastOutLinearInEasing))
            onCollapsed()
        }
    }

    fun handleDeleteTap() {
        if (!open || collapsing || collapse.value < 1f) return
        if (!confirmPending) {
            // 首击：进入确认态——±4dp 抖 2 次（无过冲）+ 音效/震动警告，文案切「确认删除」
            confirmPending = true
            ScanFeedback.play(context, ScanFeedback.Sfx.WARN)
            scope.launch {
                val step = with(density) { 4.dp.toPx() }
                val tick = tween<Float>(durationMillis = 60)
                shake.animateTo(step, tick)
                shake.animateTo(-step, tick)
                shake.animateTo(step, tick)
                shake.animateTo(-step, tick)
                shake.animateTo(0f, tick)
            }
        } else {
            // 再击：请求真删——上报页面置 collapsing，塌陷由上方 LaunchedEffect 统一播放
            confirmPending = false
            onConfirmDelete()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 退场：按塌陷进度收缩布局高度（内容锚顶、不叠压下一行）并同步淡出
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val fraction = collapse.value
                layout(placeable.width, (placeable.height * fraction).roundToInt()) {
                    placeable.placeRelativeWithLayer(0, 0) { this.alpha = fraction }
                }
            }
            .clip(RoundedCornerShape(Design.CardRadius.dp)),
    ) {
        // 红底（74:27：滑开后右缘 88dp 露出）。
        // ⚠️ 不能常驻全不透明：卡片入场 staggeredAppear 期间 alpha<1，红底会透过半透明卡显形
        //    （老板实测「切换/滑动时红色条暴露出来」，2026-09-19）。改为与下方删除触点同款
        //    「随露出比例渐显」：闭合时 alpha=0（透出页底色），侧滑时 1:1 跟手渐显，M13 半滑态语义不变。
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    alpha = (-state.requireOffset() / deleteZonePx).coerceIn(0f, 1f)
                }
                .background(Design.DeleteZone),
        )

        // 删除触点（74:28：88dp 全高右对齐，内容随露出比例渐显——半滑态只有红底，吸附态图标文字齐现）。
        // 注意：外层必须 matchParentSize 贴合行尺寸再 fillMaxHeight——LazyColumn 对 item 传无界高度约束，
        // 直接 fillMaxHeight 会 no-op 退化成包裹内容（实机踩坑：触点只剩 90px、文字被裁）。
        Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(DeleteZoneWidth)
                    .graphicsLayer {
                        alpha = (-state.requireOffset() / deleteZonePx).coerceIn(0f, 1f)
                    }
                    .clickable(enabled = open && !collapsing) { handleDeleteTap() }
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction("删除") { handleDeleteTap(); true },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.graphicsLayer { translationX = shake.value },
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                Text(
                    text = if (confirmPending) "确认删除" else "删除",
                    color = Color.White,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                }
            }
        }

        // 单据卡（3:128）：唯一位移层，与手指 1:1 跟手
        Box(
            modifier = Modifier
                .offset { IntOffset(state.requireOffset().roundToInt(), 0) }
                .anchoredDraggable(state, Orientation.Horizontal, enabled = !collapsing)
                // 展开态点卡片 = 仅收起（M13「点卡片以外任意处回弹」的行内延伸）：
                // Initial 隧道段抢先判断，纯点击（未被手势消费）才拦截，拖拽仍交还 anchoredDraggable
                .pointerInput(open) {
                    if (!open) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var latest = awaitPointerEvent(pass = PointerEventPass.Initial)
                        while (latest.changes.any { it.pressed }) {
                            latest = awaitPointerEvent(pass = PointerEventPass.Initial)
                        }
                        if (latest.changes.none { it.isConsumed }) {
                            latest.changes.forEach { it.consume() }
                            onClose()
                        }
                    }
                },
        ) {
            content()
        }
    }
}
