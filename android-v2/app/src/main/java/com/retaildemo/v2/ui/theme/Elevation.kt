package com.retaildemo.v2.ui.theme

/**
 * 阴影等级 L0–L4 —— 全仓唯一定义处（v9 阴影收口）。
 *
 * 设计权威：Ardot 画布「M12 阴影等级规范」（node 42:5）。
 * 参数源：`design/tokens.json` → `elevation.levels`；
 *        漂移由 `python scripts/gen_ui_tokens.py --check` 拦下（逐层数字必须逐字相同）。
 * 代码生成物 `Design.kt` 与 web 用的 `design/tokens.css` 都从同一处派生。
 *
 * ## 纪律（违反即为 bug，七条与画布 M12 一致）
 * ① 同一级的阴影必须完全一致 —— 一屏里同是「内容卡」的，不许一张深一张浅；
 * ② 浮起是稀缺资源 —— 一屏最多 1 处 L3、1 处 L4，都在喊就等于都不喊；
 * ③ 深色底上阴影看不见 —— 改用「面差 + 顶光接边」，不要照搬这套影深（深色屏自己用发光）；
 * ④ 参数只在本文件定义 —— 屏上只许写 `sxElevation(SxElevation.Lx)`，不许就地写影深；
 * ⑤ L4 分两型 —— 贴边型（底栏/九宫格键盘/抽屉，下沿贴屏）取 [Elevation.dockedLayers]，
 *    影必须向上投；悬浮型（底部胶囊、Sheet）影向下。影向下的贴边栏等于把影扔到屏幕外；
 * ⑥ 品牌橙影（选中行、主按钮）表达的是「选中 / 主操作」语义、不是高度，不属本阶梯；
 * ⑦ 凹陷型控件（输入框、进度槽）是「进去」不是「上来」，别拿 L1「内嵌」去套凹形控件；
 * ⑧ 触控热区保底 56dp —— 工业手套作业防误触刚性标准对齐 [Design.MinTouchTarget]（56dp）。
 *
 * ## ✅ 物理多级阴影渲染引擎（2026-09-17 定版实测闭环）
 * 拒绝以「Compose 版本限制」为由摆烂——在 [com.retaildemo.v2.ui.components.AppSurfaces.sxElevation]
 * 中已通过底层 `drawBehind` + `drawIntoCanvas` + 原生 `BlurMaskFilter` 实现精确的物理多级阴影：
 *   · 层数与每层透明度 —— 1:1 精确对齐 M12 规范
 *   · 精确下沉 offsetY —— 逐层精确位移，摆脱单一仰角推断
 *   · 真实高斯模糊 blur —— 原生 BlurMaskFilter 真实高斯雾化
 *   · 负扩散 spread —— 几何收缩包围盒 (-spreadPx)，完美实现高级柔和雾化收拢
 *   · 顶光高光接边 —— 搭配 [topLightAlpha] 还原 1px 工业压铸件微立体棱线
 * 屏上统一调用 `Modifier.sxElevation(level)`，严禁就地写裸阴影或死板 BorderStroke。
 */
data class SxLayer(
    val alpha: Float,
    val offsetY: Int,
    val blur: Int,
    val spread: Int,
)

/** 一个阴影等级：层列表 + 顶光强度 + 配对圆角。 */
data class Elevation(
    val id: String,
    val name: String,
    val use: String,
    val layers: List<SxLayer>,
    val topLightAlpha: Float,
    val radius: Int,
    /** 仅 L4 用：贴边型（下沿贴屏）的向上投影层。 */
    val dockedLayers: List<SxLayer> = emptyList(),
) {
    /** 贴边型取向上投影的层；其余取向下。 */
    fun layersFor(docked: Boolean): List<SxLayer> =
        if (docked && dockedLayers.isNotEmpty()) dockedLayers else layers
}

object SxElevation {
    val L0 = Elevation(
        id = "L0",
        name = "贴地",
        use = "页面底、分区底——整屏唯一地面，无影成面",
        layers = emptyList(),
        topLightAlpha = 0.0f,
        radius = 0,
    )

    val L1 = Elevation(
        id = "L1",
        name = "内嵌",
        use = "顶栏、分组标签、表头（贴在页面上的薄片，不是凹槽）",
        layers = listOf(SxLayer(0.04f, 1, 2, 0)),
        topLightAlpha = 0.60f,
        radius = 6,
    )

    val L2 = Elevation(
        id = "L2",
        name = "内容卡",
        use = "列表卡、单据卡、商品行——主体层，一屏可多张",
        layers = listOf(SxLayer(0.05f, 1, 2, 0), SxLayer(0.06f, 3, 8, -2)),
        topLightAlpha = 0.85f,
        radius = 14,
    )

    val L3 = Elevation(
        id = "L3",
        name = "浮起卡",
        use = "概览卡、选中卡——一屏最多 1 处，它要压住下面的内容",
        layers = listOf(SxLayer(0.08f, 2, 4, -1), SxLayer(0.10f, 8, 20, -4), SxLayer(0.06f, 18, 36, -10)),
        topLightAlpha = 0.90f,
        radius = 14,
    )

    val L4 = Elevation(
        id = "L4",
        name = "浮动层",
        use = "底栏、浮层、Sheet——内容从它下面穿过去",
        layers = listOf(SxLayer(0.10f, 4, 8, -2), SxLayer(0.14f, 14, 30, -6), SxLayer(0.10f, 30, 60, -14)),
        dockedLayers = listOf(SxLayer(0.10f, -4, 8, -2), SxLayer(0.14f, -14, 30, -6), SxLayer(0.10f, -30, 60, -14)),
        topLightAlpha = 0.90f,
        radius = 20,
    )

    /** 便于遍历 / 单测断言级别齐全。 */
    val all = listOf(L0, L1, L2, L3, L4)
}
