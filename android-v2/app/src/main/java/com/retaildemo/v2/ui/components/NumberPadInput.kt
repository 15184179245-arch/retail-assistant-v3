package com.retaildemo.v2.ui.components

/**
 * 内置数字键盘的按键语义（纯函数，无 Android 依赖，可 JVM 单测）。
 *
 * 全 APP 数字输入的唯一口径：任何数字字段的编辑都必须走 [apply]，
 * 避免各页面各写一套 when 分支导致的小数点重复、前导 0、超长等口径漂移。
 */
object NumberPadInput {

    /** 整数位默认上限（9 位 ≈ 9.99 亿，店内任何数量/金额都够，且不撑爆 Int 解析）。 */
    const val MAX_INT_DIGITS = 9

    /** 电话等长号码的整数位上限（11 位手机号 + 区号/分机余量）。 */
    const val MAX_PHONE_DIGITS = 15

    /** 小数位上限（金额/散装计量到分）。 */
    const val MAX_DECIMALS = 2

    /** 退格键指令。 */
    const val KEY_BKSP = "BKSP"

    /** 清空键指令。 */
    const val KEY_CLEAR = "CLEAR"

    /** 完成键指令。 */
    const val KEY_DONE = "DONE"

    /**
     * 应用一次按键，返回新的文本。
     *
     * @param current 当前文本
     * @param key `'0'~'9'` / `'.'` / [KEY_BKSP] / [KEY_CLEAR]；未知键原样返回
     * @param decimals 是否允许小数点；false 时 `'.'` 被忽略（纯整数字段）
     * @param maxIntDigits 整数位上限（金额/数量 9；电话等长号码传 [MAX_PHONE_DIGITS]）
     */
    fun apply(
        current: String,
        key: String,
        decimals: Boolean = true,
        maxIntDigits: Int = MAX_INT_DIGITS,
    ): String = when (key) {
        KEY_BKSP -> current.dropLast(1)
        KEY_CLEAR -> ""
        KEY_DONE -> current
        "." -> when {
            !decimals -> current
            current.contains(".") -> current
            current.isEmpty() -> "0."
            else -> if (intDigits(current) >= maxIntDigits) current else "$current."
        }
        else -> appendDigit(current, key, maxIntDigits)
    }

    private fun intDigits(s: String): Int {
        val dot = s.indexOf('.')
        return if (dot < 0) s.length else dot
    }

    private fun appendDigit(current: String, key: String, maxIntDigits: Int): String {
        if (key.length != 1 || !key[0].isDigit()) return current
        // 前导 0 替换：0 → 5 得 "5" 而非 "05"；但 "0." 后必须照常拼接小数位
        if (current == "0") return key
        val dot = current.indexOf('.')
        if (dot >= 0) {
            // 已有小数点：只在补小数位，整数位不再增长
            return if (current.length - dot - 1 >= MAX_DECIMALS) current else current + key
        }
        if (current.length >= maxIntDigits) return current
        return current + key
    }
}
