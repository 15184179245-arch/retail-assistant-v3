package com.retaildemo.v2.data.repo

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.retaildemo.v2.data.local.dao.ClientLogDao
import com.retaildemo.v2.data.local.entity.ClientLogEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * H2 日志管线的纯函数部分（纯 JVM 可单测）：级别归一、消息截断、上传分批。
 * 容量口径：单条 ≤2000 字符、单批 ≤200 条、本地留存 ≤2000 行（超量砍最老）。
 */
object LogPipeline {
    const val LEVEL_DEBUG = "DEBUG"
    const val LEVEL_INFO = "INFO"
    const val LEVEL_WARN = "WARN"
    const val LEVEL_ERROR = "ERROR"

    const val MAX_MESSAGE = 2000
    const val MAX_UPLOAD_BATCH = 200
    const val MAX_ROWS = 2000

    /** 未知级别一律归 INFO，防上游随手写串级别导致服务端过滤失真。 */
    fun normalizeLevel(raw: String): String = when (raw.trim().uppercase()) {
        LEVEL_DEBUG -> LEVEL_DEBUG
        LEVEL_WARN -> LEVEL_WARN
        LEVEL_ERROR -> LEVEL_ERROR
        else -> LEVEL_INFO
    }

    fun sanitizeMessage(raw: String): String {
        val t = raw.trim()
        return if (t.length <= MAX_MESSAGE) t else t.take(MAX_MESSAGE) + "...[truncated]"
    }

    fun chunk(records: List<ClientLogEntity>, max: Int = MAX_UPLOAD_BATCH): List<List<ClientLogEntity>> =
        if (records.isEmpty()) emptyList()
        else if (records.size <= max) listOf(records) else records.chunked(max)
}

/**
 * 2026-09-11 老板令（H2）：APP 运行日志系统——店内手机出问题，服务器不用拿到手机
 * 就能收回「它当时在干什么」。log() 非阻塞写 Room（失败静默，绝不打断业务流）；
 * ERROR 级即刻触发一次上传，配合 15 分钟周期 Worker 与启动即传，服务器端
 * GET /api/logs/recent 随时可查。日志是遥测不是业务数据：上传成功即删。
 */
@Singleton
class AppLogger @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val dao: ClientLogDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun log(level: String, tag: String, message: String) {
        val normalized = LogPipeline.normalizeLevel(level)
        val clean = LogPipeline.sanitizeMessage(message)
        scope.launch {
            runCatching {
                dao.insert(
                    ClientLogEntity(
                        ts = System.currentTimeMillis(),
                        level = normalized,
                        tag = tag,
                        message = clean,
                    ),
                )
                dao.pruneKeepLatest(LogPipeline.MAX_ROWS)
            }
        }
        if (normalized == LogPipeline.LEVEL_ERROR) triggerUpload()
    }

    /** 触发一次即时上传（进行中则 KEEP 去重）；WorkManager 未就绪/异常静默，周期任务兜底。 */
    fun triggerUpload() {
        runCatching {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                UPLOAD_NOW_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<com.retaildemo.v2.sync.LogUploadWorker>()
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                    )
                    .build(),
            )
        }
    }

    companion object {
        const val UPLOAD_NOW_NAME = "log_upload_now"
    }
}
