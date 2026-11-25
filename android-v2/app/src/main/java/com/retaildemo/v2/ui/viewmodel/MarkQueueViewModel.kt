package com.retaildemo.v2.ui.viewmodel

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retaildemo.v2.data.local.entity.DataMarkEntity
import com.retaildemo.v2.data.repo.DataMarkRepo
import com.retaildemo.v2.data.repo.ExportUtils
import com.retaildemo.v2.data.repo.ProductSupplementRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * I4 标记队列：open/exported/resolved 三组 + 本地补录清单。
 * 导出走系统分享（微信/文件），专员照单去管家婆调整；闭环确认只能人工点，不自动关。
 */
@HiltViewModel
class MarkQueueViewModel @Inject constructor(
    private val markRepo: DataMarkRepo,
    private val supplementRepo: ProductSupplementRepo,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    val openMarks = markRepo.observeByStatus(DataMarkEntity.STATUS_OPEN)
    val exportedMarks = markRepo.observeByStatus(DataMarkEntity.STATUS_EXPORTED)
    val resolvedMarks = markRepo.observeByStatus(DataMarkEntity.STATUS_RESOLVED)
    val supplements = supplementRepo.observeAll()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() { _message.value = null }

    /** 导出待处理标记 → 分享 → 批量置 exported（不置位会重复导出，卡明规）。 */
    fun exportOpen(context: Context, rows: List<DataMarkEntity>) {
        if (rows.isEmpty()) {
            _message.value = "没有待导出的标记"
            return
        }
        viewModelScope.launch {
            val csv = markRepo.marksToCsv(rows)
            val f = ExportUtils.writeCsvCache(appContext, "data_marks_${stamp()}.csv", csv)
            markRepo.markExported(rows.map { it.id })
            _message.value = "已导出 ${rows.size} 条，状态转已导出 ✓"
            shareCsv(context, f)
        }
    }

    /** 导出本地补录清单（品牌/规格/待绑条码，给管家婆建档参考；补录是持久行，不改变状态）。 */
    fun exportSupplements(context: Context) {
        viewModelScope.launch {
            val rows = supplementRepo.listAll()
            if (rows.isEmpty()) {
                _message.value = "还没有本地补录"
                return@launch
            }
            val f = ExportUtils.writeCsvCache(
                appContext, "supplements_${stamp()}.csv", supplementRepo.supplementsToCsv(rows),
            )
            _message.value = "已导出补录 ${rows.size} 条 ✓"
            shareCsv(context, f)
        }
    }

    fun resolve(id: Long) {
        viewModelScope.launch {
            markRepo.resolve(id)
            _message.value = "已解决 ✓"
        }
    }

    fun reopen(id: Long) {
        viewModelScope.launch {
            markRepo.reopen(id)
            _message.value = "已退回待处理"
        }
    }

    private fun stamp(): String = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.CHINA).format(java.util.Date())

    private fun shareCsv(context: Context, file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${appContext.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "发送标记清单"))
        }.onFailure {
            _message.value = "分享失败：${it.message}"
        }
    }
}
