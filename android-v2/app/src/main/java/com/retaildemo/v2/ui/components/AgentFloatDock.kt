package com.retaildemo.v2.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.SxElevation
import com.retaildemo.v2.ui.viewmodel.AgentOnline

/**
 * 胶囊首帧尺寸估计值（dp）。仅用于「首次布局量到真值之前」那一次默认停靠位计算，
 * 量到真值后 dockW/dockH 立即被 onSizeChanged 校正。
 * 取值贴近真值即可：低估会把默认停靠位算出屏外，高估则白留一条缝。
 */
private const val DOCK_W_ESTIMATE = 112f
private const val DOCK_H_ESTIMATE = 36f

/**
 * L10 老王悬浮胶囊（老板令「用浮窗形式一键跳转全屏」）。
 * 2026-09-11 老板令「浮窗要能移动、能关闭」升级：
 * - 可拖动：实时跟手、全程钳制不出屏、松手贴最近左右边（复用已测纯函数 [snapBallPosition]）；
 * - 可关闭：右上角 × 收起，隐藏态由调用方持久化，「我的」工作台可找回；
 * - 位置持久化（AppConfig.agentBallX/Y，-1 = 未存过 → 默认右下角安全区，与旧固定停靠一致）。
 */
@Composable
fun AgentFloatDock(
    online: AgentOnline,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    initialX: Dp,
    initialY: Dp,
    screenW: Dp,
    screenH: Dp,
    onPositionChanged: (xDp: Dp, yDp: Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val margin = 8f
    // 胶囊实测尺寸（首帧布局即校正）。估计值只在量到真值前用一帧，但必须贴近真值：
    // 旧值 96f 低估约 16dp，默认停靠位（右下）会把胶囊右侧顶出屏幕 6dp。
    // 真值 ≈ 112dp = 24(内边距) + 8(呼吸点) + 6 + 26(「老王」13sp) + 4 + 18(AI 徽章) + 8 + 18(×)。
    var dockW by remember { mutableFloatStateOf(DOCK_W_ESTIMATE) }
    var dockH by remember { mutableFloatStateOf(DOCK_H_ESTIMATE) }
    val init = remember {
        val dx = if (initialX.value >= 0f) initialX.value else screenW.value - DOCK_W_ESTIMATE - 16f
        val dy = if (initialY.value >= 0f) initialY.value else screenH.value - DOCK_H_ESTIMATE - 96f
        snapBallPosition(dx, dy, dockW, screenW.value, screenH.value, margin)
    }
    var posX by remember { mutableFloatStateOf(init.first) }
    var posY by remember { mutableFloatStateOf(init.second) }
    var dragging by remember { mutableStateOf(false) }
    // 拖动时零延迟跟手（snap），松手贴边走短动画
    val visX by animateDpAsState(posX.dp, if (dragging) snap() else tween(220), label = "dockX")
    val visY by animateDpAsState(posY.dp, if (dragging) snap() else tween(220), label = "dockY")

    fun clampLive() {
        val maxX = (screenW.value - dockW - margin).coerceAtLeast(margin)
        val maxY = (screenH.value - dockH - margin).coerceAtLeast(margin)
        posX = posX.coerceIn(margin, maxX)
        posY = posY.coerceIn(margin, maxY)
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Design.AgentBrand, // 老王品牌橙（tokens.color.light.agentBrand）
        border = BorderStroke(1.5.dp, Color.White),
        modifier = modifier
            // ⚠️ offset 必须排在 sxElevation **之前**（modifier 链上越靠前 = 越外层）。
            // sxElevation 内部依次是 shadow → clip(shape) → background：
            // 若把 offset 写在它们之后（=内层），位移只作用于「内容」，
            // 底板与裁剪框仍钉在原点 —— 屏上表现为左上角一个空橙块（无「老王」字样）、
            // 内容被裁没、整块连点都点不动（命中区同样被裁到原点）。
            // 2026-09-17 实机实锤：胶囊压在标题上且点/拖全无响应，即此因。
            .offset(x = visX, y = visY)
            .sxElevation(SxElevation.L4, shape = RoundedCornerShape(24.dp), background = Design.AgentBrand)
            .onSizeChanged {
                dockW = with(density) { it.width.toDp().value }
                dockH = with(density) { it.height.toDp().value }
            }
            .pointerInput(Unit) {
                detectTapGestures {
                    onClick()
                }
            }
            .pointerInput(screenW, screenH) {
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        dragging = false
                        val (sx, sy) = snapBallPosition(posX, posY, dockW, screenW.value, screenH.value, margin)
                        posX = sx
                        posY = sy
                        onPositionChanged(sx.dp, sy.dp)
                    },
                    onDragCancel = { dragging = false },
                ) { change, drag ->
                    change.consume()
                    // drag.x/y 是像素，posX/posY 是 dp——必须按密度换算（否则高分屏拖 1px 当 1dp，3x 屏拖动三倍速漂移）
                    posX += drag.x / density.density
                    posY += drag.y / density.density
                    clampLive()
                }
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // 状态呼吸小圆点
            PulseDot(online)
            Spacer(Modifier.width(6.dp))
            Text(
                text = "老王",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(4.dp))
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.25f),
                modifier = Modifier.size(18.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "AI",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // × 收起钮（老板令「浮窗能关闭」）：胶囊内独立热区，点它不触发打开；按住拖动仍走胶囊拖拽
            Box(
                Modifier
                    .size(18.dp)
                    .background(Color.White, CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures {
                            onDismiss()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "收起老王浮窗",
                    tint = Design.AgentBrand,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
private fun PulseDot(online: AgentOnline) {
    // 2026-09-19 性能收口：离线是静态灰点，不必起 infinite 动画——旧实现无论什么
    // 状态都挂着无限动画时钟，空闲时也在逐帧驱动重组，白烧电；只有会动的状态才起。
    if (online == AgentOnline.OFFLINE) {
        Box(Modifier.size(8.dp).background(Design.AgentOffline, CircleShape))
        return
    }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseA",
    )
    Box(
        Modifier
            .size(8.dp)
            .background(
                when (online) {
                    AgentOnline.ONLINE -> Design.AgentOnline.copy(alpha = pulse)
                    AgentOnline.OFFLINE -> Design.AgentOffline
                    else -> Design.AgentBusy.copy(alpha = pulse)
                },
                CircleShape,
            ),
    )
}
