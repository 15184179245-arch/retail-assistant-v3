package com.retaildemo.v2.print

/**
 * 标签文字防溢出机制（2026-09-11，价签模版 v3）。
 *
 * 打印机不认识「文本框」——TEXT 指令从坐标点开始无限向右铺，超宽就是直接打飞、
 * 超行就是压到下一个元素上。所以溢出只能在生成指令前算准：本文件是唯一判据。
 *
 * 三层防线（由宽到窄依次生效，绝不允许「整块丢弃」）：
 * 1. 换行——按显示宽贪婪断行，尽量把内容完整装进 maxLines 行；
 * 2. 收口——真装不下时只在末行截断并以 `…` 结束，前面几行永远保持完整；
 * 3. 缩号——[fitForBox] 在 [minSize, maxSize] 里挑仍能装下的最大字号（价格这类
 *    长数字靠缩号保住全部字符，比截断更优先）。
 *
 * 宽度口径：TSS24.BF2 字库 @1x —— ASCII=12dot，CJK/全角=24dot（[charDots]）。
 * 与 `src/print/print_server.py` 的 `_disp_w/_fit_lines/_fit_scale` 同参数同几何。
 */
internal object LabelTextFit {

    /** 省略号：全角单字符（24dot@1x），GBK 字库有字形，中文标签语境正确。 */
    const val ELLIPSIS = "…"

    /** TSS24.BF2 @1x 单字符占宽：CJK/全角=24dot，ASCII=12dot。 */
    fun charDots(c: Char): Int = if (c.code > 0x7F) 24 else 12

    /** 清理打印机吃不下或会破坏指令流的字符（换行是元素的职责，不是字符的）。 */
    fun sanitize(s: String): String = s.filter { it != '\n' && it != '\r' && it != '\t' && it.code >= 0x20 }

    /** @1x 显示宽。 */
    fun textWidth(s: String): Int = s.fold(0) { w, c -> w + charDots(c) }

    /**
     * 按显示宽断行：[maxDots] 是**该字号下**的可用宽度（内部已乘 [mul]），
     * 最多 [maxLines] 行；装不下时末行以 [ELLIPSIS] 收口（换行代替截断）。
     */
    fun fitLines(text: String, maxDots: Int, maxLines: Int, mul: Int = 1): List<String> {
        val clean = sanitize(text)
        if (clean.isEmpty() || maxLines <= 0 || maxDots <= 0) return emptyList()
        val budget = maxDots / mul.coerceAtLeast(1)

        val wrapped = mutableListOf<String>()
        val cur = StringBuilder()
        var w = 0
        for (ch in clean) {
            val cw = charDots(ch)
            if (w + cw > budget && cur.isNotEmpty()) {
                wrapped.add(cur.toString())
                cur.clear()
                w = 0
            }
            cur.append(ch)
            w += cw
        }
        if (cur.isNotEmpty()) wrapped.add(cur.toString())
        if (wrapped.size <= maxLines) return wrapped

        // 溢出：保留前 maxLines-1 行完整，末行收到放得下省略号为止
        val kept = wrapped.subList(0, maxLines).toMutableList()
        val ellDots = charDots(ELLIPSIS[0])
        val last = StringBuilder()
        var lw = 0
        for (ch in kept.last()) {
            val cw = charDots(ch)
            if (lw + cw + ellDots > budget) break
            last.append(ch)
            lw += cw
        }
        kept[maxLines - 1] = last.toString() + ELLIPSIS
        return kept
    }

    /** 在 [minimum, preferred] 里取显示后不超 maxDots 的最大字号倍率。 */
    fun fitScale(text: String, maxDots: Int, preferred: Int, minimum: Int = 1): Int {
        val one = textWidth(sanitize(text))
        for (m in preferred downTo minimum) if (one * m <= maxDots) return m
        return minimum
    }

    /**
     * 把文本装进「[maxLines] 行 × [maxDots] 宽」的盒子，返回 (字号倍率, 行)：
     * 先取装得下的最大字号；真放不下再按 [minSize] 缩号，缩到 [minSize] 仍超则末行省略号。
     * [xmul]=横向拉伸倍率（加粗行占宽同步放大），返回的字号是**倍率前**的字号。
     */
    fun fitForBox(
        text: String,
        maxDots: Int,
        maxLines: Int,
        maxSize: Int = 1,
        minSize: Int = 1,
        xmul: Int = 1,
    ): Pair<Int, List<String>> {
        val clean = sanitize(text)
        if (clean.isEmpty() || maxLines <= 0 || maxDots <= 0) return 1 to emptyList()
        val lo = minSize.coerceAtLeast(1)
        for (size in maxSize.coerceAtLeast(lo) downTo lo) {
            if (fitsFully(clean, maxDots, maxLines, size * xmul)) {
                return size to fitLines(clean, maxDots, maxLines, size * xmul)
            }
        }
        return lo to fitLines(clean, maxDots, maxLines, lo * xmul)
    }

    /** 文本在 [size] 倍率下是否完整落进 maxLines 行（无省略号收口）。 */
    fun fitsFully(text: String, maxDots: Int, maxLines: Int, size: Int = 1): Boolean {
        val clean = sanitize(text)
        if (clean.isEmpty()) return true
        val budget = maxDots / size.coerceAtLeast(1)
        var lines = 1
        var w = 0
        for (ch in clean) {
            val cw = charDots(ch)
            if (w + cw > budget) {
                lines++
                w = 0
            }
            w += cw
        }
        return lines <= maxLines
    }
}
