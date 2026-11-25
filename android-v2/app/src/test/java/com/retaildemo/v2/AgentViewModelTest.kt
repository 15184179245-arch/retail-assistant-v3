package com.retaildemo.v2

import com.retaildemo.v2.data.remote.AgentHealthData
import com.retaildemo.v2.data.remote.AgentImagePath
import com.retaildemo.v2.data.remote.AgentRunData
import com.retaildemo.v2.data.remote.AgentRunRequest
import com.retaildemo.v2.data.remote.AgentStreamClient
import com.retaildemo.v2.data.remote.AgentStreamEvent
import com.retaildemo.v2.data.remote.ApiEnvelope
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.ui.viewmodel.AgentViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * L10 老王面板 VM（流式版）：SSE 事件驱动气泡状态机；
 * 逐字上屏 / done 收尾 / error 失败 / 停止保留半截 / 重试原样重发 / 新对话清屏重置。
 * AgentStreamClient 用 mockk 假件，listener 用 slot 捕获后在测试线程驱动事件。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun apiStub(): retaildemoApi = mockk {
        coEvery { agentHealth() } returns retrofit2.Response.success(
            ApiEnvelope(data = AgentHealthData(hermes = true)),
        )
    }

    private class FakeStream {
        val listeners = mutableListOf<AgentStreamClient.Listener>()
        val requests = mutableListOf<AgentRunRequest>()
        val client = mockk<AgentStreamClient> {
            every { stream(any(), any()) } answers {
                requests.add(firstArg())
                listeners.add(secondArg())
                mockk(relaxed = true)
            }
        }
    }

    @Test
    fun `流式发送_delta逐字拼接_done收尾且会话id回写`() = runTest {
        val api = apiStub()
        val fake = FakeStream()
        val cfg = mockk<AppConfig>(relaxed = true)
        // 会话 id 动态桩：setter 真实生效，才能验证 done 帧回写闭环（静态桩读回恒为旧值）
        val sessHolder = arrayOf("sess-1")
        every { cfg.agentSessionId } answers { sessHolder[0] }
        every { cfg.agentSessionId = any() } answers { sessHolder[0] = firstArg(); Unit }

        val vm = AgentViewModel(api, cfg, fake.client, mockk(relaxed = true))
        vm.send("你是谁")
        assertTrue(vm.messages.value[1].streaming)
        assertTrue(vm.busy.value)
        fake.listeners[0].onEvent(AgentStreamEvent(type = "delta", text = "我是"))
        fake.listeners[0].onEvent(AgentStreamEvent(type = "delta", text = "老王"))
        fake.listeners[0].onEvent(
            AgentStreamEvent(type = "done", data = AgentRunData(sessionId = "sess-2", degraded = false)),
        )

        assertEquals(listOf("user", "lao"), vm.messages.value.map { it.role })
        assertEquals("我是老王", vm.messages.value[1].text)
        assertFalse(vm.messages.value[1].streaming)
        assertFalse(vm.messages.value[1].degraded)
        assertFalse(vm.busy.value)
        verify { cfg.agentSessionId = "sess-2" } // done 帧触发会话 id 回写
        assertEquals("sess-1", fake.requests[0].sessionId) // 发生在回写前，带的还是旧会话
        assertEquals("sess-2", vm.sessionId) // 动态桩证实回写真实生效
    }

    @Test
    fun `无delta的done_全文兜底落地`() = runTest {
        val fake = FakeStream()
        val vm = AgentViewModel(apiStub(), mockk(relaxed = true), fake.client, mockk(relaxed = true))
        vm.send("查价格")
        fake.listeners[0].onEvent(
            AgentStreamEvent(
                type = "done",
                data = AgentRunData(sessionId = "s", answer = "全文回答", degraded = true),
            ),
        )
        assertEquals("全文回答", vm.messages.value[1].text)
        assertTrue(vm.messages.value[1].degraded)
        assertFalse(vm.busy.value)
    }

    @Test
    fun `tool事件_状态指示更新且收尾清空`() = runTest {
        val fake = FakeStream()
        val vm = AgentViewModel(apiStub(), mockk(relaxed = true), fake.client, mockk(relaxed = true))
        vm.send("库存健康吗")
        fake.listeners[0].onEvent(AgentStreamEvent(type = "tool", name = "sx_search_low_stock"))
        assertEquals("正在查库存…", vm.toolStatus.value) // friendlyToolStatus 映射后的店员话术，非原始名
        assertTrue(vm.busy.value) // 动手阶段不算结束
        fake.listeners[0].onEvent(
            AgentStreamEvent(type = "done", data = AgentRunData(sessionId = "s", degraded = false)),
        )
        assertEquals(null, vm.toolStatus.value)
        assertFalse(vm.busy.value)
    }

    @Test
    fun `error事件_失败气泡且可重试`() = runTest {
        val fake = FakeStream()
        val vm = AgentViewModel(apiStub(), mockk(relaxed = true), fake.client, mockk(relaxed = true))
        vm.send("查价格")
        fake.listeners[0].onEvent(AgentStreamEvent(type = "error", userMessage = "老王还在上一问里"))
        assertEquals("老王还在上一问里", vm.messages.value[1].text)
        assertTrue(vm.messages.value[1].failed)
        assertFalse(vm.busy.value)

        vm.reAskLast()
        assertEquals(2, fake.requests.size)
        assertEquals(2, fake.listeners.size)
        fake.listeners[1].onEvent(
            AgentStreamEvent(type = "done", data = AgentRunData(sessionId = "s", answer = "好了", degraded = false)),
        )
        assertEquals(2, vm.messages.value.size) // 不重复加用户气泡
        assertEquals("好了", vm.messages.value[1].text)
    }

    @Test
    fun `连接失败_连不上提示气泡`() = runTest {
        val fake = FakeStream()
        val vm = AgentViewModel(apiStub(), mockk(relaxed = true), fake.client, mockk(relaxed = true))
        vm.send("查价格")
        fake.listeners[0].onFailed(java.io.IOException("stream failed: 500"))
        assertTrue(vm.messages.value[1].failed)
        assertTrue(vm.messages.value[1].text.contains("连不上老王"))
    }

    @Test
    fun `停止_保留半截回答并解除busy`() = runTest {
        val fake = FakeStream()
        val vm = AgentViewModel(apiStub(), mockk(relaxed = true), fake.client, mockk(relaxed = true))
        vm.send("讲讲货架")
        fake.listeners[0].onEvent(AgentStreamEvent(type = "delta", text = "五级货架"))
        vm.stop()
        assertEquals("五级货架", vm.messages.value[1].text)
        assertFalse(vm.messages.value[1].streaming)
        assertFalse(vm.busy.value)
    }

    @Test
    fun `新对话_清屏重置会话并带reset请求`() = runTest {
        val fake = FakeStream()
        val cfg = mockk<AppConfig>(relaxed = true)
        every { cfg.agentSessionId } returns "old-sess"
        val vm = AgentViewModel(apiStub(), cfg, fake.client, mockk(relaxed = true))
        vm.send("第一问")
        vm.newChat()

        assertTrue(vm.messages.value.isEmpty() || vm.messages.value.all { it.role == "system" })
        verify { cfg.agentSessionId = any() }
        val resetReq = fake.requests.last()
        assertTrue(resetReq.reset)
        assertFalse(fake.requests.last().sessionId.isBlank())
    }

    @Test
    fun `附件上传成功_路径挂起待发`() = runTest {
        val api = apiStub()
        coEvery { api.agentImage(any()) } returns retrofit2.Response.success(
            ApiEnvelope(data = AgentImagePath(path = "C:/data/tmp/a.jpg")),
        )
        val ctx = mockk<android.content.Context>()
        val resolver = mockk<android.content.ContentResolver>()
        every { ctx.contentResolver } returns resolver
        every { resolver.openInputStream(any()) } returns java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3))
        val cfg = mockk<AppConfig>(relaxed = true)
        every { cfg.agentSessionId } returns "s9"
        val vm = AgentViewModel(api, cfg, FakeStream().client, ctx)
        vm.attachImage(mockk(relaxed = true))
        advanceUntilIdle()
        assertEquals("C:/data/tmp/a.jpg", vm.attachedPath.value)
        assertEquals(false, vm.attaching.value)
    }

    @Test
    fun `附件上传失败_系统提示且不挂路径`() = runTest {
        val api = apiStub()
        coEvery { api.agentImage(any()) } throws java.io.IOException("net")
        val ctx = mockk<android.content.Context>()
        val resolver = mockk<android.content.ContentResolver>()
        every { ctx.contentResolver } returns resolver
        every { resolver.openInputStream(any()) } returns java.io.ByteArrayInputStream(byteArrayOf(9))
        val cfg = mockk<AppConfig>(relaxed = true)
        every { cfg.agentSessionId } returns "s10"
        val vm = AgentViewModel(api, cfg, FakeStream().client, ctx)
        vm.attachImage(mockk(relaxed = true))
        advanceUntilIdle()

        assertEquals(null, vm.attachedPath.value)
        assertTrue(vm.messages.value.last().text.contains("图片上传失败"))
    }

    @Test
    fun `确认取消_追加系统回执`() = runTest {
        val api = apiStub()
        coEvery { api.agentConfirm(any()) } returns retrofit2.Response.success(
            ApiEnvelope(success = true, data = com.retaildemo.v2.data.remote.AgentConfirmData(actionId = "ACT-1", state = "cancelled")),
        )
        val vm = AgentViewModel(api, mockk(relaxed = true), FakeStream().client, mockk(relaxed = true))
        vm.confirm("ACT-1", ok = false)
        advanceUntilIdle()

        assertEquals("system", vm.messages.value.last().role)
        assertTrue(vm.messages.value.last().text.contains("已取消"))
    }
}
