package com.aibox.ui.audio

import com.aibox.infra.plugin.SettingOption

/**
 * ASR / TTS 共享 UI 常量（D 系列）。
 *
 * 把"语言选项列表"集中在一处，便于后续扩展多 provider 时统一更新
 * （当前与 [com.aibox.domain.audio.SystemAsrProvider.supportedLanguages] /
 * [com.aibox.domain.audio.SystemTtsProvider.supportedLanguages] 手工对齐）。
 */
internal val LANGUAGE_OPTIONS: List<SettingOption> = listOf(
    SettingOption("zh-CN", "中文（简体）"),
    SettingOption("en-US", "English (US)"),
    SettingOption("en-GB", "English (UK)"),
    SettingOption("ja-JP", "日本語"),
    SettingOption("ko-KR", "한국어")
)
