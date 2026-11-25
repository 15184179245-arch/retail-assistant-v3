@file:OptIn(ExperimentalFoundationApi::class)

package com.retaildemo.v2.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retaildemo.v2.ui.theme.Border
import com.retaildemo.v2.ui.theme.Primary
import kotlinx.coroutines.delay
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.ui.theme.TextWeak

/**
 * 数字输入焦点控制 + 键盘面板数据。
 *
 * 同一时刻只允许一个数字字段持有键盘；点另一个字段即把键盘让给它。
 * 键盘**不是**覆盖层：由宿主（[com.retaildemo.v2.ui.window.SanxAlertDialog] /
 * [com.retaildemo.v2.ui.window.SanxSmallWindow] / 页面）调 [NumberPadPanel] 渲染在内容末尾，
 * 于是宿主自身会为键盘腾出空间（内容区收缩/滚动），**永远不会遮住正在编辑的字段**。
 * （2026-09-13 老板实测：「小键盘会挡住输入框」——首版做成屏幕底部覆盖式弹层，已弃。）
 */
@Stable
class NumberPadController {
    /** 当前持有键盘的字段 id；null = 无键盘。 */
    var activeId: String? by mutableStateOf(null)
        private set

    /** 面板要显示的字段名（由持有字段每次重组刷新）。 */
    var label: String by mutableStateOf("")
        internal set

    /** 面板要显示的当前值。 */
    var value: String by mutableStateOf("")
        internal set

    /** 是否允许小数点（决定键盘末行是否出现小数点键）。 */
    var decimals: Boolean by mutableStateOf(true)
        internal set

    internal var onKey: (String) -> Unit = {}
    internal var onDone: () -> Unit = {}

    fun open(id: String) {
        activeId = id
    }

    fun close() {
        activeId = null
    }

    /** 字段离场（宿主关闭/列表回收）时兜底释放，防焦点悬空。 */
    fun releaseIfActive(id: String) {
        if (activeId == id) activeId = null
    }
}

val LocalNumberPad = staticCompositionLocalOf { NumberPadController() }

private var numberFieldSeq = 0

/**
 * 只输入数字的输入框：外观与 [OutlinedTextField] 一致，但**不调起系统输入法**——
 * 点按后由内置大数字九宫格键盘接管（[NumberPadPanel]，由宿主渲染在内容末尾）。
 * 大按键 + 即震即响，店员戴手套/单手也能一次点准（巴枪范式）。
 *
 * @param decimals 是否允许小数点（金额 true；纯整数如数量/电话/实盘数 false）
 * @param maxIntDigits 整数位上限（默认 9；电话等长号码传 [NumberPadInput.MAX_PHONE_DIGITS]）
 */
@Composable
fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    decimals: Boolean = true,
    maxIntDigits: Int = NumberPadInput.MAX_INT_DIGITS,
    placeholder: String? = null,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(4.dp),
    labelSize: TextUnit = TextUnit.Unspecified,
    borderColor: Color? = null,
    pad: NumberPadController = LocalNumberPad.current,
) {
    val id = remember { "np-${numberFieldSeq++}" }
    val active = enabled && pad.activeId == id
    val focusManager = LocalFocusManager.current

    // 键盘占位后把本字段滚进「键盘上方」的可见区 —— 系统输入法同款行为：
    // 点框 → 键盘从下方顶上来 → 内容跟着上移，框始终露在键盘上方。
    // 宿主（SanxAlertDialog 的 text 槽）本就是 verticalScroll，bringIntoView 会滚动它。
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(active) {
        if (active) {
            // 等键盘完成布局再滚，否则滚的是「键盘尚未占位」时的高度；
            // 第二次兜底：宿主卡片带弹簧展开动画，首滚时键盘高度可能尚未定下来。
            delay(80)
            bringIntoView.bringIntoView()
            delay(240)
            bringIntoView.bringIntoView()
        }
    }

    Box(modifier.bringIntoViewRequester(bringIntoView)) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = {
                Text(
                    label,
                    fontSize = labelSize,
                    color = if (active) Primary else TextSecondary,
                )
            },
            placeholder = placeholder?.let { { Text(it, fontSize = labelSize) } },
            singleLine = true,
            shape = shape,
            // enabled=false 负责「不弹系统键盘、不吃点击」，配色逐项扳回常规态，
            // 保证与同屏普通 OutlinedTextField 看不出差别（仅边框在展开时高亮）。
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = TextMain,
                disabledContainerColor = Color.Transparent,
                disabledBorderColor = when {
                    active -> Primary
                    borderColor != null -> borderColor
                    else -> Border
                },
                disabledLabelColor = if (active) Primary else TextSecondary,
                disabledPlaceholderColor = TextWeak,
                disabledLeadingIconColor = TextSecondary,
                disabledTrailingIconColor = TextSecondary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        // 覆盖整块字段的点击层：点哪儿都能唤起键盘
        Box(
            Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (enabled) {
                        // 先释放文本框焦点：否则刚在「品名/备注」等文本框上停留时，
                        // 系统输入法还开着，会和内置键盘同屏打架（对话框被挤爆）
                        focusManager.clearFocus()
                        pad.open(id)
                    }
                },
        )
    }

    if (active) {
        // 每次重组把面板数据刷成最新（值相同时 mutableStateOf 不通知，不会自激）。
        // 回调用普通字段持有：键盘点击发生在重组之后，读到的必是最新闭包。
        SideEffect {
            pad.label = label
            pad.value = value
            pad.decimals = decimals
            pad.onKey = { key ->
                onValueChange(NumberPadInput.apply(value, key, decimals, maxIntDigits))
            }
            pad.onDone = { pad.close() }
        }
    }

    DisposableEffect(id, pad) {
        onDispose { pad.releaseIfActive(id) }
    }
}

/**
 * 内置数字键盘面板：由宿主渲染在内容末尾（对话框/小窗/页面），
 * 顶栏显示「正在改哪个字段 + 当前值」+ 右上角「完成」，下方九宫格。
 * 未持有键盘时完全不占位（高度 0）。
 *
 * 与系统输入法不同屏：点数字框时 [NumberField] 先 clearFocus 收 IME（主方向）；
 * 反方向（键盘开着时用户去点文本框）由这里的「IME 抬起沿」兜底收起键盘。
 * 注意不能用 [WindowInsets.isImeVisible] 直接做渲染条件——实测 Dialog 窗口里 IME 收起后
 * 该标志不回弹，会把整个面板吞掉（2026-09-13 回归）。这里只认 IME 高度 0→>0 的跳变，
 * 即便标志异常也不会造成面板整体消失。
 */
@Composable
fun NumberPadPanel(
    pad: NumberPadController = LocalNumberPad.current,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val imeOpen = WindowInsets.ime.getBottom(density) > 0
    LaunchedEffect(imeOpen) {
        // 文本框抢到 IME = 用户离开数字输入 → 收起内置键盘（不带 activeId 判断：
        // 键盘没开时这个跳变本来就无副作用）
        if (imeOpen) pad.close()
    }
    if (pad.activeId == null) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 4.dp, bottom = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(pad.label, fontSize = 11.5.sp, color = TextSecondary, maxLines = 1)
                Text(
                    pad.value.ifBlank { "0" },
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (pad.value.isBlank()) TextWeak else TextMain,
                    maxLines = 1,
                )
            }
            NumberPadDoneKey { pad.onDone() }
        }
        Spacer(Modifier.height(6.dp))
        BigNumberKeyboard(
            onKey = { pad.onKey(it) },
            onDone = { pad.onDone() },
            showDecimal = pad.decimals,
            keyHeight = 48.dp, // Design.MinTouchTarget：戴手套/单手也不脱靶
        )
    }
}

@Composable
private fun RowScope.NumberPadDoneKey(onDone: () -> Unit) {
    Surface(
        color = Primary,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .widthIn(min = 72.dp)
            .heightIn(min = 44.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDone,
            ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Text("完成", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}
