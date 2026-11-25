package com.retaildemo.v2.data.repo

import android.content.Context
import com.retaildemo.v2.data.local.dao.PrintJobDao
import com.retaildemo.v2.data.local.entity.PrintJobEntity
import com.retaildemo.v2.data.remote.PrintSendRequest
import com.retaildemo.v2.data.remote.PrinterTarget
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.print.BluetoothPrinter
import com.retaildemo.v2.print.CpclImageCodec
import com.retaildemo.v2.print.LabelTemplates
import com.retaildemo.v2.print.TagBitmapRenderer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** 打印机配置（本地持久化，SharedPreferences JSON）。支持便携机纸规(宽×高)/浓度/指令集参数。 */
data class PrinterConfig(
    val kind: String = "bluetooth", // bluetooth / network
    val name: String = "",
    val address: String = "", // 蓝牙 MAC 或网络 IP
    val port: Int = 9100,
    val paperWidth: Int = 40, // 纸宽 (mm): 30, 40, 50, 60, 70, 80
    val paperHeight: Int = 0, // 纸高 (mm): 20/30/40/50；0=按纸宽取默认（F11 老配置兼容）
    val density: Int = 8, // 打印浓度: 1~15
    val commandSet: String = "tspl", // 指令集: tspl(标签机主流) / cpcl(便携面单机常见)
    /** 打印偏移容错 X（mm，右为正）：补偿打印头起始位/纸在胶辊横向错位。 */
    val offsetXMm: Double = 0.0,
    /** 打印偏移容错 Y（mm，下为正）：补偿走纸定位偏差。 */
    val offsetYMm: Double = 0.0,
)

/**
 * 打印执行结果：失败必带原因（2026-09-11 排障：队列路径旧实现把异常吞成裸 false，
 * 老板在界面上只见「打印失败」，蓝牙连不上/没设默认打印机/打印服务不通无从区分）。
 */
data class PrintOutcome(val success: Boolean, val error: String? = null)

/** 批量出签结果：firstError=首张失败的原因（含任务名），供队列页直接展示。 */
data class PrintBatchOutcome(
    val successCount: Int,
    val failCount: Int,
    val firstError: String? = null,
)

/** 双路打印仓库：蓝牙 SPP/BLE 直发 TSPL|CPCL（按打印机指令集配置）/ 网络标签机走 print 服务渲染 + 本地打印任务队列。 */
@Singleton
class PrintRepo @Inject constructor(
    private val api: retaildemoApi,
    private val printJobDao: PrintJobDao,
    @ApplicationContext private val appContext: Context,
) {
    private val prefs = appContext.getSharedPreferences("retaildemo_v2_print", Context.MODE_PRIVATE)

    /** 排雷 M3（2026-09-09）：出队/批量打印全程持锁——此前 UI 与 Worker 并发调用会读到同一 pending 列表，同一任务重复打印。 */
    private val queueMutex = Mutex()

    fun defaultPrinter(): PrinterConfig? =
        prefs.getString("default_printer", null)?.let { json ->
            runCatching {
                val obj = org.json.JSONObject(json)
                PrinterConfig(
                    kind = obj.optString("kind", "bluetooth"),
                    name = obj.optString("name"),
                    address = obj.optString("address"),
                    port = obj.optInt("port", 9100),
                    paperWidth = obj.optInt("paper_width", 40),
                    paperHeight = obj.optInt("paper_height", 0),
                    density = obj.optInt("density", 8),
                    commandSet = obj.optString("command_set", "tspl"),
                    offsetXMm = obj.optDouble("offset_x_mm", 0.0),
                    offsetYMm = obj.optDouble("offset_y_mm", 0.0),
                )
            }.getOrNull()
        }

    fun setDefaultPrinter(config: PrinterConfig) {
        prefs.edit().putString("default_printer", serializePrinter(config)).apply()
    }

    /** 价签模板配置（打印机管理页可调）。 */
    fun priceTagTemplate(): LabelTemplates.PriceTagTemplate {
        val json = prefs.getString("price_tag_template", null) ?: return LabelTemplates.PriceTagTemplate()
        return runCatching {
            val obj = org.json.JSONObject(json)
            LabelTemplates.PriceTagTemplate(
                storeName = obj.optString("store_name"),
                showSpec = obj.optBoolean("show_spec", true),
                showSku = obj.optBoolean("show_sku", true),
                codeStyle = obj.optString("code_style", "qr"),
                priceScale = obj.optInt("price_scale", 3).coerceIn(2, 4),
                footer = obj.optString("footer"),
            )
        }.getOrDefault(LabelTemplates.PriceTagTemplate())
    }

    fun setPriceTagTemplate(t: LabelTemplates.PriceTagTemplate) {
        prefs.edit().putString(
            "price_tag_template",
            org.json.JSONObject()
                .put("store_name", t.storeName)
                .put("show_spec", t.showSpec)
                .put("show_sku", t.showSku)
                .put("code_style", t.codeStyle)
                .put("price_scale", t.priceScale)
                .put("footer", t.footer)
                .toString(),
        ).apply()
    }

    private fun templateToMap(
        t: LabelTemplates.PriceTagTemplate,
        widthMm: Int = 30,
        heightMm: Int = 40,
    ): Map<String, Any?> = mapOf(
        "store_name" to t.storeName,
        "show_spec" to t.showSpec,
        "show_sku" to t.showSku,
        "code_style" to t.codeStyle,
        "price_scale" to t.priceScale,
        "footer" to t.footer,
        // 纸规随单下发（服务端 _tspl_price_tag 按此出 SIZE 与版式，F11 双路一致）
        "width" to widthMm,
        "height" to heightMm,
        // 打印偏移容错随单下发（服务端 _apply_offset 同口径平移）
        "offset_x" to t.offsetXMm,
        "offset_y" to t.offsetYMm,
    )

    private fun serializePrinter(config: PrinterConfig): String =
        org.json.JSONObject()
            .put("kind", config.kind)
            .put("name", config.name)
            .put("address", config.address)
            .put("port", config.port)
            .put("paper_width", config.paperWidth)
            .put("paper_height", config.paperHeight)
            .put("density", config.density)
            .put("command_set", config.commandSet)
            .put("offset_x_mm", config.offsetXMm)
            .put("offset_y_mm", config.offsetYMm)
            .toString()

    fun secondPrinter(): PrinterConfig? =
        prefs.getString("second_printer", null)?.let { json ->
            runCatching {
                val obj = org.json.JSONObject(json)
                PrinterConfig(
                    kind = obj.optString("kind", "bluetooth"),
                    name = obj.optString("name"),
                    address = obj.optString("address"),
                    port = obj.optInt("port", 9100),
                    paperWidth = obj.optInt("paper_width", 40),
                    paperHeight = obj.optInt("paper_height", 0),
                    density = obj.optInt("density", 8),
                    commandSet = obj.optString("command_set", "tspl"),
                    offsetXMm = obj.optDouble("offset_x_mm", 0.0),
                    offsetYMm = obj.optDouble("offset_y_mm", 0.0),
                )
            }.getOrNull()
        }

    fun setSecondPrinter(config: PrinterConfig?) {
        if (config == null) {
            prefs.edit().remove("second_printer").apply()
        } else {
            prefs.edit().putString("second_printer", serializePrinter(config)).apply()
        }
    }

    /** 网络标签机发现（print 服务扫 /24 网段 9100 端口）。 */
    suspend fun discover(subnet: String): List<Pair<String, Int>> = runCatching {
        api.discoverPrinters(
            com.retaildemo.v2.data.remote.DiscoverRequest(subnet),
        ).body()?.data?.printers?.map { it.ip to it.port } ?: emptyList()
    }.getOrDefault(emptyList())

    /**
     * 打印：按默认打印机类型分流。
     * 蓝牙 → 本地 TSPL 直发（GBK）；网络 → print 服务端渲染。
     */
    suspend fun print(tspl: String): Boolean {
        val printer = defaultPrinter() ?: throw IllegalStateException("请先在打印机管理里设置默认打印机")
        return when (printer.kind) {
            "bluetooth" -> {
                BluetoothPrinter.printTspl(appContext, printer.address, tspl)
                true
            }
            // 排雷 M2（2026-09-09）：网络路径无法直发原始 TSPL（服务端只做模板渲染），
            // 旧实现发空 items 却返回 true——静默什么都不打还报成功。改为显式报错。
            else -> throw IllegalStateException(
                "网络打印机不支持直接发送小票文本，请使用价签/货架/货位模板打印",
            )
        }
    }

    /**
     * CPCL 价签位图（WYSIWYG，2026-09-12）：[LabelTemplates.planPriceTagOps] 几何 → 手机绘成
     * 单色位图 → CPCL EG 整图下发（NiimBot/快麦等标签 App 的行业做法）。位图是唯一事实——
     * 设备字库号/字宽/行高差异（BTP-P39 PLUS 实证 TEXT 路径中文乱码+版式漂移压码）从此免疫。
     * 兜底：若某机型 EG 不支持，文本路径仍在案（LabelTemplates.priceTagCpcl，仅改此处分发）。
     */
    private fun cpclImagePriceTagPayload(
        items: List<LabelTemplates.PriceTagItem>,
        template: LabelTemplates.PriceTagTemplate,
        paperWidth: Int,
        paperHeight: Int,
        copies: Int,
    ): String {
        val hMm = if (paperHeight > 0) paperHeight else LabelTemplates.heightFor(paperWidth)
        val wDots = paperWidth * LabelTemplates.DOTS_PER_MM
        val hDots = hMm * LabelTemplates.DOTS_PER_MM
        return items.joinToString("") { item ->
            val bmp = TagBitmapRenderer.render(
                LabelTemplates.planPriceTagOps(item, template, paperWidth, hMm), wDots, hDots,
            )
            val form = CpclImageCodec.egForm(wDots, hDots, copies) { x, y ->
                (bmp.getPixel(x, y) and 0xFF) < TagBitmapRenderer.BLACK_THRESHOLD
            }
            bmp.recycle()
            form
        }
    }

    /** 价签（蓝牙=本地模板按指令集渲染；网络=服务端渲染）。 */
    suspend fun printPriceTags(items: List<LabelTemplates.PriceTagItem>, copies: Int = 1): Boolean {
        val printer = defaultPrinter() ?: throw IllegalStateException("请先设置默认打印机")
        // 偏移容错是**打印机级校准**（同一张纸不同机器偏差不同），随出签覆盖模板偏移
        val template = priceTagTemplate().copy(
            offsetXMm = printer.offsetXMm,
            offsetYMm = printer.offsetYMm,
        )
        return when (printer.kind) {
            "bluetooth" -> {
                val payload = if (printer.commandSet.equals(LabelTemplates.CMD_CPCL, true)) {
                    // CPCL 便携机走位图 WYSIWYG（2026-09-12 BTP-P39 PLUS 实证 TEXT 版式漂移）
                    cpclImagePriceTagPayload(items, template, printer.paperWidth, printer.paperHeight, copies)
                } else {
                    LabelTemplates.priceTagFor(
                        printer.commandSet, items, copies, template, printer.paperWidth, printer.density,
                        printer.paperHeight,
                    )
                }
                BluetoothPrinter.printTspl(appContext, printer.address, payload)
                true
            }
            else -> sendServerRendered(
                type = "price_tag",
                items = items.map {
                    mapOf(
                        "name" to it.name, "price" to "%.2f".format(it.price),
                        "spec" to it.spec, "sku" to it.sku, "barcode" to it.barcode,
                        "unit" to it.unit,
                    )
                },
                copies = copies,
                template = templateToMap(
                    template,
                    printer.paperWidth,
                    if (printer.paperHeight > 0) printer.paperHeight else LabelTemplates.heightFor(printer.paperWidth),
                ),
            )
        }
    }

    /**
     * 测试打印（2026-09-12 BTP-P39 PLUS 真机排障）：样例价签（真实版式：中文/价格/码图）。
     * CPCL 蓝牙机额外附打一张「字体号对照条」（4/24/55 同句中文，见 LabelTemplates.cpclFontProbeTag）
     * ——真机打一次即可定谳该机的中文字体号；TSPL 机与网络机维持原样例价签。
     */
    suspend fun printTestTag(): Boolean {
        val printer = defaultPrinter() ?: throw IllegalStateException("请先在打印机管理里设置默认打印机")
        val sample = listOf(
            LabelTemplates.PriceTagItem(
                name = "测试商品",
                price = 12.5,
                spec = "TEST-SPEC",
                sku = "TEST-0001",
                barcode = "TEST-0001",
                unit = "个",
            ),
        )
        if (printer.kind == "bluetooth" && printer.commandSet.equals(LabelTemplates.CMD_CPCL, true)) {
            val template = priceTagTemplate().copy(
                offsetXMm = printer.offsetXMm,
                offsetYMm = printer.offsetYMm,
            )
            // 样例价签=位图 WYSIWYG（与实签同路径）；对照条=设备字库文本路径——一打同时验证两条通路
            val payload = cpclImagePriceTagPayload(
                sample, template, printer.paperWidth, printer.paperHeight, 1,
            ) + LabelTemplates.cpclFontProbeTag(printer.paperWidth, printer.paperHeight)
            BluetoothPrinter.printTspl(appContext, printer.address, payload)
            return true
        }
        return printPriceTags(sample)
    }

    /** 货架标签。 */
    suspend fun printShelfLabel(zoneName: String, rackName: String, floorTag: String, copies: Int = 1): Boolean {
        val printer = defaultPrinter() ?: throw IllegalStateException("请先设置默认打印机")
        return when (printer.kind) {
            "bluetooth" -> {
                BluetoothPrinter.printTspl(
                    appContext,
                    printer.address,
                    LabelTemplates.shelfLabelFor(
                        printer.commandSet, zoneName, rackName, floorTag, copies, printer.paperWidth, printer.density,
                        printer.paperHeight,
                    ),
                )
                true
            }
            else -> sendServerRendered(
                type = "shelf_label",
                items = listOf(
                    mapOf("zone_name" to zoneName, "rack_name" to rackName, "floor_tag" to floorTag),
                ),
                copies = copies,
            )
        }
    }

    /** 货位标签（网络路径用 barcode 类型单条）。 */
    suspend fun printCellLabel(cellCode: String, copies: Int = 1): Boolean {
        val printer = defaultPrinter() ?: throw IllegalStateException("请先设置默认打印机")
        return when (printer.kind) {
            "bluetooth" -> {
                BluetoothPrinter.printTspl(
                    appContext,
                    printer.address,
                    LabelTemplates.cellLabelFor(printer.commandSet, cellCode, copies, printer.paperWidth, printer.density, printer.paperHeight),
                )
                true
            }
            else -> sendServerRendered(
                type = "barcode",
                items = listOf(mapOf("barcode" to cellCode, "name" to cellCode)),
                copies = copies,
            )
        }
    }

    /**
     * I20 位置标签（货架 / 层 / 格）：**自定义名字大字 + 稳定定位码条码**。
     *
     * 复用既有打印系统（蓝牙 SPP / 网络标签机由 [defaultPrinter] 自动分流），不新增链路。
     * 条码里是 `SX:<tagId>` 而非名字 —— 名字改了不用重贴。
     */
    suspend fun printLocationLabel(
        name: String,
        code: String,
        sub: String = "",
        copies: Int = 1,
    ): Boolean {
        val printer = defaultPrinter() ?: throw IllegalStateException("请先设置默认打印机")
        return when (printer.kind) {
            "bluetooth" -> {
                BluetoothPrinter.printTspl(
                    appContext,
                    printer.address,
                    LabelTemplates.locationLabelFor(
                        printer.commandSet, name, code, sub, copies,
                        printer.paperWidth, printer.density, printer.paperHeight,
                    ),
                )
                true
            }
            else -> sendServerRendered(
                type = "barcode",
                items = listOf(mapOf("barcode" to code, "name" to name)),
                copies = copies,
            )
        }
    }

    private suspend fun sendServerRendered(
        type: String,
        items: List<Map<String, Any?>>,
        copies: Int,
        template: Map<String, Any?>? = null,
    ): Boolean {
        val payloadItems = if (copies > 1) (1..copies).flatMap { items } else items
        val resp = try {
            api.sendPrintJob(
                PrintSendRequest(
                    printer = defaultPrinter()?.let {
                        PrinterTarget(type = "network", ip = it.address, port = it.port)
                    },
                    type = type,
                    template = template,
                    items = payloadItems,
                ),
            )
        } catch (e: Exception) {
            // 2026-09-11 排障：网络路径连不上打印服务时必须报出原因，禁止静默 false
            throw IllegalStateException("打印服务不可达（检查后端是否启动/服务器地址）：${e.message}", e)
        }
        val body = resp.body()
        if (!resp.isSuccessful || body?.success != true) {
            val detail = body?.user_message ?: body?.error ?: body?.code ?: "HTTP ${resp.code()}"
            throw IllegalStateException("打印服务返回失败：$detail")
        }
        return true
    }

    // ==================== S5 打印任务队列 ====================

    fun observeQueue(): Flow<List<PrintJobEntity>> = printJobDao.observeAll()

    fun observePendingCount(): Flow<Int> = printJobDao.observePendingCount()

    fun observePendingJobs(): Flow<List<PrintJobEntity>> = printJobDao.observePendingJobs()

    suspend fun getPendingJobs(): List<PrintJobEntity> = printJobDao.getPendingJobs()

    suspend fun getFailedJobs(): List<PrintJobEntity> = printJobDao.getFailedJobs()

    /**
     * 入队：同 sku 且处于 pending 状态的已有任务自动合并数量 qty；
     * 否则插入新任务。
     */
    suspend fun enqueue(job: PrintJobEntity): Long {
        val existing = printJobDao.findPendingBySku(job.sku)
        return if (existing != null) {
            printJobDao.increaseQty(existing.id, job.qty)
            existing.id
        } else {
            printJobDao.insert(job)
        }
    }

    /** 批量入队。 */
    suspend fun enqueueBatch(jobs: List<PrintJobEntity>) {
        jobs.forEach { enqueue(it) }
    }

    private suspend fun executeJob(job: PrintJobEntity): Boolean {
        return if (job.sourceScene == PrintJobEntity.SCENE_SHELF) {
            val code = job.barcode?.takeIf { it.isNotBlank() }
            if (code != null && code.startsWith(com.retaildemo.v2.nfc.ShelfNfc.PREFIX, ignoreCase = true)) {
                // I20 三级货架标签：自定义名大字 + 稳定定位码（`SX:<tagId>`），改名免重贴
                printLocationLabel(name = job.name, code = code, sub = job.spec.orEmpty(), copies = job.qty)
            } else {
                // 兼容旧队列：五级货位码时代入队的任务（barcode 是裸货位码）
                printCellLabel(code ?: job.sku.removePrefix("CELL:"), copies = job.qty)
            }
        } else {
            val item = LabelTemplates.PriceTagItem(
                name = job.name,
                price = job.sellPrice,
                spec = job.spec.orEmpty(),
                sku = job.sku,
                barcode = job.barcode.orEmpty(),
                unit = job.unit.orEmpty(),
            )
            printPriceTags(listOf(item), copies = job.qty)
        }
    }

    /** 执行单个任务并归一结果：失败必带原因（异常消息或网络路径报错），状态回写由调用方负责。 */
    private suspend fun runJob(job: PrintJobEntity): PrintOutcome = try {
        if (executeJob(job)) {
            PrintOutcome(true)
        } else {
            PrintOutcome(false, "打印机未出签且未返回原因")
        }
    } catch (e: Exception) {
        PrintOutcome(false, e.message ?: e.javaClass.simpleName)
    }

    /**
     * 出队打印队首的一个任务：
     * 成功标 done；失败 fail_count+1，若达 3 次标 failed。
     * 排雷 M3：pending 读取→执行→状态回写全程持 [queueMutex]，
     * 并发调用串行推进，不再出现同一任务被两个调用方同时执行。
     */
    suspend fun dequeueAndPrintNext(): Boolean = queueMutex.withLock {
        val pendingList = printJobDao.getPendingJobs()
        if (pendingList.isEmpty()) return@withLock false
        val job = pendingList.first()

        val outcome = runJob(job)
        if (outcome.success) {
            printJobDao.updateStatus(job.id, PrintJobEntity.STATUS_DONE)
        } else {
            handleFailure(job)
        }
        outcome.success
    }

    private suspend fun handleFailure(job: PrintJobEntity) {
        val nextCount = job.failCount + 1
        if (nextCount >= 3) {
            printJobDao.recordFailure(job.id, PrintJobEntity.STATUS_FAILED)
        } else {
            printJobDao.recordFailure(job.id, PrintJobEntity.STATUS_PENDING)
        }
    }

    /**
     * 批量按顺序打印全部 pending 任务：
     * 返回 [PrintBatchOutcome]（成功/失败数 + 首个失败原因，界面直接展示）。
     * 排雷 M3：与 [dequeueAndPrintNext] 共用 [queueMutex]，快照在锁内读取。
     */
    suspend fun printAllPending(): PrintBatchOutcome = queueMutex.withLock {
        val pendingList = printJobDao.getPendingJobs()
        var successCount = 0
        var failCount = 0
        var firstError: String? = null
        for (job in pendingList) {
            val outcome = runJob(job)
            if (outcome.success) {
                printJobDao.updateStatus(job.id, PrintJobEntity.STATUS_DONE)
                successCount++
            } else {
                handleFailure(job)
                if (firstError == null) firstError = "${job.name}：${outcome.error}"
                failCount++
            }
        }
        PrintBatchOutcome(successCount, failCount, firstError)
    }

    /** 单条失败任务重试：重置为 pending 并重置 fail_count = 0。 */
    suspend fun retryJob(id: Long) {
        printJobDao.retryJob(id)
    }

    /** 删除单条任务。 */
    suspend fun deleteJob(id: Long) {
        printJobDao.deleteById(id)
    }

    /** 更新单条任务数量（数量随时可改）。 */
    suspend fun updateJobQty(id: Long, qty: Int) = queueMutex.withLock {
        val job = printJobDao.getById(id) ?: return@withLock
        printJobDao.update(job.copy(qty = qty.coerceAtLeast(1)))
    }

    /** 更新单条任务打印内容（品名/规格/价格/条码/单位/份数随时可改）。 */
    suspend fun updateJobContent(
        id: Long,
        name: String,
        spec: String?,
        sellPrice: Double,
        barcode: String?,
        qty: Int,
        unit: String? = null,
    ) = queueMutex.withLock {
        val job = printJobDao.getById(id) ?: return@withLock
        printJobDao.update(
            job.copy(
                name = name.trim(),
                spec = spec?.trim()?.ifBlank { null },
                sellPrice = sellPrice.coerceAtLeast(0.0),
                barcode = barcode?.trim()?.ifBlank { null },
                unit = unit?.trim()?.ifBlank { null },
                qty = qty.coerceAtLeast(1),
            ),
        )
    }

    /** 单条任务即刻打印（浮窗和队列页「打本条」）。失败返回原因，不再吞成裸 false。 */
    suspend fun printSingleJob(job: PrintJobEntity): PrintOutcome = queueMutex.withLock {
        val outcome = runJob(job)
        if (outcome.success) {
            printJobDao.updateStatus(job.id, PrintJobEntity.STATUS_DONE)
        } else {
            handleFailure(job)
        }
        outcome
    }

    /** 清空所有已打印 (done) 任务。 */
    suspend fun clearDone() {
        printJobDao.clearDone()
    }
}
