package com.retaildemo.v2.data.repo

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.retaildemo.v2.data.local.AppDatabase
import com.retaildemo.v2.data.local.entity.BillEntity
import com.retaildemo.v2.data.local.entity.BillItem
import com.retaildemo.v2.data.local.entity.CustomerEntity
import com.retaildemo.v2.data.local.entity.ProductEntity
import com.retaildemo.v2.data.local.entity.TempProductEntity
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.remote.BillPushRequest
import com.retaildemo.v2.data.remote.BillSyncDto
import com.retaildemo.v2.data.remote.ProductDto
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.data.remote.ServerBillDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** 账单详情 UI 模型。 */
data class BillDetailUi(
    val id: String,
    val customerName: String,
    val totalAmount: Double,
    val paidAmount: Double?,
    val status: String,
    val items: List<BillItem>,
    val createdAt: String,
    val payMethod: String? = null,
    val receiptPhoto: String? = null,
    val invoiceNeeded: Boolean = false,
    val note: String = "",
    /** B6 方向：sale=正单 / return=退货负数单。 */
    val direction: String = "sale",
    /** B6 退货单关联的原单 id（正单为 null）。 */
    val refBillId: String? = null,
    /** I1 主记账人（= BillEntity.staff）。 */
    val staff: String = "",
    /** I1 协同人（逗号分隔名单，可空）。 */
    val collaborators: String? = null,
    /** B12 单据阶段：draft=挂账 / ready=待出库。 */
    val stage: String = BillEntity.STAGE_DRAFT,
    /** B13 单据编号（管家婆上云出库单号，如 C-XSCK-18118-...）。 */
    val billCode: String? = null,
    val orderNo: String? = null,
)

/**
 * M2「从服务器恢复账单」的结果（「我的」页回显用）。
 * [ok]=false 时 [error] 必有值，且 [restored] 恒为 0 —— 取不到就说取不到，绝不用 0 装作「服务器没单」。
 */
data class RestoreResult(
    val ok: Boolean,
    val restored: Int,
    val localTotal: Int,
    val error: String? = null,
)

data class CartLine(
    val barcode: String,
    val sku: String,
    val name: String,
    val spec: String,
    val unit: String,
    var qty: Int,
    var price: Double,
    val costPrice: Double,
    val costPriceSnap: Double? = null,
    /** B3 临时码（TMP- 前缀）：无码货购物车行携带，sku 为空。 */
    val tempCode: String? = null,
) {
    val subtotal: Double get() = price * qty
    val isCustom: Boolean get() = tempCode != null || barcode.startsWith("custom_")
}

enum class BillDelayLevel {
    NORMAL,   // 正常挂账 (<30min)
    WARNING,  // 滞留提醒 (30-120min)
    DANGER,   // 严重滞留 (>120min 或隔夜)
}

data class BillDelayInfo(
    val level: BillDelayLevel,
    val text: String,
)

object BillTime {
    fun now(): String = format(Date())
    fun format(d: Date): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(d)
    fun sameDay(iso: String, day: String): Boolean {
        // 排雷 M5（2026-09-09）：裸 startsWith 会把 "2026-09-09xxx"、"2026-9-9"
        // 等异常格式误判；两侧先归一化为日期再比较，解析失败回退前缀比较（不崩）。
        val a = normalizeDay(iso)
        val b = normalizeDay(day)
        return if (a != null && b != null) a == b else iso.startsWith(day)
    }

    /** 取前 10 位按 yyyy-MM-dd 严格解析并归一化；无法解析返回 null。 */
    private fun normalizeDay(s: String): String? = runCatching {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        sdf.isLenient = false
        sdf.format(sdf.parse(s.take(10)) ?: return null)
    }.getOrNull()
    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())

    /** 滞留变色口径：挂账单根据创建时间给出红橙绿等级与文本 */
    fun getDelayInfo(createdAt: String, status: String): BillDelayInfo? {
        if (status != BillEntity.STATUS_PENDING) return null
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            val created = sdf.parse(createdAt) ?: return null
            val now = Date()
            val diffMs = now.time - created.time
            val minutes = (diffMs / (60 * 1000)).coerceAtLeast(0)

            if (!sameDay(createdAt, today())) {
                BillDelayInfo(BillDelayLevel.DANGER, "隔夜未录")
            } else if (minutes < 30) {
                BillDelayInfo(BillDelayLevel.NORMAL, "挂账 ${minutes}m")
            } else if (minutes < 120) {
                BillDelayInfo(BillDelayLevel.WARNING, "滞留 ${minutes}m")
            } else {
                val hours = minutes / 60
                val remMin = minutes % 60
                BillDelayInfo(BillDelayLevel.DANGER, "严重滞留 ${hours}h${remMin}m")
            }
        } catch (_: Exception) {
            null
        }
    }
}

@Singleton
class BillRepo @Inject constructor(
    db: AppDatabase,
    private val api: retaildemoApi,
    private val config: AppConfig,
    private val appLogger: AppLogger,
) {
    private val billDao = db.billDao()
    private val customerDao = db.customerDao()
    private val tempProductDao = db.tempProductDao()
    private val gson = Gson()

    /** I31/B2：推送/回拉全程互斥——UI 即时同步、15 分钟 Worker、启动同步并发时推/拉会交错。 */
    private val syncMutex = Mutex()

    /** 本地已删除但尚未确认服务端已同步删除的账单 id 集合（防回拉复活）。 */
    internal val pendingDeletes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    val allBills: Flow<List<BillEntity>> = billDao.observeAll()
    val allCustomers: Flow<List<CustomerEntity>> = customerDao.observeAll()

    fun itemsOf(bill: BillEntity): List<BillItem> = itemsFromJson(bill.items)

    fun itemsFromJson(json: String): List<BillItem> = try {
        val type = object : TypeToken<List<BillItem>>() {}.type
        gson.fromJson(json, type) ?: emptyList()
    } catch (e: Exception) {
        // I31/B2：解析失败必须留痕——此前静默吞掉，坏账单无从发现
        appLogger.log("ERROR", "BillRepo", "账单 items JSON 解析失败（len=${json.length}）：${e.message}")
        emptyList()
    }

    /**
     * I31/B2：编辑路径专用的 items 解析守卫。
     * 解析失败时绝不能带着空列表继续「读改写」——那等于把整张账单的行清零。
     * 显示路径仍走 [itemsFromJson]（降级为空列表只是看不见，不产生数据破坏）。
     */
    private fun editableItems(bill: BillEntity): MutableList<BillItem>? {
        val parsed = itemsFromJson(bill.items)
        if (parsed.isEmpty() && bill.items.isNotBlank() && bill.items.trim() != "[]") {
            appLogger.log("ERROR", "BillRepo", "账单 ${bill.id} items JSON 疑似损坏，拒绝编辑写回（防清行）")
            return null
        }
        return parsed.toMutableList()
    }

    fun itemsToJson(items: List<BillItem>): String = gson.toJson(items)

    /** 生成账单 ID：B + 时间戳 + 两位随机，保证唯一可读。 */
    fun genBillId(): String = "B" + System.currentTimeMillis() + (0..99).random()

    /** 保存账单：status 固定 pending，needsSync=true 立即待推。payMethod 现结制三选一，可空；
     *  receiptPhoto=开单页顺手拍的凭证（B15，可空，漏拍进待补凭证清单）。 */
    suspend fun saveBill(
        customerName: String,
        lines: List<CartLine>,
        note: String,
        paidAmount: Double?,
        payMethod: String? = null,
        invoiceNeeded: Boolean = false,
        receiptPhoto: String? = null,
    ): BillEntity {
        val items = lines.map {
            BillItem(it.barcode, it.sku, it.name, it.spec, it.unit, it.qty, it.price, it.costPrice, it.costPriceSnap, tempCode = it.tempCode)
        }
        val total = items.sumOf { it.subtotal }
        val now = BillTime.now()
        val bill = BillEntity(
            id = genBillId(),
            customerName = customerName.ifBlank { "散客" },
            totalAmount = total,
            paidAmount = paidAmount,
            status = BillEntity.STATUS_PENDING,
            items = itemsToJson(items),
            note = note,
            staff = config.staffName,
            ownerId = config.staffId.takeIf { it.isNotBlank() },
            createdAt = now,
            updatedAt = now,
            paidAt = null,
            needsSync = true,
            payMethod = payMethod,
            invoiceNeeded = invoiceNeeded,
            receiptPhoto = receiptPhoto,
        )
        billDao.upsert(bill)
        return bill
    }

    /** 挂账↔已出库 切换；出库时必须满足：无缺失编号、已选收款方式、若为POS已传小票照片。 */
    suspend fun toggleStatus(billId: String): Boolean {
        val bill = billDao.getById(billId) ?: return false
        if (bill.status == BillEntity.STATUS_PENDING) {
            if (!canMarkPaid(bill)) return false
            billDao.updateStatus(billId, BillEntity.STATUS_PAID, BillTime.now(), BillTime.now())
        } else {
            billDao.updateStatus(billId, BillEntity.STATUS_PENDING, null, BillTime.now())
        }
        return true
    }

    /**
     * 账单真删除：
     * 1. 本地登记待删队列（防 sync 回拉瞬时复活）；
     * 2. 本地数据库物理删除；
     * 3. 立即向服务端发送删除请求，成功则移出待删队列，断网则留待下次 syncBills 重试。
     */
    suspend fun deleteBill(billId: String) {
        pendingDeletes.add(billId)
        billDao.deleteById(billId)
        try {
            val resp = api.deleteBill(mapOf("id" to billId))
            if (resp.isSuccessful && resp.body()?.success == true) {
                pendingDeletes.remove(billId)
            }
        } catch (e: Exception) {
            appLogger.log("WARN", "BillDelete", "删除账单 $billId 上报失败，等待下轮同步重试: ${e.message}")
        }
    }

    // ---------------- B7 打烊闭环：跨天回收站 ----------------

    /**
     * B7 跨天挂单自动进回收站：created_at 非当天且仍 pending 且未回收 → recycled=true。
     * status 不动（红线 4）；返回处理条数。APP 启动/账单页进入时调用。
     * B12 起**只回收挂账（stage=draft）**——待出库单"随时可以去出库"，跨天也要能被
     * 收银端领到，收进回收站等于把单藏起来（与插件端点去掉当天限制直接冲突）。
     */
    suspend fun markCrossDayRecycled(): Int {
        val today = BillTime.today()
        var n = 0
        val all = billDao.observeAll().first()
        android.util.Log.i("B7", "markCrossDay: today=$today bills=${all.size}")
        all.forEach {
            android.util.Log.i(
                "B7",
                "bill ${it.id}: status=${it.status} stage=${it.stage} recycled=${it.recycled} created=${it.createdAt} sameDay=${BillTime.sameDay(it.createdAt, today)}",
            )
        }
        all.filter {
            it.status == BillEntity.STATUS_PENDING &&
                it.stage == BillEntity.STAGE_DRAFT &&
                !it.recycled
        }
            .filter { !BillTime.sameDay(it.createdAt, today) }
            .forEach {
                android.util.Log.i("B7", "recycle ${it.id} created=${it.createdAt}")
                billDao.upsert(
                    it.copy(
                        recycled = true, recycledAt = BillTime.now(),
                        updatedAt = BillTime.now(), needsSync = true,
                    ),
                )
                n++
            }
        android.util.Log.i("B7", "markCrossDay done n=$n")
        return n
    }

    /** B7 回收站一键恢复：回当天待结算挂单（createdAt 刷新为当天，防再被判跨天）。 */
    suspend fun restore(billId: String) {
        val b = billDao.getById(billId) ?: return
        billDao.upsert(
            b.copy(
                recycled = false, recycledAt = null,
                createdAt = BillTime.now(), updatedAt = BillTime.now(),
                status = BillEntity.STATUS_PENDING, needsSync = true,
            ),
        )
    }

    // ---------------- B6 退货 = 负数单 ----------------

    /**
     * 最近销售记录（退货定位原单用）：查已出库(paid)且含该 sku/条码的正单，按时间倒序。
     * 排除退货单本身（direction=return 不是销售）。sku 与 barcode 任一命中即算（sku 空则靠条码兜底）。
     */
    suspend fun recentSales(sku: String, barcode: String, limit: Int = 20): List<BillEntity> {
        val idKeys = listOf(sku, barcode).filter { it.isNotBlank() }
        if (idKeys.isEmpty()) return emptyList()
        return billDao.observeAll().first()
            .filter { it.status == BillEntity.STATUS_PAID && it.direction != "return" }
            .filter { bill ->
                itemsFromJson(bill.items).any { it.sku in idKeys || it.barcode in idKeys }
            }
            .sortedByDescending { it.createdAt }
            .take(limit)
    }

    /**
     * 生成退货单（已出库退）：复制原单命中的商品行、数量取负，金额随负，status=paid 走日结扫码。
     * 未出库退走 removeItem/updateItem（不产生负数单）。返回 null=原单不存在/未命中该行/退货数量越界。
     * 红线：只加 direction=return 标记，status 仍仅 pending/paid 两值。
     */
    suspend fun createReturnBill(origId: String, sku: String, barcode: String, returnQty: Int): BillEntity? {
        val orig = billDao.getById(origId) ?: return null
        val idKeys = listOf(sku, barcode).filter { it.isNotBlank() }
        val src = itemsFromJson(orig.items).firstOrNull { it.sku in idKeys || it.barcode in idKeys } ?: return null
        if (returnQty < 1 || returnQty > src.qty) return null   // 数量越界拒绝（src.qty 为正销售量）
        val negItem = src.copy(
            qty = -returnQty,
            price = src.price,                                   // 单价保持正，subtotal=price*(-qty) 自然为负
            costPriceSnap = src.costPriceSnap ?: src.costPrice,  // 成本快照沿用原单（按原单价负冲）
            itemNote = "退货自 ${orig.id}",
        )
        val total = negItem.subtotal
        val now = BillTime.now()
        val bill = BillEntity(
            id = genBillId(),
            customerName = orig.customerName,
            totalAmount = total,
            paidAmount = total,
            status = BillEntity.STATUS_PAID,                     // 退货单默认已出库，走同样的日结扫码入管家婆
            items = itemsToJson(listOf(negItem)),
            note = "退货-${orig.id}",
            staff = config.staffName,
            ownerId = config.staffId.takeIf { it.isNotBlank() },
            createdAt = now,
            updatedAt = now,
            paidAt = now,
            needsSync = true,
            payMethod = orig.payMethod,
            direction = "return",
            refBillId = orig.id,
        )
        billDao.upsert(bill)
        return bill
    }

    /** 某原单被哪些退货单关联（原单详情反向链接）。 */
    suspend fun referencingBills(origId: String): List<BillEntity> =
        billDao.observeAll().first().filter { it.refBillId == origId }

    /** 编辑账单内商品：改数量/改价（保存后仍可修改，挂账期自由调整）。 */
    /** 删除账单内某行。 */
    suspend fun removeItem(billId: String, index: Int): Boolean {
        val bill = billDao.getById(billId) ?: return false
        val items = itemsFromJson(bill.items).toMutableList()
        if (index !in items.indices) return false
        items.removeAt(index)
        val total = items.sumOf { it.subtotal }
        billDao.upsert(
            bill.copy(items = itemsToJson(items), totalAmount = total, updatedAt = BillTime.now(), needsSync = true),
        )
        return true
    }

    /** 账单内继续加货（同条码已有则数量+1）。 */
    suspend fun addItem(billId: String, newItem: BillItem): Boolean {
        val bill = billDao.getById(billId) ?: return false
        val items = editableItems(bill) ?: return false
        val existing = items.indexOfFirst { it.barcode == newItem.barcode && it.barcode.isNotBlank() }
        if (existing >= 0) {
            items[existing] = items[existing].copy(qty = items[existing].qty + newItem.qty)
        } else {
            items.add(newItem)
        }
        val total = items.sumOf { it.subtotal }
        billDao.upsert(
            bill.copy(items = itemsToJson(items), totalAmount = total, updatedAt = BillTime.now(), needsSync = true),
        )
        return true
    }

    /** 实收/减免（null = 恢复全额）。 */
    suspend fun setPaidAmount(billId: String, amount: Double?): Boolean {
        val bill = billDao.getById(billId) ?: return false
        billDao.upsert(
            bill.copy(paidAmount = amount, updatedAt = BillTime.now(), needsSync = true),
        )
        return true
    }

    /** B2 小票照片：写入文件名（null=删除）到 receipt_photo，账单变更置 needsSync。 */
    suspend fun updateReceiptPhoto(billId: String, photoName: String?): Boolean {
        val bill = billDao.getById(billId) ?: return false
        billDao.upsert(
            bill.copy(receiptPhoto = photoName, updatedAt = BillTime.now(), needsSync = true),
        )
        return true
    }

    /** 更新账单收款方式与备注（现金/POS/备注例外切换），账单变更置 needsSync。 */
    suspend fun updatePayMethodAndNote(billId: String, payMethod: String?, note: String? = null): Boolean {
        val bill = billDao.getById(billId) ?: return false
        billDao.upsert(
            bill.copy(
                payMethod = payMethod,
                note = note ?: bill.note,
                updatedAt = BillTime.now(),
                needsSync = true,
            ),
        )
        return true
    }

    suspend fun updateItem(
        billId: String, index: Int, newQty: Int, newPrice: Double,
        newUnit: String? = null, newItemNote: String? = null,
        newSku: String? = null,
    ): Boolean {
        val bill = billDao.getById(billId) ?: return false
        val items = itemsFromJson(bill.items).toMutableList()
        if (index !in items.indices || newQty < 1 || newPrice < 0) return false
        val old = items[index]
        items[index] = old.copy(
            qty = newQty,
            price = newPrice,
            unit = newUnit?.takeIf { it.isNotBlank() } ?: old.unit,
            itemNote = if (newItemNote != null) newItemNote.trim().ifBlank { null } else old.itemNote,
            sku = if (newSku != null) newSku.trim() else old.sku,
            barcode = if (newSku != null && old.barcode.isBlank()) newSku.trim() else old.barcode,
        )
        val total = items.sumOf { it.subtotal }
        billDao.upsert(
            bill.copy(items = itemsToJson(items), totalAmount = total, updatedAt = BillTime.now(), needsSync = true),
        )
        return true
    }

    /** 检查账单中是否包含未填商品编号 (sku 为空) 的商品项。 */
    fun hasMissingSku(bill: BillEntity): Boolean =
        hasMissingSku(itemsFromJson(bill.items))

    /** 03 稿：管家婆逐条录入状态置位/撤销（点方块出码、扫枪录入后手动标记；不动总额）。 */
    suspend fun setItemRecorded(billId: String, index: Int, recorded: Boolean): Boolean {
        val bill = billDao.getById(billId) ?: return false
        val items = itemsFromJson(bill.items).toMutableList()
        if (index !in items.indices) return false
        items[index] = items[index].copy(recorded = recorded)
        billDao.upsert(bill.copy(items = itemsToJson(items), updatedAt = BillTime.now(), needsSync = true))
        return true
    }

    fun hasMissingSku(items: List<BillItem>): Boolean =
        items.any { it.sku.isBlank() }

    /**
     * 出库条件检查（老板 2026-09-07 铁律）：
     * 1. 账单内所有商品必须已补全商品编号 (sku 不为空)；
     * 2. 必须已选定收款方式（cash/pos/other）；
     * 3. 若收款方式为 POS，必须已上传小票照片 (receiptPhoto 不为空)。
     * 三者缺任一均不可标记已出库！
     */
    fun canMarkPaid(items: List<BillItem>, payMethod: String?, receiptPhoto: String?): Boolean {
        if (hasMissingSku(items)) return false
        if (payMethod.isNullOrBlank()) return false
        if (payMethod == BillEntity.PAY_METHOD_POS && receiptPhoto.isNullOrBlank()) return false
        return true
    }

    fun canMarkPaid(bill: BillEntity): Boolean =
        canMarkPaid(itemsFromJson(bill.items), bill.payMethod, bill.receiptPhoto)

    /**
     * 补全核销信息并标记出库（原子更新）：
     * 1. 回写 skuMap 中商品编号；
     * 2. 更新 payMethod 与 note（若有传入或继承）；
     * 3. 更新 receiptPhoto（若为 POS 必须有图）；
     * 4. 校验全部通过后置状态为 paid 并标记 needsSync。
     */
    suspend fun fulfillAndMarkPaid(
        billId: String,
        skuMap: Map<Int, String> = emptyMap(),
        payMethod: String? = null,
        note: String? = null,
        receiptPhoto: String? = null,
    ): Boolean {
        val bill = billDao.getById(billId) ?: return false
        val items = editableItems(bill) ?: return false
        for ((idx, sku) in skuMap) {
            if (idx in items.indices && sku.isNotBlank()) {
                val old = items[idx]
                val trimmed = sku.trim()
                items[idx] = old.copy(
                    sku = trimmed,
                    barcode = if (old.barcode.isBlank()) trimmed else old.barcode,
                )
            }
        }
        if (items.any { it.sku.isBlank() }) return false

        val finalPayMethod = payMethod?.takeIf { it.isNotBlank() } ?: bill.payMethod
        if (finalPayMethod.isNullOrBlank()) return false

        val finalPhoto = receiptPhoto?.takeIf { it.isNotBlank() } ?: bill.receiptPhoto
        if (finalPayMethod == BillEntity.PAY_METHOD_POS && finalPhoto.isNullOrBlank()) {
            return false
        }

        val now = BillTime.now()
        billDao.upsert(
            bill.copy(
                items = itemsToJson(items),
                payMethod = finalPayMethod,
                note = note ?: bill.note,
                receiptPhoto = finalPhoto,
                status = BillEntity.STATUS_PAID,
                paidAt = now,
                updatedAt = now,
                needsSync = true,
            ),
        )
        return true
    }

    /**
     * 补全商品编号并出库（向后兼容委托）
     */
    suspend fun fillSkusAndMarkPaid(billId: String, skuMap: Map<Int, String>): Boolean =
        fulfillAndMarkPaid(billId, skuMap)

    // ---------------- B12 单据两态：挂账(draft) → 待出库(ready) ----------------

    /** B12「标待出库」结果：ok=false 时 missing 必有值且点名缺哪件（给 UI 提示用）。 */
    data class MarkReadyResult(val ok: Boolean, val missing: String? = null)

    /**
     * B15 三件齐全性检查（出门硬闸，**凭证口径 2026-09-27 老板拍板放宽**）：
     * ① 串货标注——单内每条串货行（TMP- 临时码对应 type=strade 档案）在 temp_products
     *   里都有「在谁家串」来源，无临时码行视为满足；临时码查不到档案同样拦下
     *   （宁可多问一句，不放行来历不明的行）。
     *   ② 收款方式 payMethod 非空。
     * 凭证照片**不再硬拦**：柜台现结的普通单可跳过——没拍的 ready 单进「待补凭证」
     * 清单（[BillEntity.needsReceiptSupplement]），打烊前可见可补，不挡出门。
     * （此前"所有单都要凭证"的口径由老板 2026-09-27 明示放宽。）
     */
    fun missingForReady(bill: BillEntity, tempProducts: Map<String, TempProductEntity>): String? {
        for (item in itemsFromJson(bill.items)) {
            val code = item.tempCode?.takeIf { it.isNotBlank() } ?: continue
            val tp = tempProducts[code]
            if (tp != null && tp.type != "strade") continue // 自定义行不要求「在谁家串」
            if (tp == null) return "缺串货标注（${item.name}：临时档案缺失，先去串货台账补）"
            if (tp.sourceVendor.isNullOrBlank()) return "缺串货标注（${item.name}：还没写「在谁家串」）"
        }
        if (bill.payMethod.isNullOrBlank()) return "缺收款方式"
        return null
    }

    /** B12：为单据构建 临时码→临时档案 查找表（markReady 与详情页补全提示共用一份判据）。 */
    suspend fun tempLookupOf(bill: BillEntity): Map<String, TempProductEntity> {
        val lookup = HashMap<String, TempProductEntity>()
        for (item in itemsFromJson(bill.items)) {
            val code = item.tempCode?.takeIf { it.isNotBlank() } ?: continue
            if (code in lookup) continue
            tempProductDao.getByCode(code)?.let { lookup[code] = it }
        }
        return lookup
    }

    /**
     * B12「标待出库」：先过三件硬闸，缺一件拒绝并点名；全齐才置 stage=ready
     * （+updatedAt +needsSync，推送后网关插件端点才领得到）。status 不动（红线）。
     */
    suspend fun markReady(billId: String): MarkReadyResult {
        val bill = billDao.getById(billId) ?: return MarkReadyResult(false, "账单不存在")
        if (bill.status != BillEntity.STATUS_PENDING) {
            return MarkReadyResult(false, "已出库单不需要标待出库")
        }
        if (bill.recycled) return MarkReadyResult(false, "回收站里的单先恢复再标")
        val missing = missingForReady(bill, tempLookupOf(bill))
        if (missing != null) return MarkReadyResult(false, missing)
        billDao.upsert(
            bill.copy(
                stage = BillEntity.STAGE_READY,
                updatedAt = BillTime.now(),
                needsSync = true,
            ),
        )
        return MarkReadyResult(true)
    }

    suspend fun saveCustomer(name: String, phone: String): CustomerEntity {
        customerDao.getByName(name)?.let { return it }
        val c = CustomerEntity(
            id = "c" + System.currentTimeMillis(),
            name = name,
            phone = phone,
            createdAt = BillTime.now(),
        )
        customerDao.upsert(c)
        return c
    }

    suspend fun saveCustomerWithLimit(name: String, phone: String, creditLimit: Double) {
        val existing = customerDao.getByName(name)
        if (existing != null) {
            customerDao.upsert(existing.copy(phone = phone.ifBlank { existing.phone }, creditLimit = creditLimit))
        } else {
            customerDao.upsert(
                CustomerEntity(
                    id = "c" + System.currentTimeMillis(),
                    name = name,
                    phone = phone,
                    creditLimit = creditLimit,
                    createdAt = BillTime.now(),
                ),
            )
        }
    }

    suspend fun customerByName(name: String): CustomerEntity? = customerDao.getByName(name)

    suspend fun deleteCustomer(id: String) = customerDao.deleteById(id)

    /** S18 客户档案回拉结果：upserted=新建/刷新的上游行数，linked=按名关联回填的本地行数。 */
    data class CustomerSyncOutcome(val upserted: Int = 0, val linked: Int = 0)

    /**
     * S18：拉取服务器客户档案（管家婆基础信息只读同步）合并进本地 Room。失败静默返回空结果
     * （客户档案是增益数据，不阻塞记账）。合并纪律：
     * - 按 upstreamId upsert：已有关联 → 上游档案字段刷新（name 上游赢），本地 creditLimit 永不动；
     * - 本地同名客户（NOCASE）未关联 → 回填上游锚点「关联」，绝不插入重复行（账单按名引用零悬挂）；
     * - 纯新客户 → 新建（id="U"+upstreamId，pendingSync=false）；
     * - 本地客户上游已无 → 永不删除（删除是人工动作）；服务器空名脏行跳过（后端已原样留痕）。
     */
    suspend fun syncCustomersFromServer(): CustomerSyncOutcome {
        val resp = runCatching { api.syncCustomers() }.getOrNull() ?: return CustomerSyncOutcome()
        val body = resp.body()
        if (!resp.isSuccessful || body?.success != true) return CustomerSyncOutcome()
        var upserted = 0
        var linked = 0
        for (dto in body.data.orEmpty()) {
            if (dto.upstreamId.isBlank() || dto.name.isBlank()) continue
            val byId = customerDao.getByUpstreamId(dto.upstreamId)
            if (byId != null) {
                customerDao.upsert(
                    byId.copy(
                        name = dto.name,
                        phone = dto.phone.orEmpty().ifBlank { byId.phone },
                        upstreamCode = dto.code ?: byId.upstreamCode,
                        pinyin = dto.pinyin?.takeIf { it.isNotBlank() } ?: byId.pinyin,
                        contacts = dto.contacts?.takeIf { it.isNotBlank() } ?: byId.contacts,
                        category = dto.category?.takeIf { it.isNotBlank() } ?: byId.category,
                    ),
                )
                upserted++
                continue
            }
            val sameName = customerDao.getByName(dto.name)
            if (sameName != null) {
                customerDao.upsert(
                    sameName.copy(
                        phone = dto.phone.orEmpty().ifBlank { sameName.phone },
                        upstreamId = dto.upstreamId,
                        upstreamCode = dto.code,
                        pendingSync = false,
                        pinyin = dto.pinyin?.takeIf { it.isNotBlank() },
                        contacts = dto.contacts?.takeIf { it.isNotBlank() },
                        category = dto.category?.takeIf { it.isNotBlank() },
                    ),
                )
                linked++
            } else {
                customerDao.upsert(
                    CustomerEntity(
                        id = "U" + dto.upstreamId,
                        name = dto.name,
                        phone = dto.phone.orEmpty(),
                        createdAt = BillTime.now(),
                        pendingSync = false,
                        upstreamId = dto.upstreamId,
                        upstreamCode = dto.code,
                        pinyin = dto.pinyin?.takeIf { it.isNotBlank() },
                        contacts = dto.contacts?.takeIf { it.isNotBlank() },
                        category = dto.category?.takeIf { it.isNotBlank() },
                    ),
                )
                upserted++
            }
        }
        return CustomerSyncOutcome(upserted, linked)
    }

    /** S19：最近记账的客户名（按账单最近更新时间倒序，散客除外）——选客户弹窗快捷行。 */
    suspend fun recentCustomerNames(limit: Int = 5): List<String> =
        runCatching { billDao.recentCustomerNames(limit) }.getOrDefault(emptyList())

    /** B5 需开发票标记切换（详情页/记账页），账单变更置 needsSync。 */
    suspend fun setInvoiceNeeded(billId: String, needed: Boolean): Boolean {
        val bill = billDao.getById(billId) ?: return false
        billDao.upsert(bill.copy(invoiceNeeded = needed, updatedAt = BillTime.now(), needsSync = true))
        return true
    }

    /** I1 协同人（逗号分隔名单，null=清空）；主记账人 staff 不动，改账单纯本地标记不上行同步字段以外。 */
    suspend fun setCollaborators(billId: String, collaborators: String?): Boolean {
        val bill = billDao.getById(billId) ?: return false
        val cleaned = collaborators?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.joinToString(",")
        billDao.upsert(bill.copy(collaborators = cleaned?.ifBlank { null }, updatedAt = BillTime.now(), needsSync = true))
        return true
    }

    /**
     * B5 临时客户转正（上游客户接口不可用的降级形态）：管家婆建档后，人工把临时客户改成正式名。
     * ① 若正式名已存在另一客户 → 账单并入该客户、删除临时记录（无残留引用）；
     * ② 否则原地改名并置 pendingSync=false；③ 名字变化时把旧名账单的 customerName 一并切换。
     */
    suspend fun promoteCustomer(tempId: String, formalName: String): Boolean {
        val temp = customerDao.getById(tempId) ?: return false
        val newName = formalName.trim()
        if (newName.isBlank()) return false
        val oldName = temp.name
        val now = BillTime.now()
        val existing = customerDao.getByName(newName)
        if (existing != null && existing.id != tempId) {
            customerDao.upsert(existing.copy(pendingSync = false)) // 并入的正式名同样转正
            customerDao.deleteById(tempId)
        } else {
            customerDao.upsert(temp.copy(name = newName, pendingSync = false))
        }
        if (oldName != newName) {
            billDao.getByCustomer(oldName).forEach { b ->
                billDao.upsert(b.copy(customerName = newName, updatedAt = now, needsSync = true))
            }
        }
        return true
    }

    internal fun toSyncDto(bill: BillEntity): BillSyncDto = BillSyncDto(
        id = bill.id,
        type = if (bill.direction == "return") "return" else "sale",
        customer_name = bill.customerName,
        items = itemsFromJson(bill.items).map {
            mapOf(
                "barcode" to it.barcode, "sku" to it.sku, "productName" to it.name,
                "spec" to it.spec, "unit" to it.unit, "quantity" to it.qty,
                "price" to it.price, "cost_price" to it.costPrice,
                "cost_price_snap" to it.costPriceSnap,
                // 2026-09-20 同步往返补全（同 Dto.kt 里 cost_price_snap 那次）：这三个字段
                // 此前不上送，回拉时 fromServerDto 按服务端那份重建 items，会把它们洗成默认值。
                // recorded 被洗掉最要命——它是「这条已扫枪录进管家婆」的唯一凭据，
                // 洗掉后店员会再录一遍（重复出库）。temp_code 则是收银端插件建档后
                // 绑副码要用的键（见 docs/pos-addproduct-reverse.md）。
                "item_note" to it.itemNote,
                "temp_code" to it.tempCode,
                "recorded" to it.recorded,
            )
        },
        total = bill.totalAmount,
        paidAmount = bill.paidAmount,
        note = bill.note,
        status = bill.status,
        staff = bill.staff,
        owner_id = bill.ownerId,
        created_at = bill.createdAt,
        updated_at = bill.updatedAt,
        paid_at = bill.paidAt,
        pay_method = bill.payMethod,
        invoice_needed = bill.invoiceNeeded,
        collaborators = bill.collaborators,
        recycled = bill.recycled,
        recycled_at = bill.recycledAt,
        stage = bill.stage,
        bill_code = bill.billCode,
        order_no = bill.orderNo,
    )

    private fun fromServerDto(id: String, dto: ServerBillDto, local: BillEntity? = null): BillEntity? {
        if (dto.id.isNotBlank() && dto.id != id) return null
        val createdAt = dto.created_at ?: return null
        // 服务端那份可能是本次改动之前上传的（缺 item_note/temp_code/recorded 三键）。
        // 缺键时回落到本地同序号行，别拿默认值把「已扫枪录进管家婆」洗成未录入。
        val localItems = local?.let { itemsFromJson(it.items) } ?: emptyList()
        val items = dto.items.orEmpty().mapIndexed { i, it ->
            val prev = localItems.getOrNull(i)
            BillItem(
                barcode = it["barcode"] as? String ?: "",
                sku = it["sku"] as? String ?: "",
                name = (it["productName"] as? String) ?: (it["name"] as? String) ?: "未知",
                spec = it["spec"] as? String ?: "",
                unit = it["unit"] as? String ?: "",
                qty = ((it["quantity"] as? Double) ?: (it["qty"] as? Double))?.toInt() ?: 1,
                price = (it["price"] as? Double) ?: 0.0,
                costPrice = (it["cost_price"] as? Double) ?: 0.0,
                costPriceSnap = (it["cost_price_snap"] as? Double),
                itemNote = (it["item_note"] as? String) ?: prev?.itemNote,
                tempCode = (it["temp_code"] as? String) ?: prev?.tempCode,
                recorded = (it["recorded"] as? Boolean) ?: prev?.recorded ?: false,
            )
        }
        return BillEntity(
            id = dto.id.ifBlank { id },
            customerName = dto.customer_name ?: "散客",
            totalAmount = dto.total,
            paidAmount = dto.paidAmount,
            status = BillEntity.normalizeStatus(dto.status),
            items = itemsToJson(items),
            note = dto.note ?: "",
            staff = dto.staff ?: "",
            ownerId = dto.owner_id,
            createdAt = createdAt,
            updatedAt = dto.updated_at ?: createdAt,
            paidAt = dto.paid_at,
            needsSync = false,
            payMethod = dto.pay_method,
            direction = if (dto.type == "return") "return" else "sale",
            invoiceNeeded = dto.invoice_needed ?: false,
            collaborators = dto.collaborators,
            recycled = dto.recycled ?: false,
            recycledAt = dto.recycled_at,
            stage = BillEntity.normalizeStage(dto.stage),
            billCode = dto.bill_code ?: local?.billCode,
            orderNo = dto.order_no ?: local?.orderNo,
            // I31/B2：BillSyncDto/ServerBillDto 都不带 receipt_photo；REPLACE 整行覆盖会把它洗掉，
            // 被「其它设备时钟更快」的回拉打掉照片引用的单永远无法合规出库（POS 硬条件）。
            receiptPhoto = local?.receiptPhoto,
        )
    }

    /**
     * 双向同步：先推本地未同步账单，再拉服务端全量按 updatedAt last-writer-wins 合并。
     * 返回 是否全部成功。
     *
     * 2026-09-12 标记出库被回打修复（老板实测「标记出库后列表不显示账单营业额变动了」）：
     * ① 推送应答逐单核对——服务端 LWW 判 incoming 较旧（典型：两台写者时钟差，后台
     *    宿主机时钟快于手持端）会把它放 skipped，此时绝不能 markSynced，否则本地改动
     *    被当成已同步、从此不再重推；
     * ② 回拉不覆盖本地脏行（needsSync=1，如刚标记的出库）——否则被 skip 的单立刻被
     *    服务端旧版打回 pending，营业额变动随之消失；
     * ③ 回拉只在服务端**严格更新**时覆盖（相等也保留本地）——推送成功后的回拉会原样
     *    带回刚推的内容，再 upsert 一遍只会洗掉 DTO 未携带的字段。
     */
    suspend fun syncBills(): Boolean = syncMutex.withLock {
        var ok = true

        // 1. 先清缴本地待同步删除的账单（通知服务端物理删除）
        if (pendingDeletes.isNotEmpty()) {
            val toRemove = mutableListOf<String>()
            for (id in pendingDeletes) {
                try {
                    val resp = api.deleteBill(mapOf("id" to id))
                    if (resp.isSuccessful && resp.body()?.success == true) {
                        toRemove.add(id)
                    }
                } catch (_: Exception) { }
            }
            toRemove.forEach { pendingDeletes.remove(it) }
        }

        val unsynced = billDao.getUnsynced()
        if (unsynced.isNotEmpty()) {
            try {
                val resp = api.pushBills(BillPushRequest(unsynced.map { toSyncDto(it) }))
                val body = resp.body()
                if (resp.isSuccessful && body?.success == true) {
                    val accepted = body.updated
                    if (accepted == null) {
                        // 老网关不带逐单结果键：维持旧口径（成功即全部已同步）
                        unsynced.forEach { billDao.markSynced(it.id) }
                    } else {
                        val acceptedIds = accepted.toHashSet()
                        unsynced.forEach { if (it.id in acceptedIds) billDao.markSynced(it.id) }
                    }
                } else {
                    ok = false
                }
            } catch (e: Exception) {
                // I31/B2：同步失败必须留痕（服务器端 /api/logs/recent 可见），此前零日志无从排查
                appLogger.log("WARN", "BillSync", "账单推送失败（${unsynced.size} 单）：${e.message}")
                ok = false
            }
        }

        try {
            val resp = api.pullBills()
            if (resp.isSuccessful) {
                val remote = parseServerBillMap(resp.body())
                val locals = HashMap<String, BillEntity>()
                localSnapshot().forEach { locals[it.id] = it }
                remote.forEach { (id, dto) ->
                    // 待删防复活：本机已删除但服务端可能尚未清掉的单，坚决不回插本地
                    if (id in pendingDeletes) return@forEach
                    val local = locals[id]
                    val incoming = fromServerDto(id, dto, local)
                    if (incoming != null) {
                        val localKeeps = local != null &&
                            (local.needsSync || local.updatedAt >= incoming.updatedAt)
                        if (local == null || !localKeeps) {
                            billDao.upsert(incoming)
                        }
                    }
                }
                // 跨设备真删除对齐：本地已同步过的单据，若服务端全量列表中已无此单，本地同步移除
                locals.values.forEach { local ->
                    if (!local.needsSync && local.id !in remote && local.id !in pendingDeletes) {
                        billDao.deleteById(local.id)
                    }
                }
            } else {
                appLogger.log("WARN", "BillSync", "账单回拉失败：HTTP ${resp.code()}")
                ok = false
            }
        } catch (e: Exception) {
            appLogger.log("WARN", "BillSync", "账单回拉异常：${e.message}")
            ok = false
        }
        ok
    }

    /**
     * M2 从服务器恢复账单（重装/换机后的「救命按钮」）。
     *
     * 与 `syncBills` 的拉取段共用同一套 last-writer-wins 合并，但**回传条数**给 UI 回显，
     * 并严格区分两种情况——服务器真的没单（ok + remote=0） vs 服务器取不到
     * （!ok，且**绝不报 0 条**）：后者若装成「0 条」，店员会以为账真没了。
     * 配合服务端 GET /api/bills/sync 读库失败返回 503（见 src/gateway/handler.py）。
     */
    suspend fun restoreFromServer(): RestoreResult {
        val before = localSnapshot().size
        return try {
            val resp = api.pullBills()
            if (!resp.isSuccessful) {
                return RestoreResult(
                    ok = false, restored = 0, localTotal = before,
                    error = "服务器返回 HTTP ${resp.code()}",
                )
            }
            if (resp.body() == null) {
                // 200 但没有 body：多半是被中间层/登录页拦截，同样不可当成「服务器没单」
                return RestoreResult(false, 0, before, "服务器响应无法解析")
            }
            val remote = parseServerBillMap(resp.body())
            val locals = HashMap<String, BillEntity>()
            localSnapshot().forEach { locals[it.id] = it }
            remote.forEach { (id, dto) ->
                if (id in pendingDeletes) return@forEach
                val local = locals[id]
                val incoming = fromServerDto(id, dto, local) ?: return@forEach
                val localNewer = local != null && local.updatedAt > incoming.updatedAt
                if (local == null || !localNewer) billDao.upsert(incoming)
            }
            RestoreResult(true, remote.size, localSnapshot().size, null)
        } catch (e: Exception) {
            RestoreResult(false, 0, before, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * B13: 根据收银端结算回执，将匹配的待出库单自动转为已出库。
     * 1. 只有 status == pending 的单才会被更新（已出库单不重复处理）；
     * 2. status 变为 paid，paidAt = settleTime，billCode = billCode，orderNo = orderNo；
     * 3. needsSync = true（通知服务端更新）；
     * 4. 返回受影响的账单 id 列表。
     */
    suspend fun applyReceipts(receipts: List<com.retaildemo.v2.data.remote.SettlementReceiptDto>): List<String> {
        if (receipts.isEmpty()) return emptyList()
        val changed = mutableListOf<String>()
        val byAppId = receipts.groupBy { it.app_bill_id }
        for ((appBillId, recList) in byAppId) {
            val bill = billDao.getById(appBillId) ?: continue
            if (bill.status != BillEntity.STATUS_PENDING) continue
            val latest = recList.last()
            val now = BillTime.now()
            val settleTime = latest.settle_time.ifBlank { now }
            billDao.upsert(
                bill.copy(
                    status = BillEntity.STATUS_PAID,
                    paidAt = settleTime,
                    updatedAt = now,
                    billCode = latest.bill_code.ifBlank { null },
                    orderNo = latest.order_no.ifBlank { null },
                    needsSync = true,
                ),
            )
            changed.add(appBillId)
        }
        return changed
    }

    /** B13: 增量拉取收银端结算回执并应用。返回成功更新的单据数。 */
    suspend fun syncReceipts(since: String? = null): List<String> = runCatching {
        val resp = api.pullReceipts(since)
        if (resp.isSuccessful && resp.body()?.success == true) {
            val list = resp.body()?.data.orEmpty()
            applyReceipts(list)
        } else {
            emptyList()
        }
    }.getOrDefault(emptyList())

    /**
     * B13 实时链路（老板 2026-09-26 令「要实时马上就能更新」）：
     * 拉回执 → **有成交立刻把 paid 状态推上服务端**——否则服务端那份还是 pending，
     * 收银端插件列表里已结算的单会再挂十几分钟（可能被重复导入）。
     * 给 15s 前台轮询循环用；无成交时不做整表同步，保持轻。
     */
    suspend fun syncReceiptsAndPush(): List<String> {
        val changed = syncReceipts()
        if (changed.isNotEmpty()) syncBills()
        return changed
    }

    private suspend fun localSnapshot(): List<BillEntity> = allBills.first()
}

/**
 * 2026-09-11 老板实测容错：GET /bills/sync 是裸 map，但网关（历史/加固版）会在同一层塞
 * `_request_id` 之类的字符串元数据——按 Map<String, ServerBillDto> 强类型整体解析时，
 * Gson 解到字符串值直接抛 IllegalStateException: Expected BEGIN_OBJECT but was STRING，
 * 账单同步与重装恢复一起挂。改走 JsonElement 粗解析，只挑对象值逐条转换，
 * 元数据键自然跳过；单条损坏也只丢那一条，不再全包报废。
 */
internal fun parseServerBillMap(raw: Map<String, com.google.gson.JsonElement>?): Map<String, ServerBillDto> {
    if (raw.isNullOrEmpty()) return emptyMap()
    val g = Gson()
    val out = LinkedHashMap<String, ServerBillDto>()
    for ((id, el) in raw) {
        val obj = el as? com.google.gson.JsonObject ?: continue
        runCatching { g.fromJson(obj, ServerBillDto::class.java) }.getOrNull()?.let { out[id] = it }
    }
    return out
}
