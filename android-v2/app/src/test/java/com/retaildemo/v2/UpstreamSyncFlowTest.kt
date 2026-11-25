package com.retaildemo.v2

import com.retaildemo.v2.data.local.dao.ProductDao
import com.retaildemo.v2.data.remote.ApiEnvelope
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.remote.ProductDto
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.data.remote.SyncProgressDto
import com.retaildemo.v2.data.remote.UpstreamSyncStatsDto
import com.retaildemo.v2.data.remote.UpstreamSyncStatusDto
import com.retaildemo.v2.data.repo.INCREMENTAL_MARGIN_MS
import com.retaildemo.v2.data.repo.ProductRepo
import com.retaildemo.v2.data.repo.formatSyncSummary
import com.retaildemo.v2.data.repo.incrementalSinceParam
import com.retaildemo.v2.data.repo.syncPercentOf
import com.retaildemo.v2.data.repo.syncPhaseLabel
import com.retaildemo.v2.data.repo.syncProgressText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * S17 点击同步＝真实上游同步（登录管家婆拉当场最新 → 仅变更落库）+ 手机端增量回拉。
 * 红线：失败不许改本地；旧服务器必须退回全量拉取（功能不中断）；水位用服务器时间。
 */
class UpstreamSyncFlowTest {

    // ---------------- 纯函数：水位 / 文案 ----------------

    @Test
    fun `首次同步水位为零 走全量`() {
        assertNull(incrementalSinceParam(0L))
    }

    @Test
    fun `水位太新 走全量`() {
        assertNull(incrementalSinceParam(INCREMENTAL_MARGIN_MS - 1))
    }

    @Test
    fun `有水位则回退容错窗 保证不漏拉`() {
        assertEquals(1_000_000L - INCREMENTAL_MARGIN_MS, incrementalSinceParam(1_000_000L))
    }

    @Test
    fun `阶段文案覆盖服务端全部阶段`() {
        assertEquals("登录管家婆 · 拉取最新商品…", syncPhaseLabel("fetch_products"))
        assertEquals("拉取库存状况表…", syncPhaseLabel("fetch_stock"))
        assertEquals("比对差异…", syncPhaseLabel("compare"))
        assertEquals("写入变更…", syncPhaseLabel("apply"))
        assertEquals("回拉手机商品库…", syncPhaseLabel("pull"))
        assertEquals("同步中…", syncPhaseLabel(null))
    }

    // ---------------- S17 进度条 ----------------

    @Test
    fun `进度条 拉取阶段按真实页数插值`() {
        fun at(done: Int, total: Int) = syncPercentOf(
            UpstreamSyncStatusDto(
                running = true, phase = "fetch_products",
                progress = SyncProgressDto(phase = "fetch_products", done = done, total = total),
            ),
        )
        assertEquals(2, at(0, 26))            // 起步
        assertEquals(31, at(13, 26))          // 半程 ≈ 2 + 58*0.5 = 31
        assertEquals(60, at(26, 26))          // 拉满
        assertTrue(at(5, 26)!! < at(20, 26)!!) // 单调
    }

    @Test
    fun `进度条 无真实页数时给起步值不装假`() {
        val p = syncPercentOf(UpstreamSyncStatusDto(running = true, phase = "fetch_products"))
        assertEquals(3, p)
    }

    @Test
    fun `进度条 各阶段单调推进且完成100 失败为不确定`() {
        val seq = listOf("fetch_stock", "compare", "backup", "apply", "pull", "done")
            .map { syncPercentOf(UpstreamSyncStatusDto(running = false, phase = it))!! }
        assertEquals(seq, seq.sorted())
        assertEquals(100, seq.last())
        assertNull(syncPercentOf(UpstreamSyncStatusDto(running = false, phase = "failed")))
    }

    @Test
    fun `进度条文案 拉取阶段带页数`() {
        val st = UpstreamSyncStatusDto(
            running = true, phase = "fetch_products",
            progress = SyncProgressDto(phase = "fetch_products", done = 7, total = 26),
        )
        assertEquals("拉取商品 7/26 页", syncProgressText(st))
        assertEquals("比对差异…", syncProgressText(UpstreamSyncStatusDto(running = true, phase = "compare")))
    }

    @Test
    fun `无变更摘要只说已是最新`() {
        val status = UpstreamSyncStatusDto(
            ok = true, elapsedSeconds = 6.5,
            stats = UpstreamSyncStatsDto(fetched = 12631, applied = false, totalSeconds = 6.5),
        )
        val text = formatSyncSummary(status, 0)
        assertTrue(text.startsWith("已是最新"))
        assertTrue(text.contains("12631"))
        assertTrue(!text.contains("更新"))
    }

    @Test
    fun `有变更摘要含更新新增库存与耗时`() {
        val status = UpstreamSyncStatusDto(
            ok = true, elapsedSeconds = 17.3,
            stats = UpstreamSyncStatsDto(
                fetched = 12631, applied = true, inserts = 2, updates = 5,
                costUpdated = 11, stockUpdated = 40, totalSeconds = 17.3,
            ),
        )
        val text = formatSyncSummary(status, 7)
        assertTrue(text.startsWith("同步完成"))
        assertTrue(text.contains("更新 5 行"))
        assertTrue(text.contains("新增 2 行"))
        assertTrue(text.contains("成本 11"))
        assertTrue(text.contains("库存 40"))
        assertTrue(text.contains("拉回手机 7 条"))
        assertTrue(text.contains("17.3s"))
    }

    @Test
    fun `回拉失败不影响服务器侧摘要`() {
        val status = UpstreamSyncStatusDto(
            ok = true, elapsedSeconds = 9.0,
            stats = UpstreamSyncStatsDto(fetched = 10, applied = true, updates = 1, totalSeconds = 9.0),
        )
        val text = formatSyncSummary(status, -1)
        assertTrue(text.contains("更新 1 行"))
        assertTrue(!text.contains("拉回手机"))
    }

    // ---------------- 仓库层：真上游同步流程 ----------------

    private fun okStatus(dto: UpstreamSyncStatusDto) = Response.success(ApiEnvelope(success = true, data = dto))

    @Test
    fun `旧服务器无新端点 退回全量拉取`() = runTest {
        val api = mockk<retaildemoApi>()
        val dao = mockk<ProductDao>(relaxed = true)
        coEvery { api.startUpstreamSync() } returns Response.error(
            404, "not found".toResponseBody("application/json".toMediaTypeOrNull()),
        )
        coEvery { api.syncProducts(any()) } returns Response.success(
            ApiEnvelope(success = true, data = listOf(ProductDto(id = 1L, name = "货", barcode = "b", sku = "s"))),
        )
        val repo = ProductRepo(dao, api, mockk<AppConfig>(relaxed = true))

        val outcome = repo.syncFromUpstream()

        assertTrue(outcome.ok)
        assertTrue(outcome.message.contains("全量拉取"))
        coVerify { dao.upsertAll(any()) }
    }

    @Test
    fun `启动失败 不改本地并透出提示`() = runTest {
        val api = mockk<retaildemoApi>()
        val dao = mockk<ProductDao>(relaxed = true)
        coEvery { api.startUpstreamSync() } returns Response.success(
            ApiEnvelope(success = false, user_message = "缺少熊掌柜密码"),
        )
        val repo = ProductRepo(dao, api, mockk<AppConfig>(relaxed = true))

        val outcome = repo.syncFromUpstream()

        assertTrue(!outcome.ok)
        assertEquals("缺少熊掌柜密码", outcome.message)
        coVerify(exactly = 0) { dao.upsertAll(any()) }
    }

    @Test
    fun `同步完成后只回拉变更行并落服务器水位`() = runTest {
        val api = mockk<retaildemoApi>()
        val dao = mockk<ProductDao>(relaxed = true)
        val cfg = mockk<AppConfig>(relaxed = true)
        coEvery { cfg.productsPulledThroughMs } returns 1_000_000L
        coEvery { api.startUpstreamSync() } returns okStatus(UpstreamSyncStatusDto(running = true))
        coEvery { api.upstreamSyncStatus() } returns okStatus(
            UpstreamSyncStatusDto(
                running = false, ok = true, elapsedSeconds = 12.0,
                stats = UpstreamSyncStatsDto(fetched = 12631, applied = true, updates = 3, totalSeconds = 12.0),
            ),
        )
        coEvery { api.syncProducts(any()) } returns Response.success(
            ApiEnvelope(
                success = true,
                data = listOf(ProductDto(id = 9L, name = "新货", barcode = "b9", sku = "s9")),
                serverTimeMs = 2_000_000L,
            ),
        )
        val repo = ProductRepo(dao, api, cfg)
        val phases = mutableListOf<String>()

        val outcome = repo.syncFromUpstream { st -> phases += (st.phase ?: "") }

        assertTrue(outcome.ok)
        coVerify { api.syncProducts(1_000_000L - INCREMENTAL_MARGIN_MS) }
        coVerify { cfg.productsPulledThroughMs = 2_000_000L }
        coVerify { dao.upsertAll(any()) }
        assertTrue(phases.contains("pull"))
        assertTrue(outcome.message.contains("更新 3 行"))
    }

    @Test
    fun `上游同步失败 零回拉`() = runTest {
        val api = mockk<retaildemoApi>()
        val dao = mockk<ProductDao>(relaxed = true)
        coEvery { api.startUpstreamSync() } returns okStatus(UpstreamSyncStatusDto(running = true))
        coEvery { api.upstreamSyncStatus() } returns okStatus(
            UpstreamSyncStatusDto(running = false, ok = false, error = "上游网络错误"),
        )
        val repo = ProductRepo(dao, api, mockk<AppConfig>(relaxed = true))

        val outcome = repo.syncFromUpstream()

        assertTrue(!outcome.ok)
        assertEquals("上游网络错误", outcome.message)
        coVerify(exactly = 0) { api.syncProducts(any()) }
        coVerify(exactly = 0) { dao.upsertAll(any()) }
    }
}
