package com.retaildemo.v2.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retaildemo.v2.data.local.entity.BillEntity
import com.retaildemo.v2.data.local.entity.BillItem
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.repo.BillRepo
import com.retaildemo.v2.data.repo.NetworkMonitor
import com.retaildemo.v2.data.repo.BillTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BillUiModel(
    val id: String,
    val customerName: String,
    val totalAmount: Double,
    val paidAmount: Double?,
    val status: String,
    val items: List<BillItem>,
    val createdAt: String,
    val staff: String,
    val invoiceNeeded: Boolean = false,
    /** B6 方向：sale/return（列表"退"标）。 */
    val direction: String = "sale",
    val payMethod: String? = null,
    val note: String = "",
    val receiptPhoto: String? = null,
    /** B12 单据阶段：draft=挂账 / ready=待出库。 */
    val stage: String = BillEntity.STAGE_DRAFT,
    /** M3 归属人员工 id（挂账块按人过滤的一等判据）。 */
    val ownerId: String? = null,
    /** B13 单据编号（管家婆上云出库单号）。 */
    val billCode: String? = null,
    /** B13 订单号（管家婆订单/结算单雪花 id）。 */
    val orderNo: String? = null,
)

data class TodayStatsUi(
    val totalAmount: Double = 0.0,
    val count: Int = 0,
    // 01 稿概览卡「挂账 x」：今日挂账单数（金额不计入 totalAmount，B6 口径不变）
    val todayPendingCount: Int = 0,
)

/**
 * B12 挂账块按人过滤判据（纯函数，便于单测）——系统里第一条按人过滤的规则。
 * 有名册 id 的单按 owner_id 精确匹配；老单（owner_id 空）按记账人名兜底，
 * 不让存量挂账单在切换后凭空消失（容错不篡改）。
 */
fun isOwnedBy(bill: BillUiModel, myStaffId: String, myStaffName: String): Boolean =
    if (myStaffId.isNotBlank() && !bill.ownerId.isNullOrBlank()) {
        bill.ownerId == myStaffId
    } else {
        bill.staff == myStaffName
    }

@HiltViewModel
class BillsViewModel @Inject constructor(
    private val repo: BillRepo,
    private val config: AppConfig,
    networkMonitor: NetworkMonitor,
) : ViewModel() {

    /** 36 三态合辑 · 离线条：断网时就地显示「照常记账，联网自动补传」，不封禁。 */
    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

    val bills: StateFlow<List<BillUiModel>?> = repo.allBills
        .map { list -> list.map { toUi(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), null)

    /** 账单日期筛选：null=全部，否则 "yyyy-MM-dd" 看当天。 */
    private val _dateFilter = MutableStateFlow<String?>(null)
    val dateFilter: StateFlow<String?> = _dateFilter.asStateFlow()

    /** 归属筛选：false=全店，true=只看我的（按 staff = 当前店员名）。 */
    private val _mineOnly = MutableStateFlow(false)
    val mineOnly: StateFlow<Boolean> = _mineOnly.asStateFlow()

    val filteredBills: StateFlow<List<BillUiModel>?> =
        combine(repo.allBills, _dateFilter, _mineOnly) { list, day, mine ->
            var shaped = list.map { toUi(it) }
            if (mine) shaped = shaped.filter { it.staff == config.staffName }
            if (day != null) shaped = shaped.filter { BillTime.sameDay(it.createdAt, day) }
            shaped
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), null)

    fun setDateFilter(day: String?) {
        _dateFilter.value = day
    }

    fun setMineOnly(mine: Boolean) {
        _mineOnly.value = mine
    }

    // ---------------- M2 空库就地恢复（重装/换机后账单页找回入口） ----------------

    private val _restoring = MutableStateFlow(false)
    val restoring: StateFlow<Boolean> = _restoring.asStateFlow()

    private val _restoreMsg = MutableStateFlow<String?>(null)
    val restoreMsg: StateFlow<String?> = _restoreMsg.asStateFlow()

    /** 本地全空时的就地找回：成败与条数就地回显；取不到时报错，绝不装作「本来就没有」。 */
    fun restoreBills() {
        if (_restoring.value) return
        _restoring.value = true
        viewModelScope.launch {
            val r = repo.restoreFromServer()
            _restoring.value = false
            _restoreMsg.value = when {
                !r.ok -> "恢复失败：${r.error ?: "未知原因"}"
                r.restored == 0 -> "连接正常，服务器上没有账单"
                else -> "已从服务器恢复 ${r.restored} 条 ✓"
            }
        }
    }

    private val _todayStats = MutableStateFlow(TodayStatsUi())
    val todayStats: StateFlow<TodayStatsUi> = _todayStats.asStateFlow()

    init {
        // B7 打烊闭环：跨天挂单自动进回收站（启动即查一次，日期切换由下次进页兜底）
        viewModelScope.launch { runCatching { repo.markCrossDayRecycled() } }
        // B13 结算回执：进入页拉取收银端已结算回执
        viewModelScope.launch { runCatching { repo.syncReceipts() } }
        viewModelScope.launch {
            repo.allBills.collect { list ->
                val today = com.retaildemo.v2.data.repo.BillTime.today()
                // B6 口径对齐报表：只计已出库(paid)，退货单(direction=return)冲减；挂单不计入（钱的真相在管家婆）
                // B7：回收站单(recycled)同样不计入
                val todaysPaid = list.filter {
                    it.createdAt.startsWith(today) && it.status == BillEntity.STATUS_PAID && !it.recycled
                }
                _todayStats.value = TodayStatsUi(
                    totalAmount = todaysPaid.sumOf {
                        val amt = it.paidAmount ?: it.totalAmount
                        // 退货单金额本身为负，防御性取负绝对值（与报表 netOf 同语义，避免双重取负）
                        if (it.direction == "return") -kotlin.math.abs(amt) else amt
                    },
                    count = todaysPaid.size,
                    // 01 稿概览卡细分「挂账 x」；sameDay 为 M5 排雷后的安全日期比较
                    todayPendingCount = list.filter {
                        BillTime.sameDay(it.createdAt, today) && it.status == BillEntity.STATUS_PENDING && !it.recycled
                    }.size,
                )
            }
        }
    }

    fun toUi(bill: BillEntity) = BillUiModel(
        id = bill.id,
        customerName = bill.customerName,
        totalAmount = bill.totalAmount,
        paidAmount = bill.paidAmount,
        status = bill.status,
        items = repo.itemsOf(bill),
        createdAt = bill.createdAt,
        staff = bill.staff,
        invoiceNeeded = bill.invoiceNeeded,
        direction = bill.direction,
        payMethod = bill.payMethod,
        note = bill.note,
        receiptPhoto = bill.receiptPhoto,
        stage = BillEntity.normalizeStage(bill.stage),
        ownerId = bill.ownerId,
        billCode = bill.billCode,
        orderNo = bill.orderNo,
    )

    /** B12 挂账块可见性：本单是否归属当前店员（有名册 id 用 id，老单按名兜底）。 */
    fun isMine(bill: BillUiModel): Boolean =
        isOwnedBy(bill, config.staffId, config.staffName)

    /** B12「标待出库」：三件硬闸在 repo，成功后立即推送（网关插件端点按 stage=ready 领单）。 */
    fun markReady(billId: String, onDone: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val r = repo.markReady(billId)
            if (r.ok) enqueueSync()
            onDone(r.ok, r.missing)
        }
    }

    /** 挂账 ↔ 已出库 切换后立即触发推送。 */
    fun toggleStatus(billId: String) {
        viewModelScope.launch {
            repo.toggleStatus(billId)
            enqueueSync()
        }
    }

    /** 出库前核销校验（无缺失编号 + 已选收款方式 + 若为POS必有小票照片） */
    fun canMarkPaid(b: BillUiModel): Boolean =
        repo.canMarkPaid(b.items, b.payMethod, b.receiptPhoto)

    fun deleteBill(billId: String) {
        viewModelScope.launch {
            repo.deleteBill(billId)
            enqueueSync()
        }
    }

    /** 编辑账单内商品（改数量/改价），保存后仍可继续修改。 */
    fun updateItem(billId: String, index: Int, newQty: Int, newPrice: Double) {
        viewModelScope.launch {
            repo.updateItem(billId, index, newQty, newPrice)
            enqueueSync()
        }
    }

    /** 删除账单内某行商品（商品条左滑删除）。 */
    fun removeItem(billId: String, index: Int) {
        viewModelScope.launch {
            repo.removeItem(billId, index)
            enqueueSync()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            repo.syncReceipts()
            repo.syncBills()
        }
    }

    private suspend fun enqueueSync() {
        repo.syncBills()
    }

    suspend fun billExists(id: String): Boolean =
        repo.allBills.first().any { it.id == id }

    /** 补全商品编号并完成出库 */
    fun fillSkusAndMarkPaid(billId: String, skuMap: Map<Int, String>, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = repo.fillSkusAndMarkPaid(billId, skuMap)
            if (ok) enqueueSync()
            onDone(ok)
        }
    }

    /** 补全核销信息（编号/收款方式/备注/小票）并标记出库（原子更新） */
    fun fulfillAndMarkPaid(
        billId: String,
        skuMap: Map<Int, String> = emptyMap(),
        payMethod: String? = null,
        note: String? = null,
        receiptPhoto: String? = null,
        onDone: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            val ok = repo.fulfillAndMarkPaid(billId, skuMap, payMethod, note, receiptPhoto)
            if (ok) enqueueSync()
            onDone(ok)
        }
    }
}
