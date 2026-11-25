package com.retaildemo.v2.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retaildemo.v2.data.local.CategoryTree
import com.retaildemo.v2.data.local.dao.SearchHistoryDao
import com.retaildemo.v2.data.local.entity.ProductEntity
import com.retaildemo.v2.data.local.entity.ProductSupplementEntity
import com.retaildemo.v2.data.local.entity.SearchHistoryEntity
import com.retaildemo.v2.data.remote.DisambiguationHint
import com.retaildemo.v2.data.remote.ProductLocationDto
import com.retaildemo.v2.data.repo.BillRepo
import com.retaildemo.v2.data.repo.SaleFreq
import com.retaildemo.v2.data.repo.toEntity
import com.retaildemo.v2.data.repo.DataMarkRepo
import com.retaildemo.v2.data.repo.InventoryRepo
import com.retaildemo.v2.data.repo.PrintRepo
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.repo.ProductRepo
import com.retaildemo.v2.data.repo.ProductSupplementRepo
import com.retaildemo.v2.data.repo.ReceiptPhotoStore
import com.retaildemo.v2.data.repo.ShelfLocationRef
import com.retaildemo.v2.data.repo.ShelfRepo
import com.retaildemo.v2.print.LabelTemplates
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 搜索排序模式：满足不同找货诉求（综合推荐/现货优先/价格升降/库存多少）。 */
enum class SearchSortOption(val label: String) {
    RELEVANCE("综合推荐"),
    IN_STOCK("有货优先"),
    PRICE_ASC("价格最低"),
    PRICE_DESC("价格最高"),
    STOCK_DESC("库存最多"),
}

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val productRepo: ProductRepo,
    private val historyDao: SearchHistoryDao,
    private val config: AppConfig,
    private val printRepo: PrintRepo,
    productViewDao: com.retaildemo.v2.data.local.dao.ProductViewDao,
    val photoLoader: com.retaildemo.v2.data.repo.ShelfPhotoLoader,
) : ViewModel() {

    /** 查价快捷标签 = 新分类 31 品类（2026-09-08 分类地基重建，权威源 product_kb_category；
     * 点品类 → 品类精确过滤，展开子类 → 子类名全文检索。多标签体系见 product_kb_labels。 */
    val categories = CategoryTree.TOP

    /** 当前展开子类的品类（点同品类收起）。 */
    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    /** 二级子类 chips（选中品类的子类列表）。 */
    private val _subCategories = MutableStateFlow<List<String>>(emptyList())
    val subCategories: StateFlow<List<String>> = _subCategories.asStateFlow()

    /** 点品类 chip：展开二级子类并按品类名精确过滤（后端 category=? 分支）。 */
    fun onCategoryClick(cat: String) {
        if (_selectedCategory.value == cat) {
            _selectedCategory.value = null
            _subCategories.value = emptyList()
            return
        }
        _selectedCategory.value = cat
        _subCategories.value = CategoryTree.SUB[cat] ?: emptyList()
        _query.value = cat
        viewModelScope.launch { doSearch(cat) }
    }

    /** 点子类 chip：子类名全文检索（名称匹配）。 */
    fun onSubCategoryClick(sub: String) {
        _query.value = sub
        viewModelScope.launch { doSearch(sub) }
    }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<ProductEntity>>(emptyList())
    val results: StateFlow<List<ProductEntity>> = _results.asStateFlow()

    /**
     * 05 出库频次（只读旁路，**不进 Room**）：key = sku 或条码，值为「最近出库日 + 当日件数」。
     * 只随在线检索刷新；离线兜底（searchLocal）时为空——离线本来就不该猜最近卖没卖。
     */
    private val _saleFreq = MutableStateFlow<Map<String, SaleFreq>>(emptyMap())
    val saleFreq: StateFlow<Map<String, SaleFreq>> = _saleFreq.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** S2 两段式：当前结果是否为「全量检索（含无货商品）」模式；换词自动退出。 */
    private val _frozenMode = MutableStateFlow(false)
    val frozenMode: StateFlow<Boolean> = _frozenMode.asStateFlow()

    /** 多种排序方案：满足不同找货场景（综合推荐/现货优先/价格升降/库存多少）。 */
    private val _sortOption = MutableStateFlow(SearchSortOption.RELEVANCE)
    val sortOption: StateFlow<SearchSortOption> = _sortOption.asStateFlow()

    private var currentRawResults: List<ProductEntity> = emptyList()

    fun setSortOption(option: SearchSortOption) {
        _sortOption.value = option
        applySortingAndFiltering()
    }

    /** S4 双路：B 路识别的规格/多义消歧提示，仅在在线补搜时有值。 */
    private val _disambiguation = MutableStateFlow<DisambiguationHint?>(null)
    val disambiguation: StateFlow<DisambiguationHint?> = _disambiguation.asStateFlow()
    /** S3: 同码多货候选（非空=需同屏裁决）。 */
    // S12: 库存筛选（null=全部；1=有货；0=无货——映射后端 min_qty）
    private val _stockFilter = MutableStateFlow<Int?>(null)
    val stockFilter: StateFlow<Int?> = _stockFilter.asStateFlow()

    /**
     * 当前筛选在本地过滤层藏掉的结果数。2026-09-11 老板实测：「无货」筛选激活时扫有货商品，
     * 服务器明明返回了结果却被 filter{stockQty<=0} 全数藏掉、零提示=「扫什么都扫不出来」。
     * 非 0 时 UI 必须亮提示并给一键还原，杜绝静默空结果。
     */
    private val _filterHidden = MutableStateFlow(0)
    val filterHidden: StateFlow<Int> = _filterHidden.asStateFlow()

    fun setStockFilter(minQty: Int?) {
        _stockFilter.value = minQty
        applySortingAndFiltering()
    }

    private fun applySortingAndFiltering() {
        val q = _query.value.trim()
        val filter = _stockFilter.value
        val filtered = when (filter) {
            1 -> currentRawResults.filter { it.stockQty > 0 }
            0 -> currentRawResults.filter { it.stockQty <= 0 }
            else -> currentRawResults
        }
        _filterHidden.value = if (filter == null) 0 else currentRawResults.size - filtered.size
        _results.value = sortProducts(filtered, _sortOption.value, q, _serverScores.value)
    }

    /** 服务端相关度分（id→score，仅在线结果有）。空=离线/旧服务端 → sortProducts 回退本地分桶。 */
    private val _serverScores = MutableStateFlow<Map<Long, Double>>(emptyMap())

    private val _multiCandidates = MutableStateFlow<List<ProductEntity>?>(null)
    val multiCandidates: StateFlow<List<ProductEntity>?> = _multiCandidates.asStateFlow()
    /** S3: 绑定结果提示。 */
    private val _bindMessage = MutableStateFlow<String?>(null)
    val bindMessage: StateFlow<String?> = _bindMessage.asStateFlow()

    fun clearBindMessage() { _bindMessage.value = null }


    /** 点击消歧提示：用标准词换词重查。 */
    fun searchDisambiguate(term: String) {
        if (term.isBlank()) return
        _disambiguation.value = null
        _query.value = term
        viewModelScope.launch { doSearch(term) }
    }

    /**
     * S16c 扫码强制重搜：每次扫码都立即发起检索，绕过 debounce+distinctUntilChanged
     * （同码重扫被吞 = 首扫失败后「扫第二次同一码」是 no-op 的根因之一）。模式同
     * [searchDisambiguate]：设值 + 直接 launch，不等待 200ms 抖动窗口。
     */
    fun scanSearch(code: String) {
        val c = code.trim()
        if (c.isEmpty()) return
        _query.value = c
        viewModelScope.launch {
            // 扫码即查（2026-09-27 搜索提速）：本地条码精确命中 **<50ms 先显示**（离线可用），
            // 服务器结果到达后整体替换——最终口径仍是服务器优先（消歧/多候选/频次都随服务器），
            // 但扫码不再盯着转圈等隧道往返。
            val local = productRepo.searchLocal(c)
            if (local.isNotEmpty() && _query.value.trim() == c) {
                currentRawResults = local
                applySortingAndFiltering()
            }
            doSearch(c, showLoading = local.isEmpty())
        }
    }

    /** 默认检索 0 结果时的第二击：带 include_frozen=1 重查。 */
    fun searchIncludeFrozen() {
        _frozenMode.value = true
        viewModelScope.launch { doSearch(_query.value, includeFrozen = true) }
    }

    val history = historyDao.observeAll()

    /** 最近浏览的商品（详情页打开即记）。 */
    val recentViews = productViewDao.observeRecent()

    init {
        viewModelScope.launch {
            _query
                .debounce(200)
                .distinctUntilChanged()
                .collect { q -> doSearch(q) }
        }
    }

    fun onQueryChange(q: String) {
        _query.value = q
        if (q.isBlank()) {
            currentRawResults = emptyList()
            _results.value = emptyList()
            _filterHidden.value = 0
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyDao.clear()
        }
    }

    private suspend fun doSearch(q: String, includeFrozen: Boolean = false, showLoading: Boolean = true) {
        val keyword = q.trim()
        if (keyword.isEmpty()) {
            currentRawResults = emptyList()
            _results.value = emptyList()
            return
        }
        if (!includeFrozen) _frozenMode.value = false
        // showLoading=false：扫码路径本地结果已先显示，服务器刷新不再亮转圈（静默替换）
        _loading.value = showLoading
        _disambiguation.value = null

        // I18 唯一搜索通道（ProductRepo.search）：服务器优先→本地兜底；S3 裁决/S4 消歧随结果透传。
        val res = productRepo.search(keyword, includeFrozen, _stockFilter.value)
        if (_query.value.trim() == keyword) {
            _multiCandidates.value = res.multiCandidates.takeIf { res.usedOnline }
            _disambiguation.value = res.disambiguation
            _saleFreq.value = res.saleFreq
            _serverScores.value = if (res.usedOnline) res.scores else emptyMap()
            currentRawResults = res.results
            applySortingAndFiltering()
        }
        _loading.value = false
        // 记历史（仅本地已有结果的查询）
        if (_results.value.isNotEmpty()) {
            historyDao.upsert(SearchHistoryEntity(keyword, System.currentTimeMillis()))
        }
    }

    /** S3 老板拍板：多候选同屏，店员点选即绑定（alias_audit 留痕），成功后重查出唯一商品。 */
    fun onMultiPicked(picked: ProductEntity) {
        val code = _query.value.trim()
        _multiCandidates.value = null
        if (code.isEmpty()) return
        viewModelScope.launch {
            val ok = runCatching {
                productRepo.aliasBind(code, picked.sku.orEmpty(), config.staffName.ifBlank { "unknown" })
            }.getOrDefault(false)
            if (ok) {
                _bindMessage.value = "已绑定：${picked.name}"
                doSearch(code, includeFrozen = _frozenMode.value)
            } else {
                _bindMessage.value = "绑定失败，请重试"
            }
        }
    }

    /** 批量放入打印队列（S5 查价多选）。 */
    fun enqueueBatchPrint(products: List<ProductEntity>, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val jobs = products.map { p ->
                val key = p.sku?.takeIf { it.isNotBlank() } ?: p.barcode.orEmpty()
                com.retaildemo.v2.data.local.entity.PrintJobEntity(
                    sku = key,
                    name = p.name,
                    spec = p.spec,
                    barcode = p.barcode,
                    sellPrice = p.sellPrice,
                    qty = 1,
                    unit = p.unit.orEmpty(),
                    sourceScene = com.retaildemo.v2.data.local.entity.PrintJobEntity.SCENE_MULTI,
                )
            }
            printRepo.enqueueBatch(jobs)
            onDone(jobs.size)
        }
    }

    companion object {
        fun sortProducts(
            list: List<ProductEntity>,
            option: SearchSortOption,
            keyword: String,
            scores: Map<Long, Double> = emptyMap(),
        ): List<ProductEntity> {
            val q = keyword.trim().lowercase()
            if (list.isEmpty()) return emptyList()

            return when (option) {
                SearchSortOption.RELEVANCE -> {
                    // 在线结果：服务器 _rank_results 打分随行下发 → 直接按服务器序展示
                    // （单一权威排序：拼音前缀 90 / 条码·SKU 片段 85 / 型号 85 这些本地桶没有的
                    // 层次都在服务器分里；本地粗排重排服务器序正是 2026-09-27「1957 搜不出来」
                    // 让真品沉底的根因）。空 map（离线兜底/旧服务端）才走下面的本地分桶。
                    if (scores.isNotEmpty()) {
                        return list.sortedByDescending { scores[it.id] ?: -1.0 }
                    }
                    val qDigits = q.replace(".", "")
                    list.sortedWith(
                        compareBy<ProductEntity> { p ->
                            val name = p.name.lowercase()
                            val spec = p.spec?.lowercase().orEmpty()
                            val model = p.model?.lowercase().orEmpty()
                            val barcode = p.barcode?.lowercase().orEmpty()
                            val sku = p.sku?.lowercase().orEmpty()
                            val brand = p.brand?.lowercase().orEmpty()
                            // 数字码片段命中（如 1957 ⊂ 001.99.1957，含去点号容错）：
                            // 服务器把这类 sku/barcode LIKE 命中排第一，但本排序会重排服务器结果——
                            // 若没有对应分桶，真品落 else 桶按库存沉底 = 「明明有货却搜不出来」
                            // （2026-09-27 老板反馈：搜 1957 被 499 条库存大的弱相关结果淹没）。
                            // ≥3 位才启用：1-2 位数字碎片命中率太宽，会反向淹没品名匹配。
                            val codeFragmentHit = qDigits.length >= 3 && (
                                barcode.contains(q) || sku.contains(q) ||
                                    sku.replace(".", "").contains(qDigits)
                                )
                            when {
                                q.isNotBlank() && (barcode == q || sku == q) -> 0
                                q.isNotBlank() && (barcode.startsWith(q) || sku.startsWith(q)) -> 1
                                q.isNotBlank() && name == q -> 2
                                q.isNotBlank() && name.startsWith(q) -> 3
                                codeFragmentHit -> 4
                                q.isNotBlank() && name.contains(q) -> 5
                                q.isNotBlank() && (spec.contains(q) || model.contains(q)) -> 6
                                q.isNotBlank() && brand.contains(q) -> 7
                                else -> 8
                            }
                        }.thenByDescending { it.stockQty > 0 }
                            .thenByDescending { it.stockQty }
                            .thenBy { it.sellPrice }
                    )
                }
                SearchSortOption.IN_STOCK -> {
                    list.sortedWith(
                        compareByDescending<ProductEntity> { it.stockQty > 0 }
                            .thenByDescending { it.stockQty }
                            .thenBy { it.sellPrice }
                    )
                }
                SearchSortOption.PRICE_ASC -> {
                    list.sortedWith(
                        compareBy<ProductEntity> { if (it.sellPrice <= 0.0) 1 else 0 }
                            .thenBy { it.sellPrice }
                            .thenByDescending { it.stockQty > 0 }
                    )
                }
                SearchSortOption.PRICE_DESC -> {
                    list.sortedWith(
                        compareByDescending<ProductEntity> { it.sellPrice }
                            .thenByDescending { it.stockQty > 0 }
                    )
                }
                SearchSortOption.STOCK_DESC -> {
                    list.sortedWith(
                        compareByDescending<ProductEntity> { it.stockQty }
                            .thenBy { it.sellPrice }
                    )
                }
            }
        }
    }
}

@HiltViewModel
class ProductDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val productRepo: ProductRepo,
    private val inventoryRepo: InventoryRepo,
    private val shelfRepo: ShelfRepo,
    private val printRepo: PrintRepo,
    private val billRepo: BillRepo,
    private val markRepo: DataMarkRepo,
    private val supplementRepo: ProductSupplementRepo,
    private val api: com.retaildemo.v2.data.remote.retaildemoApi,
    private val viewDao: com.retaildemo.v2.data.local.dao.ProductViewDao,
    private val photoRepo: com.retaildemo.v2.data.repo.ProductPhotoRepo,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    val barcode: String = savedStateHandle["barcode"] ?: ""

    private val _product = MutableStateFlow<ProductEntity?>(null)
    val product: StateFlow<ProductEntity?> = _product.asStateFlow()

    private val _locations = MutableStateFlow<List<ProductLocationDto>>(emptyList())
    val locations: StateFlow<List<ProductLocationDto>> = _locations.asStateFlow()

    /** 五级货架绑定（SEIS v2：本机 Room，绑定即见零网络）。老接口 product_locations 只是回落显示源。 */
    private val _shelfLocations = MutableStateFlow<List<ShelfLocationRef>>(emptyList())
    val shelfLocations: StateFlow<List<ShelfLocationRef>> = _shelfLocations.asStateFlow()

    /**
     * 货架绑定加载：先按本机商品 id 反查；跨设备同步来的绑定 product_id 可能对不上
     * 本机商品表（同步拉取不重映射 id），回落条码快照（绑定时的 barcode）。
     */
    private suspend fun loadShelfLocations(pid: Long?) {
        val byId = pid?.takeIf { it != 0L }?.let { shelfRepo.locationsOfProduct(it) }.orEmpty()
        _shelfLocations.value = byId.ifEmpty { shelfRepo.locationsOfBarcode(barcode) }
    }

    private val _images = MutableStateFlow<List<String>>(emptyList())
    val images: StateFlow<List<String>> = _images.asStateFlow()

    private val _bindMessage = MutableStateFlow<String?>(null)
    val bindMessage: StateFlow<String?> = _bindMessage.asStateFlow()

    private val _printMessage = MutableStateFlow<String?>(null)
    val printMessage: StateFlow<String?> = _printMessage.asStateFlow()

    /** I19 商品照片：服务端已传（product_images）在前 + 本机待传（photos/）在后。 */
    data class PhotoUi(
        val remote: com.retaildemo.v2.data.remote.ProductImageDto? = null,
        val local: java.io.File? = null,
    ) {
        val isLocal: Boolean get() = remote == null
    }

    private val _photos = MutableStateFlow<List<PhotoUi>>(emptyList())
    val photos: StateFlow<List<PhotoUi>> = _photos.asStateFlow()

    /** I19 下行可见性：服务端照片列表拉取失败=true（UI 亮「未加载」提示，绝不静默清空已传照片）。 */
    private val _photosOffline = MutableStateFlow(false)
    val photosOffline: StateFlow<Boolean> = _photosOffline.asStateFlow()

    private val _movements = MutableStateFlow<List<com.retaildemo.v2.data.remote.MovementDto>>(emptyList())
    val movements: StateFlow<List<com.retaildemo.v2.data.remote.MovementDto>> = _movements.asStateFlow()

    /** 施工连带辅料推荐（如角阀配套生料带、软管、扳手）。 */
    private val _crossSell = MutableStateFlow<List<com.retaildemo.v2.data.remote.ProductDto>>(emptyList())
    val crossSell: StateFlow<List<com.retaildemo.v2.data.remote.ProductDto>> = _crossSell.asStateFlow()

    /** 同类在售替代品推荐（缺货同规替代保成交）。 */
    private val _substitutes = MutableStateFlow<List<com.retaildemo.v2.data.remote.ProductDto>>(emptyList())
    val substitutes: StateFlow<List<com.retaildemo.v2.data.remote.ProductDto>> = _substitutes.asStateFlow()

    /** 同类推荐（兼容老视图）。 */
    private val _similar = MutableStateFlow<List<com.retaildemo.v2.data.remote.ProductDto>>(emptyList())
    val similar: StateFlow<List<com.retaildemo.v2.data.remote.ProductDto>> = _similar.asStateFlow()

    private val _showQr = MutableStateFlow(false)
    val showQr: StateFlow<Boolean> = _showQr.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun showQr() { _showQr.value = true }
    fun dismissQr() { _showQr.value = false }

    // ---------------- XZG-API-RO 单件实时库存（管家婆当场值） ----------------

    /** 最近一次实时查询结果；null=还没查过或查不到（界面继续显示本地快照）。 */
    private val _liveStock = MutableStateFlow<com.retaildemo.v2.data.remote.LiveStockDto?>(null)
    val liveStock: StateFlow<com.retaildemo.v2.data.remote.LiveStockDto?> = _liveStock.asStateFlow()

    private val _liveLoading = MutableStateFlow(false)
    val liveLoading: StateFlow<Boolean> = _liveLoading.asStateFlow()

    /** 实时查询失败提示（短暂 toast 用）；成功时不打扰。 */
    private val _liveMessage = MutableStateFlow<String?>(null)
    val liveMessage: StateFlow<String?> = _liveMessage.asStateFlow()

    fun clearLiveMessage() { _liveMessage.value = null }

    // ---------------- I24 条码修改（本地先生效 → 后台回写 → 校验通过才算同步成功） ----------------

    /** 条码编辑弹窗是否可见。 */
    private val _barcodeEditVisible = MutableStateFlow(false)
    val barcodeEditVisible: StateFlow<Boolean> = _barcodeEditVisible.asStateFlow()

    /** 店员输入/扫入的新条码。 */
    private val _barcodeInput = MutableStateFlow("")
    val barcodeInput: StateFlow<String> = _barcodeInput.asStateFlow()

    /** 预查结果（**只作提示，不拦路**）：这个码管家婆里现在归谁。 */
    private val _barcodeCheck = MutableStateFlow<com.retaildemo.v2.data.remote.BarcodeCheckDto?>(null)
    val barcodeCheck: StateFlow<com.retaildemo.v2.data.remote.BarcodeCheckDto?> = _barcodeCheck.asStateFlow()

    /** ""=输入中 / "submitting"（本地写入，毫秒级，仅用于防连点）。 */
    private val _barcodeStage = MutableStateFlow("")
    val barcodeStage: StateFlow<String> = _barcodeStage.asStateFlow()

    /** 条码同步反馈（受理成功 / 已同步管家婆 / 校验没过已撤回），独立消息通道。 */
    private val _barcodeSyncMessage = MutableStateFlow<String?>(null)
    val barcodeSyncMessage: StateFlow<String?> = _barcodeSyncMessage.asStateFlow()

    fun clearBarcodeSyncMessage() { _barcodeSyncMessage.value = null }

    private var barcodeCheckJob: Job? = null
    private var barcodePollJob: Job? = null

    // ---------- I26 商品图片回传管家婆（与条码回写同范式：受理 → 后台 → 回读校验为准） ----------

    /** 二次确认弹窗是否打开（上游图片只进不出，确认闸界面+服务端各一道）。 */
    private val _imagePushConfirm = MutableStateFlow(false)
    val imagePushConfirm: StateFlow<Boolean> = _imagePushConfirm.asStateFlow()

    /** 图片回传反馈（已受理 / 已同步管家婆 / 失败），独立消息通道（弹窗关了也要看得见）。 */
    private val _imagePushMessage = MutableStateFlow<String?>(null)
    val imagePushMessage: StateFlow<String?> = _imagePushMessage.asStateFlow()

    fun clearImagePushMessage() { _imagePushMessage.value = null }

    private var imagePushPollJob: Job? = null

    /** 照片区点「回传管家婆」：先验照片在场，再开二次确认弹窗（服务端受理时还会再验一次）。 */
    fun requestImagePush() {
        if (_photos.value.none { it.remote != null }) {
            _imagePushMessage.value = "这个商品还没有服务器照片，先拍照上传再回传"
            return
        }
        _imagePushConfirm.value = true
    }

    fun dismissImagePush() { _imagePushConfirm.value = false }

    fun openBarcodeEdit() {
        _barcodeInput.value = ""
        _barcodeCheck.value = null
        _barcodeStage.value = ""
        _barcodeEditVisible.value = true
    }

    fun dismissBarcodeEdit() {
        barcodeCheckJob?.cancel()
        _barcodeEditVisible.value = false
        _barcodeStage.value = ""
    }

    /**
     * 输入变化：顺手起一次**非阻塞**预查，只用来提前提示「这个码是谁的」。
     *
     * 与旧流程的关键区别：预查**不挡确认**。旧流程要等上游校验通过才能点确认，
     * 而真正的把关已经搬到后台回写的回读校验里（那儿才是权威判据）。
     */
    fun onBarcodeInputChanged(text: String) {
        _barcodeInput.value = text
        _barcodeCheck.value = null
        barcodeCheckJob?.cancel()
        val code = text.trim()
        if (code.length < 4) return          // 手输过程不必每个字符都问一次上游
        barcodeCheckJob = viewModelScope.launch {
            delay(BARCODE_CHECK_DEBOUNCE_MS)
            _barcodeCheck.value = productRepo.checkBarcode(code)
        }
    }

    /**
     * 提交条码修改（I24，老板 2026-09-14 令「逻辑重写」）。
     *
     * 【立刻关窗 + 后台异步】：点「确认修改」瞬间立即关闭弹窗并更新界面，
     * 绝不在弹窗里同步等网络阻塞，上传与管家婆回写全程在后台静默进行。
     */
    fun confirmBarcodeWrite() {
        val p = _product.value ?: return
        val code = _barcodeInput.value.trim()
        val sku = p.sku?.takeIf { it.isNotBlank() }
        if (sku.isNullOrBlank()) { _liveMessage.value = "该商品无编号，改不了条码"; return }
        if (code.isBlank()) { _liveMessage.value = "请输入新条码"; return }
        if (code == (p.barcode ?: "")) { _liveMessage.value = "和当前条码一样，不用改"; return }
        barcodeCheckJob?.cancel()

        // ① 立即关闭弹窗，店员绝不在弹窗内等待
        _barcodeEditVisible.value = false
        _barcodeStage.value = ""

        // ② 界面当场更新为新条码，店员立刻看到生效
        val oldBarcode = p.barcode
        _product.value = p.copy(barcode = code)

        // ③ 立即提示已提交后台处理
        _barcodeSyncMessage.value = "条码已修改，正在后台同步管家婆…"

        // ④ 全部网络与写入动作放入后台协程，丝毫不阻塞主界面
        viewModelScope.launch {
            productRepo.updateLocalBarcode(sku, code)
            val result = productRepo.writeBarcode(sku, code, "APP店员")
            if (!result.accepted) {
                // 服务端拒绝（如格式异常）：本地恢复原条码并弹错
                _barcodeSyncMessage.value = result.message
                if (oldBarcode != null) {
                    productRepo.updateLocalBarcode(sku, oldBarcode)
                    _product.value = _product.value?.copy(barcode = oldBarcode)
                }
                return@launch
            }
            refreshLiveStock()          // 刷新详情页上的当场值
            pollBarcodeSync(result.jobId)
        }
    }

    /**
     * 轮询后台回写结果，翻到终态就通知店员。
     *
     * 两条纪律：① **只有服务端回读校验通过（synced）才说「已同步」**，不拿"发出去了"充数；
     * ② 轮询超时**不谎报**成功，也不谎报失败——如实说「还在后台回写」。
     */
    private fun pollBarcodeSync(jobId: String?) {
        if (jobId.isNullOrBlank()) return
        barcodePollJob?.cancel()
        barcodePollJob = viewModelScope.launch {
            repeat(BARCODE_SYNC_POLL_TIMES) {
                delay(BARCODE_SYNC_POLL_INTERVAL_MS)
                val st = productRepo.barcodeSyncStatus(jobId) ?: return@repeat
                com.retaildemo.v2.data.repo.barcodeSyncNotice(st)?.let { notice ->
                    _barcodeSyncMessage.value = notice
                    return@launch
                }
            }
            // 还在等管家婆（上游慢/断网重试中）——如实告知，不编造结果
            _barcodeSyncMessage.value = "条码已在本机生效；管家婆回写还在后台进行，稍后可再看"
        }
    }

    /**
     * 提交图片回传（I26）：确认后**只受理**——压缩、上传、写回 imgUrl、回读校验
     * 都在服务端后台 worker 跑（上游图片只进不出，服务端还有第二道确认闸兜底）。
     * 默认只回传主图（宁少勿滥）；合并口径由服务端定：保留管家婆已配的图，本次追加。
     */
    fun confirmImagePush() {
        val p = _product.value ?: return
        val sku = p.sku?.takeIf { it.isNotBlank() }
        if (sku.isNullOrBlank()) { _imagePushMessage.value = "该商品无编号，回传不了图片"; return }
        if (p.id == 0L) { _imagePushMessage.value = "本机自建商品，服务器上还没有它的照片"; return }
        _imagePushConfirm.value = false
        viewModelScope.launch {
            val result = productRepo.pushImages(sku, p.id, "APP店员")
            _imagePushMessage.value = result.message
            if (!result.accepted) return@launch
            pollImageSync(result.jobId)
        }
    }

    /**
     * 轮询后台图片回传结果（节奏比条码慢——含上传+写回+回读，天然更耗时）。
     *
     * 与条码同款纪律：① 只有回读校验通过（synced）才说「已同步」；
     * ② 超时不谎报成败——如实说「还在后台回传」（重试断点续传不重传，不会多堆孤儿文件）。
     */
    private fun pollImageSync(jobId: String?) {
        if (jobId.isNullOrBlank()) return
        imagePushPollJob?.cancel()
        imagePushPollJob = viewModelScope.launch {
            repeat(IMAGE_SYNC_POLL_TIMES) {
                delay(IMAGE_SYNC_POLL_INTERVAL_MS)
                val st = productRepo.imageSyncStatus(jobId) ?: return@repeat
                com.retaildemo.v2.data.repo.imageSyncNotice(st)?.let { notice ->
                    _imagePushMessage.value = notice
                    return@launch
                }
            }
            _imagePushMessage.value = "图片还在后台回传中（断点续传不重传），稍后可再看"
        }
    }

    /**
     * 查一次管家婆当场库存（只读，约 0.5 秒）。
     *
     * 失败绝不改本地数据、不清空界面——只把提示写进 [liveMessage]，界面回退到本地快照。
     */
    /** [quiet]=自动核价路径（打开详情顺手查）：失败不弹提示，界面保持本地快照即可。 */
    fun refreshLiveStock(quiet: Boolean = false) {
        val p = _product.value ?: return
        val key = p.sku?.takeIf { it.isNotBlank() } ?: p.barcode.orEmpty()
        if (key.isBlank()) {
            if (!quiet) _liveMessage.value = "该商品无编码，查不了实时库存"
            return
        }
        viewModelScope.launch {
            _liveLoading.value = true
            val dto = productRepo.liveStock(key)
            _liveLoading.value = false
            if (dto == null) {
                if (!quiet) _liveMessage.value = "没连上管家婆，显示的仍是上次同步的库存"
            } else {
                _liveStock.value = dto
            }
        }
    }

    /** B6 退货定位原单：已出库含本商品的正单（sku/条码命中）。 */
    private val _recentSales = MutableStateFlow<List<SaleRecordUi>>(emptyList())
    val recentSales: StateFlow<List<SaleRecordUi>> = _recentSales.asStateFlow()

    /** 06 稿右列（3:764）：近 30 天成交均价 + 昨日卖出件数。口径=已出库正单，退货负数行不计；avg30=null=近 30 天无成交。 */
    private val _salesStats = MutableStateFlow(ProductSalesStats())
    val salesStats: StateFlow<ProductSalesStats> = _salesStats.asStateFlow()

    /** 退货单生成成功后回传其 id，UI 观察跳转详情；null=无。 */
    private val _returnedBillId = MutableStateFlow<String?>(null)
    val returnedBillId: StateFlow<String?> = _returnedBillId.asStateFlow()

    /** B6 退货操作反馈（成功/失败），独立于价签/图片消息通道。 */
    private val _returnMessage = MutableStateFlow<String?>(null)
    val returnMessage: StateFlow<String?> = _returnMessage.asStateFlow()

    fun clearReturnMessage() { _returnMessage.value = null }

    /** I4 数据标记反馈（成功/失败），独立消息通道。 */
    private val _markMessage = MutableStateFlow<String?>(null)
    val markMessage: StateFlow<String?> = _markMessage.asStateFlow()

    fun clearMarkMessage() { _markMessage.value = null }

    /** I4 §11 本地补充（当前商品的独立表行，同步不覆盖；null=未补充过）。 */
    private val _supplement = MutableStateFlow<ProductSupplementEntity?>(null)
    val supplement: StateFlow<ProductSupplementEntity?> = _supplement.asStateFlow()

    /** I4 新建数据标记：只写 data_marks 表，绝不调改库存/改价（红线）。 */
    fun markProduct(markType: String, note: String, photoName: String?) {
        val p = _product.value ?: return
        viewModelScope.launch {
            val key = p.sku?.takeIf { it.isNotBlank() } ?: p.barcode.orEmpty()
            markRepo.create(
                sku = key.ifBlank { barcode },
                barcode = p.barcode.orEmpty(),
                name = p.name,
                markType = markType,
                note = note,
                photo = photoName,
            )
            _markMessage.value = "已标记，专员会去管家婆核对 ✓"
        }
    }

    /** I4 §11 保存本地补充（品牌/规格/备注/照片/待绑条码；价格永不经此入口）。 */
    fun saveSupplement(brand: String, spec: String, note: String, photoName: String?, pendingBarcode: String) {
        val p = _product.value ?: return
        viewModelScope.launch {
            val key = p.sku?.takeIf { it.isNotBlank() } ?: p.barcode.orEmpty()
            if (key.isBlank()) {
                _markMessage.value = "该商品无编码，无法补充"
                return@launch
            }
            supplementRepo.save(
                sku = key,
                name = p.name,
                brand = brand,
                spec = spec,
                note = note,
                photo = photoName,
                pendingBarcode = pendingBarcode,
                mainBarcode = p.barcode,
            )
            if (pendingBarcode.isNotBlank()) {
                printRepo.enqueue(
                    com.retaildemo.v2.data.local.entity.PrintJobEntity(
                        sku = key,
                        name = p.name,
                        spec = spec.ifBlank { p.spec },
                        barcode = pendingBarcode,
                        sellPrice = p.sellPrice,
                        qty = 1,
                    unit = p.unit.orEmpty(),
                        sourceScene = com.retaildemo.v2.data.local.entity.PrintJobEntity.SCENE_REBIND,
                    ),
                )
            }
            _supplement.value = supplementRepo.getBySku(key)
            _markMessage.value = "本地信息已保存 ✓" + if (pendingBarcode.isNotBlank()) "（新码已进待绑队列与打印队列）" else ""
        }
    }

    /** I4 相册选图 → 压缩 → 存 supplement/ 私有目录，回文件名（只存名不存绝对路径）。 */
    fun cacheSupplementPhoto(uri: android.net.Uri, onDone: (String?) -> Unit) {
        val p = _product.value
        viewModelScope.launch {
            val bmp = decodeScaled(uri)
            if (p == null || bmp == null) {
                onDone(null)
                return@launch
            }
            val key = (p.sku?.takeIf { it.isNotBlank() } ?: p.barcode.orEmpty()).ifBlank { "noid" }
            onDone(ReceiptPhotoStore.saveBitmapTo(appContext, "supplement", key, bmp))
        }
    }

    private fun decodeScaled(uri: android.net.Uri): android.graphics.Bitmap? = runCatching {
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bmp ->
            val maxW = 800
            if (bmp.width > maxW) {
                val ratio = maxW.toFloat() / bmp.width
                android.graphics.Bitmap.createScaledBitmap(bmp, maxW, (bmp.height * ratio).toInt(), true)
            } else bmp
        }
    }.getOrNull()

    private suspend fun loadRecentSales(sku: String, bc: String) {
        _recentSales.value = billRepo.recentSales(sku, bc).map { bill ->
            val item = billRepo.itemsOf(bill).firstOrNull {
                (sku.isNotBlank() && it.sku == sku) || (bc.isNotBlank() && it.barcode == bc)
            }
            SaleRecordUi(bill.id, bill.customerName, bill.createdAt, item?.qty ?: 0, item?.price ?: 0.0)
        }
    }

    /**
     * 06 稿右列统计（Gate 0：_reports/decisions/21_product_stats_rows.md）：
     * 近 30 天成交均价 = 近 30 天已出库正单该品成交额/件数（qty 加权）；昨日卖出 = 昨日出库件数合计。
     * 负数退货行（qty<=0）不计；日期取 createdAt 前 10 位（yyyy-MM-dd）做字典序比较。
     */
    private suspend fun loadSalesStats(sku: String, bc: String) {
        val bills = billRepo.recentSales(sku, bc, limit = 500)
        val dayFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
        val today = dayFmt.format(java.util.Date())
        val day30 = dayFmt.format(java.util.Date(System.currentTimeMillis() - 29L * 86_400_000L))
        val yesterday = dayFmt.format(java.util.Date(System.currentTimeMillis() - 86_400_000L))
        var qty30 = 0.0
        var amt30 = 0.0
        var soldYesterday = 0
        for (bill in bills) {
            val day = bill.createdAt.take(10)
            val item = billRepo.itemsOf(bill).firstOrNull {
                (sku.isNotBlank() && it.sku == sku) || (bc.isNotBlank() && it.barcode == bc)
            } ?: continue
            if (item.qty <= 0) continue
            if (day == yesterday) soldYesterday += item.qty
            if (day in day30..today) {
                qty30 += item.qty
                amt30 += item.qty * item.price
            }
        }
        _salesStats.value = ProductSalesStats(
            avg30 = if (qty30 > 0.0) amt30 / qty30 else null,
            soldYesterday = soldYesterday,
        )
    }

    /** 一键退货：按所选原单+数量生成负数单，成功刷新列表并回传退货单 id。 */
    fun createReturn(origBillId: String, sku: String, returnQty: Int) {
        viewModelScope.launch {
            val b = billRepo.createReturnBill(origBillId, sku, barcode, returnQty)
            if (b != null) {
                _returnMessage.value = "退货单已生成"
                _returnedBillId.value = b.id
                loadRecentSales(sku, barcode)
                loadSalesStats(sku, barcode)
            } else {
                _returnMessage.value = "退货失败：原单/数量无效"
            }
        }
    }

    fun consumeReturned() { _returnedBillId.value = null }

    init {
        viewModelScope.launch {
            val matched = productRepo.searchLocal(barcode)
                .firstOrNull { it.barcode == barcode || it.sku == barcode }
            // 本地模糊搜没命中时（例如 69 码只挂在别名表、或上游刚更新还没同步回来），按条码精确兜底再查一次。
            // 注意：这里必须保持 `val`——下面的 `local.xxx` 依赖 Kotlin 对稳定值的智能转换；
            // 改成 `var` + if 赋值会让捕获到 lambda 里的 `local` 失去智能转换，整文件编译不过。
            val local = matched ?: productRepo.getByBarcode(barcode)
            _product.value = local
            // 当场价自动核（2026-10-03 老板报「同步后列表新价、详情旧价」）：本地快照先显示
            //（离线可用），在线时顺手查一次管家婆当场值，价格/库存卡自动浮正；失败静默不扰。
            if (local != null) refreshLiveStock(quiet = true)
            // I4 §11：订阅本地补充行（独立表，同步不覆盖，天然 preserve）
            val key = local?.sku?.takeIf { it.isNotBlank() } ?: local?.barcode.orEmpty()
            if (key.isNotBlank()) {
                launch { supplementRepo.observeBySku(key).collect { _supplement.value = it } }
            }
            loadRecentSales(local?.sku ?: "", local?.barcode ?: barcode)
            loadSalesStats(local?.sku ?: "", local?.barcode ?: barcode)
            val pid = local?.id
            if (pid != null && pid != 0L) {
                // 最近浏览：打开详情即记录（product_views）
                runCatching {
                    viewDao.upsert(
                        com.retaildemo.v2.data.local.entity.ProductViewEntity(
                            barcode = barcode,
                            name = local.name,
                            spec = local.spec ?: "",
                            sellPrice = local.sellPrice,
                            viewedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                _locations.value = productRepo.locationsOf(pid)
                loadShelfLocations(pid)
                _movements.value = runCatching {
                    api.movements(productId = pid, limit = 10).body()?.data.orEmpty()
                }.getOrDefault(emptyList())
                reloadPhotos()
                // I19 打开详情顺手补传本机待传（含其它商品积压），完成后刷新照片区
                launch {
                    photoRepo.retryPending()
                    com.retaildemo.v2.data.repo.ProductPhotoRepo.enqueueRetry(appContext)
                    reloadPhotos()
                }
                // 智能推荐引擎：施工连带辅料配套 + 同类在售替代品
                val rec = runCatching {
                    productRepo.getRecommendations(
                        productId = pid,
                        name = local.name,
                        category = local.category,
                        spec = local.spec,
                        limit = 6,
                    )
                }.getOrNull()
                if (rec != null && (rec.cross_sell.isNotEmpty() || rec.substitutes.isNotEmpty())) {
                    _crossSell.value = rec.cross_sell
                    _substitutes.value = rec.substitutes
                    _similar.value = (rec.cross_sell + rec.substitutes).take(6)
                } else {
                    // 降级：若推荐接口未返回，使用分类搜索兜底
                    _product.value?.category?.takeIf { it.isNotBlank() }?.let { cat ->
                        _similar.value = runCatching {
                            com.retaildemo.v2.data.repo.parseSearchJson(api.search(cat).body()?.data)
                                .first
                                .filter { it.id != pid && it.barcode != barcode }
                                .take(6)
                        }.getOrDefault(emptyList())
                    }
                }
            }
        }
    }

    /**
     * I19 下行可见性（双相互通不对称的 APP 端半边）：服务端列表拉取失败时**保留已加载的
     * remote 照片**（旧值不清空），只把本机待传重新合并，并亮 [_photosOffline]——
     * 修复「弱网/瞬断下已上传照片从详情页凭空消失、老板以为没传上」。
     */
    private suspend fun reloadPhotos() {
        val pid = _product.value?.id ?: 0L
        val pending = if (pid != 0L) {
            com.retaildemo.v2.data.repo.ProductPhotoStore.pendingOf(appContext, pid)
        } else {
            emptyList()
        }
        val remote = if (pid != 0L) productRepo.imagesOfOrNull(pid) else emptyList()
        if (remote == null) {
            _photosOffline.value = true
            _photos.value = _photos.value.filter { it.remote != null } + pending.map { PhotoUi(local = it) }
        } else {
            _photosOffline.value = false
            _photos.value = remote.map { PhotoUi(remote = it) } + pending.map { PhotoUi(local = it) }
        }
    }

    /** I19 照片区手动重试（离线提示条点击时调用）。 */
    fun retryLoadPhotos() {
        viewModelScope.launch { reloadPhotos() }
    }

    /** UI 取图：本机待传按 maxW 采样解码（相机原图直存，整幅解必 OOM），服务端图经网关拉取（含鉴权头）。 */
    suspend fun loadPhotoBitmap(photo: PhotoUi, maxW: Int = 1280): android.graphics.Bitmap? =
        photo.local?.let {
            com.retaildemo.v2.data.repo.ProductPhotoRepo.decodeScaled(it, maxW)
        } ?: photo.remote?.url?.let { photoRepo.fetchBitmap(it) }

    /** I19 相机拍照落地（内置相机已写入 target 文件）：即传，失败留本机待补传。 */
    fun onCameraPhoto(target: java.io.File) {
        viewModelScope.launch {
            if (!target.exists() || target.length() == 0L) return@launch
            val ok = photoRepo.uploadFile(target)
            _printMessage.value = if (ok) "照片已上传 ✓" else "照片已保存本机，联网后自动补传"
            reloadPhotos()
        }
    }

    /** I19 相册选图上传（一货多图：每次选图即新增一张）。 */
    fun uploadFromUri(uri: android.net.Uri) {
        val pid = _product.value?.id ?: return
        if (pid == 0L) {
            _printMessage.value = "该商品不在服务端商品库，无法存图"
            return
        }
        viewModelScope.launch {
            _busy.value = true
            val ok = photoRepo.importFromUri(uri, pid)
            _busy.value = false
            _printMessage.value = if (ok) "照片已上传 ✓" else "照片已保存本机，联网后自动补传"
            reloadPhotos()
        }
    }

    /**
     * I27 相机连拍批量：逐张走统一上传通道（失败自动留 photos/ 待传，由 WorkManager 补传），
     * 一批只出一条消息（batchUploadMessage 分开报成功/待补传张数），最后统一刷新照片区。
     */
    fun onCameraPhotos(files: List<java.io.File>) {
        if (files.isEmpty()) return
        viewModelScope.launch {
            _busy.value = true
            var ok = 0
            for (f in files) {
                if (f.exists() && f.length() > 0 && photoRepo.uploadFile(f)) ok++
            }
            _busy.value = false
            _printMessage.value =
                com.retaildemo.v2.data.repo.ProductPhotoRepo.batchUploadMessage(ok, files.size - ok)
            com.retaildemo.v2.data.repo.ProductPhotoRepo.enqueueRetry(appContext)
            reloadPhotos()
        }
    }

    /**
     * I27 相册多选批量同款：每张 importFromUri（另存待传文件 → 统一上传）。
     * 图片读取依赖相册回调授予的临时读权限，与单张 uploadFromUri 同语义。
     */
    fun uploadFromUris(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        val pid = _product.value?.id ?: return
        if (pid == 0L) {
            _printMessage.value = "该商品不在服务端商品库，无法存图"
            return
        }
        viewModelScope.launch {
            _busy.value = true
            var ok = 0
            for (u in uris) if (photoRepo.importFromUri(u, pid)) ok++
            _busy.value = false
            _printMessage.value =
                com.retaildemo.v2.data.repo.ProductPhotoRepo.batchUploadMessage(ok, uris.size - ok)
            com.retaildemo.v2.data.repo.ProductPhotoRepo.enqueueRetry(appContext)
            reloadPhotos()
        }
    }

    /** I19 删除照片：服务端图删 product_images 行，本机待传删文件。 */
    fun deletePhoto(photo: PhotoUi) {
        viewModelScope.launch {
            val ok = photo.remote?.let {
                runCatching {
                    api.deleteProductImage(com.retaildemo.v2.data.remote.ImageDeleteRequest(it.id))
                        .body()?.success == true
                }.getOrDefault(false)
            } ?: true
            photo.local?.let { com.retaildemo.v2.data.repo.ProductPhotoStore.delete(it) }
            _printMessage.value = if (ok) "已删除" else "删除失败"
            reloadPhotos()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            inventoryRepo.refreshProduct(barcode)?.let {
                _product.value = it
                if (it.id != 0L) _locations.value = productRepo.locationsOf(it.id)
                loadShelfLocations(it.id)
            }
        }
    }

    /** 商品详情一键绑定货位：扫货位码/手输货位号（2 步完成）。 */
    fun bindToCell(cellCode: String) {
        val pid = _product.value?.id
        if (pid == null || pid == 0L) {
            _bindMessage.value = "商品未加载，稍后再试"
            return
        }
        viewModelScope.launch {
            val ok = inventoryRepo.bind(pid, cellCode)
            _bindMessage.value = if (ok) "已绑定到 $cellCode ✓" else "绑定失败（网络或货位不存在）"
            if (ok) refresh()
        }
    }

    fun clearBindMessage() { _bindMessage.value = null }

    fun clearPrintMessage() { _printMessage.value = null }

    /** 放入打印队列（S5 详情单打）。 */
    fun enqueuePrintJob(qty: Int = 1) {
        viewModelScope.launch {
            val p = _product.value
            if (p == null) {
                _printMessage.value = "商品未加载"
                return@launch
            }
            val key = p.sku?.takeIf { it.isNotBlank() } ?: p.barcode.orEmpty()
            printRepo.enqueue(
                com.retaildemo.v2.data.local.entity.PrintJobEntity(
                    sku = key,
                    name = p.name,
                    spec = p.spec,
                    barcode = p.barcode,
                    sellPrice = p.sellPrice,
                    qty = qty,
                    unit = p.unit.orEmpty(),
                    sourceScene = com.retaildemo.v2.data.local.entity.PrintJobEntity.SCENE_DETAIL,
                ),
            )
            _printMessage.value = "已加入打印队列（待出签） ✓"
        }
    }

    /** 打印该商品的价签（走默认打印机）。 */
    fun printPriceTag() {
        viewModelScope.launch {
            val p = _product.value
            if (p == null) {
                _printMessage.value = "商品未加载"
                return@launch
            }
            _printMessage.value = try {
                val ok = printRepo.printPriceTags(
                    listOf(
                        LabelTemplates.PriceTagItem(
                            name = p.name,
                            price = p.sellPrice,
                            spec = p.spec ?: "",
                            sku = p.sku ?: "",
                            barcode = p.barcode ?: "",
                            unit = p.unit ?: "",
                        ),
                    ),
                )
                if (ok) "价签已发送 ✓" else "打印失败（服务端返回失败）"
            } catch (e: Exception) {
                "打印失败：${e.message}"
            }
        }
    }

    companion object {
        // I24 条码预查 / 回写轮询的节奏常量。
        // 位置说明（2026-09-14）：这三个常量**只服务于本类（ProductDetailViewModel）**的
        // 条码修改流程——初次施工时误放在同文件 SearchViewModel 的 companion 里（两个类互不相通，
        // 编译期 Unresolved reference），已由 nfc-jump 班代修归位；原处的副本已删除（nfc-jump 代修 + I24 收口）。
        /** 条码预查防抖（手输时别每敲一个字符问一次上游；扫码整串进来多等 300ms 无感）。 */
        private const val BARCODE_CHECK_DEBOUNCE_MS = 300L

        /** 后台回写结果轮询节奏——本地已生效，这里只是等「已同步管家婆」的通知。 */
        private const val BARCODE_SYNC_POLL_INTERVAL_MS = 1_500L
        private const val BARCODE_SYNC_POLL_TIMES = 20

        /** I26 图片回传轮询节奏——含压缩上传+写回+回读，比条码慢（共等约 1 分钟后如实报「仍在后台」）。 */
        private const val IMAGE_SYNC_POLL_INTERVAL_MS = 2_000L
        private const val IMAGE_SYNC_POLL_TIMES = 30
    }
}

/** B6 最近销售记录（退货定位原单展示用）：单号/客户/时间/该行销量/该行单价。 */
data class SaleRecordUi(
    val billId: String,
    val customerName: String,
    val createdAt: String,
    val qty: Int,
    val price: Double,
)

/** 06 稿零售价卡右列统计（avg30=null 表示近 30 天无成交）。 */
data class ProductSalesStats(
    val avg30: Double? = null,
    val soldYesterday: Int = 0,
)
