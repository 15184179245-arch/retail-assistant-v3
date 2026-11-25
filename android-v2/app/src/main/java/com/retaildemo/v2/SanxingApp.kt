package com.retaildemo.v2

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.retaildemo.v2.sync.BillSyncWorker
import com.retaildemo.v2.sync.MirrorWorker
import com.retaildemo.v2.sync.ProductSyncWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class retaildemoApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var billRepo: com.retaildemo.v2.data.repo.BillRepo

    // 2026-09-11 老板令：崩溃收集上报 + 设备身份登记（谁在线、哪台设备绑谁）
    @Inject
    lateinit var crashReporter: com.retaildemo.v2.util.CrashReporter

    @Inject
    lateinit var deviceRegistry: com.retaildemo.v2.data.repo.DeviceRegistry

    // H2 运行日志系统：关键事件本地留存 + 批量上报网关，服务器实时检索定位问题
    @Inject
    lateinit var appLogger: com.retaildemo.v2.data.repo.AppLogger

    override val workManagerConfiguration: Configuration
        get() = if (::workerFactory.isInitialized) {
            Configuration.Builder().setWorkerFactory(workerFactory).build()
        } else {
            Configuration.Builder().build()
        }

    override fun onCreate() {
        super.onCreate()
        com.retaildemo.v2.ui.components.ScanFeedback.init(this)
        setupCrashHandler()
        // 2026-09-19 冷启动收口：WorkManager 首次 getInstance 要初始化它自己的 Room
        // （再叠 6 条周期任务注册），原先同步跑在主线程 onCreate 里，直接拉长白屏时间。
        // enqueue 本就线程安全，挪到后台线程，主线程只留「能画第一帧」的最小路径。
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            schedulePeriodicSync()
        }
        markCrossDayRecycledOnStart()
        syncBillsOnStart()
        startupTelemetry()
        startReceiptRealtimeLoop()
    }

    /**
     * B13 实时出库（老板 2026-09-26 令「结算后要实时马上就能更新」）：
     * 前台每 15 秒拉一次收银端结算回执——命中待出库单立即转已出库并推服务端
     * （插件领单列表同步消失，防重复导入）。与插件端 5s 扫描配合，
     * 结算 → APP 转已出库 全链路 ≤ 20 秒。离线时静默跳过，不耗电不报错。
     */
    private fun startReceiptRealtimeLoop() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            while (true) {
                runCatching { billRepo.syncReceiptsAndPush() }
                kotlinx.coroutines.delay(15_000)
            }
        }
    }

    /**
     * 2026-09-11 老板令：启动即补传上次崩溃报告 + 设备报到（登记绑定店员/在线）。
     * H2：启动事件与补传结果落运行日志；日志随后即时触发一次上传。
     * 全部静默失败——离线可用优先，失败交给 15 分钟周期 Worker 兜底。
     */
    private fun startupTelemetry() {
        appLogger.log(com.retaildemo.v2.data.repo.LogPipeline.LEVEL_INFO, "App", "APP 启动")
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { crashReporter.uploadPending() }
                .onSuccess { sent ->
                    if (sent > 0) {
                        appLogger.log(
                            com.retaildemo.v2.data.repo.LogPipeline.LEVEL_INFO,
                            "CrashReporter",
                            "启动补传崩溃报告 $sent 条",
                        )
                    }
                }
            runCatching { deviceRegistry.checkin() }
        }
    }

    /**
     * M2 启动即同步：重装/换机后店员一打开 APP 就把服务器账单要回来，
     * 不必等 15 分钟周期任务。失败静默——离线可用优先，下次启动或周期任务会重试。
     */
    private fun syncBillsOnStart() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { billRepo.syncBills() }
                .onFailure { android.util.Log.w("retaildemoApp", "startup bill sync skipped", it) }
        }
    }

    /** B7 打烊闭环：进程一启动就把跨天挂单归档进回收站（无论恢复到哪个页面都生效）。 */
    private fun markCrossDayRecycledOnStart() {
        android.util.Log.i("retaildemoApp", "B7 cross-day recycle check scheduled")
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { billRepo.markCrossDayRecycled() }
                .onFailure { android.util.Log.e("retaildemoApp", "B7 cross-day recycle failed", it) }
        }
    }

    /**
     * 2026-09-11 升级：崩溃处理改由 CrashReporter 统一接管——保留 crash.log 本地现场，
     * 同时入队 JSONL 待下次启动上报服务器（远程收回新设备闪退原因）。
     */
    private fun setupCrashHandler() {
        crashReporter.install()
    }

    private fun schedulePeriodicSync() {
        try {
            val wm = WorkManager.getInstance(this)
            val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            wm.enqueueUniquePeriodicWork(
                BillSyncWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BillSyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(net)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build(),
            )
            wm.enqueueUniquePeriodicWork(
                ProductSyncWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ProductSyncWorker>(6, TimeUnit.HOURS)
                    .setConstraints(net)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build(),
            )
            // S13 数据全景：孤岛表单向镜像（老王可见性），15 分钟周期，失败静默不影响主流程
            wm.enqueueUniquePeriodicWork(
                MirrorWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<MirrorWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(net)
                    .build(),
            )
            // I19 商品照片补传：拍照即时传 + 详情页打开顺手传；周期兜底由 6h 收紧到 1h
            // （目录为空时零网络请求，代价可忽略）。UPDATE 策略：改期后对已装机也生效
            // （KEEP 会沿用旧 6h 排程，老设备永远吃不到新周期）。
            wm.enqueueUniquePeriodicWork(
                com.retaildemo.v2.sync.ProductPhotoSyncWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<com.retaildemo.v2.sync.ProductPhotoSyncWorker>(1, TimeUnit.HOURS)
                    .setConstraints(net)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build(),
            )
            // 2026-09-11 老板令：设备在线心跳（15 分钟报到；启动/绑店员另有即时报到）
            wm.enqueueUniquePeriodicWork(
                com.retaildemo.v2.sync.DeviceCheckinWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<com.retaildemo.v2.sync.DeviceCheckinWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(net)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build(),
            )
            // H2 运行日志上报（15 分钟周期兜底；启动/ERROR 级另有即时触发）
            wm.enqueueUniquePeriodicWork(
                com.retaildemo.v2.sync.LogUploadWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<com.retaildemo.v2.sync.LogUploadWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(net)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build(),
            )
        } catch (t: Throwable) {
            android.util.Log.e("retaildemoApp", "WorkManager sync scheduling suppressed, app continues normally", t)
        }
    }
}
