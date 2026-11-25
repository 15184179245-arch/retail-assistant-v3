package com.retaildemo.v2.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.retaildemo.v2.data.local.entity.ProductSupplementEntity
import kotlinx.coroutines.flow.Flow

/**
 * 商品信息本地补充 DAO（I4 §11）。独立表 keyed by sku——商品同步整表 upsert
 * 从不碰本表，本地补充天然 preserve；价格永不经此入口（红线 3）。
 */
@Dao
interface SupplementDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: ProductSupplementEntity)

    @Query("SELECT * FROM product_supplements WHERE sku = :sku LIMIT 1")
    suspend fun getBySku(sku: String): ProductSupplementEntity?

    @Query("SELECT * FROM product_supplements WHERE sku = :sku LIMIT 1")
    fun observeBySku(sku: String): Flow<ProductSupplementEntity?>

    @Query("SELECT * FROM product_supplements ORDER BY updated_at DESC")
    suspend fun listAll(): List<ProductSupplementEntity>

    @Query("SELECT * FROM product_supplements ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<ProductSupplementEntity>>
}
