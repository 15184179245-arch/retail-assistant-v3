package com.retaildemo.v2

import com.retaildemo.v2.ui.components.REVEAL_TAG_GAP_DP
import com.retaildemo.v2.ui.components.REVEAL_TAG_MARGIN_DP
import com.retaildemo.v2.ui.components.revealTagX
import com.retaildemo.v2.ui.components.revealTagY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长按浮签落位契约测试（U12，老板 2026-09-14：「按住显示」被自己的手指挡住的老矛盾）。
 *
 * 落位是纯几何，肉眼评审看不出对错，但错了就直接毁掉这个交互：
 * 落到指尖底下 = 白做；飞出屏幕 = 数字看不见；上下都不翻 = 顶部那一行永远看不到底价。
 * 所以把四条设计意图钉死在这里。坐标单位是像素，用整数模拟 1080×2000 的屏。
 */
class RevealTagPlacementTest {

    private val margin = 30
    private val gap = 90

    @Test
    fun 上方放得下时浮签在锚点上方_且底边与锚点相距gap() {
        val top = 800
        val tagHeight = 100
        val y = revealTagY(
            anchorTop = top, anchorBottom = 900, tagHeight = tagHeight,
            windowHeight = 2000, gap = gap, margin = margin,
        )
        assertEquals("浮签应整块落在锚点上方", top - tagHeight - gap, y)
        assertEquals("浮签底边让开指尖的间距就是 gap", gap, top - (y + tagHeight))
    }

    @Test
    fun 锚点贴屏幕顶部时翻到锚点下方() {
        val y = revealTagY(
            anchorTop = 100, anchorBottom = 200, tagHeight = 100,
            windowHeight = 2000, gap = gap, margin = margin,
        )
        assertEquals("上方放不下必须翻到下方", 200 + gap, y)
    }

    @Test
    fun 上方刚好等于留白时仍选择上方_差一像素才翻面() {
        val justFits = 100 + gap + margin // above 恰好 == margin 的临界锚点
        assertEquals(
            "临界点应仍留在上方",
            margin,
            revealTagY(justFits, justFits + 100, 100, 2000, gap, margin),
        )
        val onePxLess = justFits - 1
        assertEquals(
            "少一像素就该翻到下方",
            onePxLess + 100 + gap,
            revealTagY(onePxLess, onePxLess + 100, 100, 2000, gap, margin),
        )
    }

    @Test
    fun 上下都放不下时贴住上边_不许飞出屏幕() {
        val y = revealTagY(
            anchorTop = 0, anchorBottom = 100, tagHeight = 100,
            windowHeight = 150, gap = gap, margin = margin,
        )
        assertEquals("极矮屏应贴住上边留白", margin, y)
        assertTrue("浮签不得越出屏幕下沿", y + 100 <= 150)
    }

    @Test
    fun 横向以锚点为中心对齐() {
        val x = revealTagX(
            anchorLeft = 400, anchorWidth = 200, tagWidth = 300,
            windowWidth = 1080, margin = margin,
        )
        assertEquals("应以锚点中心为中心", 350, x)
    }

    @Test
    fun 锚点贴左边时浮签被钳进屏幕() {
        val x = revealTagX(
            anchorLeft = 0, anchorWidth = 100, tagWidth = 300,
            windowWidth = 1080, margin = margin,
        )
        assertEquals(margin, x)
    }

    @Test
    fun 锚点贴右边时浮签被钳进屏幕() {
        val x = revealTagX(
            anchorLeft = 1000, anchorWidth = 100, tagWidth = 300,
            windowWidth = 1080, margin = margin,
        )
        assertEquals("右边不得越界", 1080 - 300 - margin, x)
    }

    @Test
    fun 浮签比屏幕还宽时退回左边留白_不产生负坐标() {
        val x = revealTagX(
            anchorLeft = 500, anchorWidth = 100, tagWidth = 2000,
            windowWidth = 1080, margin = margin,
        )
        assertEquals(margin, x)
    }

    @Test
    fun 让开指尖的间隙不得小于指尖垫半径() {
        // 指尖垫半径约 34dp；锚点已被撑到 ≥48dp，中心往上 10dp 内仍可能被压住。
        // 间隙小于这个量级就等于没让开——这是本次修复的立身之本，写进测试防回退。
        assertTrue("浮签间隙 $REVEAL_TAG_GAP_DP dp 太小，挡不住指尖", REVEAL_TAG_GAP_DP >= 24)
        assertTrue("贴边留白 $REVEAL_TAG_MARGIN_DP dp 太小，字会顶到屏幕框", REVEAL_TAG_MARGIN_DP >= 8)
    }
}
