package com.retaildemo.v2.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.SurfaceSubtle
import com.retaildemo.v2.ui.theme.SxElevation
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextWeak
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * =====================================================================================
 * SEIS v2.0 **组件级**人机交互契约（2026-09-13）
 *
 * 为什么要有这一层：`docs/DESIGN-GUIDELINES.md` 的四大公理此前只活在**页面**里——
 * 全仓 131 处裸 `.clickable`、181 处裸 `Button`、49 处 `TextButton` 各写各的，
 * 而设计令牌里的按压缩放（pressScale）只被用了 2 处，48dp 触达只被显式写了 14 处。
 * 结论很直白：**规范写进文档不等于写进产品**。这里把公理下沉成组件属性，
 * 页面只要用这些组件，就自动满足契约。
 *
 * 组件 ↔ 公理对照：
 *   [atLeastTouchTarget]  → 公理 1（拇指自然区 / 触控目标 ≥48dp）
 *   [directTap]           → 公理 2（直接操纵：就地形变 + 声触反馈，零中介）
 *   [PrimaryActionButton] → 公理 1 + 2（主行动、拇指区、反馈内建）
 *   [InlineStepper]       → 公理 2（行内微步进，替代"弹窗输数值"的中介仪式）
 *   [HoldToReveal]        → 公理 4 / 红线 3（商业底价掩码，长按瞬时透出、松手即隐）
 *   [ChipCloud]           → 公理 3（特征拥塞：chip 云自然换行，绝不挤压文字）
 * =====================================================================================
 */

/**
 * 触达保护：把交互目标的**可点区域**撑到至少 [min]（默认 48dp，工业手套下误触率最低的临界值）。
 *
 * 视觉尺寸由子内容决定、保持不变，超出部分由布局留白承担——所以它只用在
 * 「图标/小控件必须可点」的位置，不要拿它包装整行卡片（那本来就已经够大）。
 *
 * 公理 1：Park & Han (IJIE 2010) 触控目标尺寸效应 + 店内单手持机场景。
 */
fun Modifier.atLeastTouchTarget(min: Dp = Design.MinTouchTarget.dp): Modifier = composed {
    this.defaultMinSize(minWidth = min, minHeight = min)
}

/**
 * 直接操纵点击：**按压缩放 + 声触反馈 + 点击** 一次绑定（公理 2）。
 *
 * 这是本仓所有交互的默认语言——不要再用裸 `.clickable {}`：
 * 裸 clickable 既没有就地反馈（用户不确定有没有点上），也不出声不出震
 * （店员戴手套、看货架、根本没在看屏幕）。
 *
 * 默认**不画水波纹**（`indication = null`）：本 APP 的反馈语言是"物体被按下后形变 +
 * 声触共振"（Apple Fluid / M3 Expressive 的物理隐喻），水波纹是 Material 2 时代的
 * 墨迹隐喻，两者混用会显得廉价。需要水波纹的地方显式传 [ripple] = true。
 *
 * @param sfx 语义音效事件（null = 静音，仅形变与触觉）——取值见 [ScanFeedback.Sfx]
 */
fun Modifier.directTap(
    sfx: ScanFeedback.Sfx? = ScanFeedback.Sfx.KEY,
    enabled: Boolean = true,
    ripple: Boolean = false,
    onClick: () -> Unit,
): Modifier = composed {
    val context = LocalContext.current
    val defaultIndication = androidx.compose.foundation.LocalIndication.current
    val interaction = remember { MutableInteractionSource() }
    this
        .pressScale(interaction)
        .clickable(
            interactionSource = interaction,
            indication = if (ripple) defaultIndication else null,
            enabled = enabled,
        ) {
            if (sfx != null) ScanFeedback.play(context, sfx)
            onClick()
        }
}

/**
 * 主行动按钮（公理 1 + 2）：品牌色、≥48dp、按压形变与声触反馈内建。
 *
 * 页面里出现"这一屏最想让人按的那颗按钮"时用它；其余次要用 [SecondaryActionButton] 或裸 TextButton。
 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    containerColor: Color = Primary,
    contentColor: Color = Color.White,
    sfx: ScanFeedback.Sfx = ScanFeedback.Sfx.COMMIT_OK,
    height: Dp = Design.MinTouchTarget.dp,
) {
    Row(
        modifier = modifier
            .height(height)
            .atLeastTouchTarget()
            // 拟物凸起（Neumorphic）：主 CTA 是店里被按最多的元件，给它「压铸件」手感——
            // 上沿亮棱线 + 下沿冷影 + 收拢的主光投影，替代原先的纯平色块。
            .castRaised(
                shape = RoundedCornerShape(Design.ButtonRadius.dp),
                fill = if (enabled) containerColor else containerColor.copy(alpha = 0.4f),
            )
            .directTap(sfx = sfx, enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Design.SpaceXs.dp))
        }
        Text(text, color = contentColor, fontSize = Design.FontMd.sp, fontWeight = FontWeight.Bold)
    }
}

/** 次行动按钮：中性面 + 深色字，同样 ≥48dp 与反馈内建（用于"取消/次要"位）。 */
@Composable
fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    sfx: ScanFeedback.Sfx = ScanFeedback.Sfx.KEY,
    height: Dp = Design.MinTouchTarget.dp,
) {
    Box(
        modifier = modifier
            .height(height)
            .atLeastTouchTarget()
            .toolSurface(
                shape = RoundedCornerShape(Design.ButtonRadius.dp),
                level = SxElevation.L0,
                background = SurfaceSubtle,
                hairline = false,
            )
            .directTap(sfx = sfx, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) TextMain else TextWeak,
            fontSize = Design.FontMd.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * 行内微步进器（公理 2，业务范式 3「零模态心流」）。
 *
 * 购物车改数量/改单价**不弹窗**：直接在行内 [−] 值 [+]。
 * 全面集成长按连加/连减（[holdRepeatTap]）：按住棘轮自动加速推进，
 * 每一次步进都有棘轮声与触觉，手不用离开商品行、眼睛不用离开货架。
 *
 * @param valueText 展示值（由调用方格式化，可能是 "3" 或 "3.00"）
 */
@Composable
fun InlineStepper(
    valueText: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueWidth: Dp = 36.dp,
    buttonSize: Dp = 32.dp,
    touchTargetSize: Dp = 56.dp, // fix-round1 #10：戴手套热区 56dp（视觉 32 不变）
    decreaseEnabled: Boolean = true,
    increaseEnabled: Boolean = true,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        StepperButton(
            label = "−",
            enabled = enabled && decreaseEnabled,
            sfx = ScanFeedback.Sfx.STEP_DOWN,
            buttonSize = buttonSize,
            touchTargetSize = touchTargetSize,
            onClick = onDecrease,
        )
        Box(
            modifier = Modifier.width(valueWidth).height(touchTargetSize),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                valueText,
                fontSize = Design.FontLg.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) TextMain else TextWeak,
                maxLines = 1,
            )
        }
        StepperButton(
            label = "＋",
            enabled = enabled && increaseEnabled,
            sfx = ScanFeedback.Sfx.STEP_UP,
            buttonSize = buttonSize,
            touchTargetSize = touchTargetSize,
            onClick = onIncrease,
        )
    }
}

@Composable
private fun StepperButton(
    label: String,
    enabled: Boolean,
    sfx: ScanFeedback.Sfx,
    buttonSize: Dp,
    touchTargetSize: Dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .atLeastTouchTarget(touchTargetSize)
            .size(buttonSize)
            .toolSurface(shape = CircleShape, level = SxElevation.L0, background = SurfaceSubtle, hairline = false)
            .holdRepeatTap(
                sfx = sfx,
                repeatSfx = sfx,
                enabled = enabled,
                holdDelayMs = 320,
                onPress = onClick,
                onRepeat = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) TextMain else TextWeak,
        )
    }
}

/**
 * 长按透出（公理 4 / 红线 3：商业底价与毛利默认掩码）。
 *
 * 店员手机随时面向顾客，进价/毛利率必须默认遮住；**按住 [holdMs] 毫秒透出，松手立即隐去**
 * （不是点一下切换——切换会留在明处，顾客一低头就看见了）。
 * 属于"物理按键"式交互：按住才有，松开就没有，没有中间状态可忘记关闭。
 *
 * **真值不就地替换，改浮签上移**（U12，老板 2026-09-14 点破）：之前按住后真值就地替换掩码，
 * 落点正好是指尖垫正下方——手指把要看的数字盖住了，"按住才能看"就等于"按住了看不见"。
 * 现在掩码永远留在原位（宽度恒定、行内布局零跳动），真值改为按住期间浮在手指**上方**的
 * 浮签里（[HoldRevealTag]）。防窥靠时序不靠位置：松手即隐这条没变，顾客依旧看不到。
 *
 * @param label 浮签里的名目（内容自带名目的，如「成本 ¥12.80」，传 null）
 */
@Composable
fun HoldToReveal(
    revealed: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    masked: @Composable () -> Unit = { MaskedPriceText() },
    label: String? = null,
    holdMs: Long = 500,
    enabled: Boolean = true,
) {
    var pressed by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(pressed) {
        if (pressed) {
            delay(holdMs)
            shown = true
            ScanFeedback.play(context, ScanFeedback.Sfx.KEY)
        } else {
            shown = false
        }
    }

    Box(
        modifier = modifier
            .atLeastTouchTarget()
            .pointerPress(
                enabled = enabled,
                onPressChange = { pressed = it },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        // 按住期间掩码压暗一档：500ms 的等待期里要有"我按到了"的即时确认，否则手感像卡住。
        // 用 alpha 不用尺寸/位置，保证锚点 bounds 恒定（浮签定位依赖它）。
        Box(Modifier.graphicsLayer { alpha = if (pressed) 0.5f else 1f }) { masked() }
        if (shown) HoldRevealTag(label = label) { revealed() }
    }
}

/** 按下/松开回调（不做点击语义，专供 [HoldToReveal] 这类"按住才有效"的交互）。 */
private fun Modifier.pointerPress(enabled: Boolean, onPressChange: (Boolean) -> Unit): Modifier = composed {
    if (!enabled) return@composed this
    this.pointerInput(Unit) {
        detectTapGestures(
            onPress = {
                onPressChange(true)
                tryAwaitRelease()
                onPressChange(false)
            },
        )
    }
}

/** 掩码价格（`¥ •••`）——统一形态，别让各页面各写各的。 */
@Composable
fun MaskedPriceText(
    modifier: Modifier = Modifier,
    color: Color = TextWeak,
    fontSize: androidx.compose.ui.unit.TextUnit = Design.FontMd.sp,
) {
    Text(
        "¥ •••",
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * 长按连发点击（C 族动作件）：按下即触发一次，按住 [holdDelayMs] 后按 [schedule]
 * 的节奏连发，松手立即停。
 *
 * 服务对象：数字键盘退格（按住连续删）、行内步进器（按住连加）——凡是
 * 「一下一下点很痛苦、按住连续来才顺手」的高频操作。盲操态三通道冗余照常满足：
 * 每一次连发都有声 + 触（走 [ScanFeedback.Sfx]）+ 形态（按住期间保持按压缩放）。
 *
 * 与 [directTap] 的分工：directTap = 单发（松手触发）；holdRepeatTap = 按下即触发 + 可连发。
 * 同一个元素**只能用其中一个**。
 *
 * @param schedule 连发间隔序列（纯函数生成，如 `holdRepeatSchedule()`），耗尽即停——
 *   这个"封顶"是防呆设计：误按住不放最多连发 N 次，不会无限删/无限加。
 */
fun Modifier.holdRepeatTap(
    sfx: ScanFeedback.Sfx? = ScanFeedback.Sfx.KEY,
    repeatSfx: ScanFeedback.Sfx? = sfx,
    enabled: Boolean = true,
    holdDelayMs: Long = 320,
    schedule: List<Long> = holdRepeatSchedule(),
    onPress: () -> Unit,
    onRepeat: () -> Unit,
): Modifier = composed {
    val context = LocalContext.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) Design.PressScaleDown else 1f,
        animationSpec = tween(durationMillis = if (pressed) Design.PressMs else Design.ReleaseMs),
        label = "holdRepeatScale",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(enabled, holdDelayMs, schedule) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onPress = {
                    pressed = true
                    if (sfx != null) ScanFeedback.play(context, sfx)
                    onPress()
                    // 按住超过 holdDelayMs 才进入连发；期间松手=就是一次单击
                    val quickRelease = withTimeoutOrNull(holdDelayMs) { tryAwaitRelease() }
                    if (quickRelease != true) {
                        for (interval in schedule) {
                            // 间隔内松手 → 立即停（不等这一拍打完，手感才跟手）
                            val released = withTimeoutOrNull(interval) { tryAwaitRelease() }
                            if (released == true) break
                            if (repeatSfx != null) ScanFeedback.play(context, repeatSfx)
                            onRepeat()
                        }
                    }
                    pressed = false
                },
            )
        }
}

/**
 * chip 云（公理 3）：放不下就换行，**绝不挤压文字**。
 *
 * 反例（真机截图实锤）：`Row + Spacer(weight)` 做筛选行，最后那颗 chip 被压成
 * 逐字竖排的三行「需/开/票」。筛选项天生数量不定，必须用可换行容器承接。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipCloud(
    modifier: Modifier = Modifier,
    content: @Composable FlowRowScope.() -> Unit,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}
