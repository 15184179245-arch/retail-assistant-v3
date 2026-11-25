package com.retaildemo.v2.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retaildemo.v2.data.local.dao.DataMarkDao
import com.retaildemo.v2.data.local.dao.ProductDao
import com.retaildemo.v2.data.local.entity.DataMarkEntity
import com.retaildemo.v2.data.local.entity.PrintJobEntity
import com.retaildemo.v2.data.local.entity.ProductEntity
import com.retaildemo.v2.data.local.entity.ShelfBindingEntity
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.repo.NameCandidate
import com.retaildemo.v2.data.repo.PrintRepo
import com.retaildemo.v2.data.repo.ShelfLocationRef
import com.retaildemo.v2.data.repo.ShelfPhotoLoader
import com.retaildemo.v2.data.repo.ShelfRepo
import com.retaildemo.v2.data.repo.ShelfSyncRepo
import com.retaildemo.v2.nfc.ShelfNfc
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

// =====================================================================================
// UI 模型：文件夹树（分区/楼层 → 货架 → 层 → 格）
// =====================================================================================

/**
 * 分区/楼层（I23 文件夹树节点）。`parentId == null` = 顶层（楼层），非空 = 其下分区。
 * 老板 2026-09-13：「货架也要编入分区和楼层，要想文件夹类似功能来分类整理货架」。
 */
data class ZoneUi(
    val id: Long,
    val name: String,
    val tagId: String,
    val note: String? = null,
    val parentId: Long?,
    val zones: List<ZoneUi>,
    val shelves: List<ShelfUi>,
) {
    val shelfCount: Int get() = shelves.size + zones.sumOf { it.shelfCount }
    val zoneCount: Int get() = zones.size + zones.sumOf { it.zoneCount }
}

data class BindingUi(
    val id: Long,
    val productId: Long,
    val barcode: String?,
    val name: String,
    val misplaced: Boolean,
) {
    /** 本次会话内刚绑的（用于「本次已绑 N 件」分组）。 */
    var justBound: Boolean = false
}

data class SlotUi(
    val id: Long,
    val name: String,
    val tagId: String,
    /** 备注（给人看的提示，如「放螺钉」）；null = 没写。 */
    val note: String? = null,
    val bindings: List<BindingUi>,
)

data class LevelUi(
    val id: Long,
    val name: String,
    val tagId: String,
    val note: String? = null,
    val slots: List<SlotUi>,
    /** 只绑到层（未细究到格）的商品。 */
    val directBindings: List<BindingUi>,
) {
    val slotCount: Int get() = slots.size
    val bindingCount: Int get() = directBindings.size + slots.sumOf { it.bindings.size }
}

data class ShelfUi(
    val id: Long,
    val name: String,
    val tagId: String,
    val note: String? = null,
    /** I23：所在分区（文件夹节点 id）；null = 未分类（顶层直接可见）。 */
    val zoneId: Long? = null,
    val levels: List<LevelUi>,
) {
    val bindingCount: Int get() = levels.sumOf { it.bindingCount }
}

/** 面包屑一段：分区名（顶层起点为「全部」）。 */
data class Crumb(val zoneId: Long?, val name: String)

/**
 * 商品快照（规格/单位/售价/库存）—— 卡片蒙版上要叠的信息。
 *
 * 单独走一张表式 map 而不是塞进 BindingUi：绑定是"位置×商品"的多对多关系，
 * 同一商品在多个位置出现时快照只有一份，避免 N 条绑定重复查库。
 */
data class ProductSnap(
    val spec: String?,
    val unit: String?,
    val sellPrice: Double,
    val stockQty: Double,
    val barcode: String?,
)

/** 当前位置（绑定目标）。`slotId == null` = 只绑到层。 */
data class BindTarget(
    val shelfId: Long,
    val shelfName: String,
    val levelId: Long,
    val levelName: String,
    val slotId: Long?,
    val slotName: String?,
) {
    val label: String
        get() = buildString {
            append(shelfName)
            append(" · ")
            append(levelName)
            slotName?.let { append(" · ").append(it) }
        }

    val parentPath: String
        get() = if (slotName.isNullOrBlank()) shelfName else "$shelfName · $levelName"
}

/**
 * 碰贴/位置码直达的一次性定位信号：界面收到就把「货架条」与「正文」滚到目标。
 *
 * 为什么要单独一条信号：选中态（[ShelfViewModel.target]）是**常驻状态**，
 * 而"滚过去"是**一次性动作** —— 同一个位置反复碰两次，状态没变但界面必须重新滚一次，
 * 所以带 [nonce]（每次直达自增）作为 LaunchedEffect 的 key。
 */
data class JumpTarget(
    val shelfId: Long,
    /** null = 贴贴在货架上（没有具体层）。 */
    val levelId: Long?,
    val slotId: Long?,
    val nonce: Long,
)

/** 建名前的查重结果。 */
data class DupPrompt<T>(
    val pendingName: String,
    val candidates: List<NameCandidate>,
    val payload: T,
)

/**
 * I20 货架理货 ViewModel（三级 + 完全自定义命名 + **纯本地 Room，零网络**）。
 *
 * 与旧版的根本差别：旧版货架列表是硬编码的、货位商品走后端五级表 HTTP（数据对不上，接口恒空）；
 * 新版整棵树来自本地 Room 的 4 条响应式流，**飞行模式下可用**。
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class ShelfViewModel @Inject constructor(
    private val shelfRepo: ShelfRepo,
    /** I22 多终端同步：写操作后防抖推送 + 进页/启动拉取（失败静默，离线优先不破）。 */
    private val shelfSyncRepo: ShelfSyncRepo,
    private val productDao: ProductDao,
    private val dataMarkDao: DataMarkDao,
    private val appConfig: AppConfig,
    private val printRepo: PrintRepo,
    /** 商品卡片配图（本地照片优先，服务端图兜底；断网静默降级为占位，不影响主链路）。 */
    val photoLoader: ShelfPhotoLoader,
) : ViewModel() {

    /** 进程内记住上次停留的位置（页面来回切换不丢；`下次进同格默认停在该格`）。 */
    private object Session {
        var levelId: Long? = null
        var slotId: Long? = null
        var shelfId: Long? = null
    }

    // ============================== I22 同步触发（防抖） ==============================

    private val syncTicks = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    init {
        // 写操作后 2 秒静默期合并为一次同步（连扫绑定一批只推一次；断网失败不影响本地）
        viewModelScope.launch {
            syncTicks
                .debounce(2_000)
                .collect { runCatching { shelfSyncRepo.syncNow() } }
        }
    }

    /** 安排一次同步（写路径调用；防抖合并）。 */
    private fun requestSync() {
        syncTicks.tryEmit(Unit)
    }

    /** 页面进入 / 手动刷新：立即同步一次（best-effort，不阻塞 UI）。 */
    fun syncNow() {
        viewModelScope.launch { runCatching { shelfSyncRepo.syncNow() } }
    }

    // ============================== 树（4 条流拼装，无 N+1） ==============================

    /** 本次会话内刚绑上的 binding id（用于「本次绑定」分组高亮）。 */
    private val _justBoundIds = MutableStateFlow<Set<Long>>(emptySet())

    /**
     * 08 子屏要把绑定清单拆成「本次绑定」与「本格原有」两组（Ardot 3:16 y=264 / y=503）。
     * 分组判据只能来自这个集合 —— [ShelfUi] 树上挂的 `BindingUi.justBound` 是拼装时的快照，
     * 集合变化时 UI 不一定重取；直接暴露 StateFlow 让 Compose 跟着它重组才不会分组错位。
     */
    val justBoundIds: StateFlow<Set<Long>> = _justBoundIds.asStateFlow()

    val tree: StateFlow<List<ShelfUi>> = combine(
        shelfRepo.observeShelves(),
        shelfRepo.observeAllLevels(),
        shelfRepo.observeAllSlots(),
        shelfRepo.observeAllBindings(),
    ) { shelves, levels, slots, bindings ->
        val justBoundIds = _justBoundIds.value
        val bindingsBySlot = bindings.filter { it.slotId != null }.groupBy { it.slotId!! }
        val directByLevel = bindings.filter { it.slotId == null }.groupBy { it.levelId }
        val slotsByLevel = slots.groupBy { it.levelId }
        val levelsByShelf = levels.groupBy { it.shelfId }

        shelves.map { shelf ->
            ShelfUi(
                id = shelf.id,
                name = shelf.name,
                tagId = shelf.tagId,
                note = shelf.note,
                zoneId = shelf.zoneId,
                levels = (levelsByShelf[shelf.id] ?: emptyList()).map { level ->
                    LevelUi(
                        id = level.id,
                        name = level.name,
                        tagId = level.tagId,
                        note = level.note,
                        slots = (slotsByLevel[level.id] ?: emptyList()).map { slot ->
                            SlotUi(
                                id = slot.id,
                                name = slot.name,
                                tagId = slot.tagId,
                                note = slot.note,
                                bindings = (bindingsBySlot[slot.id] ?: emptyList()).map { it.toUi(justBoundIds) },
                            )
                        },
                        directBindings = (directByLevel[level.id] ?: emptyList()).map { it.toUi(justBoundIds) },
                    )
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * I23 文件夹树：分区（楼层/分区）→ 货架。
     * 与 [tree] 一样是「本地响应式流拼装」，断网可用；顶层还混入「未分类」货架（zoneId=null）。
     */
    val zoneTree: StateFlow<List<ZoneUi>> = combine(
        shelfRepo.observeZones(),
        tree,
    ) { zones, shelves ->
        val shelvesByZone = shelves.groupBy { it.zoneId }
        val childrenByParent = zones.groupBy { it.parentId }

        fun build(parentId: Long?): List<ZoneUi> =
            (childrenByParent[parentId] ?: emptyList()).map { z ->
                ZoneUi(
                    id = z.id,
                    name = z.name,
                    tagId = z.tagId,
                    note = z.note,
                    parentId = z.parentId,
                    zones = build(z.id),
                    shelves = shelvesByZone[z.id] ?: emptyList(),
                )
            }

        build(null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前所在文件夹（null = 顶层「全部」）。 */
    private val _currentZoneId = MutableStateFlow<Long?>(null)
    val currentZoneId: StateFlow<Long?> = _currentZoneId.asStateFlow()

    /** 当前文件夹的面包屑（从「全部」到当前分区）。 */
    val breadcrumb: StateFlow<List<Crumb>> = combine(zoneTree, _currentZoneId) { zones, current ->
        val path = mutableListOf<Crumb>()
        val flat = HashMap<Long, ZoneUi>()
        fun index(list: List<ZoneUi>) {
            list.forEach { flat[it.id] = it; index(it.zones) }
        }
        index(zones)
        var cur = current?.let { flat[it] }
        val stack = mutableListOf<Crumb>()
        while (cur != null) {
            stack.add(0, Crumb(cur.id, cur.name))
            cur = cur.parentId?.let { flat[it] }
        }
        path.add(Crumb(null, "全部"))
        path.addAll(stack)
        path
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), listOf(Crumb(null, "全部")))

    /** 当前文件夹下的子分区（直接子级）。 */
    val currentZones: StateFlow<List<ZoneUi>> = combine(zoneTree, _currentZoneId) { zones, current ->
        if (current == null) {
            zones
        } else {
            val flat = HashMap<Long, ZoneUi>()
            fun index(list: List<ZoneUi>) { list.forEach { flat[it.id] = it; index(it.zones) } }
            index(zones)
            flat[current]?.zones ?: emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前文件夹下的货架（null 表示未过滤，展示全店所有货架）。 */
    val currentShelves: StateFlow<List<ShelfUi>> = combine(zoneTree, tree, _currentZoneId) { zones, shelves, current ->
        if (current == null) {
            shelves // 未选分区时展示全店货架
        } else {
            val flat = HashMap<Long, ZoneUi>()
            fun index(list: List<ZoneUi>) { list.forEach { flat[it.id] = it; index(it.zones) } }
            index(zones)
            flat[current]?.shelves ?: emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前分区名字（用于顶部空间拓扑选择器显示，如「🏢 一楼 · 五金区」或「全部货架」）。 */
    val currentZoneName: StateFlow<String> = combine(zoneTree, _currentZoneId) { zones, current ->
        if (current == null) {
            "全部"
        } else {
            val flat = HashMap<Long, ZoneUi>()
            fun index(list: List<ZoneUi>) { list.forEach { flat[it.id] = it; index(it.zones) } }
            index(zones)
            flat[current]?.name ?: "全部"
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "全部")

    private fun flattenZones(z: ZoneUi): List<ZoneUi> = listOf(z) + z.zones.flatMap { flattenZones(it) }

    /** 进入某文件夹（null = 回到顶层）。 */
    fun openZone(zoneId: Long?) {
        _currentZoneId.value = zoneId
    }

    /**
     * 商品快照表：productId → 规格/单位/售价/库存（卡片蒙版上要显示的信息）。
     * 绑定变化时批量补一次（本地 Room，零网络）；查不到的商品只有名字快照（脏数据常态）。
     */
    private val _snapshots = MutableStateFlow<Map<Long, ProductSnap>>(emptyMap())
    val snapshots: StateFlow<Map<Long, ProductSnap>> = _snapshots.asStateFlow()

    private fun ShelfBindingEntity.toUi(justBoundIds: Set<Long>) = BindingUi(
        id = id,
        productId = productId,
        barcode = barcode,
        name = name,
        misplaced = misplaced,
    ).also { it.justBound = id in justBoundIds }

    // ============================== 选中态 ==============================

    private val _selectedShelfId = MutableStateFlow<Long?>(Session.shelfId)
    val selectedShelfId: StateFlow<Long?> = _selectedShelfId.asStateFlow()

    /** 碰贴/位置码直达信号（null = 还没有过一次直达）。 */
    private val _jumpTarget = MutableStateFlow<JumpTarget?>(null)
    val jumpTarget: StateFlow<JumpTarget?> = _jumpTarget.asStateFlow()
    private var jumpNonce = 0L

    private val _target = MutableStateFlow<BindTarget?>(null)
    val target: StateFlow<BindTarget?> = _target.asStateFlow()

    private val _bindingMode = MutableStateFlow(false)
    val bindingMode: StateFlow<Boolean> = _bindingMode.asStateFlow()

    private val _sessionBoundCount = MutableStateFlow(0)
    val sessionBoundCount: StateFlow<Int> = _sessionBoundCount.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _toastEvent = MutableSharedFlow<String>()
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    init {
        // 树首次加载后：恢复上次位置，否则落到第一架第一层
        viewModelScope.launch {
            tree.collect { shelves ->
                if (shelves.isEmpty()) return@collect
                if (_selectedShelfId.value == null || shelves.none { it.id == _selectedShelfId.value }) {
                    _selectedShelfId.value = shelves.first().id
                }
                // I23：恢复选中货架时，把所在分区一起打开（否则用户看不到它，以为丢了）
                if (_currentZoneId.value == null) {
                    val sel = shelves.firstOrNull { it.id == _selectedShelfId.value }
                    val zid = sel?.zoneId
                    if (zid != null) _currentZoneId.value = zid
                }
                if (_target.value == null) {
                    restoreOrSelectFirst(shelves)
                }
            }
        }
        // 商品快照：绑定集变化时批量补齐（同一商品多处只查一次）
        viewModelScope.launch {
            shelfRepo.observeAllBindings().collect { bindings ->
                val map = HashMap<Long, ProductSnap>(bindings.size)
                bindings.map { it.productId }.distinct().forEach { pid ->
                    productDao.getById(pid)?.let { p ->
                        map[pid] = ProductSnap(p.spec, p.unit, p.sellPrice, p.stockQty, p.barcode)
                    }
                }
                _snapshots.value = map
            }
        }
    }

    private fun restoreOrSelectFirst(shelves: List<ShelfUi>) {
        val rememberedLevel = Session.levelId
        val current = shelves.firstOrNull { it.id == _selectedShelfId.value } ?: shelves.first()
        val level = current.levels.firstOrNull { it.id == rememberedLevel } ?: current.levels.firstOrNull()
        if (level == null) return
        val slot = level.slots.firstOrNull { it.id == Session.slotId }
            ?: level.slots.firstOrNull()
        _target.value = BindTarget(
            shelfId = current.id,
            shelfName = current.name,
            levelId = level.id,
            levelName = level.name,
            slotId = slot?.id,
            slotName = slot?.name,
        )
        Session.shelfId = current.id
    }

    fun selectShelf(shelfId: Long) {
        _selectedShelfId.value = shelfId
        Session.shelfId = shelfId
        val shelf = tree.value.firstOrNull { it.id == shelfId } ?: return
        val firstLevel = shelf.levels.firstOrNull()
        if (firstLevel == null) {
            _target.value = null
            return
        }
        selectLevel(shelfId, firstLevel.id, firstLevel.slots.firstOrNull()?.id)
    }

    fun selectLevel(shelfId: Long, levelId: Long, slotId: Long? = null) {
        val shelf = tree.value.firstOrNull { it.id == shelfId } ?: return
        val level = shelf.levels.firstOrNull { it.id == levelId } ?: return
        val slot = slotId?.let { id -> level.slots.firstOrNull { it.id == id } }
        setTarget(shelf, level, slot)
    }

    /** 只绑到层：把目标设到层，slotId 留空。 */
    fun selectLevelOnly(shelfId: Long, levelId: Long) {
        val shelf = tree.value.firstOrNull { it.id == shelfId } ?: return
        val level = shelf.levels.firstOrNull { it.id == levelId } ?: return
        setTarget(shelf, level, null)
    }

    private fun setTarget(shelf: ShelfUi, level: LevelUi, slot: SlotUi?) {
        _target.value = BindTarget(
            shelfId = shelf.id,
            shelfName = shelf.name,
            levelId = level.id,
            levelName = level.name,
            slotId = slot?.id,
            slotName = slot?.name,
        )
        Session.shelfId = shelf.id
        Session.levelId = level.id
        Session.slotId = slot?.id
    }

    // ============================== 绑定模式 ==============================

    fun startBindingSession() {
        if (_target.value == null) {
            _toastEvent.tryEmit("请先选一个位置")
            return
        }
        _bindingMode.value = true
        _sessionBoundCount.value = 0
        _justBoundIds.value = emptySet()
    }

    fun finishBindingSession() {
        _bindingMode.value = false
        _justBoundIds.value = emptySet()
    }

    /**
     * 连扫绑定：扫一个绑一个，**全程本地，0 网络**（飞行模式可用）。
     * 未命中不静默吞掉 —— 计入 [unmatched]，由 UI 引导「标记待补全」（铁律 12 的兜底出口）。
     */
    fun bindScanned(codes: List<String>, onUnmatched: (List<String>) -> Unit = {}) {
        val target = _target.value
        if (target == null) {
            _toastEvent.tryEmit("请先选一个位置")
            return
        }
        val cleaned = codes.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleaned.isEmpty()) return

        viewModelScope.launch {
            _busy.value = true
            var bound = 0
            val unmatched = mutableListOf<String>()
            val newIds = _justBoundIds.value.toMutableSet()

            for (code in cleaned) {
                val product = lookupLocal(code)
                if (product == null) {
                    unmatched += code
                    continue
                }
                val id = shelfRepo.bind(
                    levelId = target.levelId,
                    slotId = target.slotId,
                    productId = product.id,
                    barcode = product.barcode ?: code,
                    name = product.name,
                    by = appConfig.staffName,
                )
                newIds += id
                bound++
            }

            _justBoundIds.value = newIds
            _sessionBoundCount.value = _sessionBoundCount.value + bound
            _busy.value = false
            if (bound > 0) requestSync()

            when {
                bound > 0 && unmatched.isEmpty() -> _toastEvent.tryEmit("已绑定 $bound 件到「${target.label}」")
                bound > 0 -> _toastEvent.tryEmit("已绑 $bound 件；${unmatched.size} 个码库里查不到")
                else -> _toastEvent.tryEmit("这 ${unmatched.size} 个码都没匹配到商品")
            }
            if (unmatched.isNotEmpty()) onUnmatched(unmatched)
        }
    }

    /** 本地优先查商品（条码精确 → 名称/SKU 模糊），**不发网络请求**。 */
    private suspend fun lookupLocal(code: String): ProductEntity? {
        val clean = code.trim().removePrefix("'")
        productDao.getByBarcode(clean)?.let { return it }
        if (clean != code) productDao.getByBarcode(code.trim())?.let { return it }
        return productDao.search(clean).firstOrNull()
    }

    fun unbind(bindingId: Long) {
        viewModelScope.launch {
            val ok = shelfRepo.unbind(bindingId)
            _justBoundIds.value = _justBoundIds.value - bindingId
            if (ok) requestSync()
            _toastEvent.tryEmit(if (ok) "已解绑这一处（该商品别的位置不受影响）" else "解绑失败")
        }
    }

    /**
     * 把「搜到的商品」绑到当前位置 —— 一品多货位的主入口：
     * 商品已存在于别处也照样加一条，形成多位置。
     */
    fun bindProductToTarget(product: ProductEntity) {
        val target = _target.value ?: run {
            _toastEvent.tryEmit("请先选一个位置")
            return
        }
        viewModelScope.launch {
            val id = shelfRepo.bind(
                levelId = target.levelId,
                slotId = target.slotId,
                productId = product.id,
                barcode = product.barcode,
                name = product.name,
                by = appConfig.staffName,
            )
            _justBoundIds.value = _justBoundIds.value + id
            requestSync()
            val others = shelfRepo.locationsOfProduct(product.id)
            _toastEvent.tryEmit(
                if (others.size > 1) "「${product.name}」已加入「${target.label}」，现在共 ${others.size} 处有它"
                else "「${product.name}」已绑定到「${target.label}」",
            )
        }
    }

    // ============================== 建档 / 改名 / 排序 / 合并 ==============================

    fun createShelf(name: String, zoneId: Long? = null) {
        viewModelScope.launch {
            runCatching { shelfRepo.createShelf(name.trim(), zoneId = zoneId) }
                .onSuccess { id ->
                    _selectedShelfId.value = id
                    Session.shelfId = id
                    requestSync()
                    _toastEvent.tryEmit("已建货架「${name.trim()}」")
                }
                .onFailure { _toastEvent.tryEmit(it.message ?: "建货架失败") }
        }
    }

    // ---------------- I23 分区/楼层（文件夹） ----------------

    /** 建分区（parentId = null 建楼层）。 */
    fun createZone(name: String, parentId: Long? = null) {
        viewModelScope.launch {
            runCatching { shelfRepo.createZone(name.trim(), parentId = parentId) }
                .onSuccess {
                    requestSync()
                    _toastEvent.tryEmit("已建分区「${name.trim()}」")
                }
                .onFailure { _toastEvent.tryEmit(it.message ?: "建分区失败") }
        }
    }

    fun renameZone(id: Long, name: String) = renameGeneric("分区") { shelfRepo.renameZone(id, name) }

    fun setZoneNote(id: Long, note: String) = noteGeneric { shelfRepo.setZoneNote(id, note) }

    fun moveZone(id: Long, delta: Int) = viewModelScope.launch {
        shelfRepo.moveZone(id, delta); requestSync()
    }

    /** 把货架移到某分区（null = 移出到「未分类」）。 */
    fun moveShelfToZone(shelfId: Long, zoneId: Long?) {
        viewModelScope.launch {
            shelfRepo.moveShelfToZone(shelfId, zoneId)
            requestSync()
            _toastEvent.tryEmit(if (zoneId == null) "已移出到「未分类」" else "已移入分区")
        }
    }

    /** 删除分区的二次确认文案需要的影响面（子分区数 / 其下货架数）。 */
    suspend fun zoneImpact(zoneId: Long): Pair<Int, Int> = shelfRepo.zoneImpact(zoneId)

    fun deleteZone(id: Long) {
        viewModelScope.launch {
            shelfRepo.deleteZone(id)
            if (_currentZoneId.value == id) _currentZoneId.value = null
            requestSync()
            _toastEvent.tryEmit("分区已删除")
        }
    }

    /** 建货架并返回其 tagId（写贴向导「先贴后建」用：建完立刻把稳定码写进贴）。 */
    suspend fun createShelfAndReturnTag(name: String): String? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        val id = runCatching { shelfRepo.createShelf(clean) }.getOrNull() ?: return null
        _selectedShelfId.value = id
        Session.shelfId = id
        requestSync()
        _toastEvent.tryEmit("已建货架「$clean」")
        return shelfRepo.tagIdOfShelf(id)
    }

    fun createLevel(shelfId: Long, name: String) {
        viewModelScope.launch {
            runCatching { shelfRepo.createLevel(shelfId, name.trim()) }
                .onSuccess {
                    requestSync()
                    _toastEvent.tryEmit("已建层「${name.trim()}」")
                }
                .onFailure { _toastEvent.tryEmit(it.message ?: "建层失败") }
        }
    }

    fun createSlot(levelId: Long, name: String) {
        viewModelScope.launch {
            runCatching { shelfRepo.createSlot(levelId, name.trim()) }
                .onSuccess {
                    requestSync()
                    _toastEvent.tryEmit("已建格「${name.trim()}」")
                }
                .onFailure { _toastEvent.tryEmit(it.message ?: "建格失败") }
        }
    }

    /**
     * 建名前查重。「完全自定义命名」的固有风险：同一位置被起了两个名，货分散两边。
     * 返回非空候选时由 UI 先问「是不是这个？」再决定新建还是合并。
     */
    suspend fun checkShelfName(name: String): List<NameCandidate> = shelfRepo.similarShelves(name)
    suspend fun checkLevelName(shelfId: Long, name: String): List<NameCandidate> = shelfRepo.similarLevels(shelfId, name)
    suspend fun checkSlotName(levelId: Long, name: String): List<NameCandidate> = shelfRepo.similarSlots(levelId, name)

    fun renameShelf(id: Long, name: String) = renameGeneric("货架") { shelfRepo.renameShelf(id, name) }
    fun renameLevel(id: Long, name: String) = renameGeneric("层") { shelfRepo.renameLevel(id, name) }
    fun renameSlot(id: Long, name: String) = renameGeneric("格") { shelfRepo.renameSlot(id, name) }

    private fun renameGeneric(what: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess {
                    requestSync()
                    _toastEvent.tryEmit("已改名（贴在货架上的标签不用重贴）")
                }
                .onFailure { _toastEvent.tryEmit(it.message ?: "改$what 名失败") }
        }
    }

    // ---------------- 备注（2026-09-13 老板补需求：三级都能写备注） ----------------

    fun setShelfNote(id: Long, note: String) = noteGeneric { shelfRepo.setShelfNote(id, note) }
    fun setLevelNote(id: Long, note: String) = noteGeneric { shelfRepo.setLevelNote(id, note) }
    fun setSlotNote(id: Long, note: String) = noteGeneric { shelfRepo.setSlotNote(id, note) }

    private fun noteGeneric(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess {
                    requestSync()
                    _toastEvent.tryEmit("备注已保存")
                }
                .onFailure { _toastEvent.tryEmit(it.message ?: "保存备注失败") }
        }
    }

    fun moveShelf(id: Long, delta: Int) = viewModelScope.launch {
        shelfRepo.moveShelf(id, delta); requestSync()
    }
    fun moveLevel(id: Long, shelfId: Long, delta: Int) = viewModelScope.launch {
        shelfRepo.moveLevel(id, shelfId, delta); requestSync()
    }
    fun moveSlot(id: Long, levelId: Long, delta: Int) = viewModelScope.launch {
        shelfRepo.moveSlot(id, levelId, delta); requestSync()
    }

    fun mergeLevels(fromId: Long, toId: Long) {
        viewModelScope.launch {
            shelfRepo.mergeLevel(fromId, toId)
            requestSync()
            _toastEvent.tryEmit("已合并，绑定一起搬过去了（商品没丢）")
        }
    }

    fun mergeSlots(fromId: Long, toId: Long) {
        viewModelScope.launch {
            shelfRepo.mergeSlot(fromId, toId)
            requestSync()
            _toastEvent.tryEmit("已合并，绑定一起搬过去了（商品没丢）")
        }
    }

    fun deleteShelf(id: Long) {
        viewModelScope.launch {
            shelfRepo.deleteShelf(id)
            if (_selectedShelfId.value == id) _selectedShelfId.value = null
            _target.value = null
            requestSync()
            _toastEvent.tryEmit("货架已删除")
        }
    }

    fun deleteLevel(id: Long) {
        viewModelScope.launch {
            shelfRepo.deleteLevel(id)
            requestSync()
            _toastEvent.tryEmit("层已删除")
        }
    }

    fun deleteSlot(id: Long) {
        viewModelScope.launch {
            shelfRepo.deleteSlot(id)
            requestSync()
            _toastEvent.tryEmit("格已删除")
        }
    }

    // ============================== 找货（反查：一品多货位全列出） ==============================

    private val _findKeyword = MutableStateFlow("")
    val findKeyword: StateFlow<String> = _findKeyword.asStateFlow()

    private val _findResults = MutableStateFlow<List<ProductEntity>>(emptyList())
    val findResults: StateFlow<List<ProductEntity>> = _findResults.asStateFlow()

    private val _pickedProduct = MutableStateFlow<ProductEntity?>(null)
    val pickedProduct: StateFlow<ProductEntity?> = _pickedProduct.asStateFlow()

    private val _pickedLocations = MutableStateFlow<List<ShelfLocationRef>>(emptyList())
    val pickedLocations: StateFlow<List<ShelfLocationRef>> = _pickedLocations.asStateFlow()

    /** 库外条码（商品库查不到）——引导「标记待补全」，让位置信息不至于丢。 */
    private val _orphanLocations = MutableStateFlow<List<ShelfLocationRef>>(emptyList())
    val orphanLocations: StateFlow<List<ShelfLocationRef>> = _orphanLocations.asStateFlow()

    fun onFindKeywordChange(keyword: String) {
        _findKeyword.value = keyword
        if (keyword.isBlank()) {
            _findResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            _findResults.value = withContext(Dispatchers.IO) {
                productDao.search(keyword.trim()).take(20)
            }
        }
    }

    /**
     * 扫码找货位：一条码进来，直接给「它现在在哪」。
     *
     * **位置码优先**（WMS 行业标配，见 `_reports/shelf-refs/README.md`）：先当它是货架/层/格的
     * 定位码去解（`SX:<tagId>`，旧裸码兼容）——扫格标签就是"我就要去这个位置"，
     * 命中即直达；解不出或没这个贴，再按商品条码找货。两条路互不干扰，
     * 因为商品条码几乎不可能撞上 8 位 tagId，即便撞了也是"先定位"更符合扫码意图。
     */
    fun findProductByCode(code: String) {
        val clean = code.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            ShelfNfc.decodeTagPayload(clean)?.let { tagId ->
                if (shelfRepo.findByTagId(tagId) != null) {
                    onNfcTag(tagId)
                    return@launch
                }
            }
            val product = lookupLocal(clean)
            if (product == null) {
                // 商品库没有 → 用绑定时的条码快照反查（脏数据的容错出口）
                val locs = shelfRepo.locationsOfBarcode(clean)
                _orphanLocations.value = locs
                _pickedProduct.value = null
                _pickedLocations.value = emptyList()
                _toastEvent.tryEmit(
                    if (locs.isEmpty()) "库里查不到 $clean，也没有绑定记录"
                    else "库里查不到 $clean，但有 ${locs.size} 处绑定记录",
                )
                return@launch
            }
            selectProduct(product)
        }
    }

    fun selectProduct(product: ProductEntity) {
        viewModelScope.launch {
            _pickedProduct.value = product
            _orphanLocations.value = emptyList()
            _pickedLocations.value = shelfRepo.locationsOfProduct(product.id)
        }
    }

    /**
     * 货架卡片点进商品详情用：拿「能导航的码」。
     * 优先绑定快照里的条码；快照没有（绑定时的商品无码/脏数据）→ 回查本地商品库补；
     * 都拿不到才返回 null（调用方给一句提示，不做无声失败）。
     */
    suspend fun navCodeOf(binding: BindingUi): String? {
        binding.barcode?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val product = productDao.getById(binding.productId) ?: return null
        return product.barcode?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun clearFind() {
        _findKeyword.value = ""
        _findResults.value = emptyList()
        _pickedProduct.value = null
        _pickedLocations.value = emptyList()
        _orphanLocations.value = emptyList()
    }

    /** 点「去这里」：把目标切到该位置（跳到那一格）。 */
    fun jumpTo(ref: ShelfLocationRef) {
        landOn(ref.shelfId, ref.levelId, ref.slotId)
        _toastEvent.tryEmit("已切到「${ref.display}」")
    }

    /**
     * 真正「落到某个位置」：
     * ① 选中货架 ② **把它所在的分区打开** ③ 选到层/格 ④ 发一次滚动信号。
     *
     * 四件事缺一不可（老板 2026-09-14 实测反馈「碰一下只是调整到货架页、不是跳转到指定货架」）：
     * - 不切分区：I23 的货架条只画**当前分区**的货架，命中的货架挂在别的分区里 → 屏幕上没有它，
     *   看着就像"没跳过去"（分区为空时整页甚至只显示「这个分区下还没有货架」）；
     * - 不选层/格：正文停在原来那一处；
     * - 不发滚动信号：长货架条/多层货架里，目标排在屏幕外，等于没到。
     */
    private fun landOn(shelfId: Long, levelId: Long?, slotId: Long?) {
        val shelf = tree.value.firstOrNull { it.id == shelfId } ?: return
        _selectedShelfId.value = shelfId
        Session.shelfId = shelfId
        // zoneId = null 表示未分类 → 回到顶层（顶层展示全店货架，未分类的自然可见）
        _currentZoneId.value = shelf.zoneId
        when {
            levelId == null -> selectShelf(shelfId)
            slotId == null -> selectLevelOnly(shelfId, levelId)
            else -> selectLevel(shelfId, levelId, slotId)
        }
        _jumpTarget.value = JumpTarget(shelfId, levelId, slotId, ++jumpNonce)
    }

    // ============================== 巡架标记 + 出签（配合现有打印系统） ==============================

    /**
     * 巡架错位标记：落 [DataMarkEntity]（只标记、不改变库存 —— 铁律 12）。
     * 备注写**层/格名字**而不是 id，导出给人看时能直接读懂。
     */
    fun markDiscrepancy(name: String, sku: String, barcode: String, markType: String, note: String, photo: String? = null) {
        viewModelScope.launch {
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val where = _target.value?.label
            dataMarkDao.insert(
                DataMarkEntity(
                    sku = sku.ifBlank { "UNKNOWN" },
                    barcode = barcode,
                    name = name,
                    markType = markType,
                    note = if (where.isNullOrBlank()) note else "【$where】$note",
                    photo = photo,
                    status = DataMarkEntity.STATUS_OPEN,
                    createdBy = appConfig.staffName,
                    createdAt = now,
                ),
            )
            _toastEvent.tryEmit("已登记异常至数据标记队列")
        }
    }

    /** 巡架发现商品不在该位置 → 打错位标记（不改库存，只标记）。 */
    fun toggleMisplaced(binding: BindingUi) {
        viewModelScope.launch {
            val where = _target.value?.label.orEmpty()
            if (binding.misplaced) {
                shelfRepo.clearMisplaced(binding.id)
                _toastEvent.tryEmit("已取消错位标记")
            } else {
                shelfRepo.setMisplaced(binding.id, "【$where】实物不在此位置")
                _toastEvent.tryEmit("已标记错位（只记不改数）")
            }
            requestSync()
        }
    }

    fun misplacedCount(): Int = (tree.value.sumOf { it.levels.sumOf { lv -> lv.slots.sumOf { s -> s.bindings.count { b -> b.misplaced } } + lv.directBindings.count { it.misplaced } } })

    /**
     * 位置标签出签：入**现有打印队列**（`SCENE_SHELF`），蓝牙/网络由 [PrintRepo] 按默认打印机自动分流。
     * 标签内容 = 自定义名字大字 + 稳定定位码条码（改名不用重贴）。
     */
    fun enqueueLabel(name: String, tagId: String, parentPath: String) {
        viewModelScope.launch {
            val payload = ShelfNfc.encodeTagPayload(tagId)
            if (payload == null) {
                _toastEvent.tryEmit("标签码异常，无法出签")
                return@launch
            }
            printRepo.enqueue(
                PrintJobEntity(
                    sku = "SHELF:$tagId",
                    name = name,
                    spec = parentPath,
                    barcode = payload,
                    sellPrice = 0.0,
                    qty = 1,
                    sourceScene = PrintJobEntity.SCENE_SHELF,
                    createdBy = appConfig.staffName,
                ),
            )
            _toastEvent.tryEmit("「$name」标签已入打印队列")
        }
    }

    /**
     * 一键出整架标签：货架 + 各层 + 各格（现场贴完即用）。
     */
    fun enqueueShelfLabels(shelf: ShelfUi) {
        viewModelScope.launch {
            val jobs = mutableListOf<PrintJobEntity>()
            fun job(name: String, tagId: String, parent: String) {
                val payload = ShelfNfc.encodeTagPayload(tagId) ?: return
                jobs += PrintJobEntity(
                    sku = "SHELF:$tagId",
                    name = name,
                    spec = parent,
                    barcode = payload,
                    sellPrice = 0.0,
                    qty = 1,
                    sourceScene = PrintJobEntity.SCENE_SHELF,
                    createdBy = appConfig.staffName,
                )
            }
            job(shelf.name, shelf.tagId, "")
            shelf.levels.forEach { level ->
                job(level.name, level.tagId, shelf.name)
                level.slots.forEach { slot -> job(slot.name, slot.tagId, "${shelf.name} · ${level.name}") }
            }
            if (jobs.isEmpty()) {
                _toastEvent.tryEmit("没有可出签的位置")
                return@launch
            }
            printRepo.enqueueBatch(jobs)
            _toastEvent.tryEmit("「${shelf.name}」共 ${jobs.size} 张标签已入打印队列")
        }
    }

    // ============================== 碰贴直达（按稳定 tagId 定位） ==============================

    /**
     * 碰贴/扫格码 → 定位到对应货架 / 层 / 格。
     *
     * 载荷解出来的是**稳定 tagId**（与显示名解耦），所以改过名的位置照样命中。
     * 等树加载到位再定位（贴可能在别的页面刚建好）；超时则只提示不卡死。
     *
     * 定位动作全部交给 [landOn]（含"把分区一起打开"与滚动信号），
     * 否则 I23 引入文件夹树后，命中别的分区的货架时屏幕上根本看不到它。
     */
    fun onNfcTag(tagId: String, onFound: (String) -> Unit = {}) {
        viewModelScope.launch {
            val hit = shelfRepo.findByTagId(tagId)
            if (hit == null) {
                _toastEvent.tryEmit("这个贴还没对应位置（可去「写贴」给它起个名）")
                return@launch
            }
            _selectedShelfId.value = hit.shelfId
            Session.shelfId = hit.shelfId

            val ready = withTimeoutOrNull(3_000) {
                tree.first { shelves -> shelves.any { it.id == hit.shelfId } }
            }
            if (ready == null) {
                _toastEvent.tryEmit("货架「${hit.label}」还没加载出来")
                return@launch
            }

            landOn(hit.shelfId, hit.levelId, hit.slotId)
            onFound(hit.label)
        }
    }

    // ============================== 未命中兜底（铁律 12：人工慢慢解决的通道） ==============================

    /**
     * 扫码没匹配到商品 → 落「数据标记队列」的待绑条码项，由人后续在管家婆侧补齐。
     * **不改任何商品数据**（铁律 12）。
     */
    fun markUnmatched(codes: List<String>) {
        val cleaned = codes.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return
        viewModelScope.launch {
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val where = _target.value?.label.orEmpty()
            cleaned.forEach { code ->
                dataMarkDao.insert(
                    DataMarkEntity(
                        sku = "UNKNOWN",
                        barcode = code,
                        name = "扫码未命中",
                        markType = DataMarkEntity.TYPE_BARCODE_SUPPLEMENT,
                        note = "【$where】扫码未匹配到商品，待人工补码",
                        status = DataMarkEntity.STATUS_OPEN,
                        createdBy = appConfig.staffName,
                        createdAt = now,
                    ),
                )
            }
            _toastEvent.tryEmit("已把 ${cleaned.size} 个未命中条码标进待补队列")
        }
    }

    /**
     * U5 迁移保留：入库收货连扫 → 批量入打印队列（S5「入库即打」）。
     * 只查库补 sku/spec，不改库存 —— 对账以管家婆为准（铁律 12）。
     */
    fun enqueueInboundPrint(codes: List<String>) {
        val cleaned = codes.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return
        viewModelScope.launch {
            var added = 0
            var missed = 0
            val jobs = mutableListOf<PrintJobEntity>()
            cleaned.groupingBy { it }.eachCount().forEach { (barcode, qty) ->
                val p = lookupLocal(barcode)
                if (p != null) {
                    jobs += PrintJobEntity(
                        sku = p.sku ?: barcode,
                        name = p.name,
                        spec = p.spec,
                        barcode = p.barcode ?: barcode,
                        sellPrice = p.sellPrice,
                        qty = qty,
                        sourceScene = PrintJobEntity.SCENE_INBOUND,
                        createdBy = appConfig.staffName,
                    )
                    added++
                } else {
                    missed++
                }
            }
            if (jobs.isNotEmpty()) printRepo.enqueueBatch(jobs)
            _toastEvent.tryEmit(
                if (missed > 0) "入库出签 $added 款已入打印队列；$missed 个条码库里查不到"
                else "已将 $added 款商品（共 ${cleaned.size} 张）加入打印队列",
            )
        }
    }
}
