package com.aibox.litert

import com.aibox.plugin.BackendCapabilitiesParcel
import com.aibox.plugin.IPluginDescriptor
import com.aibox.plugin.PluginInfo
import com.aibox.plugin.SettingFieldEntry

/**
 * [IPluginDescriptor] 的具体实现(Stub)。
 *
 * 暴露插件元信息给宿主:
 *  - [getPluginInfo] / [getName] / [getDisplayName] / [getVersion]:身份信息。
 *  - [describeCapabilities]:后端能力(用于宿主路由 + UI 标签)。
 *  - [getSettingsSchema]:设置项 schema,宿主动态生成 UI。
 *  - [getDefaultSettings]:默认设置值。
 *
 * 与 [LiteRTBinder] 的关系:[LiteRTBinder] 是"运行时"接口(loadModel / infer),
 * [LiteRTDescriptor] 是"元信息"接口(只读)。本工程让两者**分离实例**:
 *  - 宿主通过 `bindService` → `LiteRTBinder`(在 manifest 中暴露的 [LiteRTPluginService] onBind 返回)。
 *  - 宿主通过 `Class.forName("com.aibox.litert.LiteRTDescriptor")` → `newInstance()`
 *    → `asInterface()` 拿到 descriptor(由 manifest meta-data `aibox.descriptor_class` 暴露)。
 *
 * 分离的好处:descriptor 的调用不依赖 Service 已绑定(宿主可在扫描阶段先反射创建
 * descriptor 读 capabilities,再决定是否 bind);bad plugin 的 descriptor 异常不影响 Service。
 */
class LiteRTDescriptor : IPluginDescriptor.Stub() {

    private val logTag = "aibox.plugin.litert.descriptor"

    override fun getPluginInfo(): PluginInfo {
        val info = PluginInfo()
        info.packageName = "com.aibox.litert.backend"
        info.descriptorClass = "com.aibox.litert.LiteRTDescriptor"
        info.version = "1.0.0"
        info.displayName = "LiteRT-LM"
        info.sizeBytes = 5L * 1024 * 1024
        return info
    }

    override fun getName(): String = "litert"

    override fun getDisplayName(): String = "LiteRT-LM (Google)"

    override fun getVersion(): String = "1.0.0"

    /**
     * 能力声明。bitmask 由 [BitPositions] 单点计算,与 `assets/plugin.json` 对齐。
     */
    override fun describeCapabilities(): BackendCapabilitiesParcel {
        val parcel = BackendCapabilitiesParcel()
        parcel.supportedTasksBitmask = BitPositions.supportedTasksBitmask
        parcel.supportedInputModalitiesBitmask = BitPositions.supportedInputModalitiesBitmask
        parcel.supportedOutputModalitiesBitmask = BitPositions.supportedOutputModalitiesBitmask
        parcel.maxContextLength = 8192
        parcel.maxImageResolution = 2048
        parcel.supportedModelFormats = listOf("tflite")
        parcel.supportedQuantizations = listOf("int8", "fp16", "bf16")
        parcel.featuresBitmask = BitPositions.featuresBitmask
        return parcel
    }

    /**
     * 设置 schema:驱动宿主 UI 动态生成设置面板,字段引用 [LiteRTBackend] 在 loadModel
     * 时从 configJson 解析的相同 key(命名约定 `litert.*`,避免与其它插件冲突)。
     *
     * type 取值对应宿主 SettingField.FieldType 序数([BitPositions.SETTING_TYPE_*]):
     *  - 0=SWITCH / 1=DROPDOWN / 2=SLIDER / 3=INPUT / 4=GROUP
     *
     * 字段集合(5 项):
     *  1. 优先 Delegate(下拉:auto / npu / gpu / cpu)。
     *  2. GPU FP16 推理(开关):GPU Delegate 上用 FP16 加速。
     *  3. 性能模式(下拉:performance / balanced / luxury)。
     *  4. 启用视觉编码器(开关):多模态总开关。
     *  5. 视觉编码分辨率(滑块:512-2048,步长 64)。
     */
    override fun getSettingsSchema(): MutableList<SettingFieldEntry> {
        val entries = mutableListOf<SettingFieldEntry>()

        // 1. 优先 Delegate
        entries += SettingFieldEntry().apply {
            key = "litert.preferredDelegate"
            type = BitPositions.SETTING_TYPE_DROPDOWN
            title = "优先 Delegate"
            description = "NPU 加速 / GPU / CPU 备选"
            defaultValue = "auto"
            optionValues = listOf("auto", "npu", "gpu", "cpu")
            optionLabels = listOf("自动(推荐)", "Hexagon NPU", "GPU Delegate", "CPU")
        }

        // 2. GPU FP16 推理
        entries += SettingFieldEntry().apply {
            key = "litert.gpuDelegateFP16"
            type = BitPositions.SETTING_TYPE_SWITCH
            title = "GPU FP16 推理"
            description = "GPU Delegate 上使用 FP16 加速"
            defaultValue = "true"
        }

        // 3. 性能模式
        entries += SettingFieldEntry().apply {
            key = "litert.performanceMode"
            type = BitPositions.SETTING_TYPE_DROPDOWN
            title = "性能模式"
            description = "影响 NPU 频率和能耗"
            defaultValue = "balanced"
            optionValues = listOf("performance", "balanced", "luxury")
            optionLabels = listOf("最高性能", "均衡(推荐)", "省电")
        }

        // 4. 启用视觉编码器
        entries += SettingFieldEntry().apply {
            key = "litert.enableVisionEncoder"
            type = BitPositions.SETTING_TYPE_SWITCH
            title = "启用视觉编码器"
            description = "支持图像理解(Gemma + Siglip)"
            defaultValue = "true"
        }

        // 5. 视觉编码分辨率
        entries += SettingFieldEntry().apply {
            key = "litert.visionResolution"
            type = BitPositions.SETTING_TYPE_SLIDER
            title = "视觉编码分辨率"
            description = "图像输入到 Siglip 的分辨率"
            defaultValue = "1024"
            minValue = 512f
            maxValue = 2048f
            step = 64f
        }

        return entries
    }

    /**
     * 默认设置值;与 [getSettingsSchema] 的 defaultValue 字段对齐(双路暴露,
     * 宿主首次安装时优先用本 Map;后续用户改动从 settings repo 读)。
     */
    override fun getDefaultSettings(): MutableMap<String, String> = mutableMapOf(
        "litert.preferredDelegate" to "auto",
        "litert.gpuDelegateFP16" to "true",
        "litert.performanceMode" to "balanced",
        "litert.enableVisionEncoder" to "true",
        "litert.visionResolution" to "1024"
    )
}
