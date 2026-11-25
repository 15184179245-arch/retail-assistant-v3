package com.retaildemo.v2.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.retaildemo.v2.data.local.entity.ShelfBindingEntity
import com.retaildemo.v2.data.local.entity.ShelfEntity
import com.retaildemo.v2.data.local.entity.ShelfLevelEntity
import com.retaildemo.v2.data.local.entity.ShelfSlotEntity
import com.retaildemo.v2.data.local.entity.ShelfZoneEntity
import kotlinx.coroutines.flow.Flow

/**
 * I20 三级货架 DAO（货架 → 层 → 格 + 多对多绑定）；I22 起承载多终端同步；I23 加分区/楼层文件夹树。
 *
 * 设计要点：
 * - 层级只靠自增 id 父子外键，**没有任何按名字/码的查询**（名字完全自定义，不可解析）。
 * - 首页整树用 observe* 全量流在内存拼装（单店规模：几十层、几百绑定），避免 N+1。
 * - 改名只改 name；tag_id 一旦生成永不变（NFC 写的是 tagId，改名免重贴）；同步以 tag_id 对位。
 * - **所有读查询过滤 `deleted = 0`**；删除一律软删（`deleted = 1, dirty = 1`），
 *   否则删除只留在本机、同步后会在别的设备「复活」（I22 设计）。
 * - **所有写查询同时置 `dirty = 1, updated_at = :now`**（LWW 时间戳与待上传标记）。
 * - 级联合并/删除用 @Transaction 保证原子，中途失败可回滚。
 */
@Dao
interface ShelfDao {

    // ============================== 分区 / 楼层（I23 文件夹树） ==============================

    @Query("SELECT * FROM shelf_zones WHERE deleted = 0 ORDER BY sort_order ASC, id ASC")
    fun observeZones(): Flow<List<ShelfZoneEntity>>

    @Query("SELECT * FROM shelf_zones WHERE deleted = 0 ORDER BY sort_order ASC, id ASC")
    suspend fun zones(): List<ShelfZoneEntity>

    @Query("SELECT * FROM shelf_zones WHERE id = :id LIMIT 1")
    suspend fun zoneById(id: Long): ShelfZoneEntity?

    @Query("SELECT * FROM shelf_zones WHERE tag_id = :tagId AND deleted = 0 LIMIT 1")
    suspend fun zoneByTag(tagId: String): ShelfZoneEntity?

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM shelf_zones WHERE (parent_id IS NULL AND :parentId IS NULL) OR parent_id = :parentId")
    suspend fun nextZoneSort(parentId: Long?): Int

    @Query("SELECT COUNT(*) FROM shelf_zones WHERE tag_id = :tagId")
    suspend fun countZoneTag(tagId: String): Int

    @Insert
    suspend fun insertZone(zone: ShelfZoneEntity): Long

    @Query("UPDATE shelf_zones SET name = :name, dirty = 1, updated_at = :now WHERE id = :id")
    suspend fun renameZone(id: Long, name: String, now: Long)

    @Query("UPDATE shelf_zones SET note = :note, dirty = 1, updated_at = :now WHERE id = :id")
    suspend fun setZoneNote(id: Long, note: String?, now: Long)

    @Query("UPDATE shelf_zones SET parent_id = :parentId, dirty = 1, updated_at = :now WHERE id = :id")
    suspend fun setZoneParent(id: Long, parentId: Long?, now: Long)

    @Query("UPDATE shelf_zones SET sort_order = :order, dirty = 1, updated_at = :now WHERE id = :id")
    suspend fun setZoneSort(id: Long, order: Int, now: Long)

    @Query("SELECT id FROM shelf_zones WHERE deleted = 0 AND ((parent_id IS NULL AND :parentId IS NULL) OR parent_id = :parentId) ORDER BY sort_order ASC, id ASC")
    suspend fun zoneIdsUnder(parentId: Long?): List<Long>

    @Query("SELECT id FROM shelves WHERE deleted = 0 AND ((zone_id IS NULL AND :zoneId IS NULL) OR zone_id = :zoneId) ORDER BY sort_order ASC, id ASC")
    suspend fun shelfIdsUnder(zoneId: Long?): List<Long>

    // ============================== 货架 ==============================

    @Query("SELECT * FROM shelves WHERE deleted = 0 ORDER BY sort_order ASC, id ASC")
    fun observeShelves(): Flow<List<ShelfEntity>>

    @Query("SELECT * FROM shelves WHERE deleted = 0 ORDER BY sort_order ASC, id ASC")
    suspend fun shelves(): List<ShelfEntity>

    @Query("SELECT * FROM shelves WHERE id = :id LIMIT 1")
    suspend fun shelfById(id: Long): ShelfEntity?

    @Query("SELECT * FROM shelves WHERE tag_id = :tagId AND deleted = 0 LIMIT 1")
    suspend fun shelfByTag(tagId: String): ShelfEntity?

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM shelves WHERE (zone_id IS NULL AND :zoneId IS NULL) OR zone_id = :zoneId")
    suspend fun nextShelfSort(zoneId: Long?): Int

    @Insert
    suspend fun insertShelf(shelf: ShelfEntity): Long

    @Query("UPDATE shelves SET name = :name, dirty = 1, updated_at = :now WHERE id = :id")
    suspend fun renameShelf(id: Long, name: String, now: Long)

    @Query("UPDATE shelves SET zone_id = :zoneId, dirty = 1, updated_at = :now WHERE id = :id")
    suspend fun setShelfZone(id: Long, zoneId: Long?, now: Long)

    @Query("UPDATE shelves SET sort_order = :order, dirty = 1, updated_at = :now WHERE id = :id")
    suspend fun setShelfSort(id: Long, order: Int, now: Long)

    /** 备注（I20 补需求）：名字之外给人看的提示，不参与任何逻辑。 */
    @Query("UPDATE shelves SET note = :note, dirty = 1, updated_at = :now WHERE id = :id")
    suspend fun setShelfNote(id: Long, note: String?, now: Long)

    // ============================== 层 ==============================

    @Query("SELECT * FROM shelf_levels WHERE deleted = 0 ORDER BY sort_order ASC, id ASC")
    fun observeAllLevels(): Flow<List<ShelfLevelEntity>>

    @Query("SELECT * FROM shelf_levels WHERE deleted = 0 AND shelf_id = :shelfId ORDER BY sort_order ASC, id ASC")
    suspend fun levelsOf(shelfId: Long): List<ShelfLevelEntity>

    @Query("SELECT * FROM shelf_levels WHERE id = :id LIMIT 1")
    suspend fun levelById(id: Long): ShelfLevelEntity?

    @Query("SELECT * FROM shelf_levels WHERE tag_id = :tagId AND deleted = 0 LIMIT 1")
    suspend fun levelByTag(tagId: String): ShelfLevelEntity?

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM shelf_levels WHERE deleted = 0 AND shelf_id = :shelfId")
    suspend fun nextLevelSort(shelfId: Long): Int

    @Insert
    suspend fun insertLevel(level: ShelfLevelEntity): Long

    @Query("UPDATE shelf_levels SET name = :name, dirty = 1, updated_at = :now WHERE id = :id")
    suspend fun renameLevel(id: Long, name: String, now: Long)

    @Query("UPDATE shelf_levels SET sort_order = :order, dirty = 1, updated_at = :now WHERE id = :id")
    suspend fun setLevelSort(id: Long, order: Int, now: Long)

    @Query("UPDATE shelf_levels SET note = :note, dirty = 1, updated_at = :now WHERE id = :id")
    suspend fun setLevelNote(id: Long, note: String?, now: Long)

    /** 软删层（I22）：置 deleted/dirty；行保留以便同步到其它设备。 */
    @Query("UPDATE shelf_levels SET deleted = 1, dirty = 1, updated_at = :now WHERE id = :id")
    suspend fun softDeleteLevel(id: Long, now: Long)

    // ============================== 格 ==============================

    @Query("SELECT * FROM shelf_slots WHERE deleted = 0 ORDER BY sort_order ASC, id ASC")
    fun observeAllSlots(): Flow<List<ShelfSlotEntity>>

    @Query("SELECT * FROM shelf_slots WHERE deleted = 0 AND level_id = :levelId ORDER BY sort_order ASC, id ASC")
    suspend fun slotsOf(levelId: Long): List<ShelfSlotEntity>

    @Query("SELECT * FROM shelf_slots WHERE id = :id LIMIT 1")
    suspend fun slotById(id: Long): ShelfSlotEntity?

    @Query("SELECT * FROM shelf_slots WHERE tag_id = :tagId AND deleted = 0 LIMIT 1")
    suspend fun slotByTag(tagId: String): ShelfSlotEntity?

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM shelf_slots WHERE deleted = 0 AND level_id = :levelId")
    suspend fun nextSlotSort(levelId: Long): Int

    @Insert
    suspend fun insertSlot(slot: ShelfSlotEntity): Long

    @Query("UPDATE shelf_slots SET name = :name, dirty = 1, updated_at = :now WHERE id = :id")
    suspend fun renameSlot(id: Long, name: String, now: Long)

    @Query("UPDATE shelf_slots SET sort_order = :order, dirty = 1, updated_at = :now WHERE id = :id")
    suspend fun setSlotSort(id: Long, order: Int, now: Long)

    @Query("UPDATE shelf_slots SET note = :note, dirty = 1, updated_at = :now WHERE id = :id")
    suspend fun setSlotNote(id: Long, note: String?, now: Long)

    /** 软删格（I22）。 */
    @Query("UPDATE shelf_slots SET deleted = 1, dirty = 1, updated_at = :now WHERE id = :id")
    suspend fun softDeleteSlot(id: Long, now: Long)

    // ============================== 绑定（多对多） ==============================

    @Query("SELECT * FROM shelf_bindings WHERE deleted = 0 ORDER BY created_at DESC, id DESC")
    fun observeAllBindings(): Flow<List<ShelfBindingEntity>>

    @Query("SELECT * FROM shelf_bindings WHERE deleted = 0 AND level_id = :levelId ORDER BY created_at DESC, id DESC")
    suspend fun bindingsByLevel(levelId: Long): List<ShelfBindingEntity>

    @Query("SELECT * FROM shelf_bindings WHERE deleted = 0 AND slot_id = :slotId ORDER BY created_at DESC, id DESC")
    suspend fun bindingsBySlot(slotId: Long): List<ShelfBindingEntity>

    /** 一品多货位反查：同一商品的所有位置（含「只绑到层」的 slot_id IS NULL 记录）。 */
    @Query("SELECT * FROM shelf_bindings WHERE deleted = 0 AND product_id = :productId ORDER BY created_at ASC, id ASC")
    suspend fun bindingsByProduct(productId: Long): List<ShelfBindingEntity>

    @Query("SELECT * FROM shelf_bindings WHERE deleted = 0 AND barcode = :barcode ORDER BY created_at ASC, id ASC")
    suspend fun bindingsByBarcode(barcode: String): List<ShelfBindingEntity>

    @Query("SELECT * FROM shelf_bindings WHERE id = :id LIMIT 1")
    suspend fun bindingById(id: Long): ShelfBindingEntity?

    /** 幂等护栏：同一格内同一商品不重复建记录。 */
    @Query("SELECT * FROM shelf_bindings WHERE deleted = 0 AND level_id = :levelId AND product_id = :productId AND ((slot_id IS NULL AND :slotId IS NULL) OR slot_id = :slotId) LIMIT 1")
    suspend fun findBinding(levelId: Long, slotId: Long?, productId: Long): ShelfBindingEntity?

    @Query("SELECT COUNT(*) FROM shelf_bindings WHERE deleted = 0 AND misplaced = 1")
    suspend fun misplacedCount(): Int

    @Insert
    suspend fun insertBinding(binding: ShelfBindingEntity): Long

    /** 软删绑定（I22）：解绑 = 标记删除并上传，别处不受影响。 */
    @Query("UPDATE shelf_bindings SET deleted = 1, dirty = 1, updated_at = :now WHERE id = :id")
    suspend fun softDeleteBinding(id: Long, now: Long)

    @Query("UPDATE shelf_bindings SET misplaced = :misplaced, note = :note, dirty = 1, updated_at = :now WHERE id = :id")
    suspend fun setMisplaced(id: Long, misplaced: Boolean, note: String?, now: Long)

    // ============================== tagId 唯一性 ==============================

    @Query("SELECT COUNT(*) FROM shelves WHERE tag_id = :tagId")
    suspend fun countShelfTag(tagId: String): Int

    @Query("SELECT COUNT(*) FROM shelf_levels WHERE tag_id = :tagId")
    suspend fun countLevelTag(tagId: String): Int

    @Query("SELECT COUNT(*) FROM shelf_slots WHERE tag_id = :tagId")
    suspend fun countSlotTag(tagId: String): Int

    // ============================== I22 同步：dirty 行导出 ==============================

    @Query("SELECT * FROM shelf_zones WHERE dirty = 1")
    suspend fun dirtyZones(): List<ShelfZoneEntity>

    @Query("SELECT * FROM shelves WHERE dirty = 1")
    suspend fun dirtyShelves(): List<ShelfEntity>

    @Query("SELECT * FROM shelf_levels WHERE dirty = 1")
    suspend fun dirtyLevels(): List<ShelfLevelEntity>

    @Query("SELECT * FROM shelf_slots WHERE dirty = 1")
    suspend fun dirtySlots(): List<ShelfSlotEntity>

    @Query("SELECT * FROM shelf_bindings WHERE dirty = 1")
    suspend fun dirtyBindings(): List<ShelfBindingEntity>

    // ============================== I22 同步：服务端行落库（按 tagId upsert） ==============================

    @Query("SELECT id FROM shelves WHERE tag_id = :tagId LIMIT 1")
    suspend fun localShelfIdByTag(tagId: String): Long?

    @Query("SELECT id FROM shelf_zones WHERE tag_id = :tagId LIMIT 1")
    suspend fun localZoneIdByTag(tagId: String): Long?

    @Query("SELECT id FROM shelf_levels WHERE tag_id = :tagId LIMIT 1")
    suspend fun localLevelIdByTag(tagId: String): Long?

    @Query("SELECT id FROM shelf_slots WHERE tag_id = :tagId LIMIT 1")
    suspend fun localSlotIdByTag(tagId: String): Long?

    @Query("SELECT id FROM shelf_bindings WHERE uid = :uid LIMIT 1")
    suspend fun localBindingIdByUid(uid: String): Long?

    /** 拉取落库用：整行覆盖（dirty 已置 0）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertZone(zone: ShelfZoneEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertShelf(shelf: ShelfEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLevel(level: ShelfLevelEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSlot(slot: ShelfSlotEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBinding(binding: ShelfBindingEntity): Long

    /** 全部行（含已删）——供同步落库时定位与一致性自检。 */
    @Query("SELECT * FROM shelves")
    suspend fun allShelvesIncludingDeleted(): List<ShelfEntity>

    @Query("SELECT * FROM shelf_zones")
    suspend fun allZonesIncludingDeleted(): List<ShelfZoneEntity>

    @Query("SELECT * FROM shelf_levels")
    suspend fun allLevelsIncludingDeleted(): List<ShelfLevelEntity>

    @Query("SELECT * FROM shelf_slots")
    suspend fun allSlotsIncludingDeleted(): List<ShelfSlotEntity>

    /** 清 dirty：推成功后按 id 归零（保留 deleted 状态）。 */
    @Query("UPDATE shelf_zones SET dirty = 0 WHERE id = :id")
    suspend fun clearZoneDirty(id: Long)

    @Query("UPDATE shelves SET dirty = 0 WHERE id = :id")
    suspend fun clearShelfDirty(id: Long)

    @Query("UPDATE shelf_levels SET dirty = 0 WHERE id = :id")
    suspend fun clearLevelDirty(id: Long)

    @Query("UPDATE shelf_slots SET dirty = 0 WHERE id = :id")
    suspend fun clearSlotDirty(id: Long)

    @Query("UPDATE shelf_bindings SET dirty = 0 WHERE id = :id")
    suspend fun clearBindingDirty(id: Long)

    // I31/B1：按批次 id 批量清脏——清脏范围必须与实际推送范围一致，
    // 防止「截断推送 + 全量清脏」把从未上传的行永久标成已同步（静默丢失）。
    @Query("UPDATE shelf_zones SET dirty = 0 WHERE id IN (:ids)")
    suspend fun clearZoneDirtyByIds(ids: List<Long>)

    @Query("UPDATE shelves SET dirty = 0 WHERE id IN (:ids)")
    suspend fun clearShelfDirtyByIds(ids: List<Long>)

    @Query("UPDATE shelf_levels SET dirty = 0 WHERE id IN (:ids)")
    suspend fun clearLevelDirtyByIds(ids: List<Long>)

    @Query("UPDATE shelf_slots SET dirty = 0 WHERE id IN (:ids)")
    suspend fun clearSlotDirtyByIds(ids: List<Long>)

    @Query("UPDATE shelf_bindings SET dirty = 0 WHERE id IN (:ids)")
    suspend fun clearBindingDirtyByIds(ids: List<Long>)

    // ============================== 级联与合并（事务） ==============================

    /** 合并层：把 from 层下所有绑定（含其上各格的绑定）迁到 to 层，软删 from 层与其格。 */
    @Transaction
    suspend fun mergeLevelTx(from: Long, to: Long, now: Long) {
        moveLevelBindings(from, to, now)
        softDeleteSlotsOfLevel(from, now)
        softDeleteLevel(from, now)
    }

    @Query("UPDATE shelf_bindings SET level_id = :to, slot_id = NULL, dirty = 1, updated_at = :now WHERE level_id = :from AND deleted = 0")
    suspend fun moveLevelBindings(from: Long, to: Long, now: Long)

    @Query("UPDATE shelf_slots SET deleted = 1, dirty = 1, updated_at = :now WHERE level_id = :levelId AND deleted = 0")
    suspend fun softDeleteSlotsOfLevel(levelId: Long, now: Long)

    /** 合并格：把 from 格下所有绑定迁到 to 格，软删 from 格。 */
    @Transaction
    suspend fun mergeSlotTx(from: Long, to: Long, now: Long) {
        moveSlotBindings(from, to, now)
        softDeleteSlot(from, now)
    }

    @Query("UPDATE shelf_bindings SET slot_id = :to, dirty = 1, updated_at = :now WHERE slot_id = :from AND deleted = 0")
    suspend fun moveSlotBindings(from: Long, to: Long, now: Long)

    /** 删货架：连同其下所有层、格、绑定一并软删（事务；行保留供同步）。 */
    @Transaction
    suspend fun deleteShelfCascade(shelfId: Long, now: Long) {
        softDeleteBindingsOfShelf(shelfId, now)
        softDeleteSlotsOfShelf(shelfId, now)
        softDeleteLevelsOfShelf(shelfId, now)
        softDeleteShelfRow(shelfId, now)
    }

    @Query("UPDATE shelf_bindings SET deleted = 1, dirty = 1, updated_at = :now WHERE deleted = 0 AND level_id IN (SELECT id FROM shelf_levels WHERE shelf_id = :shelfId)")
    suspend fun softDeleteBindingsOfShelf(shelfId: Long, now: Long)

    @Query("UPDATE shelf_slots SET deleted = 1, dirty = 1, updated_at = :now WHERE deleted = 0 AND level_id IN (SELECT id FROM shelf_levels WHERE shelf_id = :shelfId)")
    suspend fun softDeleteSlotsOfShelf(shelfId: Long, now: Long)

    @Query("UPDATE shelf_levels SET deleted = 1, dirty = 1, updated_at = :now WHERE deleted = 0 AND shelf_id = :shelfId")
    suspend fun softDeleteLevelsOfShelf(shelfId: Long, now: Long)

    @Query("UPDATE shelves SET deleted = 1, dirty = 1, updated_at = :now WHERE id = :id")
    suspend fun softDeleteShelfRow(id: Long, now: Long)

    /** 删分区（I23）：递归软删子分区 + 其下货架（货架走各自 cascade）。 */
    @Transaction
    suspend fun deleteZoneCascade(zoneId: Long, now: Long) {
        val childZoneIds = zoneIdsUnder(zoneId)
        for (child in childZoneIds) {
            deleteZoneCascade(child, now)
        }
        val shelfIds = shelfIdsUnder(zoneId)
        for (sid in shelfIds) {
            deleteShelfCascade(sid, now)
        }
        softDeleteZoneRow(zoneId, now)
    }

    @Query("UPDATE shelf_zones SET deleted = 1, dirty = 1, updated_at = :now WHERE id = :id")
    suspend fun softDeleteZoneRow(id: Long, now: Long)

    /** 删层：连同其下所有格与绑定（事务，软删）。 */
    @Transaction
    suspend fun deleteLevelCascade(levelId: Long, now: Long) {
        softDeleteBindingsOfLevel(levelId, now)
        softDeleteSlotsOfLevel(levelId, now)
        softDeleteLevel(levelId, now)
    }

    @Query("UPDATE shelf_bindings SET deleted = 1, dirty = 1, updated_at = :now WHERE deleted = 0 AND level_id = :levelId")
    suspend fun softDeleteBindingsOfLevel(levelId: Long, now: Long)

    /** 删格：连同其下绑定（事务，软删）。 */
    @Transaction
    suspend fun deleteSlotCascade(slotId: Long, now: Long) {
        softDeleteBindingsOfSlot(slotId, now)
        softDeleteSlot(slotId, now)
    }

    @Query("UPDATE shelf_bindings SET deleted = 1, dirty = 1, updated_at = :now WHERE deleted = 0 AND slot_id = :slotId")
    suspend fun softDeleteBindingsOfSlot(slotId: Long, now: Long)
}
