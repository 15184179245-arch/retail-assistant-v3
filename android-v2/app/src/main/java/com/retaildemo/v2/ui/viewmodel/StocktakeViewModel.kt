package com.retaildemo.v2.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retaildemo.v2.data.local.entity.ProductEntity
import com.retaildemo.v2.data.remote.CellProductDto
import com.retaildemo.v2.data.remote.ProductLocationDto
import com.retaildemo.v2.data.repo.InventoryRepo
import com.retaildemo.v2.data.repo.ProductRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 盘点状态模型。 */
data class StocktakeItem(
    val plId: Long,
    val productId: Long,
    val barcode: String?,
    val name: String,
    val systemQty: Int,
)

@HiltViewModel
class StocktakeViewModel @Inject constructor(
    private val inventoryRepo: InventoryRepo,
    private val productRepo: ProductRepo,
    private val printRepo: com.retaildemo.v2.data.repo.PrintRepo,
    private val photoRepo: com.retaildemo.v2.data.repo.ProductPhotoRepo,
) : ViewModel() {

    private val _cellCode = MutableStateFlow("")
    val cellCode: StateFlow<String> = _cellCode.asStateFlow()

    private val _items = MutableStateFlow<List<CellProductDto>>(emptyList())
    val items: StateFlow<List<CellProductDto>> = _items.asStateFlow()

    private val _counts = MutableStateFlow<Map<Long, Int>>(emptyMap()) // productId -> 实数
    val counts: StateFlow<Map<Long, Int>> = _counts.asStateFlow()

    private val _summary = MutableStateFlow<Pair<String, Boolean>?>(null)
    val summary: StateFlow<Pair<String, Boolean>?> = _summary.asStateFlow()

    fun loadCell(code: String) {
        if (code.isBlank()) return
        viewModelScope.launch {
            _cellCode.value = code
            _items.value = inventoryRepo.bindingsOfCell(code)
            _counts.value = emptyMap()
            _summary.value = if (_items.value.isEmpty()) "该货位没有绑定商品" to false else null
        }
    }

    fun setCount(productId: Long, value: Int) {
        _counts.value = _counts.value + (productId to value)
    }

    /** 清空实数 = 回到「未盘」状态（退格删空/C 清空都走这里，不能落成 0）。 */
    fun clearCount(productId: Long) {
        _counts.value = _counts.value - productId
    }

    /** 差异行批量提交调账（adjust 用增量：差值 = 实数 - 系统数）。 */
    fun commitDifferences() {
        viewModelScope.launch {
            val diffs = _items.value.mapNotNull { cp ->
                val counted = _counts.value[cp.id] ?: return@mapNotNull null
                val delta = counted - cp.stock_qty
                if (delta == 0.0) null else Triple(cp.id, delta, cp.name)
            }
            if (diffs.isEmpty()) {
                _summary.value = "没有差异需要提交" to true
                return@launch
            }
            var ok = 0
            diffs.forEach { (pid, delta, _) ->
                // 调账接口按整数件（历史契约）；散装小数差异取整提交，与迁移前一致
                if (inventoryRepo.adjust(pid, delta.toInt(), "adjust", "盘点差异", "")) ok++
            }
            _summary.value =
                ("提交 ${diffs.size} 笔差异，成功 $ok 笔").let { it to (ok == diffs.size) }
            if (ok > 0) loadCell(_cellCode.value)
        }
    }

    /** I19 盘点现场拍照：上传成功删本机；失败留 photos/ 待传（下次拍照/Worker 补传）。 */
    fun capturePhoto(file: java.io.File) {
        viewModelScope.launch {
            if (!file.exists() || file.length() == 0L) return@launch
            val ok = photoRepo.uploadFile(file)
            _summary.value = (if (ok) "照片已上传 ✓" else "照片已保存本机，联网后自动补传") to ok
        }
    }

    /** 巡检补打：将指定商品加入打印队列（S5 巡检补打）。 */
    fun enqueueStocktakePrint(item: CellProductDto) {
        viewModelScope.launch {
            val job = com.retaildemo.v2.data.local.entity.PrintJobEntity(
                sku = item.sku?.takeIf { it.isNotBlank() } ?: item.barcode.orEmpty().ifBlank { "PROD-${item.id}" },
                name = item.name,
                spec = item.spec,
                barcode = item.barcode,
                sellPrice = item.sell_price,
                qty = 1,
                sourceScene = com.retaildemo.v2.data.local.entity.PrintJobEntity.SCENE_STOCKTAKE,
            )
            printRepo.enqueue(job)
            _summary.value = "已将【${item.name}】加入打印队列 ✓" to true
        }
    }
}
