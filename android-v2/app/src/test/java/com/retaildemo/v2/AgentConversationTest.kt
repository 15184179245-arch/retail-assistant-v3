package com.retaildemo.v2

import com.retaildemo.v2.ui.viewmodel.AgentConversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** L10：老王会话流状态机（纯逻辑）——流式拼接/收尾/失败/停止/重试手术。 */
class AgentConversationTest {

    @Test
    fun `流式增量拼接与收尾`() {
        val c = AgentConversation()
        c.addUser("查一下螺丝", false)
        val id = c.beginReply()
        c.appendDelta(id, "十字")
        c.appendDelta(id, "螺丝")
        c.finalizeReply(id, null, degraded = false)
        val bubbles = c.bubbles
        assertEquals(2, bubbles.size)
        assertEquals("十字螺丝", bubbles[1].text)
        assertFalse(bubbles[1].streaming)
        assertFalse(bubbles[1].degraded)
    }

    @Test
    fun `无增量的done用全文兜底`() {
        val c = AgentConversation()
        c.addUser("q", false)
        val id = c.beginReply()
        c.finalizeReply(id, "全文回答", degraded = true)
        val b = c.bubbles[1]
        assertEquals("全文回答", b.text)
        assertTrue(b.degraded)
    }

    @Test
    fun `空收尾给兜底话术`() {
        val c = AgentConversation()
        c.addUser("q", false)
        val id = c.beginReply()
        c.finalizeReply(id, "", degraded = false)
        assertTrue(c.bubbles[1].text.contains("再问一次"))
    }

    @Test
    fun `失败与停止都解除流式态`() {
        val c = AgentConversation()
        c.addUser("q", false)
        val id = c.beginReply()
        c.failReply(id, "连不上")
        assertTrue(c.bubbles[1].failed)
        assertFalse(c.bubbles[1].streaming)

        val id2 = c.beginReply()
        c.appendDelta(id2, "半截")
        c.stopReply(id2)
        assertEquals("半截", c.bubbles[2].text)
        assertFalse(c.bubbles[2].streaming)

        val id3 = c.beginReply()
        c.stopReply(id3)
        assertEquals("（已停止）", c.bubbles[3].text)
    }

    @Test
    fun `dropLastReply只删最后一条老王回答`() {
        val c = AgentConversation()
        c.addUser("问1", false)
        val id = c.beginReply()
        c.finalizeReply(id, "答1", false)
        c.addSystem("回执")
        assertEquals(id, c.dropLastReply())
        assertEquals(listOf("user", "system"), c.bubbles.map { it.role })
        assertEquals(-1L, c.dropLastReply())
    }

    @Test
    fun `lastUserMessage取最后一条用户气泡`() {
        val c = AgentConversation()
        assertNull(c.lastUserMessage())
        c.addUser("第一问", false)
        c.addUser("（看图识别）", true)
        assertEquals("（看图识别）", c.lastUserMessage()?.text)
        assertTrue(c.lastUserMessage()!!.hasImage)
    }

    @Test
    fun `clear清屏`() {
        val c = AgentConversation()
        c.addUser("q", false)
        c.beginReply()
        c.clear()
        assertTrue(c.bubbles.isEmpty())
    }
}
