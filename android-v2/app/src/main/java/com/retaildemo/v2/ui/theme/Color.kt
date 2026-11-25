package com.retaildemo.v2.ui.theme

import androidx.compose.ui.graphics.Color

// 品牌与主操作色（Design System 5.0 TITAN 工业安全工程橙，高辨识强视觉牵引）
val Primary = Color(0xFFFF6A00)
// 承载白字的主 CTA 深档主色（tokens v12 `color.light.primaryAction`；画布 TASK-01H 定值）。
// 白字压 Primary #FF6A00 实测仅 2.871:1 不达 WCAG AA，压本值 4.501:1 过 AA；
// Primary 原值不动，继续用于图标/描边等非文字承载（司令部裁决 ①）。
val PrimaryAction = Color(0xFFC75300)
val PrimaryDark = Color(0xFFE55F00)
val PrimaryLight = Color(0xFFFFF3E8)

// 业务状态语义色（符合工业五金与强反差扫码人机工学）
// Success/Warning 亮色主题取深文字档（fix-round1 #1）：浅底上作文字需 ≥4.5:1，
// 亮绿 #00E599 打 #E6FDF4 仅 1.56:1；暗色主题仍用亮值（Design.kt 生成侧已分流）。
val Success = Color(0xFF1B7E3B)        // 充足 / 管家婆已出库 / 正确
val SuccessLight = Color(0xFFE6FDF4)
val Danger = Color(0xFFFF2E4D)         // 大字售价 / 零库存熔断 / 报警 / 删除
val DangerLight = Color(0xFFFFEFF2)
val Warning = Color(0xFFB26F00)        // 紧张 / 待处理挂账 / 提醒
val WarningLight = Color(0xFFFFF9E6)
val Cyan = Color(0xFF00F2FE)           // 精工雷达青 / 货位拓扑定位
val CyanLight = Color(0xFFE6FDFF)
val CyanDark = Color(0xFF0E7490)            // 货位胶囊深色文字档（WCAG AA ≥ 4.5:1）

// 背景与表面
val BgGray = Color(0xFFF8F9FB)         // 页面底色
val Card = Color(0xFFFFFFFF)           // 卡片白
val SurfaceSubtle = Color(0xFFF2F4F7)  // 胶囊/次级底色

// 文字阶梯
val TextMain = Color(0xFF111827)       // 标题、金额、主商品名（深炭黑，高对比）
val TextSecondary = Color(0xFF475467)  // 规格、条码、辅助说明
val TextWeak = Color(0xFF667085)       // 占位符、时间戳、未激活（v8：原 98A2B3 白底仅 2.8:1，不达 WCAG AA）
val TextDisabled = Color(0xFFD0D5DD)

// 边框与分割
val Border = Color(0xFFEAECF0)         // 卡片 0.5dp 细边框
val BorderStrong = Color(0xFFD0D5DD)   // 输入框聚焦/深边框

// 端三 · 老王（Agent）品牌与状态灯（画布 33 稿 70:30；值与既有代码逐字对齐，非新设计）
val AgentBrand = Color(0xFFE8590C)     // 老王品牌橙：浮窗胶囊底 / AI 徽章 / 收起钮描边
val AgentOnline = Color(0xFF40C057)    // 悬浮球呼吸点·在线
val AgentOffline = Color(0xFFADB5BD)   // 悬浮球呼吸点·离线
val AgentBusy = Color(0xFFFFD43B)      // 悬浮球呼吸点·忙

// 客户瓷片渐变（画布 3:1139；散客走 SurfaceSubtle 灰片 3:1145）
val CustomerTileFrom = Color(0xFFFFF7F0)
val CustomerTileTo = Color(0xFFFFF2E8)

// 侧滑删除区底色（M13 规范板 74:37 / 实体稿 74:27 SVG 真源；大面积色块专用红，较 danger 降刺激）
val DeleteZone = Color(0xFFE5474C)

// 主操作大 CTA 渐变（画布 3:16 格详情「开始连扫」y=188 / 「完成（本次 N 件）」y=768 的
// linearGradient 真源：stop #FF7C19 → #FF6B00，左→右）。
// ★ 命名跟随「客户瓷片渐变 CustomerTile*」既有惯例（From/To 一对），别再往页面里写裸 Color(0x…)。
val CtaGradientFrom = Color(0xFFFF7C19)
val CtaGradientTo = Color(0xFFFF6B00)

// 夜视屏（相机类页面）色阶 —— 值逐字取自 design/tokens.json v11 `color.dark`，与画布
// 18 扫码交接台（38:768）同一套，改这里必须同步改 tokens.json，别在页面里各写一份。
// 注：v12 起 `gen_ui_tokens.py --check` 的 DARK_NAME_MAP 已把这一组逐档对账，dark 不再是"人工同步、无网可拦"；
// 相机页是仓库里第一处真按 dark 档取色的界面（TASK-02B，2026-09-22）。
val NightPrimaryAction = Color(0xFFC75300)        // 夜视主 CTA 底（白字压它 4.501:1；不得再用 #FF6A00 承载白字）
val NightBg = Color(0xFF080B10)                   // 相机页地面（原 Color.Black）
val NightCard = Color(0xFF111622)                 // 夜视卡底
val NightSurfaceSubtle = Color(0xFF182030)        // 交接清单面板底
val NightBorder = Color(0xFF222C3E)               // 夜视描边
val NightTextMain = Color(0xFFFFFFFF)             // 夜视主文字
val NightTextSecondary = Color(0xFFCBD5E1)        // 夜视次级文字
val NightTextWeak = Color(0xFF94A3B8)             // 夜视弱文字（M3 不算数档）
val NightSuccess = Color(0xFF00E599)              // 夜视成功：light 的 #1B7E3B 压 #080B10 对比不足
val NightWarning = Color(0xFFFFB800)              // 夜视提醒/手电筒琥珀（原散写 #FFC107）
val NightSuccessLight = Color(0xFF06291F)         // 亮绿胶囊上的墨绿文字（画布 56:796 M1 胶囊实测）
val NightWarningLight = Color(0xFF2E2304)         // 琥珀胶囊上的墨黄文字（画布 56:796 M2 胶囊实测）

// 价签预览色（tokens v12 `labelPreview.*`，司令部裁决 ④）——模拟**实体不干胶样张**，不是 UI 调色板：
// 这一组只许出现在价签样张预览里，拿它当界面底色/文字色用就是毁掉语义。
// 值由 gen_ui_tokens.py --check 与 design/tokens.json 对账，改这里必须同步改 tokens.json。
val LabelPreviewPaper = Color(0xFFFAF9F6)         // 米白不干胶底
val LabelPreviewPaperEdge = Color(0xFFD5D0C5)     // 不干胶描边
val LabelPreviewInkStrong = Color(0xFF262626)     // 主墨（强）
val LabelPreviewInkBody = Color(0xFF444444)       // 正文墨
val LabelPreviewInk = Color(0xFF555555)           // 次级墨
val LabelPreviewInkWeak = Color(0xFF666666)       // 弱墨（页脚）
val LabelPreviewRule = Color(0xFFCCCCCC)          // 样张分隔线

// 窗口层遮罩/投影（tokens v12 `window.*`，SanxWindow 用）——**ARGB** 写死的含 alpha 值，
// 与 CSS 的 RRGGBBAA 序不兼容，故只走 Kotlin 侧，不进 tokens.css（见 tokens.json window.comment）。
val WindowScrimModal = Color(0x73000000)          // 模态遮罩（45% 黑）
val WindowShadowAmbient = Color(0x1A101828)       // 环境阴影
val WindowShadowSpot = Color(0x3D101828)          // 投影阴影

