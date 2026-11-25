package com.retaildemo.v2.util

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.retaildemo.v2.BuildConfig
import com.retaildemo.v2.data.remote.AppConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.math.sqrt

/**
 * I17 语音输入引擎（2026-09-10 老板定向「联网实时」）：
 * 录音 16k PCM16 → 客户端 RMS 静默断句(1.2s)/上限 12s → 包 WAV →
 * POST 网关 /api/asr/recognize → 服务端 MiMo 云端识别（sherpa 本地兜底，对端透明）。
 * 网关/服务不可达即报错，由浮层回退系统 SpeechRecognizer。
 * 线程：录音+上传都在单工作线程；回调统一切主线程。零新依赖（HttpURLConnection）。
 *
 * 2026-09-10 修复「说话没反应」（老板实测）：
 * ① 隧道是自签 HTTPS，裸 HttpURLConnection 不认证书 → TLS 必败 → 每次报「连不上」。
 *   现 trustAllTls 开启时对 https 请求信任全部证书（与 OkHttp 主链路 DynamicTrustManager 同口径）；
 * ② 令牌取 prefs 后回退 BuildConfig.GATEWAY_TOKEN（与 AuthInterceptor 同口径，防 401）；
 * ③ VAD 阈值改为开场 500ms 环境噪声自适应（下限 350），原固定 1000 对轻声说话全漏；
 * ④ 开口前 800ms 预录缓冲回放，防止说话首字被截；
 * ⑤ onLevel 电平回调（0..100），UI 呼吸随声音起伏，用户能看到「在听」。
 */
class ServerAsr(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHUNK = 1024              // 64ms
        private const val SILENCE_END_MS = 1200     // 静默断句
        private const val MAX_MS = 12_000
        private const val CALIBRATE_MS = 500        // 开场采环境噪声
        private const val MIN_VAD_RMS = 350         // 噪声再低阈值也不低于此（防环境常触发）
        private const val PRE_ROLL_MS = 800         // 开口前预录，防丢首字
        private const val PRE_ROLL_CHUNKS =
            (SAMPLE_RATE * PRE_ROLL_MS / 1000 + CHUNK - 1) / CHUNK

        /** VAD 判定阈值：随环境噪声自适应（噪声底的 3 倍，下限 MIN_VAD_RMS；纯函数便于单测）。 */
        fun vadThreshold(noiseFloor: Double): Int =
            maxOf(MIN_VAD_RMS, (noiseFloor * 3).toInt())

        /** RMS → 0..100 相对电平（阈值处=50，供 UI 呼吸反馈；纯函数）。 */
        fun levelPercent(rms: Double, threshold: Int): Int =
            ((rms / threshold) * 50).toInt().coerceIn(0, 100)

        /** 裸 PCM16 包 44 字节 WAV 头（与服务端 wrap_pcm_to_wav 同格式；纯函数便于单测）。 */
        fun wrapWav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
            val header = java.nio.ByteBuffer.allocate(44)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
                .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(pcm.size)
            return header.array() + pcm
        }

        /** PCM16 → 小端字节（纯函数便于单测）。 */
        fun pcmToBytes(src: ShortArray, n: Int = src.size): ByteArray {
            val out = ByteArray(n * 2)
            for (i in 0 until n) {
                out[i * 2] = (src[i].toInt() and 0xFF).toByte()
                out[i * 2 + 1] = (src[i].toInt() shr 8).toByte()
            }
            return out
        }

        /**
         * 自签隧道信任上下文（仅 trustAllTls 开启时用于 https 请求，与 OkHttp 口径一致）。
         * 2026-09-10 响应慢优化：companion 单例缓存——每次新建 SSLContext 会禁用 JVM 的
         * TLS 会话与 TCP 连接复用，每次识别都全量握手（公网隧道下多吃近 1 秒）。
         */
        private val trustAllFactory:javax.net.ssl.SSLSocketFactory by lazy {
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(object : X509TrustManager {
                    override fun checkClientTrusted(
                        chain: Array<out X509Certificate>?, authType: String?,
                    ) {}

                    override fun checkServerTrusted(
                        chain: Array<out X509Certificate>?, authType: String?,
                    ) {}

                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }), SecureRandom())
            }.socketFactory
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var holdMode = false        // 长按模式：松手断句，不做 VAD 静默断句
    @Volatile private var releaseRequested = false

    fun start(
        onState: (String) -> Unit,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
        onLevel: (Int) -> Unit = {},
    ) {
        if (running) stop()
        holdMode = false
        releaseRequested = false
        running = true
        worker = Thread {
            post(onState, "listening")
            recordUtterance(onFinal, onError, onLevel)
        }.apply { start() }
    }

    /**
     * 长按模式（2026-09-10 老板令「随动长按」）：按住录音、松手立即断句上传。
     * 按住期间不做 VAD 静默断句（按住中途停顿不算说完），仅 12s 上限兜底。
     * 松手时 [release] 触发断句；若没听到有效说话则报「太短」。
     */
    fun startHold(
        onState: (String) -> Unit,
        onLevel: (Int) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (running) stop()
        holdMode = true
        releaseRequested = false
        running = true
        worker = Thread {
            post(onState, "listening")
            recordUtterance(onFinal, onError, onLevel, hold = true)
        }.apply { start() }
    }

    /** 松手：请求立即断句上传（仅长按模式生效；VAD 模式忽略）。 */
    fun release() {
        releaseRequested = true
    }

    /**
     * 连接预热（2026-09-10 响应慢优化）：按住按钮的瞬间就异步建好到网关的 TCP+TLS，
     * 松手上传时连接已在 JVM keep-alive 池里（与 recognize 用同一 trustAllFactory 才能复用），
     * 公网隧道下省一次全量握手。失败静默——预热只是优化，识别路径会自己重连。
     */
    fun warmUp() {
        Thread {
            try {
                val config = AppConfig(context)
                val conn = URL("${config.serverUrl.trimEnd('/')}/api/asr/health")
                    .openConnection() as HttpURLConnection
                if (conn is HttpsURLConnection && config.trustAllTls) {
                    conn.sslSocketFactory = trustAllFactory
                    conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                }
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                try {
                    conn.inputStream.use { it.readBytes() } // 消费完响应体才会进 keep-alive 池
                } finally {
                    conn.disconnect()
                }
            } catch (_: Throwable) {
            }
        }.start()
    }

    /** 调试验证通道：直接把 wav 文件 POST 给服务端（模拟器无麦克风 / F3 排障）。 */
    fun startFromFile(
        wavPath: String,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (running) stop()
        running = true
        worker = Thread {
            try {
                val wav = java.io.File(wavPath).readBytes()
                val text = recognize(wav, onError) ?: return@Thread
                post(onFinal, text)
            } catch (t: Throwable) {
                post(onError, t.message ?: "读测试音频失败")
            }
        }.apply { start() }
    }

    fun stop() {
        running = false
        worker = null
    }

    private fun post(cb: (String) -> Unit, msg: String) = main.post { cb(msg) }

    @SuppressLint("MissingPermission") // 权限由调用方运行时请求；此处防御性 try
    private fun recordUtterance(
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
        onLevel: (Int) -> Unit,
        hold: Boolean = false,
    ) {
        var record: AudioRecord? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
            record.startRecording()
            val buf = ShortArray(CHUNK)
            val pcm = ByteArrayOutputStream()
            val preRoll = ArrayDeque<ShortArray>()
            var speaking = false
            var speechMs = 0
            var silenceMs = 0
            var totalMs = 0
            var calSum = 0.0
            var calChunks = 0
            var threshold = MIN_VAD_RMS
            while (running) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) continue
                val chunkMs = (n * 1000L / SAMPLE_RATE).toInt()
                totalMs += chunkMs
                var sum = 0.0
                for (i in 0 until n) sum += buf[i] * buf[i]
                val rms = sqrt(sum / n)

                if (totalMs <= CALIBRATE_MS) { // 开场采环境噪声定阈值；同时滚预录缓冲
                    calSum += rms
                    calChunks++
                    if (totalMs + chunkMs > CALIBRATE_MS) {
                        threshold = vadThreshold(if (calChunks > 0) calSum / calChunks else 0.0)
                    }
                    preRoll.addLast(buf.copyOf(n))
                    if (preRoll.size > PRE_ROLL_CHUNKS) preRoll.removeFirst()
                    continue
                }
                main.post { onLevel(levelPercent(rms, threshold)) }

                if (!speaking && rms > threshold) {
                    speaking = true
                    for (c in preRoll) pcm.write(pcmToBytes(c)) // 回放开口前预录，防丢首字
                    preRoll.clear()
                    speechMs = 0
                    silenceMs = 0
                }
                if (speaking) {
                    pcm.write(pcmToBytes(buf, n))
                    speechMs += chunkMs
                    if (rms > threshold) silenceMs = 0 else silenceMs += chunkMs
                } else {
                    preRoll.addLast(buf.copyOf(n))
                    if (preRoll.size > PRE_ROLL_CHUNKS) preRoll.removeFirst()
                }
                // 长按模式：松手（releaseRequested）即断；VAD 模式：说够 300ms 后静默 1.2s 断；12s 上限兜底
                val endpoint = if (hold) {
                    releaseRequested || totalMs >= MAX_MS
                } else {
                    speaking && speechMs > 300 &&
                        (silenceMs >= SILENCE_END_MS || totalMs >= MAX_MS)
                }
                if (endpoint) {
                    if (hold && (!speaking || speechMs <= 300)) { // 松手时没听到有效说话
                        stop()
                        post(onError, "说话时间太短，请按住多说几个字")
                        return
                    }
                    val wav = wrapWav(pcm.toByteArray())
                    stop()
                    val text = recognize(wav, onError) ?: return // 具体错误已由 recognize 上报
                    post(onFinal, text)
                    return
                }
                if (totalMs >= 8000 && !speaking && !hold) { // 8 秒没开口（长按模式由松手收尾）
                    stop()
                    post(onError, "没有听清，请靠近一点再说")
                    return
                }
            }
            if (running) post(onError, "已停止")
            record.stop()
        } catch (t: Throwable) {
            if (running) post(onError, t.message ?: "录音失败")
        } finally {
            try { record?.release() } catch (_: Throwable) {}
        }
    }

    /** POST 网关 /api/asr/recognize；失败经 onError 上报具体原因（连不上≠没听清），返回 null。 */
    private fun recognize(wav: ByteArray, onError: (String) -> Unit): String? {
        val config = AppConfig(context)
        val base = config.serverUrl.trimEnd('/')
        val conn = URL("$base/api/asr/recognize?rate=$SAMPLE_RATE&lang=zh")
            .openConnection() as HttpURLConnection
        return try {
            if (conn is HttpsURLConnection && config.trustAllTls) {
                conn.sslSocketFactory = trustAllFactory
                conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }
            conn.requestMethod = "POST"
            conn.connectTimeout = 4000
            conn.readTimeout = 40_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "audio/wav")
            val token = config.gatewayToken?.takeIf { it.isNotBlank() }
                ?: BuildConfig.GATEWAY_TOKEN.takeIf { it.isNotBlank() }
            token?.let { conn.setRequestProperty("X-Auth-Token", it) }
            conn.setFixedLengthStreamingMode(wav.size)
            conn.outputStream.use { it.write(wav) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                post(onError, "服务器识别失败($code)，请稍后再试")
                return null
            }
            val json = JSONObject(body)
            if (!json.optBoolean("success", false)) {
                val data = json.optJSONObject("data")
                val hint = json.optString("hint").takeIf { it.isNotBlank() }
                    ?: json.optString("user_message").takeIf { it.isNotBlank() }
                    ?: data?.optString("text")
                post(onError, hint ?: "没有听清，再说一次？")
                return null
            }
            json.optJSONObject("data")?.optString("text")?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            android.util.Log.w("ServerAsr", "recognize fail", t)
            post(onError, "连不上服务器，请确认手机和店内网络")
            null
        } finally {
            conn.disconnect()
        }
    }
}
