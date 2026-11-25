package com.retaildemo.v2

import com.retaildemo.v2.data.local.dao.ProductDao
import com.retaildemo.v2.data.remote.ApiEnvelope
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.remote.ProductDto
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.data.repo.ProductRepo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * U6 重建离线商品库：唯一红线是「先拉成功才清，失败零删除」——
 * 店里断网时点重建绝不能把本地 12k SKU 查价能力清没。
 */
class ProductRepoRebuildTest {

    private fun dto(name: String) = ProductDto(id = 1L, name = name, barcode = "bc", sku = "sku")

    private fun ok(vararg products: ProductDto) = Response.success(
        ApiEnvelope(success = true, data = products.toList(), code = "OK"),
    )

    @Test
    fun `服务器失败 重建中止本地零删除`() = runTest {
        val api = mockk<retaildemoApi>()
        val dao = mockk<ProductDao>()
        coEvery { api.syncProducts() } returns Response.error(503, "server down".toResponseBody("application/json".toMediaTypeOrNull()))
        val repo = ProductRepo(dao, api, mockk(relaxed = true))

        assertTrue(!repo.rebuildFromServer())

        coVerify(exactly = 0) { dao.clearAll() }
        coVerify(exactly = 0) { dao.upsertAll(any()) }
    }

    @Test
    fun `服务器成功但空数据 视同失败不清库`() = runTest {
        val api = mockk<retaildemoApi>()
        val dao = mockk<ProductDao>()
        coEvery { api.syncProducts() } returns ok()
        val repo = ProductRepo(dao, api, mockk(relaxed = true))

        assertTrue(!repo.rebuildFromServer())

        coVerify(exactly = 0) { dao.clearAll() }
    }

    @Test
    fun `拉取成功 先清后灌并落同步时间`() = runTest {
        val api = mockk<retaildemoApi>()
        val dao = mockk<ProductDao>(relaxed = true)
        val cfg = mockk<AppConfig>(relaxed = true)
        coEvery { api.syncProducts() } returns ok(dto("新货"))
        val repo = ProductRepo(dao, api, cfg)

        assertTrue(repo.rebuildFromServer())

        coVerifyOrder {
            dao.clearAll()
            dao.upsertAll(any())
        }
        coVerify { cfg.productsSyncedAt = any() }
    }

    @Test
    fun `网络异常同样零删除`() = runTest {
        val api = mockk<retaildemoApi>()
        val dao = mockk<ProductDao>()
        coEvery { api.syncProducts() } throws java.net.ConnectException("offline")
        val repo = ProductRepo(dao, api, mockk(relaxed = true))

        assertTrue(!repo.rebuildFromServer())

        coVerify(exactly = 0) { dao.clearAll() }
    }
}
