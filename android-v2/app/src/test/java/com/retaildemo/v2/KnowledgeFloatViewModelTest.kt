package com.retaildemo.v2

import com.retaildemo.v2.data.remote.ChatContext
import com.retaildemo.v2.data.remote.ChatResponse
import com.retaildemo.v2.data.remote.KnowledgeDto
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.ui.viewmodel.KnowledgeFloatViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * I6 浮窗 VM：context 注入随问发出（sku/name/spec 到后端）；断网/超时 → 离线提示，
 * 不抛异常不卡死（S4 同款降级体验）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KnowledgeFloatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val ctx = ChatContext(sku = "192.0.2.10", name = "膨胀螺丝", spec = "M6")

    @Test
    fun `在线成功回答关联商品且知识列标题`() = runTest {
        val api = mockk<retaildemoApi>()
        coEvery { api.chat(any()) } returns retrofit2.Response.success(
            ChatResponse(
                response = "打孔后塞入拧紧即可",
                knowledge = listOf(KnowledgeDto(question = "膨胀螺丝怎么安装", keyword = "安装")),
            ),
        )
        val vm = KnowledgeFloatViewModel(api)
        vm.ask("这货怎么用", ctx)
        advanceUntilIdle()

        assertEquals("打孔后塞入拧紧即可", vm.answer.value?.reply)
        assertEquals(listOf("膨胀螺丝怎么安装"), vm.answer.value?.knowledgeTitles)
        // 上下文注入确已发出（后端 prompt 置顶行靠它）
        coVerify { api.chat(match { it.context?.name == "膨胀螺丝" && it.context?.spec == "M6" }) }
    }

    @Test
    fun `离线异常提示暂不可用不崩`() = runTest {
        val api = mockk<retaildemoApi>()
        coEvery { api.chat(any()) } throws RuntimeException("timeout")
        val vm = KnowledgeFloatViewModel(api)
        vm.ask("配什么件", ctx)
        advanceUntilIdle()

        assertEquals("当前离线，AI 暂不可用", vm.answer.value?.reply)
        assertEquals(false, vm.busy.value)
    }
}
