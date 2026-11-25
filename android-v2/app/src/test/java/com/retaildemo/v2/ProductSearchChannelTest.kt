package com.retaildemo.v2

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.retaildemo.v2.data.local.dao.ProductDao
import com.retaildemo.v2.data.local.entity.ProductEntity
import com.retaildemo.v2.data.remote.ApiEnvelope
import com.retaildemo.v2.data.remote.DisambiguationHint
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.data.repo.ProductRepo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * I18 搜索双通道合一：ProductRepo.search 是查价/记账/加货共用的唯一编排点。
 * 锁定语义：服务器优先（在线非空即用）→ 本地 Room 兜底（在线空/不可达）；空词短路。
 */
class ProductSearchChannelTest {

    private fun entity(name: String) = ProductEntity(
        id = 1L, barcode = "bc1", sku = "sku1", name = name, spec = null, unit = "件",
        category = null, sellPrice = 10.0, costPrice = 6.0, stockQty = 5.0,
        pinyin = null, initials = null, updatedAt = null,
    )

    private fun envelope(data: JsonElement?) = Response.success(
        ApiEnvelope(success = true, data = data, code = "OK"),
    )

    // U6 ProductRepo 注入 AppConfig 后，不关心同步时间落库的用例给 relaxed mock
    private fun repo(dao: ProductDao, api: retaildemoApi) =
        ProductRepo(dao, api, mockk(relaxed = true))

    @Test
    fun `在线命中 用服务器结果 不落本地`() = runTest {
        val api = mockk<retaildemoApi>()
        val dao = mockk<ProductDao>()
        val arr = JsonArray().apply { add(JsonObject().apply { addProperty("name", "服务器货") }) }
        coEvery { api.search("公牛", null, null) } returns envelope(arr)
        val repo = repo(dao, api)

        val out = repo.search("公牛")

        assertTrue(out.usedOnline)
        assertEquals("服务器货", out.results.first().name)
        coVerify(exactly = 0) { dao.search(any(), any()) }
    }

    @Test
    fun `在线空 回退本地结果且消歧置空`() = runTest {
        val api = mockk<retaildemoApi>()
        val dao = mockk<ProductDao>()
        coEvery { api.search("公牛", null, null) } returns envelope(JsonArray())
        coEvery { dao.search(any(), any()) } returns listOf(entity("本地货"))
        coEvery { dao.searchSubsequence(any(), any()) } returns emptyList()
        val repo = repo(dao, api)

        val out = repo.search("公牛")

        assertTrue(!out.usedOnline)
        assertEquals(listOf("本地货"), out.results.map { it.name })
        assertNull(out.disambiguation)
        assertNull(out.multiCandidates)
    }

    @Test
    fun `消歧提示随在线结果透传且includeFrozen映射minQty`() = runTest {
        val api = mockk<retaildemoApi>()
        val dao = mockk<ProductDao>()
        val arr = JsonArray().apply { add(JsonObject()) }
        coEvery { api.search("6901", "1", 1) } returns Response.success(
            ApiEnvelope(
                success = true, data = arr, code = "OK",
                disambiguation = DisambiguationHint(ask = "要哪种？", term = "标准词"),
            ),
        )
        val repo = repo(dao, api)

        val out = repo.search("6901", includeFrozen = true, minQty = 1)

        assertTrue(out.usedOnline)
        assertEquals("标准词", out.disambiguation?.term)
        coVerify(exactly = 0) { dao.search(any(), any()) }
    }

    @Test
    fun `空词短路 不打网络不查库`() = runTest {
        val api = mockk<retaildemoApi>()
        val dao = mockk<ProductDao>()
        val repo = repo(dao, api)

        val out = repo.search("   ")

        assertTrue(out.results.isEmpty())
        assertTrue(!out.usedOnline)
        coVerify(exactly = 0) { api.search(any(), any(), any()) }
        coVerify(exactly = 0) { dao.search(any(), any()) }
    }
}
