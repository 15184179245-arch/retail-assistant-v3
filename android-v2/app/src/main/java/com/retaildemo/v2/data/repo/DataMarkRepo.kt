package com.retaildemo.v2.data.repo

import com.retaildemo.v2.data.local.dao.DataMarkDao
import com.retaildemo.v2.data.local.entity.DataMarkEntity
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.remote.MarkSyncDto
import com.retaildemo.v2.data.remote.retaildemoApi
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * 数据问题标记仓库（I4）。店员只写本表打标记（≤10 秒），专员在管家婆调完、
 * 下次同步回来后人工点「已解决」关闭——标记不自动消失。
 * 红线：本仓库绝不调用改库存/改价能力，标记与调账是两条道。
 */
class DataMarkRepo @Inject constructor(
    private val dao: DataMarkDao,
    private val config: AppConfig,
    private val api: dagger.Lazy<retaildemoApi>,
) {
    fun observeByStatus(s: String): Flow<List<DataMarkEntity>> = dao.observeByStatus(s)

    fun observeAll(): Flow<List<DataMarkEntity>> = dao.observeAll()

    suspend fun listByStatus(s: String): List<DataMarkEntity> = dao.listByStatus(s)

    /** 新建标记：createdBy 取设备归属（I1 staffName），禁止写死 unknown。 */
    suspend fun create(
        sku: String,
        barcode: String,
        name: String,
        markType: String,
        note: String,
        photo: String?,
    ): Long {
        val entity = DataMarkEntity(
            sku = sku,
            barcode = barcode,
            name = name,
            markType = markType,
            note = note.trim(),
            photo = photo?.takeIf { it.isNotBlank() },
            status = DataMarkEntity.STATUS_OPEN,
            createdBy = config.staffName,
            createdAt = BillTime.now(),
        )
        val id = dao.insert(entity)
        syncToServer(listOf(entity.copy(id = id)))
        return id
    }

    /** 导出后批量置 exported（open→exported 单向，不在 open 的行不受影响）。 */
    suspend fun markExported(ids: List<Long>) {
        ids.forEach { dao.markExported(it) }
    }

    /** 同步标记到后端网关 bills.db（供管理员与老板在 Web 看板直接导出/审核）。 */
    suspend fun syncToServer(items: List<DataMarkEntity>) {
        runCatching {
            val dtos = items.map {
                MarkSyncDto(
                    sku = it.sku,
                    barcode = it.barcode,
                    productName = it.name,
                    markType = it.markType,
                    note = it.note,
                    photoUri = it.photo ?: "",
                    status = it.status,
                    created_at = it.createdAt,
                )
            }
            api.get().syncMarks(dtos)
        }
    }

    /** 人工确认已解决：exported/open 均可关（同步回来对照修好即点）。 */
    suspend fun resolve(id: Long) = dao.resolve(id, BillTime.now())

    /** 没修好退回 open（resolved_at 清空，可重新导出）。 */
    suspend fun reopen(id: Long) = dao.reopen(id)

    /** 标记导出 CSV 行（专员照单去管家婆调整用，UTF-8 BOM，Excel 直开）。 */
    fun marksToCsv(rows: List<DataMarkEntity>): String = ExportUtils.toCsv(
        headers = listOf("商品编码", "商品名称", "条码", "问题类型", "备注", "照片文件", "标记人", "标记日期", "状态"),
        rows = rows.map {
            listOf(it.sku, it.name, it.barcode, DataMarkEntity.typeLabel(it.markType), it.note, it.photo ?: "", it.createdBy, it.createdAt, it.status)
        },
    )
}
