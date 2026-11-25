package com.retaildemo.v2.data.repo

import com.retaildemo.v2.data.local.dao.ShelfDao
import com.retaildemo.v2.data.local.entity.ShelfBindingEntity
import com.retaildemo.v2.data.local.entity.ShelfEntity
import com.retaildemo.v2.data.local.entity.ShelfLevelEntity
import com.retaildemo.v2.data.local.entity.ShelfSlotEntity
import com.retaildemo.v2.data.local.entity.ShelfZoneEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 商品的一个位置。一品多货位 ⇒ 一次返回**列表**（老板 2026-09-11「一品多货位做好」）。
 * 解绑某一处只删那一条 binding，其余位置原样保留。
 */
data class ShelfLocationRef(
    val bindingId: Long,
    val shelfId: Long,
    val shelfName: String,
    val levelId: Long,
    val levelName: String,
    val slotId: Long?,
    val slotName: String?,
    val misplaced: Boolean = false,
) {
    /** 人读位置串：`A架 · 1层 · 左`；只绑到层时末段省略。 */
    val display: String
        get() = buildString {
            append(shelfName)
            append(" · ")
            append(levelName)
            if (!slotName.isNullOrBlank()) {
                append(" · ")
                append(slotName)
            }
        }

    /** 结构化内部码（打印小字/调试用）：S<货架>/L<层>[/C<格>]，**不是**可解析的业务码。 */
    val internalRef: String
        get() = buildString {
            append("S").append(shelfId).append("/L").append(levelId)
            slotId?.let { append("/C").append(it) }
        }
}

/** 碰贴/扫码定位命中：货架、或层、或格。 */
data class ShelfTagHit(
    val shelfId: Long,
    val levelId: Long?,
    val slotId: Long?,
    val label: String,
)

/** 同父下的近似名候选（「完全自定义命名」防重名用）。 */
data class NameCandidate(
    val id: Long,
    val name: String,
    /** true = 归一化后完全相同（最该合并的那个）。 */
    val exact: Boolean,
)

/**
 * I20 货架仓库：三级（货架→层→格）+ 多对多绑定，**全部本地 Room，零网络**。
 * I22 加多终端同步元数据（写路径一律置 dirty + updated_at，删除走软删）。
 * I23 加分区/楼层（文件夹树）——分区可嵌套，货架挂在分区下。
 *
 * 两条不可违背的约束（预研 §4.1）：
 * 1. 名字完全自定义 ⇒ 内部引用一律用 `id`，**本类不存在任何按名字解析层级的逻辑，也禁止引入码格式正则**。
 * 2. 位置与数量解耦 ⇒ 本类不记录、不修改任何数量/库存（铁律 12）。
 */
@Singleton
class ShelfRepo @Inject constructor(
    private val dao: ShelfDao,
) {
    companion object {
        /** 去掉 0/O/1/I 等易混字符，便于人肉抄写。 */
        private const val TAG_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        private const val TAG_ID_LEN = 8
    }

    private fun now(): Long = System.currentTimeMillis()

    // ============================== 响应式读取（首页整树） ==============================

    fun observeZones(): Flow<List<ShelfZoneEntity>> = dao.observeZones()
    fun observeShelves(): Flow<List<ShelfEntity>> = dao.observeShelves()
    fun observeAllLevels(): Flow<List<ShelfLevelEntity>> = dao.observeAllLevels()
    fun observeAllSlots(): Flow<List<ShelfSlotEntity>> = dao.observeAllSlots()
    fun observeAllBindings(): Flow<List<ShelfBindingEntity>> = dao.observeAllBindings()

    suspend fun zones(): List<ShelfZoneEntity> = dao.zones()
    suspend fun shelves(): List<ShelfEntity> = dao.shelves()
    suspend fun levelsOf(shelfId: Long): List<ShelfLevelEntity> = dao.levelsOf(shelfId)
    suspend fun slotsOf(levelId: Long): List<ShelfSlotEntity> = dao.slotsOf(levelId)

    // ============================== 分区 / 楼层（I23 文件夹树） ==============================

    /**
     * 建分区/楼层。`parentId == null` = 顶层（楼层）；非空 = 其下的分区。
     * 名字空白抛 [IllegalArgumentException]。
     */
    suspend fun createZone(name: String, parentId: Long? = null, note: String? = null): Long {
        val clean = requireName(name)
        return dao.insertZone(
            ShelfZoneEntity(
                parentId = parentId,
                name = clean,
                tagId = generateTagId(),
                note = normalizeNote(note),
                sortOrder = dao.nextZoneSort(parentId),
            ),
        )
    }

    suspend fun renameZone(id: Long, newName: String) = dao.renameZone(id, requireName(newName), now())
    suspend fun setZoneNote(id: Long, note: String?) = dao.setZoneNote(id, normalizeNote(note), now())

    /** 分区换父级（挂到别的楼层/分区下）。 */
    suspend fun moveZoneTo(id: Long, parentId: Long?) = dao.setZoneParent(id, parentId, now())

    /** 货架换分区（挂到某分区下；null = 移出到顶层未分类）。 */
    suspend fun moveShelfToZone(shelfId: Long, zoneId: Long?) = dao.setShelfZone(shelfId, zoneId, now())

    /** 分区上移/下移（同父内）。 */
    suspend fun moveZone(id: Long, delta: Int) {
        val zone = dao.zoneById(id) ?: return
        val ids = dao.zoneIdsUnder(zone.parentId).toMutableList()
        if (!swapIds(ids, id, delta)) return
        ids.forEachIndexed { idx, zid -> dao.setZoneSort(zid, idx, now()) }
    }

    /** 删除分区前的影响面（给二次确认用）：子分区数 + 其下货架数（含嵌套）。 */
    suspend fun zoneImpact(zoneId: Long): Pair<Int, Int> {
        var childZones = 0
        var shelves = 0
        val stack = ArrayDeque<Long>()
        stack.add(zoneId)
        while (stack.isNotEmpty()) {
            val cur = stack.removeFirst()
            val children = dao.zoneIdsUnder(cur)
            childZones += children.size
            shelves += dao.shelfIdsUnder(cur).size
            children.forEach { stack.add(it) }
        }
        return childZones to shelves
    }

    /** 删除分区：递归软删子分区与其下所有货架（含层/格/绑定）。 */
    suspend fun deleteZone(zoneId: Long) = dao.deleteZoneCascade(zoneId, now())

    // ============================== 建档（name 完全自定义 + 生成稳定 tagId） ==============================

    /** 建货架（可挂在分区下）。返回新 id；名字空白抛 [IllegalArgumentException]。 */
    suspend fun createShelf(name: String, note: String? = null, zoneId: Long? = null): Long {
        val clean = requireName(name)
        return dao.insertShelf(
            ShelfEntity(
                name = clean,
                tagId = generateTagId(),
                note = normalizeNote(note),
                zoneId = zoneId,
                sortOrder = dao.nextShelfSort(zoneId),
            ),
        )
    }

    suspend fun createLevel(shelfId: Long, name: String): Long {
        val clean = requireName(name)
        return dao.insertLevel(
            ShelfLevelEntity(
                shelfId = shelfId,
                name = clean,
                tagId = generateTagId(),
                sortOrder = dao.nextLevelSort(shelfId),
            ),
        )
    }

    suspend fun createSlot(levelId: Long, name: String): Long {
        val clean = requireName(name)
        return dao.insertSlot(
            ShelfSlotEntity(
                levelId = levelId,
                name = clean,
                tagId = generateTagId(),
                sortOrder = dao.nextSlotSort(levelId),
            ),
        )
    }

    // ============================== 改名（只动 name，tagId 永不变） ==============================

    suspend fun renameShelf(id: Long, newName: String) = dao.renameShelf(id, requireName(newName), now())
    suspend fun renameLevel(id: Long, newName: String) = dao.renameLevel(id, requireName(newName), now())
    suspend fun renameSlot(id: Long, newName: String) = dao.renameSlot(id, requireName(newName), now())

    // ============================== 备注（三级都有；只给人看，不参与任何逻辑） ==============================

    /**
     * 写备注（2026-09-13 老板补需求："没有备注的地方"）。
     * 空白一律归一为 null —— 「没有备注」必须是**一个**状态，不能空串/null 两种。
     */
    suspend fun setShelfNote(id: Long, note: String?) = dao.setShelfNote(id, normalizeNote(note), now())
    suspend fun setLevelNote(id: Long, note: String?) = dao.setLevelNote(id, normalizeNote(note), now())
    suspend fun setSlotNote(id: Long, note: String?) = dao.setSlotNote(id, normalizeNote(note), now())

    private fun normalizeNote(note: String?): String? = note?.trim()?.takeIf { it.isNotEmpty() }

    // ============================== 排序（显式 sortOrder，自定义名无法比较） ==============================

    /** 货架在**同分区内**上移/下移（delta = -1 / +1），重排为紧凑序号。 */
    suspend fun moveShelf(id: Long, delta: Int) {
        val shelf = dao.shelfById(id) ?: return
        val list = dao.shelves().filter { it.zoneId == shelf.zoneId }.toMutableList()
        if (!swapBy(list, id, delta, { it.id })) return
        list.forEachIndexed { idx, e -> dao.setShelfSort(e.id, idx, now()) }
    }

    suspend fun moveLevel(id: Long, shelfId: Long, delta: Int) {
        val list = dao.levelsOf(shelfId).toMutableList()
        if (!swapBy(list, id, delta, { it.id })) return
        list.forEachIndexed { idx, e -> dao.setLevelSort(e.id, idx, now()) }
    }

    suspend fun moveSlot(id: Long, levelId: Long, delta: Int) {
        val list = dao.slotsOf(levelId).toMutableList()
        if (!swapBy(list, id, delta, { it.id })) return
        list.forEachIndexed { idx, e -> dao.setSlotSort(e.id, idx, now()) }
    }

    private fun <T> swapBy(list: MutableList<T>, id: Long, delta: Int, idOf: (T) -> Long): Boolean {
        val i = list.indexOfFirst { idOf(it) == id }
        if (i < 0) return false
        val j = i + delta
        if (j < 0 || j >= list.size) return false
        val tmp = list[i]
        list[i] = list[j]
        list[j] = tmp
        return true
    }

    private fun swapIds(list: MutableList<Long>, id: Long, delta: Int): Boolean {
        val i = list.indexOf(id)
        if (i < 0) return false
        val j = i + delta
        if (j < 0 || j >= list.size) return false
        val tmp = list[i]
        list[i] = list[j]
        list[j] = tmp
        return true
    }

    // ============================== 查重（同父下名字唯一） ==============================

    suspend fun similarShelves(name: String): List<NameCandidate> =
        rankCandidates(dao.shelves().map { it.id to it.name }, name)

    suspend fun similarLevels(shelfId: Long, name: String): List<NameCandidate> =
        rankCandidates(dao.levelsOf(shelfId).map { it.id to it.name }, name)

    suspend fun similarSlots(levelId: Long, name: String): List<NameCandidate> =
        rankCandidates(dao.slotsOf(levelId).map { it.id to it.name }, name)

    private fun rankCandidates(source: List<Pair<Long, String>>, name: String): List<NameCandidate> {
        return source.mapNotNull { (id, existing) ->
            val score = nameSimilarityScore(existing, name) ?: return@mapNotNull null
            Triple(id, existing, score)
        }.sortedBy { it.third }
            .map { NameCandidate(it.first, it.second, it.third == 0) }
    }

    // ============================== 合并（事务：迁绑定 → 删源） ==============================

    suspend fun mergeLevel(fromLevelId: Long, toLevelId: Long) {
        if (fromLevelId == toLevelId) return
        dao.mergeLevelTx(fromLevelId, toLevelId, now())
    }

    suspend fun mergeSlot(fromSlotId: Long, toSlotId: Long) {
        if (fromSlotId == toSlotId) return
        dao.mergeSlotTx(fromSlotId, toSlotId, now())
    }

    // ============================== 绑定 / 解绑（多对多） ==============================

    /**
     * 绑定商品到位置。`slotId == null` = 只绑到层（老板拍板允许）。
     * **幂等**：同一位置同一商品重复扫不会产生重复记录（返回已有记录 id）。
     */
    suspend fun bind(
        levelId: Long,
        slotId: Long?,
        productId: Long,
        barcode: String?,
        name: String,
        by: String = "",
    ): Long {
        dao.findBinding(levelId, slotId, productId)?.let { return it.id }
        return dao.insertBinding(
            ShelfBindingEntity(
                levelId = levelId,
                slotId = slotId,
                productId = productId,
                barcode = barcode,
                name = name,
                createdBy = by,
            ),
        )
    }

    /**
     * 解绑**一处**。一品多货位语义：只删这一条记录（软删并同步），该商品在别的位置的绑定不受影响。
     * 返回 true 表示确实删掉了一条。
     */
    suspend fun unbind(bindingId: Long): Boolean {
        val existing = dao.bindingById(bindingId) ?: return false
        if (existing.deleted) return false
        dao.softDeleteBinding(bindingId, now())
        return true
    }

    suspend fun bindingsOfLevel(levelId: Long): List<ShelfBindingEntity> = dao.bindingsByLevel(levelId)
    suspend fun bindingsOfSlot(slotId: Long): List<ShelfBindingEntity> = dao.bindingsBySlot(slotId)

    /** 一品多货位反查：该商品**所有**位置（含只绑到层的）。 */
    suspend fun locationsOfProduct(productId: Long): List<ShelfLocationRef> =
        dao.bindingsByProduct(productId).mapNotNull { toLocationRef(it) }

    /** 按条码反查（商品库可能没同步到，用绑定时的条码快照兜底）。 */
    suspend fun locationsOfBarcode(barcode: String): List<ShelfLocationRef> =
        dao.bindingsByBarcode(barcode.trim()).mapNotNull { toLocationRef(it) }

    private suspend fun toLocationRef(b: ShelfBindingEntity): ShelfLocationRef? {
        val level = dao.levelById(b.levelId) ?: return null
        val shelf = dao.shelfById(level.shelfId) ?: return null
        val slot = b.slotId?.let { dao.slotById(it) }
        return ShelfLocationRef(
            bindingId = b.id,
            shelfId = shelf.id,
            shelfName = shelf.name,
            levelId = level.id,
            levelName = level.name,
            slotId = slot?.id,
            slotName = slot?.name,
            misplaced = b.misplaced,
        )
    }

    // ============================== 巡架标记（只标记，不改库存） ==============================

    suspend fun setMisplaced(bindingId: Long, note: String?) = dao.setMisplaced(bindingId, true, note, now())
    suspend fun clearMisplaced(bindingId: Long) = dao.setMisplaced(bindingId, false, null, now())
    suspend fun misplacedCount(): Int = dao.misplacedCount()

    // ============================== 按 tagId 定位（碰贴直达） ==============================

    /** 由稳定 tagId 找到对象（货架优先 → 层 → 格），供 NFC 碰贴与条码兜底共用。 */
    suspend fun findByTagId(tagId: String): ShelfTagHit? {
        val t = tagId.trim()
        if (t.isEmpty()) return null

        dao.shelfByTag(t)?.let { return ShelfTagHit(it.id, null, null, it.name) }

        dao.levelByTag(t)?.let { level ->
            val shelf = dao.shelfById(level.shelfId)
            return ShelfTagHit(level.shelfId, level.id, null, "${shelf?.name.orEmpty()} · ${level.name}")
        }

        dao.slotByTag(t)?.let { slot ->
            val level = dao.levelById(slot.levelId) ?: return null
            val shelf = dao.shelfById(level.shelfId)
            return ShelfTagHit(
                level.shelfId,
                level.id,
                slot.id,
                "${shelf?.name.orEmpty()} · ${level.name} · ${slot.name}",
            )
        }

        return null
    }

    // ============================== 删除（一律软删并同步；I22） ==============================

    suspend fun deleteShelf(shelfId: Long) = dao.deleteShelfCascade(shelfId, now())
    suspend fun deleteLevel(levelId: Long) = dao.deleteLevelCascade(levelId, now())
    suspend fun deleteSlot(slotId: Long) = dao.deleteSlotCascade(slotId, now())

    // ============================== tagId 读取（写贴 / 出签用） ==============================

    suspend fun tagIdOfShelf(id: Long): String? = dao.shelfById(id)?.tagId
    suspend fun tagIdOfLevel(id: Long): String? = dao.levelById(id)?.tagId
    suspend fun tagIdOfSlot(id: Long): String? = dao.slotById(id)?.tagId

    // ============================== 内部工具 ==============================

    private fun requireName(name: String): String {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "名字不能为空" }
        return clean
    }

    /** 生成全局（四张表范围内）唯一的 tagId，冲突自动重试。 */
    private suspend fun generateTagId(): String {
        repeat(8) {
            val candidate = buildString {
                repeat(TAG_ID_LEN) { append(TAG_ALPHABET[Random.nextInt(TAG_ALPHABET.length)]) }
            }
            val taken = dao.countShelfTag(candidate) + dao.countLevelTag(candidate) +
                dao.countSlotTag(candidate) + dao.countZoneTag(candidate)
            if (taken == 0) return candidate
        }
        // 理论上到不了这里；兜底用时间戳保证不重复
        return "T" + System.currentTimeMillis().toString(36).uppercase()
    }
}

// =====================================================================================
// 名字相似度（纯函数，JVM 可测）——「完全自定义命名」的固有风险：同一位置被起了两个名，
// 货会分散在两边。UI 在建名前用它给出「是不是这个？」的合并建议。
// =====================================================================================

private val NAME_NOISE = setOf(
    ' ', '\t', '\n', '\r', '，', '。', '、', '；', '：', ',', '.', ';', ':',
    '·', '-', '—', '_', '/', '\\', '|', '(', ')', '（', '）', '[', ']', '【', '】',
)

/** 归一化：去空白与常见标点、转小写。名字完全自定义 ⇒ 只能这样软性比对，不做任何码解析。 */
internal fun normalizeName(raw: String): String =
    raw.trim().lowercase().filter { it !in NAME_NOISE }

/**
 * 近似度打分：0 = 完全相同（最该合并），1 = 互相包含，2 = 首字相同且编辑距离 ≤1；不相似返回 null。
 *
 * 首字必须相同这条很关键：中文位置名普遍很短（「上层」「顶层」「底层」），只按编辑距离会把
 * 「顶层/底层」都算成「上层」的近邻，候选列表全是噪音。加上首字约束后，「上层↔上曾」这类
 * 真·笔误仍能命中，而「顶层/底层」不会被误报。
 */
internal fun nameSimilarityScore(a: String, b: String): Int? {
    val x = normalizeName(a)
    val y = normalizeName(b)
    if (x.isEmpty() || y.isEmpty()) return null
    if (x == y) return 0
    if (x.contains(y) || y.contains(x)) return 1
    if (x.first() == y.first() && editDistanceAtMost1(x, y)) return 2
    return null
}

/** 编辑距离是否 ≤1（带剪枝，不做完整 DP）。 */
internal fun editDistanceAtMost1(a: String, b: String): Boolean {
    if (a == b) return true
    if (kotlin.math.abs(a.length - b.length) > 1) return false
    var i = 0
    var j = 0
    var diff = 0
    while (i < a.length && j < b.length) {
        if (a[i] == b[j]) {
            i++
            j++
            continue
        }
        if (++diff > 1) return false
        when {
            a.length > b.length -> i++
            a.length < b.length -> j++
            else -> {
                i++
                j++
            }
        }
    }
    return diff + (a.length - i) + (b.length - j) <= 1
}
