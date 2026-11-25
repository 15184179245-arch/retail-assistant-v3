package com.retaildemo.v2.nfc

import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable

/**
 * I20 写贴引擎（Android 侧，ReaderMode 回调线程同步调用）。
 *
 * 老板 2026-09-14 实测三连反馈的根因都修在这里：
 *
 * 1. **「不能覆盖写入」**：旧实现写前先判 `isWritable`，只读贴直接拒写；能写的贴即便覆盖成功，
 *    也被下一条的假阴性判成"建议换一张贴"。现在：贴内已有内容先走
 *    [ShelfNfc.overwriteDecision] —— 同一位置免重写（ALREADY），旧内容直接覆盖（REPLACE），
 *    只有真正锁死的贴才拒绝，并把贴里现存的旧内容讲给用户听。
 * 2. **「写入的和读取的不一致」**：旧实现在 `Ndef.connect()` 的同一条会话里读 `ndefMessage`
 *    做复核 ——  discovery 时的 Tag 对象缓存的是**写入前**的内容，覆盖旧贴必然"读回不一致"。
 *    现在写完先 `close()`，再用全新的 `Ndef.get(tag)` 会话重读，读到才是贴片上的真值。
 * 3. **「写入成功没有明显交互」**：返回带语义状态的结构化结果（[Result.status]），
 *    页面据此渲染大字结果面板 + 语义音效，不再只改底部一行小字。
 */
object ShelfTagWriter {

    enum class Status {
        /** 写入并读回核对一致。 */
        OK,

        /** 贴里已经是这个位置码，没重写（碰它就能直达）。 */
        ALREADY,

        /** 写入指令完成但没能读回核对（空贴首次格式化、或贴被拿开太快）——需再碰一次确认。 */
        UNVERIFIED,

        /** 贴本身写不了（只读锁死/不是 NDEF 贴/容量不足），换贴。 */
        REJECTED,

        /** 通信失败等可重试错误（贴移动太快等）。 */
        FAILED,
    }

    data class Result(
        val status: Status,
        /** 大字标题（结果面板第一行）。 */
        val title: String,
        /** 小字细节（贴内旧内容、读回值、下一步动作）。 */
        val detail: String,
        val readBack: String?,
    )

    private fun ok(title: String, detail: String, readBack: String?) = Result(Status.OK, title, detail, readBack)
    private fun reject(detail: String, readBack: String? = null) = Result(Status.REJECTED, "这张贴写不了", detail, readBack)
    private fun fail(title: String, detail: String) = Result(Status.FAILED, title, detail, null)

    /**
     * 写入 [payload]（`SX:<tagId>`）并复读核对。永不抛异常 —— 一切失败都进 [Result]。
     * 在 ReaderMode 回调线程同步跑（单次写贴几百毫秒，贴离位则失败重试）。
     */
    fun write(tag: Tag, payload: String?): Result {
        if (payload == null) {
            return reject("目标位置的稳定码不合法，已阻止写入（避免写坏一张贴）")
        }
        val msg = textMessage(payload)

        return try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                try {
                    val before = runCatching { extractText(ndef.ndefMessage) }.getOrNull()
                    when (ShelfNfc.overwriteDecision(before, payload)) {
                        ShelfNfc.Overwrite.SAME -> return Result(
                            Status.ALREADY,
                            "这张贴已经指向这里",
                            "贴里就是 $before，不用重写 —— 直接碰它就能到。",
                            before,
                        )
                        else -> {}
                    }
                    if (!ndef.isWritable) {
                        return reject(
                            if (before.isNullOrBlank()) "空白贴被锁成只读了，换一张。"
                            else "贴里还是旧内容「$before」，且被锁成只读覆盖不了 —— 换一张新贴。",
                            before,
                        )
                    }
                    if (ndef.maxSize in 1 until msg.toByteArray().size) {
                        return reject("这张贴容量装不下定位码，换 NTAG213 及以上规格。", null)
                    }
                    // 核心执行覆盖写入
                    ndef.writeNdefMessage(msg)

                    val note = if (before.isNullOrBlank()) "" else "已覆盖旧内容「$before」。"

                    // 写完立即在当前会话尝试读回复核
                    var readBack = runCatching { extractText(ndef.ndefMessage) }.getOrNull()
                    // 如果当前会话读出的还是旧值或空，尝试重新连接一次（部分芯片缓存机制）
                    if (readBack == null || ShelfNfc.overwriteDecision(readBack, payload) != ShelfNfc.Overwrite.SAME) {
                        runCatching { ndef.close() }
                        readBack = readBackText(tag)
                    }

                    return when {
                        readBack == null -> Result(
                            Status.UNVERIFIED,
                            "已写入（未读到复核）",
                            "写入指令已完成！请拿开后再碰一次这张贴确认。$note",
                            null,
                        )
                        ShelfNfc.overwriteDecision(readBack, payload) == ShelfNfc.Overwrite.SAME ->
                            ok("写入成功", "读回 $readBack，与库中一致 ✓ $note", readBack)
                        else -> fail(
                            "写入后核对不上",
                            "贴里读到的是「${readBack}」，和要写的 $payload 不一致 —— 换一张贴重试。",
                        )
                    }
                } finally {
                    runCatching { ndef.close() }
                }
            } else {
                // 标签未格式化（空白卡）：使用 NdefFormatable
                val fmt = NdefFormatable.get(tag)
                    ?: return reject("这张贴不认识（既不是 NDEF 贴也不支持 NDEF 格式化），请换用普通 NTAG 贴")
                try {
                    fmt.connect()
                    fmt.format(msg)
                } finally {
                    runCatching { fmt.close() }
                }
                val readBack = readBackText(tag)
                return if (readBack != null && ShelfNfc.overwriteDecision(readBack, payload) == ShelfNfc.Overwrite.SAME) {
                    ok("格式化并写入成功", "新贴已初始化，读回 $readBack ✓", readBack)
                } else {
                    Result(
                        Status.UNVERIFIED,
                        "新贴已格式化并写入",
                        "这是一张空白新贴，已写入 $payload。请再碰一次确认能否直达。",
                        null,
                    )
                }
            }
        } catch (e: FormatException) {
            fail("贴的格式不认识", "NDEF 格式异常（${e.message ?: "FormatException"}），换一张新贴")
        } catch (e: Exception) {
            fail("写入中断", "${e.message ?: e.javaClass.simpleName} —— 贴移开太快或接触不良，贴稳在手机背面再试一次")
        }
    }

    /** 全新会话读回贴内文本；读不到（贴不在位/非 NDEF）返回 null。 */
    private fun readBackText(tag: Tag): String? = runCatching {
        val n = Ndef.get(tag) ?: return null
        try {
            n.connect()
            extractText(n.ndefMessage)
        } finally {
            runCatching { n.close() }
        }
    }.getOrNull()

    fun textMessage(payload: String): NdefMessage {
        val record = NdefRecord.createTextRecord("en", payload)
        return NdefMessage(arrayOf(record))
    }

    /** 从 NDEF 文本记录里取回文本（跳过语言码前缀；与 MainActivity 的读侧解析同规则）。 */
    private fun extractText(msg: NdefMessage?): String? {
        if (msg == null) return null
        for (record in msg.records) {
            val payload = record.payload ?: continue
            if (payload.isEmpty()) continue
            val type = record.type ?: ByteArray(0)
            val isText = record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                type.contentEquals(NdefRecord.RTD_TEXT)
            return if (isText) {
                val langLen = payload[0].toInt() and 0x3F
                if (payload.size > 1 + langLen) {
                    String(payload, 1 + langLen, payload.size - 1 - langLen, Charsets.UTF_8)
                } else {
                    String(payload, Charsets.UTF_8)
                }
            } else {
                String(payload, Charsets.UTF_8)
            }
        }
        return null
    }
}
