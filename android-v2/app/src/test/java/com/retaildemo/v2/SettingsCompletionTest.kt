package com.retaildemo.v2

import com.retaildemo.v2.ui.viewmodel.SettingsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * U6 设置页功能补全：锁定三块纯逻辑——
 * 上次同步时间文案（0=引导文案）、清理结果文案（成败+详情拼装）、缓存递归清理（字节数+空壳目录回收）。
 */
class SettingsCompletionTest {

    @Test
    fun `从未同步给引导文案而不是1970`() {
        val s = SettingsViewModel.formatLastSync(0L)
        assertTrue(s.contains("从未同步"))
        assertTrue(!s.contains("1970"))
    }

    @Test
    fun `同步时间按年月日时分格式化`() {
        val cal = java.util.Calendar.getInstance().apply { set(2026, 8, 11, 14, 30, 0) } // 2026-09-11
        val s = SettingsViewModel.formatLastSync(cal.timeInMillis)
        assertTrue("实际文案：$s", s.contains("2026-09-11"))
        assertTrue("实际文案：$s", s.contains("14:30"))
    }

    @Test
    fun `清理文案成功带勾失败带原因`() {
        assertTrue(
            SettingsViewModel.cleanupMessage(SettingsViewModel.KIND_CACHE, true, "释放 1.5 MB")
                .contains("✓"),
        )
        assertTrue(
            SettingsViewModel.cleanupMessage(SettingsViewModel.KIND_CACHE, true, "释放 1.5 MB")
                .contains("1.5 MB"),
        )
        val rebuildFail = SettingsViewModel.cleanupMessage(SettingsViewModel.KIND_REBUILD, false, "拉取失败")
        assertTrue(rebuildFail.contains("未动"))
        assertTrue(rebuildFail.contains("拉取失败"))
        // 无详情时不留空括号
        assertTrue(!SettingsViewModel.cleanupMessage(SettingsViewModel.KIND_HISTORY, true).contains("（"))
    }

    @Test
    fun `递归清空缓存目录并统计释放字节数`() {
        val root = File(
            File.createTempFile("u6probe", "").parentFile,
            "u6cache-${System.nanoTime()}",
        ).apply { mkdirs() }
        try {
            File(root, "a.txt").writeText("12345") // 5B
            File(root, "sub").mkdirs()
            File(root, "sub/b.bin").writeText("1234567") // 7B
            val freed = SettingsViewModel.deleteDirContents(root)
            assertEquals(12L, freed)
            assertTrue("根目录本身保留", root.exists())
            assertTrue("内容应清空", root.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `清缓存对null与不存在路径安全`() {
        assertEquals(0L, SettingsViewModel.deleteDirContents(null))
        assertEquals(0L, SettingsViewModel.deleteDirContents(File("u6/definitely/not/exist")))
    }
}
