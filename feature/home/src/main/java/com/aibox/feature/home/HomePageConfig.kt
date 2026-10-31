package com.aibox.feature.home

import kotlinx.serialization.Serializable

/**
 * 自定义主页 — 用户可自定义首屏内容和默认入口。
 *
 * 设计原则：
 *   - 主页不是固定的，用户可选择"我常做什么" → 显示对应快速入口卡片
 *   - 首次启动 [OnboardingFlow] 会引导用户选择
 *   - 用户可随时在设置页修改 [HomePageConfig]
 *
 * 反屎山：
 *   - 配置完全 data class，所有修改通过 copy()
 *   - 通过 [HomePageCard] 枚举限制可选项，禁止动态加载未授权卡片
 */
@Serializable
public data class HomePageConfig(
    /** 默认打开的功能页（应用启动后直接跳转） */
    public val defaultEntry: HomeEntry = HomeEntry.CHAT,

    /** 主页要显示的快速入口卡片（顺序即排列顺序） */
    public val quickCards: List<HomePageCard> = listOf(
        HomePageCard.CHAT,
        HomePageCard.IMAGE,
        HomePageCard.PERSONA,
        HomePageCard.BENCHMARK,
    ),

    /** 是否首次启动过 onboarding（false 表示需要走 [OnboardingFlow]） */
    public val onboardingCompleted: Boolean = false,

    /** onboarding 选择的偏好语言 */
    public val preferredLanguage: String = "zh-CN",

    /** onboarding 选择的"主要使用场景"（影响推荐后端） */
    public val primaryUseCase: String = "chat",

    /** 是否同意匿名性能基准上传（用于跨设备性能对比，未来功能） */
    public val allowAnonymousBenchmarkUpload: Boolean = false,
)

/** 默认入口 */
@Serializable
public enum class HomeEntry(public val displayName: String) {
    CHAT("聊天"),
    IMAGE("图像"),
    AUDIO("语音"),
    PERSONA("角色"),
    TRANSLATE("翻译"),
    AGENT("Agent"),
    BENCHMARK("性能测试"),
    SETTINGS("设置"),
}

/**
 * 主页快速入口卡片（用户可勾选显示哪些）。
 *
 * 聊天是内置功能，永远显示，不在本枚举里。
 */
@Serializable
public enum class HomePageCard(public val displayName: String, public val iconKey: String) {
    CHAT("聊天", "ic_chat"),
    IMAGE("图像", "ic_image"),
    AUDIO("语音", "ic_audio"),
    PERSONA("角色", "ic_persona"),
    TRANSLATE("翻译", "ic_translate"),
    AGENT("Agent", "ic_agent"),
    BENCHMARK("性能测试", "ic_benchmark"),
    AUTOTUNE("自动调优", "ic_autotune"),
    PLUGIN_MARKET("插件市场", "ic_plugin"),
    MODEL_MARKET("模型市场", "ic_model"),
    LOG_VIEWER("日志", "ic_log"),
}
