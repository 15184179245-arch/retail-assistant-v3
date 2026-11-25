package com.retaildemo.v2.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retaildemo.v2.data.local.entity.TempProductEntity
import com.retaildemo.v2.data.remote.AliasBindRequest
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.data.repo.NetworkMonitor
import com.retaildemo.v2.data.repo.TempProductRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** B4 串货台账：待报销(pending，可核销) / 待入库(done 未回写，可回写正式码) 两视图。 */
@HiltViewModel
class StradeLedgerViewModel @Inject constructor(
    private val tempRepo: TempProductRepo,
    private val api: retaildemoApi,
    private val config: AppConfig,
    networkMonitor: NetworkMonitor,
) : ViewModel() {

    /** 36 三态合辑 · 离线条：串货台账依赖网络导出与回写，断网就地提示。 */
    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

    var tab by mutableStateOf("pending")
        private set
    var rows by mutableStateOf<List<TempProductEntity>>(emptyList())
        private set
    /** 待入库视图：已回写正式码的 tempCode 集合（回写完成的不再出现在待入库列表）。 */
    var boundCodes by mutableStateOf<Set<String>>(emptySet())
        private set
    var busy by mutableStateOf(false)
        private set
    var msg by mutableStateOf<String?>(null)
        private set

    fun switchTab(tab: String) {
        this.tab = tab
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            busy = true
            try {
                rows = tempRepo.listStrade(tab)
                boundCodes = if (tab == "done") rows.map { it.tempCode }
                    .filter { isBound(it) }.toSet() else emptySet()
            } catch (e: Exception) {
                msg = "加载失败：${e.message}"
            } finally {
                busy = false
            }
        }
    }

    /** 核销（单向 pending→done）：报销完成，进待入库视图。 */
    fun writeOff(code: String) {
        viewModelScope.launch {
            tempRepo.setReimburseStatus(code, "done")
            refresh()
        }
    }

    /**
     * 回写正式码：调网关 /alias/bind（source=strade 可追溯），成功即从待入库消失。
     * 红线：只写别名映射，绝不写管家婆。
     */
    fun bindOfficialCode(tempCode: String, officialCode: String) {
        val sku = officialCode.trim()
        if (sku.isBlank()) {
            msg = "请输入正式码"
            return
        }
        viewModelScope.launch {
            busy = true
            try {
                val resp = api.aliasBind(
                    AliasBindRequest(
                        barcode = tempCode,
                        sku = sku,
                        actor = config.staffName.ifBlank { "unknown" },
                        source = "strade",
                    ),
                )
                if (resp.isSuccessful) {
                    msg = "已回写：$tempCode → $sku"
                } else {
                    msg = "回写失败：HTTP ${resp.code()}"
                }
            } catch (e: Exception) {
                msg = "回写失败：${e.message}"
            } finally {
                busy = false
                refresh()
            }
        }
    }

    fun clearMsg() {
        msg = null
    }

    private suspend fun isBound(tempCode: String): Boolean = try {
        api.aliasResolve(tempCode).body()?.data != null
    } catch (e: Exception) {
        false
    }
}
