package com.aibox.ui.components.unified

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.DesignTokens
import com.aibox.ui.theme.Type

/**
 * 统一分组标题。把一组相关字段（如 "通用优化" / "LiteRT-LM 专属"）打包显示。
 *
 * 视觉规则：
 * - 标题用 [Type.titleSmall] + [Colors.primary]，作为视觉锚点
 * - 上方有 [DesignTokens.spacing8] 间距，与上一个 section 分隔
 * - 下方 [DesignTokens.spacing4] 紧接字段内容
 * - 顶部一条 [Colors.outlineVariant] 细分隔线，强化分组感（首个 section 可通过 [showTopDivider] 关闭）
 *
 * @param title 分组标题文本
 * @param modifier 外部布局修饰符
 * @param showTopDivider 是否显示顶部分隔线
 * @param content 分组下的字段内容
 */
@Composable
fun UnifiedSection(
    title: String,
    modifier: Modifier = Modifier,
    showTopDivider: Boolean = true,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (showTopDivider) {
            HorizontalDivider(
                color = Colors.outlineVariant,
                thickness = DesignTokens.spacing4 / 4  // 1dp
            )
        }
        Text(
            text = title,
            style = Type.titleSmall,
            color = Colors.primary,
            modifier = Modifier.padding(
                horizontal = DesignTokens.spacing16,
                vertical = DesignTokens.spacing12
            )
        )
        content()
    }
}
