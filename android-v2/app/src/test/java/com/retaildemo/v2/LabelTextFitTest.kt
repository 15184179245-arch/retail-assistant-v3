package com.retaildemo.v2

import com.retaildemo.v2.print.LabelTextFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标签文字防溢出机制（LabelTextFit）：三层防线——换行 / 末行省略收口 / 缩号。
 * 价签所有文字元素共用本机制，是「任何商品信息都不打飞、不丢字」的根基。
 */
class LabelTextFitTest {

    @Test
    fun `显示宽口径_ASCII半宽_CJK全角`() {
        assertEquals(24, LabelTextFit.textWidth("M8"))
        assertEquals(48, LabelTextFit.textWidth("螺栓"))
        assertEquals(48, LabelTextFit.textWidth("M8螺"))
    }

    @Test
    fun `换行代替截断_两行都完整`() {
        val lines = LabelTextFit.fitLines("不锈钢外六角螺栓加长型螺母", 168, 2)
        assertEquals(listOf("不锈钢外六角螺", "栓加长型螺母"), lines)
        assertFalse("完整装下不留省略号", lines.any { it.endsWith(LabelTextFit.ELLIPSIS) })
    }

    @Test
    fun `超容量_只末行省略号_前面各行完整`() {
        val lines = LabelTextFit.fitLines("得力9531-1山形铁票夹145mm袋装4只装彩色涂层钢板可订做定制加长", 224, 2)
        assertEquals(2, lines.size)
        assertTrue("末行省略号收口", lines.last().endsWith(LabelTextFit.ELLIPSIS))
        assertEquals("首行完整不被截", "得力9531-1山形铁票", lines.first())
    }

    @Test
    fun `最小可行情形_单字加省略号而非空串`() {
        // 值列极窄：至少留 1 个字符 + 省略号（旧实现会退化成光秃秃的「…」）
        val lines = LabelTextFit.fitLines("自攻钉", 100, 2, 2)
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.joinToString("").startsWith("自"))
    }

    @Test
    fun `缩号优先于截断_fitForBox取装得下的最大字号`() {
        val (size, lines) = LabelTextFit.fitForBox("Y123456.78", 240, 1, 2, 1)
        assertEquals("两倍宽放得下就取 2", 2, size)
        assertFalse(lines.last().endsWith(LabelTextFit.ELLIPSIS))
    }

    @Test
    fun `fitsFully_完整判定`() {
        assertTrue(LabelTextFit.fitsFully("自攻钉", 120, 1, 1))
        assertTrue("两行放得下", LabelTextFit.fitsFully("自攻钉", 120, 2, 1))
        assertTrue("四字一行放得下（120dot=5 汉字）", LabelTextFit.fitsFully("自攻钉加长", 120, 1, 1))
        assertFalse("六字一行放不下", LabelTextFit.fitsFully("自攻钉加长型", 120, 1, 1))
        assertTrue("六字两行放得下", LabelTextFit.fitsFully("自攻钉加长型", 120, 2, 1))
    }

    @Test
    fun `控制字符清洗_不破坏指令流`() {
        assertEquals("自攻钉", LabelTextFit.sanitize("自攻\n钉"))
        assertEquals("ABC", LabelTextFit.sanitize("A\r\nB\tC"))
        assertFalse(LabelTextFit.sanitize("A\r\nB\tC").contains("\n"))
    }

    @Test
    fun `空文本与零宽_返回空不抛异常`() {
        assertTrue(LabelTextFit.fitLines("", 100, 2).isEmpty())
        assertTrue(LabelTextFit.fitLines("abc", 0, 2).isEmpty())
        assertTrue(LabelTextFit.fitLines("abc", 100, 0).isEmpty())
        // 空串：显示宽 0，任何倍率都"放得下"，返回上限
        assertEquals(3, LabelTextFit.fitScale("", 100, 3))
    }
}
