package com.retaildemo.v2.util

import android.content.Context
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.remote.CrashReportRequest
import com.retaildemo.v2.data.remote.retaildemoApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 崩溃队列存储（纯 JVM 可单测）：JSONL 追加 + 容量护栏（超 256KB 砍最老一半），
 * 上报成功即清除。「落盘先行、启动补传」——崩溃瞬间进程随时会死，绝不在现场做网络 IO。
 */
object CrashQueue {
    fun file(base: File): File = File(base, "crash_reports.jsonl")
    private const val MAX_BYTES = 256 * 1024

    fun append(base: File, jsonLine: String) {
        runCatching {
            base.mkdirs()
            val f = file(base)
            f.appendText(jsonLine + "\n")
            if (f.length() > MAX_BYTES) {
                val lines = f.readLines().filter { it.isNotBlank() }
                f.writeText(lines.takeLast(lines.size / 2).joinToString("\n") + "\n")
            }
        }
    }

    fun pending(base: File): List<String> = runCatching {
        val f = file(base)
        if (!f.exists()) emptyList() else f.readLines().filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    /** 只保留未送成功的行；全部成功则删文件。 */
    fun keepOnly(base: File, remaining: List<String>) {
        runCatching {
            val f = file(base)
            if (remaining.isEmpty()) f.delete()
            else f.writeText(remaining.joinToString("\n") + "\n")
        }
    }
}

/**
 * 2026-09-11 老板令：崩溃收集上报——新设备若再闪退，服务器远程就能收回堆栈定位原因。
 * 链路：崩溃现场只落盘（保留旧 crash.log 行为）→ 下次启动 uploadPending() 补传网关
 * → 服务端 logs/app-crashes.jsonl 封存（GET /api/crash/reports 可远程查看）。
 */
@Singleton
class CrashReporter @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val api: retaildemoApi,
    private val config: AppConfig,
) {
    private val base: File get() = ctx.filesDir

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 两条持久化都自兜底，绝不在崩溃处理里二次抛出
            runCatching { writeLegacyCrashLog(thread, throwable) }
            runCatching { CrashQueue.append(base, queueLine(thread, throwable)) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 启动补传：逐条上报，成功的清除，失败的留队下次再试。返回成功条数。 */
    suspend fun uploadPending(): Int = withContext(Dispatchers.IO) {
        val all = CrashQueue.pending(base)
        if (all.isEmpty()) return@withContext 0
        val remaining = mutableListOf<String>()
        var sent = 0
        for (line in all) {
            val ok = runCatching {
                val payload = com.google.gson.Gson().fromJson(line, CrashReportRequest::class.java)
                api.crashReport(payload).isSuccessful
            }.getOrDefault(false)
            if (ok) sent++ else remaining.add(line)
        }
        CrashQueue.keepOnly(base, remaining)
        sent
    }

    private fun queueLine(thread: Thread, throwable: Throwable): String {
        val sw = java.io.StringWriter()
        throwable.printStackTrace(java.io.PrintWriter(sw))
        val payload = CrashReportRequest(
            app_version = runCatching {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            }.getOrNull().orEmpty(),
            device_model = deviceModel(),
            android = androidVersion(),
            thread = thread.name,
            crash = sw.toString(),
        )
        return com.google.gson.Gson().toJson(payload)
    }

    private fun deviceModel(): String =
        "${android.os.Build.MANUFACTURER.orEmpty()} ${android.os.Build.MODEL.orEmpty()}".trim()

    private fun androidVersion(): String =
        "Android ${android.os.Build.VERSION.RELEASE.orEmpty()} (SDK ${android.os.Build.VERSION.SDK_INT})"

    /** 旧 crash.log（filesDir/crash.log，单文件覆盖式）保留原格式：现场拉线排障仍可用。 */
    private fun writeLegacyCrashLog(thread: Thread, throwable: Throwable) {
        val crashFile = File(ctx.filesDir, "crash.log")
        val sw = java.io.StringWriter()
        val pw = java.io.PrintWriter(sw)
        pw.println("=== retaildemo Hardware Assistant Crash Report ===")
        pw.println("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())}")
        pw.println("Device: ${deviceModel()} (${android.os.Build.PRODUCT})")
        pw.println("Android: ${androidVersion()}")
        pw.println("App: ${config.staffName} @ ${config.serverUrl}")
        pw.println("Thread: ${thread.name} (id: ${thread.id})")
        pw.println("Exception: ${throwable.javaClass.name}: ${throwable.message}")
        throwable.printStackTrace(pw)
        pw.flush()
        crashFile.writeText(sw.toString())
        android.util.Log.e("retaildemoApp", "Crash intercepted:\n$sw")
    }
}
