package com.aibox.domain.inference

import com.aibox.infra.plugin.SettingField

/**
 * 后端描述符（v2）。
 *
 * 让宿主（AIBox App）**动态生成** 设置 UI，无需为每个后端（含插件）写硬编码的设置页面。
 *
 * 工作流：
 * 1. 宿主启动时遍历所有注册的 [InferenceBackendV2]，对每个后端取其 [BackendDescriptor]。
 * 2. 调 [getSettingsSchema] 拿到字段列表（[List]<[SettingField]>）。
 * 3. 用 [com.aibox.ui.components.unified.SettingsListRenderer] 动态生成设置 UI。
 * 4. 用户改完后，宿主把 `Map<String, Any>` 传回 [parseSettings]，得到 [InferenceConfigV2]，
 *    再传给 [InferenceBackendV2.loadModel]。
 *
 * 因此宿主**不感知**每个后端的具体配置项（如 llama.cpp 的 nGpuLayers、LiteRT 的 preferredDelegate），
 * 完全由后端 / 插件自己描述，保证插件可独立演进而不需要宿主改 UI 代码。
 *
 * 与 [InferenceBackendV2] 的关系：[InferenceBackendV2] 是"运行时契约"（加载 / 推理 / 卸载），
 * [BackendDescriptor] 是"元数据契约"（能力 + 设置 schema）。一个后端实现通常同时实现这两个接口，
 * 但本接口独立声明，便于"先注册描述符、后按需加载实现"的懒加载场景。
 */
interface BackendDescriptor {

    /** 后端唯一名称（机器可读），如 "litert"。与 [InferenceBackendV2.getName] 一致。 */
    fun getName(): String

    /** 用户可见的显示名，如 "LiteRT-LM"。与 [InferenceBackendV2.getDisplayName] 一致。 */
    fun getDisplayName(): String

    /** 版本号。与 [InferenceBackendV2.getVersion] 一致。 */
    fun getVersion(): String

    /** 能力声明。与 [InferenceBackendV2.describeCapabilities] 一致。 */
    fun describeCapabilities(): BackendCapabilities

    /**
     * 设置字段 schema：返回该后端暴露给用户的所有设置项。
     *
     * 宿主按返回的 [List]<[SettingField]> 用 [com.aibox.ui.components.unified.SettingsListRenderer] 渲染。
     * 每个字段的 [SettingField.key] 用作存储键；建议格式 `<backendName>.<settingName>`，
     * 如 `litert.preferredDelegate`、`llamaCpp.nGpuLayers`。
     */
    fun getSettingsSchema(): List<SettingField>

    /**
     * 默认设置值（与 [getSettingsSchema] 的 [SettingField.defaultValue] 一致）。
     * 宿主首次加载该后端时用作初始配置。
     */
    fun getDefaultSettings(): Map<String, Any>

    /**
     * 把用户设置的 `Map<String, Any>` 解析为 [InferenceConfigV2]。
     *
     * - 通用字段（threads / maxTokens / temperature 等）按 V2 通用字段填。
     * - 框架专属字段（如 nGpuLayers、preferredDelegate）放入 [InferenceConfigV2.frameworkExtras]。
     * - 后端在 [InferenceBackendV2.loadModel] 时从 [InferenceConfigV2.frameworkExtras] 取回。
     *
     * 这是把"用户可见的扁平设置" → "后端可执行的分层配置"的转换器，是宿主与后端解耦的关键。
     */
    fun parseSettings(values: Map<String, Any>): InferenceConfigV2
}
