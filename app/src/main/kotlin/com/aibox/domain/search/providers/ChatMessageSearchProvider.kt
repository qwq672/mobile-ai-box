package com.aibox.domain.search.providers

import com.aibox.data.repo.ChatRepository
import com.aibox.domain.search.SearchDomain
import com.aibox.domain.search.SearchProvider
import com.aibox.domain.search.SearchResult
import com.aibox.domain.search.SearchableEntity
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.flow.first

/**
 * 聊天消息搜索 Provider：跨 session 全文搜索消息内容。
 *
 * 数据来源：[ChatRepository.searchAll]（按 content LIKE 查询，跨所有 session）。
 * 返回 [SearchDomain.CHAT_MESSAGE] 域的结果。
 *
 * 与 [ChatSearchProvider]（会话标题搜索）分离：每个 Provider 只负责一个域。
 *
 * 评分：正文匹配固定 0.4（参考 [com.aibox.domain.search.scoreMatch] 的 content 规则）；
 * 单独 Provider 处理而不是用 [scoreMatch]，因为 [ChatRepository.searchAll] 已经做了 LIKE 过滤，
 * 这里只需要给每条结果打固定 score。
 */
class ChatMessageSearchProvider(
    private val chatRepository: ChatRepository
) : SearchProvider {

    private val tag = "aibox.search.chat.msg"
    override fun getDomain(): SearchDomain = SearchDomain.CHAT_MESSAGE

    override suspend fun search(query: String): List<SearchResult> {
        return try {
            val results = chatRepository.searchAll(query).first()
            results.map { r ->
                val snippet = r.content.take(120).replace("\n", " ")
                val e = MessageEntityData(
                    searchId = "chat_msg:${r.sessionId}:${r.messageId}",
                    searchTitle = "「${r.sessionTitle}」中 ${if (r.role == "user") "用户" else "助手"}",
                    searchSubtitle = snippet,
                    searchKeywords = emptyList(),
                    searchTimestamp = r.ts,
                    sessionId = r.sessionId,
                    messageId = r.messageId
                )
                SearchResult(
                    entity = e,
                    score = 0.4, // 正文匹配固定 0.4
                    matchedField = "content",
                    matchedSnippet = "「${r.sessionTitle}」 · $snippet"
                )
            }
        } catch (t: Throwable) {
            LogSystem.error(tag, "search failed: ${t.message}", t)
            emptyList()
        }
    }
}

/** 聊天消息实体。 */
private data class MessageEntityData(
    override val searchId: String,
    override val searchTitle: String,
    override val searchSubtitle: String,
    override val searchKeywords: List<String>,
    override val searchTimestamp: Long?,
    val sessionId: Long,
    val messageId: Long
) : SearchableEntity {
    override val searchDomain: SearchDomain = SearchDomain.CHAT_MESSAGE
    override val searchData: Map<String, Any> = mapOf(
        "sessionId" to sessionId,
        "messageId" to messageId
    )
}
