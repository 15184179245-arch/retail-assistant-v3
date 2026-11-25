package com.retaildemo.v2.data.repo

import com.retaildemo.v2.data.local.dao.TempProductDao
import com.retaildemo.v2.data.local.entity.TempProductEntity
import com.retaildemo.v2.data.remote.AppConfig
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** 临时码号段生成（纯函数，永不查上游）。TMP-{yyyyMMdd}-{c|s}{NNN}，与管家婆 code 体系物理隔离。 */
object TempCode {
    fun build(type: String, day: String, seq: Int): String {
        val letter = if (type == "strade") "s" else "c"
        return "TMP-$day-$letter" + "%03d".format(seq)
    }
}

/** 无码货表单校验（纯函数，供 UI/VM/单测共用）。返回 null=通过，否则错误文案。 */
object TempFormValidator {
    fun validate(
        type: String,
        name: String,
        spec: String,
        sellPriceText: String,
        sourceVendor: String,
        stradePriceText: String,
        voucherPhoto: String?,
    ): String? {
        if (name.isBlank()) return "请填写商品名"
        if (type == "custom") {
            val price = sellPriceText.toDoubleOrNull() ?: return "店内商品请填写有效售价"
            if (price < 0) return "售价不能为负"
        } else if (type == "strade") {
            // 串货的价值在参数完整（2026-09-10 老板反馈，回归 billing-loop §2 原口径）：
            // 规格型号/售价/在谁家串/串货价/凭证五项必填；商品编号(条码)最不重要，出库前补齐即可
            if (spec.isBlank()) return "串货须填：规格型号"
            val price = sellPriceText.toDoubleOrNull() ?: return "串货须填：售价"
            if (price < 0) return "售价不能为负"
            if (sourceVendor.isBlank()) return "串货须填：在谁家串"
            val sp = stradePriceText.toDoubleOrNull() ?: return "串货须填：串货价(进价)"
            if (sp < 0) return "串货价不能为负"
            if (voucherPhoto.isNullOrBlank()) return "串货必须拍凭证照片"
        }
        return null
    }
}

/**
 * 无码货临时档案仓库（B3）。临时码=当日全量计数+1 序号（同日期不重，跨日随日期段自然重置）。
 * 自定义只入待补全队列；串货记凭证+进价+垫钱人，reimburseStatus=pending 进 B4 台账。
 */
class TempProductRepo @Inject constructor(
    private val dao: TempProductDao,
    private val config: AppConfig,
) {
    fun observeByType(type: String): Flow<List<TempProductEntity>> = dao.observeByType(type)

    suspend fun listByType(type: String): List<TempProductEntity> = dao.listByType(type)

    suspend fun nextTempCode(type: String): String {
        val day = BillTime.today().replace("-", "")
        val seq = dao.countMatching("TMP-$day-%") + 1
        return TempCode.build(type, day, seq)
    }

    suspend fun add(
        type: String,
        name: String,
        spec: String?,
        sellPrice: Double,
        costPrice: Double?,
        note: String?,
        sourceVendor: String? = null,
        stradePrice: Double? = null,
        voucherPhoto: String? = null,
    ): TempProductEntity {
        val tp = TempProductEntity(
            tempCode = nextTempCode(type),
            type = type,
            name = name.trim(),
            spec = spec?.trim()?.ifBlank { null },
            sellPrice = sellPrice,
            costPrice = costPrice,
            note = note?.trim()?.ifBlank { null },
            sourceVendor = sourceVendor?.trim()?.ifBlank { null },
            stradePrice = stradePrice,
            voucherPhoto = voucherPhoto?.takeIf { it.isNotBlank() },
            reimburseStatus = if (type == "strade") "pending" else "none",
            createdBy = config.staffName,
            createdAt = BillTime.now(),
        )
        dao.insert(tp)
        return tp
    }

    /** 队列销账：正式码建好后删除临时记录（不级联删账单行，账单历史保留临时码供追溯）。 */
    suspend fun delete(code: String) = dao.deleteByCode(code)

    /** B4 台账：串货记录按报销状态取（pending=待报销，done=已核销待入库）。 */
    suspend fun listStrade(reimburseStatus: String): List<TempProductEntity> =
        dao.listByType("strade").filter { it.reimburseStatus == reimburseStatus }

    /** B4 核销（单向：pending→done，UI 不提供回退）。 */
    suspend fun setReimburseStatus(code: String, status: String) =
        dao.setReimburseStatus(code, status)
}
