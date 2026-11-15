package com.aibox.domain.cloud

/**
 * 云端模型配置。
 *
 * 走 OpenAI 兼容协议（`POST {baseUrl}/chat/completions`），
 * 因此一套实现可以覆盖 OpenAI / DeepSeek / Moonshot / Ollama / vLLM / LM Studio。
 * baseUrl 允许自定义，本地局域网里的推理服务也能直接接。
 */
data class CloudConfig(
    val enabled: Boolean = false,
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048
) {
    /** 配置是否可用于发起请求。 */
    val usable: Boolean
        get() = enabled && baseUrl.isNotBlank() && model.isNotBlank()

    /** UI 上展示的短名，如 `deepseek-chat`。 */
    val label: String
        get() = model.ifBlank { "cloud" }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-chat"
    }
}

/** 云端对话消息（内部使用，比 ChatMsg 多带 tool_calls / tool_call_id）。 */
data class CloudMsg(
    val role: String,
    val content: String,
    val toolCalls: List<CloudToolCall> = emptyList(),
    val toolCallId: String? = null
)

/** 模型返回的一次工具调用。 */
data class CloudToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

/** 一轮流式请求的汇总结果。 */
data class CloudTurn(
    val content: String,
    val toolCalls: List<CloudToolCall>,
    val finishReason: String
)

/** 常见 provider 预设，仅用于设置页一键填充。 */
data class CloudPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val model: String,
    val needsKey: Boolean = true
)

object CloudPresets {
    val all: List<CloudPreset> = listOf(
        CloudPreset("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
        CloudPreset("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        CloudPreset("moonshot", "Moonshot", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
        CloudPreset("ollama", "Ollama（本机）", "http://127.0.0.1:11434/v1", "qwen2.5:7b", needsKey = false)
    )

    fun byId(id: String): CloudPreset? = all.firstOrNull { it.id == id }
}
