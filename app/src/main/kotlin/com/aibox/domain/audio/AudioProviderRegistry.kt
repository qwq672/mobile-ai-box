package com.aibox.domain.audio

/**
 * ASR / TTS provider 统一注册中心（D 系列）。
 *
 * 设计原则：
 * - **懒注册、按需取**：宿主启动时把内置 [SystemAsrProvider] / [SystemTtsProvider] 注册进来；
 *   后续可注册插件 provider（如离线神经网络 ASR / 在线 TTS）。
 * - **默认 fallback 是 "system"**：[getDefaultAsrProvider] / [getDefaultTtsProvider] 永远返回 id 为 `"system"` 的 provider，
 *   UI 层无需关心插件状态——若没装插件就用系统，若装了插件则可显式指定 provider id。
 * - **零依赖**：纯 Kotlin 类，无 Android / 协程依赖；线程安全由调用方保证（集中注册）。
 *
 * 注册时机：
 * - [com.aibox.di.AppContainer] 构造时调 [registerAsr] / [registerTts] 注册内置 provider；
 * - 插件 manager 启用时若插件提供 ASR / TTS，由 PluginManager 调本类注册。
 */
class AudioProviderRegistry {
    private val asrProviders = mutableMapOf<String, AsrProvider>()
    private val ttsProviders = mutableMapOf<String, TtsProvider>()

    /** 注册 / 覆盖一个 ASR provider（按 [AsrProvider.id] 去重）。 */
    fun registerAsr(provider: AsrProvider) {
        asrProviders[provider.id] = provider
    }

    /** 注册 / 覆盖一个 TTS provider（按 [TtsProvider.id] 去重）。 */
    fun registerTts(provider: TtsProvider) {
        ttsProviders[provider.id] = provider
    }

    /** 列出所有已注册的 ASR provider（UI 用于显示 provider 选择列表）。 */
    fun listAsrProviders(): List<AsrProvider> = asrProviders.values.toList()

    /** 列出所有已注册的 TTS provider。 */
    fun listTtsProviders(): List<TtsProvider> = ttsProviders.values.toList()

    /** 取默认（系统）ASR provider；不存在返回 null。 */
    fun getDefaultAsrProvider(): AsrProvider? = asrProviders["system"]

    /** 取默认（系统）TTS provider；不存在返回 null。 */
    fun getDefaultTtsProvider(): TtsProvider? = ttsProviders["system"]

    /** 按 id 取 ASR provider；不存在返回 null。 */
    fun getAsrProvider(id: String): AsrProvider? = asrProviders[id]

    /** 按 id 取 TTS provider；不存在返回 null。 */
    fun getTtsProvider(id: String): TtsProvider? = ttsProviders[id]
}
