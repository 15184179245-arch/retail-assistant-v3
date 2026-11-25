package com.retaildemo.v2.data.repo

import android.content.Context
import com.retaildemo.v2.data.local.dao.ShelfDao
import com.retaildemo.v2.data.local.entity.ShelfBindingEntity
import com.retaildemo.v2.data.local.entity.ShelfEntity
import com.retaildemo.v2.data.local.entity.ShelfLevelEntity
import com.retaildemo.v2.data.local.entity.ShelfSlotEntity
import com.retaildemo.v2.data.local.entity.ShelfZoneEntity
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.data.remote.ShelfSyncChanges
import com.retaildemo.v2.data.remote.ShelfSyncRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * I22 货架多终端同步仓库（老板令：货架信息所有终端都能同步到，含 NFC 与扫货架码）。
 *
 * 协议（服务端 `src/gateway/shelf_store.py`，设计见 I22 卡）：
 * - **推送本地脏行**（`dirty = 1`，含软删行）→ 服务端行级 LWW 合并；
 * - **按水位拉增量**（`server_seq > lastSeq`）→ 落本地 Room（按 tagId/uid 对位，dirty 置 0）；
 * - **对象主键 = tagId**：NFC 贴与货位标签里就是 `SX:<tagId>`，跨机对位靠它；
 *   绑定的 tagId 是创建时生成的 uid（绑定没有贴纸）。
 *
 * 四条纪律：
 * 1. **离线优先不破**：任何异常（断网/服务端 5xx/解析失败）都只让本次同步白跑，
 *    本地操作（建架/绑定/找货）永远照常——同步只是把结果带给别的设备。
 * 2. **本地脏行不被拉取覆盖**：本地刚改的行带着待上传标记，本轮推给服务端后由服务端裁决；
 *    在此之前服务端回带的旧版不许盖本地（否则「我刚改的名字被同步洗回旧的」）。
 * 3. 服务端对**非脏行**权威：直接覆盖落库（dirty 置 0）。
 * 4. 删除靠软删同步：`deleted = 1` 的行跟着水位走，别的设备据此删本地，绝不「复活」。
 */
@Singleton
class ShelfSyncRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: retaildemoApi,
    private val dao: ShelfDao,
    private val config: AppConfig,
) {
    companion object {
        private const val PREFS = "shelf_sync"
        private const val KEY_WATERMARK = "last_seq"
        private const val KEY_CLOCK_OFFSET = "clock_offset_ms"
        private const val MAX_ROWS_PER_PUSH = 2000
    }

    /** 同步结果摘要（给设置页/日志用；UI 不需要弹窗）。 */
    data class SyncOutcome(
        val ok: Boolean = false,
        val pushed: Int = 0,
        val applied: Int = 0,
        val serverSeq: Long = 0,
        val error: String? = null,
    )

    /**
     * I31/B1：一批实际推送的脏行——wire 是上送载荷，*Ids 是这批的本地行 id 范围。
     * 清脏只许清这个范围：超限截断行、推送期间新写入的行必须保持 dirty。
     */
    private data class PushBatch(
        val wire: Map<String, List<Map<String, Any?>>>,
        val zoneIds: List<Long> = emptyList(),
        val shelfIds: List<Long> = emptyList(),
        val levelIds: List<Long> = emptyList(),
        val slotIds: List<Long> = emptyList(),
        val bindingIds: List<Long> = emptyList(),
    )

    /** I31/B1：syncNow 互斥——触发源有 debounce 收集器/进页手动/Workers，并发跑会互踩缓存与清脏。 */
    private val syncMutex = Mutex()

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 已拉取到的服务端水位（server_seq）。 */
    fun watermark(): Long = prefs().getLong(KEY_WATERMARK, 0L)

    /**
     * 本机时钟相对服务器的偏移（毫秒，server − local）。
     *
     * 为什么必须有它（2026-09-13 逆向熊掌柜经验 + 自家账单同步教训）：
     * 行级 LWW 比较的是 `updated_at`。店里真实存在设备时钟差（账单一度因此被服务器静默 skip）；
     * 若某台手机慢 8 小时，它新建的货架行时间戳看着比服务器上任意旧数据都旧 → 永远被判 stale、同步不上去。
     * 每次同步用响应里的 `server_time_ms` 校准偏移，推送组包时按校正值走，LWW 才有意义。
     */
    fun clockOffsetMs(): Long = prefs().getLong(KEY_CLOCK_OFFSET, 0L)

    /**
     * 执行一次同步：先推后拉。
     * 返回摘要；失败时 `ok = false` 且带 error（调用方按 best-effort 处理，不弹错）。
     * I31/B1：全程持 [syncMutex]——两个触发源并发时，tagCache 互踩 + 双方都全量清脏会互吞数据。
     */
    suspend fun syncNow(): SyncOutcome = syncMutex.withLock {
        try {
            val pushed = pushDirty()
            val lastSeq = watermark()
            val resp = api.shelfSync(
                ShelfSyncRequest(
                    deviceId = config.deviceId,
                    lastSeq = lastSeq,
                    changes = pushed.wire,
                ),
            )
            val body = resp.body()
            if (!resp.isSuccessful || body == null || body.success != true || body.data == null) {
                return@withLock SyncOutcome(ok = false, error = body?.error ?: "HTTP ${resp.code()}")
            }
            val data = body.data
            // 时钟校正（在应用拉取行之前算好，供下次推送用；LWW 的前提见 clockOffsetMs 注释）
            data.serverTimeMs?.let { serverMs ->
                if (serverMs > 0) {
                    val offset = serverMs - System.currentTimeMillis()
                    if (kotlin.math.abs(offset) > 1000) { // 1 秒内视为网络延迟噪声，不记
                        prefs().edit().putLong(KEY_CLOCK_OFFSET, offset).apply()
                    }
                }
            }
            // 顺序关键：**先清脏、再应用拉取**。
            // 本批脏行已经推给服务端了，本地不再是「未上传」状态——不清脏的话，
            // 服务端在响应里回带的合法更新（例如别的设备刚删了这一行）会因本地 dirty 被跳过，
            // 之后再清脏，这次更新就永久丢了（同一毫秒时间戳下还会被 LWW 当成平局）。
            // I31/B1：只清「实际推送过」的 id 范围（截断行/推送中新写入的行保持 dirty）。
            clearPushedDirty(pushed)
            val applied = applyPulled(data.changes)
            // 水位取「本次响应里服务器给的水位」，下次只拉增量
            if (data.serverSeq > lastSeq) {
                prefs().edit().putLong(KEY_WATERMARK, data.serverSeq).apply()
            }
            SyncOutcome(
                ok = true,
                pushed = pushed.wire.values.sumOf { it.size },
                applied = applied,
                serverSeq = data.serverSeq,
            )
        } catch (e: Exception) {
            // 断网/超时/解析失败：本次白跑，下次触发补同步（离线优先不破）
            SyncOutcome(ok = false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    // ============================== 推送：脏行 → 服务端行 ==============================

    private suspend fun pushDirty(): PushBatch {
        refreshCaches() // id→tagId 映射（推送组包需要把本地外键翻译成跨设备标识）
        val zones = dao.dirtyZones()
        val shelves = dao.dirtyShelves()
        val levels = dao.dirtyLevels()
        val slots = dao.dirtySlots()
        val bindings = dao.dirtyBindings()
        val total = zones.size + shelves.size + levels.size + slots.size + bindings.size
        // 极端情况下（比如首次同步上千行）——也要能同步，只是拆批由上层重复调用完成。
        // 这里保守截断为本批上限，剩余 dirty 留待下次触发（不丢，只是慢一点）。
        // I31/B1：截断时被留下的行 id 不进 PushBatch ⇒ 不会被清脏，语义从此闭合。
        val cut = total > MAX_ROWS_PER_PUSH
        fun <T : Any> batch(list: List<T>) = if (cut) list.take(MAX_ROWS_PER_PUSH) else list
        val pz = batch(zones)
        val ps = batch(shelves)
        val pl = batch(levels)
        val pt = batch(slots)
        val pb = batch(bindings)
        return PushBatch(
            wire = mapOf(
                "zones" to pz.map { it.toWire() },
                "shelves" to ps.map { it.toWire() },
                "levels" to pl.map { it.toWire() },
                "slots" to pt.map { it.toWire() },
                "bindings" to pb.map { it.toWire() },
            ),
            zoneIds = pz.map { it.id },
            shelfIds = ps.map { it.id },
            levelIds = pl.map { it.id },
            slotIds = pt.map { it.id },
            bindingIds = pb.map { it.id },
        )
    }

    private fun ShelfZoneEntity.toWire(): Map<String, Any?> = mapOf(
        "entity_id" to tagId,
        "parent_id" to parentId?.let { zoneTagCache[it] },
        "name" to name,
        "note" to note,
        "sort_order" to sortOrder,
        "created_at" to createdAt,
        "updated_at" to (updatedAt + clockOffsetMs()),
        "deleted" to if (deleted) 1 else 0,
    )

    private fun ShelfEntity.toWire(): Map<String, Any?> = mapOf(
        "entity_id" to tagId,
        "zone_id" to zoneId?.let { zoneTagCache[it] },
        "name" to name,
        "note" to note,
        "sort_order" to sortOrder,
        "created_at" to createdAt,
        "updated_at" to (updatedAt + clockOffsetMs()),
        "deleted" to if (deleted) 1 else 0,
    )

    private fun ShelfLevelEntity.toWire(): Map<String, Any?> = mapOf(
        "entity_id" to tagId,
        "shelf_id" to shelfTagCache[shelfId],
        "name" to name,
        "note" to note,
        "sort_order" to sortOrder,
        "created_at" to createdAt,
        "updated_at" to (updatedAt + clockOffsetMs()),
        "deleted" to if (deleted) 1 else 0,
    )
    private fun ShelfSlotEntity.toWire(): Map<String, Any?> = mapOf(
        "entity_id" to tagId,
        "level_id" to levelTagCache[levelId],
        "name" to name,
        "note" to note,
        "sort_order" to sortOrder,
        "created_at" to createdAt,
        "updated_at" to (updatedAt + clockOffsetMs()),
        "deleted" to if (deleted) 1 else 0,
    )

    private fun ShelfBindingEntity.toWire(): Map<String, Any?> = mapOf(
        "entity_id" to uid,
        "level_id" to levelTagCache[levelId],
        "slot_id" to slotId?.let { slotTagCache[it] },
        "product_id" to productId,
        "barcode" to barcode,
        "name" to name,
        "misplaced" to if (misplaced) 1 else 0,
        "note" to note,
        "created_by" to createdBy,
        "created_at" to createdAt,
        "updated_at" to (updatedAt + clockOffsetMs()),
        "deleted" to if (deleted) 1 else 0,
    )

    // 推送组包阶段的 id→tagId 缓存（挂起函数里不能直接调 dao，先预加载）
    private var zoneTagCache: Map<Long, String> = emptyMap()
    private var shelfTagCache: Map<Long, String> = emptyMap()
    private var levelTagCache: Map<Long, String> = emptyMap()
    private var slotTagCache: Map<Long, String> = emptyMap()

    /**
     * 预加载 id→tagId 映射（推送组包用）。
     * 用 all*IncludingDeleted：软删行也要能上送（删除事件需要父子 tagId 引用）。
     */
    private suspend fun refreshCaches() {
        zoneTagCache = dao.allZonesIncludingDeleted().associate { it.id to it.tagId }
        shelfTagCache = dao.allShelvesIncludingDeleted().associate { it.id to it.tagId }
        levelTagCache = dao.allLevelsIncludingDeleted().associate { it.id to it.tagId }
        slotTagCache = dao.allSlotsIncludingDeleted().associate { it.id to it.tagId }
    }

    // ============================== 清脏 ==============================

    /** I31/B1：只清本批实际推送的 id——重新全表查 dirty 再全清会把截断行/新写入行一起误标「已同步」。 */
    private suspend fun clearPushedDirty(batch: PushBatch) {
        // IN 参数绑定上限（老 SQLite 999）：一律按 500 一段分批
        fun <T> segments(list: List<T>): List<List<T>> =
            if (list.size <= 500) listOf(list) else list.chunked(500)
        for (ids in segments(batch.zoneIds)) dao.clearZoneDirtyByIds(ids)
        for (ids in segments(batch.shelfIds)) dao.clearShelfDirtyByIds(ids)
        for (ids in segments(batch.levelIds)) dao.clearLevelDirtyByIds(ids)
        for (ids in segments(batch.slotIds)) dao.clearSlotDirtyByIds(ids)
        for (ids in segments(batch.bindingIds)) dao.clearBindingDirtyByIds(ids)
    }

    // ============================== 拉取：服务端行 → 本地 ==============================

    private suspend fun applyPulled(changes: ShelfSyncChanges): Int {
        var count = 0
        for (row in changes.zones) count += applyZoneRow(row)
        for (row in changes.shelves) count += applyShelfRow(row)
        for (row in changes.levels) count += applyLevelRow(row)
        for (row in changes.slots) count += applySlotRow(row)
        for (row in changes.bindings) count += applyBindingRow(row)
        return count
    }

    private suspend fun applyZoneRow(row: Map<String, Any?>): Int {
        val tag = str(row, "entity_id") ?: return 0
        var localId = dao.localZoneIdByTag(tag)
        if (localId == null) {
            localId = dao.upsertZone(
                ShelfZoneEntity(
                    tagId = tag,
                    parentId = null, // 新行先无父；下面统一解析父链
                    name = str(row, "name") ?: "",
                    note = str(row, "note"),
                    sortOrder = int(row, "sort_order"),
                    createdAt = int(row, "created_at").toLong(),
                    updatedAt = int(row, "updated_at").toLong(),
                    dirty = false,
                    deleted = int(row, "deleted") == 1,
                ),
            )
        } else {
            val cur = dao.zoneById(localId)!!
            if (cur.dirty) return 0 // 本地有未上传改动：等推送裁决，旧版不覆盖（纪律 2）
            dao.upsertZone(
                cur.copy(
                    name = str(row, "name") ?: cur.name,
                    note = str(row, "note"),
                    sortOrder = int(row, "sort_order"),
                    updatedAt = int(row, "updated_at").toLong(),
                    dirty = false,
                    deleted = int(row, "deleted") == 1,
                ),
            )
        }
        // 父链解析（父可能因排序先后还没落库：按 tagId 再查一次，查不到就先空着，后续行会带来）
        val parentTag = str(row, "parent_id")
        if (parentTag != null) {
            val pid = dao.localZoneIdByTag(parentTag)
            if (pid != null) {
                val cur = dao.zoneById(localId)!!
                if (cur.parentId != pid) dao.upsertZone(cur.copy(parentId = pid))
            }
        }
        return 1
    }

    private suspend fun applyShelfRow(row: Map<String, Any?>): Int {
        val tag = str(row, "entity_id") ?: return 0
        val localId = dao.localShelfIdByTag(tag)
        if (localId == null) {
            dao.upsertShelf(
                ShelfEntity(
                    tagId = tag,
                    name = str(row, "name") ?: "",
                    note = str(row, "note"),
                    zoneId = null,
                    sortOrder = int(row, "sort_order"),
                    createdAt = int(row, "created_at").toLong(),
                    updatedAt = int(row, "updated_at").toLong(),
                    dirty = false,
                    deleted = int(row, "deleted") == 1,
                ),
            )
        } else {
            val cur = dao.shelfById(localId)!!
            if (cur.dirty) return 0 // 本地有未上传改动：旧版不覆盖（纪律 2）
            dao.upsertShelf(
                cur.copy(
                    name = str(row, "name") ?: cur.name,
                    note = str(row, "note"),
                    sortOrder = int(row, "sort_order"),
                    updatedAt = int(row, "updated_at").toLong(),
                    dirty = false,
                    deleted = int(row, "deleted") == 1,
                ),
            )
        }
        val zoneTag = str(row, "zone_id")
        if (zoneTag != null) {
            val zid = dao.localZoneIdByTag(zoneTag)
            if (zid != null) {
                val id = dao.localShelfIdByTag(tag) ?: return 1
                val cur = dao.shelfById(id) ?: return 1
                if (cur.zoneId != zid) dao.upsertShelf(cur.copy(zoneId = zid))
            }
        }
        return 1
    }

    private suspend fun applyLevelRow(row: Map<String, Any?>): Int {
        val tag = str(row, "entity_id") ?: return 0
        val shelfTag = str(row, "shelf_id") ?: return 0
        val localId = dao.localLevelIdByTag(tag)
        if (localId == null) {
            val shelfId = dao.localShelfIdByTag(shelfTag) ?: ensurePlaceholderShelf(shelfTag)
            dao.upsertLevel(
                ShelfLevelEntity(
                    tagId = tag,
                    shelfId = shelfId,
                    name = str(row, "name") ?: "",
                    note = str(row, "note"),
                    sortOrder = int(row, "sort_order"),
                    createdAt = int(row, "created_at").toLong(),
                    updatedAt = int(row, "updated_at").toLong(),
                    dirty = false,
                    deleted = int(row, "deleted") == 1,
                ),
            )
        } else {
            val cur = dao.levelById(localId)!!
            if (cur.dirty) return 0 // 本地有未上传改动：旧版不覆盖（纪律 2）
            val shelfId = dao.localShelfIdByTag(shelfTag) ?: cur.shelfId
            dao.upsertLevel(
                cur.copy(
                    shelfId = shelfId,
                    name = str(row, "name") ?: cur.name,
                    note = str(row, "note"),
                    sortOrder = int(row, "sort_order"),
                    updatedAt = int(row, "updated_at").toLong(),
                    dirty = false,
                    deleted = int(row, "deleted") == 1,
                ),
            )
        }
        return 1
    }

    private suspend fun applySlotRow(row: Map<String, Any?>): Int {
        val tag = str(row, "entity_id") ?: return 0
        val levelTag = str(row, "level_id") ?: return 0
        val localId = dao.localSlotIdByTag(tag)
        if (localId == null) {
            val levelId = dao.localLevelIdByTag(levelTag) ?: ensurePlaceholderLevel(levelTag)
            dao.upsertSlot(
                ShelfSlotEntity(
                    tagId = tag,
                    levelId = levelId,
                    name = str(row, "name") ?: "",
                    note = str(row, "note"),
                    sortOrder = int(row, "sort_order"),
                    createdAt = int(row, "created_at").toLong(),
                    updatedAt = int(row, "updated_at").toLong(),
                    dirty = false,
                    deleted = int(row, "deleted") == 1,
                ),
            )
        } else {
            val cur = dao.slotById(localId)!!
            if (cur.dirty) return 0 // 本地有未上传改动：旧版不覆盖（纪律 2）
            val levelId = dao.localLevelIdByTag(levelTag) ?: cur.levelId
            dao.upsertSlot(
                cur.copy(
                    levelId = levelId,
                    name = str(row, "name") ?: cur.name,
                    note = str(row, "note"),
                    sortOrder = int(row, "sort_order"),
                    updatedAt = int(row, "updated_at").toLong(),
                    dirty = false,
                    deleted = int(row, "deleted") == 1,
                ),
            )
        }
        return 1
    }

    private suspend fun applyBindingRow(row: Map<String, Any?>): Int {
        val uid = str(row, "entity_id") ?: return 0
        val levelTag = str(row, "level_id") ?: return 0
        val levelId = dao.localLevelIdByTag(levelTag) ?: ensurePlaceholderLevel(levelTag)
        val slotId = str(row, "slot_id")?.let { dao.localSlotIdByTag(it) }
        val localId = dao.localBindingIdByUid(uid)
        if (localId == null) {
            dao.upsertBinding(
                ShelfBindingEntity(
                    uid = uid,
                    levelId = levelId,
                    slotId = slotId,
                    productId = int(row, "product_id").toLong(),
                    barcode = str(row, "barcode"),
                    name = str(row, "name") ?: "",
                    misplaced = int(row, "misplaced") == 1,
                    note = str(row, "note"),
                    createdBy = str(row, "created_by") ?: "",
                    createdAt = int(row, "created_at").toLong(),
                    updatedAt = int(row, "updated_at").toLong(),
                    dirty = false,
                    deleted = int(row, "deleted") == 1,
                ),
            )
        } else {
            val cur = dao.bindingById(localId)!!
            if (cur.dirty) return 0 // 本地有未上传改动：旧版不覆盖（纪律 2）
            dao.upsertBinding(
                cur.copy(
                    levelId = levelId,
                    slotId = slotId,
                    productId = int(row, "product_id").toLong(),
                    barcode = str(row, "barcode"),
                    name = str(row, "name") ?: cur.name,
                    misplaced = int(row, "misplaced") == 1,
                    note = str(row, "note"),
                    createdAt = int(row, "created_at").toLong(),
                    updatedAt = int(row, "updated_at").toLong(),
                    dirty = false,
                    deleted = int(row, "deleted") == 1,
                ),
            )
        }
        return 1
    }

    /** 父行尚未落库时（行序或坏数据）：建一个占位父，保住树连通性，等真正的行到了再覆盖。 */
    private suspend fun ensurePlaceholderShelf(tag: String): Long =
        dao.upsertShelf(ShelfEntity(tagId = tag, name = "（同步中）", dirty = false))

    private suspend fun ensurePlaceholderLevel(tag: String): Long {
        // 占位层需要挂在一个 shelf 上；用「同步中」占位架兜底（同一占位架复用，不重复建）
        val existing = dao.shelves().firstOrNull { it.tagId == "SYNCPLACEHOLDER" }?.id
        val shelfId = existing
            ?: dao.upsertShelf(ShelfEntity(tagId = "SYNCPLACEHOLDER", name = "（同步中）", dirty = false))
        return dao.upsertLevel(
            ShelfLevelEntity(tagId = tag, shelfId = shelfId, name = "（同步中）", dirty = false),
        )
    }

    // ============================== 解析助手 ==============================

    private fun str(row: Map<String, Any?>, key: String): String? {
        val v = row[key] ?: return null
        val s = v.toString().trim()
        return s.ifEmpty { null }
    }

    private fun int(row: Map<String, Any?>, key: String): Int {
        val v = row[key] ?: return 0
        return when (v) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: 0
            is Boolean -> if (v) 1 else 0
            else -> 0
        }
    }
}
