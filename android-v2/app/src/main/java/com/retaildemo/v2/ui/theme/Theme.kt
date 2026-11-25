package com.retaildemo.v2.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = PrimaryLight,
    onPrimaryContainer = PrimaryDark,
    secondary = Success,
    onSecondary = Color.White,
    secondaryContainer = SuccessLight,
    error = Danger,
    onError = Color.White,
    errorContainer = DangerLight,
    background = BgGray,
    onBackground = TextMain,
    surface = Card,
    onSurface = TextMain,
    surfaceVariant = SurfaceSubtle,
    onSurfaceVariant = TextSecondary,
    outline = Border,
    outlineVariant = BorderStrong,
    // ===== 美学公理 A1（中性表面纯净律，tokens v7）=====
    // M3 的表面色计算是 surfaceTint.copy(alpha = 由 elevation 推出).compositeOver(surface)——
    // 注意 alpha 来自 elevation，**不是**来自 tint 自身的 alpha。所以：
    //   · tint=primary（M3 默认）→ 每张带 elevation 的卡片被品牌橙按高度染色，
    //     1dp≈4.8%，一屏几十张叠加=满屏橙雾（实测卡片底 #FFF7F2，而非令牌纯白）。
    //   · tint=Color.Transparent → 不是"关闭染色"，而是换成**黑**染料（RGB 恒为 0），
    //     同一处卡片被压成 #F2F2F2 —— 比页面底色还暗，抬升感彻底反转。
    // 正解=tint 与 surface 同源，混合即成恒等变换：抬升只由光影表达，不由染色表达。
    surfaceTint = Card,
    // M3 默认 surfaceContainer* 是带紫调的灰（#F3EDF7 一类），与我们的冷中性阶梯不同源，
    // 会让导航栏/顶栏显出一层脏紫。这里逐档钉回令牌阶梯，全端只有一套中性面。
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCFCFD),
    surfaceContainer = BgGray,
    surfaceContainerHigh = SurfaceSubtle,
    surfaceContainerHighest = Color(0xFFEDEFF3),
)

private val AppTypography = Typography(
    // 页面主统计数字 / 结账大金额
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        letterSpacing = (-0.5).sp,
        color = TextMain,
    ),
    // 模块主标题 / 卡片大标题
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        letterSpacing = (-0.2).sp,
        color = TextMain,
    ),
    // 页面小标题 / 弹窗标题
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        color = TextMain,
    ),
    // 列表中商品名称（加粗醒目）
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        color = TextMain,
    ),
    // 正文大
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
        color = TextMain,
    ),
    // 默认正文字号
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = TextMain,
    ),
    // 规格/条码/次要描述（石板灰）
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = TextSecondary,
    ),
    // 主按钮文本
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = 0.1.sp,
    ),
    // 胶囊标签 / 状态徽章文本
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        color = TextSecondary,
    ),
    // 时间戳 / 极小辅助信息
    labelSmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        color = TextWeak,
    ),
)

@Composable
fun retaildemoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = AppTypography,
        content = content,
    )
}
