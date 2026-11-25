package com.retaildemo.v2.nfc

/**
 * I20 货架标签编解码（纯函数，JVM 可测，不依赖 android.nfc）。
 *
 * ## 为什么载荷是 tagId 而不是名字
 * 老板拍板「货架号/层号/格号完全自定义」→ 名字随时可改。若把名字写进贴，改一次名全店贴作废。
 * 故贴里写的是创建即生成、**永不改变**的 `tagId`，载荷格式 `SX:<tagId>`；显示名只活在 APP 里。
 *
 * ## 与旧贴的兼容
 * 上一代实现写的是裸货位码（`A-01`）/可选 `SHELF:` 前缀。旧贴不废：
 * [decodeTagPayload] 认不出 `SX:` 前缀时回落到旧规则解析，旧贴仍能碰出结果。
 *
 * ## 契约
 * - APP 生成的 tagId 一律是大写 `A-Z0-9`（8 位，去掉易混字符）。
 * - [decodeTagPayload] 对 `SX:` 载荷统一转大写后返回，与库中存值一致。
 */
object ShelfNfc {

    const val PREFIX = "SX:"

    /** 新式 tagId 允许的字符集：字母数字与 - _（保守放宽，便于将来换生成策略）。 */
    private val TAG_RE = Regex("^[A-Za-z0-9_-]{1,64}$")

    /** 旧式裸码：`A`、`B2`、`A-01`、`A-1-2`。 */
    private val LEGACY_CODE_RE = Regex("^[A-Z0-9]+(-[A-Z0-9]+)*$")

    /**
     * 写贴载荷 = `SX:<tagId>`。非法（空/含空白或非法字符）返回 null —— 写贴页据此拒绝写入。
     */
    fun encodeTagPayload(tagId: String): String? {
        val t = tagId.trim()
        if (t.isEmpty() || !TAG_RE.matches(t)) return null
        return PREFIX + t.uppercase()
    }

    /**
     * 碰贴解码：容忍前后空白与大小写。
     * - `SX:xxxx` → 返回大写 tagId
     * - 旧裸码（`A-01` / `SHELF:A`）→ 兼容返回大写旧码（旧贴不废）
     * - 其余 → null（未知标签绝不崩）
     */
    fun decodeTagPayload(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null

        if (s.startsWith(PREFIX, ignoreCase = true)) {
            val t = s.substring(PREFIX.length).trim()
            return if (t.isNotEmpty() && TAG_RE.matches(t)) t.uppercase() else null
        }

        // 旧贴兼容：剥可选 SHELF: 前缀，要求形如 A / B2 / A-01
        var legacy = s.uppercase()
        if (legacy.startsWith("SHELF:")) legacy = legacy.removePrefix("SHELF:")
        return if (legacy.isNotEmpty() && LEGACY_CODE_RE.matches(legacy)) legacy else null
    }

    /** 是否为本代 `SX:` 载荷（用于区分新贴与旧贴，决定走本地 id 查找还是旧码回退）。 */
    fun isTagPayload(raw: String): Boolean = raw.trim().startsWith(PREFIX, ignoreCase = true)

    /**
     * 写贴前对贴内**已有内容**的处置判定（老板 2026-09-14 反馈「不能覆盖写入」的核心判定）。
     *
     * 比较走 [decodeTagPayload] 归一化：`sx:ab` `/ `SX:AB`` / 裸码 `AB` 都算"已经指向同一位置"，
     * 不必重写；旧裸码与新 `SX:` 码即使解出同一个码也不强改（旧贴不废原则）。
     * 解不出的内容（第三方工具的 URL 等）一律 REPLACE —— 覆盖掉才能救回这张贴。
     */
    fun overwriteDecision(existing: String?, payload: String): Overwrite {
        val e = existing?.trim().orEmpty()
        if (e.isEmpty()) return Overwrite.EMPTY
        val from = decodeTagPayload(e)
        val to = decodeTagPayload(payload)
        return if (from != null && from == to) Overwrite.SAME else Overwrite.REPLACE
    }

    enum class Overwrite { EMPTY, SAME, REPLACE }

    /**
     * 从一张贴的**全部** NDEF 记录文本里挑出位置码载荷（原始串；调用方再 [decodeTagPayload]）。
     *
     * 为什么必须遍历而不是取第一条（老板 2026-09-14 实测反馈的根因之一）：
     * 一张贴里可以有多条记录 —— 第三方写贴工具常写「URL + 文本」两条，
     * 贴也可能被别的工具追加过内容。旧实现只认第一条文本，于是
     * **第一条不是位置码就整张贴作废，APP 还照样跳到货架页**，看起来就是
     * 「碰一下只切到货架页、没落到那一架」。这里改成：全记录里找得到位置码就用它。
     *
     * @return 第一个能解出位置码的记录原文；都没有则 null（调用方据此**明确报错**，不再空跳）。
     */
    fun pickTagPayload(candidates: List<String?>): String? {
        for (c in candidates) {
            val s = c?.trim() ?: continue
            if (s.isNotEmpty() && decodeTagPayload(s) != null) return s
        }
        return null
    }
}
