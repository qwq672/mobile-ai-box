package com.aibox.domain.search.providers

import com.aibox.data.repo.ChatRepository
import com.aibox.domain.search.SearchDomain
import com.aibox.domain.search.SearchProvider
import com.aibox.domain.search.SearchResult
import com.aibox.domain.search.SearchableEntity
import com.aibox.domain.search.scoreMatch
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.flow.first

/**
 * 聊天会话搜索 Provider：按会话标题模糊匹配。
 *
 * 数据来源：[ChatRepository.searchSessions]（按标题 LIKE 查询）。
 * 返回 [SearchDomain.CHAT_CONVERSATION] 域的结果。
 *
 * 与 [ChatMessageSearchProvider] 分离：每个 Provider 只负责一个域，
 * 便于 SearchEngine 按 domain 过滤；用户可单独选择"会话"或"消息"两个 chip。
 */
class ChatSearchProvider(
    private val chatRepository: ChatRepository
) : SearchProvider {

    private val tag = "aibox.search.chat.conv"
    override fun getDomain(): SearchDomain = SearchDomain.CHAT_CONVERSATION

    override suspend fun search(query: String): List<SearchResult> {
        return try {
            val sessions = chatRepository.searchSessions(query).first()
            sessions.mapNotNull { s ->
                val keywords = s.title.split(Regex("\\s+")).filter { it.isNotBlank() }
                val match = scoreMatch(
                    query = query,
                    title = s.title,
                    subtitle = "消息数 ${s.messageCount}",
                    keywords = keywords
                ) ?: return@mapNotNull null
                SearchResult(
                    entity = ConversationEntity(
                        searchId = "chat_conv:${s.id}",
                        searchTitle = s.title,
                        searchSubtitle = "消息数 ${s.messageCount} · 最近 ${formatTime(s.lastUsedAt)}",
                        searchKeywords = keywords,
                        searchTimestamp = s.lastUsedAt,
                        sessionId = s.id
                    ),
                    score = match.first,
                    matchedField = match.second,
                    matchedSnippet = match.third
                )
            }
        } catch (t: Throwable) {
            LogSystem.error(tag, "search failed: ${t.message}", t)
            emptyList()
        }
    }

    /** 格式化时间戳为简短显示。 */
    private fun formatTime(ts: Long): String {
        if (ts <= 0) return "—"
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3_600_000 -> "${diff / 60_000} 分钟前"
            diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
            else -> "${diff / 86_400_000} 天前"
        }
    }
}

/** 聊天会话实体。 */
private data class ConversationEntity(
    override val searchId: String,
    override val searchTitle: String,
    override val searchSubtitle: String,
    override val searchKeywords: List<String>,
    override val searchTimestamp: Long?,
    val sessionId: Long
) : SearchableEntity {
    override val searchDomain: SearchDomain = SearchDomain.CHAT_CONVERSATION
    override val searchData: Map<String, Any> = mapOf("sessionId" to sessionId)
}
