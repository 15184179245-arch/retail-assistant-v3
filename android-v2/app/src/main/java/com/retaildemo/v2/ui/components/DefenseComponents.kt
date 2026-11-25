package com.retaildemo.v2.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.SxElevation

/**
 * 业务工程防御组件集（DefenseComponents.kt · SEIS v2.0）。
 *
 * 针对五金机电真实工业现场数据与渲染天花板构筑的三大防线：
 * 1. [AdaptiveProductTitle]：40字长品名防穿卡组件（maxLines=2 + 弹性容器）
 * 2. [PriceBaselineRow]：价格行基线锁定组件（锁定 32dp/28dp，彻底关死系统默认行距）
 * 3. [StateTrioHost]：状态三件套标准容器（正常态、空态 EmptyState、骨架屏 Shimmer、离线条 OfflineBanner）
 */

// =====================================================================================
// 防线 1: 40字超长品名防穿卡自适应标题组件
// =====================================================================================

/**
 * 自适应商品标题：强制锁定 `maxLines = 2` 与 `TextOverflow.Ellipsis`。
 *
 * 工业现场存在 40 字超长商品名称（例如“国标304不锈钢内六角圆柱头螺钉 M8*30 加硬发黑特供”）。
 * 若无保护，Text 会无限制拉长并撑破列表卡片容器。
 *
 * @param title      商品标题文本
 * @param modifier   修饰符
 * @param maxLines   最大允许行数（默认 2 行）
 * @param color      文字颜色（默认 TextMain）
 * @param style      文字样式
 */
@Composable
fun AdaptiveProductTitle(
    title: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    color: Color = Design.TextMain,
    style: TextStyle = LocalTextStyle.current.copy(
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 20.sp,
    ),
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = title,
            color = color,
            style = style,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// =====================================================================================
// 防线 2: 价格行高度锁定与零膨胀基线组件
// =====================================================================================

/**
 * 价格行基线锁定容器：固定高度 32.dp（或 28.dp）。
 *
 * 显式配置 `PlatformTextStyle(includeFontPadding = false)` 与 `LineHeightStyle`，
 * 彻底消除 Android 系统默认字体上下行距造成的卡片高度膨胀（杜绝 12px 膨胀并挤出底栏）。
 *
 * @param currency     货币符号（默认 "¥"）
 * @param integerPart  整数部分金额字符串（例如 "128"）
 * @param decimalPart  小数部分金额字符串（例如 ".50"，可为空）
 * @param height       固定锁定高度（推荐 32.dp 或 28.dp）
 * @param color        价格主色（默认警示正红 Design.Danger）
 * @param modifier     修饰符
 */
@Composable
fun PriceBaselineRow(
    integerPart: String,
    currency: String = "¥",
    decimalPart: String = "",
    height: Dp = 32.dp,
    color: Color = Design.Danger,
    modifier: Modifier = Modifier,
) {
    // 强制关死 includeFontPadding 并锁定行高
    val baseStyle = LocalTextStyle.current.copy(
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Bottom,
            trim = LineHeightStyle.Trim.Both,
        ),
    )

    Row(
        modifier = modifier
            .height(height),
        verticalAlignment = Alignment.Bottom,
    ) {
        // 货币符号（14sp）
        Text(
            text = currency,
            color = color,
            style = baseStyle.copy(
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 14.sp,
            ),
            modifier = Modifier.padding(bottom = 2.dp),
        )

        Spacer(Modifier.width(2.dp))

        // 整数部分（28sp，加粗放大）
        Text(
            text = integerPart,
            color = color,
            style = baseStyle.copy(
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 28.sp,
            ),
        )

        // 小数部分（16sp）
        if (decimalPart.isNotEmpty()) {
            Text(
                text = if (decimalPart.startsWith(".")) decimalPart else ".$decimalPart",
                color = color,
                style = baseStyle.copy(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 16.sp,
                ),
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
}

// =====================================================================================
// 防线 3: 状态三件套标准容器 (StateTrioHost)
// =====================================================================================

/**
 * 界面状态三件套状态定义。
 */
sealed interface UIState<out T> {
    data class Success<T>(val data: T) : UIState<T>
    data class Empty(
        val message: String = "暂无相关数据",
        val hint: String? = null,
        val actionText: String? = null,
    ) : UIState<Nothing>
    data class Loading(val skeletonCount: Int = 4) : UIState<Nothing>
    data class Error(val message: String, val canRetry: Boolean = true) : UIState<Nothing>
}

/**
 * 状态三件套宿主容器：
 * 统管正常态、空态（EmptyState）、骨架微光态（SkeletonShimmer）、离线条（OfflineBanner）。
 * 遵循严格的状态优先律：OfflineBanner 常驻叠放 > Error 重试态 > Empty 空态 > Loading 骨架态 > Content 正常态。
 */
@Composable
fun <T> StateTrioHost(
    state: UIState<T>,
    modifier: Modifier = Modifier,
    isOffline: Boolean = false,
    offlineMessage: String = "当前处于离线模式，本地数据已安全固化",
    onRetry: (() -> Unit)? = null,
    onEmptyAction: (() -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // 1. 离线状态条（高优先级置顶通知）
        if (isOffline) {
            OfflineBanner(message = offlineMessage)
        }

        // 2. 主体状态分流
        when (state) {
            is UIState.Loading -> {
                SkeletonShimmerHost(itemCount = state.skeletonCount)
            }
            is UIState.Empty -> {
                EmptyStateCard(
                    message = state.message,
                    hint = state.hint,
                    actionText = state.actionText,
                    onAction = onEmptyAction,
                )
            }
            is UIState.Error -> {
                ErrorStateCard(
                    message = state.message,
                    canRetry = state.canRetry,
                    onRetry = onRetry,
                )
            }
            is UIState.Success -> {
                content(state.data)
            }
        }
    }
}

/**
 * 离线警示条：顶部横幅，工业冷暖警示色。
 */
@Composable
fun OfflineBanner(
    message: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Design.WarningLight)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Design.Warning),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = message,
            color = Design.Warning,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 标准空态卡片组件。
 */
@Composable
fun EmptyStateCard(
    message: String,
    hint: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Design.GrayChip),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "—",
                color = Design.TextWeak,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = message,
            color = Design.TextMain,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )

        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = hint,
                color = Design.TextWeak,
                fontSize = 12.sp,
            )
        }

        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Design.LightBlueChip,
                    contentColor = Design.TextMain,
                ),
                shape = RoundedCornerShape(Design.ButtonRadius.dp),
                modifier = Modifier.touchTarget56(),
            ) {
                Text(
                    text = actionText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * 错误重试卡片。
 */
@Composable
fun ErrorStateCard(
    message: String,
    canRetry: Boolean,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            color = Design.Danger,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        if (canRetry && onRetry != null) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Design.ButtonRadius.dp))
                    .background(Design.DangerLight)
                    .clickable(onClick = onRetry)
                    .touchTarget56()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "点击重新加载",
                    color = Design.Danger,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * 骨架屏微光动画容器。
 */
@Composable
fun SkeletonShimmerHost(
    itemCount: Int = 4,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "shimmerTransition")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerTranslate",
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            Design.GrayChip.copy(alpha = 0.6f),
            Design.CardSurface.copy(alpha = 0.9f),
            Design.GrayChip.copy(alpha = 0.6f),
        ),
        start = Offset(translateAnim - 200f, 0f),
        end = Offset(translateAnim, 0f),
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(itemCount) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .toolSurface(level = SxElevation.L2, shape = RoundedCornerShape(Design.CardRadius.dp))
                    .padding(14.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    // 图标骨架
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(Design.ShapeSm.dp))
                            .background(shimmerBrush),
                    )
                    Spacer(Modifier.width(12.dp))
                    // 文本行骨架
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .height(16.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(shimmerBrush),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.4f)
                                .height(14.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(shimmerBrush),
                        )
                    }
                }
            }
        }
    }
}
