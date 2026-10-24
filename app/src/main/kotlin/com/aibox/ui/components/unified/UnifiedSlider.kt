package com.aibox.ui.components.unified

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.aibox.infra.plugin.SettingField
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.DesignTokens
import com.aibox.ui.theme.Type
import java.util.Locale

/**
 * 统一滑块组件。所有插件设置中的数值项（temperature、topP、max tokens 等）必须通过此组件渲染。
 *
 * 视觉规则：
 * - 行布局使用 M3 ListItem：左侧标题 + 右侧粗体数值（[Colors.primary]）
 * - 滑块位于行下方，水平边距与 ListItem 主体对齐（[DesignTokens.spacing16]）
 * - 步长 [SettingField.step] 自动换算为 Slider `steps`（离散点数）
 * - [SettingField.enabled] = false 时滑块禁用
 *
 * 数值显示规则：
 * - step >= 1 或为空 → 整数显示
 * - 否则按 step 推断小数位（最多 4 位）
 *
 * @param field 设置字段元数据。要求 [SettingField.min] / [SettingField.max] 非 null（Number 类型）。
 * @param value 当前数值（Float）。
 * @param onValueChange 用户拖动回调（已 coerceIn min..max）。
 * @param modifier 外部布局修饰符
 */
@Composable
fun UnifiedSlider(
    field: SettingField,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val min = (field.min as? Number)?.toFloat() ?: 0f
    val max = (field.max as? Number)?.toFloat() ?: 1f
    val step = (field.step as? Number)?.toFloat()
    val steps = computeSliderSteps(min, max, step)
    val clamped = value.coerceIn(min, max)
    val displayText = formatSliderValue(clamped, step)

    Column(modifier = modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = {
                Text(
                    text = field.title,
                    style = Type.titleSmall,
                    color = Colors.onSurface
                )
            },
            supportingContent = {
                if (field.description.isNotBlank()) {
                    Text(
                        text = field.description,
                        style = Type.bodySmall,
                        color = Colors.onSurfaceVariant
                    )
                }
            },
            trailingContent = {
                Text(
                    text = displayText,
                    style = Type.labelLarge,
                    color = Colors.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        )
        Slider(
            value = clamped,
            onValueChange = onValueChange,
            valueRange = min..max,
            steps = steps,
            enabled = field.enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DesignTokens.spacing16)
        )
    }
}

/**
 * 把 `step`（任意 Number）换算成 [Slider] 接收的 `steps` 参数（中间离散点数）。
 * - step 为空或非正或 >= (max - min) → 视为连续滑块，返回 0
 * - 否则按 `((max - min) / step) - 1` 计算
 */
private fun computeSliderSteps(min: Float, max: Float, step: Float?): Int {
    if (step == null || step <= 0f) return 0
    val range = max - min
    if (range <= 0f || step >= range) return 0
    val n = (range / step).toInt() - 1
    return n.coerceAtLeast(0)
}

/**
 * 根据 step 决定显示精度：
 * - step >= 1 或为 null → 整数
 * - 否则按 step 推断小数位（最多 4）
 */
private fun formatSliderValue(value: Float, step: Float?): String {
    if (step == null || step >= 1f) return value.toInt().toString()
    var decimals = 0
    var v = step
    while (v < 1f && decimals < 4) {
        v *= 10f
        decimals++
    }
    return "%.${decimals}f".format(Locale.US, value)
}
