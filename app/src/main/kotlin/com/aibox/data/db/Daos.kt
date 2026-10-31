package com.aibox.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY lastUsedAt DESC")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun getById(id: Long): ModelEntity?

    @Query("SELECT * FROM models ORDER BY lastUsedAt DESC LIMIT 1")
    suspend fun lastUsed(): ModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(m: ModelEntity): Long

    @Update
    suspend fun update(m: ModelEntity)

    @Delete
    suspend fun delete(m: ModelEntity)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY lastUsedAt DESC, createdAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    /** 按标题模糊搜索 */
    @Query("SELECT * FROM sessions WHERE title LIKE '%' || :q || '%' ORDER BY lastUsedAt DESC")
    fun searchByTitle(q: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: Long): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(s: SessionEntity): Long

    @Update
    suspend fun update(s: SessionEntity)

    @Delete
    suspend fun delete(s: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sid ORDER BY ts ASC")
    fun observeBySession(sid: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sid ORDER BY ts ASC")
    suspend fun getBySession(sid: Long): List<MessageEntity>

    /** 在指定 session 内搜索消息内容 */
    @Query("SELECT * FROM messages WHERE sessionId = :sid AND content LIKE '%' || :q || '%' ORDER BY ts ASC")
    fun searchInSession(sid: Long, q: String): Flow<List<MessageEntity>>

    /** 全局搜索消息内容（用于跨 session 搜索） */
    @Query("SELECT * FROM messages WHERE content LIKE '%' || :q || '%' ORDER BY ts DESC")
    fun searchAll(q: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(m: MessageEntity): Long

    @Query("DELETE FROM messages WHERE sessionId = :sid")
    suspend fun deleteBySession(sid: Long)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sid")
    suspend fun countBySession(sid: Long): Int
}

/**
 * 基准测试结果 DAO（P2 benchmark system）。
 *
 * 支持按 modelPath / backendName / 时间范围查询历史记录，
 * 供 UI 显示"历史基准测试结果"与跨设备对比。
 */
@Dao
interface BenchmarkResultDao {
    /** 全部记录，按时间倒序；UI 默认列表用。 */
    @Query("SELECT * FROM benchmark_results ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<BenchmarkResultEntity>>

    /** 按模型路径过滤（同一模型的多次测试）。 */
    @Query("SELECT * FROM benchmark_results WHERE modelPath = :modelPath ORDER BY timestamp DESC")
    fun observeByModel(modelPath: String): Flow<List<BenchmarkResultEntity>>

    /** 按后端名过滤（同一后端在不同模型 / 配置下的表现）。 */
    @Query("SELECT * FROM benchmark_results WHERE backendName = :backendName ORDER BY timestamp DESC")
    fun observeByBackend(backendName: String): Flow<List<BenchmarkResultEntity>>

    /** 按时间范围（毫秒）过滤，时间倒序。 */
    @Query("SELECT * FROM benchmark_results WHERE timestamp >= :from AND timestamp <= :to ORDER BY timestamp DESC")
    fun observeByTimeRange(from: Long, to: Long): Flow<List<BenchmarkResultEntity>>

    /** 单次按 ID 查询。 */
    @Query("SELECT * FROM benchmark_results WHERE id = :id")
    suspend fun getById(id: Long): BenchmarkResultEntity?

    /** 取最近 N 条（用于首页 dashboard）。 */
    @Query("SELECT * FROM benchmark_results ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<BenchmarkResultEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(e: BenchmarkResultEntity): Long

    @Query("DELETE FROM benchmark_results WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM benchmark_results")
    suspend fun deleteAll()
}
