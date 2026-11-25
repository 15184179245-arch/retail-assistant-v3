package com.retaildemo.v2.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.retaildemo.v2.data.local.entity.ClientLogEntity

/**
 * H2（2026-09-11 老板令）APP 运行日志 DAO：
 * 写入（AppLogger）→ 批量取待传（LogUploadWorker）→ 上传成功即删；容量靠 pruneKeepLatest 封顶。
 */
@Dao
interface ClientLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: ClientLogEntity): Long

    /** 待上传日志（最老在前，保证服务端时序）。 */
    @Query("SELECT * FROM client_logs ORDER BY id ASC LIMIT :limit")
    suspend fun pendingLogs(limit: Int): List<ClientLogEntity>

    @Query("DELETE FROM client_logs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** 只留最新 :keep 条（超量砍最老一半由 AppLogger 控制节奏）。 */
    @Query("DELETE FROM client_logs WHERE id NOT IN (SELECT id FROM client_logs ORDER BY id DESC LIMIT :keep)")
    suspend fun pruneKeepLatest(keep: Int)
}
