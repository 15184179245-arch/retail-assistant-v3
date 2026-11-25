package com.retaildemo.v2

import com.retaildemo.v2.data.remote.AgentRunRequest
import com.retaildemo.v2.data.remote.AgentStreamClient
import com.retaildemo.v2.data.remote.AppConfig
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** L10：SSE 客户端对 MockWebServer 的真解析（帧切分/Gson 反序列化/失败回调）。 */
class AgentStreamClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(): AgentStreamClient {
        val cfg = mockk<AppConfig>()
        every { cfg.serverUrl } returns server.url("/").toString().trimEnd('/')
        return AgentStreamClient(OkHttpClient(), cfg)
    }

    @Test
    fun `sse帧解析_delta顺序到达_done收尾后closed`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"type\":\"delta\",\"text\":\"你\"}\n\n" +
                        "data: {\"type\":\"delta\",\"text\":\"好\"}\n\n" +
                        "data: {\"type\":\"done\",\"data\":{\"session_id\":\"s1\",\"answer\":\"\",\"degraded\":false," +
                        "\"pending_actions\":[{\"action_id\":\"ACT-1\",\"op\":\"checkout\",\"summary\":\"出库\",\"args\":{}}]}}\n\n",
                ),
        )
        val events = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(1)
        val closed = CountDownLatch(1)
        client().stream(AgentRunRequest(message = "在吗"), object : AgentStreamClient.Listener {
            override fun onEvent(evt: com.retaildemo.v2.data.remote.AgentStreamEvent) {
                events.add(evt.type)
                if (evt.type == "delta") events.add(evt.text ?: "")
                if (evt.type == "done") {
                    events.add(evt.data?.pendingActions?.firstOrNull()?.actionId ?: "")
                    done.countDown()
                }
            }

            override fun onClosed() {
                closed.countDown()
                done.countDown()
            }

            override fun onFailed(t: Throwable) {
                events.add("FAILED:" + t.message)
                done.countDown()
            }
        })
        // 30s：本机常有多班 gradle/6 服务/hermes 并行，10s 在 CPU 打满时会偶发饿死 localhost 回调
        assertTrue("events=" + events, done.await(30, TimeUnit.SECONDS))
        // onClosed 可能晚 onEvent(done) 一拍：用独立锁存器等它（CountDownLatch 自带 happens-before）
        assertTrue("onClosed 未回调", closed.await(30, TimeUnit.SECONDS))
        assertEquals(listOf("delta", "你", "delta", "好", "done", "ACT-1"), events)
    }

    @Test
    fun `服务端500_走onFailed可重试路径`() {
        server.enqueue(MockResponse().setResponseCode(500))
        val failed = CountDownLatch(1)
        val errors = CopyOnWriteArrayList<Throwable>()
        client().stream(AgentRunRequest(message = "在吗"), object : AgentStreamClient.Listener {
            override fun onEvent(evt: com.retaildemo.v2.data.remote.AgentStreamEvent) = Unit
            override fun onClosed() = Unit
            override fun onFailed(t: Throwable) {
                errors.add(t)
                failed.countDown()
            }
        })
        assertTrue(failed.await(30, TimeUnit.SECONDS))
        assertTrue(errors.isNotEmpty())
    }
}
