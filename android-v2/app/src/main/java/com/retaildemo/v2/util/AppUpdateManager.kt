package com.retaildemo.v2.util

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.retaildemo.v2.BuildConfig
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.remote.AppVersionDto
import com.retaildemo.v2.data.remote.retaildemoApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UpdateState {
    object Idle : UpdateState
    object Checking : UpdateState
    data class UpdateAvailable(val version: AppVersionDto, val isManual: Boolean = false) : UpdateState
    data class Downloading(val progress: Float, val currentBytes: Long, val totalBytes: Long) : UpdateState
    data class ReadyToInstall(val apkFile: File) : UpdateState
    data class UpToDate(val isManual: Boolean = false) : UpdateState
    data class Error(val message: String, val isManual: Boolean = false) : UpdateState
}

@Singleton
class AppUpdateManager @Inject constructor(
    private val api: retaildemoApi,
    private val okHttpClient: OkHttpClient,
    private val config: AppConfig,
) {
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * 挂起式检查服务端是否有新版本内部推送（便于测试与协程调度）。
     */
    suspend fun checkForUpdateSuspending(currentVersionCode: Int = BuildConfig.VERSION_CODE, isManual: Boolean = false) {
        if (_updateState.value is UpdateState.Downloading) return
        _updateState.value = UpdateState.Checking
        try {
            val resp = api.getAppVersion()
            if (resp.isSuccessful && resp.body()?.success == true) {
                val data = resp.body()?.data
                if (data != null && data.versionCode > currentVersionCode) {
                    _updateState.value = UpdateState.UpdateAvailable(data, isManual)
                } else {
                    _updateState.value = UpdateState.UpToDate(isManual)
                }
            } else {
                _updateState.value = UpdateState.Error(resp.body()?.error ?: "获取版本信息失败", isManual)
            }
        } catch (e: Exception) {
            _updateState.value = UpdateState.Error(e.message ?: "网络连接异常", isManual)
        }
    }

    /**
     * 检查服务端是否有新版本内部推送。
     * @param currentVersionCode 当前运行的 App 版本号，默认使用 BuildConfig.VERSION_CODE
     * @param isManual 是否由店员在设置页主动点击触发（若是且无更新，会提示已是最新版）
     */
    fun checkForUpdate(currentVersionCode: Int = BuildConfig.VERSION_CODE, isManual: Boolean = false) {
        scope.launch {
            checkForUpdateSuspending(currentVersionCode, isManual)
        }
    }

    fun dismissDialog() {
        _updateState.value = UpdateState.Idle
    }

    /**
     * 应用内下载并拉起系统安装器。
     */
    fun downloadAndInstall(context: Context, version: AppVersionDto) {
        if (_updateState.value is UpdateState.Downloading) return
        val rawUrl = version.downloadUrl
        val fullUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl
        } else {
            val base = config.serverUrl.trimEnd('/')
            val path = rawUrl.trimStart('/')
            "$base/$path"
        }

        scope.launch {
            try {
                _updateState.value = UpdateState.Downloading(0f, 0L, version.apkSize)

                val request = Request.Builder().url(fullUrl).build()
                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw IllegalStateException("下载失败: HTTP ${response.code}")
                }

                val body = response.body ?: throw IllegalStateException("下载内容为空")
                val totalLength = if (body.contentLength() > 0) body.contentLength() else version.apkSize

                val apkDir = File(context.cacheDir, "apk")
                if (!apkDir.exists()) apkDir.mkdirs()
                // 依版本号命名，彻底规避旧 APK 缓存重名导致系统安装器调旧包
                val targetFile = File(apkDir, "retaildemo-v2-v${version.versionCode}.apk")
                if (targetFile.exists()) targetFile.delete()

                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        var read: Int
                        var lastProgressReportTime = 0L

                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastProgressReportTime > 100 || downloaded == totalLength) {
                                lastProgressReportTime = now
                                val progress = if (totalLength > 0) downloaded.toFloat() / totalLength else 0f
                                _updateState.value = UpdateState.Downloading(progress.coerceIn(0f, 1f), downloaded, totalLength)
                            }
                        }
                        output.flush()
                    }
                }

                _updateState.value = UpdateState.ReadyToInstall(targetFile)

                withContext(Dispatchers.Main) {
                    installApk(context, targetFile)
                }
            } catch (e: Exception) {
                _updateState.value = UpdateState.Error("下载更新失败: ${e.message}", true)
            }
        }
    }

    /**
     * 唤起系统安装器
     */
    fun installApk(context: Context, apkFile: File) {
        try {
            val authority = "${context.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "唤起安装器失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
