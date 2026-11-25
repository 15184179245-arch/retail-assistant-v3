package com.retaildemo.v2.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.retaildemo.v2.data.local.entity.DataMarkEntity
import kotlinx.coroutines.flow.Flow

/** 数据问题标记 DAO（I4）。状态机 open→exported→resolved，resolved 可退回 open（人工确认才关）。 */
@Dao
interface DataMarkDao {
    @Insert
    suspend fun insert(e: DataMarkEntity): Long

    @Update
    suspend fun update(e: DataMarkEntity)

    @Query("SELECT * FROM data_marks WHERE status = :s ORDER BY created_at DESC")
    suspend fun listByStatus(s: String): List<DataMarkEntity>

    @Query("SELECT * FROM data_marks WHERE status = :s ORDER BY created_at DESC")
    fun observeByStatus(s: String): Flow<List<DataMarkEntity>>

    @Query("SELECT * FROM data_marks ORDER BY created_at DESC")
    fun observeAll(): Flow<List<DataMarkEntity>>

    @Query("UPDATE data_marks SET status = 'exported' WHERE id = :id AND status = 'open'")
    suspend fun markExported(id: Long)

    @Query("UPDATE data_marks SET status = 'resolved', resolved_at = :t WHERE id = :id")
    suspend fun resolve(id: Long, t: String)

    @Query("UPDATE data_marks SET status = 'open', resolved_at = NULL WHERE id = :id")
    suspend fun reopen(id: Long)
}
