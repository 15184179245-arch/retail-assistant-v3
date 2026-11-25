package com.retaildemo.v2.data.repo

import com.retaildemo.v2.data.local.dao.ProductDao
import com.retaildemo.v2.data.local.entity.ProductEntity
import com.retaildemo.v2.data.remote.DisambiguationHint
import com.retaildemo.v2.data.remote.AliasBindRequest
import com.retaildemo.v2.data.remote.ProductDto
import com.retaildemo.v2.data.remote.ProductLocationDto
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.data.remote.UpstreamSyncStatusDto
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProductRepo @Inject constructor(
    private val productDao: ProductDao,
    private val api: retaildemoApi,
    private val config: com.retaildemo.v2.data.remote.AppConfig,
) {
    /** 本地离线搜索（全量商品已在 Room，含拼音缩写列）。includeFrozen=true 走 S2「全量检索」。
     * 架构：多路全覆盖粗召回（应收尽收，宁错杀不放过），由上层按相关度/排序策略决胜。 */
    suspend fun searchLocal(keyword: String, includeFrozen: Boolean = false): List<ProductEntity> {
        val q = keyword.trim()
        if (q.isEmpty()) return emptyList()

        val results = mutableListOf<ProductEntity>()
        val seenIds = mutableSetOf<Long>()

        // 1. 直接全串多字段匹配（品名/规格/品牌/分类/条码/SKU/全拼/首字母）
        val direct = productDao.search(q, includeFrozen)
        for (item in direct) {
            if (seenIds.add(item.id)) {
                results.add(item)
            }
        }

        // 2. 多词拆分跨字段无序交集匹配（如"绿林 螺丝刀"、"插座 公牛"、"4分 弯头"）
        val tokens = splitSearchTokens(q)
        if (tokens.size >= 2) {
            val multi = productDao.searchMulti(tokens[0], tokens[1], includeFrozen)
            for (item in multi) {
                if (seenIds.add(item.id)) {
                    results.add(item)
                }
            }
        }

        // 3. 通用顺序字符子序列跨字段检索（如"雷士灯"、"东成角磨机"、"联塑水管"）
        val cleanChars = q.filter { !it.isWhitespace() && it !in "*.,-_()（）" }
        if (cleanChars.length in 2..8) {
            val subseq = "%" + cleanChars.map { it }.joinToString("%") + "%"
            val subseqResults = productDao.searchSubsequence(subseq, includeFrozen)
            for (item in subseqResults) {
                if (seenIds.add(item.id)) {
                    results.add(item)
                }
            }
        }

        return results
    }

    /** 条码精确查（扫码场景）：本地优先，未命中在线查（支持后端别名映射与前导单引号/SKU容错）。 */
    suspend fun getByBarcode(barcode: String): ProductEntity? {
        val clean = barcode.trim().removePrefix("'")
        productDao.getByBarcode(clean)?.let { return it }
        if (clean != barcode) {
            productDao.getByBarcode(barcode)?.let { return it }
        }
        return runCatching {
            api.search(clean).body()?.data?.let { el ->
                val (list, cands) = parseSearchJson(el)
                (list.firstOrNull { it.barcode.equals(clean, ignoreCase = true) }
                    ?: list.firstOrNull { it.sku.equals(clean, ignoreCase = true) }
                    ?: list.firstOrNull()
                    ?: cands?.firstOrNull())?.toEntity()
            }
        }.getOrNull()
    }

    /** 在线搜索（本地结果为空或需要补充时）。includeFrozen=true 走 S2 两段式「全量检索」；返回商品 + S4 消歧提示。 */
    /** S3: 在线检索结果（results 常规结果；multiCandidates 非空=同码多货，须同屏裁决）。 */
    data class SearchOnlineResult(
        val results: List<ProductEntity>,
        val disambiguation: DisambiguationHint?,
        val multiCandidates: List<ProductEntity>?,
        /** 05 出库频次（只读旁路，见 [SaleFreq] 的说明）。 */
        val saleFreq: Map<String, SaleFreq> = emptyMap(),
        /** 服务端相关度分（id→score）；离线或旧服务端为空——空即回退本地分桶排序。 */
        val scores: Map<Long, Double> = emptyMap(),
    )

    private class ParsedSearch(
        val results: List<ProductEntity>,
        val multi: List<ProductEntity>?,
        val saleFreq: Map<String, SaleFreq>,
        val scores: Map<Long, Double>,
    )

    private fun parseSearchData(
        el: com.google.gson.JsonElement?,
    ): ParsedSearch {
        val (dtos, multi) = parseSearchJson(el)
        return ParsedSearch(
            dtos.map { it.toEntity() },
            multi?.map { it.toEntity() },
            saleFreqLookup(dtos + multi.orEmpty()),
            dtos.mapNotNull { d -> d.score?.let { d.id to it } }.toMap(),
        )
    }

    suspend fun searchOnline(keyword: String, includeFrozen: Boolean = false, minQty: Int? = null): SearchOnlineResult = runCatching {
        val body = api.search(keyword, if (includeFrozen) "1" else null, minQty).body()
        val parsed = parseSearchData(body?.data)
        SearchOnlineResult(
            parsed.results,
            body?.disambiguation?.takeIf { it.ask.isNotBlank() },
            parsed.multi,
            parsed.saleFreq,
            parsed.scores,
        )
    }.getOrDefault(SearchOnlineResult(emptyList(), null, null))

    /** I18 统一搜索通道（查价/记账开单/账单加货共用的唯一编排点）：
     * 服务器库优先（全店全量，老板 2026-09-08 拍板）→ 在线不可达或确实无此货时本地 Room 离线兜底。
     * S3 同码多货 / S4 消歧提示随结果透传，保证各入口同一关键词同一结果序。 */
    data class SearchOutcome(
        val results: List<ProductEntity>,
        val usedOnline: Boolean,
        val disambiguation: DisambiguationHint?,
        val multiCandidates: List<ProductEntity>?,
        /** 05 出库频次；离线兜底（searchLocal）时为空——离线本来就不知道最近卖没卖。 */
        val saleFreq: Map<String, SaleFreq> = emptyMap(),
        /** 服务端相关度分（id→score），仅 usedOnline 时可能非空；空=展示层回退本地分桶。 */
        val scores: Map<Long, Double> = emptyMap(),
    )

    suspend fun search(keyword: String, includeFrozen: Boolean = false, minQty: Int? = null): SearchOutcome {
        val q = keyword.trim()
        if (q.isEmpty()) return SearchOutcome(emptyList(), false, null, null)
        val online = searchOnline(q, includeFrozen, minQty)
        if (online.results.isNotEmpty()) {
            return SearchOutcome(online.results, true, online.disambiguation, online.multiCandidates, online.saleFreq, online.scores)
        }
        return SearchOutcome(searchLocal(q, includeFrozen), false, null, null)
    }

    /** S3 Step4: 绑定/改判（扫到码归位到商品）。 */
    suspend fun aliasBind(barcode: String, sku: String, actor: String): Boolean = runCatching {
        val resp = api.aliasBind(AliasBindRequest(barcode, sku, actor)).body()
        resp?.success == true
    }.getOrDefault(false)

    /** 商品货位绑定列表（按 product_id 查）。 */
    suspend fun locationsOf(productId: Long): List<ProductLocationDto> = runCatching {
        api.productLocations(productId).body()?.data.orEmpty()
    }.getOrDefault(emptyList())

    /**
     * XZG-API-RO 单件实时库存：问管家婆拿当场值（网关代查，约 0.5 秒）。
     *
     * 返回 null = 拿不到（无凭据/上游慢/没这个商品/断网）——**调用方继续用本地快照**，
     * 绝不因为查不到就把界面清空或报错。只读，不动任何本地数据。
     */
    suspend fun liveStock(sku: String): com.retaildemo.v2.data.remote.LiveStockDto? = runCatching {
        val body = api.liveStock(sku).body()
        if (body?.success == true) body.data else null
    }.getOrNull()

    /**
     * 条码修改第①步（I24 改为**非阻塞预查**）：一货一码提示。
     *
     * 只用来在弹窗里提前告诉店员「这个码管家婆里已经是 X 的」，**不再拦路**：
     * 真正的把关放在后台回写的回读校验里（那儿才是权威判据）。
     *
     * @return 校验结果；null=连不上或上游异常（当成"不知道"，不阻止店员继续）
     */
    suspend fun checkBarcode(barcode: String): com.retaildemo.v2.data.remote.BarcodeCheckDto? = runCatching {
        val body = api.barcodeCheck(com.retaildemo.v2.data.remote.BarcodeCheckRequest(barcode)).body()
        if (body?.success == true) body.data else null
    }.getOrNull()

    /**
     * 条码修改第②步（I24）：**提交受理**。
     *
     * 服务端不再同步跑管家婆（旧实现要全量扫 12,631 条，几十秒）——它先把条码写进**本地**
     * 别名表（店员当场就能扫到），再排后台作业回写上游。所以这里正常是**毫秒级**返回。
     *
     * @return [BarcodeWriteResult]：accepted=true 表示本地已生效，可用 jobId 轮询同步结果。
     */
    suspend fun writeBarcode(
        sku: String,
        barcode: String,
        actor: String,
    ): BarcodeWriteResult = runCatching {
        val resp = api.barcodeWrite(
            com.retaildemo.v2.data.remote.BarcodeWriteRequest(
                sku = sku, barcode = barcode, confirmed = true, actor = actor))
        val body = resp.body()
        if (body?.success == true) {
            val d = body.data
            val replaced = d?.replacedSku?.takeIf { it.isNotBlank() }
            val msg = if (replaced != null) {
                "已在本机生效（原绑定的 $replaced 已让位），正在回写管家婆…"
            } else {
                d?.message ?: "已在本机生效，正在回写管家婆…"
            }
            BarcodeWriteResult(true, msg, d?.jobId)
        } else {
            BarcodeWriteResult(false, body?.user_message ?: body?.error ?: "条码没能生效")
        }
    }.getOrElse { BarcodeWriteResult(false, "提交失败：${it.message ?: "网络异常"}") }

    /**
     * 本地离线快速覆盖条码（毫秒级，不卡网络）：点确认即改，即查即见。
     */
    suspend fun updateLocalBarcode(sku: String, barcode: String): Boolean = runCatching {
        productDao.updateBarcodeBySku(sku, barcode) > 0
    }.getOrDefault(false)

    /**
     * 条码修改第③步（I24）：查后台回写结果。
     *
     * @return 状态；null=查不到（服务端没这条作业/断网）——此时**不要**改口说"已同步"，
     *         保持「已在本机生效」的既有说法即可。
     */
    suspend fun barcodeSyncStatus(jobId: String): com.retaildemo.v2.data.remote.BarcodeSyncStatusDto? =
        runCatching {
            val body = api.barcodeSyncStatus(jobId).body()
            if (body?.success == true) body.data else null
        }.getOrNull()

    /**
     * I26 图片回传第①步：**提交受理**（默认只回传主图，服务端口径「宁少勿滥」）。
     *
     * 与条码同款：服务端只查本机照片在场就入队返回（毫秒级），压缩/上传/写回/回读在后台 worker。
     * 上游图片存储只进不出，服务端确认闸硬拦——这里固定带 confirmed=true（界面已二次确认过）。
     */
    suspend fun pushImages(
        sku: String,
        productId: Long,
        actor: String,
    ): ImagePushResult = runCatching {
        val resp = api.imagePush(
            com.retaildemo.v2.data.remote.ImagePushRequest(
                sku = sku, productId = productId, confirmed = true, actor = actor))
        val body = resp.body()
        if (body?.success == true) {
            val d = body.data
            ImagePushResult(true, d?.message ?: "已受理，正在后台回传管家婆…", d?.jobId)
        } else {
            ImagePushResult(false, body?.user_message ?: body?.error ?: "图片没能受理")
        }
    }.getOrElse { ImagePushResult(false, "提交失败：${it.message ?: "网络异常"}") }

    /**
     * I26 图片回传第②步：查后台回传结果。
     *
     * @return null=查不到（断网/服务端没这条作业）——不要改口说「已同步」，保持「已受理」说法。
     */
    suspend fun imageSyncStatus(jobId: String): com.retaildemo.v2.data.remote.ImageSyncStatusDto? =
        runCatching {
            val body = api.imageSyncStatus(jobId).body()
            if (body?.success == true) body.data else null
        }.getOrNull()

    /** 商品图片列表。 */
    suspend fun imagesOf(productId: Long): List<com.retaildemo.v2.data.remote.ProductImageDto> = runCatching {
        api.productImages2(productId).body()?.data.orEmpty()
    }.getOrDefault(emptyList())

    /**
     * I19 下行可见性：同 imagesOf，但网络失败返回 null——与「服务端确实没有图」区分。
     * 详情页靠它避免把已上传照片在弱网下静默清空（双相互通不对称的 APP 端半边）。
     */
    suspend fun imagesOfOrNull(productId: Long): List<com.retaildemo.v2.data.remote.ProductImageDto>? = runCatching {
        api.productImages2(productId).body()?.data.orEmpty()
    }.getOrNull()

    /** 全量拉取商品库（首次/手动/6h 周期）。返回是否成功。 */
    suspend fun pullAllProducts(): Boolean = runCatching {
        val resp = api.syncProducts()
        val body = resp.body()
        if (resp.isSuccessful && body?.success == true) {
            val products = body.data.orEmpty().map { it.toEntity() }
            if (products.isNotEmpty()) {
                productDao.upsertAll(products)
                config.productsSyncedAt = System.currentTimeMillis()
                body.serverTimeMs?.takeIf { it > 0 }?.let { config.productsPulledThroughMs = it }
                true
            } else {
                false
            }
        } else {
            false
        }
    }.getOrDefault(false)

    // ---------------------------------------------------------------- S17 点击同步＝真实上游同步

    /** S17：增量水位回退（纯函数便于单测）。首次(<=0)→null 走全量；否则回退容错窗，
     * upsert 幂等，多拉几行换「绝不漏拉」。 */
    internal fun sinceParam(watermarkMs: Long): Long? = incrementalSinceParam(watermarkMs)

    /** S17：只回拉服务器变更行并更新水位。返回本次下行条数；失败返回 -1。 */
    suspend fun pullChanged(): Int = runCatching {
        val resp = api.syncProducts(sinceParam(config.productsPulledThroughMs))
        val body = resp.body()
        if (!resp.isSuccessful || body?.success != true) return@runCatching -1
        val products = body.data.orEmpty().map { it.toEntity() }
        if (products.isNotEmpty()) productDao.upsertAll(products)
        body.serverTimeMs?.takeIf { it > 0 }?.let { config.productsPulledThroughMs = it }
        config.productsSyncedAt = System.currentTimeMillis()
        products.size
    }.getOrDefault(-1)

    /** S17 同步结果。 */
    data class SyncOutcome(val ok: Boolean, val message: String)

    /**
     * S17 点击同步：**真实上游同步**（服务端登录管家婆 → 拉当场最新 → 比对 → 仅变更落库），
     * 完成后再把变更行增量回拉到本地 Room。旧服务器（无新端点）自动退回全量拉取（向后兼容）。
     *
     * [onStatus] 每次轮询（含启动）回调最新状态，UI 据此画进度条与阶段文案。
     */
    suspend fun syncFromUpstream(
        onStatus: (com.retaildemo.v2.data.remote.UpstreamSyncStatusDto) -> Unit = {},
    ): SyncOutcome {
        val startResp = runCatching { api.startUpstreamSync() }.getOrNull()
        if (startResp == null || startResp.code() == 404 || startResp.code() == 405) {
            val ok = pullAllProducts()
            return SyncOutcome(
                ok,
                if (ok) "服务器不支持上游直连同步，已全量拉取商品库" else "拉取失败，请检查网络后重试",
            )
        }
        val startBody = startResp.body()
        if (!startResp.isSuccessful || startBody?.success != true) {
            return SyncOutcome(false, startBody?.user_message ?: "同步启动失败（HTTP ${startResp.code()}）")
        }
        startBody.data?.let(onStatus)

        var status: com.retaildemo.v2.data.remote.UpstreamSyncStatusDto? = null
        val deadline = System.currentTimeMillis() + SYNC_POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(POLL_INTERVAL_MS)
            val st = runCatching { api.upstreamSyncStatus().body()?.data }.getOrNull() ?: continue
            onStatus(st)
            if (!st.running) {
                status = st
                break
            }
        }
        val final = status ?: return SyncOutcome(false, "同步超时（10 分钟未完成），请稍后重试")
        if (final.ok != true) {
            return SyncOutcome(false, final.error?.takeIf { it.isNotBlank() } ?: "上游同步失败，请稍后重试")
        }
        onStatus(final.copy(phase = "pull"))
        val pulled = pullChanged()
        onStatus(final.copy(phase = "done"))
        val summary = formatSyncSummary(final, pulled)
        config.lastSyncSummary = summary
        return SyncOutcome(true, summary)
    }

    /**
     * U6 重建离线商品库：先拉成功再清再灌，任一步失败即中止、本地零删除——
     * 查价能力绝不因一次断网被清空（店里没网时重建按钮只会得到「失败」提示）。
     */
    suspend fun rebuildFromServer(): Boolean = runCatching {
        val resp = api.syncProducts()
        val body = resp.body()
        if (!resp.isSuccessful || body?.success != true) return@runCatching false
        val products = body.data.orEmpty().map { it.toEntity() }
        if (products.isEmpty()) return@runCatching false
        productDao.clearAll()
        productDao.upsertAll(products)
        config.productsSyncedAt = System.currentTimeMillis()
        body.serverTimeMs?.takeIf { it > 0 }?.let { config.productsPulledThroughMs = it }
        true
    }.getOrDefault(false)

    /** 智能推荐：五金施工连带辅料与同类在售替代品。 */
    suspend fun getRecommendations(
        productId: Long? = null,
        name: String? = null,
        category: String? = null,
        spec: String? = null,
        limit: Int = 6,
    ): com.retaildemo.v2.data.remote.RecommendationResponse? = runCatching {
        val resp = api.getRecommendations(productId, name, category, spec, limit)
        if (resp.isSuccessful && resp.body()?.success == true) {
            resp.body()?.data
        } else {
            null
        }
    }.getOrNull()

    private companion object {
        /** 增量水位容错回退：多拉 2 分钟内的行（upsert 幂等），换「绝不漏拉」。 */
        const val INCREMENTAL_MARGIN_MS = 120_000L

        /** 同步进度轮询间隔 / 总超时（服务端作业墙钟上限 15 分钟）。 */
        const val POLL_INTERVAL_MS = 1_500L
        const val SYNC_POLL_TIMEOUT_MS = 10 * 60_000L
    }
}

/** I24：条码修改的受理结果（本地是否已生效 + 给店员的话 + 后台作业号）。 */
data class BarcodeWriteResult(
    /** true=条码已在本机生效（店员当场就能扫到），**不代表已写进管家婆**。 */
    val accepted: Boolean,
    val message: String,
    /** 后台回写作业号；非空即可轮询同步结果。 */
    val jobId: String? = null,
)

/**
 * I24：把后台回写状态翻成给店员看的话（纯函数，便于单测锁纪律）。
 *
 * 两条不可松动的纪律：
 * ① **只有服务端回读校验通过（synced 且 readback_ok）才允许说「已同步」**——
 *    「请求发出去了」不算成功，否则店员会对着一张写失败的码放心去收银台扫；
 * ② 失败必须同时说清「已撤回本机绑定」和原因，让店员知道能重试，而不是以为改成功了。
 *
 * @return null = 后台还在进行中（不打扰店员，界面保持「已在本机生效」的说法）
 */
fun barcodeSyncNotice(st: com.retaildemo.v2.data.remote.BarcodeSyncStatusDto): String? = when {
    st.synced && st.readbackOk -> "条码已同步到管家婆 ✓"
    st.synced -> "管家婆已接受写入，但回读校验没通过，请在管家婆里核一下"
    st.failed -> st.lastError?.takeIf { it.isNotBlank() }
        ?.let { "没写进管家婆：$it；本机绑定已撤回，请重试" }
        ?: "条码没能写进管家婆，本机绑定已撤回，请重试"
    else -> null
}

/** I26：图片回传的受理结果（是否受理 + 给店员的话 + 后台作业号）。 */
data class ImagePushResult(
    val accepted: Boolean,
    val message: String,
    val jobId: String? = null,
)

/**
 * I26：把图片回传状态翻成给店员看的话（纯函数，锁与条码同款纪律）。
 *
 * ① **只有回读校验通过（synced 且 readback_ok）才说「已同步」**；
 * ② 失败要说清原因；图片不像条码有「本机先生效」一说，失败就是没传成，可重试；
 *    服务端若已自动恢复原图片引用（回读漂移），原因文本里已带口径。
 *
 * @return null = 后台还在进行中（不打扰店员）
 */
fun imageSyncNotice(st: com.retaildemo.v2.data.remote.ImageSyncStatusDto): String? = when {
    st.synced && st.readbackOk -> "商品图片已同步到管家婆 ✓"
    st.synced -> "管家婆已接受图片写入，但回读校验没通过，请在管家婆里核一下商品图片"
    st.failed -> st.lastError?.takeIf { it.isNotBlank() }
        ?.let { "图片没传进管家婆：$it" }
        ?: "图片没能传进管家婆，请稍后重试"
    else -> null
}

/** S3: search data 兼容解析——常规为数组；一码多货为 {multi,candidates} 对象。 */
/**
 * 05 查价页「昨天出 3 件」的只读载体：最近一次出库的门店本地自然日 + 当日合计件数。
 *
 * **刻意不做成 ProductEntity 的字段**，两条理由都是硬约束：
 * ① 商品表会被全量同步整体覆盖（`clearAll()` + `upsertAll()`，本文件 `syncFull`），
 *    频次列只要落库就必被抹掉；
 * ② 频次是「此刻的事实」，只随在线检索返回——离线时本来就不知道最近卖没卖，
 *    留个旧值反而误导店员。
 *
 * 注意 `day` 存的是**日期**而不是「几天前」：相对文案由 UI 按设备当天实时换算，
 * 所以即便这份快照放旧了，「昨天」也会自动变成「3 天前」，不会说错话。
 */
data class SaleFreq(val day: String?, val qty: Double?)

/**
 * 把 DTO 里的频次摊成查找表，**sku 与条码双键**。
 *
 * 为什么不只认一路：账单 item 的键是「sku 优先、缺则条码」，而真实库里两者格式都很杂
 * （实测既有 `6.300073` 这种条码键，也有 `3.01.00065` 这种 sku 键），只认一路必然大面积 miss。
 * 条码键用 putIfAbsent——同一条码已被别的 sku 占了就别覆盖。
 */
internal fun saleFreqLookup(dtos: List<ProductDto>): Map<String, SaleFreq> {
    val out = HashMap<String, SaleFreq>()
    for (d in dtos) {
        if (d.last_sale_qty == null && d.last_sale_day == null) continue
        val f = SaleFreq(d.last_sale_day, d.last_sale_qty)
        d.sku?.trim()?.takeIf { it.isNotEmpty() }?.let { out[it] = f }
        d.barcode?.trim()?.takeIf { it.isNotEmpty() }?.let { out.putIfAbsent(it, f) }
    }
    return out
}

internal fun parseSearchJson(el: com.google.gson.JsonElement?): Pair<List<ProductDto>, List<ProductDto>?> {
    if (el == null || el.isJsonNull) return emptyList<ProductDto>() to null
    val g = com.google.gson.Gson()
    if (el.isJsonObject) {
        val cands = el.asJsonObject.get("candidates")?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { runCatching { g.fromJson(it, ProductDto::class.java) }.getOrNull() }
            .orEmpty()
        return emptyList<ProductDto>() to cands
    }
    val list = el.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { runCatching { g.fromJson(it, ProductDto::class.java) }.getOrNull() }
        .orEmpty()
    return list to null
}

/**
 * 搜索结果行的 **LazyColumn key**（唯一、稳定）。
 *
 * 为什么必须单独算：店内 sku 唯一定位一件商品，但**条码可空、品名大量重名**——
 * 实测 data/wujin.db 有 2769 条空条码商品，其中「手套」「交流接触器(40)」「节能灯(35)」等大批重名；
 * 拿 barcode/name 当 key 会在命中两条同名空码货时直接抛
 * `IllegalArgumentException: Key "手套" was already used`（2026-09-19 新建账单搜索崩溃）。
 * 这里只在**展示层**做唯一化，不碰任何数据（铁律：脏数据只容错不篡改）。
 */
fun uniqueProductKeys(items: List<ProductEntity>): List<String> {
    val used = HashSet<String>(items.size)
    return items.map { p ->
        val base = p.sku?.takeIf { it.isNotBlank() }
            ?: p.barcode?.takeIf { it.isNotBlank() }
            ?: p.name
        var key = base
        var n = 1
        // 引擎返回重复行时补 #n，保证集合内绝对唯一
        while (!used.add(key)) {
            key = "$base#$n"
            n++
        }
        key
    }
}

fun ProductDto.toEntity() = ProductEntity(
    id = id,
    barcode = barcode,
    sku = sku,
    name = name,
    spec = spec,
    model = model,
    unit = unit,
    category = category,
    sellPrice = sell_price,
    costPrice = cost_price,
    stockQty = stock_qty,
    pinyin = pinyin,
    initials = initials,
    updatedAt = updated_at,
    saleStatus = sale_status,
    brand = brand,
)

internal fun splitSearchTokens(query: String): List<String> {
    val q = query.trim()
    if (q.contains(" ")) {
        return q.split("\\s+".toRegex()).filter { it.isNotBlank() }
    }
    // 自动按规格数字/英文/中文自然分界智能拆词（例如 "4分角阀" -> ["4分", "角阀"]; "20A空开" -> ["20A", "空开"]）
    val regex = Regex("(\\d+(?:\\.\\d+)?(?:分|寸|mm|米|A|V|W|P|平方|位|度)?|[a-zA-Z]+|[\\u4e00-\\u9fa5]+)")
    val matches = regex.findAll(q).map { it.value }.filter { it.isNotBlank() }.toList()
    if (matches.size >= 2) {
        return matches
    }
    // 纯中文字符串若长度>=4，按2字双词切分（如"公牛插座" -> ["公牛", "插座"]）
    if (q.length >= 4) {
        return listOf(q.substring(0, 2), q.substring(2))
    }
    return listOf(q)
}

// ---------------------------------------------------------------- S17 同步文案（纯函数，单测锁定）

/** S17 增量水位容错窗：多拉这段时间内的行（upsert 幂等），换「绝不漏拉」。 */
internal const val INCREMENTAL_MARGIN_MS = 120_000L

/** S17：水位 → `since` 查询参数。首次/水位太新(<容错窗)→null 走全量；否则回退容错窗。 */
internal fun incrementalSinceParam(watermarkMs: Long): Long? =
    if (watermarkMs <= INCREMENTAL_MARGIN_MS) null else watermarkMs - INCREMENTAL_MARGIN_MS

/** S17：服务端同步阶段 → 设置页按钮文案。 */
internal fun syncPhaseLabel(phase: String?): String = when (phase) {
    "fetch_products" -> "登录管家婆 · 拉取最新商品…"
    "fetch_stock" -> "拉取库存状况表…"
    "compare" -> "比对差异…"
    "backup" -> "备份数据库…"
    "apply" -> "写入变更…"
    "pull" -> "回拉手机商品库…"
    "done" -> "完成"
    "failed" -> "失败"
    else -> "同步中…"
}

/**
 * S17 进度条：服务端进度 → 0..100 百分比。
 * 拉取阶段用**真实页数**插值（2..60），其余阶段按权重推进（比对/备份/写入），
 * 手机回拉（pull）单独占 92，全部完成 100；失败/未知返回 null（UI 画不确定态）。
 */
internal fun syncPercentOf(status: UpstreamSyncStatusDto): Int? {
    fun lerp(a: Int, b: Int, frac: Double): Int =
        (a + (b - a) * frac).toInt().coerceIn(a, b)
    return when (status.phase) {
        "fetch_products" -> {
            val done = status.progress?.done
            val total = status.progress?.total
            if (done != null && total != null && total > 0) lerp(2, 60, done.toDouble() / total) else 3
        }
        "fetch_stock" -> 62
        "compare" -> 70
        "backup" -> 77
        "apply" -> 85
        "pull" -> 92
        "done" -> 100
        "failed" -> null
        else -> 1
    }
}

/** S17 进度条文案：拉取阶段带真实页数「拉取商品 7/26 页」，其余用阶段名。 */
internal fun syncProgressText(status: UpstreamSyncStatusDto): String {
    val p = status.progress
    val done = p?.done
    val total = p?.total
    if (status.phase == "fetch_products" && done != null && total != null && total > 0) {
        return "拉取商品 $done/$total 页"
    }
    return syncPhaseLabel(status.phase)
}

/** S17：同步结果摘要（设置页直接上屏）。pulled<0 表示增量回拉失败（不影响服务器侧结果）。 */
internal fun formatSyncSummary(status: UpstreamSyncStatusDto, pulled: Int): String {
    val stats = status.stats
    val seconds = fmtSeconds(status.elapsedSeconds ?: stats?.totalSeconds)
    if (stats == null || stats.applied != true) {
        return "已是最新 ✓ 上游 ${stats?.fetched ?: 0} 条无变化（用时 $seconds）"
    }
    val parts = mutableListOf("上游 ${stats.fetched ?: 0} 条")
    (stats.updates ?: 0).takeIf { it > 0 }?.let { parts += "更新 $it 行" }
    (stats.inserts ?: 0).takeIf { it > 0 }?.let { parts += "新增 $it 行" }
    (stats.costUpdated ?: 0).takeIf { it > 0 }?.let { parts += "成本 $it" }
    (stats.stockUpdated ?: 0).takeIf { it > 0 }?.let { parts += "库存 $it" }
    if (pulled >= 0) parts += "拉回手机 $pulled 条"
    return "同步完成 ✓ " + parts.joinToString(" · ") + "（用时 $seconds）"
}

private fun fmtSeconds(value: Double?): String =
    if (value == null) "--" else "${kotlin.math.round(value * 10) / 10}s"
