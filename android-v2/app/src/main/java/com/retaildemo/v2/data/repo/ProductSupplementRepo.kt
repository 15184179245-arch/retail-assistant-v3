package com.retaildemo.v2.data.repo

import com.retaildemo.v2.data.local.dao.DataMarkDao
import com.retaildemo.v2.data.local.dao.SupplementDao
import com.retaildemo.v2.data.local.entity.DataMarkEntity
import com.retaildemo.v2.data.local.entity.ProductSupplementEntity
import com.retaildemo.v2.data.remote.AppConfig
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * 商品信息本地补充仓库（I4 §11）。详情页「编辑信息」落盘：图片/品牌/规格/备注，
 * 条码只记 pendingBarcode（待 S3 绑定流程，绝不裸改主条码列），价格永不经此入口。
 */
class ProductSupplementRepo @Inject constructor(
    private val dao: SupplementDao,
    private val markDao: DataMarkDao,
    private val config: AppConfig,
) {
    fun observeBySku(sku: String): Flow<ProductSupplementEntity?> = dao.observeBySku(sku)

    suspend fun getBySku(sku: String): ProductSupplementEntity? = dao.getBySku(sku)

    fun observeAll(): Flow<List<ProductSupplementEntity>> = dao.observeAll()

    suspend fun listAll(): List<ProductSupplementEntity> = dao.listAll()

    /**
     * 保存本地补充（整行 REPLACE）。新码非空且与主条码不同 → 附带产生一条
     * 待绑条码标记（进治理队列导出，S3 落地前只记录不绑定）。
     */
    suspend fun save(
        sku: String,
        name: String,
        brand: String?,
        spec: String?,
        note: String?,
        photo: String?,
        pendingBarcode: String?,
        mainBarcode: String?,
    ) {
        val now = BillTime.now()
        val pending = pendingBarcode?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals(mainBarcode?.trim(), ignoreCase = true) }
        dao.upsert(
            ProductSupplementEntity(
                sku = sku,
                name = name,
                brand = brand?.trim()?.ifBlank { null },
                spec = spec?.trim()?.ifBlank { null },
                note = note?.trim()?.ifBlank { null },
                photo = photo?.takeIf { it.isNotBlank() },
                pendingBarcode = pending,
                updatedBy = config.staffName,
                updatedAt = now,
            ),
        )
        if (pending != null) {
            markDao.insert(
                DataMarkEntity(
                    sku = sku,
                    barcode = mainBarcode?.trim().orEmpty(),
                    name = name,
                    markType = DataMarkEntity.TYPE_BARCODE_SUPPLEMENT,
                    note = "待绑新码：$pending",
                    createdBy = config.staffName,
                    createdAt = now,
                ),
            )
        }
    }

    /** 补录导出 CSV 行（品牌/规格/待绑条码给管家婆建档参考用）。 */
    fun supplementsToCsv(rows: List<ProductSupplementEntity>): String = ExportUtils.toCsv(
        headers = listOf("商品编码", "商品名称", "品牌(本地)", "规格(本地)", "备注(本地)", "待绑条码", "照片文件", "更新人", "更新时间"),
        rows = rows.map {
            listOf(it.sku, it.name, it.brand ?: "", it.spec ?: "", it.note ?: "", it.pendingBarcode ?: "", it.photo ?: "", it.updatedBy, it.updatedAt)
        },
    )
}
