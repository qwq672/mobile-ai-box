package com.aibox.domain.shortcut

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 快捷方式数据模型（E 主页 Hybrid 设计）。
 *
 * 一个 [Shortcut] 描述"用户预存的一组功能入口配置"：点击后直接进入对应功能页，
 * 配置（system prompt / temperature / 步数等）已预填，省去每次手动调整的麻烦。
 *
 * 设计要点：
 * - [targetType] 决定跳到哪个功能页（聊天 / 生图 / ASR / TTS / 翻译 / 基准 / 插件页）。
 * - [presetConfig] 携带预填配置；不同 [ShortcutTargetType] 读取不同子集（聊天读 systemPrompt/temperature，
 *   生图读 samplingSteps/guidanceScale，翻译读 sourceLanguage/targetLanguage，等等）。
 * - [modelId] 可选绑定模型 id；空表示"使用当前激活模型"。
 * - [icon] 是 emoji 字符串（"📝" / "🎨"…）；不引入自定义图标资源依赖。
 *
 * 持久化：通过 [ShortcutRepository] 用 DataStore JSON 序列化整列表存（详见 repository）。
 */
@Serializable
data class Shortcut(
    /** UUID 或短 id；由 [ShortcutRepository.create] 分配。 */
    val id: String,
    /** 主标题，如 "和林黛玉聊天"。 */
    val title: String,
    /** 副标题，如 "Qwen2.5-0.5B Q4_K_M"；可空。 */
    val subtitle: String? = null,
    /** Emoji 图标字符，如 "📝" / "🎨" / "🗣️"。 */
    val icon: String,
    /** 跳转目标功能类型。 */
    val targetType: ShortcutTargetType,
    /** 预填配置；不同 targetType 读取不同子集。 */
    val presetConfig: PresetConfig = PresetConfig(),
    /** 绑定的模型 id（数据库主键）；null 表示"使用当前激活模型"。 */
    val modelId: String? = null,
    /** 自定义颜色（hex 字符串如 "#FF6F00"）；当前 UI 不强制使用，预留扩展位。 */
    val color: String? = null,
    /** 创建时间戳（毫秒）。 */
    val createdAt: Long = System.currentTimeMillis(),
    /** 最近一次使用时间戳；null 表示从未使用。 */
    val lastUsedAt: Long? = null
)

/**
 * 快捷方式跳转目标类型。
 *
 * - [CHAT]：进入 [com.aibox.ui.chat.ChatScreen]，应用 systemPrompt/temperature 等预填。
 * - [IMAGE_GEN]：进入图像生成页（占位，等模块装了才有）；预填 samplingSteps/sampler/guidanceScale。
 * - [ASR]：进入语音识别页（占位）；后续接 ASR 模块。
 * - [TTS]：进入语音合成页（占位）；后续接 TTS 模块。
 * - [TRANSLATE]：进入翻译页（占位）；预填 sourceLanguage/targetLanguage。
 * - [BENCHMARK]：进入基准测试页；预填 backendName/threads/kvQuant。
 * - [PLUGIN_PAGE]：进入插件提供的独立页面（预留扩展）。
 */
enum class ShortcutTargetType {
    CHAT,
    IMAGE_GEN,
    ASR,
    TTS,
    TRANSLATE,
    BENCHMARK,
    PLUGIN_PAGE;

    companion object {
        /** 全部 targetType 的中文显示名（用于编辑器选择列表）。 */
        fun displayName(type: ShortcutTargetType): String = when (type) {
            CHAT        -> "聊天"
            IMAGE_GEN   -> "图像生成"
            ASR         -> "语音识别"
            TTS         -> "语音合成"
            TRANSLATE   -> "翻译"
            BENCHMARK   -> "基准测试"
            PLUGIN_PAGE -> "插件页面"
        }

        /** 默认 emoji 图标。 */
        fun defaultIcon(type: ShortcutTargetType): String = when (type) {
            CHAT        -> "💬"
            IMAGE_GEN   -> "🎨"
            ASR         -> "🗣️"
            TTS         -> "📢"
            TRANSLATE   -> "🌐"
            BENCHMARK   -> "📊"
            PLUGIN_PAGE -> "🧩"
        }
    }
}

/**
 * 预填配置。所有字段可空；null 表示"使用功能页默认值"。
 *
 * 不同 [ShortcutTargetType] 读取不同子集：
 * - CHAT：[systemPrompt] / [temperature] / [topP] / [maxTokens]
 * - IMAGE_GEN：[samplingSteps] / [sampler] / [guidanceScale]
 * - TRANSLATE：[sourceLanguage] / [targetLanguage]
 * - BENCHMARK：[backendName] / [threads] / [kvQuant]
 * - 其它：仅读 [customPayload]
 *
 * [customPayload] 用 [JsonElement] 持有任意 JSON 结构，避免 Map<String, Any> 无法被
 * kotlinx.serialization 直接序列化的问题；UI / 业务层按需 cast 到 JsonObject / JsonArray / JsonPrimitive。
 */
@Serializable
data class PresetConfig(
    // ---- 聊天 ----
    /** 聊天角色设定（system prompt）；空表示用 Settings 的全局 systemPrompt。 */
    val systemPrompt: String? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,

    // ---- 图像生成 ----
    val samplingSteps: Int? = null,
    /** 采样器名（"Euler a" / "DPM++ 2M"…）；空表示用默认。 */
    val sampler: String? = null,
    val guidanceScale: Float? = null,

    // ---- 翻译 ----
    /** 翻译源语言（"auto" / "zh" / "en"…）。 */
    val sourceLanguage: String? = null,
    /** 翻译目标语言。 */
    val targetLanguage: String? = null,

    // ---- 基准测试 ----
    /** 指定后端名（如 "llamaCpp"）；空表示用当前 V2 后端。 */
    val backendName: String? = null,
    /** 测试线程数；null 表示后端自动决定。 */
    val threads: Int? = null,
    /** KV 量化类型名（如 "Q8_0" / "F16"）；空表示默认。 */
    val kvQuant: String? = null,

    /** 其它预填；用 [JsonElement] 持有任意 JSON 结构。 */
    val customPayload: Map<String, JsonElement> = emptyMap()
)
