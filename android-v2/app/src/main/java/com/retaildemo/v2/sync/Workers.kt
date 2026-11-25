package com.retaildemo.v2.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.retaildemo.v2.data.repo.BillRepo
import com.retaildemo.v2.data.repo.LogPipeline
import com.retaildemo.v2.data.repo.ProductRepo
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** 账单双向同步（推送未同步 + 拉取合并）。H2：失败落运行日志，服务器可实时收回「同步为什么没成」。 */
@HiltWorker
class BillSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val billRepo: BillRepo,
    private val productRepo: ProductRepo,
    private val appLogger: com.retaildemo.v2.data.repo.AppLogger,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // B13: 增量拉取收银端结算回执（已结算待出库单转已出库）
        runCatching { billRepo.syncReceipts() }
        // 2026-10-03 老板报「同步后列表新价、详情旧价」根修②：商品镜像追平从 6h 提到 15min。
        // 按水位增量只拉变更行（轻请求，只读服务端已入库的变更）；上游管家婆全量节奏不变。
        // 失败静默——下轮 15min 再追，不与账单同步结果挂钩。
        runCatching { productRepo.pullChanged() }
        return if (billRepo.syncBills()) {
            Result.success()
        } else {
            appLogger.log(LogPipeline.LEVEL_WARN, "BillSync", "账单同步失败，等待下轮重试")
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "bill_sync"
    }
}

/** 商品库同步（首次/6h 周期/手动）。S17：走真实上游同步（服务端登录管家婆拉最新 →
 * 仅变更落库）后增量回拉本地；旧服务器无该端点时仓库层自动退回全量拉取，功能不中断。
 * S18：商品同步成功后顺带回拉客户档案（best-effort，失败不影响商品同步结果）。 */
@HiltWorker
class ProductSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val productRepo: ProductRepo,
    private val billRepo: BillRepo,
    private val appLogger: com.retaildemo.v2.data.repo.AppLogger,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val outcome = productRepo.syncFromUpstream()
        if (outcome.ok) {
            runCatching { billRepo.syncCustomersFromServer() }
        } else {
            appLogger.log(LogPipeline.LEVEL_WARN, "ProductSync", "商品同步失败：${outcome.message}")
        }
        return if (outcome.ok) Result.success() else Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "product_sync"
    }
}

/** I19 商品照片补传：photos/ 待传文件逐个上传（成功即删）；仍有剩余 → retry 退避重试。 */
@HiltWorker
class ProductPhotoSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val photoRepo: com.retaildemo.v2.data.repo.ProductPhotoRepo,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val (_, left) = photoRepo.retryPending()
        return if (left == 0) Result.success() else Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "product_photo_sync"
    }
}

/** 2026-09-11 老板令：设备在线心跳（15 分钟周期报到，服务器据 last_seen 判定谁在线）。 */
@HiltWorker
class DeviceCheckinWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val deviceRegistry: com.retaildemo.v2.data.repo.DeviceRegistry,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (deviceRegistry.checkin()) Result.success() else Result.retry()

    companion object {
        const val UNIQUE_NAME = "device_checkin"
    }
}

/**
 * H2（2026-09-11 老板令）运行日志上传：批量取本地日志 → POST /api/logs/ingest → 成功即删
 * （日志是遥测不是业务数据，宁可断网时丢上限也不无限堆积）；失败 retry 退避重试。
 * 触发时机：15 分钟周期（retaildemoApp）+ 启动即传 + ERROR 级日志即传（AppLogger.triggerUpload）。
 */
@HiltWorker
class LogUploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val logDao: com.retaildemo.v2.data.local.dao.ClientLogDao,
    private val api: com.retaildemo.v2.data.remote.retaildemoApi,
    private val config: com.retaildemo.v2.data.remote.AppConfig,
) : CoroutineWorker(context, params) {
    // s18-customer（2026-09-12）：表达式体改块体——原写法 `Result = try { ... return ... }`
    // 无法编译（表达式体内禁 return），语义零改动，仅修编译挡板。
    override suspend fun doWork(): Result {
        return try {
            var rounds = 0
            while (rounds++ < 20) { // 200×20=4000 条/次上限，防异常积压一次拖死
                val pending = logDao.pendingLogs(LogPipeline.MAX_UPLOAD_BATCH)
                if (pending.isEmpty()) break
                val resp = api.ingestLogs(
                    com.retaildemo.v2.data.remote.LogIngestRequest(
                        device_id = config.deviceId,
                        app_version = appVersion(),
                        model = deviceModel(),
                        records = pending.map {
                            com.retaildemo.v2.data.remote.LogRecordDto(
                                ts = it.ts,
                                level = it.level,
                                tag = it.tag,
                                message = it.message,
                            )
                        },
                    ),
                )
                if (!resp.isSuccessful || resp.body()?.success != true) return Result.retry()
                logDao.deleteByIds(pending.map { it.id })
            }
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }

    private fun appVersion(): String = runCatching {
        applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0).versionName
    }.getOrNull().orEmpty()

    private fun deviceModel(): String =
        "${android.os.Build.MANUFACTURER.orEmpty()} ${android.os.Build.MODEL.orEmpty()}".trim()

    companion object {
        const val UNIQUE_NAME = "log_upload"
    }
}
