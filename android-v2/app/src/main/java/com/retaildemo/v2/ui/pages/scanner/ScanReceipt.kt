package com.retaildemo.v2.ui.pages.scanner

import androidx.compose.ui.graphics.Color
import com.retaildemo.v2.ui.components.ScanFeedback
import com.retaildemo.v2.ui.theme.NightBg
import com.retaildemo.v2.ui.theme.NightPrimaryAction
import com.retaildemo.v2.ui.theme.NightSuccess
import com.retaildemo.v2.ui.theme.NightSuccessLight
import com.retaildemo.v2.ui.theme.NightTextWeak
import com.retaildemo.v2.ui.theme.NightWarning
import com.retaildemo.v2.ui.theme.NightWarningLight

/**
 * 扫码交接台三态回执（M1/M2/M3）——**唯一真源**。
 *
 * 为什么要单独成文件（TASK-02 组件化解耦）：三态原先散在 `ScannerActivity` 里
 * ——`Receipt` sealed 类定义在 Activity 内、颜色与文案写在 `ReceiptCard` 的 when 分支、
 * 音效写在 `onCodeRead` / `onDuplicateScan` / `onConfirmPending`。三件事没有同一处定义，改一处必漏一处。
 * 这里把「状态 → 音效 / 主色 / 文案 / 计不计入」收成一个纯对象：
 *
 *  · **JVM 可单测**（[ScanReceiptStateTest] 锁文案与「M2 绝不加数量」）；
 *  · 回执卡 [ScanReceiptCard] 与流式交接清单 [HandoverLiveList] **共用同一份判定**，
 *    不会出现「卡上写未计入、清单里却 +1」两张皮。
 *
 * 三态口径（2026-09-20 老板令 + 画布 M1/M2/M3，文案以 `_reports/decisions/23_scan_handover_redesign.md` §5.5 为准）：
 *  · **M1 命中新件** —— 绿卡，计入件数与合计；
 *  · **M2 重复扫码** —— 暖黄卡 + 震动，**绝不自动加数量**（加量去「记一笔」的货筐改）；
 *  · **M3 库外条码** —— 弱灰卡「库里没有」，不计入、更不进单据的未识别行。
 */
enum class ReceiptM {
    /**
     * **M0 待确认**（2026-09-26 老板令：「每扫一件弹一次确认…我这边每单并不多」）。
     *
     * 扫到、库里有、但**还没裁决** —— 此时**不计件数、不进清单、不计金额**。
     * 卡上给两个钮：「计入」才算进这一单，或「不算」撤掉。
     *
     * 为什么要单独一个态、而不是拿 UI 假装：口径必须**数据上就分开**。
     * 只要它在别处被当成 M1（比如清单的 merge、件数的 sum），
     * 就会出现「卡上写待确认、清单里已经 +1」这种两张皮 —— 正是本文件开头要防的那件事。
     */
    M0_PENDING,

    M1_HIT,

    M2_DUP,

    M3_OUTSIDE,
}

/**
 * 一次扫码回执 = 交接清单里的一行（同一个模型，两处显示不会说两套话）。
 *
 * @param qty 本次会话内该码**被计入**的累计件数；M2 不增，M3 恒为 0。
 */
data class ScanReceipt(
    val code: String,
    /** M3 库里没有品名，回退成条码本身——卡片与清单共用这一个字段。 */
    val name: String,
    val spec: String,
    val price: Double,
    val qty: Int,
    val state: ReceiptM,
)

/**
 * M2 / M3 的定稿文案（§5.5 口径，测试逐字锁死，改文案必须先过 [ScanReceiptStateTest]）。
 *
 * ⚠️ M2 那句「要加数量 → 回「记一笔」的货筐里改」是 2026-09-20 的**文案修正**：
 * 画布 M2 原写「在清单里点那一行改」，但**扫码页不拥有数量**（数量归「记一笔」的货筐），
 * 指向必须落在真正能改的地方。旧口径「未额外计入，加量请在货筐修改」自本行起废弃。
 */
const val TEXT_M2_NOT_COUNTED: String = "本次未计入"
const val TEXT_M2_WAY_TO_FIX: String = "要加数量 → 回「记一笔」的货筐里改"
const val TEXT_M3_IGNORED: String = "不在商品库里 · 已忽略，未计入清单"

/**
 * 清单行**副行的短版**（画布「清单行 h=56」只有两行，放不下回执卡那句长文案）。
 * 同一个语义、更短的写法：长版见 [TEXT_M3_IGNORED]，只用在回执卡上。
 */
const val TEXT_M3_SHORT: String = "不在商品库里"

object ScanReceiptSpec {

    /**
     * 判定优先级：先问「是不是店里的」，再问「是不是刚扫过」。
     *
     * 库外码被重复扫时仍按 M3 出灰卡——它压根没进过单，讲「重复扫到」会让人以为已经算了。
     */
    fun stateOf(inStore: Boolean, duplicate: Boolean): ReceiptM = when {
        !inStore -> ReceiptM.M3_OUTSIDE
        duplicate -> ReceiptM.M2_DUP
        else -> ReceiptM.M1_HIT
    }

    /**
     * 语义音效（rule 14④：一律走 [ScanFeedback.Sfx] 语义事件表，不直接引 `R.raw`）。
     *
     *  · M1 = `SCAN_OK`——事件表里的「扫码成功」音（资产 `fx_success`，明亮「嘀」+40ms 震动）；
     *  · M2 = `DUPLICATE`——敲两下门板 +90ms 震动，任务书要求的「震动提醒」在事件表里成对定义；
     *  · M3 = `null`——读码那一帧还不知道库里有货没，M1 的「嘀」已经响了；
     *    再补一记负向音会把「一码一响」的节奏打乱，所以库外只出灰卡、不再出声。
     */
    fun toneOf(state: ReceiptM): ScanFeedback.Sfx? = when (state) {
        // M0 是「读到了，等你裁决」——响的是"嘀"（SCAN_OK）；真正的「落筐声」(ITEM_ADD)
        // 留到店员点「计入」那一刻（见 ScannerActivity.onConfirmPending）。
        // 一读一判两声，语义分明；也不算白响。
        ReceiptM.M0_PENDING -> ScanFeedback.Sfx.SCAN_OK
        ReceiptM.M1_HIT -> ScanFeedback.Sfx.SCAN_OK
        ReceiptM.M2_DUP -> ScanFeedback.Sfx.DUPLICATE
        ReceiptM.M3_OUTSIDE -> null
    }

    /**
     * 主色（胶囊底 / 清单状态点）。M3 用弱灰而非警告黄：它不是「待处理」，是「不算数」。
     *
     * ★ 一律取 **夜视档**（tokens v11 `color.dark`）：回执卡与清单只活在相机页，
     * 亮色档 #1B7E3B 压在 #080B10 上对比不足（TASK-01 报告 §四.1 同一判据），画布 38:768 亦用夜视档。
     *
     * M0 取**品牌橙的夜视档** `NightPrimaryAction`（#C75300，白字压它 4.501:1）——
     * 它是「需要你动手」的一态，和 M2 的琥珀（「已经算过，别再加」）语义不同，色也不同。
     */
    fun accent(state: ReceiptM): Color = when (state) {
        ReceiptM.M0_PENDING -> NightPrimaryAction
        ReceiptM.M1_HIT -> NightSuccess
        ReceiptM.M2_DUP -> NightWarning
        ReceiptM.M3_OUTSIDE -> NightTextWeak
    }

    /** 卡底 = 主色低透明度浮在预览画面上（沿用相机页既有的 0.20f 档）。 */
    fun cardTint(state: ReceiptM): Color = accent(state).copy(alpha = 0.20f)

    /**
     * 胶囊上的文字色 —— **不是白色**。
     *
     * 画布 `56:796` 实测三态胶囊文字色为 M1 `#06291F` / M2 `#2E2304` / M3 `#080B10`
     * （figma-local 任务 `937f6991`），即"亮底压墨色"。原实现一律 `Color.White`：
     * 白压 `#00E599` 只有约 1.9:1、白压 `#94A3B8` 约 2.5:1，都够不到 WCAG AA。
     * 三个值全部来自 tokens v11 `color.dark`（successLight / warningLight / bg），非新造色。
     */
    fun pillText(state: ReceiptM): Color = when (state) {
        // M0 的胶囊是品牌橙实底 → 白字（tokens 注明 #C75300 压白 4.501:1，刚好够 AA）
        ReceiptM.M0_PENDING -> Color.White
        ReceiptM.M1_HIT -> NightSuccessLight
        ReceiptM.M2_DUP -> NightWarningLight
        ReceiptM.M3_OUTSIDE -> NightBg
    }

    fun pill(state: ReceiptM): String = when (state) {
        ReceiptM.M0_PENDING -> "待确认"
        ReceiptM.M1_HIT -> "已计入 ✓"
        ReceiptM.M2_DUP -> "重复扫到"
        ReceiptM.M3_OUTSIDE -> "库里没有"
    }

    /** 副行：M1 给规格/件数/单价，M2、M3 给定稿文案。 */
    fun detail(receipt: ScanReceipt): String = when (receipt.state) {
        // M0 与 M1 同一套副行：店员正是**看着这行**决定要不要计入，规格与单价不能省。
        ReceiptM.M0_PENDING,
        ReceiptM.M1_HIT -> buildString {
            if (receipt.spec.isNotBlank()) append(receipt.spec).append(" · ")
            append("×").append(receipt.qty).append(" @¥").append(money(receipt.price))
        }
        ReceiptM.M2_DUP -> "已在本次扫过 ×${receipt.qty} · $TEXT_M2_NOT_COUNTED"
        ReceiptM.M3_OUTSIDE -> TEXT_M3_IGNORED
    }

    /**
     * 右上角状态徽标：**只有 M2 出「未计入」**。
     *
     * 依据是两处一致：§5.5 的 M2 行写「右侧『未计入』」，画布 `56:796` 实测该徽标在
     * y=2076 与「重复扫到」胶囊同一行右侧、色 `#FFB800`（figma-local 任务 `937f6991`）。
     * M1 右侧给的是金额、M3 由副行说明「已忽略」，都不该再挂徽标。
     */
    fun trailing(receipt: ScanReceipt): String? =
        if (receipt.state == ReceiptM.M2_DUP) "未计入" else null

    /**
     * 第三行（出路指引）：**只有 M2 有**——重复扫到的店员真正想知道的是「那我怎么加数量」，
     * 而扫码页改不了数量，所以这一行只负责把人指回「记一笔」的货筐（§5.5 文案修正）。
     * M1 已经计入、M3 压根不算数，都不该出现「去改」的入口。
     */
    fun hint(receipt: ScanReceipt): String? =
        if (receipt.state == ReceiptM.M2_DUP) TEXT_M2_WAY_TO_FIX else null

    /**
     * 计入件数：只有 M1 计数。
     *
     * M2 沿用会话内已累计的那一件（**不 +1**），M3 恒 0——「不进入单据未识别红行」在
     * 交接台这一侧的同一条口径。
     */
    fun piecesOf(state: ReceiptM, sessionCount: Int): Int = when (state) {
        // M0 显示的是**正在问你**的这一件（它还没进单，会话计数必为 0 → 取 1）。
        // ⚠️ 「计不计入」不归这里管 —— 归 [counts]。清单与合计只看 counts，
        //    所以 M0 即便显示 ×1，也**不会**被算进件数与金额。
        ReceiptM.M0_PENDING -> sessionCount.coerceAtLeast(1)
        ReceiptM.M1_HIT -> sessionCount.coerceAtLeast(1)
        ReceiptM.M2_DUP -> sessionCount.coerceAtLeast(1)
        ReceiptM.M3_OUTSIDE -> 0
    }

    /** 该行是否计入交接合计。 */
    fun counts(state: ReceiptM): Boolean = state == ReceiptM.M1_HIT

    /** 卡片右下角的大字金额；M2/M3 不该出现金额（算了钱就等于承认计入了）。 */
    fun amountOf(receipt: ScanReceipt): String? =
        if (counts(receipt.state)) money(receipt.price * receipt.qty) else null

    /** 金额格式（与回执卡、交接清单同一套，避免两处四舍五入口径不同）。 */
    fun money(value: Double): String = "%.2f".format(value)
}
