package com.retaildemo.v2.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.retaildemo.v2.ui.theme.Design

/**
 * 纯函数（JVM 可测）：把悬浮球位置夹回屏幕内并水平贴最近边。
 * L10 老板令「没有边缘吸附，超出边框点击不了」——此前 onDragEnd 原样保存拖动累计值，
 * 拖出屏幕即永久失联；restore 的历史脏坐标也走本函数自愈。
 * 返回 (x, y)（dp 浮点），x 恒等于左/右边缘贴边位，y 仅夹取不吸附。
 */
internal fun snapBallPosition(
    x: Float,
    y: Float,
    ball: Float,
    screenW: Float,
    screenH: Float,
    margin: Float,
): Pair<Float, Float> {
    val maxX = (screenW - ball - margin).coerceAtLeast(margin)
    val maxY = (screenH - ball - margin).coerceAtLeast(margin)
    val cx = x.coerceIn(margin, maxX)
    val cy = y.coerceIn(margin, maxY)
    val snapX = if (cx + ball / 2f <= screenW / 2f) margin else maxX
    return snapX to cy
}

/**
 * I16 悬浮球：应用内 Compose 叠层（零权限零依赖，仅本 APP 内）。
 * L10 交互修复：拖动实时跟手、全程钳制不出屏、松手动画贴最近左右边（含 margin）。
 * initialX/initialY 允许是历史脏值（旧版本存下的出屏坐标），首帧即被钳回屏内。
 */
@Composable
fun AgentFloatBall(
    initialX: Dp,
    initialY: Dp,
    screenW: Dp,
    screenH: Dp,
    collapsed: Boolean,
    onTap: () -> Unit,
    onDragEnd: (xDp: Dp, yDp: Dp, collapsed: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    ballSize: Dp = 44.dp,
    edgeMargin: Dp = 8.dp,
) {
    val ball = ballSize.value
    val margin = edgeMargin.value
    val init = remember {
        snapBallPosition(initialX.value, initialY.value, ball, screenW.value, screenH.value, margin)
    }
    var posX by remember { mutableFloatStateOf(init.first) }
    var posY by remember { mutableFloatStateOf(init.second) }
    var dragging by remember { androidx.compose.runtime.mutableStateOf(false) }
    // 拖动时零延迟跟手（snap），松手贴边走短动画
    val visX by animateDpAsState(posX.dp, if (dragging) snap() else tween(220), label = "ballX")
    val visY by animateDpAsState(posY.dp, if (dragging) snap() else tween(220), label = "ballY")

    fun clampLive() {
        val maxX = (screenW.value - ball - margin).coerceAtLeast(margin)
        val maxY = (screenH.value - ball - margin).coerceAtLeast(margin)
        posX = posX.coerceIn(margin, maxX)
        posY = posY.coerceIn(margin, maxY)
    }

    Box(
        modifier = modifier
            .offset(x = visX, y = visY)
            .size(ballSize)
            .background(Design.AgentBrand, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures {
                    onTap()
                }
            }
            .pointerInput(screenW, screenH) {
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        dragging = false
                        val (sx, sy) = snapBallPosition(posX, posY, ball, screenW.value, screenH.value, margin)
                        posX = sx
                        posY = sy
                        onDragEnd(sx.dp, sy.dp, collapsed)
                    },
                    onDragCancel = { dragging = false },
                ) { change, drag ->
                    change.consume()
                    posX += drag.x
                    posY += drag.y
                    clampLive()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("王", color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}
