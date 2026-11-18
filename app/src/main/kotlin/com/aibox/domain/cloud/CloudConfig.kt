package com.aibox.domain.cloud

/**
 * 云端模型配置。
 *
 * 走 OpenAI 兼容协议（`POST {baseUrl}/chat/completions`），
 * 因此一套实现可以覆盖 OpenAI / DeepSeek / Moonshot / Ollama / vLLM / LM Studio。
 * baseUrl 允许自定义，本地局域网里的推理服务也能直接接。
 */
/**
 * 云端接口格式。
 *
 * 目前只有 [OPENAI_CHAT] 有完整实现（流式、工具调用、多模态）。
 * 其余格式先占位，UI 中标记「即将支持」，避免用户选中后请求直接失败。
 */
enum class CloudProtocol(
    val id: String,
    val label: String,
    val supported: Boolean = true
) {
    OPENAI_CHAT("openai_chat", "OpenAI Chat Completions"),
    OPENAI_RESPONSES("openai_responses", "OpenAI Responses（即将支持）", supported = false),
    ANTHROPIC_MESSAGES("anthropic_messages", "Anthropic Messages（即将支持）", supported = false),
    OLLAMA_NATIVE("ollama_native", "Ollama 原生（即将支持）", supported = false);

    companion object {
        fun fromId(id: String?): CloudProtocol =
            entries.firstOrNull { it.id == id } ?: OPENAI_CHAT
    }
}

data class CloudConfig(
    val enabled: Boolean = false,
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    /** 接口格式；决定请求/流式/工具调用用哪套实现。 */
    val protocol: CloudProtocol = CloudProtocol.OPENAI_CHAT,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    /** 供应商/模型是否支持图片输入（多模态）。 */
    val supportsVision: Boolean = false,
    /** 是否支持音频输入。 */
    val supportsAudio: Boolean = false,
    /** 供应商自带联网搜索（无需本地 web_search 工具）。 */
    val builtinWebSearch: Boolean = false,
    /** 供应商自带 URL 阅读（无需本地 url_reader 工具）。 */
    val builtinUrlReader: Boolean = false
) {
    /** 配置是否可用于发起请求。 */
    val usable: Boolean
        get() = enabled && baseUrl.isNotBlank() && model.isNotBlank() && protocol.supported

    /** UI 上展示的短名，如 `deepseek-chat`。 */
    val label: String
        get() = model.ifBlank { "cloud" }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-chat"
    }
}

/** 云端对话消息（内部使用，比 ChatMsg 多带 tool_calls / tool_call_id / 多模态图片）。 */
data class CloudMsg(
    val role: String,
    val content: String,
    val toolCalls: List<CloudToolCall> = emptyList(),
    val toolCallId: String? = null,
    /** data URL（data:image/png;base64,...）列表，供多模态模型使用。 */
    val images: List<String> = emptyList()
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
        CloudPreset("moonshot", "Kimi（Moonshot）", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
        CloudPreset("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
        CloudPreset("dashscope", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
        CloudPreset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
        CloudPreset("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct"),
        CloudPreset("ollama", "Ollama（本机）", "http://127.0.0.1:11434/v1", "qwen2.5:7b", needsKey = false)
    )

    fun byId(id: String): CloudPreset? = all.firstOrNull { it.id == id }
}
