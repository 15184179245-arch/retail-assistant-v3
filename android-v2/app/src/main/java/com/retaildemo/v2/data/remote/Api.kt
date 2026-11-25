package com.retaildemo.v2.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 网关 :18767 端点全集（v2 全模块）。
 * 注意：chat 响应是顶层裸 JSON（无 success 包裹）；GET bills/sync 是裸 map。
 */
interface retaildemoApi {

    // ---------- S13 数据全景（孤岛表单向镜像） ----------
    @POST("api/mirror/push")
    suspend fun mirrorPush(@Body body: MirrorPushRequest): Response<ApiEnvelope<Map<String, Any>>>


    // ---------- 待办 / 诊断 ----------
    @GET("api/todos")
    suspend fun todos(@Query("status") status: String? = null): Response<ApiEnvelope<List<TodoDto>>>

    @POST("api/todos/create")
    suspend fun createTodo(@Body body: TodoCreateRequest): Response<ApiEnvelope<Map<String, String>>>

    @POST("api/todos/toggle")
    suspend fun toggleTodo(@Body body: TodoIdRequest): Response<ApiEnvelope<Map<String, String>>>

    @POST("api/todos/delete")
    suspend fun deleteTodo(@Body body: TodoIdRequest): Response<ApiEnvelope<Map<String, String>>>

    @GET("api/logs/errors")
    suspend fun errorLogs(): Response<ApiEnvelope<Map<String, ErrorStatDto>>>

    @POST("api/alias/bind")
    suspend fun aliasBind(@Body body: AliasBindRequest): Response<ApiEnvelope<Map<String, String>>>

    @POST("api/alias/rebind")
    suspend fun aliasRebind(@Body body: AliasBindRequest): Response<ApiEnvelope<Map<String, String>>>

    /** B4: 别名解析（tempCode 是否已回写正式码）。未绑定 data=null。 */
    @GET("api/alias/resolve")
    suspend fun aliasResolve(@Query("barcode") barcode: String): Response<ApiEnvelope<AliasResolveDto?>>

    // ---------- 搜索 / 商品 ----------
    @GET("api/search")
    suspend fun search(
        @Query("q") keyword: String,
        @Query("include_frozen") includeFrozen: String? = null,
        @Query("min_qty") minQty: Int? = null,
    ): Response<ApiEnvelope<com.google.gson.JsonElement>>  // S3: data 可能是数组或 {multi,candidates}

    /** 智能推荐：五金施工连带辅料与同类在售替代品。 */
    @GET("api/recommendations")
    suspend fun getRecommendations(
        @Query("product_id") productId: Long? = null,
        @Query("name") name: String? = null,
        @Query("category") category: String? = null,
        @Query("spec") spec: String? = null,
        @Query("limit") limit: Int = 6,
    ): Response<ApiEnvelope<RecommendationResponse>>

    /** 商品库拉取。since=服务器时间水位（毫秒）时只回变更行；不传=全量（首次/重建）。 */
    @GET("api/sync")
    suspend fun syncProducts(@Query("since") since: Long? = null): Response<ApiEnvelope<List<ProductDto>>>

    /** S18 客户档案拉取（管家婆基础信息只读同步落库的服务器侧客户）。 */
    @GET("api/customers")
    suspend fun syncCustomers(): Response<ApiEnvelope<List<CustomerSyncDto>>>

    /**
     * XZG-API-RO 单件实时库存：网关直查管家婆当场值（约 0.5 秒）。
     *
     * 失败（无凭据/上游慢/没这个商品）返回 success=false，调用方继续用本地快照——
     * 实时查询是增强，不是依赖。
     */
    @GET("api/stock/live")
    suspend fun liveStock(@Query("sku") sku: String): Response<ApiEnvelope<LiveStockDto>>

    /**
     * 条码修改（XZG-API-RO，老板单独授权的最小写口子）：
     * ① check = 一货一码**预查**（只读，仅作提示，不拦路）；
     * ② write = 提交修改（必须带 confirmed）：**本地先生效** + 后台回写管家婆；
     * ③ sync-status = 查后台回写作业状态（回读校验通过才算同步成功）。
     */
    @POST("api/product/barcode/check")
    suspend fun barcodeCheck(@Body body: BarcodeCheckRequest): Response<ApiEnvelope<BarcodeCheckDto>>

    @POST("api/product/barcode/write")
    suspend fun barcodeWrite(@Body body: BarcodeWriteRequest): Response<ApiEnvelope<BarcodeWriteDto>>

    @GET("api/product/barcode/sync-status")
    suspend fun barcodeSyncStatus(@Query("job_id") jobId: String): Response<ApiEnvelope<BarcodeSyncStatusDto>>

    /**
     * I26 商品图片回传管家婆（与条码回写同范式）：
     * push = **受理**（服务端确认照片在场即入队，压缩/上传/写回/回读全在后台 worker）；
     * sync-status = 查作业状态（回读校验通过才算同步成功）。
     * 上游图片存储只进不出，push 必带 confirmed=true（人工确认闸，服务端硬拦）。
     */
    @POST("api/product/image/push")
    suspend fun imagePush(@Body body: ImagePushRequest): Response<ApiEnvelope<ImagePushDto>>

    @GET("api/product/image/sync-status")
    suspend fun imageSyncStatus(@Query("job_id") jobId: String): Response<ApiEnvelope<ImageSyncStatusDto>>

    // ---------- S17 真实上游同步：点击＝登录管家婆拉当场最新（服务端后台作业） ----------
    @POST("api/sync/upstream")
    suspend fun startUpstreamSync(): Response<ApiEnvelope<UpstreamSyncStatusDto>>

    @GET("api/sync/upstream/status")
    suspend fun upstreamSyncStatus(): Response<ApiEnvelope<UpstreamSyncStatusDto>>

    @GET("api/inventory/product/locations")
    suspend fun productLocations(@Query("product_id") productId: Long): Response<ApiEnvelope<List<ProductLocationDto>>>

    /** 货位下绑定的商品清单。 */
    @GET("api/inventory/cell/{code}")
    suspend fun cellProducts(@Path("code", encoded = true) code: String): Response<ApiEnvelope<List<CellProductDto>>>

    @GET("api/inventory/product/images")
    suspend fun productImages2(@Query("product_id") productId: Long): Response<ApiEnvelope<List<ProductImageDto>>>

    @POST("api/inventory/product/image/upload")
    suspend fun uploadProductImage(@Body body: ImageUploadRequest): Response<ApiEnvelope<Unit>>

    @POST("api/inventory/product/image/delete")
    suspend fun deleteProductImage(@Body body: ImageDeleteRequest): Response<ApiEnvelope<Unit>>

    // ---------- 账单同步 ----------
    // 2026-09-12：应答 {success, updated, skipped} 是顶层键，不在 ApiEnvelope.data 里
    // （旧签名解析出的逐单结果恒为空，被 LWW skip 的单也被误标已同步）。
    @POST("api/bills/sync")
    suspend fun pushBills(@Body body: BillPushRequest): Response<BillPushResult>

    /** 账单真删除：向服务端发送删除请求，从服务端数据库真正删除。 */
    @POST("api/bills/delete")
    suspend fun deleteBill(@Body body: Map<String, String>): Response<ApiEnvelope<Map<String, Any>>>

    @DELETE("api/bills/{id}")
    suspend fun deleteBillById(@Path("id") id: String): Response<ApiEnvelope<Map<String, Any>>>

    // 2026-09-11：值改 JsonElement 粗解析——老网关会把 _request_id 字符串混进裸 map，
    // 按 ServerBillDto 强类型解析会整包抛 Expected BEGIN_OBJECT；BillRepo.parseServerBillMap 只挑对象值。
    @GET("api/bills/sync")
    suspend fun pullBills(): Response<Map<String, com.google.gson.JsonElement>>

    /** B13 结算回执：增量拉取收银端已结算回执。 */
    @GET("api/pos/settlement-receipts")
    suspend fun pullReceipts(@Query("since") since: String? = null): Response<ApiEnvelope<List<SettlementReceiptDto>>>

    /** M3 人员名册：在职店员列表（首启「这台机器是谁用」下拉来源）。 */
    @GET("api/roster")
    suspend fun roster(): Response<ApiEnvelope<List<RosterItemDto>>>

    // ---------- 2026-09-11 老板令：设备身份登记 + 崩溃收集 ----------
    /** 设备报到：登记/刷新「哪台设备绑哪个店员」+ 在线心跳（启动、绑店员、15 分钟周期）。 */
    @POST("api/device/checkin")
    suspend fun deviceCheckin(@Body body: DeviceCheckinRequest): Response<ApiEnvelope<DeviceCheckinResult>>

    /** 崩溃报告上报（启动时补传上次崩溃；服务端免鉴权、超限自动截断）。 */
    @POST("api/crash/report")
    suspend fun crashReport(@Body body: CrashReportRequest): Response<ApiEnvelope<Map<String, String>>>

    // ---------- I22 货架多终端同步（老板令：所有终端都要能同步到，含 NFC 与扫货架码） ----------
    /** 货架同步：推脏行 + 带水位拉增量（服务端 JSONL 存储，行级 LWW，软删）。 */
    @POST("api/shelves/sync")
    suspend fun shelfSync(@Body body: ShelfSyncRequest): Response<ApiEnvelope<ShelfSyncResult>>

    /** H2 运行日志批量上报（服务端免鉴权；单批 500/单条 4000 截断、5MB 轮转护栏）。 */
    @POST("api/logs/ingest")
    suspend fun ingestLogs(@Body body: LogIngestRequest): Response<ApiEnvelope<Map<String, Int>>>

    @GET("api/stats/revenue/today")
    suspend fun revenueToday(): Response<ApiEnvelope<RevenueTodayData>>

    // ---------- 库存 / 货位（inventory:18604）----------
    @GET("api/inventory/tree")
    suspend fun inventoryTree(): Response<ApiEnvelope<List<LocationNodeDto>>>

    @GET("api/inventory/low-stock")
    suspend fun lowStock(): Response<ApiEnvelope<List<LowStockDto>>>

    @GET("api/inventory/zero-stock")
    suspend fun zeroStock(): Response<ApiEnvelope<List<LowStockDto>>>

    @GET("api/inventory/movements")
    suspend fun movements(
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
        @Query("product_id") productId: Long? = null,
    ): Response<ApiEnvelope<List<MovementDto>>>

    @POST("api/inventory/adjust")
    suspend fun adjustStock(@Body body: AdjustRequest): Response<ApiEnvelope<Unit>>

    @POST("api/inventory/product/bind")
    suspend fun bindProduct(@Body body: BindRequest): Response<ApiEnvelope<Unit>>

    @POST("api/inventory/product/unbind")
    suspend fun unbindProduct(@Body body: UnbindRequest): Response<ApiEnvelope<Unit>>

    // ---------- 打印（print:18605，服务端渲染 TSPL）----------
    @POST("api/print/send")
    suspend fun sendPrintJob(@Body body: PrintSendRequest): Response<ApiEnvelope<PrintResult>>

    @GET("api/print/jobs")
    suspend fun printJobs(): Response<ApiEnvelope<PrintJobsData>>

    @POST("api/print/discover")
    suspend fun discoverPrinters(@Body body: DiscoverRequest): Response<ApiEnvelope<DiscoverResult>>

    // ---------- AI ----------
    @POST("api/chat")
    suspend fun chat(@Body body: ChatRequest): Response<ChatResponse>

    @POST("api/knowledge")
    suspend fun knowledgeSearch(@Body body: KnowledgeRequest): Response<ApiEnvelope<List<KnowledgeDto>>>

    // 知识入库：店员/老板在浮窗里把问答沉淀进知识库（approved 直上架，重题幂等）
    @POST("api/knowledge/add")
    suspend fun knowledgeAdd(@Body body: KnowledgeAddRequest): Response<ApiEnvelope<KnowledgeAddResult>>

    // ---------- 登录 ----------
    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): Response<ApiEnvelope<LoginData>>

    // S11: 无售价商品清单入口
    @GET("api/products/unpriced")
    suspend fun getUnpricedProducts(
        @Query("kw") kw: String = "",
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50,
    ): Response<ApiEnvelope<UnpricedResponse>>

    // I4 / I5: 数据治理标记同步
    @POST("api/marks/sync")
    suspend fun syncMarks(@Body body: List<MarkSyncDto>): Response<ApiEnvelope<MarkSyncResult>>

    // APP 内部版本推送（检查更新）
    @GET("api/app/version")
    suspend fun getAppVersion(): Response<ApiEnvelope<AppVersionDto>>

    // ---------- I16: 老王常驻 Agent ----------
    @POST("api/agent/run")
    suspend fun agentRun(@Body body: AgentRunRequest): Response<ApiEnvelope<AgentRunData>>

    @POST("api/agent/confirm")
    suspend fun agentConfirm(@Body body: AgentConfirmRequest): Response<ApiEnvelope<AgentConfirmData>>

    @GET("api/agent/health")
    suspend fun agentHealth(): Response<ApiEnvelope<AgentHealthData>>

    @POST("api/agent/image")
    suspend fun agentImage(@Body body: okhttp3.RequestBody): Response<ApiEnvelope<AgentImagePath>>

    /** 文件上传（2026-09-19 老板令）：raw 字节直通；原文件名走 ?name=（URL 编码），白名单在网关。 */
    @POST("api/agent/file")
    suspend fun agentFile(
        @retrofit2.http.Query("name") name: String,
        @Body body: okhttp3.RequestBody,
    ): Response<ApiEnvelope<AgentFilePath>>
}

data class MarkSyncDto(
    val sku: String = "",
    val barcode: String = "",
    @Suppress("PropertyName") val productName: String = "",
    @Suppress("PropertyName") val markType: String = "",
    val note: String = "",
    @Suppress("PropertyName") val photoUri: String = "",
    val status: String = "open",
    @Suppress("PropertyName") val created_at: String = "",
)

/** 设备报到载荷：device_id 为安装维度身份（AppConfig.deviceId）；staff_* 为当前绑定店员。 */
data class DeviceCheckinRequest(
    val device_id: String,
    val staff_id: String = "",
    val staff_name: String = "",
    val model: String = "",
    val app_version: String = "",
)

data class DeviceCheckinResult(
    @Suppress("PropertyName") val device_id: String = "",
    @Suppress("PropertyName") val bound_staff_id: String = "",
    @Suppress("PropertyName") val server_time: String = "",
)

/** 崩溃报告载荷：crash=完整堆栈原文，服务端只封存不改写（超 200KB 截断）。 */
data class CrashReportRequest(
    val app_version: String = "",
    val device_model: String = "",
    val android: String = "",
    val thread: String = "",
    val crash: String = "",
)

/** H2 单条运行日志（ts=客户端 epoch ms）。 */
data class LogRecordDto(
    val ts: Long = 0L,
    val level: String = "INFO",
    val tag: String = "",
    val message: String = "",
)

/** H2 运行日志批量上报载荷：device_id/app_version/model 为请求级字段，服务端并入每条记录。 */
data class LogIngestRequest(
    val device_id: String = "",
    val app_version: String = "",
    val model: String = "",
    val records: List<LogRecordDto> = emptyList(),
)

data class MarkSyncResult(
    val count: Int = 0,
)

data class RevenueTodayData(
    @Suppress("PropertyName") val today_revenue: Double = 0.0,
    @Suppress("PropertyName") val today_orders: Int = 0,
    @Suppress("PropertyName") val pending_count: Int = 0,
)

data class ChatRequest(
    val messages: List<ChatMessage>,
    val session_id: String = "",
    /** I6 商品上下文（浮窗唤起时带入；null=老式无上下文问答，Gson 默认不序列化 null，老调用零影响）。 */
    val context: ChatContext? = null,
)

/** I6 浮窗商品上下文：只带定位商品的三个字段，不含价格（价格是记账场景的事）。 */
data class ChatContext(val sku: String = "", val name: String = "", val spec: String = "")

data class ChatMessage(val role: String, val content: String)

/** chat 服务顶层裸响应（无 success 包裹），字段名 response。 */
data class ChatResponse(
    val response: String = "",
    val products: List<ProductDto> = emptyList(),
    val knowledge: List<KnowledgeDto> = emptyList(),
    @Suppress("PropertyName") val session_id: String = "",
)

data class KnowledgeRequest(val query: String, val limit: Int = 5)

data class KnowledgeDto(
    val question: String = "",
    val answer: String = "",
    val category: String = "",
    val keyword: String = "",
)

// 知识入库请求：question/answer 必填，category/keyword 可空（keyword 空时服务端检索走问句）
data class KnowledgeAddRequest(
    val question: String,
    val answer: String,
    val category: String = "",
    val keyword: String = "",
)

data class KnowledgeAddResult(val added: Int = 0)
