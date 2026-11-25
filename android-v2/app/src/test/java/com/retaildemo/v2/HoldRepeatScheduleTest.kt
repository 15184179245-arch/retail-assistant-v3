package com.retaildemo.v2

import com.retaildemo.v2.ui.components.holdRepeatSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长按连删节奏的契约测试（框架「主件打磨六步法」第 6 步：落测试）。
 *
 * 节奏是纯手感参数，评审肉眼看不出来对错——改快了像抽风、改慢了删不完一串、
 * 不封顶则误按住会把整行删光。这里用单测把四条设计意图钉死。
 */
class HoldRepeatScheduleTest {

    @Test
    fun 首次连删间隔等于初始间隔() {
        assertEquals(150L, holdRepeatSchedule().first())
    }

    @Test
    fun 节奏单调不增_先慢后快() {
        val s = holdRepeatSchedule()
        for (i in 1 until s.size) {
            assertTrue("间隔必须单调不增（第$i 项 ${s[i - 1]} → ${s[i]}）", s[i] <= s[i - 1])
        }
    }

    @Test
    fun 间隔不低于下限_避免快到失控() {
        for (v in holdRepeatSchedule()) {
            assertTrue("间隔 ${v}ms 低于 45ms 下限", v >= 45L)
        }
    }

    @Test
    fun 次数封顶_误按住不会无限删() {
        val s = holdRepeatSchedule(maxRepeats = 30)
        assertEquals(30, s.size)
        // 防呆上限：数量/金额字段最长 9+2 位，30 次足够；长按到底也只删 30 位
        assertTrue("封顶次数应 ≤ 40（再大就失去防呆意义）", s.size <= 40)
    }

    @Test
    fun 整段连删总时长可控() {
        val s = holdRepeatSchedule()
        val total = s.sum()
        // 按到底约 1.6s 删 30 位：够删完一整串，又不至于按一下等半天
        assertTrue("总时长 ${total}ms 超出可控范围（应 <3000ms）", total < 3_000)
        assertTrue("总时长 ${total}ms 太快（应 >=1000ms，否则连删不可控）", total >= 1_000)
    }

    @Test
    fun 衰减到下限后保持稳定() {
        val s = holdRepeatSchedule(initialMs = 150, floorMs = 45, decayPercent = 80, maxRepeats = 30)
        val tail = s.takeLast(5).toSet()
        assertEquals("尾段应稳定在下限值", setOf(45L), tail)
    }
}
