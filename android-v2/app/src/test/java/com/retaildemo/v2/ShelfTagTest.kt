package com.retaildemo.v2

import com.retaildemo.v2.nfc.ShelfNfc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I20 货架标签编解码（纯函数，JVM 直测，不碰 android.nfc）。
 *
 * 这是「名字完全自定义」能不能成立的技术底座：贴里写的是稳定 tagId（`SX:<tagId>`），
 * 与显示名解耦；同时必须**兼容旧贴**（上一代写的是裸货位码 `A-01` / `SHELF:A`），旧贴不能废。
 */
class ShelfTagTest {

    // --------------------------- 编码 ---------------------------

    @Test
    fun `encode wraps tagId with SX prefix`() {
        assertEquals("SX:AB23CD45", ShelfNfc.encodeTagPayload("AB23CD45"))
    }

    @Test
    fun `encode normalizes case and trims`() {
        assertEquals("SX:AB23CD45", ShelfNfc.encodeTagPayload("  ab23cd45  "))
    }

    @Test
    fun `encode rejects blank and whitespace-bearing input`() {
        assertNull(ShelfNfc.encodeTagPayload(""))
        assertNull(ShelfNfc.encodeTagPayload("   "))
        assertNull(ShelfNfc.encodeTagPayload("has space"))
        assertNull(ShelfNfc.encodeTagPayload("bad\\slash"))
    }

    // --------------------------- 解码：新格式 ---------------------------

    @Test
    fun `decode round-trips encoded payload`() {
        val tag = "Z8Y7X6W5"
        val payload = ShelfNfc.encodeTagPayload(tag)!!
        assertEquals(tag, ShelfNfc.decodeTagPayload(payload))
    }

    @Test
    fun `decode tolerates surrounding whitespace and lowercase prefix`() {
        assertEquals("AB23CD45", ShelfNfc.decodeTagPayload("  SX:AB23CD45  "))
        assertEquals("AB23CD45", ShelfNfc.decodeTagPayload("sx:ab23cd45"))
        assertEquals("AB23CD45", ShelfNfc.decodeTagPayload("Sx:Ab23Cd45"))
    }

    @Test
    fun `decode rejects empty SX payload prefix`() {
        assertNull(ShelfNfc.decodeTagPayload("SX:"))
        assertNull(ShelfNfc.decodeTagPayload("SX:   "))
    }

    // --------------------------- 解码：旧贴兼容（旧贴不废） ---------------------------

    @Test
    fun `decode still understands legacy naked slot codes`() {
        assertEquals("A-01", ShelfNfc.decodeTagPayload("A-01"))
        assertEquals("A-01", ShelfNfc.decodeTagPayload("a-01"))
        assertEquals("B2", ShelfNfc.decodeTagPayload("b2"))
        assertEquals("A-1-2", ShelfNfc.decodeTagPayload("A-1-2"))
    }

    @Test
    fun `decode strips optional legacy SHELF prefix`() {
        assertEquals("A", ShelfNfc.decodeTagPayload("SHELF:A"))
        assertEquals("A-1", ShelfNfc.decodeTagPayload("shelf:a-1"))
    }

    // --------------------------- 解码：拒绝垃圾 ---------------------------

    @Test
    fun `decode rejects garbage and never throws`() {
        assertNull(ShelfNfc.decodeTagPayload(""))
        assertNull(ShelfNfc.decodeTagPayload("   "))
        assertNull(ShelfNfc.decodeTagPayload("hello world"))
        assertNull(ShelfNfc.decodeTagPayload("这是一个中文标签名"))
        assertNull(ShelfNfc.decodeTagPayload("A 01"))
        assertNull(ShelfNfc.decodeTagPayload("A--1")) // 连续连字符非法（旧契约遗留边界）
        assertNull(ShelfNfc.decodeTagPayload("-"))
    }

    // --------------------------- 格式判定 ---------------------------

    @Test
    fun `isTagPayload distinguishes new tags from legacy codes`() {
        assertTrue(ShelfNfc.isTagPayload("SX:AB23CD45"))
        assertTrue(ShelfNfc.isTagPayload("  sx:AB23CD45  "))
        assertFalse(ShelfNfc.isTagPayload("A-01"))
        assertFalse(ShelfNfc.isTagPayload("SHELF:A"))
    }

    /**
     * 关键契约：**名字改了不影响定位**。
     * 载荷只由 tagId 决定，与位置叫什么完全无关 —— 这是「改名免重贴」的形式化表达。
     */
    @Test
    fun `payload depends only on tagId not on display name`() {
        val tagId = "QQ33WW44"
        // 无论这个位置叫「A架」还是「门口那排」，写进贴的都是同一个值
        val payload = ShelfNfc.encodeTagPayload(tagId)
        assertEquals("SX:QQ33WW44", payload)
        assertEquals(tagId, ShelfNfc.decodeTagPayload(payload!!))
    }

    // --------------------------- 一张贴多条记录：全记录里挑位置码 ---------------------------
    //
    // 老板 2026-09-14 实测反馈「碰一下只是调整到货架页、不是跳转到指定货架」的根因之一：
    // 一张贴可以有多条 NDEF 记录（第三方写贴工具常写「URL + 文本」），旧实现只认第一条文本，
    // 第一条不是位置码就整张贴作废 —— 而界面照样跳到货架页，看起来就是"没跳过去"。

    @Test
    fun `pickTagPayload finds code in any record not just the first`() {
        // 第一条是 URL（第三方工具写的），位置码在第二条
        val picked = ShelfNfc.pickTagPayload(
            listOf("https://example.com/t/123", "SX:AB23CD45"),
        )
        assertEquals("SX:AB23CD45", picked)
        assertEquals("AB23CD45", ShelfNfc.decodeTagPayload(picked!!))
    }

    @Test
    fun `pickTagPayload skips blank and undecodable records`() {
        assertEquals(
            "SX:AB23CD45",
            ShelfNfc.pickTagPayload(listOf(null, "", "   ", "hello world", "SX:AB23CD45")),
        )
    }

    @Test
    fun `pickTagPayload accepts legacy naked code`() {
        assertEquals("A-01", ShelfNfc.pickTagPayload(listOf("第一行不是码", "A-01")))
    }

    @Test
    fun `pickTagPayload skips malformed SX record and keeps looking`() {
        // "SX:   " 是解不出的 SX 载荷 —— 不能因为它就整张贴作废
        assertEquals("B2", ShelfNfc.pickTagPayload(listOf("SX:   ", "B2")))
    }

    @Test
    fun `pickTagPayload returns null when nothing is a location code`() {
        assertNull(ShelfNfc.pickTagPayload(emptyList()))
        assertNull(ShelfNfc.pickTagPayload(listOf(null, "", "https://example.com")))
        assertNull(ShelfNfc.pickTagPayload(listOf("这是一个中文标签名", "hello world")))
    }

    @Test
    fun `pickTagPayload trims the picked record`() {
        assertEquals("SX:AB23CD45", ShelfNfc.pickTagPayload(listOf("  SX:AB23CD45  ")))
    }

    // --------------------------- 覆盖写判定（老板 2026-09-14「不能覆盖写入」修复的判定核） ---------------------------

    private val same = ShelfNfc.encodeTagPayload("AB23CD45")!!

    @Test
    fun `overwrite empty tag needs write`() {
        assertEquals(ShelfNfc.Overwrite.EMPTY, ShelfNfc.overwriteDecision(null, same))
        assertEquals(ShelfNfc.Overwrite.EMPTY, ShelfNfc.overwriteDecision("", same))
        assertEquals(ShelfNfc.Overwrite.EMPTY, ShelfNfc.overwriteDecision("   ", same))
    }

    @Test
    fun `overwrite same target recognized regardless of case padding or legacy form`() {
        // 三种写法解出的都是 AB23CD45 —— 都算"贴已经指向这里"，不必重写
        assertEquals(ShelfNfc.Overwrite.SAME, ShelfNfc.overwriteDecision(same, same))
        assertEquals(ShelfNfc.Overwrite.SAME, ShelfNfc.overwriteDecision("  sx:ab23cd45 ", same))
        assertEquals(ShelfNfc.Overwrite.SAME, ShelfNfc.overwriteDecision("AB23CD45", same)) // 裸码旧贴
    }

    @Test
    fun `overwrite different target replaces`() {
        val other = ShelfNfc.encodeTagPayload("Z8Y7X6W5")!!
        assertEquals(ShelfNfc.Overwrite.REPLACE, ShelfNfc.overwriteDecision(same, other))
    }

    @Test
    fun `overwrite replaces unreadable third-party content`() {
        // 第三方工具写的 URL / 中文垃圾：解不出位置码 → 覆盖掉才能救回这张贴
        assertEquals(ShelfNfc.Overwrite.REPLACE, ShelfNfc.overwriteDecision("https://example.com/t/1", same))
        assertEquals(ShelfNfc.Overwrite.REPLACE, ShelfNfc.overwriteDecision("这是别的店写的", same))
    }

    @Test
    fun `overwrite legacy old code replaced by new tagId`() {
        // 旧裸码 A-01 换到新稳定码体系：不同位置 → 覆盖（旧贴能救回来用）
        assertEquals(ShelfNfc.Overwrite.REPLACE, ShelfNfc.overwriteDecision("A-01", same))
    }

    @Test
    fun `overwrite corrupt leading colon payload replaced by new payload`() {
        // 曾因语言码未占位导致写入成了 `:AB23CD45`（少了 SX）的脏贴，必须判定为 REPLACE 覆盖救回
        assertEquals(ShelfNfc.Overwrite.REPLACE, ShelfNfc.overwriteDecision(":AB23CD45", same))
    }

    // --------------------------- 官方 NdefRecord 文本记录标准编码解码对称性 ---------------------------

    @Test
    fun `text record standard payload encoding preserves SX prefix without loss`() {
        val payload = "SX:AB23CD45"
        // 模拟标准 NDEF 文本记录字节流（状态字节+语言码+文本）
        val lang = "en".toByteArray(Charsets.US_ASCII)
        val textBytes = payload.toByteArray(Charsets.UTF_8)
        val statusByte = (lang.size and 0x3F).toByte()
        val recordPayload = byteArrayOf(statusByte) + lang + textBytes

        // 模拟解码逻辑（MainActivity & ShelfTagWriter 一致的提取逻辑）
        val langLen = recordPayload[0].toInt() and 0x3F
        val extracted = String(recordPayload, 1 + langLen, recordPayload.size - 1 - langLen, Charsets.UTF_8)

        // 必须完整读回 SX:AB23CD45，绝对不能丢掉 SX 变成 :AB23CD45
        assertEquals(payload, extracted)
        assertEquals("AB23CD45", ShelfNfc.decodeTagPayload(extracted))
    }
}
