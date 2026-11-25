package com.retaildemo.v2.ui.window

/**
 * U8 Flyme 小窗物理（纯函数，JVM 可测）——与 U7 AgentFloatBall.snapBallPosition 同一范式，
 * 窗口是矩形故按宽高计算：
 * ①全程不出屏：拖动中/落点/历史脏坐标一律钳回可视区（margin 内）；
 * ②松手横向贴最近边：按窗口中心在屏幕左/右半边贴对应边缘（Flyme 小窗手势），纵向只钳不吸。
 * 单位任意（调用侧统一 dp 或统一 px 即可），函数不做换算。
 */

/** 把窗口左上角 (x, y) 钳回屏幕内；屏幕过小时退化到 margin 点（窗口可大于屏，绝不飞出）。 */
internal fun clampWindowPos(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    screenW: Float,
    screenH: Float,
    margin: Float,
): Pair<Float, Float> {
    val maxX = (screenW - w - margin).coerceAtLeast(margin)
    val maxY = (screenH - h - margin).coerceAtLeast(margin)
    return x.coerceIn(margin, maxX) to y.coerceIn(margin, maxY)
}

/**
 * 松手横向贴边（Flyme 手势）：仅当松手位已**靠近**某边缘（≤ threshold）时吸附到该缘，
 * 其余位置原样保留（钳回屏内）——宽窗拖到中间松手不该被弹回屏幕中位。
 */
internal fun snapWindowEdgeX(
    x: Float,
    w: Float,
    screenW: Float,
    margin: Float,
    threshold: Float = screenW * 0.14f,
): Float {
    val maxX = (screenW - w - margin).coerceAtLeast(margin)
    return when {
        x <= margin + threshold -> margin
        x >= maxX - threshold -> maxX
        else -> x.coerceIn(margin, maxX)
    }
}
