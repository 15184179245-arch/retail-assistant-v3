package com.retaildemo.v2.data.repo

import com.retaildemo.v2.data.local.dao.ProductDao
import com.retaildemo.v2.data.local.entity.ProductEntity
import com.retaildemo.v2.data.remote.AdjustRequest
import com.retaildemo.v2.data.remote.BindRequest
import com.retaildemo.v2.data.remote.CellProductDto
import com.retaildemo.v2.data.remote.LocationNodeDto
import com.retaildemo.v2.data.remote.LowStockDto
import com.retaildemo.v2.data.remote.MovementDto
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.data.remote.UnbindRequest
import javax.inject.Inject
import javax.inject.Singleton

/** 库存/货位/打印（inventory + print 服务）仓库。 */
@Singleton
class InventoryRepo @Inject constructor(
    private val api: retaildemoApi,
    private val productDao: ProductDao,
) {
    /** 五级货架树。 */
    suspend fun tree(): List<LocationNodeDto> = runCatching {
        api.inventoryTree().body()?.data.orEmpty()
    }.getOrDefault(emptyList())

    suspend fun lowStock(): List<LowStockDto> = runCatching {
        api.lowStock().body()?.data.orEmpty()
    }.getOrDefault(emptyList())

    suspend fun zeroStock(): List<LowStockDto> = runCatching {
        api.zeroStock().body()?.data.orEmpty()
    }.getOrDefault(emptyList())

    suspend fun movements(limit: Int = 50, offset: Int = 0): List<MovementDto> = runCatching {
        api.movements(limit, offset).body()?.data.orEmpty()
    }.getOrDefault(emptyList())

    /** 调账：qty 为增减量。type=adjust/in/out（服务端按 product_id 记）。 */
    suspend fun adjust(productId: Long, delta: Int, type: String, note: String, operator: String): Boolean =
        runCatching {
            val resp = api.adjustStock(AdjustRequest(productId, delta, type, note, operator))
            resp.isSuccessful && resp.body()?.success == true
        }.getOrDefault(false)

    /** 绑定商品到货位（cell_code）。 */
    suspend fun bind(productId: Long, cellCode: String): Boolean = runCatching {
        val resp = api.bindProduct(BindRequest(productId, cellCode))
        resp.isSuccessful && resp.body()?.success == true
    }.getOrDefault(false)

    /** 解绑（product_locations 记录 id）。 */
    suspend fun unbind(plId: Long): Boolean = runCatching {
        val resp = api.unbindProduct(UnbindRequest(plId))
        resp.isSuccessful && resp.body()?.success == true
    }.getOrDefault(false)

    /** 货位下绑定的商品。 */
    suspend fun bindingsOfCell(cellCode: String): List<CellProductDto> = runCatching {
        api.cellProducts(cellCode).body()?.data.orEmpty()
    }.getOrDefault(emptyList())

    /** 调账后刷新本地库存缓存（拉该商品最新数据）。入参可为条码或 SKU（U4：无条码商品按 SKU 打开详情）。 */
    suspend fun refreshProduct(barcode: String): ProductEntity? {
        val hit = runCatching {
            api.search(barcode).body()?.data
                ?.let {
                    parseSearchJson(it).first.firstOrNull { p ->
                        p.barcode.equals(barcode, true) || p.sku.equals(barcode, true)
                    }
                }
        }.getOrNull() ?: return null
        val entity = hit.toEntity()
        productDao.upsertAll(listOf(entity))
        return entity
    }
}
