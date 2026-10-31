package com.aibox.feature.translate

import com.aibox.core.common.Outcome

/**
 * 翻译能力 — 独立于 chat 的专门 UI 入口。
 *
 * 实现路径：
 *   1. 借助 chat 后端 + 翻译 systemPrompt（最简单，质量取决于 LLM）
 *   2. 借助专门翻译插件（如 NLLB-200 / SeamlessM4T），质量更高
 *
 * 设计原则：
 *   - 接口零依赖：不绑定具体后端
 *   - 自动语种检测：[LanguageCode.AUTO] 时由后端判断
 *   - 流式输出：长翻译（>1000 字）走流式，避免 UI 卡顿
 */
public interface Translator {

    public suspend fun translate(request: TranslateRequest): Outcome<TranslateResult>

    public suspend fun translateStreamed(
        request: TranslateRequest,
        onToken: (String) -> Unit,
    ): Outcome<TranslateResult>
}

public data class TranslateRequest(
    public val text: String,
    public val from: LanguageCode,
    public val to: LanguageCode,
    public val backendId: String? = null,   // null = 自动选
    public val useTranslationPlugin: Boolean = false,  // true 优先用 NLLB 等专用插件
)

public data class TranslateResult(
    public val translatedText: String,
    public val detectedSourceLang: LanguageCode?,
    public val backendUsed: String,
    public val latencyMs: Long,
    public val npuUsed: Boolean,
)

/**
 * 常用语种码（ISO 639-1）。
 *
 * 反屎山：枚举集中定义，禁止散落字符串字面量。
 * [AUTO] 表示自动检测源语种。
 */
public enum class LanguageCode(public val iso6391: String, public val displayName: String) {
    AUTO("auto", "自动检测"),
    ZH_CN("zh-CN", "简体中文"),
    ZH_TW("zh-TW", "繁体中文"),
    EN("en", "English"),
    JA("ja", "日本語"),
    KO("ko", "한국어"),
    FR("fr", "Français"),
    DE("de", "Deutsch"),
    ES("es", "Español"),
    RU("ru", "Русский"),
    AR("ar", "العربية"),
    HI("hi", "हिन्दी"),
    VI("vi", "Tiếng Việt"),
    TH("th", "ภาษาไทย"),
    ID("id", "Bahasa Indonesia");
}
