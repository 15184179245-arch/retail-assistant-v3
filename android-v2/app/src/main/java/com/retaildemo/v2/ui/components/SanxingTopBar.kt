package com.retaildemo.v2.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retaildemo.v2.ui.components.atLeastTouchTarget
import com.retaildemo.v2.ui.theme.Border
import com.retaildemo.v2.ui.theme.Design
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextWeak

enum class TopBarStyle {
    BRAND_GRADIENT, // 品牌渐变蓝（默认主界面风格）
    SURFACE_CLEAN,  // 纯净白底（详情/表单页面风格）
}

/**
 * 统一顶栏：彻底消除状态栏撞顶 Bug，规范 52dp 高度与 48dp 返回键点击靶心。
 */
@Composable
fun retaildemoTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    style: TopBarStyle = TopBarStyle.BRAND_GRADIENT,
    onBack: (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit)? = null,
) {
    // 【U2 2026-09-08】老板拍板：蓝渐变顶栏与蓝色状态栏条退役，全 App 统一浅色头（白底深字）。
    // style 参数保留兼容旧调用方；暗色主题回归时再在此处区分。
    // 【前端改版 2026-09-14】去掉顶栏底部 0.5dp 分割线（SEIS 公理 3「严禁用线条切割界面」），
    // 层级改用「白面 vs #F8F9FB 页面底」的表面差 + 字重阶梯；标题字阶 17→18 Bold、副标 11→12。
    val isGradient = false
    val contentColor = if (isGradient) Color.White else TextMain
    val bgModifier = if (isGradient) {
        Modifier.background(brandGradient())
    } else {
        Modifier.background(com.retaildemo.v2.ui.theme.Card)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(bgModifier)
                .statusBarsPadding()
                .height(Design.TopBarHeight.dp)
                .padding(horizontal = 4.dp),
        ) {
            if (onBack != null) {
                // fix-round1 #10：顶栏返回键热区 56dp（视觉 48 不变，手套可点）
                Box(
                    modifier = Modifier
                        .atLeastTouchTarget(56.dp)
                        .size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = contentColor,
                        )
                    }
                }
            } else {
                Spacer(Modifier.width(16.dp))
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            ) {
                Text(
                    text = title,
                    color = contentColor,
                    fontSize = Design.FontXl.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = if (isGradient) Color.White.copy(alpha = 0.8f) else TextWeak,
                        fontSize = Design.FontSm.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            actions?.invoke(this)
        }
    }
}
