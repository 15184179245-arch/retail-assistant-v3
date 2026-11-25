package com.retaildemo.v2.ui.pages

/**
 * 一帧识别的裁定（U9 重复扫码确认机制）。
 */
enum class ScanVerdict {
    /** 首次扫到：已入列，上层走成功反馈。 */
    NEW,

    /** 离场后回扫本次已扫过的码：不入列，上层只给一条「未计入」提醒（不提供再计入口）。 */
    DUPLICATE,

    /** 被同码离场门/帧级防抖吞掉：静默忽略。 */
    IGNORED,
}

/** [ScanBuffer.observe] 的裁定结果：针对帧内首个可读码。 */
data class ScanDecision(val code: String, val verdict: ScanVerdict)

/**
 * 连续扫码缓冲区（巴枪范式，纯 Kotlin 无 Android 依赖，便于 JVM 单测）。
 *
 * 规则（U9 重复扫码确认机制，对标 Zebra/Honeywell 工业扫码「同码离场再读」）：
 * 1. 同一次目击只计一次——相机每秒 30 帧，同一件商品会被 ML Kit 连续识别几十次；
 *    已入列的码只要还在取景框里（目击间隔不足 [DEFAULT_RESEEN_GAP_MS]），视为同一次目击，不再入列；
 * 2. 码离开画面满离场间隔后回扫：本次已扫过 → [ScanVerdict.DUPLICATE]，**不默默 +1**，
 *    上层只给一条「未计入」提醒（老板 2026-09-20：「重复扫描提醒不额外录」）。
 *    原 U9 的「再计一件 / 忽略」确认条已废 —— 加数量一律到清单里手动改。
 * 3. 未入列的码第一次被裁定就入列（[ScanVerdict.NEW]），不受离场门影响——与已扫码同框路过，
 *    不该吞掉它自己的第一次；
 * 4. 帧级防抖 [DEFAULT_DEBOUNCE_MS] 保留为兜底（对焦丢失导致目击帧中断等异常场景）。
 */
class ScanBuffer(
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val reseenGapMs: Long = DEFAULT_RESEEN_GAP_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val codes = mutableListOf<String>()
    private val lastAcceptedAt = mutableMapOf<String, Long>()

    /** 每码最近一次画面目击时刻：帧帧刷新，码离开画面后停更——离场门的依据。 */
    private val lastSeenAt = mutableMapOf<String, Long>()

    /** 已入列条码快照（按扫码顺序，含确认后的重复件）。 */
    fun snapshot(): List<String> = codes.toList()

    /** 已扫件数。 */
    val size: Int
        get() = codes.size

    /** 最后扫到的一件；空则返回 null。 */
    fun last(): String? = codes.lastOrNull()

    /**
     * **忘掉一个码**（连"已入列"与目击记录一起清）。
     *
     * ★ 为什么必须有（2026-09-26 加「每扫一件确认一次」时发现的连带缺口）：
     *   [observe] 在裁定 [ScanVerdict.NEW] 的那一刻就把码记进了 `codes` ——
     *   那是**读到**的时刻，而在"扫一件确认一件"的口径下，**读到不等于算数**。
     *   店员点了「不算」（或该件被新码顶替）之后，这个码在 buffer 里仍被当作"已扫过"，
     *   再扫只会得到 [ScanVerdict.DUPLICATE]（卡上写「本次未计入」）——
     *   **误扫之后这件货就再也扫不进来了**，只能绕去手动输入。
     *   误扫→纠正是最常见的动作，必须让它能原路重来。
     */
    fun forget(code: String) {
        codes.remove(code)
        lastAcceptedAt.remove(code)
        lastSeenAt.remove(code)
    }

    /**
     * 每帧调用一次：对帧内首个可读码裁定，并记录本帧全部目击。
     *
     * @param frameCodes 当前帧识别出的全部条码原文（可含 null/空白；空帧=没认出东西）。
     * @return 帧内无可读码返回 null，否则返回首个可读码的 [ScanDecision]。
     */
    fun observe(frameCodes: List<String?>): ScanDecision? {
        val t = now()
        val primary = frameCodes.firstNotNullOfOrNull { it?.trim()?.takeIf { c -> c.isNotEmpty() } }
        val decision = primary?.let { code ->
            val seen = lastSeenAt[code]
            val accepted = lastAcceptedAt[code]
            val known = codes.contains(code)
            when {
                // 同码离场门：上帧目击距今不足一个回头间隔 = 同一次目击（相机还盯着/刚离开）
                known && seen != null && t - seen < reseenGapMs -> ScanDecision(code, ScanVerdict.IGNORED)
                // 帧级防抖兜底
                known && accepted != null && t - accepted < debounceMs -> ScanDecision(code, ScanVerdict.IGNORED)
                known -> {
                    // 离场后回扫已扫过的码：不默默 +1，交确认条
                    lastSeenAt[code] = t
                    lastAcceptedAt[code] = t
                    ScanDecision(code, ScanVerdict.DUPLICATE)
                }
                else -> {
                    lastSeenAt[code] = t
                    lastAcceptedAt[code] = t
                    codes.add(code)
                    ScanDecision(code, ScanVerdict.NEW)
                }
            }
        }
        frameCodes.forEach { raw ->
            val code = raw?.trim().orEmpty()
            if (code.isNotEmpty()) lastSeenAt[code] = t
        }
        return decision
    }

    companion object {
        /** 同码帧级防抖兜底窗口（毫秒）。 */
        const val DEFAULT_DEBOUNCE_MS: Long = 800L

        /**
         * 同码离场门（毫秒）：已入列的码离开取景框满这么久再被读到，才算「新的目击」。
         * 人手重新对准最快 ~1s（旧防抖注释实测），取 1200ms 留余量；
         * 对标 Zebra「Timeout Between Same Symbols」1000~3000ms 的下限区间。
         */
        const val DEFAULT_RESEEN_GAP_MS: Long = 1200L
    }
}

/**
 * 条码结果解析：连续模式回传 [ScannerActivity.EXTRA_BARCODES]（批量），
 * 旧调用点或异常情况下兜底 [ScannerActivity.EXTRA_BARCODE]（单个）。
 *
 * 纯函数，便于单测。
 */
fun parseScanResult(data: android.content.Intent?): List<String> {
    if (data == null) return emptyList()
    val batch = data.getStringArrayListExtra(ScannerActivity.EXTRA_BARCODES)
    if (!batch.isNullOrEmpty()) {
        return batch.map { it.trim() }.filter { it.isNotEmpty() }
    }
    val single = data.getStringExtra(ScannerActivity.EXTRA_BARCODE)
    return if (single.isNullOrBlank()) emptyList() else listOf(single.trim())
}

/**
 * 从识别文字里提取连续数字串（2 位及以上），去重后按长度降序。
 *
 * 手写码/手写条码优先精确匹配：长条码（8~13 位）先试，短编号（店内码）后试。
 *
 * 纯函数，便于 JVM 单测。
 */
fun extractDigitTokens(text: String): List<String> {
    return Regex("\\d{2,}")
        .findAll(text)
        .map { it.value }
        .toList()
        .distinct()
        .sortedByDescending { it.length }
}
