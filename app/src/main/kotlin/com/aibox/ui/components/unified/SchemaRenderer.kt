package com.aibox.ui.components.unified

import androidx.compose.runtime.Composable
import com.aibox.infra.plugin.FieldType
import com.aibox.infra.plugin.SettingField
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier

/**
 * Schema 渲染引擎：把插件描述的 [SettingField] 列表渲染为统一风格的 UI。
 *
 * 这是 P0.2 的"门面"：插件只需要声明自己的设置 schema（一组 [SettingField]），
 * 宿主 UI 自动套用 [UnifiedSwitch] / [UnifiedDropdown] / [UnifiedSlider] / [UnifiedInput]，
 * 不允许插件自行画控件，从而保证 App 视觉风格统一。
 *
 * 设计：
 * - [SettingFieldRenderer] 渲染单个字段，按 [FieldType] 派发到对应 UnifiedXxx 组件
 * - [SettingsListRenderer] 渲染整组字段，自动按 [SettingField.group] / `FieldType.GROUP` 切分 [UnifiedSection]
 */

/**
 * 渲染单个 [SettingField]。按 [FieldType] 派发到对应 Unified 组件。
 *
 * - 若 `currentValue` 为 null 或类型不符，自动回退到 [SettingField.defaultValue]。
 * - `FieldType.GROUP` 仅渲染 [UnifiedSection] 标题（不渲染为控件）；通常请改用 [SettingsListRenderer]。
 *
 * @param field 字段元数据
 * @param currentValue 当前值（Boolean / String / Number）
 * @param onValueChange 用户修改回调，回传新值（保持原类型）
 */
@Composable
fun SettingFieldRenderer(
    field: SettingField,
    currentValue: Any?,
    onValueChange: (Any) -> Unit
) {
    when (field.type) {
        FieldType.SWITCH -> UnifiedSwitch(
            field = field,
            value = currentValue as? Boolean ?: (field.defaultValue as? Boolean ?: false),
            onValueChange = { onValueChange(it) }
        )

        FieldType.DROPDOWN -> UnifiedDropdown(
            field = field,
            value = currentValue as? String ?: (field.defaultValue as? String ?: ""),
            onValueChange = { onValueChange(it) }
        )

        FieldType.SLIDER -> UnifiedSlider(
            field = field,
            value = (currentValue as? Number)?.toFloat()
                ?: (field.defaultValue as? Number)?.toFloat()
                ?: 0f,
            onValueChange = { onValueChange(it) }
        )

        FieldType.INPUT -> UnifiedInput(
            field = field,
            value = currentValue as? String ?: (field.defaultValue as? String ?: ""),
            onValueChange = { onValueChange(it) }
        )

        FieldType.GROUP -> UnifiedSection(title = field.title) {
            // 单字段渲染场景下，GROUP 仅作标题占位，无内容
        }
    }
}

/**
 * 渲染整组字段，自动按 [SettingField.group] / `FieldType.GROUP` 切分 [UnifiedSection]。
 *
 * 分组规则：
 * - 遇到 `FieldType.GROUP` 字段开启新 section，标题取该字段的 [SettingField.title]
 * - 其它字段归入最近一个 group；若尚未有任何 GROUP 字段则直接平铺
 * - 每个字段都通过 [SettingField.visibleWhen] 联动过滤
 * - 调用方负责把 `currentValues` 维护为"全部字段当前值"的 Map，供联动判断
 *
 * @param fields 字段列表（顺序即渲染顺序）
 * @param currentValues 当前所有字段值，key = [SettingField.key]
 * @param onValueChange 任一字段值变更回调，回传 `(key, newValue)`
 */
@Composable
fun SettingsListRenderer(
    fields: List<SettingField>,
    currentValues: Map<String, Any>,
    onValueChange: (String, Any) -> Unit
) {
    // 把字段切分为若干"段"：每段以一个 GROUP 字段开头（若有），后续是非 GROUP 字段。
    val segments = buildSegments(fields)

    Column(Modifier.fillMaxWidth()) {
        segments.forEachIndexed { index, segment ->
            val sectionHeader = segment.header
            val bodyItems = segment.items
            val renderBody: @Composable () -> Unit = {
                bodyItems.forEach { field ->
                    if (field.visibleWhen(currentValues)) {
                        SettingFieldRenderer(
                            field = field,
                            currentValue = currentValues[field.key]
                        ) { v -> onValueChange(field.key, v) }
                    }
                }
            }
            if (sectionHeader != null) {
                UnifiedSection(
                    title = sectionHeader.title,
                    showTopDivider = index > 0
                ) { renderBody() }
            } else {
                renderBody()
            }
        }
    }
}

/**
 * 一段字段：可能带 GROUP 标题，包含若干普通字段。
 */
private class Segment(
    val header: SettingField?,
    val items: List<SettingField>
)

/**
 * 把扁平的 [fields] 列表按 `FieldType.GROUP` 切分为多段。
 * - 列表开头若没有 GROUP，第一段 header=null（平铺渲染）
 * - 连续多个 GROUP 字段会形成多个段（中间空段被丢弃）
 */
private fun buildSegments(fields: List<SettingField>): List<Segment> {
    val result = mutableListOf<Segment>()
    var currentHeader: SettingField? = null
    var currentItems = mutableListOf<SettingField>()

    fun flush() {
        if (currentHeader != null || currentItems.isNotEmpty()) {
            result.add(Segment(currentHeader, currentItems))
        }
        currentItems = mutableListOf()
    }

    for (field in fields) {
        if (field.type == FieldType.GROUP) {
            flush()
            currentHeader = field
        } else {
            currentItems.add(field)
        }
    }
    flush()
    return result
}
