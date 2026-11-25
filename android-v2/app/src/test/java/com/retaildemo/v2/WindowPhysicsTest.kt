package com.retaildemo.v2

import com.retaildemo.v2.ui.window.clampWindowPos
import com.retaildemo.v2.ui.window.snapWindowEdgeX
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * U8 Flyme 小窗物理纯函数测试——两条手势铁律：
 * ①全程不出屏（拖动/落点/历史脏坐标自愈）②松手横向贴最近边。
 * 与 U7 snapBallPosition 测试同一风格（坐标域纯数值）。
 */
class WindowPhysicsTest {

    private val screenW = 1080f
    private val screenH = 2200f
    private val w = 640f
    private val h = 400f
    private val margin = 24f

    // ---------- clampWindowPos ----------

    @Test
    fun `居中位置原样通过`() {
        val (x, y) = clampWindowPos(220f, 900f, w, h, screenW, screenH, margin)
        assertEquals(220f, x, 0.01f)
        assertEquals(900f, y, 0.01f)
    }

    @Test
    fun `拖出右缘被钳回`() {
        val maxX = screenW - w - margin
        val (x, _) = clampWindowPos(screenW + 500f, 900f, w, h, screenW, screenH, margin)
        assertEquals(maxX, x, 0.01f)
    }

    @Test
    fun `拖出上缘被钳回`() {
        val (_, y) = clampWindowPos(220f, -800f, w, h, screenW, screenH, margin)
        assertEquals(margin, y, 0.01f)
    }

    @Test
    fun `拖出下缘钳到底 margin`() {
        val maxY = screenH - h - margin
        val (_, y) = clampWindowPos(220f, screenH + 800f, w, h, screenW, screenH, margin)
        assertEquals(maxY, y, 0.01f)
    }

    @Test
    fun `历史脏坐标(负值)自愈回 margin`() {
        val (x, y) = clampWindowPos(-999f, -999f, w, h, screenW, screenH, margin)
        assertEquals(margin, x, 0.01f)
        assertEquals(margin, y, 0.01f)
    }

    @Test
    fun `屏幕过小窗口大于屏时退化为 margin 点`() {
        val (x, y) = clampWindowPos(220f, 900f, w, h, 300f, 200f, margin)
        assertEquals(margin, x, 0.01f)
        assertEquals(margin, y, 0.01f)
    }

    // ---------- snapWindowEdgeX（Flyme 式：靠近边缘才吸附，中间松手原地保留） ----------

    @Test
    fun `靠近左缘松手贴左缘`() {
        val x = snapWindowEdgeX(margin + 50f, w, screenW, margin)
        assertEquals(margin, x, 0.01f)
    }

    @Test
    fun `靠近右缘松手贴右缘`() {
        val maxX = screenW - w - margin
        val x = snapWindowEdgeX(maxX - 50f, w, screenW, margin)
        assertEquals(maxX, x, 0.01f)
    }

    @Test
    fun `中间松手原地保留不被弹回`() {
        val mid = (screenW - w) / 2f
        val x = snapWindowEdgeX(mid, w, screenW, margin)
        assertEquals(mid, x, 0.01f)
    }

    @Test
    fun `贴边结果仍在钳屏范围内（贴边即合法位）`() {
        val sx = snapWindowEdgeX(0f, w, screenW, margin)
        val (cx, _) = clampWindowPos(sx, margin, w, h, screenW, screenH, margin)
        assertEquals(sx, cx, 0.01f)
    }
}
