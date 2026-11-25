package com.retaildemo.v2.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retaildemo.v2.data.local.entity.ProductEntity
import com.retaildemo.v2.data.remote.LowStockDto
import com.retaildemo.v2.data.remote.MovementDto
import com.retaildemo.v2.data.repo.InventoryRepo
import com.retaildemo.v2.data.repo.NetworkMonitor
import com.retaildemo.v2.data.repo.ProductRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// I20（2026-09-11）：五级货架的树 VM 与货位详情 VM 已删（后端五级树死代码，全仓零引用、无路由）；
// 三级货架改由 ShelfViewModel + 本地 Room 承载。下面的预警/流水/调账 VM 仍在用。

@HiltViewModel
class StockViewModel @Inject constructor(
    private val inventoryRepo: InventoryRepo,
    networkMonitor: NetworkMonitor,
) : ViewModel() {

    /** 36 三态合辑 · 离线条：全局网络态（NetworkMonitor 唯一源），断网就地降级不转圈。 */
    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

    private val _low = MutableStateFlow<List<LowStockDto>>(emptyList())
    val low: StateFlow<List<LowStockDto>> = _low.asStateFlow()

    private val _zero = MutableStateFlow<List<LowStockDto>>(emptyList())
    val zero: StateFlow<List<LowStockDto>> = _zero.asStateFlow()

    private val _movements = MutableStateFlow<List<MovementDto>>(emptyList())
    val movements: StateFlow<List<MovementDto>> = _movements.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _low.value = inventoryRepo.lowStock()
            _zero.value = inventoryRepo.zeroStock()
            _movements.value = inventoryRepo.movements()
            _loading.value = false
        }
    }

}

@HiltViewModel
class AdjustViewModel @Inject constructor(
    private val inventoryRepo: InventoryRepo,
    private val productRepo: ProductRepo,
) : ViewModel() {

    private val _hit = MutableStateFlow<ProductEntity?>(null)
    val hit: StateFlow<ProductEntity?> = _hit.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 扫码/输入条码找商品。 */
    fun lookup(barcode: String) {
        viewModelScope.launch {
            _hit.value = productRepo.getByBarcode(barcode.trim())
        }
    }

    fun submit(qty: Int, isIn: Boolean, reason: String, onDone: (Boolean) -> Unit) {
        val product = _hit.value ?: return
        viewModelScope.launch {
            _busy.value = true
            val ok = inventoryRepo.adjust(product.id, qty, if (isIn) "in" else "out", reason, "")
            if (ok) inventoryRepo.refreshProduct(product.barcode ?: "")
            _busy.value = false
            onDone(ok)
        }
    }
}
