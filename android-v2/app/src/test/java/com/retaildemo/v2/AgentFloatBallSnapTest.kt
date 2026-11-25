package com.retaildemo.v2

import com.retaildemo.v2.ui.components.snapBallPosition
import org.junit.Assert.assertEquals
import org.junit.Test

/** L10：悬浮球边缘吸附+边界钳制纯函数（老板令「没有边缘吸附，超出边框点击不了」回归）。 */
class AgentFloatBallSnapTest {

    private val ball = 44f
    private val margin = 8f
    private val w = 1080f
    private val h = 2400f
    private val maxX = w - ball - margin // 1028
    private val maxY = h - ball - margin // 2348

    @Test
    fun `屏内左半区松手贴左边`() {
        val (x, _) = snapBallPosition(500f, 600f, ball, w, h, margin)
        assertEquals(margin, x, 0.001f)
    }

    @Test
    fun `屏内右半区松手贴右边`() {
        val (x, _) = snapBallPosition(560f, 600f, ball, w, h, margin)
        assertEquals(maxX, x, 0.001f)
    }

    @Test
    fun `球心正好在屏幕中线贴左边`() {
        val (x, _) = snapBallPosition(w / 2f - ball / 2f, 600f, ball, w, h, margin)
        assertEquals(margin, x, 0.001f)
    }

    @Test
    fun `拖出右边界_夹回右边缘贴边位`() {
        val (x, y) = snapBallPosition(2000f, 600f, ball, w, h, margin)
        assertEquals(maxX, x, 0.001f)
        assertEquals(600f, y, 0.001f)
    }

    @Test
    fun `拖出左和上边界_夹回屏内`() {
        val (x, y) = snapBallPosition(-500f, -300f, ball, w, h, margin)
        assertEquals(margin, x, 0.001f)
        assertEquals(margin, y, 0.001f)
    }

    @Test
    fun `拖出下边界_y夹到最大值`() {
        val (_, y) = snapBallPosition(500f, 9999f, ball, w, h, margin)
        assertEquals(maxY, y, 0.001f)
    }

    @Test
    fun `历史脏坐标自愈_旧版本存下的出屏位首帧回屏`() {
        // 旧版 onDragEnd 无钳制，可能存进 20000/-3000 这类值
        val (x, y) = snapBallPosition(20000f, -3000f, ball, w, h, margin)
        assertEquals(maxX, x, 0.001f)
        assertEquals(margin, y, 0.001f)
    }

    @Test
    fun `极小屏幕不崩_退化到margin位`() {
        val (x, y) = snapBallPosition(50f, 50f, ball, 30f, 30f, margin)
        assertEquals(margin, x, 0.001f)
        assertEquals(margin, y, 0.001f)
    }
}
