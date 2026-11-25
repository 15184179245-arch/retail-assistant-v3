package com.retaildemo.v2.data.repo

import android.content.Context
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.remote.DeviceCheckinRequest
import com.retaildemo.v2.data.remote.retaildemoApi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 2026-09-11 老板令「给设备发身份，服务器知道谁在线」：
 * 设备以安装维度稳定 device_id（AppConfig.deviceId）向服务器报到，登记/刷新
 * 「哪台设备绑哪个店员」+ 在线心跳。账单归属仍走 M3 的 owner_id 一等字段，
 * 本登记只做设备台账与在线感知，不改账单数据。
 *
 * 调用时机：APP 启动、首启绑店员/设置页改绑（SettingsViewModel.saveStaffIdentity）、
 * DeviceCheckinWorker 15 分钟周期兜底。失败静默（离线优先），绝不打断业务流。
 */
@Singleton
class DeviceRegistry @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val api: retaildemoApi,
    private val config: AppConfig,
) {
    suspend fun checkin(): Boolean = runCatching {
        val resp = api.deviceCheckin(
            DeviceCheckinRequest(
                device_id = config.deviceId,
                staff_id = config.staffId,
                staff_name = config.staffName,
                model = deviceModel(),
                app_version = appVersion(),
            ),
        )
        resp.isSuccessful && resp.body()?.success == true
    }.getOrDefault(false)

    private fun deviceModel(): String =
        "${android.os.Build.MANUFACTURER.orEmpty()} ${android.os.Build.MODEL.orEmpty()}".trim()

    private fun appVersion(): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull().orEmpty()
}
