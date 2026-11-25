package com.retaildemo.v2

import com.retaildemo.v2.ui.theme.SxElevation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阴影定稿验收（v9 阴影收口，2026-09-16）。
 *
 * 设计权威＝画布「M12 阴影等级规范」；代码源＝`design/tokens.json` 的 `elevation.levels`；
 * Kotlin 唯一定义处＝`ui/theme/Elevation.kt` 的 [SxElevation]。
 *
 * 这里锁死四条不变量——**改设计稿必须先改 tokens.json 与 Elevation.kt，否则本测会红**：
 *   ① 级别齐全、层数与设计稿一致（L0=0 / L1=1 / L2=2 / L3=3 / L4=3）；
 *   ② 「层级不是把影调黑，是让影子离地更远」—— 模糊与透明度必须逐级递增；
 *   ③ L4 分两型：贴边型（dockedLayers）必须是悬浮型的**垂直镜像**；
 *   ④ 顶光随层级单调不减，L0 无顶光。
 */
class ShadowLadderTest {

    @Test
    fun `五级齐全且 id 顺序正确`() {
        assertEquals(5, SxElevation.all.size)
        assertEquals(listOf("L0", "L1", "L2", "L3", "L4"), SxElevation.all.map { it.id })
    }

    @Test
    fun `层数与设计稿一致`() {
        assertEquals(0, SxElevation.L0.layers.size)
        assertEquals(1, SxElevation.L1.layers.size)
        assertEquals(2, SxElevation.L2.layers.size)
        assertEquals(3, SxElevation.L3.layers.size)
        assertEquals(3, SxElevation.L4.layers.size)
    }

    @Test
    fun `层级靠离地距离 模糊逐级递增`() {
        val maxBlur = SxElevation.all.map { lv -> lv.layers.maxOfOrNull { it.blur } ?: 0 }
        assertEquals(listOf(0, 2, 8, 36, 60), maxBlur)
    }

    @Test
    fun `最深一层透明度逐级加深`() {
        val maxAlpha = SxElevation.all.drop(1).map { lv -> lv.layers.maxOf { it.alpha } }
        assertEquals(listOf(0.04f, 0.06f, 0.10f, 0.14f), maxAlpha)
    }

    @Test
    fun `同级一致性 L2 两层透明度固定`() {
        assertEquals(listOf(0.05f, 0.06f), SxElevation.L2.layers.map { it.alpha })
    }

    @Test
    fun `L4 贴边型是悬浮型的垂直镜像`() {
        val floating = SxElevation.L4.layers
        val docked = SxElevation.L4.dockedLayers
        assertEquals(floating.size, docked.size)
        floating.zip(docked).forEach { (f, d) ->
            assertEquals(-f.offsetY, d.offsetY)
            assertEquals(f.blur, d.blur)
            assertEquals(f.spread, d.spread)
            assertEquals(f.alpha, d.alpha, 1e-6f)
        }
    }

    @Test
    fun `只有 L4 有贴边型`() {
        assertEquals(1, SxElevation.all.count { it.dockedLayers.isNotEmpty() })
        assertEquals("L4", SxElevation.all.first { it.dockedLayers.isNotEmpty() }.id)
    }

    @Test
    fun `贴边只对 L4 生效 其余级别取向下层`() {
        assertEquals(SxElevation.L2.layers, SxElevation.L2.layersFor(docked = true))
        assertEquals(SxElevation.L4.dockedLayers, SxElevation.L4.layersFor(docked = true))
        assertEquals(SxElevation.L4.layers, SxElevation.L4.layersFor(docked = false))
    }

    @Test
    fun `顶光随层级单调不减且 L0 无顶光`() {
        assertEquals(0.0f, SxElevation.L0.topLightAlpha, 1e-6f)
        val alphas = SxElevation.all.map { it.topLightAlpha }
        assertEquals(alphas.sorted(), alphas)
    }

    @Test
    fun `配对圆角与设计稿一致`() {
        assertEquals(listOf(0, 6, 14, 14, 20), SxElevation.all.map { it.radius })
    }

    @Test
    fun `每层参数在合法区间`() {
        SxElevation.all.forEach { lv ->
            (lv.layers + lv.dockedLayers).forEach { la ->
                assertTrue("${lv.id} 模糊必须为正", la.blur > 0)
                assertTrue("${lv.id} 透明度必须在 (0,1)", la.alpha > 0f && la.alpha < 1f)
                assertTrue("${lv.id} 偏移不得为 0（0 偏移＝没有层级）", la.offsetY != 0)
            }
        }
    }
}
