package com.retaildemo.v2

import com.retaildemo.v2.data.local.entity.ClientLogEntity
import com.retaildemo.v2.data.repo.LogPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H2（2026-09-11 老板令）日志管线纯函数单测：级别归一、消息截断、上传分批。
 * Room/Worker 链路靠编译与真机验证（遥测路径，失败静默不打扰业务）。
 */
class LogPipelineTest {

    // ---------- normalizeLevel ----------

    @Test
    fun `normalizeLevel passes known levels and uppercases`() {
        assertEquals("DEBUG", LogPipeline.normalizeLevel("debug"))
        assertEquals("INFO", LogPipeline.normalizeLevel("INFO"))
        assertEquals("WARN", LogPipeline.normalizeLevel(" warn "))
        assertEquals("ERROR", LogPipeline.normalizeLevel("Error"))
    }

    @Test
    fun `normalizeLevel maps unknown to INFO`() {
        assertEquals("INFO", LogPipeline.normalizeLevel("fatal"))
        assertEquals("INFO", LogPipeline.normalizeLevel(""))
        assertEquals("INFO", LogPipeline.normalizeLevel("WARRR"))
    }

    // ---------- sanitizeMessage ----------

    @Test
    fun `sanitizeMessage trims and keeps short messages intact`() {
        assertEquals("hello 世界", LogPipeline.sanitizeMessage("  hello 世界  "))
        assertTrue(LogPipeline.sanitizeMessage("").isEmpty())
    }

    @Test
    fun `sanitizeMessage truncates oversized with marker`() {
        val big = "x".repeat(LogPipeline.MAX_MESSAGE + 500)
        val out = LogPipeline.sanitizeMessage(big)
        assertEquals(LogPipeline.MAX_MESSAGE + "...[truncated]".length, out.length)
        assertTrue(out.endsWith("...[truncated]"))
    }

    // ---------- chunk ----------

    @Test
    fun `chunk returns empty for empty input`() {
        assertTrue(LogPipeline.chunk(emptyList()).isEmpty())
    }

    @Test
    fun `chunk keeps small batches as one`() {
        val records = (1..10L).map { ClientLogEntity(id = it, ts = it, level = "INFO", tag = "t", message = "m") }
        val chunks = LogPipeline.chunk(records)
        assertEquals(1, chunks.size)
        assertEquals(10, chunks[0].size)
    }

    @Test
    fun `chunk splits large batches respecting the cap`() {
        val n = LogPipeline.MAX_UPLOAD_BATCH * 3 + 7
        val records = (1..n.toLong()).map { ClientLogEntity(id = it, ts = it, level = "INFO", tag = "t", message = "m") }
        val chunks = LogPipeline.chunk(records)
        assertEquals(4, chunks.size)
        assertTrue(chunks.all { it.size <= LogPipeline.MAX_UPLOAD_BATCH })
        assertEquals(n, chunks.sumOf { it.size }) // 不丢不重
        assertEquals(chunks.flatMap { it }.map { it.id }, records.map { it.id }) // 保序
    }
}
