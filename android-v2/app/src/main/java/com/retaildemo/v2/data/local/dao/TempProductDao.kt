package com.retaildemo.v2.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.retaildemo.v2.data.local.entity.TempProductEntity
import kotlinx.coroutines.flow.Flow

/** 无码货临时档案 DAO（B3）。自定义走待补全队列，串货走 B4 台账。 */
@Dao
interface TempProductDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tp: TempProductEntity)

    @Query("SELECT * FROM temp_products WHERE type = :type ORDER BY created_at DESC")
    suspend fun listByType(type: String): List<TempProductEntity>

    @Query("SELECT * FROM temp_products WHERE type = :type ORDER BY created_at DESC")
    fun observeByType(type: String): Flow<List<TempProductEntity>>

    /** 当日临时码计数（序号生成用）：temp_code 形如 TMP-{yyyyMMdd}-% 的行数。 */
    @Query("SELECT COUNT(*) FROM temp_products WHERE temp_code LIKE :pattern")
    suspend fun countMatching(pattern: String): Int

    @Query("SELECT * FROM temp_products WHERE temp_code = :code LIMIT 1")
    suspend fun getByCode(code: String): TempProductEntity?

    @Query("UPDATE temp_products SET reimburse_status = :status WHERE temp_code = :code")
    suspend fun setReimburseStatus(code: String, status: String)

    @Query("DELETE FROM temp_products WHERE temp_code = :code")
    suspend fun deleteByCode(code: String)
}
