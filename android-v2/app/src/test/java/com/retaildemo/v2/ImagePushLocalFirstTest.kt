package com.retaildemo.v2

import com.retaildemo.v2.data.remote.ApiEnvelope
import com.retaildemo.v2.data.remote.ImagePushDto
import com.retaildemo.v2.data.remote.ImagePushRequest
import com.retaildemo.v2.data.remote.ImageSyncStatusDto
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.data.repo.ProductRepo
import com.retaildemo.v2.data.repo.imageSyncNotice
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * I26：商品图片「显式回传 + 后台回写」APP 侧契约（IRON 2 锁纪律——与条码 I24 同款范式）。
 *
 * ① **只有服务端回读校验通过（synced 且 readback_ok）才允许说「已同步」**——
 *    上传请求「发出去了」不算成功；
 * ② 请求体固定带 confirmed=true（人工确认闸，服务端硬拦），默认不指定 image_ids
 *    （= 只回传主图，上游图片存储只进不出、宁少勿滥）。
 */
class ImagePushLocalFirstTest {

    private fun notice(
        state: String? = "pending",
        readbackOk: Boolean = false,
        lastError: String? = null,
    ) = ImageSyncStatusDto(
        jobId = "io-1", sku = "192.0.2.10", productId = 42, state = state,
        lastError = lastError, readbackOk = readbackOk,
        pending = state == "pending", synced = state == "synced", failed = state == "failed",
    )

    // ---------------- ① 「已同步」的判据是回读校验，不是送达 ----------------

    @Test
    fun `回读通过才说已同步`() {
        val msg = imageSyncNotice(notice(state = "synced", readbackOk = true))
        assertTrue("回读通过应告知已同步，实际：$msg", msg!!.contains("已同步"))
    }

    @Test
    fun `回了 synced 但回读没过不许说已同步`() {
        val msg = imageSyncNotice(notice(state = "synced", readbackOk = false))
        assertFalse("回读对不上绝不能报成功：$msg", msg!!.contains("已同步到管家婆"))
        assertTrue("必须提示去核对，实际：$msg", msg.contains("核一下"))
    }

    @Test
    fun `进行中不打扰店员`() {
        assertNull(imageSyncNotice(notice(state = "pending")))
    }

    @Test
    fun `失败要带原因不能沉默`() {
        val msg = imageSyncNotice(notice(state = "failed", lastError = "管家婆里找不到这个商品"))
        assertTrue("要带上服务端给的原因：$msg", msg!!.contains("找不到"))
    }

    @Test
    fun `失败没给原因也要说可重试`() {
        val msg = imageSyncNotice(notice(state = "failed", lastError = null))
        assertTrue(msg!!.contains("重试"))
    }

    // ---------------- ② 受理语义：确认闸 + 默认主图 ----------------

    private fun repoWith(api: retaildemoApi) = ProductRepo(mockk(relaxed = true), api, mockk(relaxed = true))

    @Test
    fun `受理成功要带出作业号`() = runTest {
        val api = mockk<retaildemoApi>()
        coEvery { api.imagePush(any()) } returns Response.success(
            ApiEnvelope(
                success = true,
                data = ImagePushDto(
                    jobId = "io-42", sku = "192.0.2.10", productId = 42,
                    imageIds = listOf(7), state = "pending", upstreamPending = true,
                    message = "已受理，正在后台回传管家婆（1 张）…",
                ),
            ))

        val r = repoWith(api).pushImages("192.0.2.10", 42, "APP店员")

        assertTrue(r.accepted)
        assertEquals("io-42", r.jobId)
        assertFalse("受理阶段不能宣称已写进管家婆：${r.message}", r.message.contains("已同步"))
    }

    @Test
    fun `请求体固定带确认闸且默认主图`() = runTest {
        val api = mockk<retaildemoApi>()
        val captured = mutableListOf<ImagePushRequest>()
        coEvery { api.imagePush(capture(captured)) } returns Response.success(
            ApiEnvelope(success = true, data = ImagePushDto(jobId = "io-1", state = "pending")))

        repoWith(api).pushImages("192.0.2.10", 42, "APP店员")

        assertEquals(1, captured.size)
        assertTrue("人工确认闸不得被绕过", captured[0].confirmed)
        assertEquals("192.0.2.10", captured[0].sku)
        assertEquals(42L, captured[0].productId)
        assertNull("默认不指定张数=服务端只挑主图（宁少勿滥）", captured[0].imageIds)
    }

    @Test
    fun `服务端拒绝时用它的原话`() = runTest {
        val api = mockk<retaildemoApi>()
        coEvery { api.imagePush(any()) } returns Response.success(
            ApiEnvelope(success = false, user_message = "这个商品本机还没有照片，先拍照再来回传"))

        val r = repoWith(api).pushImages("192.0.2.10", 42, "APP店员")

        assertFalse(r.accepted)
        assertEquals("这个商品本机还没有照片，先拍照再来回传", r.message)
        assertNull(r.jobId)
    }

    @Test
    fun `网络异常不谎报成功`() = runTest {
        val api = mockk<retaildemoApi>()
        coEvery { api.imagePush(any()) } returns Response.error(500, "".toResponseBody(null))

        val r = repoWith(api).pushImages("192.0.2.10", 42, "APP店员")

        assertFalse("拿不到受理结果就必须报失败", r.accepted)
        assertNull(r.jobId)
    }
}
