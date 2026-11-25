package com.retaildemo.v2

import com.retaildemo.v2.ui.pages.ViewfinderHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取景框命中判定（2026-09-20 老板实报「一堆货摆在一起都给扫上了」的回归锁）。
 *
 * 病根：CameraX 送进分析器的是**整幅画面**，屏幕上那个 236×236 取景框此前纯装饰 ——
 * 全仓 `setViewPort` / `cropRect` / `RectF` 零命中，识别范围对它没有任何约束，
 * 于是视野里所有条码都会被逐帧识别、邻货的码被"顺"进来。
 *
 * 这里锁住视图↔图像的 FILL_CENTER 换算，以及"框外一律不算命中"。
 */
class ViewfinderHitTest {

    /**
     * ★ 取景框矩形必须**对上画布锚点** —— 这是「画框」与「算识别区域」共用的那个函数。
     *
     * 期望值来自画布（不是从代码反推），页「M 扫码交接台」= `3:1`，规格
     * `_reports/redraw/spec/scan_handover.json`：
     *   · 取景框 x = 77（390dp 屏上 (390-236)/2 = 77，恒水平居中）
     *   · 取景框 y = 140 = 状态栏 32 + 顶栏 48 + 间隙 4 + 单据条 44 + 间隙 12
     *
     * ⚠️ 旧锚点 y=273（稿 3:1781，按屏幕剩余高度 0.449 比例算）**已作废**：
     * 画布 M 帧把框上移到单据条之下，纵向改为**绝对上沿**，不再随屏高按比例漂。
     * 两处（`Viewfinder` 画 / `insideViewfinder` 算）都调它，所以这条同时锁住了「同源」。
     */
    @Test
    fun `取景框矩形对上画布锚点 左沿77 上沿140`() {
        val (left, top) = ViewfinderHit.frameLeftTop(
            viewW = 390f, frameSize = 236f, topPx = 140f,
        )
        assertEquals("左沿须为画布实测 x=77（水平居中）", 77f, left, 0.01f)
        assertEquals("框顶须为画布实测 y=140（顶栏+单据条之下）", 140f, top, 0.01f)
    }

    /** 纵向是**绝对上沿**（透传），不再按屏幕剩余高度算比例 —— 换屏高不漂。 */
    @Test
    fun `绝对上沿不随屏高漂移`() {
        assertEquals(140f, ViewfinderHit.frameLeftTop(390f, 236f, 140f).second, 0.001f)
        assertEquals(140f, ViewfinderHit.frameLeftTop(430f, 236f, 140f).second, 0.001f)
    }

    /**
     * ★ 交接台取景框上沿 = 画布 M 帧那串**定高堆栈**之和。
     *
     * 期望值来自画布（`_reports/redraw/gen_scan_handover_spec.py` 的锚点），不是从代码反推：
     * `32(状态栏) + 48(顶栏) + 4(间隙) + 44(单据条) + 12(间隙) = 140`。
     * 与上面那条「左沿 77 / 上沿 140」配对：**画布改了而代码没改时，两条会一起红**。
     */
    @Test
    fun `交接台取景框上沿等于定高堆栈之和`() {
        assertEquals(
            140f,
            ViewfinderHit.handoverFrameTop(
                statusBarPx = 32f, topBarPx = 48f, billBarGapPx = 4f,
                billBarPx = 44f, frameGapPx = 12f,
            ),
            0.001f,
        )
    }

    /** 非交接台（没有单据条那一行）：那一项传 0 —— 堆栈里少掉的正好是 44（140 → 96）。 */
    @Test
    fun `非交接台堆栈少掉单据条那一项`() {
        assertEquals(
            96f,
            ViewfinderHit.handoverFrameTop(32f, 48f, 4f, 0f, 12f),
            0.001f,
        )
    }

    /**
     * ★★ 图像坐标系必须按 rotation 折算 —— 这是 2026-09-20 实测踩到的坑：
     *
     * 实测 `proxy=1280x720 rotation=90` 时 `InputImage.getWidth/Height` **仍报 1280x720**
     * （旋转前，不交换），而 Google 官方文档明确 bbox 是在**旋转后**的 buffer 坐标系里。
     * 两者不一致 ⇒ 直接用 input 尺寸会让竖屏下生效区域转 90° 错位，
     * 而且**不崩不报错、单测全绿**，只有真机才看得出来。
     */
    @Test
    fun `竖屏 rotation90 与 270 必须交换图像宽高`() {
        assertEquals(
            "rotation=90 应换成 720x1280",
            720f to 1280f, ViewfinderHit.imageSpaceOf(1280, 720, 90),
        )
        assertEquals(
            "rotation=270 应换成 720x1280",
            720f to 1280f, ViewfinderHit.imageSpaceOf(1280, 720, 270),
        )
    }

    @Test
    fun `横屏 rotation0 与 180 保持原尺寸`() {
        assertEquals(1280f to 720f, ViewfinderHit.imageSpaceOf(1280, 720, 0))
        assertEquals(1280f to 720f, ViewfinderHit.imageSpaceOf(1280, 720, 180))
    }

    /** 同宽高比：无 letterbox，换算退化成等比缩放。 */
    @Test
    fun `同宽高比 视图中心映射到图像中心`() {
        val (x, y) = ViewfinderHit.viewToImage(
            vx = 500f, vy = 1000f,
            viewW = 1000f, viewH = 2000f,
            imgW = 500f, imgH = 1000f,
        )
        assertEquals(250f, x, 0.01f)
        assertEquals(500f, y, 0.01f)
    }

    /** 图像比视图"高"（FILL_CENTER 会横向铺满、纵向溢出并居中裁剪）。 */
    @Test
    fun `图像偏高时纵向居中裁剪 中心仍映射中心`() {
        val (x, y) = ViewfinderHit.viewToImage(
            vx = 500f, vy = 500f,
            viewW = 1000f, viewH = 1000f,
            imgW = 500f, imgH = 1000f,
        )
        assertEquals(250f, x, 0.01f)
        assertEquals(500f, y, 0.01f)

        // 互为逆运算
        val (vx, vy) = ViewfinderHit.imageToView(
            ix = 250f, iy = 500f,
            viewW = 1000f, viewH = 1000f,
            imgW = 500f, imgH = 1000f,
        )
        assertEquals(500f, vx, 0.01f)
        assertEquals(500f, vy, 0.01f)
    }

    /** 取景框内（框心）→ 命中。注意：cx/cy 为 ML Kit 的图像坐标。 */
    @Test
    fun `取景框框心命中`() {
        // 视图 1000x2000 / 图像 500x1000（同比例，scale=2）
        // 框 400 见方，视图左沿 300、上沿 718.4，框心视图坐标 (500, 918.4)
        // 图像中对应的框心应为 (250, 459.2)
        val hit = ViewfinderHit.hit(
            cx = 250f, cy = 459.2f,
            viewW = 1000f, viewH = 2000f, imgW = 500f, imgH = 1000f,
            frameLeft = 300f, frameTop = 718.4f, frameSize = 400f,
        )
        assertTrue("框心必须命中", hit)
    }

    /** 真实 1080x2400 屏幕真机模型回归：中心瞄准条码必须命中（曾因双重转换把框心判定为框外）。 */
    @Test
    fun `真实1080x2400真机模型中心条码命中`() {
        val viewW = 1080f
        val viewH = 2400f
        val imgW = 720f
        val imgH = 1280f
        val frameSize = 649f
        val frameLeft = (viewW - frameSize) / 2f // 215.5
        val frameTop = (viewH - frameSize) * 0.449f // 786.19
        // 视觉上对准取景框中心的条码，在图像中的实际坐标：
        val (imgCx, imgCy) = ViewfinderHit.viewToImage(
            vx = frameLeft + frameSize / 2f,
            vy = frameTop + frameSize / 2f,
            viewW = viewW, viewH = viewH, imgW = imgW, imgH = imgH,
        )
        val hit = ViewfinderHit.hit(
            cx = imgCx, cy = imgCy,
            viewW = viewW, viewH = viewH, imgW = imgW, imgH = imgH,
            frameLeft = frameLeft, frameTop = frameTop, frameSize = frameSize,
        )
        assertTrue("真机对准取景框中央的条码必须命中", hit)
    }

    /** ★ 核心回归：取景框**外**的码一律不算命中（这正是"邻货被顺进来"的入口）。 */
    @Test
    fun `取景框外的码不算命中`() {
        fun miss(cx: Float, cy: Float, label: String) {
            val hit = ViewfinderHit.hit(
                cx = cx, cy = cy,
                viewW = 1000f, viewH = 2000f, imgW = 500f, imgH = 1000f,
                frameLeft = 300f, frameTop = 718.4f, frameSize = 400f,
            )
            assertFalse("$label 不该命中（框外）", hit)
        }
        // 图像中框范围为 X=[150, 350], Y=[359.2, 559.2]
        miss(50f, 50f, "左上角远处")
        miss(450f, 50f, "右上角远处")
        miss(50f, 950f, "左下角远处")
        miss(250f, 50f, "正上方框外")
        miss(250f, 600f, "正下方框外")
        miss(100f, 459.2f, "正左方框外")
    }

    /** 边界：恰好落在框沿上算命中（避免"差一像素就丢"的抖动）。 */
    @Test
    fun `框沿上的点算命中`() {
        // 图像中框左上角为 (150, 359.2)
        val hit = ViewfinderHit.hit(
            cx = 150f, cy = 359.2f,
            viewW = 1000f, viewH = 2000f, imgW = 500f, imgH = 1000f,
            frameLeft = 300f, frameTop = 718.4f, frameSize = 400f,
        )
        assertTrue("框左上角沿必须命中", hit)
    }

    // ---------------- 命中区外扩（2026-09-26 老板实测「要对准半天」） ----------------

    @Test
    fun `外扩后 框沿外侧一点也命中`() {
        // 不外扩：图像 (140, 349.2) 在框左沿 (150) 外 10px → 不命中
        val strict = ViewfinderHit.hit(
            cx = 140f, cy = 349.2f,
            viewW = 1000f, viewH = 2000f, imgW = 500f, imgH = 1000f,
            frameLeft = 300f, frameTop = 718.4f, frameSize = 400f,
        )
        assertFalse("不外扩时框外不命中", strict)
        // 外扩 25 视图 px（=12.5 图像 px）：同一中心 → 命中（好对准）
        val inflated = ViewfinderHit.hit(
            cx = 140f, cy = 349.2f,
            viewW = 1000f, viewH = 2000f, imgW = 500f, imgH = 1000f,
            frameLeft = 300f, frameTop = 718.4f, frameSize = 400f,
            inflatePx = 25f,
        )
        assertTrue("外扩后框沿附近的条码应命中", inflated)
    }

    @Test
    fun `外扩一整框之外的码仍不命中 防串扫底线不破`() {
        // 真机模型：1080x2400 屏、框 649px、外扩 30%（195px）
        val viewW = 1080f; val viewH = 2400f
        val imgW = 720f; val imgH = 1280f
        val frameSize = 649f
        val frameLeft = (viewW - frameSize) / 2f   // 215.5
        val frameTop = (viewH - frameSize) * 0.449f // ≈786
        val inflate = frameSize * 0.30f
        fun centerImageOf(vx: Float, vy: Float): Pair<Float, Float> =
            ViewfinderHit.viewToImage(vx, vy, viewW, viewH, imgW, imgH)

        // 邻货中心在一整框宽之外（水平方向）→ 仍不命中（"一堆货摆在一起"的老底线）
        val (nx, ny) = centerImageOf(frameLeft - frameSize, frameTop + frameSize / 2f)
        assertFalse(
            "一整框之外的邻货不得命中",
            ViewfinderHit.hit(nx, ny, viewW, viewH, imgW, imgH, frameLeft, frameTop, frameSize, inflate),
        )
        // 框中心 → 命中（回归主路径）
        val (cx0, cy0) = centerImageOf(frameLeft + frameSize / 2f, frameTop + frameSize / 2f)
        assertTrue(
            "框中心必须命中",
            ViewfinderHit.hit(cx0, cy0, viewW, viewH, imgW, imgH, frameLeft, frameTop, frameSize, inflate),
        )
        // 外扩边缘上（框左沿外 30%）→ 命中（好对准的关键增量）
        val (ex, ey) = centerImageOf(frameLeft - inflate, frameTop + frameSize / 2f)
        assertTrue(
            "外扩边缘应命中",
            ViewfinderHit.hit(ex, ey, viewW, viewH, imgW, imgH, frameLeft, frameTop, frameSize, inflate),
        )
    }

    /** 尺寸非正（尚未布局完 / 空帧）：原样返回，调用方据此**不过滤**，避免把有效识别全丢掉。 */
    @Test
    fun `尺寸非正时原样返回不缩放`() {
        val (x, y) = ViewfinderHit.viewToImage(
            vx = 123f, vy = 456f,
            viewW = 0f, viewH = 0f, imgW = 0f, imgH = 0f,
        )
        assertEquals(123f, x, 0.01f)
        assertEquals(456f, y, 0.01f)
    }
}
