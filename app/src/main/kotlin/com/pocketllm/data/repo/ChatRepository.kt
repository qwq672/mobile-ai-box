package com.pocketllm.data.repo

import android.content.Context
import com.pocketllm.data.db.AppDatabase
import com.pocketllm.data.db.MessageEntity
import com.pocketllm.data.db.SessionEntity
import com.pocketllm.data.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 聊天会话仓库：管理历史话题 + 消息持久化。
 */
class ChatRepository(context: Context) {

    private val db = AppDatabase.get(context)
    private val sessionDao = db.sessionDao()
    private val messageDao = db.messageDao()

    // ---- Session ----

    fun observeSessions(): Flow<List<SessionEntity>> = sessionDao.observeAll()

    fun searchSessions(q: String): Flow<List<SessionEntity>> =
        if (q.isBlank()) sessionDao.observeAll() else sessionDao.searchByTitle(q.trim())

    suspend fun createSession(title: String, modelPath: String?): Long {
        val now = System.currentTimeMillis()
        val s = SessionEntity(
            title = title,
            modelId = null,
            createdAt = now,
            lastUsedAt = now,
            messageCount = 0,
            modelPath = modelPath
        )
        return sessionDao.insert(s)
    }

    suspend fun updateSessionTitle(id: Long, title: String) {
        val s = sessionDao.getById(id) ?: return
        sessionDao.update(s.copy(title = title, lastUsedAt = System.currentTimeMillis()))
    }

    suspend fun touchSession(id: Long) {
        val s = sessionDao.getById(id) ?: return
        sessionDao.update(s.copy(lastUsedAt = System.currentTimeMillis()))
    }

    suspend fun deleteSession(id: Long) {
        messageDao.deleteBySession(id)
        sessionDao.deleteById(id)
    }

    suspend fun getSession(id: Long): SessionEntity? = sessionDao.getById(id)

    // ---- Messages ----

    fun observeMessages(sessionId: Long): Flow<List<ChatMessage>> =
        messageDao.observeBySession(sessionId).map { list ->
            list.map { it.toChatMessage() }
        }

    suspend fun getMessages(sessionId: Long): List<ChatMessage> =
        messageDao.getBySession(sessionId).map { it.toChatMessage() }

    suspend fun addMessage(sessionId: Long, msg: ChatMessage): Long {
        val entity = msg.toEntity(sessionId)
        val id = messageDao.insert(entity)
        // 更新 session 的 messageCount 和 lastUsedAt
        val s = sessionDao.getById(sessionId)
        if (s != null) {
            sessionDao.update(s.copy(
                messageCount = s.messageCount + 1,
                lastUsedAt = System.currentTimeMillis()
            ))
        }
        return id
    }

    suspend fun deleteMessage(id: Long) {
        messageDao.deleteById(id)
    }

    fun searchInSession(sessionId: Long, q: String): Flow<List<ChatMessage>> =
        messageDao.searchInSession(sessionId, q.trim()).map { list ->
            list.map { it.toChatMessage() }
        }

    fun searchAll(q: String): Flow<List<SearchResult>> =
        messageDao.searchAll(q.trim()).map { list ->
            list.map { e ->
                val session = sessionDao.getById(e.sessionId)
                SearchResult(
                    messageId = e.id,
                    sessionId = e.sessionId,
                    sessionTitle = session?.title ?: "(已删除)",
                    role = e.role,
                    content = e.content,
                    ts = e.ts
                )
            }
        }
}

/** 全局搜索结果：跨 session 的消息命中 */
data class SearchResult(
    val messageId: Long,
    val sessionId: Long,
    val sessionTitle: String,
    val role: String,
    val content: String,
    val ts: Long
)

// ---- mapping ----
private fun MessageEntity.toChatMessage() = ChatMessage(
    id = id, sessionId = sessionId, role = role, content = content,
    ts = ts, tokensPerSecond = tokensPerSecond
)
private fun ChatMessage.toEntity(sid: Long) = MessageEntity(
    id = id, sessionId = sid, role = role, content = content,
    ts = ts, tokensPerSecond = tokensPerSecond
)
