package com.retaildemo.v2.data.remote

import com.retaildemo.v2.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** 双 header 鉴权：Bearer JWT（登录后）+ X-Auth-Token（静态网关令牌，可选）。 */
class AuthInterceptor(private val config: AppConfig) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
        config.jwtToken?.let { builder.header("Authorization", "Bearer $it") }
        val gatewayToken = config.gatewayToken ?: BuildConfig.GATEWAY_TOKEN
        if (gatewayToken.isNotBlank()) {
            builder.header("X-Auth-Token", gatewayToken)
        }
        return chain.proceed(builder.build())
    }
}

/**
 * 5xx/网络异常重试（最多 2 次重试，线性退避）。
 * 2026-09-09 排雷 M1：只重试幂等方法（GET/HEAD）。此前对 POST 也自动重发，
 * pushBills/marks 同步/mirror/push 等非幂等请求弱网下会重复入账；
 * 非幂等请求失败一律直接抛出，交由离线同步队列（needsSync）兜底重试。
 * 重试前必须先 close 上一轮 response（OkHttp 强制：应用拦截器二次 proceed
 * 携带未关闭的 response 会抛 IllegalStateException 崩进程）；5xx 不透传，
 * 耗尽后统一转成 IOException 走既有失败路径。
 */
class RetryInterceptor : Interceptor {
    private fun retryable(method: String): Boolean = method == "GET" || method == "HEAD"

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!retryable(chain.request().method)) return chain.proceed(chain.request())
        var lastException: java.io.IOException? = null
        for (attempt in 0..2) {
            try {
                val resp = chain.proceed(chain.request())
                if (resp.code < 500) return resp
                resp.close()
                lastException = java.io.IOException("HTTP ${resp.code}")
            } catch (e: java.io.IOException) {
                lastException = e
            }
            if (attempt < 2) {
                try {
                    Thread.sleep(1000L * (attempt + 1))
                } catch (_: InterruptedException) {
                }
            }
        }
        throw lastException ?: java.io.IOException("请求失败")
    }
}

/**
 * 请求级 URL 重写：每次请求动态读 [AppConfig.serverUrl]，
 * 让「设置 → 保存服务器地址」即时生效（Retrofit baseUrl 在构建时固定，改地址只写配置不重建）。
 */
class ServerUrlInterceptor(private val config: AppConfig) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        val base = config.serverUrl.toHttpUrlOrNull()
        if (base != null &&
            (url.scheme != base.scheme || url.host != base.host || url.port != base.port)
        ) {
            val target = base.newBuilder()
                .encodedPath(url.encodedPath)
                .encodedQuery(url.encodedQuery)
                .build()
            return chain.proceed(chain.request().newBuilder().url(target).build())
        }
        return chain.proceed(chain.request())
    }
}

/**
 * 信任策略随 [AppConfig.trustAllTls] 实时切换（每次 TLS 握手读取，开关无需重启）。
 * 关 = 系统默认校验（与原来完全一致）；开 = 信任全部证书（仅限自建自签隧道的公网场景）。
 */
class DynamicTrustManager(private val config: AppConfig) : X509TrustManager {
    private val system: X509TrustManager = run {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        if (!config.trustAllTls) system.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        if (!config.trustAllTls) system.checkServerTrusted(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
}

/** 用 [DynamicTrustManager] 构建 TLS 上下文；OkHttp 配合 sslSocketFactory + trustManager 使用。 */
fun buildDynamicTls(config: AppConfig): Pair<DynamicTrustManager, SSLContext> {
    val trustManager = DynamicTrustManager(config)
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, arrayOf(trustManager), SecureRandom())
    return trustManager to sslContext
}

/** 401 自愈：过期 JWT 被拒时清掉本地登录态，下次请求自动降级到网关令牌/私服模式。 */
class UnauthorizedCleanupInterceptor(private val config: AppConfig) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code == 401 && config.jwtToken != null) {
            config.jwtToken = null
        }
        return response
    }
}

/**
 * H2 运行日志：网络层失败落 APP 日志——5xx/IO 异常 ERROR（即时触发上传）、4xx WARN，
 * 服务器实时看到「手机当时哪个请求失败」。跳过 /api/logs/ 自身，防「上报失败→记日志→再上报」回环。
 * 只记方法/路径/状态码/异常类名，不记请求体响应体（密钥与业务数据不进日志）。
 */
class HttpLogInterceptor(private val logger: com.retaildemo.v2.data.repo.AppLogger) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.encodedPath.contains("/api/logs/")) return chain.proceed(request)
        val line = "${request.method} ${request.url.encodedPath}"
        return try {
            val response = chain.proceed(request)
            if (response.code >= 400) {
                val level = if (response.code >= 500) {
                    com.retaildemo.v2.data.repo.LogPipeline.LEVEL_ERROR
                } else {
                    com.retaildemo.v2.data.repo.LogPipeline.LEVEL_WARN
                }
                logger.log(level, "Http", "$line → HTTP ${response.code}")
            }
            response
        } catch (e: java.io.IOException) {
            logger.log(
                com.retaildemo.v2.data.repo.LogPipeline.LEVEL_ERROR,
                "Http",
                "$line 网络异常 ${e.javaClass.simpleName}: ${e.message.orEmpty()}",
            )
            throw e
        }
    }
}