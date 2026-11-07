package com.pocketllm.data.db

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
