package com.retaildemo.v2.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retaildemo.v2.data.local.entity.PrintJobEntity
import com.retaildemo.v2.data.repo.PrintRepo
import com.retaildemo.v2.data.repo.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 打印任务队列 ViewModel（S5 打印场景矩阵 + 打印队列）。
 * 管理本地 Room 持久化待出签队列，支持批量出签、单项重试、状态过滤与完成清空。
 */
@HiltViewModel
class PrintQueueViewModel @Inject constructor(
    private val printRepo: PrintRepo,
    networkMonitor: NetworkMonitor,
) : ViewModel() {

    /** 36 三态合辑 · 离线条：网络标签机在断网时暂不可达，任务保留在本地队列。 */
    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

    private val _filter = MutableStateFlow("all") // all / pending / done / failed
    val filter: StateFlow<String> = _filter.asStateFlow()

    private val _isPrinting = MutableStateFlow(false)
    val isPrinting: StateFlow<Boolean> = _isPrinting.asStateFlow()

    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()

    val rawJobs: StateFlow<List<PrintJobEntity>> = printRepo.observeQueue()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredJobs: StateFlow<List<PrintJobEntity>> = combine(rawJobs, _filter) { jobs, f ->
        when (f) {
            "pending" -> jobs.filter { it.status == PrintJobEntity.STATUS_PENDING }
            "done" -> jobs.filter { it.status == PrintJobEntity.STATUS_DONE }
            "failed" -> jobs.filter { it.status == PrintJobEntity.STATUS_FAILED }
            else -> jobs
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingCount: StateFlow<Int> = printRepo.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val pendingJobs: StateFlow<List<PrintJobEntity>> = printRepo.observePendingJobs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setFilter(f: String) {
        _filter.value = f
    }

    fun printAllPending() {
        if (_isPrinting.value) return
        viewModelScope.launch {
            _isPrinting.value = true
            try {
                val outcome = printRepo.printAllPending()
                _feedback.value = when {
                    outcome.failCount == 0 && outcome.successCount > 0 ->
                        "全部打印完成！共成功出签 ${outcome.successCount} 张"
                    outcome.failCount > 0 ->
                        "打印完成：成功 ${outcome.successCount} 张，失败 ${outcome.failCount} 张" +
                            (outcome.firstError?.let { "——失败原因：$it" } ?: "")
                    else -> "当前没有待打印任务"
                }
            } catch (e: Exception) {
                _feedback.value = "打印异常: ${e.message}"
            } finally {
                _isPrinting.value = false
            }
        }
    }

    fun retryJob(id: Long) {
        viewModelScope.launch {
            printRepo.retryJob(id)
            _feedback.value = "已重新放入待打印队列"
        }
    }

    fun deleteJob(id: Long) {
        viewModelScope.launch {
            printRepo.deleteJob(id)
        }
    }

    /** 数量随时可改：直接更新 Room 任务数量（最低 1 份）。 */
    fun updateQty(id: Long, newQty: Int) {
        viewModelScope.launch {
            printRepo.updateJobQty(id, newQty)
        }
    }

    /** 打印内容随时可改：品名、规格、价格、条码、单位、数量。 */
    fun updateContent(
        id: Long,
        name: String,
        spec: String?,
        sellPrice: Double,
        barcode: String?,
        unit: String?,
        qty: Int,
    ) {
        viewModelScope.launch {
            printRepo.updateJobContent(id, name, spec, sellPrice, barcode, qty, unit)
            _feedback.value = "打印内容已更新 ✓"
        }
    }

    /** 随时打单条任务（打本条）。 */
    fun printSingle(job: PrintJobEntity) {
        if (_isPrinting.value) return
        viewModelScope.launch {
            _isPrinting.value = true
            try {
                val outcome = printRepo.printSingleJob(job)
                _feedback.value = if (outcome.success) {
                    "已出签：${job.name} (${job.qty}张) ✓"
                } else {
                    // 2026-09-11 排障：失败必须带原因（蓝牙连不上/没设默认打印机/打印服务不通），不再只报「打印失败」
                    "打印失败：${outcome.error ?: "未知原因"}"
                }
            } catch (e: Exception) {
                _feedback.value = "打印异常: ${e.message}"
            } finally {
                _isPrinting.value = false
            }
        }
    }

    fun clearDone() {
        viewModelScope.launch {
            printRepo.clearDone()
            _feedback.value = "已清空已打印记录"
        }
    }

    fun clearFeedback() {
        _feedback.value = null
    }
}
