package com.retaildemo.v2.data.remote

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * L10：老王流式对话客户端（POST /api/agent/stream，SSE）。
 * 复用 DI 的 OkHttpClient（双 header 鉴权 + 动态服务器地址 + TLS 信任开关随设置即时生效），
 * 仅把 readTimeout 放开为 0——流式回答没有固定读间隔，连接/写超时保留。
 * 回调都在 OkHttp 线程触发；VM 侧用线程安全状态承接。
 */
@Singleton
class AgentStreamClient @Inject constructor(
    private val base: OkHttpClient,
    private val config: AppConfig,
) {
    private val gson = Gson()

    interface Listener {
        fun onEvent(evt: AgentStreamEvent)
        fun onClosed()
        fun onFailed(t: Throwable)
    }

    class StreamHandle internal constructor(private val es: EventSource) {
        private val cancelled = AtomicBoolean(false)

        fun cancel() {
            cancelled.set(true)
            es.cancel()
        }

        val isCancelled: Boolean get() = cancelled.get()
    }

    fun stream(request: AgentRunRequest, listener: Listener): StreamHandle {
        val url = config.serverUrl.trimEnd('/') + "/api/agent/stream"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .post(
                gson.toJson(request)
                    .toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
            .build()
        val client = base.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val es = EventSources.createFactory(client)
            .newEventSource(
                req,
                object : EventSourceListener() {
                    override fun onEvent(
                        eventSource: EventSource,
                        id: String?,
                        type: String?,
                        data: String,
                    ) {
                        val evt = runCatching {
                            gson.fromJson(data, AgentStreamEvent::class.java)
                        }.getOrNull()
                        if (evt != null && evt.type.isNotBlank()) listener.onEvent(evt)
                    }

                    override fun onClosed(eventSource: EventSource) {
                        listener.onClosed()
                    }

                    override fun onFailure(
                        eventSource: EventSource,
                        t: Throwable?,
                        response: Response?,
                    ) {
                        listener.onFailed(t ?: IOException("stream failed: ${response?.code ?: "-"}"))
                    }
                },
            )
        return StreamHandle(es)
    }
}
