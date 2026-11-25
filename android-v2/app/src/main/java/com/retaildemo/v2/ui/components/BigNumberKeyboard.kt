package com.retaildemo.v2.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.SxElevation

/** 九宫格键位：[label] 显示字面，[action] 回吐给 onKey 的指令，[span] 占几列（0=占位空格）。 */
private data class PadKey(val label: String, val action: String, val span: Float = 1f)

/**
 * 键位指令 → 语义音效（纯函数，便于单测锁定；改映射必须同步改 `PadKeySfxTest`）。
 *
 * 为什么每个键要有自己的声音：键盘是全 APP 被点得最多的表面，店员常常**不看屏幕**
 * 连点（眼睛在货架/单据上）。此前所有键共用一个通用 tick —— 连"完成"这种提交动作
 * 都没有自己的声音，闭眼连点时根本分不清自己按的是数字还是完成。
 */
internal fun padKeySfx(action: String): ScanFeedback.Sfx = when (action) {
    "DONE" -> ScanFeedback.Sfx.COMMIT_OK   // 提交确认（收银抽屉关合）
    "BKSP" -> ScanFeedback.Sfx.BACKSPACE   // 退格（纸张回抽，比按键更闷更低）
    "CLEAR" -> ScanFeedback.Sfx.CLEAR      // 清空（一口气抹掉）
    else -> ScanFeedback.Sfx.KEY           // 数字/小数点（机械短咔）
}

/**
 * 长按连删的**间隔序列**（纯函数，节奏由单测锁定）。
 *
 * 设计（盲操态三通道冗余 + 防呆）：
 * - 首删由"按下"即时触发；按住 [holdDelayMs] 后进入连删，第一次连删在 [initialMs] 后；
 * - 之后每次间隔按 [decayPercent]% 衰减、不低于 [floorMs]——先慢后快，
 *   让"删一两位"和"删一串"两种意图都能顺手完成；
 * - 总次数封顶 [maxRepeats]：数量/金额字段最长 9+2 位，30 次绰绰有余，
 *   而**误按住不放最多也只删 30 位**——防呆上限比"删光为止"安全。
 *
 * @return 每次连删之间的等待间隔（毫秒）列表，长度 = maxRepeats
 */
internal fun holdRepeatSchedule(
    initialMs: Long = 150,
    floorMs: Long = 45,
    decayPercent: Int = 80,
    maxRepeats: Int = 30,
): List<Long> {
    val out = ArrayList<Long>(maxRepeats)
    var cur = initialMs
    repeat(maxRepeats) {
        out.add(cur)
        cur = maxOf(floorMs, cur * decayPercent / 100)
    }
    return out
}

/**
 * 大数字九宫格键盘（WMS PDA 范式）：数量/金额专用，店员戴手套也能准确点按。
 * 4 列网格（三列数字 + 一列动作），末行 0 键跨两列，与系统计算器同构。
 *
 * ## 组件级人机交互契约（SEIS v2.0）
 *
 * 键盘是全 APP **被点得最多的表面**（改价/改量/实收/实盘/额度…全走它），所以它必须
 * 自己满足契约，而不是指望每个宿主页面记得：
 * - **每键独立语义音效**：数字=机械短咔、⌫=纸张回抽、C=一口气抹掉、✓=提交确认。
 *   此前所有键共用一个通用 tick —— 连"完成"这种提交动作都没有自己的声音，
 *   闭眼连点时根本分不清自己按的是数字还是完成。
 * - **按压形变**（[directTap]）：键帽按下即缩小回弹，手指有落点感。
 * - **键帽质感**（[toolSurface]）：白面 + 顶光接边 = 机械键帽的微立体，而非一张白纸。
 * - **键缝吃触摸**：整键盘拦截落在自己身上的触摸，缝隙绝不穿透（对话框里会点穿遮罩
 *   把弹窗关掉丢输入；页面里会点到背后列表）。
 *
 * onKey 回吐：`'0'~'9'` / `'.'` / `"BKSP"`（退格）/ `"CLEAR"`（清空）；onDone: ✓
 * @param showDecimal 是否显示小数点键（纯整数场景如数量/电话/实盘数传 false）
 * @param showDone 是否显示 ✓ 键（宿主已有明确完成动作、或值本就实时生效时传 false，避免死键）
 */
@Composable
fun BigNumberKeyboard(
    onKey: (String) -> Unit,
    onDone: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    showDecimal: Boolean = true,
    showDone: Boolean = true,
    keyHeight: Dp = 56.dp,
) {
    val rows: List<List<PadKey>> = listOf(
        listOf(PadKey("1", "1"), PadKey("2", "2"), PadKey("3", "3"), PadKey("⌫", "BKSP")),
        listOf(PadKey("4", "4"), PadKey("5", "5"), PadKey("6", "6"), PadKey("C", "CLEAR")),
        listOf(
            PadKey("7", "7"),
            PadKey("8", "8"),
            PadKey("9", "9"),
            if (showDone) PadKey("✓", "DONE") else PadKey("", "", 1f),
        ),
        if (showDecimal) {
            listOf(PadKey("0", "0", 2f), PadKey(".", "."), PadKey("", "", 1f))
        } else {
            listOf(PadKey("0", "0", 3f), PadKey("", "", 1f))
        },
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        // 键盘整体吃下落在自己身上的触摸：行距、末行占位格这些「非按键缝隙」绝不能
        // 穿透到下层（对话框里会穿透到遮罩=点空隙把整个弹窗关掉丢输入；页面里会点到背后的列表）。
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { key -> PadKeyCell(key, onKey, onDone, keyHeight) }
            }
        }
    }
}

@Composable
private fun RowScope.PadKeyCell(
    key: PadKey,
    onKey: (String) -> Unit,
    onDone: (() -> Unit)?,
    keyHeight: Dp,
) {
    if (key.label.isEmpty()) {
        Box(modifier = Modifier.weight(key.span).height(keyHeight))
        return
    }
    val isDone = key.action == "DONE"
    val isAction = isDone || key.action == "BKSP" || key.action == "CLEAR"
    val sfx = padKeySfx(key.action)
    // 退格键走长按连删（按下即删一、按住连发、松手即停、30 次封顶防呆）；
    // 其余键保持单发（松手触发）。同一元素只挂一种手势，不叠加。
    val gesture = if (key.action == "BKSP") {
        Modifier.holdRepeatTap(
            sfx = sfx,
            repeatSfx = sfx,
            onPress = { onKey("BKSP") },
            onRepeat = { onKey("BKSP") },
        )
    } else {
        Modifier.directTap(sfx = sfx) {
            when (key.action) {
                "CLEAR" -> onKey("CLEAR")
                "DONE" -> onDone?.invoke()
                else -> onKey(key.action)
            }
        }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .weight(key.span)
            .height(keyHeight)
            .toolSurface(
                shape = RoundedCornerShape(12.dp),
                level = SxElevation.L0,
                background = when {
                    isDone -> Primary
                    isAction -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.surface
                },
                // ✓ 已经是实心品牌色，再描边会显脏；其余键靠顶光接边做"机械键帽"质感
                hairline = !isDone,
            )
            .then(gesture),
    ) {
        Text(
            key.label,
            fontSize = if (key.label.length > 1) 18.sp else if (isAction) 22.sp else 24.sp,
            fontWeight = FontWeight.Bold,
            color = if (isDone) Color.White else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 02 记一笔 · 开单台专用贴底大键盘（对齐 Ardot 画布 3:4 原图）：
 * 4×4 等宽大按键布局：
 * [ 1 ]  [ 2 ]  [ 3 ]  [ ⌫ ]
 * [ 4 ]  [ 5 ]  [ 6 ]  [ 00 ]
 * [ 7 ]  [ 8 ]  [ 9 ]  [ . ]
 * [ +1 ] [ 0 ]  [ +10 ][ 完成 ]
 *
 * 完成键为品牌橙（Primary）圆角矩形，其余键为 SurfaceSubtle 浅灰大键帽。
 */
@Composable
fun CashierKeyboard(
    onKey: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    keyHeight: Dp = 50.dp,
) {
    val rows: List<List<PadKey>> = listOf(
        listOf(PadKey("1", "1"), PadKey("2", "2"), PadKey("3", "3"), PadKey("⌫", "BKSP")),
        listOf(PadKey("4", "4"), PadKey("5", "5"), PadKey("6", "6"), PadKey("00", "00")),
        listOf(PadKey("7", "7"), PadKey("8", "8"), PadKey("9", "9"), PadKey(".", ".")),
        listOf(PadKey("+1", "+1"), PadKey("0", "0"), PadKey("+10", "+10"), PadKey("完成", "DONE")),
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { key -> PadKeyCell(key, onKey, onDone, keyHeight) }
            }
        }
    }
}
