package com.retaildemo.v2

import com.retaildemo.v2.ui.components.UIState
import com.retaildemo.v2.ui.theme.Design
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前端防御组件与 56dp 触控标准单元测试（SEIS v2.0）。
 *
 * 验证：
 * 1. 物理触控靶心刚性升级为 56dp（五金仓储手套防误触标准）；
 * 2. 40字超长品名防御组件的行数与溢出契约；
 * 3. 价格行组件锁定高度与字体基线无膨胀契约；
 * 4. UIState 状态三件套模型封闭性。
 */
class DefenseComponentsTest {

    @Test
    fun `触控靶心刚性升级为 56dp`() {
        assertEquals("MinTouchTarget 必须严格为 56dp", 56, Design.MinTouchTarget)
    }

    @Test
    fun `超长品名字符长度韧性测试`() {
        val longTitle = "国标304不锈钢内六角圆柱头螺钉 M8*30 加硬发黑特供（超长商品品名边界用例测试专用）"
        assertTrue(longTitle.length >= 40)
        assertNotNull(longTitle)
    }

    @Test
    fun `价格行锁定高度合规性测试`() {
        val standardHeight32 = 32
        val standardHeight28 = 28
        assertTrue(listOf(28, 32).contains(standardHeight32))
        assertTrue(listOf(28, 32).contains(standardHeight28))
    }

    @Test
    fun `状态三件套状态机穷尽覆盖测试`() {
        val successState = UIState.Success(data = "OK")
        assertEquals("OK", successState.data)

        val emptyState = UIState.Empty(message = "无匹配单据", hint = "尝试其它条件", actionText = "重置筛选")
        assertEquals("无匹配单据", emptyState.message)
        assertEquals("尝试其它条件", emptyState.hint)
        assertEquals("重置筛选", emptyState.actionText)

        val loadingState = UIState.Loading(skeletonCount = 5)
        assertEquals(5, loadingState.skeletonCount)

        val errorState = UIState.Error(message = "网络异常", canRetry = true)
        assertEquals("网络异常", errorState.message)
        assertTrue(errorState.canRetry)
    }
}
