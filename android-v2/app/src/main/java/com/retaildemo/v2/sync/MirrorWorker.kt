package com.retaildemo.v2.sync

import android.content.Context
import android.provider.Settings
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.retaildemo.v2.data.repo.MirrorRepo
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** S13 数据全景：孤岛表单向镜像（≥15 分钟周期，retaildemoApp 排程）。失败静默，不打扰主流程。 */
@HiltWorker
class MirrorWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val mirrorRepo: MirrorRepo,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val device = Settings.Secure.getString(
            applicationContext.contentResolver, Settings.Secure.ANDROID_ID,
        ) ?: "unknown"
        mirrorRepo.push(device)
        return Result.success() // 镜像失败也不 retry 轰炸：下个周期自然重推（全量小表，幂等）
    }

    companion object {
        const val UNIQUE_NAME = "mirror_push"
    }
}
