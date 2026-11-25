package com.retaildemo.v2.ui.pages.scanner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retaildemo.v2.ui.theme.NightSurfaceSubtle

/**
 * 流式交接清单（取景框下方那一栏）：扫一件进一行，边扫边核对。
 *
 * 对标快递交接的「纸质交接单」心智——回执卡只回答「刚那下算没算数」，
 * 清单回答「这一整趟交接一共几件、多少钱、哪些被忽略了」。二者共用 [ScanReceipt]
 * 这一个模型与 [ScanReceiptSpec] 这一份判定，所以不会出现两套口径。
 *
 * 为什么归并逻辑是**纯函数**（[mergeHandover] / [summarizeHandover]）：
 * 「重复扫码绝不自动加数量」是老板令，也是这条链路上最容易在重构中被改坏的不变量。
 * 收在纯函数里才能被 [ScanReceiptStateTest] 直接锁住（不依赖相机、不依赖 Robolectric）。
 */
object HandoverLiveListModel {

    /**
     * 把一条回执并进清单，返回新清单（**最新在最前**：店员低头就能看见刚扫的那件，
     * 不必在长清单里找最后一行）。
     *
     * 一个码在清单里恒占一行（查库结果会话内被 `lookupCache` 钉死，同码不会又在库里又不在），
     * 再次扫到只是把那一行的**状态**换掉：
     *  · M1 → 件数取回执件数（会话累计），置顶；
     *  · M2 → 件数一律不变（老板令：绝不自动加数量），只置顶并标成 M2；
     *  · M3 → 该行件数归零、转灰档。
     */
    fun mergeHandover(rows: List<ScanReceipt>, receipt: ScanReceipt): List<ScanReceipt> =
        listOf(receipt) + rows.filterNot { it.code == receipt.code }

    /** 清单汇总：件数/金额只算计入的行，忽略数单独报（灰档不算钱，但要点个数）。 */
    fun summarizeHandover(rows: List<ScanReceipt>): HandoverSummary {
        val counted = rows.filter { ScanReceiptSpec.counts(it.state) }
        return HandoverSummary(
            kinds = counted.size,
            pieces = counted.sumOf { it.qty },
            amount = counted.sumOf { it.price * it.qty },
            ignored = rows.count { !ScanReceiptSpec.counts(it.state) },
        )
    }

    /**
     * 清单行的**第二行（左）** —— 画布 M 帧「清单行」是 **56dp 两行结构**
     * （上行：品名 + ×件数；下行：副行 + 金额），规格实测于
     * `_reports/redraw/spec/scan_handover.json` 的 `清单1-副`。
     *
     * ⚠️ 原实现是**单行**（只有品名 / ×N / 金额），比画布少一行 ——
     * 属「还原度对账三层」里的**第③层（行结构）**缺口：尺寸圆角都对得上，
     * 但卡片里少了一行，只对几何矩阵是看不出来的。
     */
    fun subLineOf(row: ScanReceipt): String = when (row.state) {
        // M0 理论上**进不来清单**（待确认的件还没裁决，不该在清单里占位）——
        // 真进来了说明有代码绕过裁决把它塞进了 mergeHandover，给个显眼文案便于当场发现。
        ReceiptM.M0_PENDING -> "待确认（不该出现在清单里）"
        ReceiptM.M1_HIT -> row.spec.ifBlank { "—" }
        ReceiptM.M2_DUP -> TEXT_M2_NOT_COUNTED
        ReceiptM.M3_OUTSIDE -> TEXT_M3_SHORT
    }
}

/** 交接清单汇总（纯数据，便于单测断言）。 */
data class HandoverSummary(
    val kinds: Int,
    val pieces: Int,
    val amount: Double,
    val ignored: Int,
)

/**
 * 取景框下方的流式清单。
 *
 * 零嵌套滚动（rule 14②）：整块只有这一个 LazyColumn；高度由调用方给出**上限**
 * （[maxHeight]，按当前屏高算出的剩余空间），不与相机页的点击对焦层抢位置，
 * 也不与底部栏叠罗汉。
 *
 * 为什么上限由外面给而不是写死：画布 M 帧是 390×914，而实机是 393×851 ——
 * 写死一个 dp 上限就会在矮屏上顶到底栏。外层已经量到了真实剩余空间，交给它最准。
 */
@Composable
fun HandoverLiveList(
    rows: List<ScanReceipt>,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) return
    val summary = HandoverLiveListModel.summarizeHandover(rows)
    Column(
        modifier =
            modifier
                .width(358.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(NightSurfaceSubtle.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 画布 M 帧「清单头」是**段落标签**（"已扫清单"）。
            // ⚠️ 这里**不再重复报件数** —— 件数与合计已经在顶部单据条上、
            //    逐件明细就在下面，同一组数字在一屏里出现三遍是这页最容易犯的错。
            Text(
                text = "已扫清单",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 13.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "¥" + ScanReceiptSpec.money(summary.amount),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (summary.ignored > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "· 忽略 ${summary.ignored}",
                    color = ScanReceiptSpec.accent(ReceiptM.M3_OUTSIDE).copy(alpha = 0.9f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.heightIn(max = maxHeight).fillMaxWidth(),
        ) {
            items(rows, key = { it.code }) { row -> HandoverRowLine(row) }
        }
    }
}

/**
 * 清单行的行高（dp）—— 画布 M 帧「清单行 h=56」实测值。
 *
 * 行是**两行结构**（上行品名/件数，下行副行/金额），所以不能用单行文本的自然高度；
 * 钉死绝对高度才能让「行结构」这一层对得上稿，也避免历史上那种
 * 「卡片被行高撑高」的事故（`docs/UI-REDESIGN-HANDOFF.md` §一）。
 */
private val HandoverRowHeight = 56.dp

/**
 * 一行 = 一件，**两行结构**（对齐画布 M 帧的 `清单行`）：
 * 上行 = 状态点 + 品名 + ×件数；下行 = 副行（规格 / 未计入原因） + 金额（或状态尾注）。
 */
@Composable
private fun HandoverRowLine(row: ScanReceipt) {
    val accent = ScanReceiptSpec.accent(row.state)
    val dim = row.state != ReceiptM.M1_HIT
    val amount = ScanReceiptSpec.amountOf(row)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(HandoverRowHeight).fillMaxWidth(),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                color = Color.White.copy(alpha = if (dim) 0.55f else 0.95f),
                fontSize = 13.sp,
                lineHeight = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = HandoverLiveListModel.subLineOf(row),
                color = if (dim) accent.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (row.qty > 0) "×${row.qty}" else "—",
                color = if (dim) accent else Color.White.copy(alpha = 0.8f),
                fontSize = 13.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            // 状态尾注：M1 给金额；M2 写「刚才重复」（它的 ×N 是**之前**计入的件数，
            // 若这里也写「未计入」会被读成「这一件没算数」）；M3 写「已忽略」。
            Text(
                text = when (row.state) {
                    // M0 不该出现在清单里（没裁决就不该占位）；真出现说明有代码绕过了裁决，
                    // 给个显眼尾注便于当场发现。
                    ReceiptM.M0_PENDING -> "未裁决"
                    ReceiptM.M1_HIT -> "¥${amount ?: ScanReceiptSpec.money(0.0)}"
                    ReceiptM.M2_DUP -> "刚才重复"
                    ReceiptM.M3_OUTSIDE -> "已忽略"
                },
                color = if (amount != null) Color.White.copy(alpha = 0.85f) else accent.copy(alpha = 0.9f),
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
    }
}
