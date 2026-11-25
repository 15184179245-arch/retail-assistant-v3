package com.retaildemo.v2.data.repo

import android.content.Context
import java.io.File

/**
 * CSV 导出工具（I4 新建，B4 未建故不复用、B4 建时可直接复用本文件）。
 * 治理闭环的物理出口：标记队列/补录清单走系统分享（微信/文件）给专员，照单去管家婆调整。
 */
object ExportUtils {
    /** 生成带 UTF-8 BOM 的 CSV 文本，Excel 直接打开不乱码。纯函数。 */
    fun toCsv(headers: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder("\uFEFF") // UTF-8 BOM
        sb.append(headers.joinToString(",") { csvCell(it) }).append("\n")
        rows.forEach { r -> sb.append(r.joinToString(",") { csvCell(it) }).append("\n") }
        return sb.toString()
    }

    private fun csvCell(v: String): String {
        val s = v.replace("\"", "\"\"")
        return if (s.contains(',') || s.contains('"') || s.contains('\n')) "\"$s\"" else s
    }

    /** 写 APP 缓存目录并返回 File（配合 FileProvider 分享，不碰外部存储权限）。 */
    fun writeCsvCache(context: Context, name: String, content: String): File {
        val f = File(context.cacheDir, name)
        f.writeText(content, Charsets.UTF_8)
        return f
    }
}
