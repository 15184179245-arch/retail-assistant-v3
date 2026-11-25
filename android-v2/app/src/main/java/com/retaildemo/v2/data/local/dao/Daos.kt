package com.retaildemo.v2.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.retaildemo.v2.data.local.entity.BillDraftEntity
import com.retaildemo.v2.data.local.entity.ProductViewEntity
import com.retaildemo.v2.data.local.entity.BillEntity
import com.retaildemo.v2.data.local.entity.CustomerEntity
import com.retaildemo.v2.data.local.entity.ProductEntity
import com.retaildemo.v2.data.local.entity.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(products: List<ProductEntity>)

    /** U6 重建离线商品库用：只清 products（上游镜像，可随时重拉），业务表绝不碰。 */
    @Query("DELETE FROM products")
    suspend fun clearAll()

    @Query(
        "SELECT * FROM products WHERE barcode = :barcode COLLATE NOCASE " +
            "OR barcode = REPLACE(:barcode, '''', '') COLLATE NOCASE " +
            "OR REPLACE(barcode, '''', '') = :barcode COLLATE NOCASE " +
            "OR barcode = REPLACE(:barcode, '.', '') COLLATE NOCASE " +
            "OR sku = :barcode COLLATE NOCASE " +
            "ORDER BY CASE " +
            "WHEN barcode = :barcode COLLATE NOCASE THEN 0 " +
            "WHEN REPLACE(barcode, '''', '') = :barcode COLLATE NOCASE THEN 1 " +
            "WHEN sku = :barcode COLLATE NOCASE THEN 2 " +
            "ELSE 3 END LIMIT 1",
    )
    suspend fun getByBarcode(barcode: String): ProductEntity?

    /** 按主键精确取一条（货架卡片要商品的规格/单位/售价做蒙版信息，按 id 查最省）。 */
    @Query("SELECT * FROM products WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ProductEntity?

    @Query(
        "SELECT * FROM products WHERE (sale_status IS NULL OR sale_status != 'frozen' OR :includeFrozen = 1) " +
            "AND (name LIKE '%'||:q||'%' OR spec LIKE '%'||:q||'%' OR barcode LIKE '%'||:q||'%' " +
            "OR barcode LIKE '%'||replace(:q, '.', '')||'%' " +
            "OR sku LIKE '%'||:q||'%' " +
            "OR model LIKE '%'||:q||'%' " +
            "OR replace(model, '.', '') LIKE '%'||replace(:q, '.', '')||'%' " +
            "OR replace(pinyin, ' ', '') LIKE '%'||:q||'%' " +
            "OR replace(pinyin, ' ', '') LIKE '%'||replace(:q, ' ', '')||'%' " +
            "OR initials LIKE '%'||:q||'%' OR category LIKE '%'||:q||'%' " +
            "OR brand LIKE '%'||:q||'%') " +
            "ORDER BY CASE " +
            "WHEN barcode = :q COLLATE NOCASE THEN 0 " +
            "WHEN sku = :q COLLATE NOCASE THEN 1 " +
            "WHEN name = :q COLLATE NOCASE THEN 2 " +
            "WHEN name LIKE :q||'%' THEN 3 " +
            "WHEN name LIKE '%'||:q||'%' THEN 4 " +
            "WHEN spec LIKE '%'||:q||'%' THEN 5 " +
            "WHEN model LIKE '%'||:q||'%' THEN 5 " +
            "WHEN barcode LIKE :q||'%' THEN 6 " +
            "ELSE 7 END, CASE WHEN stock_qty > 0 THEN 0 ELSE 1 END, sell_price LIMIT 500",
    )
    suspend fun search(q: String, includeFrozen: Boolean = false): List<ProductEntity>

    @Query(
        "SELECT * FROM products WHERE (sale_status IS NULL OR sale_status != 'frozen' OR :includeFrozen = 1) " +
            "AND (name LIKE '%'||:q1||'%' OR brand LIKE '%'||:q1||'%' OR category LIKE '%'||:q1||'%' OR spec LIKE '%'||:q1||'%' OR model LIKE '%'||:q1||'%' OR sku LIKE '%'||:q1||'%' OR barcode LIKE '%'||:q1||'%' OR initials LIKE '%'||:q1||'%') " +
            "AND (name LIKE '%'||:q2||'%' OR brand LIKE '%'||:q2||'%' OR category LIKE '%'||:q2||'%' OR spec LIKE '%'||:q2||'%' OR model LIKE '%'||:q2||'%' OR sku LIKE '%'||:q2||'%' OR barcode LIKE '%'||:q2||'%' OR initials LIKE '%'||:q2||'%') " +
            "ORDER BY CASE WHEN name LIKE '%'||:q1||'%' AND name LIKE '%'||:q2||'%' THEN 0 ELSE 1 END, CASE WHEN stock_qty > 0 THEN 0 ELSE 1 END, sell_price LIMIT 500",
    )
    suspend fun searchMulti(q1: String, q2: String, includeFrozen: Boolean = false): List<ProductEntity>

    @Query(
        "SELECT * FROM products WHERE (sale_status IS NULL OR sale_status != 'frozen' OR :includeFrozen = 1) " +
            "AND (name || ' ' || COALESCE(brand,'') || ' ' || COALESCE(category,'') || ' ' || COALESCE(spec,'') || ' ' || COALESCE(model,'')) LIKE :subseq " +
            "ORDER BY CASE WHEN name LIKE :subseq THEN 0 ELSE 1 END, CASE WHEN stock_qty > 0 THEN 0 ELSE 1 END, sell_price LIMIT 500",
    )
    suspend fun searchSubsequence(subseq: String, includeFrozen: Boolean = false): List<ProductEntity>

    @Query("SELECT COUNT(*) FROM products")
    suspend fun count(): Int

    /** I24/I29：条码修改本地立刻覆盖（更新 Room products 表），当场改完当场能扫。 */
    @Query("UPDATE products SET barcode = :barcode WHERE sku = :sku")
    suspend fun updateBarcodeBySku(sku: String, barcode: String): Int
}

@Dao
interface BillDao {
    @Query("SELECT * FROM bills ORDER BY created_at DESC")
    fun observeAll(): Flow<List<BillEntity>>

    @Query("SELECT * FROM bills WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): BillEntity?

    @Query("SELECT * FROM bills WHERE needs_sync = 1")
    suspend fun getUnsynced(): List<BillEntity>

    @Query("SELECT * FROM bills WHERE customer_name = :name")
    suspend fun getByCustomer(name: String): List<BillEntity>

    /** S19：最近记账的客户名（按账单最近更新倒序，散客/空名除外）——选客户弹窗快捷行。 */
    @Query(
        "SELECT customer_name FROM bills WHERE customer_name IS NOT NULL AND customer_name != '' " +
            "AND customer_name != '散客' GROUP BY customer_name ORDER BY MAX(updated_at) DESC LIMIT :limit"
    )
    suspend fun recentCustomerNames(limit: Int): List<String>

    @Query("UPDATE bills SET needs_sync = 0 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("UPDATE bills SET status = :status, paid_at = :paidAt, updated_at = :updatedAt, needs_sync = 1 WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, paidAt: String?, updatedAt: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bill: BillEntity)

    @Query("DELETE FROM bills WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers ORDER BY created_at DESC")
    fun observeAll(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getByName(name: String): CustomerEntity?

    @Query("SELECT * FROM customers WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CustomerEntity?

    /** S18：按管家婆客户 id 找已关联档案（null 安全，纯本地客户查不到）。 */
    @Query("SELECT * FROM customers WHERE upstream_id = :upstreamId LIMIT 1")
    suspend fun getByUpstreamId(upstreamId: String): CustomerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(customer: CustomerEntity)

    @Query("DELETE FROM customers WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY searched_at DESC LIMIT 30")
    fun observeAll(): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SearchHistoryEntity)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

@Dao
interface ProductViewDao {
    @Query("SELECT * FROM product_views ORDER BY viewed_at DESC LIMIT 20")
    fun observeRecent(): Flow<List<ProductViewEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProductViewEntity)
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM bill_draft WHERE id = 1 LIMIT 1")
    suspend fun get(): BillDraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(draft: BillDraftEntity)

    @Query("DELETE FROM bill_draft")
    suspend fun clear()
}
