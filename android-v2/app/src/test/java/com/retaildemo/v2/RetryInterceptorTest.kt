package com.retaildemo.v2

import com.retaildemo.v2.data.remote.RetryInterceptor
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 排雷 M1（2026-09-09）：RetryInterceptor 只重试幂等请求（GET/HEAD）。
 * 旧实现连 POST 一起自动重发——pushBills/marks 同步/mirror/push 弱网下会重复入账。
 */
class RetryInterceptorTest {

    private fun canned(chain: Interceptor.Chain, code: Int): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body("".toResponseBody(null))
            .build()

    private fun client(next: Interceptor): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor())
            .addInterceptor(next)
            .build()

    private fun postRequest(): Request = Request.Builder()
        .url("http://sx-test.invalid/api/bills/push")
        .post("{}".toRequestBody(null))
        .build()

    private fun getRequest(): Request = Request.Builder()
        .url("http://sx-test.invalid/api/search?q=x")
        .build()

    @Test
    fun `POST收到5xx不重试——只调用一次并原样返回`() {
        val counter = AtomicInteger(0)
        val client = client(Interceptor { chain ->
            counter.incrementAndGet()
            canned(chain, 500)
        })
        val resp = client.newCall(postRequest()).execute()
        assertEquals(500, resp.code)
        assertEquals("非幂等 POST 不得重发", 1, counter.get())
    }

    @Test
    fun `POST网络异常立即抛出不再重发`() {
        val counter = AtomicInteger(0)
        val client = client(Interceptor {
            counter.incrementAndGet()
            throw IOException("offline")
        })
        try {
            client.newCall(postRequest()).execute()
            fail("应当抛出 IOException")
        } catch (e: IOException) {
            assertEquals(1, counter.get())
        }
    }

    @Test
    fun `GET收到5xx重试至成功`() {
        val counter = AtomicInteger(0)
        val client = client(Interceptor { chain ->
            val n = counter.incrementAndGet()
            canned(chain, if (n < 3) 500 else 200)
        })
        val resp = client.newCall(getRequest()).execute()
        assertEquals(200, resp.code)
        assertEquals(3, counter.get())
    }

    @Test
    fun `GET网络异常重试两次后成功`() {
        val counter = AtomicInteger(0)
        val client = client(Interceptor { chain ->
            if (counter.incrementAndGet() <= 2) throw IOException("flaky")
            canned(chain, 200)
        })
        val resp = client.newCall(getRequest()).execute()
        assertEquals(200, resp.code)
        assertEquals(3, counter.get())
    }

    @Test
    fun `GET三次全败仍抛出最后异常`() {
        val counter = AtomicInteger(0)
        val client = client(Interceptor {
            counter.incrementAndGet()
            throw IOException("down")
        })
        try {
            client.newCall(getRequest()).execute()
            fail("应当抛出 IOException")
        } catch (e: IOException) {
            assertEquals(3, counter.get())
        }
    }

    /**
     * 2026-09-19 店机闪退回归：真栈（含 RetryAndFollowUpInterceptor）下，
     * 5xx 后未 close 上一轮 response 就二次 proceed，OkHttp 抛
     * "cannot make a new request because the previous response is still open"
     * 直接崩进程。上面的假链测试接不住这条路径，必须走 MockWebServer。
     */
    @Test
    fun `真栈下GET重试先关闭上一轮response——5xx转200不抛IllegalState`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        server.start()
        try {
            val realClient = OkHttpClient.Builder()
                .addInterceptor(RetryInterceptor())
                .build()
            val resp = realClient.newCall(
                Request.Builder().url(server.url("/api/search?q=x")).build()
            ).execute()
            assertEquals(200, resp.code)
            assertEquals("ok", resp.body!!.string())
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `真栈下GET三次5xx耗尽仍抛IOException不崩进程`() {
        val server = MockWebServer()
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        server.start()
        try {
            val realClient = OkHttpClient.Builder()
                .addInterceptor(RetryInterceptor())
                .build()
            try {
                realClient.newCall(
                    Request.Builder().url(server.url("/api/search?q=x")).build()
                ).execute()
                fail("应当抛出 IOException")
            } catch (e: IOException) {
                assertEquals(3, server.requestCount)
            }
        } finally {
            server.shutdown()
        }
    }
}
