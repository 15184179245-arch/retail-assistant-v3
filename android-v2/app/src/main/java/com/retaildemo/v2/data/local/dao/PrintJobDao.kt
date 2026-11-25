package com.retaildemo.v2.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.retaildemo.v2.data.local.entity.PrintJobEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PrintJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: PrintJobEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(jobs: List<PrintJobEntity>)

    @Update
    suspend fun update(job: PrintJobEntity)

    @Query("SELECT * FROM print_jobs WHERE sku = :sku AND status = 'pending' LIMIT 1")
    suspend fun findPendingBySku(sku: String): PrintJobEntity?

    @Query("SELECT * FROM print_jobs ORDER BY created_at DESC")
    fun observeAll(): Flow<List<PrintJobEntity>>

    @Query("SELECT * FROM print_jobs WHERE status = 'pending' ORDER BY created_at ASC")
    suspend fun getPendingJobs(): List<PrintJobEntity>

    @Query("SELECT * FROM print_jobs WHERE status = 'failed' ORDER BY created_at DESC")
    suspend fun getFailedJobs(): List<PrintJobEntity>

    @Query("SELECT * FROM print_jobs WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PrintJobEntity?

    @Query("UPDATE print_jobs SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE print_jobs SET status = :status, fail_count = fail_count + 1 WHERE id = :id")
    suspend fun recordFailure(id: Long, status: String = "failed")

    @Query("UPDATE print_jobs SET status = 'pending', fail_count = 0 WHERE id = :id")
    suspend fun retryJob(id: Long)

    @Query("UPDATE print_jobs SET qty = qty + :deltaQty WHERE id = :id")
    suspend fun increaseQty(id: Long, deltaQty: Int)

    @Query("DELETE FROM print_jobs WHERE status = 'done'")
    suspend fun clearDone()

    @Query("DELETE FROM print_jobs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM print_jobs WHERE status = 'pending'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM print_jobs WHERE status = 'pending' ORDER BY created_at ASC")
    fun observePendingJobs(): Flow<List<PrintJobEntity>>
}
