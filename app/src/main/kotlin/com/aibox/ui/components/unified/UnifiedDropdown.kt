package com.aibox.ui.components.unified

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.aibox.infra.plugin.SettingField
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.DesignTokens
import com.aibox.ui.theme.Type

/**
 * 统一下拉单选组件。所有插件设置中的枚举 / 单选项必须通过此组件渲染。
 *
 * 视觉规则：
 * - 行布局与 [UnifiedSwitch] 对齐（M3 ListItem），点按整行展开菜单
 * - 当前选中值用 [Colors.primary] 高亮显示在副标题位
 * - 右侧 [Icons.Outlined.ArrowDropDown] 提示可下拉
 * - 菜单项前打勾指示当前选中项
 * - [SettingField.enabled] = false 时禁用点按（控件不变灰以保持可读）
 *
 * @param field 设置字段元数据。要求 [SettingField.options] 非空，否则只显示当前值文本。
 * @param value 当前选中的 [SettingOption.value]
 * @param onValueChange 用户选中回调，回传新选中的 value
 * @param modifier 外部布局修饰符
 */
@Composable
fun UnifiedDropdown(
    field: SettingField,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = field.options?.firstOrNull { it.value == value }?.label ?: value

    Box(modifier = modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = field.enabled) { expanded = true },
            headlineContent = {
                Text(
                    text = field.title,
                    style = Type.titleSmall,
                    color = Colors.onSurface
                )
            },
            supportingContent = {
                if (field.description.isNotBlank() || currentLabel.isNotBlank()) {
                    Column {
                        if (field.description.isNotBlank()) {
                            Text(
                                text = field.description,
                                style = Type.bodySmall,
                                color = Colors.onSurfaceVariant
                            )
                        }
                        Text(
                            text = currentLabel,
                            style = Type.bodyMedium,
                            color = Colors.primary
                        )
                    }
                }
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.Outlined.ArrowDropDown,
                    contentDescription = null,
                    tint = Colors.onSurfaceVariant
                )
            }
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            field.options?.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.label,
                            style = Type.bodyMedium
                        )
                    },
                    onClick = {
                        onValueChange(option.value)
                        expanded = false
                    },
                    leadingIcon = {
                        if (option.value == value) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = Colors.primary
                            )
                        }
                    }
                )
            }
            // options 为空时给个占位提示，避免菜单渲染为空
            if (field.options.isNullOrEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "(无可用选项)",
                            style = Type.bodyMedium,
                            color = Colors.onSurfaceVariant
                        )
                    },
                    onClick = { expanded = false }
                )
            }
        }
    }
}
