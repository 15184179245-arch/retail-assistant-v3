package com.retaildemo.v2

import com.retaildemo.v2.data.remote.ApiEnvelope
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.remote.AppVersionDto
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.util.AppUpdateManager
import com.retaildemo.v2.util.UpdateState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class AppUpdateManagerTest {

    @Test
    fun `服务端版本号大于当前版本号 触发 UpdateAvailable 内部推送`() = runTest {
        val fakeApi = mockk<retaildemoApi>()
        val fakeConfig = mockk<AppConfig>()
        every { fakeConfig.serverUrl } returns "http://127.0.0.1:18767"

        val newVersion = AppVersionDto(
            versionCode = 2,
            versionName = "2.1.0",
            downloadUrl = "/retaildemo-v2.apk",
            apkSize = 49978602L,
            updateLog = "1. 连续扫码\n2. 手写识别",
        )
        coEvery { fakeApi.getAppVersion() } returns Response.success(ApiEnvelope(success = true, data = newVersion))

        val manager = AppUpdateManager(fakeApi, OkHttpClient(), fakeConfig)
        manager.checkForUpdateSuspending(currentVersionCode = 1, isManual = false)

        val state = manager.updateState.value
        assertTrue("Expected UpdateAvailable but was $state", state is UpdateState.UpdateAvailable)
        val update = state as UpdateState.UpdateAvailable
        assertEquals(2, update.version.versionCode)
        assertEquals("2.1.0", update.version.versionName)
        assertEquals(49978602L, update.version.apkSize)
    }

    @Test
    fun `服务端版本号小于等于当前版本号 触发 UpToDate`() = runTest {
        val fakeApi = mockk<retaildemoApi>()
        val fakeConfig = mockk<AppConfig>()
        every { fakeConfig.serverUrl } returns "http://127.0.0.1:18767"

        val sameVersion = AppVersionDto(versionCode = 1, versionName = "2.0.0")
        coEvery { fakeApi.getAppVersion() } returns Response.success(ApiEnvelope(success = true, data = sameVersion))

        val manager = AppUpdateManager(fakeApi, OkHttpClient(), fakeConfig)
        manager.checkForUpdateSuspending(currentVersionCode = 1, isManual = true)

        val state = manager.updateState.value
        assertTrue("Expected UpToDate but was $state", state is UpdateState.UpToDate)
        assertTrue((state as UpdateState.UpToDate).isManual)
    }

    @Test
    fun `当前版本与服务端版本均为 2 时不触发 UpdateAvailable 判定为最新版本`() = runTest {
        val fakeApi = mockk<retaildemoApi>()
        val fakeConfig = mockk<AppConfig>()
        every { fakeConfig.serverUrl } returns "http://127.0.0.1:18767"

        val serverVersion = AppVersionDto(versionCode = 2, versionName = "2.1.0")
        coEvery { fakeApi.getAppVersion() } returns Response.success(ApiEnvelope(success = true, data = serverVersion))

        val manager = AppUpdateManager(fakeApi, OkHttpClient(), fakeConfig)
        manager.checkForUpdateSuspending(currentVersionCode = 2, isManual = false)

        val state = manager.updateState.value
        assertTrue("Expected UpToDate but was $state", state is UpdateState.UpToDate)
    }
}
