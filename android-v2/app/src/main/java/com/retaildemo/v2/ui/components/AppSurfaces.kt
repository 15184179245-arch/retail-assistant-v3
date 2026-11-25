package com.retaildemo.v2.ui.components

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.Elevation
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.SxElevation

/** 135° 渐变（顶栏/统计卡统一用）。 */
fun brandGradient(from: Color = Design.TopBarGradientFrom, to: Color = Design.TopBarGradientTo): Brush =
    Brush.linearGradient(colors = listOf(from, to), start = Offset.Zero, end = Offset.Infinite)

/**
 * 渐变顶栏：52dp + 沉浸状态栏 + 18sp SemiBold 标题。
 */
@Composable
fun GradientTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(brandGradient())
            .statusBarsPadding()
            .height(Design.TopBarHeight.dp)
            .padding(horizontal = 4.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                )
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        actions?.invoke(this)
    }
}

/**
 * 按压缩放反馈（Gemini 规格：按下 0.97 / 100ms，松开 150ms）
 * **+ 光影交互**：按下瞬间元件顶光增强（"吃光"），松手渐隐。
 *
 * 光影与形变、声、震**同帧**发生（都挂在同一个 [interactionSource] 上），
 * 构成本 APP 的直接操纵反馈语言——店里戴手套、眼睛在看货架，
 * 余光里那一下"光"和形变就是"我按到了"的确认。
 *
 * 因为所有 [com.retaildemo.v2.ui.components.directTap] 都会走到这里，
 * 所以**改这一个函数即可让全仓可点元件获得按压光影**，无需改任何调用点。
 */
fun Modifier.pressScale(interactionSource: MutableInteractionSource): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Design.PressScaleDown else 1f,
        animationSpec = tween(durationMillis = if (pressed) Design.PressMs else Design.ReleaseMs),
        label = "pressScale",
    )
    val glow by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(durationMillis = if (pressed) Design.PressMs else Design.ReleaseMs),
        label = "pressGlow",
    )
    this
        .drawWithContent {
            drawContent()
            if (glow > 0.01f) {
                // 用 drawRoundRect(radius=0) 而非 drawRect：本文件 ghostCell 已验证
                // drawRoundRect 在此 Compose 版本可直接用（drawRect 需额外 import，此处避免）
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.28f * glow),
                            Color.Transparent,
                        ),
                    ),
                    cornerRadius = CornerRadius(0f, 0f),
                )
            }
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
}

// =====================================================================================
// SEIS v2.0 美学原语（tokens v7）——哑光工装材质三件套
//
// 设计主张：抬升靠「光影」不靠「染色」。旧版整机蒙着一层橙灰，根因是 M3 tonal tint
// 按 elevation 把品牌色混进每个卡片底色（Theme.kt 已置 surfaceTint 透明）。
// 关掉染色之后，面与面的层级必须由下面三件事重新建立：
//   ① 环境遮蔽柔影（大扩散极淡）+ 主光投影（收拢略深）= 双层微立体
//   ② 0.5dp 顶光接边（磨砂塑料件的高光棱线）
//   ③ 极细中性地平线，而不是粗边框
// =====================================================================================

// =====================================================================================
// v9 阴影收口：全仓阴影只走 SxElevation（L0–L4），实现在此。
// 设计权威＝Ardot 画布「M12 阴影等级规范」；等级定义、七条纪律、与设计稿的已知差异
// 都写在 ui/theme/Elevation.kt 文件头——改影深前先读它。
// =====================================================================================

// =====================================================================================
// 阴影渲染性能收口（2026-09-19）：光栅化一次进位图缓存，逐帧只贴图。
//
// 旧实现每次 draw 都 new Paint + BlurMaskFilter 并逐层 CPU 高斯光栅化。全仓 116 处
// toolSurface/sxElevation/glassSurface，切页转场、滚动出列、按压变色每一笔重绘都在
// 重复光栅化——低端机上直接掉帧（老板反馈「来回切卡卡的」的主凶之一）。
// 阴影只取决于「等级 + 贴边 + 尺寸 + 圆角 + 密度」，与内容无关：同尺寸的列表行
// 只在首帧光栅化一次，之后每帧是 GPU 贴图。LRU + 字节预算防内存失控；
// 超限（超大面/创建失败）自动回落到旧的逐层直绘，视觉完全一致。
// =====================================================================================
private const val SHADOW_BITMAP_MAX_DIM = 2600
private const val SHADOW_ENTRY_MAX_BYTES = 12L * 1024 * 1024
private const val SHADOW_CACHE_BUDGET_BYTES = 32L * 1024 * 1024

private class CachedShadow(val bitmap: android.graphics.Bitmap, val padPx: Float)

private object ShadowBitmapCache {
    private val cache = object : LinkedHashMap<String, CachedShadow>(16, 0.75f, true) {}
    private var usedBytes = 0L

    fun get(
        level: com.retaildemo.v2.ui.theme.Elevation,
        docked: Boolean,
        widthPx: Float,
        heightPx: Float,
        cornerRadiusPx: Float,
        density: Float,
    ): CachedShadow? {
        if (widthPx <= 0f || heightPx <= 0f) return null
        val key = "${level.id}|$docked|${widthPx.toInt()}x${heightPx.toInt()}|${cornerRadiusPx.toInt()}|${(density * 100).toInt()}"
        synchronized(cache) { cache[key] }?.let { return it }

        val layers = level.layersFor(docked)
        if (layers.isEmpty()) return null

        // 高斯可见尾巴 ≈ 1.73×radius，取 2× 留余量；再叠加位移与扩散
        var pad = 4f
        for (layer in layers) {
            val extent = layer.blur * 2f * density +
                kotlin.math.abs(layer.offsetY * density) +
                kotlin.math.abs(layer.spread * density)
            if (extent > pad) pad = extent
        }
        val bw = (widthPx + pad * 2f).toInt().coerceAtLeast(1)
        val bh = (heightPx + pad * 2f).toInt().coerceAtLeast(1)
        if (bw > SHADOW_BITMAP_MAX_DIM || bh > SHADOW_BITMAP_MAX_DIM) return null
        val bytes = bw.toLong() * bh.toLong() * 4L
        if (bytes > SHADOW_ENTRY_MAX_BYTES) return null

        val bmp = try {
            android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            return null
        }
        val canvas = android.graphics.Canvas(bmp)
        canvas.translate(pad, pad)
        val ink = Design.ShadowInk
        for (layer in layers) {
            val blurPx = layer.blur * density
            val offsetYPx = layer.offsetY * density
            val spreadPx = layer.spread * density
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = ink.copy(alpha = layer.alpha).toArgb()
                if (blurPx > 0f) {
                    maskFilter = android.graphics.BlurMaskFilter(blurPx, android.graphics.BlurMaskFilter.Blur.NORMAL)
                }
            }
            val left = -spreadPx
            val top = offsetYPx - spreadPx
            val right = widthPx + spreadPx
            val bottom = heightPx + offsetYPx + spreadPx
            val rx = (cornerRadiusPx + spreadPx).coerceAtLeast(0f)
            canvas.drawRoundRect(left, top, right, bottom, rx, rx, paint)
        }

        val entry = CachedShadow(bmp, pad)
        synchronized(cache) {
            cache[key] = entry
            usedBytes += bytes
            val iter = cache.entries.iterator()
            while (usedBytes > SHADOW_CACHE_BUDGET_BYTES && cache.size > 1 && iter.hasNext()) {
                val eldest = iter.next()
                usedBytes -= eldest.value.bitmap.allocationByteCount
                iter.remove()
            }
        }
        return entry
    }
}

/** 逐层直绘的老路：位图缓存超限/失败时的回落路径（视觉一致，仅慢）。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawShadowLayersDirect(
    level: com.retaildemo.v2.ui.theme.Elevation,
    docked: Boolean,
    cornerRadiusPx: Float,
) {
    val layers = level.layersFor(docked)
    if (layers.isEmpty()) return
    drawIntoCanvas { canvas ->
        val nativeCanvas = canvas.nativeCanvas
        val ink = Design.ShadowInk
        val density = this.density
        for (layer in layers) {
            val blurPx = layer.blur * density
            val offsetYPx = layer.offsetY * density
            val spreadPx = layer.spread * density

            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = ink.copy(alpha = layer.alpha).toArgb()
                if (blurPx > 0f) {
                    maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
                }
            }

            val left = -spreadPx
            val top = offsetYPx - spreadPx
            val right = size.width + spreadPx
            val bottom = size.height + offsetYPx + spreadPx
            val rx = (cornerRadiusPx + spreadPx).coerceAtLeast(0f)

            nativeCanvas.drawRoundRect(left, top, right, bottom, rx, rx, paint)
        }
    }
}

/**
 * 按设计稿的阴影等级抬升元素。**屏上只许调这一个函数**，不许就地写影深。
 *
 * 采用原生 DrawScope 与 Android BlurMaskFilter 真实绘制 M12 多层物理阴影
 * （首帧光栅化进 [ShadowBitmapCache]，此后逐帧贴图——见上「性能收口」注释）：
 *   · 层数与每层透明度完全对齐 M12 规范
 *   · 精确还原 offsetY 下沉、高斯模糊 blur 与负扩散 spread 雾化收拢
 *   · 搭配顶光 1px 高光内倒角 hairlineEdge，还原工业压铸件微立体质感
 *
 * @param level  L0–L4，语义与用法见 [SxElevation]
 * @param docked 贴边型（下沿贴屏：九宫格键盘 / 抽屉）传 true，L4 影向上投
 * @param cornerRadius 圆角大小，默认取 level.radius.dp
 */
fun Modifier.sxElevation(
    level: Elevation,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(level.radius.dp),
    background: Color = Design.CardSurface,
    docked: Boolean = false,
    hairline: Boolean = level.topLightAlpha > 0f,
    cornerRadius: Dp = level.radius.dp,
): Modifier = this
    .drawBehind {
        val cached = ShadowBitmapCache.get(
            level = level,
            docked = docked,
            widthPx = size.width,
            heightPx = size.height,
            cornerRadiusPx = cornerRadius.toPx(),
            density = density,
        )
        if (cached != null) {
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawBitmap(cached.bitmap, -cached.padPx, -cached.padPx, null)
            }
        } else {
            drawShadowLayersDirect(level, docked, cornerRadius.toPx())
        }
    }
    .clip(shape)
    .background(background)
    .then(if (hairline) Modifier.hairlineEdge(shape, level.topLightAlpha) else Modifier)

/**
 * 磨砂工作面：一张「精加工磨砂塑料/压铸件」质感的卡片。
 *
 * 抬升一律取设计稿级别（默认 L2 内容卡）；顶光强度也来自该级（不再有独立的固定高光值）。
 */
fun Modifier.toolSurface(
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(Design.CardRadius.dp),
    level: Elevation = SxElevation.L2,
    background: Color = Design.CardSurface,
    hairline: Boolean = true,
): Modifier = sxElevation(level = level, shape = shape, background = background, hairline = hairline)

/**
 * 56dp 物理触控靶心保底（五金工业仓储场景手套防误触刚性标准，升级自 48dp）。
 * 视觉小元素（如 24dp 图标、32dp 胶囊徽标、步进器小按钮）必须通过此修饰符进行隐式物理命中区域扩展。
 */
fun Modifier.touchTarget56(): Modifier = this.defaultMinSize(
    minWidth = Design.MinTouchTarget.dp,
    minHeight = Design.MinTouchTarget.dp,
)

/**
 * 毛玻璃面（玻璃拟物）——与 [toolSurface]（哑光工装面）分工：
 *   - [toolSurface]：贴地卡片，实色哑光，靠双层柔影 + 顶光接边建立层级
 *   - [glassSurface]：**浮在内容之上**的条/片（底部导航、抽屉、浮层），下面有滚动内容经过，
 *     用「半透明底 + 顶光接边 + 双层柔影」做出磨砂玻璃压在内容上的质感。
 *
 * 可读性纪律（SEIS 公理 A4「蒙版保底」）：alpha 不低于 0.82，且玻璃面上若有文字，
 * 必须保证对比度仍达 WCAG AA——宁可调高不透明度，也不要为了「透」牺牲可读性。
 * 本 APP 是店内强光、油污手套场景，玻璃只做**轻微**通透。
 */
fun Modifier.glassSurface(
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(Design.ShapeXl.dp),
    alpha: Float = 0.95f,
    tint: Color = Design.CardSurface,
    level: Elevation = SxElevation.L4,
    docked: Boolean = false,
    cornerRadius: Dp = Design.ShapeXl.dp,
): Modifier = this
    .drawBehind {
        val cached = ShadowBitmapCache.get(
            level = level,
            docked = docked,
            widthPx = size.width,
            heightPx = size.height,
            cornerRadiusPx = cornerRadius.toPx(),
            density = density,
        )
        if (cached != null) {
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawBitmap(cached.bitmap, -cached.padPx, -cached.padPx, null)
            }
        } else {
            drawShadowLayersDirect(level, docked, cornerRadius.toPx())
        }
    }
    .clip(shape)
    .background(tint.copy(alpha = alpha))
    .hairlineEdge(shape, level.topLightAlpha)

/**
 * 拟物凸起（Neumorphic 轻拟物）：同一色系下的「压铸件凸起」——上沿亮、下沿暗。
 *
 * 只用于**极少数需要被"按"的元素**（主行动按钮、数量步进器），不要铺开用：
 * 大面积拟物凸起会让界面显旧，本项目主体仍是哑光工装面（[toolSurface]）。
 */
fun Modifier.castRaised(
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(Design.ButtonRadius.dp),
    fill: Color = Design.CardSurface,
    level: Elevation = SxElevation.L2,
): Modifier = composed {
    val light = Design.HairlineLight
    val shadow = Design.HairlineShadow
    var base = this
    for (la in level.layersFor(false)) {
        val c = Design.ShadowInk.copy(alpha = la.alpha)
        base = base.shadow(
            elevation = (la.blur / 2f).dp,
            shape = shape,
            clip = false,
            ambientColor = c,
            spotColor = c,
        )
    }
    base
        .clip(shape)
        .background(fill)
        .border(width = 0.8.dp, brush = Brush.verticalGradient(listOf(light, fill, shadow)), shape = shape)
}

/**
 * 顶光接边：上沿一线白高光，下沿一线冷影，中间维持极细中性边。
 *
 * @param topLightAlpha 上沿高光强度——**来自阴影等级**（L1 0.60 / L2 0.85 / L3・L4 0.90），
 *        不再用固定值，否则同级的顶光会各不相同。
 */
fun Modifier.hairlineEdge(
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(Design.CardRadius.dp),
    topLightAlpha: Float = 0.55f,
): Modifier = this.border(
    width = 0.8.dp,
    brush = Brush.verticalGradient(
        listOf(Design.HairlineLight.copy(alpha = topLightAlpha), Design.CardStroke, Design.HairlineShadow),
    ),
    shape = shape,
)

/**
 * 图上文字保底蒙版（tokens v7 公理 A4）。
 *
 * 普通的两点渐变在亮图上会把文字冲成一团白雾；这里用带「平台段」的四点渐变——
 * 文字落在平台段上，不透明度钉在 [Design.ScrimPlateau]，任何亮度的图都能读清。
 * 墨色用 [Design.ScrimInk]（冷墨蓝黑）而非纯黑：纯黑在 OLED 上割裂感极强。
 */
fun scrimBottomBrush(ink: Color = Design.ScrimInk): Brush = Brush.verticalGradient(
    0.00f to Color.Transparent,
    0.34f to ink.copy(alpha = Design.ScrimFade),
    0.58f to ink.copy(alpha = Design.ScrimPlateau * 0.82f),
    1.00f to ink.copy(alpha = Design.ScrimPlateau),
)

/**
 * 幽灵格：虚线细描边的空位容器。
 *
 * 用途=货架的「＋加格」等「这里还空着，可以放东西」的位置。用**同一套圆角与容器语言**
 * 与实体格并置（而不是实边框的线框图），视觉上像货架上空着的那个格子本身。
 */
fun Modifier.ghostCell(
    radius: androidx.compose.ui.unit.Dp = Design.InputRadius.dp,
    color: Color = Design.CardStrokeStrong,
    strokeWidth: androidx.compose.ui.unit.Dp = 1.2.dp,
    dash: Float = 7f,
    gap: Float = 5f,
): Modifier = this.drawBehind {
    val w = strokeWidth.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(w / 2f, w / 2f),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(radius.toPx() - w / 2f),
        style = Stroke(
            width = w,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap), 0f),
        ),
    )
}

/**
 * 列表瀑布入场（Gemini 规格：淡入+上移 16dp，220ms，间隔 35ms，Ease-Out）。
 *
 * 2026-09-19 性能收口：状态改 [androidx.compose.runtime.saveable.rememberSaveable]——
 * 配合底部导航的 saveState/restoreState，同一张卡在 Tab 来回切换时**不再重播**入场动画
 * （旧实现每次切回页面整列重新淡入，与 240ms 页面转场叠加，是「来回切卡卡的」主凶之一）；
 * 只在进程冷启动后的首次进入播一次，语义不变。
 */
fun Modifier.staggeredAppear(index: Int): Modifier = composed {
    var shown by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(index.coerceAtMost(12) * Design.ListAnimStaggerMs.toLong())
        shown = true
    }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(Design.ListAnimMs, easing = CubicBezierEasing(0.2f, 0f, 0.2f, 1f)),
        label = "stagger",
    )
    graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * 16.dp.toPx()
    }
}


/**
 * 主 CTA 品牌底色（**画布定稿 = 实心 primary，非渐变**）。
 *
 * 2026-09-22 TASK-01C/01D 按司令部落地口径改：色值不自行拍，一律回画布取实测——
 * 节点 `38:742` 的「CTA-保存挂账」实测为 `SOLID #FF6A00` 且已绑 `SEIS v11` 变量，
 * 夜视屏 `38:768` 的「CTA-完成扫码」同为 `#FF6A00`，**整页线性渐变 0 处**
 * （取证：figma-local 任务 `ea256dd9`，2026-09-22 03:11）。
 * 改前这里写着裸的 8 位十六进制渐变（FF6A00 → E55F00），既是 TASK-03B 审计的
 * E1 唯一「本次新引入」红线，也让 5 个主按钮与画布不一致。
 *
 * ⚠️ 函数名仍叫 `ctaGradient` 是历史包袱（5 个调用点：Workbench/Shelf/Onboarding/
 *    QrOverlay/NewBillKeypad）。改名属独立一张卡，本卡按「最小改动」不动调用点。
 *
 * 用法：Button(colors = buttonColors(containerColor = Color.Transparent),
 *        modifier = Modifier.ctaGradient(shape)) —— containerColor 必须透明。
 */
fun Modifier.ctaGradient(
    shape: androidx.compose.ui.graphics.Shape,
    enabled: Boolean = true,
): Modifier = if (!enabled) this else this.background(Primary, shape)
