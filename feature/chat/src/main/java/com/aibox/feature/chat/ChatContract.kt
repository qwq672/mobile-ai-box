package com.aibox.feature.chat

import com.aibox.core.inference.InferenceRequest
import com.aibox.core.inference.InferenceResult
import com.aibox.core.inference.ChatTurn

/**
 * 聊天会话状态。
 *
 * 反屎山原则：
 * - 不可变 data class：所有修改通过 copy() 产生新实例
 * - 历史只在 ViewModel 层维护，UI 只渲染快照
 * - 流式更新通过 [ChatUiState.Streaming] 表达，不与 [ChatUiState.Idle] 混用字段
 */
public sealed interface ChatUiState {
    public data object Idle : ChatUiState
    public data class Streaming(val partialResponse: String, val tokensPerSec: Double) : ChatUiState
    public data class Completed(val result: InferenceResult) : ChatUiState
    public data class Error(val message: String) : ChatUiState
}

/**
 * 完整对话（包含历史）。
 *
 * [history] 用于多轮上下文管理：
 * - 限制最大轮数（避免 token 爆炸）：当前 20 轮
 * - 超限时采用"摘要+保留最新 N 轮"策略（M2 实现）
 */
public data class ChatSession(
    public val id: String,
    public val title: String = "New Chat",
    public val personaId: String? = null,
    public val history: List<ChatTurn> = emptyList(),
    public val activeBackendId: String? = null,
    public val maxHistoryTurns: Int = 20,
)

/** 聊天发送意图 */
public sealed interface ChatIntent {
    public data class Send(val text: String, val images: List<String> = emptyList()) : ChatIntent
    public data object Cancel : ChatIntent
    public data class SwitchBackend(val backendId: String) : ChatIntent
    public data class SwitchPersona(val personaId: String?) : ChatIntent
    public data class Regenerate(val fromTurnIndex: Int) : ChatIntent
    public data object Clear : ChatIntent
}
