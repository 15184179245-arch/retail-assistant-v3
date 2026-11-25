package com.retaildemo.v2.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.retaildemo.v2.data.local.entity.BillDraftEntity
import com.retaildemo.v2.data.local.entity.CustomerEntity
import com.retaildemo.v2.data.local.entity.DataMarkEntity
import com.retaildemo.v2.data.local.entity.ProductSupplementEntity
import com.retaildemo.v2.data.local.entity.ProductViewEntity
import com.retaildemo.v2.data.local.entity.SearchHistoryEntity
import com.retaildemo.v2.data.local.entity.TempProductEntity

/** S13 数据全景：孤岛表只读快照（镜像推送用，单向 APP→后端，永不回写）。 */
@Dao
interface MirrorDao {
    @Query("SELECT * FROM temp_products")
    suspend fun tempProducts(): List<TempProductEntity>

    @Query("SELECT * FROM data_marks")
    suspend fun dataMarks(): List<DataMarkEntity>

    @Query("SELECT * FROM product_supplements")
    suspend fun supplements(): List<ProductSupplementEntity>

    @Query("SELECT * FROM product_views")
    suspend fun productViews(): List<ProductViewEntity>

    @Query("SELECT * FROM search_history")
    suspend fun searchHistory(): List<SearchHistoryEntity>

    @Query("SELECT * FROM bill_draft")
    suspend fun drafts(): List<BillDraftEntity>

    @Query("SELECT * FROM customers")
    suspend fun customers(): List<CustomerEntity>
}
