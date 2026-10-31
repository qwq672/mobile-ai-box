package com.aibox.ui.components.unified

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aibox.infra.plugin.SettingField
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.Type

/**
 * 统一开关组件。所有插件设置中的布尔项必须通过此组件渲染，
 * 保证颜色 / 间距 / 字号符合 AIBox 宿主设计。
 *
 * 视觉规则：
 * - 主标题用 [Type.titleSmall] + [Colors.onSurface]
 * - 副标题用 [Type.bodySmall] + [Colors.onSurfaceVariant]（空则不渲染，行高自适应）
 * - Switch 颜色由 MaterialTheme.colorScheme 自动适配（深 / 浅色都正确）
 * - [SettingField.enabled] = false 时控件置灰
 *
 * @param field 设置字段元数据（用 [SettingField.title] / [SettingField.description] / [SettingField.enabled]）
 * @param value 当前布尔值
 * @param onValueChange 用户切换回调
 * @param modifier 外部布局修饰符
 */
@Composable
fun UnifiedSwitch(
    field: SettingField,
    value: Boolean,
    onValueChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    ListItem(
        modifier = modifier.fillMaxWidth(),
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
            Switch(
                checked = value,
                onCheckedChange = onValueChange,
                enabled = field.enabled
            )
        }
    )
}
