package com.retaildemo.v2

import com.retaildemo.v2.print.CpclImageCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CPCL EG 位图编码（v2.8.3 WYSIWYG 路径）：打包/头尾结构/守卫。 */
class CpclImageCodecTest {

    @Test
    fun `单黑点_MSB最左_头尾结构完整`() {
        val out = CpclImageCodec.egForm(8, 2, 1) { x, y -> x == 0 && y == 0 }
        assertEquals("! 0 200 200 2 1\nPAGE-WIDTH 8\nEG 1 2 0 0 8000\nFORM\nPRINT\n", out)
    }

    @Test
    fun `整行全黑为FF_份数透传`() {
        val out = CpclImageCodec.egForm(16, 1, 3) { _, _ -> true }
        assertTrue(out.contains("\nEG 2 1 0 0 FFFF\n"))
        assertTrue("页头份数", out.startsWith("! 0 200 200 1 3\n"))
        assertTrue(out.endsWith("FORM\nPRINT\n"))
    }

    @Test
    fun `宽度非8倍数拒绝_高宽非正拒绝`() {
        for (bad in listOf(12 to 10, 0 to 10, 8 to 0)) {
            var threw = false
            try {
                CpclImageCodec.egForm(bad.first, bad.second, 1) { _, _ -> false }
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue("bad=${bad.first}x${bad.second} 应拒", threw)
        }
    }

    @Test
    fun `40x30价签尺寸_数据长度精确`() {
        // 320×240：每行 40B → hex 19200 字符
        val out = CpclImageCodec.egForm(320, 240, 1) { _, _ -> false }
        val egLine = out.lineSequence().first { it.startsWith("EG ") }
        assertTrue(egLine.startsWith("EG 40 240 0 0 "))
        assertEquals(19200, egLine.length - "EG 40 240 0 0 ".length)
    }
}
