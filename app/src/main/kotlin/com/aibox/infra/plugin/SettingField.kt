package com.aibox.infra.plugin

/**
 * 插件设置字段描述。
 *
 * 所有插件暴露给宿主 UI 渲染的设置都通过此 data class 描述；
 * 宿主用 [com.aibox.ui.components.unified.SettingFieldRenderer] 统一渲染，
 * 保证 App 内所有插件的设置页风格一致，不依赖插件各自实现 UI。
 *
 * 这是 P0.2（UI 统一基础）与 P1.1（插件接口抽象）的契约面。
 *
 * @property key 全局唯一字段键。建议格式 `<pluginId>.<settingName>`，
 *   如 `litert.preferredDelegate` / `mlc.temperature`。用作 [SettingsListRenderer] 的存储键。
 * @property type 控件类型，见 [FieldType]。
 * @property title 主标题，如 "优先 Delegate"。
 * @property description 副标题 / 说明文本，如 "NPU 加速 / GPU / CPU 备选"。可空。
 * @property defaultValue 默认值。SWITCH 为 Boolean、DROPDOWN/INPUT 为 String、SLIDER 为 Number。
 * @property options DROPDOWN 类型的可选项列表；其它类型忽略。
 * @property min SLIDER 类型的下界（Int / Float 均可）。
 * @property max SLIDER 类型的上界。
 * @property step SLIDER 类型的步长；为空则视为连续滑块。
 * @property group 分组标题。相同 group 的字段会在同一 [UnifiedSection] 下渲染。
 * @property enabled 是否可交互。false 时控件置灰但仍显示。
 * @property keyboardHint INPUT 类型的键盘类型 hint：
 *   `"number"` / `"decimal"` / `"password"` / `"email"` / `"phone"` / `"uri"` / 其它 = 默认 text。
 * @property visibleWhen 联动可见性谓词。输入"当前所有字段值" Map，返回本字段是否可见。
 *   默认恒为 true。可用于"开启 X 后才显示 Y"这类联动。
 */
data class SettingField(
    val key: String,
    val type: FieldType,
    val title: String,
    val description: String = "",
    val defaultValue: Any,
    val options: List<SettingOption>? = null,
    val min: Any? = null,
    val max: Any? = null,
    val step: Any? = null,
    val group: String? = null,
    val enabled: Boolean = true,
    val keyboardHint: String? = null,
    val visibleWhen: (Map<String, Any>) -> Boolean = { true }
)

/**
 * DROPDOWN 类型字段的可选项。
 *
 * @property value 存储值（如 "npu"）。
 * @property label 显示文本（如 "NPU 加速"）。
 */
data class SettingOption(
    val value: String,
    val label: String
)

/**
 * 设置字段控件类型。
 */
enum class FieldType {
    /** 布尔开关。 */
    SWITCH,
    /** 下拉单选。需配合 [SettingField.options]。 */
    DROPDOWN,
    /** 数值滑块。需配合 [SettingField.min] / [SettingField.max] / [SettingField.step]。 */
    SLIDER,
    /** 文本 / 数字输入。可配合 [SettingField.keyboardHint]。 */
    INPUT,
    /** 分组标题。该字段本身不渲染为控件，仅作为 [UnifiedSection] 标题。 */
    GROUP
}
