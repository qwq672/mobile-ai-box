package com.aibox.ui.components.unified

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.aibox.infra.plugin.SettingField
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.DesignTokens
import com.aibox.ui.theme.Type

/**
 * 统一输入框组件。所有插件设置中的文本 / 数值输入项必须通过此组件渲染。
 *
 * 视觉规则：
 * - 上方：标题 [Type.titleSmall] + 副标题 [Type.bodySmall]（可空）
 * - 下方：[OutlinedTextField]，圆角 [DesignTokens.shapeMedium]
 * - 单行；键盘类型由 [SettingField.keyboardHint] 决定
 * - [SettingField.enabled] = false 时输入框置灰
 *
 * @param field 设置字段元数据
 * @param value 当前文本值
 * @param onValueChange 用户输入回调
 * @param modifier 外部布局修饰符
 */
@Composable
fun UnifiedInput(
    field: SettingField,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = DesignTokens.spacing16,
                vertical = DesignTokens.spacing8
            )
    ) {
        Text(
            text = field.title,
            style = Type.titleSmall,
            color = Colors.onSurface
        )
        if (field.description.isNotBlank()) {
            Text(
                text = field.description,
                style = Type.bodySmall,
                color = Colors.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(DesignTokens.spacing4))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = field.enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = parseKeyboardType(field.keyboardHint)
            ),
            shape = DesignTokens.shapeMedium,
            textStyle = Type.bodyLarge,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 把 [SettingField.keyboardHint] 字符串映射到 [KeyboardType]。
 * 未知 / null → [KeyboardType.Text]
 */
private fun parseKeyboardType(hint: String?): KeyboardType = when (hint?.lowercase()) {
    "number" -> KeyboardType.Number
    "decimal" -> KeyboardType.Decimal
    "password" -> KeyboardType.Password
    "phone" -> KeyboardType.Phone
    "email" -> KeyboardType.Email
    "uri" -> KeyboardType.Uri
    else -> KeyboardType.Text
}
