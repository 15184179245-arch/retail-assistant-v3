package com.retaildemo.v2.data.remote

/**
 * 网关统一响应。降级/部分老路径可能缺 code/error，全部宽带领默认值。
 */
data class ApiEnvelope<T>(
    val success: Boolean = false,
    val data: T? = null,
    val code: String? = null,
    val error: String? = null,
    val user_message: String? = null,
    val hint: String? = null,
    @Suppress("PropertyName") val total: Int? = null,
    /** S17 增量回拉水位：服务器出这份数据时的服务器时间（毫秒），规避手机时钟差 */
    @com.google.gson.annotations.SerializedName("server_time_ms") val serverTimeMs: Long? = null,
    /** S4 双路：B 路规格/多义消歧提示，可为空 */
    val disambiguation: DisambiguationHint? = null,
)

/** S4 消歧提示：ask 展示文案，term 点击换词重查用的标准词。 */
data class DisambiguationHint(
    val ask: String = "",
    val term: String = "",
)

/** S11: 无售价商品清单响应 */
data class UnpricedResponse(
    val items: List<UnpricedProductItem>,
    @Suppress("PropertyName") val total: Int = 0,
    @Suppress("PropertyName") val page: Int = 1,
    @Suppress("PropertyName") val page_size: Int = 50,
)

/** S11: 无售价商品项（列表行）*/
data class UnpricedProductItem(
    val id: Long = 0,
    val barcode: String? = null,
    val sku: String? = null,
    val name: String = "",
    val spec: String? = null,
    val category: String? = null,
    @Suppress("PropertyName") val sell_price: Double = 0.0,
    /** S17：同 ProductDto.stock_qty——库存是 REAL，有小数值（散装按斤/按米）。 */
    @Suppress("PropertyName") val stock_qty: Double = 0.0,
    val location: String? = null,
)

/** 服务端商品字段（蛇形），本地转 ProductEntity。 */
data class ProductDto(
    val id: Long = 0,
    val barcode: String? = null,
    val sku: String? = null,
    val name: String = "",
    val spec: String? = null,
    /** 上游「型号」（雷士等品牌填出厂货号 DD.00000001）；2026-09-19 起随 /api/sync 与搜索下发。 */
    val model: String? = null,
    val unit: String? = null,
    val category: String? = null,
    val brand: String? = null,
    @Suppress("PropertyName") val sell_price: Double = 0.0,
    @Suppress("PropertyName") val cost_price: Double = 0.0,
    /** S17：库存是 REAL（上游按斤/按米散装商品有小数，如 0.4/12.5）——
     * 用 Int 会让 Gson 整包解析抛 NumberFormatException（实测 191 行小数库存）。 */
    @Suppress("PropertyName") val stock_qty: Double = 0.0,
    val pinyin: String? = null,
    val initials: String? = null,
    @Suppress("PropertyName") val updated_at: String? = null,
    @Suppress("PropertyName") val sale_status: String? = null,
    /**
     * 服务端 _rank_results 相关度分（2026-09-27 起 /search 随行下发）。
     * 查价页在线结果按它排序（单一权威排序）——本地分桶只兜底离线/旧服务端，
     * 否则本地粗排重排服务器序会让真品沉底（2026-09-27「1957 搜不出来」根因）。
     * 旧服务端/零结果路径可能缺字段 → 缺省 null = 无分。
     */
    val score: Double? = null,
    /**
     * 05 查价页「昨天出 3 件」：最近一次出库的**门店本地自然日** + 当日合计出库件数。
     *
     * 服务端在**无销售记录时不下发**这两个字段（而不是下发 0）→ 这里缺省 null 即「没卖过」，
     * 展示层按「为 null 就整行不显示」处理，不要渲染「出 0 件」。
     */
    @Suppress("PropertyName") val last_sale_day: String? = null,
    @Suppress("PropertyName") val last_sale_qty: Double? = null,
    val location: String? = null,
    @Suppress("PropertyName") val recommend_reason: String? = null,
    @Suppress("PropertyName") val recommend_type: String? = null,
)

/** 智能推荐响应数据结构（五金施工配套辅料与同类在售替代品）。 */
data class RecommendationResponse(
    @Suppress("PropertyName") val product_id: Long? = null,
    val name: String = "",
    @Suppress("PropertyName") val cross_sell: List<ProductDto> = emptyList(),
    val substitutes: List<ProductDto> = emptyList(),
)

/** 登录（Phase 1 预留，网关 /api/auth/login）。 */
data class LoginRequest(val username: String, val password: String)

/** 待办（库存服务 todos 表，网关已代理 /api/todos*）。 */
data class TodoDto(
    val id: Int,
    val title: String,
    val note: String? = null,
    val priority: String = "medium",
    val status: String = "pending",
    @Suppress("PropertyName") val created_by: String? = null,
    @Suppress("PropertyName") val created_at: String? = null,
    @Suppress("PropertyName") val completed_at: String? = null,
)

data class TodoCreateRequest(
    val title: String,
    val note: String = "",
    val priority: String = "medium",
    @Suppress("PropertyName") val created_by: String = "",
)

data class TodoIdRequest(val id: Int)

/** S3 多码裁决绑定（扫到码 → 人工点选商品 → 绑定）。 */
data class AliasBindRequest(
    val barcode: String,
    val sku: String,
    val actor: String = "unknown",
    val source: String = "manual",
)

/** B4 别名解析（GET api/alias/resolve）：tempCode → 正式码；未绑定 data=null。 */
data class AliasResolveDto(
    val barcode: String,
    val sku: String,
    val source: String? = null,
    val created_by: String? = null,
    @Suppress("PropertyName") val created_at: String? = null,
)

/** 网关错误统计（GET api/logs/errors，宽带领）。 */
data class ErrorStatDto(
    @Suppress("PropertyName") val error_count: Int = 0,
    @Suppress("PropertyName") val warning_count: Int = 0,
    @Suppress("PropertyName") val recent_errors: List<Map<String, String>> = emptyList(),
)

data class LoginData(
    val token: String = "",
    val user: UserInfo = UserInfo(),
)

data class UserInfo(
    val id: String = "",
    val username: String = "",
    val display_name: String = "",
    val role: String = "",
)

/**
 * M3 人员名册条目（GET api/roster）：在职店员，供首启「这台机器是谁用」下拉。
 * 拍板「全员管理员身份」下名册**不是权限系统**，只用于把账单归属到人。
 */
data class RosterItemDto(
    val id: String = "",
    @Suppress("PropertyName") val display_name: String = "",
    val role: String = "",
)

/**
 * 上传账单形态：字段名对齐 bills_sync.py 实际读取的键
 * （id/status/createdAt/updatedAt/paidAmount/staff/items...）。
 *
 * 2026-09-12 同步往返补全：type（退货方向）/invoice_needed/collaborators 与
 * items.cost_price_snap 此前不上送，推送成功后回拉会用服务端重构版覆盖本地，
 * 把退货「退」标、开票标记、成本快照洗掉——服务端账单 JSON 是原样存取，
 * 客户端带上即可闭环，无需后端改动。
 */
data class BillSyncDto(
    val id: String,
    val type: String = "sale",
    val customer_name: String? = null,
    val items: List<Map<String, Any?>>,
    val total: Double,
    val paidAmount: Double? = null,
    val note: String? = null,
    val status: String,
    val staff: String? = null,
    /** M3 归属人员工 id（人员名册）。一等字段：staff 是显示名，改名会断链，owner_id 不会。 */
    @Suppress("PropertyName") val owner_id: String? = null,
    @Suppress("PropertyName") val created_at: String,
    @Suppress("PropertyName") val updated_at: String,
    @Suppress("PropertyName") val paid_at: String? = null,
    @Suppress("PropertyName") val pay_method: String? = null,
    /** B5 需开发票标记（同步往返需携带，否则回拉洗掉）。 */
    @Suppress("PropertyName") val invoice_needed: Boolean? = null,
    /** 协同记账人（JSON 字符串原样往返）。 */
    val collaborators: String? = null,
    /** B7 回收站：跨天挂单标记（同步往返需携带，否则被服务端旧版覆盖洗掉）。 */
    val recycled: Boolean = false,
    @Suppress("PropertyName") val recycled_at: String? = null,
    /** B12 单据阶段（draft=挂账 / ready=待出库）：同步往返需携带，否则回拉洗掉、插件端点也看不到。 */
    @Suppress("PropertyName") val stage: String? = null,
    /** B13 单据编号（管家婆上云出库单号，如 C-XSCK-18118-...）：同步往返需携带。 */
    @Suppress("PropertyName") val bill_code: String? = null,
    /** B13 订单号（管家婆订单/结算单雪花 id）：同步往返需携带。 */
    @Suppress("PropertyName") val order_no: String? = null,
)

data class BillPushRequest(val bills: List<BillSyncDto>)

/**
 * POST /bills/sync 应答：{success, updated, skipped}——updated/skipped 是**顶层键**，
 * 不在 ApiEnvelope.data 里（旧实现用 ApiEnvelope<BillPushResult> 解析，data 恒为 null，
 * 逐单结果从未被核对）。可空：老网关不带这两键时，调用方回退「成功即全部已同步」旧口径。
 */
data class BillPushResult(
    val success: Boolean = false,
    val updated: List<String>? = null,
    val skipped: List<String>? = null,
    val error: String? = null,
)

/** 服务端 /api/bills/sync GET 返回裸 map：{billId: billJson}，解析后本地再映射。 */
data class ServerBillDto(
    val id: String = "",
    /** B6 退货方向（sale/return，与 BillSyncDto.type 往返对应；缺省=正单）。 */
    val type: String? = null,
    val customer_name: String? = null,
    val items: List<Map<String, Any?>>? = null,
    val total: Double = 0.0,
    val paidAmount: Double? = null,
    val note: String? = null,
    val status: String? = null,
    val staff: String? = null,
    /** M3 归属人员工 id（与 BillSyncDto 往返对应）。 */
    @Suppress("PropertyName") val owner_id: String? = null,
    @Suppress("PropertyName") val created_at: String? = null,
    @Suppress("PropertyName") val updated_at: String? = null,
    @Suppress("PropertyName") val paid_at: String? = null,
    @Suppress("PropertyName") val pay_method: String? = null,
    /** B5 需开发票（与 BillSyncDto 往返对应）。 */
    @Suppress("PropertyName") val invoice_needed: Boolean? = null,
    /** 协同记账人（JSON 字符串原样往返）。 */
    val collaborators: String? = null,
    /** B7 回收站（与 BillSyncDto 往返对应）。 */
    val recycled: Boolean? = null,
    @Suppress("PropertyName") val recycled_at: String? = null,
    /** B12 单据阶段（与 BillSyncDto 往返对应；缺键折叠回 draft）。 */
    @Suppress("PropertyName") val stage: String? = null,
    /** B13 单据编号（与 BillSyncDto 往返对应）。 */
    @Suppress("PropertyName") val bill_code: String? = null,
    /** B13 订单号（与 BillSyncDto 往返对应）。 */
    @Suppress("PropertyName") val order_no: String? = null,
)

/** B13 收银台结算回执 DTO（GET /api/pos/settlement-receipts?since= 返回）。 */
data class SettlementReceiptDto(
    @Suppress("PropertyName") val app_bill_id: String,
    @Suppress("PropertyName") val bill_code: String,
    @Suppress("PropertyName") val order_no: String,
    @Suppress("PropertyName") val settle_state: String,
    @Suppress("PropertyName") val settle_time: String,
    @Suppress("PropertyName") val reported_at: String? = null,
)

// ---------- 库存 / 货位 ----------

/** /api/inventory/tree 节点：floor→zone→rack→level→cell 嵌套 children；cell 带 code。 */
data class LocationNodeDto(
    val id: Long = 0,
    val name: String = "",
    val code: String? = null,
    val description: String? = null,
    @Suppress("PropertyName") val product_count: Int = 0,
    val children: List<LocationNodeDto>? = null,
)

/** 商品在某货位的绑定（product/locations 行）。 */
data class ProductLocationDto(
    val id: Long = 0,
    @Suppress("PropertyName") val location_code: String = "",
    @Suppress("PropertyName") val location_type: String? = null,
    val qty: Int = 0,
    val note: String? = null,
    @Suppress("PropertyName") val cell_description: String? = null,
    @Suppress("PropertyName") val created_at: String? = null,
)

/** 货位下的绑定商品（GET /api/inventory/cell/{code} 行）。 */
data class CellProductDto(
    @Suppress("PropertyName") val pl_id: Long = 0,
    val id: Long = 0,
    val barcode: String? = null,
    val sku: String? = null,
    val name: String = "",
    val spec: String? = null,
    val unit: String? = null,
    val category: String? = null,
    @Suppress("PropertyName") val sell_price: Double = 0.0,
    @Suppress("PropertyName") val cost_price: Double = 0.0,
    /** S17：库存 REAL（散装小数）；loc_qty=该货位件数，仍是整数。 */
    @Suppress("PropertyName") val stock_qty: Double = 0.0,
    @Suppress("PropertyName") val loc_qty: Int = 0,
    @Suppress("PropertyName") val image_url: String? = null,
)

data class LowStockDto(
    val id: Long = 0,
    val name: String = "",
    val barcode: String? = null,
    val spec: String? = null,
    val unit: String? = null,
    /** S17：库存 REAL（散装小数）——Int 会让整包解析失败。 */
    @Suppress("PropertyName") val stock_qty: Double = 0.0,
    val location: String? = null,
    @Suppress("PropertyName") val sell_price: Double = 0.0,
)

data class MovementDto(
    val id: Long = 0,
    @Suppress("PropertyName") val product_name: String = "",
    @Suppress("PropertyName") val product_sku: String? = null,
    @Suppress("PropertyName") val change_qty: Int = 0,
    @Suppress("PropertyName") val new_stock_qty: Int = 0,
    @Suppress("PropertyName") val movement_type: String = "",
    val note: String? = null,
    val operator: String? = null,
    @Suppress("PropertyName") val created_at: String? = null,
)

/** 调账：qty 为增减量（正入负出按 type 语义），对齐 inventory_server adjust_stock。 */
data class AdjustRequest(
    @Suppress("PropertyName") val product_id: Long,
    val qty: Int,
    val type: String = "adjust", // adjust / in / out
    val note: String = "",
    val operator: String = "",
)

/** 绑定商品到货位。 */
data class BindRequest(
    @Suppress("PropertyName") val product_id: Long,
    @Suppress("PropertyName") val cell_code: String,
    @Suppress("PropertyName") val location_type: String = "库存",
    val qty: Int = 0,
    val note: String = "",
)

/** 解绑：product_locations 记录 id。 */
data class UnbindRequest(val id: Long)

// ---------- 打印 ----------

data class PrintSendRequest(
    val printer: PrinterTarget? = null,
    val type: String = "price_tag",
    val template: Map<String, Any?>? = null,
    val items: List<Map<String, Any?>>,
)

data class PrinterTarget(
    val type: String = "network", // network / bluetooth(本地直发时为空)
    val ip: String? = null,
    val port: Int? = null,
)

data class PrintResult(
    val success: Boolean = false,
    @Suppress("PropertyName") val printed: Int = 0,
    @Suppress("PropertyName") val success_count: Int = 0,
)

/** 打印队列任务（GET api/print/jobs，print 服务内存队列）。 */
data class PrintJobDto(
    val id: String? = null,
    val type: String? = null,
    val status: String = "pending",
    val retries: Int = 0,
    val error: String? = null,
)
data class PrintJobsData(val jobs: List<PrintJobDto> = emptyList(), val count: Int = 0)

data class DiscoverRequest(val subnet: String)

// ---------- 商品图片 ----------

/** 商品图片行（GET product/images）。字段按 inventory_server 实际返回。 */
data class ProductImageDto(
    val id: Long = 0,
    @Suppress("PropertyName") val product_id: Long = 0,
    val url: String = "",
    @Suppress("PropertyName") val is_primary: Int = 0,
)

data class ImageUploadRequest(
    @Suppress("PropertyName") val product_id: Long,
    @Suppress("PropertyName") val image_data: String, // dataURL: data:image/jpeg;base64,...
    val filename: String = "photo.jpg",
)

data class ImageDeleteRequest(@Suppress("PropertyName") val image_id: Long)

data class DiscoverResult(
    val printers: List<DiscoveredPrinter> = emptyList(),
    val count: Int = 0,
)

data class DiscoveredPrinter(
    val ip: String = "",
    val port: Int = 9100,
    val status: String = "",
)

// ---------- APP 内部版本推送 ----------

/** 服务端 APP 最新版本信息（GET /api/app/version）。 */
data class AppVersionDto(
    @com.google.gson.annotations.SerializedName("version_code") val versionCode: Int = 1,
    @com.google.gson.annotations.SerializedName("version_name") val versionName: String = "2.0.0",
    @com.google.gson.annotations.SerializedName("min_version_code") val minVersionCode: Int = 1,
    @com.google.gson.annotations.SerializedName("download_url") val downloadUrl: String = "/retaildemo-v2.apk",
    @com.google.gson.annotations.SerializedName("apk_size") val apkSize: Long = 0L,
    @com.google.gson.annotations.SerializedName("release_date") val releaseDate: String? = null,
    val title: String = "店员助手新版本推送",
    @com.google.gson.annotations.SerializedName("update_log") val updateLog: String = "",
    @com.google.gson.annotations.SerializedName("force_update") val forceUpdate: Boolean = false,
)

// ---------- I16: 老王常驻 Agent ----------
data class AgentRunRequest(
    val message: String,
    @com.google.gson.annotations.SerializedName("session_id") val sessionId: String = "",
    @com.google.gson.annotations.SerializedName("image_paths") val imagePaths: List<String> = emptyList(),
    val reset: Boolean = false,
    /** 看人下菜碟（2026-09-19 老板令）：提问者身份（店员名册绑定名），网关注入提示词供老王按人应对。 */
    @com.google.gson.annotations.SerializedName("staff") val staff: String = "",
    /** 文件上传（2026-09-19 老板令）：网关 /api/agent/file 落盘后的服务端路径。 */
    @com.google.gson.annotations.SerializedName("file_paths") val filePaths: List<String> = emptyList(),
)

data class ToolTrace(
    val op: String = "",
    val ok: Boolean = false,
    val summary: String? = null,
)

data class PendingAction(
    @com.google.gson.annotations.SerializedName("action_id") val actionId: String = "",
    val op: String = "",
    val summary: String = "",
    val args: Map<String, Any?> = emptyMap(),
)

data class AgentRunData(
    @com.google.gson.annotations.SerializedName("session_id") val sessionId: String = "",
    val answer: String = "",
    val degraded: Boolean = false,
    @com.google.gson.annotations.SerializedName("tool_trace") val toolTrace: List<ToolTrace> = emptyList(),
    @com.google.gson.annotations.SerializedName("pending_actions") val pendingActions: List<PendingAction> = emptyList(),
)

data class AgentConfirmRequest(
    @com.google.gson.annotations.SerializedName("action_id") val actionId: String,
    val confirm: Boolean,
)

data class AgentConfirmData(
    @com.google.gson.annotations.SerializedName("action_id") val actionId: String = "",
    val state: String = "",
    val result: String? = null,
)

data class AgentHealthData(
    val hermes: Boolean = false,
    @com.google.gson.annotations.SerializedName("hermes_cli") val hermesCli: String? = null,
    val fallback: String? = null,
)

data class AgentImagePath(
    val path: String = "",
)

data class AgentFilePath(
    val path: String = "",
    val name: String = "",
)

// ---------- L10: 老王流式对话（/api/agent/stream 的 SSE data 帧） ----------
// 帧形见 src/agent/agent_stream.py：delta/thinking/tool/done/error 五种。
data class AgentStreamEvent(
    val type: String = "",
    val text: String? = null,
    val name: String? = null,
    val code: String? = null,
    @com.google.gson.annotations.SerializedName("user_message") val userMessage: String? = null,
    val data: AgentRunData? = null,
    /** V7 生成式 UI：{"type":"ui","messages":[A2UI 报文...]}，报文即 A2UI v0.9.1 消息数组。 */
    val messages: com.google.gson.JsonArray? = null,
)

/** S13 数据全景：孤岛表镜像推送（tables=表名→行列表，行键=服务端 snake_case 契约）。 */
data class MirrorPushRequest(
    val device: String,
    val tables: Map<String, List<Map<String, Any?>>>,
)

// ---------- S17: 点击同步＝真实上游同步（登录管家婆 → 拉当场最新 → 仅变更落库） ----------
// 服务端契约见 src/gateway/upstream_sync.py：phase ∈ fetch_products/fetch_stock/compare/
// backup/apply/done/failed；stats 是本次同步统计（供摘要文案）。
data class UpstreamSyncStatusDto(
    val running: Boolean = false,
    val phase: String? = null,
    val ok: Boolean? = null,
    val error: String? = null,
    @com.google.gson.annotations.SerializedName("elapsed_seconds") val elapsedSeconds: Double? = null,
    @com.google.gson.annotations.SerializedName("server_time_ms") val serverTimeMs: Long? = null,
    val stats: UpstreamSyncStatsDto? = null,
    /** S17 进度条：真实计数（目前=商品分页 done/total），不可得时字段为空由 UI 画权重进度。 */
    val progress: SyncProgressDto? = null,
)

data class SyncProgressDto(
    val phase: String? = null,
    val done: Int? = null,
    val total: Int? = null,
)

data class UpstreamSyncStatsDto(
    val fetched: Int? = null,
    val applied: Boolean? = null,
    val inserts: Int? = null,
    val updates: Int? = null,
    @com.google.gson.annotations.SerializedName("cost_updated") val costUpdated: Int? = null,
    @com.google.gson.annotations.SerializedName("stock_updated") val stockUpdated: Int? = null,
    @com.google.gson.annotations.SerializedName("fetch_path") val fetchPath: String? = null,
    @com.google.gson.annotations.SerializedName("fetch_seconds") val fetchSeconds: Double? = null,
    @com.google.gson.annotations.SerializedName("total_seconds") val totalSeconds: Double? = null,
    /** S18 客户档案（基础信息）同步计数，随同一作业返回。 */
    @com.google.gson.annotations.SerializedName("customers_fetched") val customersFetched: Int? = null,
    @com.google.gson.annotations.SerializedName("customers_inserted") val customersInserted: Int? = null,
    @com.google.gson.annotations.SerializedName("customers_updated") val customersUpdated: Int? = null,
)

/**
 * XZG-API-RO：GET /api/stock/live 单件**实时**库存（上游直查，约 0.5 秒）。
 *
 * 本地库存是同步快照、可能过期；店员想看当场值时点刷新，网关去问管家婆。
 * 字段全部可空——上游没这个商品或降级失败时 App 继续用本地快照。
 */
data class LiveStockDto(
    val sku: String? = null,
    val barcode: String? = null,
    val name: String? = null,
    @com.google.gson.annotations.SerializedName("stock_qty") val stockQty: Double? = null,
    @com.google.gson.annotations.SerializedName("cost_price") val costPrice: Double? = null,
    @com.google.gson.annotations.SerializedName("sell_price") val sellPrice: Double? = null,
    val spec: String? = null,
    val category: String? = null,
    val brand: String? = null,
    /** true=管家婆当场值（与本地快照区分，UI 可加标记）。 */
    val live: Boolean = false,
)

/**
 * XZG-API-RO 条码写回：一货一码校验（POST /api/product/barcode/check）。
 *
 * 改条码**之前**必须先查：这个码现在归谁？已被别人占用就不能用，避免一码两货。
 */
data class BarcodeCheckRequest(val barcode: String)

data class BarcodeCheckDto(
    val barcode: String? = null,
    /** 该码当前归属；null=没人用。 */
    val owner: BarcodeOwnerDto? = null,
    /** true=这个码上游没人用，可以绑。 */
    @com.google.gson.annotations.SerializedName("is_free") val isFree: Boolean = false,
)

data class BarcodeOwnerDto(
    val sku: String? = null,
    val name: String? = null,
    val spec: String? = null,
)

/**
 * I24 条码修改：提交（POST /api/product/barcode/write）。
 *
 * [confirmed] 是**人工确认闸**——服务端要求显式 true 才受理，防止跳过界面直接调用。
 *
 * 注意语义已从「同步写回」改为**受理**（老板 2026-09-14 令）：服务端先把条码写进**本地**别名表
 * （店员当场就能扫到），再排一条后台作业去回写管家婆。返回的 [state] 是作业状态：
 * `pending`（本地已生效、正在回写）/ `synced` / `failed`（校验没过、本地已回滚）。
 */
data class BarcodeWriteRequest(
    val sku: String,
    val barcode: String,
    val confirmed: Boolean,
    val actor: String = "",
)

data class BarcodeWriteDto(
    /** 后台回写作业号，用它查 [BarcodeSyncStatusDto]。 */
    @com.google.gson.annotations.SerializedName("job_id") val jobId: String? = null,
    val sku: String? = null,
    val barcode: String? = null,
    /** pending=本地已生效、正在回写；synced=已同步；failed=校验没过且本地已回滚。 */
    val state: String? = null,
    /** true=条码已在本机生效，店员现在就能扫到（**不等管家婆**）。 */
    @com.google.gson.annotations.SerializedName("local_effective") val localEffective: Boolean = false,
    @com.google.gson.annotations.SerializedName("upstream_pending") val upstreamPending: Boolean = false,
    /** 非空=这次修改顶掉了原绑定，原绑定已记录；回写失败会自动还原。 */
    @com.google.gson.annotations.SerializedName("replaced_sku") val replacedSku: String? = null,
    val message: String? = null,
    // 旧服务端字段（保留兼容，不再作为主判据）
    val name: String? = null,
    @com.google.gson.annotations.SerializedName("old_barcode") val oldBarcode: String? = null,
    @com.google.gson.annotations.SerializedName("new_barcode") val newBarcode: String? = null,
    @com.google.gson.annotations.SerializedName("changed_fields") val changedFields: List<String> = emptyList(),
    @com.google.gson.annotations.SerializedName("readback_ok") val readbackOk: Boolean = false,
)

/**
 * I24 条码回写作业状态（GET /api/product/barcode/sync-status）。
 *
 * 「同步成功」的判据是服务端**回读校验**，不是「请求发出去了」——
 * 只有 [readbackOk] 为真且 [synced] 为真，才允许告诉店员「已同步到管家婆」。
 */
data class BarcodeSyncStatusDto(
    @com.google.gson.annotations.SerializedName("job_id") val jobId: String? = null,
    val sku: String? = null,
    val barcode: String? = null,
    val state: String? = null,
    val attempts: Int = 0,
    /** 失败原因（给店员看的口径，如「这个条码在管家婆里已经是 X 的」）。 */
    @com.google.gson.annotations.SerializedName("last_error") val lastError: String? = null,
    /** true=校验没过，本机绑定已被自动撤回。 */
    @com.google.gson.annotations.SerializedName("rolled_back") val rolledBack: Boolean = false,
    @com.google.gson.annotations.SerializedName("rollback_note") val rollbackNote: String? = null,
    @com.google.gson.annotations.SerializedName("updated_at") val updatedAt: String? = null,
    @com.google.gson.annotations.SerializedName("local_effective") val localEffective: Boolean = false,
    @com.google.gson.annotations.SerializedName("readback_ok") val readbackOk: Boolean = false,
    val pending: Boolean = false,
    val synced: Boolean = false,
    val failed: Boolean = false,
)

/**
 * I26 商品图片回传管家婆（POST /api/product/image/push）——**受理**语义，与 I24 条码回写同范式：
 * 服务端确认本机照片存在即入队返回（毫秒级），压缩、上传、写回 imgUrl、回读校验全在后台 worker。
 *
 * [confirmed] 是**人工确认闸**（服务端硬拦）：上游图片存储「只进不出」——传进去的文件没有删除
 * 端点，所以没确认过一律不受理。[imageIds] 留空 = 只回传主图（宁少勿滥的默认口径）。
 */
data class ImagePushRequest(
    val sku: String,
    @com.google.gson.annotations.SerializedName("product_id") val productId: Long,
    @com.google.gson.annotations.SerializedName("image_ids") val imageIds: List<Long>? = null,
    val confirmed: Boolean,
    val actor: String = "",
)

data class ImagePushDto(
    /** 后台回传作业号，用它轮询 [ImageSyncStatusDto]。 */
    @com.google.gson.annotations.SerializedName("job_id") val jobId: String? = null,
    val sku: String? = null,
    @com.google.gson.annotations.SerializedName("product_id") val productId: Long = 0,
    @com.google.gson.annotations.SerializedName("image_ids") val imageIds: List<Long> = emptyList(),
    val state: String? = null,
    @com.google.gson.annotations.SerializedName("upstream_pending") val upstreamPending: Boolean = false,
    val message: String? = null,
)

/**
 * I26 图片回传作业状态（GET /api/product/image/sync-status）。
 *
 * 与条码同纪律：**回读校验通过（synced 且 readback_ok）才算同步成功**；
 * 「上传请求发出去了」不算成功。
 */
data class ImageSyncStatusDto(
    @com.google.gson.annotations.SerializedName("job_id") val jobId: String? = null,
    val sku: String? = null,
    @com.google.gson.annotations.SerializedName("product_id") val productId: Long = 0,
    val state: String? = null,
    val attempts: Int = 0,
    /** 失败原因（给店员看的口径，如「管家婆里找不到这个商品」）。 */
    @com.google.gson.annotations.SerializedName("last_error") val lastError: String? = null,
    /** 已成功上传的张数（断点续传口径：重试不重传）。 */
    @com.google.gson.annotations.SerializedName("uploaded_count") val uploadedCount: Int = 0,
    @com.google.gson.annotations.SerializedName("readback_ok") val readbackOk: Boolean = false,
    /** true=这条回传已被撤销（imgUrl 引用已恢复原样，图片文件在上游删不掉）。 */
    @com.google.gson.annotations.SerializedName("rolled_back") val rolledBack: Boolean = false,
    @com.google.gson.annotations.SerializedName("rollback_note") val rollbackNote: String? = null,
    @com.google.gson.annotations.SerializedName("updated_at") val updatedAt: String? = null,
    val pending: Boolean = false,
    val synced: Boolean = false,
    val failed: Boolean = false,
)

/** S18：GET /api/customers 单行（管家婆基础信息客户档案，只读同步）。 */
data class CustomerSyncDto(
    @com.google.gson.annotations.SerializedName("upstream_id") val upstreamId: String = "",
    val code: String? = null,
    val name: String = "",
    @com.google.gson.annotations.SerializedName("full_name") val fullName: String? = null,
    val pinyin: String? = null,
    val phone: String? = null,
    val contacts: String? = null,
    val address: String? = null,
    val category: String? = null,
    val remark: String? = null,
)

// =====================================================================================
// I22 货架多终端同步（老板令「货架信息所有终端都能同步到包括NFC和扫货架码」）
// =====================================================================================

/**
 * POST /api/shelves/sync 请求：推送本地脏行 + 带水位拉取增量。
 *
 * `changes` 用宽松 `Map<String, Any?>` 承载行数据——服务端列契约由 `shelf_store._ROW_COLS`
 * 定义，客户端发原生字段名即可；强类型化只会让两侧契约漂移时更难兼容（服务端永远是权威）。
 */
data class ShelfSyncRequest(
    @com.google.gson.annotations.SerializedName("device_id") val deviceId: String,
    @com.google.gson.annotations.SerializedName("last_seq") val lastSeq: Long,
    val changes: Map<String, List<Map<String, Any?>>>,
)

/** 服务端返回的变更行集合（五张表；行内字段名与服务端列一致）。 */
data class ShelfSyncChanges(
    val zones: List<Map<String, Any?>> = emptyList(),
    val shelves: List<Map<String, Any?>> = emptyList(),
    val levels: List<Map<String, Any?>> = emptyList(),
    val slots: List<Map<String, Any?>> = emptyList(),
    val bindings: List<Map<String, Any?>> = emptyList(),
)

/** POST /api/shelves/sync 响应 data。 */
data class ShelfSyncResult(
    @com.google.gson.annotations.SerializedName("server_seq") val serverSeq: Long = 0,
    /** 服务器当前时间（毫秒）：APP 据此校正本机时钟差（慢钟设备的改动会被 LWW 误判为旧行）。 */
    @com.google.gson.annotations.SerializedName("server_time_ms") val serverTimeMs: Long? = null,
    val changes: ShelfSyncChanges = ShelfSyncChanges(),
    val applied: Map<String, Int> = emptyMap(),
    @com.google.gson.annotations.SerializedName("skipped_stale") val skippedStale: Map<String, Int> = emptyMap(),
)
