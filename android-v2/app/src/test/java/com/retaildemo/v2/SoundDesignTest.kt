package com.retaildemo.v2

import com.retaildemo.v2.ui.components.ScanFeedback
import com.retaildemo.v2.ui.components.padKeySfx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 声音设计契约测试（2026-09-13）。
 *
 * 锁定两件事，防止「音效语言」在后续迭代中被无意破坏：
 *
 * 1. **键位语义映射**：数字/退格/清空/完成必须各走各的音效。
 *    键盘是全 APP 被点得最多的表面，店员常不看屏幕连点——键位声音必须能区分，
 *    否则"闭眼连点"时会把完成键当数字键。
 *
 * 2. **响度/触觉阶梯不变量**：设计三原则之一「触觉时长 = 事件权重」——
 *    高频操作必须是轻触觉，里程碑/危险必须是重触觉。这条一旦被改乱，
 *    店里表现是"点键盘震得手麻"或"删账单毫无感觉"，而代码评审时肉眼看不出来。
 */
class SoundDesignTest {

    // ---------------- 键位语义映射 ----------------

    @Test
    fun 数字键走机械短咔() {
        for (d in listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", ".")) {
            assertEquals("数字键 $d 应走 KEY 音", ScanFeedback.Sfx.KEY, padKeySfx(d))
        }
    }

    @Test
    fun 退格清空完成各有专属音效() {
        assertEquals(ScanFeedback.Sfx.BACKSPACE, padKeySfx("BKSP"))
        assertEquals(ScanFeedback.Sfx.CLEAR, padKeySfx("CLEAR"))
        assertEquals(ScanFeedback.Sfx.COMMIT_OK, padKeySfx("DONE"))
    }

    @Test
    fun 键位音效两两不同_闭眼连点可区分() {
        val all = listOf("5", "BKSP", "CLEAR", "DONE").map { padKeySfx(it) }
        assertEquals("四类键位音效必须互不相同", all.size, all.toSet().size)
    }

    // ---------------- 触觉阶梯不变量 ----------------

    @Test
    fun 高频操作必须是轻触觉() {
        // 一秒可能点十次的操作：触觉 <= 15ms，否则手麻
        for (e in listOf(ScanFeedback.Sfx.KEY, ScanFeedback.Sfx.BACKSPACE,
                         ScanFeedback.Sfx.STEP_UP, ScanFeedback.Sfx.STEP_DOWN)) {
            assertTrue("$e 触觉 ${e.vibrateMs}ms 过长（高频操作应 <=15ms）", e.vibrateMs <= 15)
        }
    }

    @Test
    fun 里程碑与危险动作必须是重触觉() {
        assertTrue("一单扫完应 >=50ms", ScanFeedback.Sfx.PIPELINE_DONE.vibrateMs >= 50)
        assertTrue("危险动作应 >=100ms", ScanFeedback.Sfx.DANGER.vibrateMs >= 100)
        assertTrue("重复扫码提示应 >=80ms（要能被立刻察觉）",
            ScanFeedback.Sfx.DUPLICATE.vibrateMs >= 80)
    }

    @Test
    fun 危险动作触觉重于普通提交() {
        assertTrue(
            "危险动作的触觉必须比普通提交更重，否则手感上分不出" +
                "「删了」和「存了」",
            ScanFeedback.Sfx.DANGER.vibrateMs > ScanFeedback.Sfx.COMMIT_OK.vibrateMs,
        )
    }

    @Test
    fun 每个语义事件都有音效资源() {
        for (e in ScanFeedback.Sfx.entries) {
            assertTrue("$e 缺少音效资源（resId=${e.resId}）", e.resId != 0)
            assertTrue("$e 音量应在 (0,1] 区间", e.volume > 0f && e.volume <= 1f)
        }
    }

    @Test
    fun 事件表覆盖关键语义且无重复资源() {
        val ids = ScanFeedback.Sfx.entries.map { it.resId }
        assertEquals("同一音效资源不应挂到多个语义事件上", ids.size, ids.toSet().size)
        assertTrue("事件数不应少于 14（覆盖键/步进/入列/绑定/提交/里程碑/警告/危险/重复/空）",
            ScanFeedback.Sfx.entries.size >= 14)
    }
}
