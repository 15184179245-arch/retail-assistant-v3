package com.retaildemo.v2.ui.window

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.retaildemo.v2.ui.components.LocalNumberPad
import com.retaildemo.v2.ui.components.NumberPadController
import com.retaildemo.v2.ui.components.NumberPadPanel
import com.retaildemo.v2.ui.components.ScanFeedback
import com.retaildemo.v2.ui.components.directTap
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.WindowScrimModal
import com.retaildemo.v2.ui.theme.WindowShadowAmbient
import com.retaildemo.v2.ui.theme.WindowShadowSpot
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * U8 Flyme 小窗体系——全 APP 浮窗统一外壳（用户点名对标 Flyme 小窗，老板真机 Meizu 18）。
 * 三件套：
 *  - [SanxAlertDialog]：material3.AlertDialog 平替（同名槽位 + 文本参数双轨），Flyme 对话小窗。
 *  - [SanxSmallWindow]：可拖拽小窗容器（把手 + 标题 + × + 内容区），松手横向贴边。
 *  - [rememberWindowEnter]：弹簧进场状态（scale/alpha），供全屏自定义浮层（出码/看图）复用同一动效语言。
 * 设计基调：24dp 大圆角、弥散柔影、暗化遮罩点外关闭、顶部把手拖拽跟手、
 * 弹簧物理复用 Design tokens（SpringDampingAppear/SpringStiffnessAppear）。
 */

// ===== 窗口专属规格（尺寸内聚此处；遮罩/投影三档色自 tokens v12 `window.*` 起归令牌源） =====
internal object WinSpec {
    val Radius = 24.dp
    val ScrimColor = WindowScrimModal // Flyme 式深遮罩（45% 黑）
    val ShadowElevation = 28.dp
    val ShadowAmbient = WindowShadowAmbient
    val ShadowSpot = WindowShadowSpot
    const val WidthFraction = 0.86f
    val MaxWidth = 360.dp
    val ButtonHeight = 52.dp
    val ButtonFontSize = 15.sp
    val TitleFontSize = 16.sp
    val EdgeMargin = 12.dp
    val EnterDamping = Design.SpringDampingAppear
    val EnterStiffness = Design.SpringStiffnessAppear
    val Shape = RoundedCornerShape(Radius)

    // 画布 37 弹窗实体稿：竖排块状按钮（删除/核销确认键样式）
    val BlockButtonHeight = 48.dp
    val BlockButtonRadius = 10.dp
}

/** 弹簧进场（并行：alpha 淡入 + scale 回弹），供全屏浮层与窗口外壳共用。 */
private suspend fun enterAnim(alpha: Animatable<Float, *>, scale: Animatable<Float, *>) =
    coroutineScope {
        launch { alpha.animateTo(1f, tween(150)) }
        launch {
            scale.animateTo(
                1f,
                spring(dampingRatio = WinSpec.EnterDamping, stiffness = WinSpec.EnterStiffness),
            )
        }
    }

/** 弹簧进场状态：全屏自定义浮层（出码/看图）graphicsLayer 套用，动效同源。 */
class WindowEnterState internal constructor() {
    val scale = Animatable(0.9f)
    val alpha = Animatable(0f)
    internal suspend fun enter() = enterAnim(alpha, scale)
}

@Composable
fun rememberWindowEnter(): WindowEnterState {
    val state = remember { WindowEnterState() }
    LaunchedEffect(state) { state.enter() }
    return state
}

/**
 * 进出状态机：组合即弹簧进场；[close] 后快速缩退（150ms）再回调 onClosed（父层把可见态置 false）。
 * Dialog 的 onDismissRequest（返回键/点外）一律路由到 close()，保证退出动画必播。
 */
internal class WindowReveal(private val onClosed: () -> Unit) {
    var leaving by mutableStateOf(false)
        internal set
    val scale = Animatable(0.9f)
    val alpha = Animatable(0f)

    suspend fun enter() = enterAnim(alpha, scale)

    fun close() {
        if (leaving) return
        leaving = true
    }
}

@Composable
internal fun rememberWindowReveal(onClosed: () -> Unit): WindowReveal {
    val latest = rememberUpdatedState(onClosed)
    val reveal = remember { WindowReveal { latest.value() } }
    LaunchedEffect(reveal) { reveal.enter() }
    LaunchedEffect(reveal.leaving) {
        if (reveal.leaving) {
            coroutineScope {
                launch { reveal.alpha.animateTo(0f, tween(140)) }
                launch { reveal.scale.animateTo(0.94f, tween(150)) }
            }
            latest.value()
            // ★ 兜底「要关但没关成」：父层若因业务原因拒绝关闭（如更新下载中不允许退出），
            // 窗口不会被移出组合。此时必须先把 alpha/scale 恢复，再改 leaving 状态！
            // 如果先改 leaving=false，LaunchedEffect key 改变会导致协程被立即取消，
            // 导致窗口永久卡死在 alpha=0 的隐形遮罩死锁状态（2026-09-14 老板真机实锤问题）。
            reveal.alpha.snapTo(1f)
            reveal.scale.snapTo(1f)
            reveal.leaving = false
        }
    }
    return reveal
}

/** 遮罩层：淡入淡出 + 点击关闭（无水波纹，Flyme 是干净的暗场）。 */
@Composable
private fun SanxScrim(reveal: WindowReveal, pad: NumberPadController) {
    val scrimAlpha by animateFloatAsState(
        targetValue = if (reveal.leaving) 0f else 1f,
        animationSpec = tween(if (reveal.leaving) 140 else 180),
        label = "scrim",
    )
    Box(
        Modifier
            .fillMaxSize()
            .alpha(scrimAlpha)
            .background(WinSpec.ScrimColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                // 内置键盘开着时，第一下点空白 = 收键盘（同系统输入法肌肉记忆），
                // 避免「想收键盘却把整个弹窗连同刚填的值一起关掉」
                if (pad.activeId != null) pad.close() else reveal.close()
            },
    )
}

/** 窗口卡片外壳：圆角 + 弥散柔影 + 弹簧缩放进场/缩退（reveal 的 graphics 在最外层，拖拽平移叠加其上）。 */
@Composable
private fun SanxWindowCard(
    reveal: WindowReveal,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = reveal.scale.value
                scaleY = reveal.scale.value
                alpha = reveal.alpha.value
                shape = WinSpec.Shape
                clip = false
            }
            .shadow(
                elevation = WinSpec.ShadowElevation,
                shape = WinSpec.Shape,
                clip = false,
                ambientColor = WinSpec.ShadowAmbient,
                spotColor = WinSpec.ShadowSpot,
            )
            .background(Design.CardSurface, WinSpec.Shape)
            .clip(WinSpec.Shape)
            // 卡片本体吃下落在卡片内的触摸：字段间隙、键盘行距、空白区这些无控件的缝隙
            // 绝不允许穿透到遮罩——否则「点空隙」被当成「点弹窗外」，直接收窗丢输入
            // （2026-09-13 模拟器实测：点数字键盘的空隙把整只对话框关掉，老板报的「全是bug」）。
            .pointerInput(Unit) { detectTapGestures { } },
        content = content,
    )
}

/** 顶部拖拽把手（Flyme 小窗签名元素）：36×4 圆角条。 */
@Composable
private fun WindowHandle() {
    Box(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 36.dp, height = 4.dp)
                .background(Design.CardStrokeStrong, RoundedCornerShape(2.dp)),
        )
    }
}

/**
 * 拖拽修饰链（px 域）：跟手 + 全程钳屏（不出遮罩可视区）；dragEnd 按需横向贴边。
 * offset 是相对「未拖拽中心位」的偏移；绝对位 = 基准位 + 偏移，钳位在绝对位上做。
 */
private fun Modifier.windowDrag(
    offsetState: MutableState<Offset>,
    draggingState: MutableState<Boolean>,
    cardSizeState: State<IntSize>,
    availW: Float,
    availH: Float,
    marginPx: Float,
    snapEdge: Boolean,
): Modifier = pointerInput(availW, availH, marginPx, snapEdge) {
    detectDragGestures(
        onDragStart = { draggingState.value = true },
        onDragEnd = {
            draggingState.value = false
            if (snapEdge) {
                val w = cardSizeState.value.width.toFloat()
                if (w > 0f) {
                    val (x, y) = offsetState.value
                    val baseX = (availW - w) / 2f
                    val sx = snapWindowEdgeX(baseX + x, w, availW, marginPx)
                    offsetState.value = Offset(sx - baseX, y)
                }
            }
        },
        onDragCancel = { draggingState.value = false },
    ) { change, drag ->
        change.consume()
        val size = cardSizeState.value
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        if (w <= 0f || h <= 0f || availW <= 0f || availH <= 0f) return@detectDragGestures
        val baseX = (availW - w) / 2f
        val baseY = (availH - h) / 2f
        val raw = offsetState.value + drag
        val (ax, ay) = clampWindowPos(
            baseX + raw.x, baseY + raw.y, w, h, availW, availH, marginPx,
        )
        offsetState.value = Offset(ax - baseX, ay - baseY)
    }
}

/** Flyme 按钮条：hairline 上分割 + 按钮间竖 hairline；主按钮品牌橙加粗，危险动作红。 */
@Composable
private fun SanxDialogButtonBar(
    reveal: WindowReveal,
    confirmText: String?,
    onConfirm: (() -> Unit)?,
    confirmEnabled: Boolean,
    confirmDanger: Boolean,
    confirmCloses: Boolean,
    dismissText: String?,
    onDismiss: (() -> Unit)?,
    actions: (RowScope.() -> Unit)?,
) {
    HorizontalDivider(thickness = 0.75.dp, color = Design.CardStroke)
    if (actions != null) {
        Row(
            Modifier.fillMaxWidth().height(WinSpec.ButtonHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) { actions() }
        return
    }
    if (confirmText == null && dismissText == null) return
    Row(
        Modifier.fillMaxWidth().height(WinSpec.ButtonHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dismissText != null) {
            BarButton(
                text = dismissText,
                color = Design.TextSecondary,
                fontWeight = FontWeight.Medium,
                enabled = true,
                reveal = reveal,
                closes = true,
                onClick = { onDismiss?.invoke() ?: reveal.close() },
                Modifier.weight(1f),
            )
        }
        if (dismissText != null && confirmText != null) {
            Box(
                Modifier
                    .width(0.75.dp)
                    .fillMaxHeight()
                    .background(Design.CardStroke),
            )
        }
        if (confirmText != null) {
            BarButton(
                text = confirmText,
                color = if (confirmDanger) Design.Danger else Primary,
                fontWeight = FontWeight.Bold,
                enabled = confirmEnabled,
                reveal = reveal,
                closes = confirmCloses,
                // 组件级契约：危险确认键自带「沉重双记低音」（音效设计里的 danger 事件）。
                // 落点放在组件里而不是 5 处调用点——日后新增删除/作废弹窗自动继承，
                // 不会因为页面作者忘了加而静默丢失这道听觉关卡。
                danger = confirmDanger,
                onClick = { onConfirm?.invoke() },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BarButton(
    text: String,
    color: Color,
    fontWeight: FontWeight,
    enabled: Boolean,
    reveal: WindowReveal,
    closes: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(
        modifier
            .fillMaxHeight()
            .directTap(
                // 危险键=沉重双记低音，普通确认键=提交确认音；两者听感必须拉开档次
                sfx = if (danger) ScanFeedback.Sfx.DANGER else ScanFeedback.Sfx.COMMIT_OK,
                enabled = enabled,
            ) {
                onClick()
                if (closes) reveal.close()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = WinSpec.ButtonFontSize,
            fontWeight = fontWeight,
            color = if (enabled) color else Design.TextPlaceholder,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 画布 37 弹窗实体稿的竖排块状按钮：通栏 48dp · 圆角 10（[WinSpec.BlockButtonRadius]）。
 * 危险块（删除/出库）用 [Design.Danger] 容器白字加粗；次选块（取消/稍后）用 [Design.GrayChip] 容器。
 * 音效契约同 BarButton：danger=沉重双记低音，普通=提交确认音。
 */
@Composable
fun SanxDialogBlockButton(
    text: String,
    container: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(WinSpec.BlockButtonHeight)
            .background(container, RoundedCornerShape(WinSpec.BlockButtonRadius))
            .directTap(
                sfx = if (danger) ScanFeedback.Sfx.DANGER else ScanFeedback.Sfx.COMMIT_OK,
                enabled = enabled,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = WinSpec.ButtonFontSize,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            color = if (enabled) textColor else Design.TextPlaceholder,
            textAlign = TextAlign.Center,
        )
    }
}

/** 屏幕可视区（px）：Dialog 全屏窗口内钳位与贴边的基准。 */
@Composable
private fun rememberScreenPx(): Pair<Float, Float> {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    return remember(configuration, density) {
        with(density) {
            configuration.screenWidthDp.dp.toPx() to configuration.screenHeightDp.dp.toPx()
        }
    }
}

/**
 * Flyme 对话小窗：material3.AlertDialog 平替。
 * 槽位与 M3 同名（title/text），按钮改文本参数（confirmText/dismissText + onConfirm/onDismiss），
 * 复杂按钮用 [actions] 逃生舱。点遮罩/返回键=取消（播退出动画）；确认回调后自动收窗。
 * 把手+标题区可拖拽整窗跟手，全程钳屏不出可视区。
 */
@Composable
fun SanxAlertDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    confirmDanger: Boolean = false,
    confirmCloses: Boolean = true,
    dismissText: String? = null,
    onDismiss: (() -> Unit)? = null,
    actions: (RowScope.() -> Unit)? = null,
    actionsColumn: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val reveal = rememberWindowReveal(onDismissRequest)
    val pad = LocalNumberPad.current
    val (screenW, screenH) = rememberScreenPx()
    val density = LocalDensity.current
    val marginPx = remember(density) { with(density) { WinSpec.EdgeMargin.toPx() } }
    val cardSize = remember { mutableStateOf(IntSize.Zero) }
    val dragOffset = remember { mutableStateOf(Offset.Zero) }
    val dragging = remember { mutableStateOf(false) }
    // 拖动实时跟手（snap），松手回弹用弹簧
    val visX by animateFloatAsState(
        targetValue = dragOffset.value.x,
        animationSpec = if (dragging.value) snap() else spring(
            dampingRatio = WinSpec.EnterDamping,
            stiffness = WinSpec.EnterStiffness,
        ),
        label = "winX",
    )
    val visY by animateFloatAsState(
        targetValue = dragOffset.value.y,
        animationSpec = if (dragging.value) snap() else spring(
            dampingRatio = WinSpec.EnterDamping,
            stiffness = WinSpec.EnterStiffness,
        ),
        label = "winY",
    )

    Dialog(
        onDismissRequest = {
            // 返回键：内置键盘开着先收键盘（保住刚填的值），再按一次才关窗
            if (pad.activeId != null) pad.close() else reveal.close()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize()) {
            SanxScrim(reveal, pad)
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding(),
                contentAlignment = Alignment.Center,
            ) {
                SanxWindowCard(
                    reveal = reveal,
                    modifier = modifier
                        .fillMaxWidth(WinSpec.WidthFraction)
                        .widthIn(max = WinSpec.MaxWidth)
                        .onSizeChanged { cardSize.value = it }
                        .graphicsLayer {
                            translationX = visX
                            translationY = visY
                        },
                ) {
                    // 拖拽热区：把手 + 标题
                    Column(
                        Modifier.windowDrag(
                            offsetState = dragOffset,
                            draggingState = dragging,
                            cardSizeState = cardSize,
                            availW = screenW,
                            availH = screenH,
                            marginPx = marginPx,
                            snapEdge = false,
                        ),
                    ) {
                        WindowHandle()
                        title?.let {
                            Box(Modifier.padding(horizontal = 22.dp, vertical = 10.dp)) { it() }
                        }
                    }
                    text?.let {
                        Box(
                            Modifier
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 22.dp)
                                .padding(bottom = 18.dp),
                        ) { it() }
                    }
                    // 内置数字键盘：渲染在内容末尾 —— 对话框会为它让出空间（text 区收缩可滚），
                    // 所以键盘永远压不到正在编辑的字段（2026-09-13 老板实测「小键盘会挡住输入框」的修复）。
                    NumberPadPanel()
                    Spacer(Modifier.height(6.dp))
                    if (actionsColumn != null) {
                        // 画布 37 弹窗实体稿：竖排块状按钮列（删除/核销类实体键样式）
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) { actionsColumn() }
                    } else {
                        SanxDialogButtonBar(
                            reveal = reveal,
                            confirmText = confirmText,
                            onConfirm = onConfirm,
                            confirmEnabled = confirmEnabled,
                            confirmDanger = confirmDanger,
                            confirmCloses = confirmCloses,
                            dismissText = dismissText,
                            onDismiss = onDismiss,
                            actions = actions,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Flyme 可拖拽小窗：把手 + 标题/副标题 + × 收起 + 内容区（高度封顶可拖，松手横向贴边）。
 * 点遮罩/返回键/×=关闭（播退出动画）。内容自管滚动：文本内容用 Column(verticalScroll)，
 * 列表直接放 LazyColumn（配 heightIn 封顶）。
 */
@Composable
fun SanxSmallWindow(
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    contentHeightFraction: Float = 0.78f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reveal = rememberWindowReveal(onDismiss)
    val pad = LocalNumberPad.current
    val (screenW, screenH) = rememberScreenPx()
    val density = LocalDensity.current
    val marginPx = remember(density) { with(density) { WinSpec.EdgeMargin.toPx() } }
    val maxContentH = remember(screenH, density) {
        with(density) { (screenH * contentHeightFraction).toDp() }
    }
    val cardSize = remember { mutableStateOf(IntSize.Zero) }
    val dragOffset = remember { mutableStateOf(Offset.Zero) }
    val dragging = remember { mutableStateOf(false) }
    val visX by animateFloatAsState(
        targetValue = dragOffset.value.x,
        animationSpec = if (dragging.value) snap() else spring(
            dampingRatio = WinSpec.EnterDamping,
            stiffness = WinSpec.EnterStiffness,
        ),
        label = "smallWinX",
    )
    val visY by animateFloatAsState(
        targetValue = dragOffset.value.y,
        animationSpec = if (dragging.value) snap() else spring(
            dampingRatio = WinSpec.EnterDamping,
            stiffness = WinSpec.EnterStiffness,
        ),
        label = "smallWinY",
    )

    Dialog(
        onDismissRequest = {
            // 返回键：内置键盘开着先收键盘，再按一次才关窗（同 SanxAlertDialog）
            if (pad.activeId != null) pad.close() else reveal.close()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize()) {
            SanxScrim(reveal, pad)
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding(),
                contentAlignment = Alignment.Center,
            ) {
                SanxWindowCard(
                    reveal = reveal,
                    modifier = modifier
                        .fillMaxWidth(0.94f)
                        .widthIn(max = 480.dp)
                        .heightIn(max = maxContentH)
                        .onSizeChanged { cardSize.value = it }
                        .graphicsLayer {
                            translationX = visX
                            translationY = visY
                        },
                ) {
                    // 把手 + 标题行 = 拖拽热区
                    Column(
                        Modifier.windowDrag(
                            offsetState = dragOffset,
                            draggingState = dragging,
                            cardSizeState = cardSize,
                            availW = screenW,
                            availH = screenH,
                            marginPx = marginPx,
                            snapEdge = true,
                        ),
                    ) {
                        WindowHandle()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 18.dp, end = 12.dp)
                                .padding(top = 6.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    title,
                                    fontSize = WinSpec.TitleFontSize,
                                    fontWeight = FontWeight.Bold,
                                    color = Design.TextMain,
                                )
                                if (!subtitle.isNullOrBlank()) {
                                    Text(subtitle, fontSize = 12.sp, color = Design.TextSecondary)
                                }
                            }
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .background(Design.GrayChip, CircleShape)
                                    .clickable { reveal.close() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "✕",
                                    fontSize = 13.sp,
                                    color = Design.TextSecondary,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    HorizontalDivider(thickness = 0.75.dp, color = Design.CardStroke)
                    content()
                    // 内置数字键盘（小窗内若有数字字段，键盘同样占位而非覆盖）
                    NumberPadPanel()
                }
            }
        }
    }
}
