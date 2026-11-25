package com.retaildemo.v2.ui.pages

/**
 * 取景框命中判定（纯 Kotlin 无 Android 依赖，便于 JVM 单测）。
 *
 * ★ 为什么要有这个（2026-09-20 老板实报「一堆货摆在一起都给扫上了」）：
 *   CameraX 送进分析器的帧是**整幅画面**（1280×720），而屏幕上那个 236×236 取景框
 *   **此前纯粹是画给人看的** —— 全仓 `setViewPort` / `cropRect` / `RectF` 零命中，
 *   识别范围对它没有任何约束。于是取景框外、相机视野内的所有条码都会被逐帧识别
 *   （`barcodes.firstOrNull()` 每帧取一个，30fps 连着吃），邻货的码就被"顺"进来了。
 *
 * 这里把取景框矩形从**视图坐标**换算到**图像坐标**，再判断条码中心是否落在框内。
 * 换算按 PreviewView 的 `FILL_CENTER` 语义：等比缩放铺满视图 + 居中裁剪。
 *
 * 选这个方案而不是 CameraX 的 `setViewPort`：不需要在布局完成后重绑相机
 * （重绑会打断已有扫码会话），且换算逻辑是纯函数、可单测。
 */
object ViewfinderHit {

    /**
     * 供坐标换算使用的**图像尺寸**。
     *
     * ★★ 为什么需要它（2026-09-20 实测踩坑，差点留下静默错位）：
     *   实测 `proxy=1280x720 rotation=90` 时，`InputImage.getWidth()/getHeight()`
     *   **仍返回 1280x720（旋转前）**，并不随 rotation 交换。
     *   而 Google 官方《ML Kit spatial and coordinate mapping》明确写着：
     *   「ML Kit results, bounding boxes, are relative to the **rotated buffer**」。
     *   两者**不一致** —— 所以绝不能直接用 `input.width/height` 当图像坐标系，
     *   必须自己按 rotation 交换。
     *
     *   用错的后果：竖屏（rotation 90/270，本页 `screenOrientation="portrait"` 恒成立）
     *   整个生效区域转 90° 错位 —— **不崩、不报错、单测全绿**，只有真机才看得出来。
     *
     * @return `(width, height)`，已按 rotation 折算为**正立**方向
     */
    fun imageSpaceOf(rawW: Int, rawH: Int, rotationDegrees: Int): Pair<Float, Float> =
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            rawH.toFloat() to rawW.toFloat()
        } else {
            rawW.toFloat() to rawH.toFloat()
        }

    /**
     * 交接台取景框上沿（**定高堆栈**之和，单位随入参）= 状态栏 + 顶栏 + 间隙 + 单据条 + 间隙。
     *
     * 画布 M 帧（`_reports/redraw/gen_scan_handover_spec.py`）的锚点：
     * `32 + 48 + 4 + 44 + 12 = 140`。
     *
     * 与 [frameLeftTop] 配套、职责分开：**本函数给上沿，那个函数给左上角**。
     * 抽成纯函数是为了让「画布那串加法」在代码里只有一个出处，并能被单测钉死 ——
     * 否则画布改了、代码没改，两边都「看起来对」。
     *
     * `billBarPx` 传 0 表示非交接台（没有单据条那一行）。
     */
    fun handoverFrameTop(
        statusBarPx: Float,
        topBarPx: Float,
        billBarGapPx: Float,
        billBarPx: Float,
        frameGapPx: Float,
    ): Float = statusBarPx + topBarPx + billBarGapPx + billBarPx + frameGapPx

    /**
     * 取景框在**视图坐标**下的左上角 —— 画框与算识别区域**必须都调这一个函数**。
     *
     * 以前两处各写一套算式（`Viewfinder` 里画、`insideViewfinder` 里算），靠注释约定"同源"；
     * 一旦有人只改一边，「看到的框」和「生效的框」就会错位，而且**不崩不报错、单测全绿**。
     * 抽成一个函数后，同源是**结构性**的，不再依赖纪律。
     *
     * 横向恒为**水平居中**；纵向是**绝对上沿**（不是屏幕比例）。
     *
     * ★ 为什么纵向由「比例」改成「绝对」（2026-09-26）：
     *   画布 M 帧把取景框上移到了顶栏 + 单据条之下（y=108/140），框上方从此有了**定高内容**。
     *   原来的 `(viewH - frameSize) * ratio` 是拿「屏幕剩余高度」算位置 —— 一旦顶部多了
     *   一条 44dp 的单据条，同比例在不同屏高上会漂：390×844 与 393×851 上各落一处，
     *   而设计给的是**绝对锚点**（顶栏底 + 4 + 单据条 44 + 12）。
     *   改成绝对上沿后，「框在单据条之下多少」是恒定的，与屏高无关。
     *
     * @param topPx 取景框上沿（视图 px）—— 由顶部 chrome 实测高度推出，见
     *   `ScannerActivity.frameTopPx`（画框与算识别区域读同一个值）。
     */
    fun frameLeftTop(
        viewW: Float,
        frameSize: Float,
        topPx: Float,
    ): Pair<Float, Float> = ((viewW - frameSize) / 2f) to topPx

    /**
     * 视图坐标 → 图像坐标（FILL_CENTER）。
     *
     * 记图像在视图里被等比放大 [scale] 倍后居中摆放，左上角偏移 ([dx], [dy])，
     * 则视图点 `(vx, vy)` 对应图像点 `((vx - dx) / scale, (vy - dy) / scale)`。
     * 任一尺寸非正（尚未布局完 / 空帧）时原样返回，调用方据此不过滤。
     */
    fun viewToImage(
        vx: Float, vy: Float,
        viewW: Float, viewH: Float,
        imgW: Float, imgH: Float,
    ): Pair<Float, Float> {
        if (viewW <= 0f || viewH <= 0f || imgW <= 0f || imgH <= 0f) return vx to vy
        val scale = maxOf(viewW / imgW, viewH / imgH)
        val dx = (viewW - imgW * scale) / 2f
        val dy = (viewH - imgH * scale) / 2f
        return (vx - dx) / scale to (vy - dy) / scale
    }

    /**
     * 图像坐标 → 视图坐标（FILL_CENTER 的逆运算）。
     *
     * ML Kit 识别出的条码 `boundingBox` 在旋转后的图像缓冲区坐标系里，
     * 换算成屏幕视图坐标后，直接与屏幕上绘制的取景框矩形比较。
     */
    fun imageToView(
        ix: Float, iy: Float,
        viewW: Float, viewH: Float,
        imgW: Float, imgH: Float,
    ): Pair<Float, Float> {
        if (viewW <= 0f || viewH <= 0f || imgW <= 0f || imgH <= 0f) return ix to iy
        val scale = maxOf(viewW / imgW, viewH / imgH)
        val dx = (viewW - imgW * scale) / 2f
        val dy = (viewH - imgH * scale) / 2f
        return (ix * scale + dx) to (iy * scale + dy)
    }

    /**
     * 条码中心 `(cx, cy)`（图像坐标，来自 ML Kit 的 Barcode.boundingBox）是否落在取景框内。
     *
     * 换算方式：将图像坐标换算到视图坐标，再与**外扩后的**取景框比较。
     * 视口未就绪时原样放行，避免首帧全丢。
     *
     * @param cx        条码中心 X（图像坐标）
     * @param cy        条码中心 Y（图像坐标）
     * @param frameLeft 取景框左沿（视图坐标）
     * @param frameTop  取景框上沿（视图坐标）
     * @param frameSize 取景框边长（视图坐标，正方形）
     * @param inflatePx 命中区四周外扩量（视图 px）。0=严格按画出的框；
     *                  老板 2026-09-26 实测「要对准半天才能扫出来」——画的框太小导致
     *                  有效识别区窄，按框的 30% 外扩（画的框不动，只放宽判定），
     *                  同时仍把一整框之外的邻货挡在外面（防串扫的老底线不破）。
     */
    fun hit(
        cx: Float, cy: Float,
        viewW: Float, viewH: Float,
        imgW: Float, imgH: Float,
        frameLeft: Float, frameTop: Float, frameSize: Float,
        inflatePx: Float = 0f,
    ): Boolean {
        if (viewW <= 0f || viewH <= 0f || imgW <= 0f || imgH <= 0f) return true
        val (vx, vy) = imageToView(cx, cy, viewW, viewH, imgW, imgH)
        val left = frameLeft - inflatePx
        val top = frameTop - inflatePx
        val right = frameLeft + frameSize + inflatePx
        val bottom = frameTop + frameSize + inflatePx
        return vx >= left && vx <= right && vy >= top && vy <= bottom
    }
}
