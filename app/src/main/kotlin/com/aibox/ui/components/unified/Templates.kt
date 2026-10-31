package com.aibox.ui.components.unified

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.DesignTokens
import com.aibox.ui.theme.Type

/**
 * 标准设置页模板。
 *
 * 视觉规则：
 * - 顶部页面标题：[Type.headingMedium] + [Colors.onBackground]
 * - 标题与内容之间用 [DesignTokens.spacing8] 留白
 * - 多个 [sections] 之间用 [DesignTokens.spacing8] 等距排列
 * - 整体不留水平边距（section / 字段内部各自带 [DesignTokens.spacing16] 缩进）
 *
 * 用法：
 * ```
 * SettingsTemplate(title = "LiteRT-LM 设置", sections = listOf(
 *     { UnifiedSection("通用优化") { SettingsListRenderer(...) } },
 *     { UnifiedSection("推理参数") { SettingsListRenderer(...) } }
 * ))
 * ```
 *
 * @param title 页面标题（也可由外层 TopAppBar 承载，留空则不渲染大标题）
 * @param sections 各 section 内容（通常每个是 [UnifiedSection] + [SettingsListRenderer] 组合）
 * @param modifier 外部布局修饰符
 */
@Composable
fun SettingsTemplate(
    title: String,
    sections: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(vertical = DesignTokens.spacing16),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
    ) {
        if (title.isNotBlank()) {
            Text(
                text = title,
                style = Type.headingMedium,
                color = Colors.onBackground,
                modifier = Modifier.padding(
                    horizontal = DesignTokens.spacing16,
                    vertical = DesignTokens.spacing8
                )
            )
        }
        sections.forEach { it() }
    }
}

/**
 * 标准列表模板，用于模块管理 / 模型列表等场景。
 *
 * 视觉规则：
 * - 顶部页面标题：[Type.headingMedium] + [Colors.onBackground]
 * - [items] 之间用 [DesignTokens.spacing8] 等距排列（适合行高一致的场景）
 *
 * @param title 页面标题
 * @param items 列表项内容（如 List<UnifiedCard> / List<ListItem>）
 * @param modifier 外部布局修饰符
 */
@Composable
fun ListTemplate(
    title: String,
    items: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(vertical = DesignTokens.spacing16),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
    ) {
        if (title.isNotBlank()) {
            Text(
                text = title,
                style = Type.headingMedium,
                color = Colors.onBackground,
                modifier = Modifier.padding(
                    horizontal = DesignTokens.spacing16,
                    vertical = DesignTokens.spacing8
                )
            )
        }
        items.forEach { it() }
    }
}

/**
 * 卡片列表模板，用于模型卡 / 模块卡等场景。
 *
 * 视觉规则：
 * - 顶部页面标题：[Type.headingMedium] + [Colors.onBackground]
 * - [cards] 之间用 [DesignTokens.spacing12] 留白（比 [ListTemplate] 稍大，配合卡片阴影）
 *
 * @param title 页面标题
 * @param cards 卡片内容（通常每个是 [UnifiedCard] 调用）
 * @param modifier 外部布局修饰符
 */
@Composable
fun CardListTemplate(
    title: String,
    cards: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(vertical = DesignTokens.spacing16),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing12)
    ) {
        if (title.isNotBlank()) {
            Text(
                text = title,
                style = Type.headingMedium,
                color = Colors.onBackground,
                modifier = Modifier.padding(
                    horizontal = DesignTokens.spacing16,
                    vertical = DesignTokens.spacing8
                )
            )
        }
        cards.forEach { it() }
    }
}
