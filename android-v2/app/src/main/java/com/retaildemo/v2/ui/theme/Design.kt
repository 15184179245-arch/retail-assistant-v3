package com.retaildemo.v2.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 设计令牌——由 scripts/gen_ui_tokens.py 从 design/tokens.json 生成，勿手改。
 * 契约：DESIGN.md v6（2026-09-08）· M3 Expressive 理念 · 亮色优先/暗色同源。
 * 改令牌 → 改 design/tokens.json → 重跑生成器 → python scripts/gen_ui_tokens.py --check。
 */
object Design {
    // ===== 品牌渐变（顶栏/统计卡，工业蓝阶梯） =====
    val TopBarGradientFrom = Color(0xFF1E60D5)
    val TopBarGradientTo = Color(0xFF1044A5)
    val StatsGradientFrom = Color(0xFF1E60D5)
    val StatsGradientTo = Color(0xFF0F3E99)

    // ===== 语义色别名（继承 Color.kt，与 tokens 亮色零漂移） =====
    val Success = com.retaildemo.v2.ui.theme.Success
    val SuccessLight = com.retaildemo.v2.ui.theme.SuccessLight
    val Danger = com.retaildemo.v2.ui.theme.Danger
    val DangerLight = com.retaildemo.v2.ui.theme.DangerLight
    val Warning = com.retaildemo.v2.ui.theme.Warning
    val WarningLight = com.retaildemo.v2.ui.theme.WarningLight
    val DeleteZone = com.retaildemo.v2.ui.theme.DeleteZone
    val Cyan = com.retaildemo.v2.ui.theme.Cyan
    val CyanLight = com.retaildemo.v2.ui.theme.CyanLight
    val CyanDark = com.retaildemo.v2.ui.theme.CyanDark

    // ===== 端三 · 老王品牌与状态灯 + 客户瓷片渐变（v10 补漏；亮色专属） =====
    val AgentBrand = com.retaildemo.v2.ui.theme.AgentBrand
    val AgentOnline = com.retaildemo.v2.ui.theme.AgentOnline
    val AgentOffline = com.retaildemo.v2.ui.theme.AgentOffline
    val AgentBusy = com.retaildemo.v2.ui.theme.AgentBusy
    val CustomerTileFrom = com.retaildemo.v2.ui.theme.CustomerTileFrom
    val CustomerTileTo = com.retaildemo.v2.ui.theme.CustomerTileTo

    // ===== 底与面 =====
    val PageBg = com.retaildemo.v2.ui.theme.BgGray
    val CardSurface = com.retaildemo.v2.ui.theme.Card
    val CardStroke = com.retaildemo.v2.ui.theme.Border
    val CardStrokeStrong = com.retaildemo.v2.ui.theme.BorderStrong
    val LightBlueChip = com.retaildemo.v2.ui.theme.PrimaryLight
    val GrayChip = com.retaildemo.v2.ui.theme.SurfaceSubtle

    // ===== 文字阶 =====
    val TextMain = com.retaildemo.v2.ui.theme.TextMain
    val TextSecondary = com.retaildemo.v2.ui.theme.TextSecondary
    val TextWeak = com.retaildemo.v2.ui.theme.TextWeak
    val TextPlaceholder = com.retaildemo.v2.ui.theme.TextDisabled

    // ===== 阴影墨色（v9 阴影收口：L0–L4 五级共用同一墨色） =====
    val ShadowInk = Color(0xFF0A1221)

    // ===== 材质与光影（v7 美学令牌：顶光接边，不靠色阶涂装） =====
    // ★ 阴影等级 L0–L4 的唯一定义在 ui/theme/Elevation.kt；
    //   参数源＝本文件 elevation.levels，漂移由 gen_ui_tokens.py --check 拦下。
    val HairlineLight = Color(0x8CFFFFFF)   // 顶光接边（磨砂塑料高光）
    val HairlineShadow = Color(0x140B1220) // 底缘影线
    val ScrimInk = Color(0xFF0B1220)         // 图上蒙版墨色（禁纯黑）
    const val ScrimPlateau = 0.78f        // 蒙版平台段不透明度（文字区保底）
    const val ScrimFade = 0.18f              // 蒙版起渐变不透明度
    const val SignalBudget = 0.12f         // 单屏高饱和品牌色面积预算

    // ===== 尺寸规范（4dp 网格基准） =====
    const val CardRadius = 14
    const val StatsRadius = 16
    const val ButtonRadius = 10
    const val InputRadius = 10
    const val PillRadius = 20
    const val TopBarHeight = 52
    const val BottomBarHeight = 60
    const val MinTouchTarget = 56

    // ===== 间距阶（4dp 网格） =====
    const val SpaceXs = 4
    const val SpaceSm = 8
    const val SpaceMd = 12
    const val SpaceLg = 16
    const val SpaceXl = 20
    const val SpaceXxl = 24

    // ===== 字号阶（Theme.kt 排版同源） =====
    const val FontXxs = 10
    const val FontXs = 11
    const val FontSm = 12
    const val FontMd = 14
    const val FontMdPlus = 15
    const val FontMdPlus2 = 13
    const val FontLg = 16
    const val FontXl = 18
    const val FontXxl = 24
    const val FontDisplay = 28

    // ===== 行高阶（与字号阶一一对应；只设字号不设行高＝行盒按继承行高撑开，本项目最高频事故） =====
    const val LineXxs = 14
    const val LineXs = 15
    const val LineSm = 16
    const val LineMd = 20
    const val LineMdPlus = 20
    const val LineMdPlus2 = 18
    const val LineLg = 22
    const val LineXl = 25
    const val LineXxl = 32
    const val LineDisplay = 37

    // ===== 形状阶（M3 Expressive 对比形状：层级靠形状不靠线框） =====
    const val ShapeXs = 8
    const val ShapeSm = 10
    const val ShapeLg = 16
    const val ShapeXl = 20

    // ===== 动效 =====
    const val ListAnimMs = 200
    const val ListAnimStaggerMs = 30
    const val PressScaleDown = 0.97f
    const val PressMs = 80
    const val ReleaseMs = 120
    const val NavSlideMs = 240

    // ===== 弹簧物理（M3 Expressive 精髓：动效统一用弹簧曲线） =====
    const val SpringDampingPress = 0.85f      // 按压回弹（克制）
    const val SpringStiffnessPress = 800f   // 按压快速跟手
    const val SpringDampingAppear = 0.75f    // 出现轻弹
    const val SpringStiffnessAppear = 400f

    // ===== 时长阶（ms；禁止野值，取自 docs/UI-MOTION-SPEC.md）=====
    const val DurQuick = 80          // 80  按下
    const val DurBack = 120            // 120 回位 / 数字翻转
    const val DurExit = 160            // 160 退出（accelerate）
    const val DurAppear = 200        // 200 出现
    const val DurEnter = 240          // 240 进入 / 切页（decelerate）
    const val DurSheet = 320          // 320 抽屉 / 键盘升起
    const val DurMilestone = 420  // 420 里程碑（唯一允许过冲）

    // ===== 品类弹簧（全仓只用这六组，页面不得自写 tween）=====
    const val SpringDampingSheet = 0.82f
    const val SpringStiffnessSheet = 320f
    const val SpringDampingNav = 0.9f
    const val SpringStiffnessNav = 380f
    const val SpringDampingSuccess = 0.55f
    const val SpringStiffnessSuccess = 250f
    const val SpringDampingDanger = 0.95f
    const val SpringStiffnessDanger = 700f
}
