package com.retaildemo.v2

import com.retaildemo.v2.util.CrashQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-09-11 老板令：崩溃收集上报——落盘队列（JSONL + 容量护栏 + 上报清除）纯 JVM 契约。
 * 「落盘先行、启动补传」链路的本机半场；网络半场由 Retrofit 层契约保证。
 */
class CrashQueueTest {

    private fun newBase(): File =
        File(System.getProperty("java.io.tmpdir"), "sx-crash-q-${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun `append and pending roundtrip preserves lines`() {
        val base = newBase()
        CrashQueue.append(base, """{"crash":"boom-1","thread":"main"}""")
        CrashQueue.append(base, """{"crash":"boom-2","thread":"DefaultDispatcher"}""")
        val pending = CrashQueue.pending(base)
        assertEquals(2, pending.size)
        assertTrue(pending[0].contains("boom-1"))
        assertTrue(pending[1].contains("boom-2"))
    }

    @Test
    fun `pending on missing directory returns empty not throw`() {
        assertTrue(CrashQueue.pending(File(System.getProperty("java.io.tmpdir"), "sx-never-${System.nanoTime()}")).isEmpty())
    }

    @Test
    fun `append caps queue by keeping newest half`() {
        val base = newBase()
        // 256KB 上限：写 64KB 一条 × 多条，必触发砍半
        repeat(8) { CrashQueue.append(base, """{"crash":"${"x".repeat(64 * 1024)}","n":$it}""") }
        val pending = CrashQueue.pending(base)
        assertTrue(pending.size < 8)               // 砍过半
        assertTrue(pending.last().contains("\"n\":7")) // 最新一条必在
        assertTrue(CrashQueue.file(base).length() <= 256 * 1024 + 1024)
    }

    @Test
    fun `keepOnly empty deletes file and partial rewrites remainder`() {
        val base = newBase()
        CrashQueue.append(base, """{"crash":"a"}""")
        CrashQueue.append(base, """{"crash":"b"}""")
        CrashQueue.keepOnly(base, listOf("""{"crash":"b"}"""))
        assertEquals(listOf("""{"crash":"b"}"""), CrashQueue.pending(base))
        CrashQueue.keepOnly(base, emptyList())
        assertFalse(CrashQueue.file(base).exists())
        // 删完再 append 要能自愈重建
        CrashQueue.append(base, """{"crash":"c"}""")
        assertEquals(1, CrashQueue.pending(base).size)
    }
}
