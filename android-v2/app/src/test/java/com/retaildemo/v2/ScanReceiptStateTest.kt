package com.retaildemo.v2

import com.retaildemo.v2.ui.components.ScanFeedback
import com.retaildemo.v2.ui.pages.scanner.HandoverLiveListModel
import com.retaildemo.v2.ui.pages.scanner.ReceiptM
import com.retaildemo.v2.ui.pages.scanner.ScanReceipt
import com.retaildemo.v2.ui.pages.scanner.ScanReceiptSpec
import com.retaildemo.v2.ui.pages.scanner.TEXT_M2_NOT_COUNTED
import com.retaildemo.v2.ui.pages.scanner.TEXT_M2_WAY_TO_FIX
import com.retaildemo.v2.ui.pages.scanner.TEXT_M3_IGNORED
import com.retaildemo.v2.ui.theme.NightBg
import com.retaildemo.v2.ui.theme.NightSuccess
import com.retaildemo.v2.ui.theme.NightSuccessLight
import com.retaildemo.v2.ui.theme.NightTextWeak
import com.retaildemo.v2.ui.theme.NightWarning
import com.retaildemo.v2.ui.theme.NightWarningLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫码交接台三态回执（M1/M2/M3）契约（TASK-02，2026-09-22）。
 *
 * 这里锁的是**回执与交接清单共用的那一份真源**（`ScanReceiptSpec` /
 * `HandoverLiveListModel`），三条都是老板 2026-09-20 实报后定的口径：
 *   · M1 命中新件 = 成功音 + 绿卡 + 计入；
 *   · M2 重复扫码 = 震动音 + 暖黄卡，**绝不自动加数量**（最容易被"顺手优化"改坏的一条）；
 *   · M3 库外条码 = 弱灰卡「库里没有 · 不在商品库里 · 已忽略，未计入清单」，不计入、也不进单据的未识别行。
 *
 * 取景框几何截断（"框外的码不算"）另有 `ViewfinderHitTest` 逐值锁死，本测不重复。
 */
class ScanReceiptStateTest {

    private fun hit(code: String, name: String = "防火胶布", price: Double = 12.5, qty: Int = 1) =
        ScanReceipt(code, name, "10m", price, qty, ReceiptM.M1_HIT)

    private fun dup(code: String, qty: Int = 1) =
        ScanReceipt(code, "防火胶布", "10m", 12.5, qty, ReceiptM.M2_DUP)

    private fun out(code: String) =
        ScanReceipt(code, code, "", 0.0, 0, ReceiptM.M3_OUTSIDE)

    // ---------------- 状态判定 ----------------

    @Test
    fun `三态判定 库外优先于重复`() {
        assertEquals(ReceiptM.M1_HIT, ScanReceiptSpec.stateOf(inStore = true, duplicate = false))
        assertEquals(ReceiptM.M2_DUP, ScanReceiptSpec.stateOf(inStore = true, duplicate = true))
        assertEquals(ReceiptM.M3_OUTSIDE, ScanReceiptSpec.stateOf(inStore = false, duplicate = false))
        // 库外码被回扫仍按 M3：它压根没进过单，说「重复扫到」会误导
        assertEquals(ReceiptM.M3_OUTSIDE, ScanReceiptSpec.stateOf(inStore = false, duplicate = true))
    }

    // ---------------- 语义音效（rule 14④ 语义事件表）----------------

    @Test
    fun `M1播成功音 M2播重复音带震动 M3不出声`() {
        assertEquals("M1 须播扫码成功语义音", ScanFeedback.Sfx.SCAN_OK, ScanReceiptSpec.toneOf(ReceiptM.M1_HIT))
        assertEquals("M2 须播重复扫码专属音", ScanFeedback.Sfx.DUPLICATE, ScanReceiptSpec.toneOf(ReceiptM.M2_DUP))
        // 「震动提醒」成对定义在事件表里：DUPLICATE 的触觉 90ms 必须保留
        assertTrue(
            "M2 必须带震动（任务书「震动提醒」）",
            ScanFeedback.Sfx.DUPLICATE.vibrateMs > ScanFeedback.Sfx.SCAN_OK.vibrateMs,
        )
        assertNull("M3 静默：读码那帧还不知道库里有没，负向音会打乱一码一响", ScanReceiptSpec.toneOf(ReceiptM.M3_OUTSIDE))
    }

    // ---------------- 颜色与文案 ----------------

    @Test
    fun `三态主色取夜视档 绿_暖黄_弱灰`() {
        assertEquals(NightSuccess, ScanReceiptSpec.accent(ReceiptM.M1_HIT))
        assertEquals(NightWarning, ScanReceiptSpec.accent(ReceiptM.M2_DUP))
        assertEquals("M3 必须是弱灰而非警告黄（不算数≠待处理）", NightTextWeak, ScanReceiptSpec.accent(ReceiptM.M3_OUTSIDE))
        // 胶囊文字色按画布 56:796 实测：亮底压墨色，一律不许用白（白压 #00E599 仅约 1.9:1）
        assertEquals(NightSuccessLight, ScanReceiptSpec.pillText(ReceiptM.M1_HIT))
        assertEquals(NightWarningLight, ScanReceiptSpec.pillText(ReceiptM.M2_DUP))
        assertEquals(NightBg, ScanReceiptSpec.pillText(ReceiptM.M3_OUTSIDE))
    }

    @Test
    fun `M2 M3 文案逐字锁死 §5_5 定稿`() {
        assertEquals("本次未计入", TEXT_M2_NOT_COUNTED)
        assertEquals("要加数量 → 回「记一笔」的货筐里改", TEXT_M2_WAY_TO_FIX)
        assertEquals("不在商品库里 · 已忽略，未计入清单", TEXT_M3_IGNORED)
        // 副行按 §5.5 组装：件数 + 「本次未计入」；旧任务书口径「未额外计入，加量请在货筐修改」已废弃
        assertEquals("已在本次扫过 ×2 · 本次未计入", ScanReceiptSpec.detail(dup("6901", qty = 2)))
        assertEquals("不在商品库里 · 已忽略，未计入清单", ScanReceiptSpec.detail(out("6901999")))
        assertEquals("库里没有", ScanReceiptSpec.pill(ReceiptM.M3_OUTSIDE))
    }

    @Test
    fun `只有 M2 给加数量的出路 另两态不得出现入口`() {
        assertEquals(TEXT_M2_WAY_TO_FIX, ScanReceiptSpec.hint(dup("6901")))
        assertNull("M1 已计入，不该看到「去改」指引", ScanReceiptSpec.hint(hit("6901")))
        assertNull("M3 压根不算数，更没有加量入口（§5.4：忽略就是忽略）", ScanReceiptSpec.hint(out("6901")))
    }

    @Test
    fun `只有 M2 挂右侧未计入徽标 画布 56_796 实测同位`() {
        assertEquals("未计入", ScanReceiptSpec.trailing(dup("6901")))
        assertNull("M1 右侧给金额，不挂徽标", ScanReceiptSpec.trailing(hit("6901")))
        assertNull("M3 由副行说明已忽略，不再叠徽标", ScanReceiptSpec.trailing(out("6901")))
    }

    // ---------------- 计数闭环（最容易改坏的一条）----------------

    @Test
    fun `重复扫码绝不自动加数量`() {
        // 会话里这件已计入 1 件，回扫判 M2 —— 件数必须还是 1，不是 2
        assertEquals(1, ScanReceiptSpec.piecesOf(ReceiptM.M2_DUP, sessionCount = 1))
        assertEquals(3, ScanReceiptSpec.piecesOf(ReceiptM.M2_DUP, sessionCount = 3))
        assertEquals(2, ScanReceiptSpec.piecesOf(ReceiptM.M1_HIT, sessionCount = 2))
        assertEquals("库外码不计入", 0, ScanReceiptSpec.piecesOf(ReceiptM.M3_OUTSIDE, sessionCount = 1))
    }

    @Test
    fun `只有命中件有金额 重复与库外不给金额`() {
        assertEquals("12.50", ScanReceiptSpec.amountOf(hit("6901", price = 12.5, qty = 1)))
        assertEquals("25.00", ScanReceiptSpec.amountOf(hit("6901", price = 12.5, qty = 2)))
        assertNull(ScanReceiptSpec.amountOf(dup("6901")))
        assertNull(ScanReceiptSpec.amountOf(out("6901")))
        assertTrue(ScanReceiptSpec.counts(ReceiptM.M1_HIT))
        assertFalse(ScanReceiptSpec.counts(ReceiptM.M2_DUP))
        assertFalse(ScanReceiptSpec.counts(ReceiptM.M3_OUTSIDE))
    }

    // ---------------- 流式交接清单 ----------------

    @Test
    fun `清单一个码一行 最新在最前`() {
        val after1 = HandoverLiveListModel.mergeHandover(emptyList(), hit("A", "胶布"))
        val after2 = HandoverLiveListModel.mergeHandover(after1, hit("B", "钻头"))
        assertEquals(listOf("B", "A"), after2.map { it.code })
        // 同码再扫（M2）不新增行，只把状态换掉并置顶
        val afterDup = HandoverLiveListModel.mergeHandover(after2, dup("A"))
        assertEquals("同码恒占一行", 2, afterDup.size)
        assertEquals("A", afterDup.first().code)
        assertEquals(ReceiptM.M2_DUP, afterDup.first().state)
    }

    @Test
    fun `重复扫码只换状态 件数绝不变`() {
        val rows = HandoverLiveListModel.mergeHandover(
            listOf(hit("A", price = 10.0, qty = 1)),
            dup("A", qty = 1),
        )
        assertEquals(1, rows.single().qty)
    }

    @Test
    fun `汇总只算计入的行`() {
        val rows = listOf(
            hit("A", price = 10.0, qty = 2),
            dup("B", qty = 1),
            out("C"),
            hit("D", price = 5.0, qty = 1),
        ).let { list ->
            list.fold(emptyList<ScanReceipt>()) { acc, r ->
                HandoverLiveListModel.mergeHandover(acc, r)
            }
        }
        val s = HandoverLiveListModel.summarizeHandover(rows)
        assertEquals("计入 2 种", 2, s.kinds)
        assertEquals("计入 3 件", 3, s.pieces)
        assertEquals(25.0, s.amount, 0.001)
        assertEquals("未计入的（重复 1 + 库外 1）单列", 2, s.ignored)
    }

    // ---------------- 清单行第二行（画布「清单行 h=56 两行」）----------------

    /**
     * ★ 2026-09-26：画布 M 帧的「清单行」是 **56dp 两行**（上行 品名+×件数，下行 副行+金额），
     * 而代码此前只有**单行** —— 属「还原度对账三层」的**第③层（行结构）**缺口：
     * 尺寸、圆角都对得上，卡片里却少了一行，**只对几何矩阵看不出来**。
     *
     * 这里锁住第二行（左）的三态口径：M1 给规格、M2 说明「本次未计入」、M3 说明不在库里。
     */
    @Test
    fun `清单行第二行 三态各有其词`() {
        assertEquals("M1 给规格", "10m", HandoverLiveListModel.subLineOf(hit("A")))
        assertEquals(
            "M2 必须说明这一件没算数",
            TEXT_M2_NOT_COUNTED, HandoverLiveListModel.subLineOf(dup("A")),
        )
        assertEquals(
            "M3 必须说明不在商品库里",
            "不在商品库里", HandoverLiveListModel.subLineOf(out("C")),
        )
    }

    /** 规格为空时不留空白行（行高固定 56dp，空白会让第二行看起来像渲染失败）。 */
    @Test
    fun `M1 规格为空时第二行给占位符`() {
        val noSpec = ScanReceipt("A", "防火胶布", "", 12.5, 1, ReceiptM.M1_HIT)
        assertEquals("—", HandoverLiveListModel.subLineOf(noSpec))
    }

    // ---------------- M0 待确认（2026-09-26 老板令「每扫一件弹一次确认」）----------------

    /**
     * ★ 本需求的全部要害：**待确认 ≠ 已计入**。
     *
     * 没裁决就不算数 —— 件数不进合计、金额不给。谁要是把 M0 混进 [ScanReceiptSpec.counts]
     * （比如清单汇总手滑写成 `state != M3_OUTSIDE`），这条立刻红。
     */
    @Test
    fun `M0待确认 不计件数 不给金额`() {
        assertFalse("待确认绝不能等于已计入", ScanReceiptSpec.counts(ReceiptM.M0_PENDING))
        val r = ScanReceipt("A", "螺丝", "10m", 12.5, 1, ReceiptM.M0_PENDING)
        assertNull("没裁决就不该出金额 —— 出金额等于承认算过了", ScanReceiptSpec.amountOf(r))
        assertEquals(
            "卡上要显示正在问你的是哪一件",
            1, ScanReceiptSpec.piecesOf(ReceiptM.M0_PENDING, sessionCount = 0),
        )
    }

    /** 待确认的胶囊、音效、徽标：读到那一声是「嘀」，但**还没有**右侧「未计入」徽标。 */
    @Test
    fun `M0的胶囊音效与徽标`() {
        assertEquals("待确认", ScanReceiptSpec.pill(ReceiptM.M0_PENDING))
        assertEquals(
            "读到那一声是嘀（SCAN_OK）；落筐声留到点计入那一刻",
            ScanFeedback.Sfx.SCAN_OK, ScanReceiptSpec.toneOf(ReceiptM.M0_PENDING),
        )
        val r = ScanReceipt("A", "螺丝", "10m", 12.5, 1, ReceiptM.M0_PENDING)
        assertNull("待确认不带右侧徽标", ScanReceiptSpec.trailing(r))
    }

    /** 待确认的副行与 M1 同一套：店员正是**看着规格与单价**决定要不要计入，不能省。 */
    @Test
    fun `M0副行与M1同套 报规格与单价`() {
        val m0 = ScanReceipt("A", "螺丝", "10m", 12.5, 1, ReceiptM.M0_PENDING)
        assertEquals(
            ScanReceiptSpec.detail(m0.copy(state = ReceiptM.M1_HIT)),
            ScanReceiptSpec.detail(m0),
        )
    }

    /** 待确认的件**不该出现在清单里**；万一进来了，文案要能当场看出来。 */
    @Test
    fun `M0混进清单时文案显眼`() {
        val r = ScanReceipt("A", "螺丝", "10m", 12.5, 1, ReceiptM.M0_PENDING)
        assertTrue(
            "文案须自曝异常，便于当场发现",
            HandoverLiveListModel.subLineOf(r).contains("不该"),
        )
    }
}
