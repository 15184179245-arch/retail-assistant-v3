package com.retaildemo.v2.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retaildemo.v2.data.repo.BillRepo
import com.retaildemo.v2.data.local.entity.BillEntity
import com.retaildemo.v2.data.local.entity.BillItem
import com.retaildemo.v2.data.local.entity.CustomerEntity
import com.retaildemo.v2.data.local.entity.ProductEntity
import com.retaildemo.v2.data.repo.ProductRepo
import com.retaildemo.v2.data.repo.BillDetailUi
import com.retaildemo.v2.data.repo.TempFormValidator
import com.retaildemo.v2.data.repo.TempProductRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BillDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: BillRepo,
    private val productRepo: ProductRepo,
    private val tempRepo: TempProductRepo,
    private val config: com.retaildemo.v2.data.remote.AppConfig,
    private val api: com.retaildemo.v2.data.remote.retaildemoApi,
) : ViewModel() {

    val billId: String = savedStateHandle["billId"] ?: ""

    /** B4: 临时码→正式码解析（只读网关别名表）；未绑定/网络异常返回 null（回落显示临时码）。 */
    suspend fun aliasOf(tempCode: String): com.retaildemo.v2.data.remote.AliasResolveDto? =
        try {
            api.aliasResolve(tempCode).body()?.data
        } catch (e: Exception) {
            null
        }

    val bill: StateFlow<BillDetailUi?> = repo.allBills
        .map { list -> list.firstOrNull { it.id == billId } }
        .map { entity ->
            entity?.let {
                BillDetailUi(
                    id = it.id,
                    customerName = it.customerName,
                    totalAmount = it.totalAmount,
                    paidAmount = it.paidAmount,
                    status = it.status,
                    items = repo.itemsOf(it),
                    createdAt = it.createdAt,
                    payMethod = it.payMethod,
                    receiptPhoto = it.receiptPhoto,
                    invoiceNeeded = it.invoiceNeeded,
                    note = it.note,
                    direction = it.direction,
                    refBillId = it.refBillId,
                    staff = it.staff,
                    collaborators = it.collaborators,
                    stage = BillEntity.normalizeStage(it.stage),
                    billCode = it.billCode,
                    orderNo = it.orderNo,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), null)

    /** 更新收款方式与备注（现金/POS/备注例外切换）。 */
    fun updatePayMethod(payMethod: String?, note: String? = null) {
        viewModelScope.launch { repo.updatePayMethodAndNote(billId, payMethod, note) }
    }

    /** B6 原单的被退记录：refBillId==本单 的退货单（正向：退货单详情用 refBillId 跳原单）。 */
    val referencedBills: StateFlow<List<BillEntity>> = repo.allBills
        .map { list -> list.filter { it.refBillId == billId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), emptyList())

    fun updateItem(index: Int, qty: Int, price: Double, unit: String? = null, itemNote: String? = null, sku: String? = null) {
        viewModelScope.launch { repo.updateItem(billId, index, qty, price, unit, itemNote, sku) }
    }

    fun deleteItem(index: Int) {
        viewModelScope.launch { repo.removeItem(billId, index) }
    }

    /** 03/04 稿：管家婆逐条录入状态置位/撤销（本地状态，不产生上游写、不影响总额）。
     *  04 稿行为：最后一件标记后若满足出库校验（编号/收款/POS 小票），自动标记已出库并回调。 */
    fun setItemRecorded(index: Int, recorded: Boolean, onAllRecorded: (() -> Unit)? = null) {
        viewModelScope.launch {
            val ok = repo.setItemRecorded(billId, index, recorded)
            if (!ok) return@launch
            val entity = repo.allBills.first().firstOrNull { it.id == billId } ?: return@launch
            val items = repo.itemsOf(entity)
            if (entity.status == BillEntity.STATUS_PENDING && items.isNotEmpty() && items.all { it.recorded }) {
                if (repo.canMarkPaid(items, entity.payMethod, entity.receiptPhoto)) {
                    repo.toggleStatus(billId)
                    repo.syncBills()
                    onAllRecorded?.invoke()
                }
            }
        }
    }

    /** 03 稿：该客户其余挂账单合计（累计欠款口径=挂账未出库；不含本单本额）。 */
    val customerDebt: StateFlow<Double> =
        kotlinx.coroutines.flow.combine(repo.allBills, bill) { list, b ->
            if (b == null) 0.0
            else list
                .filter {
                    it.customerName == b.customerName &&
                        it.status == BillEntity.STATUS_PENDING &&
                        it.id != b.id
                }
                .sumOf { it.totalAmount }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), 0.0)

    /** 03 稿：客户档案（电话/信用额度）——按客户名取本机档案；散客等无名客户为 null。 */
    val customer: StateFlow<CustomerEntity?> = kotlinx.coroutines.flow.combine(bill, repo.allCustomers) { b, customers ->
        b?.let { cur -> customers.firstOrNull { it.name == cur.customerName } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), null)

    /** 03 稿：上次拿货 = 本客户上一笔账单日期（不含本单）。 */
    val lastVisit: StateFlow<String?> = kotlinx.coroutines.flow.combine(repo.allBills, bill) { list, b ->
        if (b == null) null
        else list
            .filter { it.customerName == b.customerName && it.id != b.id }
            .maxByOrNull { it.createdAt }
            ?.createdAt?.take(10)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), null)

    private val _movements = kotlinx.coroutines.flow.MutableStateFlow<List<com.retaildemo.v2.data.remote.MovementDto>>(emptyList())
    val movements: StateFlow<List<com.retaildemo.v2.data.remote.MovementDto>> = _movements

    /** 03 稿：本单首个未录商品的最近出入库流水（库存服务 movements；失败静默为空）。 */
    fun loadMovements() {
        viewModelScope.launch {
            val b = bill.value ?: return@launch
            val target = b.items.firstOrNull { !it.recorded } ?: b.items.firstOrNull() ?: return@launch
            val key = target.barcode.ifBlank { target.sku }
            if (key.isBlank()) {
                _movements.value = emptyList()
                return@launch
            }
            val product = runCatching { productRepo.getByBarcode(key) }.getOrNull()
            val pid = product?.id ?: return@launch
            _movements.value = runCatching {
                api.movements(limit = 5, productId = pid).body()?.data.orEmpty()
            }.getOrDefault(emptyList())
        }
    }

    fun setPaidAmount(amount: Double?) {
        viewModelScope.launch { repo.setPaidAmount(billId, amount) }
    }

    /** B2 小票照片：存好后回写文件名（UI 层已用 ReceiptPhotoStore 落盘）；null=删除。 */
    fun setReceiptPhoto(name: String?) {
        viewModelScope.launch { repo.updateReceiptPhoto(billId, name) }
    }

    /** B5 需开发票标记切换。 */
    fun setInvoiceNeeded(needed: Boolean) {
        viewModelScope.launch { repo.setInvoiceNeeded(billId, needed) }
    }

    /** I1 协同人候选名单（本机店员名单）。 */
    val staffList: List<String> get() = config.staffList.sorted()

    /** I1 设置协同人（逗号分隔；null/空=清空），主记账人 staff 不动。 */
    fun setCollaborators(names: List<String>) {
        viewModelScope.launch { repo.setCollaborators(billId, names.joinToString(",")) }
    }

    /** 搜索店内商品供继续加货选择（I18：与查价/开单同一统一通道，服务器优先→本地兜底） */
    suspend fun searchProducts(query: String): List<ProductEntity> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        return productRepo.search(trimmed).results
    }

    /** 往账单添加一行商品（店内搜到 / 串货 / 无码自定义） */
    fun addItem(item: BillItem) {
        viewModelScope.launch {
            repo.addItem(billId, item)
            repo.syncBills()
        }
    }

    /** 从店内商品搜索直接加入账单 */
    fun addProduct(product: ProductEntity, qty: Int = 1) {
        viewModelScope.launch {
            repo.addItem(
                billId,
                BillItem(
                    barcode = product.barcode ?: "",
                    sku = product.sku ?: "",
                    name = product.name,
                    spec = product.spec ?: "",
                    unit = product.unit ?: "件",
                    qty = qty,
                    price = product.sellPrice,
                    costPrice = product.costPrice,
                    costPriceSnap = product.costPrice,
                ),
            )
            repo.syncBills()
        }
    }

    /**
     * 继续加货 - 串货/店内无码货双通道（B3）：
     * 串货五项必填（规格/售价/在谁家串/串货价/凭证，2026-09-10 老板口径）；编号不必填，出库前补齐拦截。
     */
    fun addNoCodeItem(
        type: String,
        name: String,
        spec: String?,
        priceText: String,
        qty: Int,
        costText: String?,
        sourceVendor: String,
        stradePriceText: String,
        voucherPhoto: String?,
        sku: String? = null,
    ): String? {
        val err = TempFormValidator.validate(type, name, spec.orEmpty(), priceText, sourceVendor, stradePriceText, voucherPhoto)
        if (err != null) return err
        if (qty < 1) return "数量至少填 1"
        val price = priceText.toDoubleOrNull() ?: 0.0
        val costVal = if (type == "strade") stradePriceText.toDoubleOrNull() else costText?.toDoubleOrNull()
        viewModelScope.launch {
            val tp = tempRepo.add(
                type = type,
                name = name,
                spec = spec,
                sellPrice = price,
                costPrice = costVal,
                note = null,
                sourceVendor = sourceVendor.ifBlank { null },
                stradePrice = stradePriceText.toDoubleOrNull(),
                voucherPhoto = voucherPhoto,
            )
            val assignedSku = sku?.trim().orEmpty()
            repo.addItem(
                billId,
                BillItem(
                    barcode = if (assignedSku.isNotBlank()) assignedSku else "",
                    sku = assignedSku,
                    name = name.trim(),
                    spec = spec?.takeIf { it.isNotBlank() } ?: if (type == "strade") "串货" else "自定义",
                    unit = "件",
                    qty = qty,
                    price = price,
                    costPrice = costVal ?: 0.0,
                    costPriceSnap = costVal,
                    tempCode = tp.tempCode,
                ),
            )
            repo.syncBills()
        }
        return null
    }

    /** 扫码加货（账单内继续加商品）。 */
    fun addByBarcode(barcode: String, onMiss: (String) -> Unit = {}) {
        viewModelScope.launch {
            val product: ProductEntity? = productRepo.getByBarcode(barcode)
            if (product != null) {
                repo.addItem(
                    billId,
                    BillItem(
                        barcode = product.barcode ?: "",
                        sku = product.sku ?: "",
                        name = product.name,
                        spec = product.spec ?: "",
                        unit = product.unit ?: "件",
                        qty = 1,
                        price = product.sellPrice,
                        costPrice = product.costPrice,
                        costPriceSnap = product.costPrice,
                    ),
                )
                repo.syncBills()
            } else {
                onMiss(barcode)
            }
        }
    }

    fun buildShareText(): String {
        val b = bill.value ?: return ""
        return buildString {
            append("【账单】${b.customerName}  ${b.createdAt.take(10)}\n")
            b.items.forEach { item ->
                append("${item.name} ${item.qty}×¥%.2f = ¥%.2f\n".format(item.price, item.subtotal))
            }
            append("合计：¥%.2f\n".format(b.totalAmount))
            b.paidAmount?.let { append("实收：¥%.2f\n".format(it)) }
        }
    }

    /** 出库前核销校验（无缺失编号 + 已选收款方式 + 若为POS必有小票照片） */
    fun canMarkPaid(b: BillDetailUi): Boolean =
        repo.canMarkPaid(b.items, b.payMethod, b.receiptPhoto)

    fun toggleStatus() {
        viewModelScope.launch {
            repo.toggleStatus(billId)
            repo.syncBills()
        }
    }

    // ---------------- B12 单据两态：补全三件 → 标待出库 ----------------

    /**
     * B12 三件齐全性实时判据（详情页「补全三件」卡用）：返回 null=齐；否则缺哪件的人话说明。
     * UI 以 bill 的关键字段（stage/items/payMethod/receiptPhoto）作 key 调用，值变即重算。
     */
    suspend fun missingForReady(): String? {
        val entity = repo.allBills.first().firstOrNull { it.id == billId } ?: return "账单不存在"
        if (entity.status != BillEntity.STATUS_PENDING) return null
        return repo.missingForReady(entity, repo.tempLookupOf(entity))
    }

    /** B12「标待出库」：三件硬闸在 repo；成功后立即推送（插件按 stage=ready 领单）。 */
    fun markReady(onDone: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val r = repo.markReady(billId)
            if (r.ok) repo.syncBills()
            onDone(r.ok, r.missing)
        }
    }

    /** 补全商品编号并完成出库 */
    fun fillSkusAndMarkPaid(skuMap: Map<Int, String>, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = repo.fillSkusAndMarkPaid(billId, skuMap)
            if (ok) repo.syncBills()
            onDone(ok)
        }
    }

    /** 补全核销信息（编号/收款方式/备注/小票）并标记出库（原子更新） */
    fun fulfillAndMarkPaid(
        skuMap: Map<Int, String> = emptyMap(),
        payMethod: String? = null,
        note: String? = null,
        receiptPhoto: String? = null,
        onDone: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            val ok = repo.fulfillAndMarkPaid(billId, skuMap, payMethod, note, receiptPhoto)
            if (ok) repo.syncBills()
            onDone(ok)
        }
    }
}
