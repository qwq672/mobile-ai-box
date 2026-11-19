package com.aibox.data.repo

import android.content.Context
import com.aibox.data.db.AppDatabase
import com.aibox.data.db.MessageEntity
import com.aibox.data.db.SessionEntity
import com.aibox.data.model.ChatMessage
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

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

    suspend fun createSession(
        title: String,
        modelPath: String?,
        mode: String = "CHAT",
        characterId: Long? = null,
        personaId: Long? = null
    ): Long {
        val now = System.currentTimeMillis()
        val s = SessionEntity(
            title = title,
            modelId = null,
            createdAt = now,
            lastUsedAt = now,
            messageCount = 0,
            modelPath = modelPath,
            mode = mode,
            characterId = characterId,
            personaId = personaId
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

    suspend fun updateMessage(msg: ChatMessage) {
        if (msg.id <= 0) return
        messageDao.update(msg.toEntity(msg.sessionId))
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
private val attachmentJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * 健壮解码 JSON 字段：解析失败不再静默降级为空，而是记录 error 日志并返回 null，
 * 交给调用方保底处理。避免工具调用历史 / 版本历史因脏数据「无声消失」而无法排查。
 * 原始数据始终留在 DB 里（此处只读、不回写），因此失败是可诊断、可恢复的。
 *
 * 这里为三个字段各写一个具名函数，而不是用 inline reified 泛型——后者在 lambda 中内联
 * reified 类型对编译环境更敏感，本仓库只能靠 CI 编译，故选择最保守的确定写法。
 */
private fun logDecodeFailure(raw: String, field: String, msgId: Long, t: Throwable) {
    LogSystem.error(
        "aibox.data.repo",
        "msg#$msgId 的 $field 解析失败(长度=${raw.length})，原始数据仍在 DB，未静默丢弃: ${t.message}",
        t
    )
}

private fun decodeAttachments(raw: String, msgId: Long): List<com.aibox.data.model.ChatAttachment> {
    if (raw.isBlank()) return emptyList()
    return runCatching { attachmentJson.decodeFromString<List<com.aibox.data.model.ChatAttachment>>(raw) }
        .getOrElse { t -> logDecodeFailure(raw, "attachments", msgId, t); emptyList() }
}

private fun decodeParts(raw: String, msgId: Long): List<com.aibox.data.model.MsgPart>? {
    if (raw.isBlank()) return emptyList()
    return runCatching { attachmentJson.decodeFromString<List<com.aibox.data.model.MsgPart>>(raw) }
        .getOrElse { t -> logDecodeFailure(raw, "parts", msgId, t); null }
}

private fun decodeVersions(raw: String, msgId: Long): List<String>? {
    if (raw.isBlank()) return emptyList()
    return runCatching { attachmentJson.decodeFromString<List<String>>(raw) }
        .getOrElse { t -> logDecodeFailure(raw, "versions", msgId, t); null }
}

private fun MessageEntity.toChatMessage(): ChatMessage {
    val parts = decodeParts(partsJson, id) ?: parseFallbackParts(content)
    val versions = decodeVersions(versionsJson, id) ?: listOf(content)
    // 版本历史可能因脏数据从多版降级为单版，收敛下标避免 UI 访问 versions[index] 越界崩溃。
    val safeVersionIndex = versionIndex.coerceIn(0, (versions.size - 1).coerceAtLeast(0))
    return ChatMessage(
        id = id, sessionId = sessionId, role = role, content = content,
        ts = ts, tokensPerSecond = tokensPerSecond,
        attachments = decodeAttachments(attachmentsJson, id),
        parts = parts,
        versions = versions,
        versionIndex = safeVersionIndex,
        parentUserVersion = parentUserVersion
    )
}

/**
 * parts 解析失败时的保底：至少把正文还原成一条 text part，保证用户看得到内容，
 * 不会因时间线 JSON 损坏而显示成空白气泡。工具卡片的原始数据仍留在 DB 的 partsJson 里。
 */
private fun parseFallbackParts(content: String): List<com.aibox.data.model.MsgPart> =
    if (content.isBlank()) emptyList() else listOf(com.aibox.data.model.MsgPart.text(content))
private fun ChatMessage.toEntity(sid: Long) = MessageEntity(
    id = id, sessionId = sid, role = role, content = content,
    ts = ts, tokensPerSecond = tokensPerSecond,
    attachmentsJson = if (attachments.isEmpty()) ""
    else attachmentJson.encodeToString(attachments),
    partsJson = if (parts.isEmpty()) "" else attachmentJson.encodeToString(parts),
    versionsJson = if (versions.isEmpty()) "" else attachmentJson.encodeToString(versions),
    versionIndex = versionIndex,
    parentUserVersion = parentUserVersion
)
