package com.retaildemo.v2.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retaildemo.v2.data.local.dao.DraftDao
import com.retaildemo.v2.data.local.entity.BillDraftEntity
import com.retaildemo.v2.data.local.entity.BillEntity
import com.retaildemo.v2.data.local.entity.BillItem
import com.retaildemo.v2.data.local.entity.ProductEntity
import com.retaildemo.v2.data.repo.BillRepo
import com.retaildemo.v2.data.repo.CartLine
import com.retaildemo.v2.data.repo.ProductRepo
import com.retaildemo.v2.data.repo.TempFormValidator
import com.retaildemo.v2.data.repo.TempProductRepo
import com.retaildemo.v2.data.repo.uniqueProductKeys
import com.retaildemo.v2.ui.pages.extractDigitTokens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 一次性提示：带自增 id，同一句话连续出现两次也能再次发射（StateFlow 按 equals 去重）。 */
data class UiMessage(val text: String, val id: Int)

/** 文字识别候选：识别出的文字 + 本地模糊搜索到的候选商品（供店员点选入单）。 */
data class TextSearchResult(
    val query: String,
    val candidates: List<ProductEntity>,
)

/** 文字识别候选商品最多展示条数。 */
private const val MAX_TEXT_CANDIDATES = 20

/** 搜索结果条目：库内商品或在线补充。 */
data class SearchHit(
    /**
     * 列表行唯一键（[com.retaildemo.v2.data.repo.uniqueProductKeys] 生成）。
     * 不能拿 barcode/name 当 key：空条码 + 重名（「手套」两条）会撞 key 直接崩 Compose。
     */
    val key: String = "",
    val barcode: String,
    val sku: String,
    val name: String,
    val spec: String,
    val unit: String,
    val sellPrice: Double,
    val costPrice: Double,
    val marginPct: Int?, // 毛利百分比（无成本价时 null）
    /** S2 销售状态（frozen 显示"无库存记录"标；扫码加购路径为 null 不显示）。 */
    val saleStatus: String? = null,
)

/** 草稿横幅：一条暂存的开单草稿摘要。 */
data class DraftInfo(val customerName: String, val itemCount: Int, val savedAt: Long)

@HiltViewModel
class NewBillViewModel @Inject constructor(
    private val billRepo: BillRepo,
    private val productRepo: ProductRepo,
    private val draftDao: DraftDao,
    private val tempRepo: TempProductRepo,
) : ViewModel() {

    val customers = billRepo.allCustomers

    private val _customerName = MutableStateFlow("散客")
    val customerName: StateFlow<String> = _customerName.asStateFlow()

    /** 收款方式：cash(现金) / pos(POS) / other(备注例外)；开单时可不选，标记出库时必须强制选择。 */
    private val _payMethod = MutableStateFlow<String?>(null)
    val payMethod: StateFlow<String?> = _payMethod.asStateFlow()

    fun setPayMethod(v: String?) { _payMethod.value = v }

    /** B15 开单页顺手拍的凭证文件名（ReceiptPhotoStore 落盘后只存名；保存时随单落库）。 */
    private val _receiptPhoto = MutableStateFlow<String?>(null)
    val receiptPhoto: StateFlow<String?> = _receiptPhoto.asStateFlow()

    fun setReceiptPhoto(name: String?) { _receiptPhoto.value = name }

    /** 开单备注（当选择「备注」例外收款方式或需额外说明时填写）。 */
    private val _note = MutableStateFlow("")
    val note: StateFlow<String> = _note.asStateFlow()

    fun setNote(v: String) { _note.value = v }

    /** B5 需开发票标记（现结制只打标记，不做开票流程）。 */
    private val _invoiceNeeded = MutableStateFlow(false)
    val invoiceNeeded: StateFlow<Boolean> = _invoiceNeeded.asStateFlow()

    fun setInvoiceNeeded(v: Boolean) { _invoiceNeeded.value = v }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _results = MutableStateFlow<List<SearchHit>>(emptyList())
    val results: StateFlow<List<SearchHit>> = _results.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _cart = MutableStateFlow<List<CartLine>>(emptyList())
    val cart: StateFlow<List<CartLine>> = _cart.asStateFlow()

    // ── 文字识别 / 可并入挂账单 ──
    // 2026-09-20 老板令：**未识别条码区与副码绑定整条退役**（「非条码 SKU 不要录进来，忽略掉」）。
    // 扫码查不到库 → 直接忽略，不进任何队列、不提供补录入口。
    // 副码绑定能力本身没丢：查价页 SearchViewModel 与串货台账 StradeLedgerViewModel 仍在用。

    /** 文字识别 → 候选商品搜索结果（null=未搜索/已关闭）。 */
    private val _textSearch = MutableStateFlow<TextSearchResult?>(null)
    val textSearch: StateFlow<TextSearchResult?> = _textSearch.asStateFlow()

    /** 未结挂账单（供「并入」选择；排除回收站单）。 */
    val pendingBills: StateFlow<List<BillEntity>> = billRepo.allBills
        .map { list -> list.filter { it.status == BillEntity.STATUS_PENDING && !it.recycled } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), emptyList())

    private var toastSeq = 0
    private val _toast = MutableStateFlow<UiMessage?>(null)
    val toast: StateFlow<UiMessage?> = _toast.asStateFlow()

    private val _saved = MutableStateFlow<String?>(null)
    val saved: StateFlow<String?> = _saved.asStateFlow()

    /** 草稿横幅（null=无草稿）。 */
    private val _draftInfo = MutableStateFlow<DraftInfo?>(null)
    val draftInfo: StateFlow<DraftInfo?> = _draftInfo.asStateFlow()

    val totalAmount: Double get() = cart.value.sumOf { it.subtotal }
    val totalQty: Int get() = cart.value.sumOf { it.qty }

    private var searchJob: Job? = null

    fun setCustomer(name: String) {
        _customerName.value = name.ifBlank { "散客" }
    }

    /** S19：最近记账客户（选客户弹窗快捷行）；弹窗打开时刷新一次。 */
    private val _recentCustomerNames = MutableStateFlow<List<String>>(emptyList())
    val recentCustomerNames: StateFlow<List<String>> = _recentCustomerNames.asStateFlow()

    fun loadRecentCustomers() {
        viewModelScope.launch {
            _recentCustomerNames.value = billRepo.recentCustomerNames(5)
        }
    }

    /** 选择/新建客户：保存到本地客户表并选中，若欠款超信用额度当场提醒。 */
    fun pickCustomer(name: String) {
        viewModelScope.launch {
            val trimmed = name.trim()
            billRepo.saveCustomer(trimmed, "")
            setCustomer(trimmed)
            checkCredit(trimmed)
        }
    }

    /** 信用额度提醒：挂账欠款超过额度时提示（额度未设则不提醒）。 */
    private suspend fun checkCredit(name: String) {
        val cust = runCatching { billRepo.customerByName(name) }.getOrNull() ?: return
        val limit = cust.creditLimit
        if (limit <= 0.0) return
        val debt = billRepo.allBills.first()
            .filter {
                it.status == com.retaildemo.v2.data.local.entity.BillEntity.STATUS_PENDING &&
                    it.customerName.equals(name, ignoreCase = true)
            }
            .sumOf { it.totalAmount }
        if (debt > limit) {
            postToast("提醒：$name 欠款 ¥%.2f 已超信用额度 ¥%.2f".format(debt, limit))
        }
    }

    fun onQueryChange(q: String) {
        _searchQuery.value = q
        searchJob?.cancel()
        if (q.isBlank()) {
            _results.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            _searching.value = true
            // I18 统一搜索通道：与查价同一管道（服务器优先→本地兜底），废弃旧「本地优先」独立编排
            val res = productRepo.search(q)
            if (_searchQuery.value == q) {
                _results.value = res.results.toHits()
            }
            _searching.value = false
        }
    }

    init {
        // 商品详情「记一笔」直达：预加该商品入购物车
        // 草稿横幅：进入页面先看有没有上次暂存
        viewModelScope.launch { refreshDraftInfo() }
        com.retaildemo.v2.data.repo.PendingProductHolder.consume()?.let { preset ->
            onBarcodeScanned(preset)
        }
    }

    /**
     * 扫码结果统一入口（2026-09-20 老板令改版）。
     *
     * 三条新口径（原「同码重复扫=数量+1 / 查不到进未识别区」已废）：
     * 1. **重复扫到已在单里的码 → 不自动 +1**。加数量一律到清单里手动改。
     *    动因：扫码太灵敏，一堆货摆一起会把相邻的码一起扫进来，自动累加等于放大误扫。
     * 2. **库里查不到的码 → 直接忽略**，不进未识别区、不提供补录入口。
     * 3. 只有真正新命中的商品才入单。
     */
    fun onBarcodeScanned(barcode: String) {
        scanBatch(listOf(barcode))
    }

    /**
     * 批量入单（连续扫码一次回传一串码）。
     *
     * @param saveAfterScan 交接台老板令（2026-09-26）：「**确定 · 保存账单**」按下去要**一步到底**——
     *   入完单**紧接着就把账单存了**，不再让店员回记账页再点一次「保存挂账」。
     *
     *   ⚠️ 必须**在入单协程末尾**调 [saveBill]，不能在外层紧接着调：
     *   `getByBarcode` 是挂起查询，外层此刻拿到的是**还没入单的空货筐** → `saveBill` 会因
     *   `lines.isEmpty()` 直接 return，账单静默丢掉（看着像「按了确定没反应」）。
     */
    fun scanBatch(codes: List<String>, saveAfterScan: Boolean = false) {
        val cleaned = codes.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return
        viewModelScope.launch {
            var added = 0
            var dup = 0
            var missed = 0
            var lastAddedName = ""
            cleaned.forEach { barcode ->
                // 已在单里 → 只计数，**不改数量**（加货靠手动改清单）
                if (_cart.value.any { it.barcode == barcode && it.barcode.isNotBlank() }) {
                    dup++
                    return@forEach
                }
                val product = productRepo.getByBarcode(barcode)
                if (product != null) {
                    addToCart(product.toHit())
                    lastAddedName = product.name
                    added++
                } else {
                    missed++          // 库里没有 → 忽略（不落任何队列）
                }
            }
            postToast(
                when {
                    cleaned.size == 1 && added == 1 -> "已加入：$lastAddedName"
                    cleaned.size == 1 && dup == 1 ->
                        "${cleaned.first()} 已在单里，未重复计入；要加数量请点清单里那一行"
                    cleaned.size == 1 && missed == 1 ->
                        "${cleaned.first()} 不在商品库里，已忽略"
                    else -> buildString {
                        append("入单 $added 件")
                        if (dup > 0) append("；$dup 件已在单里未重复计入")
                        if (missed > 0) append("；$missed 个码不在库里已忽略")
                    }
                },
            )
            // 交接台「确定 · 保存账单」：入完单就地落存（空货筐时 saveBill 自己会 return）
            if (saveAfterScan) saveBill()
        }
    }

    /** 文字识别结果 → 优先数字精确匹配，数字匹配不到再降级整段文字模糊搜索。 */
    fun searchByText(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            // 1. 优先：提取连续数字串，逐个精确查条码/SKU（手写码命中率高、快，且比手写中文可靠）
            val exact = extractDigitTokens(q)
                .firstNotNullOfOrNull { token -> productRepo.getByBarcode(token) }
            if (exact != null) {
                _textSearch.value = TextSearchResult(q, listOf(exact))
                return@launch
            }
            // 2. 降级：数字匹配不到（无数字或数字非条码），再整段文字模糊搜索
            val candidates = productRepo.searchLocal(q).take(MAX_TEXT_CANDIDATES)
            _textSearch.value = TextSearchResult(q, candidates)
        }
    }

    fun clearTextSearch() {
        _textSearch.value = null
    }

    /** 店员点选候选商品（文字识别路径）→ 直接入单。 */
    fun addProduct(product: ProductEntity) {
        addToCart(product.toHit())
    }

    /** 所扫商品并入已有挂账单（CartLine 全字段透传，同码在 repo 内自动合并数量）。 */
    fun mergeInto(billId: String) {
        val lines = _cart.value
        if (lines.isEmpty()) return
        viewModelScope.launch {
            lines.forEach { line ->
                billRepo.addItem(
                    billId,
                    BillItem(
                        line.barcode, line.sku, line.name, line.spec, line.unit,
                        line.qty, line.price, line.costPrice, line.costPriceSnap,
                        tempCode = line.tempCode,
                    ),
                )
            }
            billRepo.syncBills()
            _cart.value = emptyList()
            draftDao.clear()
            _draftInfo.value = null
            postToast("已并入账单 ✓")
        }
    }

    fun addToCart(hit: SearchHit) {
        _cart.value = _cart.value.toMutableList().apply {
            val idx = indexOfFirst { it.barcode == hit.barcode && it.barcode.isNotBlank() }
            if (idx >= 0) {
                val line = this[idx]
                this[idx] = line.copy(qty = line.qty + 1)
            } else {
                add(
                    CartLine(
                        barcode = hit.barcode, sku = hit.sku, name = hit.name,
                        spec = hit.spec, unit = hit.unit.ifBlank { "件" },
                        qty = 1, price = hit.sellPrice, costPrice = hit.costPrice,
                        costPriceSnap = hit.costPrice,
                    ),
                )
            }
        }
        _searchQuery.value = ""
        _results.value = emptyList()
    }

    /**
     * 无码货双通道（B3）：custom=店内无账无码杂件；strade=从别家临时拿货（垫钱）。
     * 串货五项必填：规格型号/售价/在谁家串/串货价/凭证（2026-09-10 老板口径）；编号选填出库前补。
     * 先校验，再落 temp_products 拿 TMP 码。返回 null=已入购物车，否则错误文案（弹窗不关防丢输入）。
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
        val err = TempFormValidator.validate(type, name, spec ?: "", priceText, sourceVendor, stradePriceText, voucherPhoto)
        if (err != null) { postToast(err); return err }
        if (qty < 1) { postToast("数量至少填 1"); return "数量至少填 1" }
        val price = priceText.toDoubleOrNull() ?: 0.0
        val costVal = if (type == "strade") stradePriceText.toDoubleOrNull() else costText?.toDoubleOrNull()
        viewModelScope.launch {
            val tp = tempRepo.add(
                type = type, name = name, spec = spec, sellPrice = price,
                costPrice = costVal, note = null,
                sourceVendor = sourceVendor.ifBlank { null },
                stradePrice = stradePriceText.toDoubleOrNull(),
                voucherPhoto = voucherPhoto,
            )
            val assignedSku = sku?.trim().orEmpty()
            _cart.value = _cart.value + CartLine(
                barcode = if (assignedSku.isNotBlank()) assignedSku else "",
                sku = assignedSku,
                name = name.trim(),
                spec = spec?.takeIf { it.isNotBlank() } ?: if (type == "strade") "串货" else "自定义",
                unit = "件", qty = qty, price = price,
                costPrice = costVal ?: 0.0, costPriceSnap = costVal,
                tempCode = tp.tempCode,
            )
            _searchQuery.value = ""
            _results.value = emptyList()
        }
        return null
    }

    fun updateQty(index: Int, newQty: Int) {
        if (newQty < 1) return
        _cart.value = _cart.value.toMutableList().apply {
            if (index in indices) this[index] = this[index].copy(qty = newQty)
        }
    }

    fun updatePrice(index: Int, newPrice: Double) {
        if (newPrice < 0) return
        _cart.value = _cart.value.toMutableList().apply {
            if (index in indices) this[index] = this[index].copy(price = newPrice)
        }
    }

    fun removeAt(index: Int) {
        _cart.value = _cart.value.toMutableList().apply { if (index in indices) removeAt(index) }
    }

    // ── 草稿暂存（记一笔退出自动存，仅一条最新草稿）──

    private fun refreshDraftInfo() {
        viewModelScope.launch {
            _draftInfo.value = draftDao.get()?.let {
                DraftInfo(it.customerName, parseLines(it.items).size, it.savedAt)
            }
        }
    }

    /** 退出页面时暂存当前开单内容（购物车为空则忽略）。 */
    fun saveDraft() {
        val lines = _cart.value
        if (lines.isEmpty()) return
        viewModelScope.launch {
            val at = System.currentTimeMillis()
            draftDao.save(
                BillDraftEntity(
                    customerName = _customerName.value,
                    items = linesToJson(lines),
                    savedAt = at,
                ),
            )
            _draftInfo.value = DraftInfo(_customerName.value, lines.size, at)
            postToast("已暂存草稿，下次进来可恢复")
        }
    }

    /** 恢复草稿到当前开单，并清掉草稿记录。 */
    fun restoreDraft() {
        viewModelScope.launch {
            val d = draftDao.get() ?: return@launch
            val lines = parseLines(d.items)
            if (lines.isNotEmpty()) {
                _customerName.value = d.customerName.ifBlank { "散客" }
                _cart.value = lines
            }
            draftDao.clear()
            _draftInfo.value = null
            postToast(if (lines.isEmpty()) "草稿已失效，已清除" else "已恢复草稿")
        }
    }

    /** 放弃草稿。 */
    fun discardDraft() {
        viewModelScope.launch {
            draftDao.clear()
            _draftInfo.value = null
        }
    }

    /** 保存账单；[readyAfterSave]=true 时走「收款 · 出库」——保存后立即过三件硬闸标待出库
     *  （B15 双按钮，2026-09-27 老板拍板）。出库被拦（缺串货标注/收款方式）时单据
     *  **仍然保存为挂账**，通过 [onResult] 把结果交 UI 提示，绝不静默丢单。 */
    fun saveBill(readyAfterSave: Boolean = false, onResult: ((Boolean, String?) -> Unit)? = null) {
        val lines = _cart.value
        if (lines.isEmpty()) return
        viewModelScope.launch {
            val bill = billRepo.saveBill(
                customerName = _customerName.value,
                lines = lines,
                note = _note.value.trim(),
                paidAmount = null,
                payMethod = _payMethod.value,
                invoiceNeeded = _invoiceNeeded.value,
                receiptPhoto = _receiptPhoto.value,
            )
            _cart.value = emptyList()
            draftDao.clear()
            _draftInfo.value = null
            _payMethod.value = null
            _note.value = ""
            _invoiceNeeded.value = false
            _receiptPhoto.value = null
            if (readyAfterSave) {
                val r = billRepo.markReady(bill.id)
                billRepo.syncBills()
                _saved.value = bill.id
                onResult?.invoke(r.ok, r.missing)
            } else {
                _saved.value = bill.id
                billRepo.syncBills()
                onResult?.invoke(true, null)
            }
        }
    }

    fun consumeSaved() { _saved.value = null }

    fun clearToast() { _toast.value = null }

    private fun postToast(text: String) {
        _toast.value = UiMessage(text, ++toastSeq)
    }

    /**
     * 批量映射搜索结果：逐行算唯一 key。
     * 空条码重名货（如「手套」两条）会让 LazyColumn key 撞车崩掉整页，见 [SearchHit.key]。
     */
    private fun List<ProductEntity>.toHits(): List<SearchHit> {
        val keys = uniqueProductKeys(this)
        return mapIndexed { i, p -> p.toHit(keys[i]) }
    }

    private fun ProductEntity.toHit(key: String = "") = SearchHit(
        key = key,
        barcode = barcode ?: "",
        sku = sku ?: "",
        name = name,
        spec = spec ?: "",
        unit = unit ?: "件",
        sellPrice = sellPrice,
        costPrice = costPrice,
        marginPct = marginOf(sellPrice, costPrice),
        saleStatus = saleStatus,
    )

    private fun marginOf(sell: Double, cost: Double): Int? {
        if (cost <= 0.0 || sell <= 0.0) return null
        return (((sell - cost) / sell) * 100).toInt()
    }

    private fun linesToJson(lines: List<CartLine>): String =
        org.json.JSONArray().apply {
            lines.forEach { l ->
                put(
                    org.json.JSONObject()
                        .put("barcode", l.barcode)
                        .put("sku", l.sku)
                        .put("name", l.name)
                        .put("spec", l.spec)
                        .put("unit", l.unit)
                        .put("qty", l.qty)
                        .put("price", l.price)
                        .put("costPrice", l.costPrice)
                        .put("costPriceSnap", l.costPriceSnap ?: org.json.JSONObject.NULL)
                        .put("tempCode", l.tempCode ?: org.json.JSONObject.NULL),
                )
            }
        }.toString()

    private fun parseLines(json: String): List<CartLine> = runCatching {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CartLine(
                barcode = o.optString("barcode"),
                sku = o.optString("sku"),
                name = o.optString("name"),
                spec = o.optString("spec"),
                unit = o.optString("unit"),
                qty = o.optInt("qty", 1),
                price = o.optDouble("price", 0.0),
                costPrice = o.optDouble("costPrice", 0.0),
                costPriceSnap = if (o.isNull("costPriceSnap")) null else o.optDouble("costPriceSnap"),
                tempCode = if (o.isNull("tempCode")) null else o.optString("tempCode"),
            )
        }
    }.getOrDefault(emptyList())
}
