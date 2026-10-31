package com.aibox.ui.tool

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aibox.AIBoxApp
import com.aibox.domain.tool.Tool
import com.aibox.domain.tool.ToolCategory
import com.aibox.infra.plugin.FieldType
import com.aibox.infra.plugin.SettingField
import com.aibox.ui.components.unified.UnifiedCard
import com.aibox.ui.components.unified.UnifiedCardTone
import com.aibox.ui.components.unified.UnifiedSection
import com.aibox.ui.components.unified.UnifiedSwitch
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.DesignTokens
import com.aibox.ui.theme.Type

/**
 * 工具调用设置页（B7 工具设置面板）。
 *
 * 布局：
 * 1. TopAppBar：返回 + 标题 "工具调用设置"
 * 2. Section "基础工具（自动执行）"：列出所有 BASIC 类工具，每行 [ToolToggleRow]
 * 3. Section "高级工具（需确认）"：列出所有 ADVANCED 类工具，每行 [ToolToggleRow] +
 *    若启用，下方追加 [AutoConfirmRow]（"自动确认"二级开关）
 * 4. Section "工具详情"：选中工具时展示其描述 / 参数 / 版本 / 作者
 * 5. Section "说明"：[UnifiedCard] 提示基础 / 高级工具的差异 + 风险提示
 *
 * 数据源：
 * - [ToolSettingsViewModel.tools]：[ToolRegistry.listAllTools] 快照
 * - [ToolSettingsViewModel.enabledToolIds]：DataStore 流式
 * - [ToolSettingsViewModel.autoConfirmIds]：DataStore 流式
 *
 * 入口：[com.aibox.ui.nav.Dest.ToolSettings] 路由 → 本页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolSettingsScreen(
    onBack: () -> Unit,
    vm: ToolSettingsViewModel = viewModel(
        factory = ToolSettingsViewModelFactory(
            toolRegistry = AIBoxApp.instance.container.toolRegistry,
            toolSettingsRepository = AIBoxApp.instance.container.toolSettingsRepository
        )
    )
) {
    val tools by vm.tools.collectAsStateWithLifecycle()
    val enabledIds by vm.enabledToolIds.collectAsStateWithLifecycle()
    val autoConfirmIds by vm.autoConfirmIds.collectAsStateWithLifecycle()

    val basicTools = tools.filter { it.category == ToolCategory.BASIC }
    val advancedTools = tools.filter { it.category == ToolCategory.ADVANCED }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("工具调用设置", style = Type.titleLarge) }
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(vertical = DesignTokens.spacing16),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
        ) {
            // ============== 基础工具 ==============
            item {
                UnifiedSection(title = "基础工具（自动执行）", showTopDivider = false) {
                    if (basicTools.isEmpty()) {
                        EmptyToolHint("尚未注册基础工具")
                    } else {
                        basicTools.forEach { tool ->
                            ToolToggleRow(
                                tool = tool,
                                enabled = tool.id in enabledIds,
                                onToggle = { vm.toggleTool(tool.id, it) }
                            )
                        }
                    }
                }
            }

            // ============== 高级工具 ==============
            item {
                UnifiedSection(title = "高级工具（需确认）") {
                    if (advancedTools.isEmpty()) {
                        EmptyToolHint("尚未注册高级工具")
                    } else {
                        advancedTools.forEach { tool ->
                            ToolToggleRowWithAutoConfirm(
                                tool = tool,
                                enabled = tool.id in enabledIds,
                                autoConfirm = tool.id in autoConfirmIds,
                                onToggleEnabled = { vm.toggleTool(tool.id, it) },
                                onToggleAutoConfirm = { vm.toggleAutoConfirm(tool.id, it) }
                            )
                        }
                    }
                }
            }

            // ============== 工具详情（参数 / 版本 / 作者） ==============
            item {
                UnifiedSection(title = "工具详情") {
                    UnifiedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = DesignTokens.spacing16),
                        tone = UnifiedCardTone.SURFACE
                    ) {
                        if (tools.isEmpty()) {
                            Text(
                                text = "（无已注册工具）",
                                style = Type.bodySmall,
                                color = Colors.onSurfaceVariant
                            )
                        } else {
                            tools.forEach { tool ->
                                ToolDetailCard(tool)
                                Spacer(Modifier.height(DesignTokens.spacing8))
                            }
                        }
                    }
                }
            }

            // ============== 说明 ==============
            item {
                UnifiedSection(title = "说明") {
                    UnifiedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = DesignTokens.spacing16),
                        tone = UnifiedCardTone.SURFACE_LOW
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing4)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    imageVector = Icons.Outlined.Build,
                                    contentDescription = null,
                                    tint = Colors.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(DesignTokens.spacing8))
                                Text(
                                    text = "基础工具:LLM 调用时自动执行,无用户确认。",
                                    style = Type.bodySmall,
                                    color = Colors.onSurface
                                )
                            }
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    imageVector = Icons.Outlined.VerifiedUser,
                                    contentDescription = null,
                                    tint = Colors.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(DesignTokens.spacing8))
                                Text(
                                    text = "高级工具:每次执行前需用户确认,除非开启'自动确认'。",
                                    style = Type.bodySmall,
                                    color = Colors.onSurface
                                )
                            }
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    imageVector = Icons.Outlined.Warning,
                                    contentDescription = null,
                                    tint = Colors.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(DesignTokens.spacing8))
                                Text(
                                    text = "⚠️ 高级工具可能影响设备状态或数据,谨慎启用。",
                                    style = Type.bodySmall,
                                    color = Colors.error
                                )
                            }
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = null,
                                    tint = Colors.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(DesignTokens.spacing8))
                                Text(
                                    text = "禁用的工具不会出现在 LLM function calling schema 中,LLM 无法调用。",
                                    style = Type.bodySmall,
                                    color = Colors.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // 底部留白
            item { Spacer(Modifier.height(DesignTokens.spacing16)) }
        }
    }
}

/* ==================== 工具开关行 ==================== */

/**
 * 单个工具的启用开关行（B7）。
 *
 * 视觉规则：
 * - 左侧：图标 emoji + 显示名 + 描述（max 2 行）
 * - 右侧：[UnifiedSwitch]（启用开关）
 * - 用 [SettingField] 元数据驱动 [UnifiedSwitch]，保证与其它设置页风格一致
 */
@Composable
private fun ToolToggleRow(
    tool: Tool,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    UnifiedSwitch(
        field = SettingField(
            key = tool.id,
            type = FieldType.SWITCH,
            title = "${tool.icon} ${tool.displayName}",
            description = tool.description,
            defaultValue = enabled
        ),
        value = enabled,
        onValueChange = onToggle
    )
}

/**
 * 高级工具开关行 + 自动确认二级开关（B7）。
 *
 * 视觉规则：
 * - 顶部：[ToolToggleRow]（启用开关）
 * - 若启用：下方追加缩进的"自动确认"行（[AutoConfirmRow]）
 *   - 自动确认开启时：此工具执行前不再弹 dialog（用户已信任此工具）
 *   - 自动确认关闭时：每次执行前都弹 [ToolConfirmDialog]
 */
@Composable
private fun ToolToggleRowWithAutoConfirm(
    tool: Tool,
    enabled: Boolean,
    autoConfirm: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleAutoConfirm: (Boolean) -> Unit
) {
    Column {
        ToolToggleRow(tool = tool, enabled = enabled, onToggle = onToggleEnabled)
        if (enabled) {
            AutoConfirmRow(
                toolId = tool.id,
                autoConfirm = autoConfirm,
                onToggle = onToggleAutoConfirm
            )
        }
    }
}

/**
 * "自动确认"二级开关行（B7）。
 *
 * 视觉规则：
 * - 缩进 [DesignTokens.spacing16]，与外层 [ToolToggleRow] 形成视觉层次
 * - 标题前缀加 "└ " 表明二级关系
 * - 副标题提示用户：开启后不再弹确认 dialog
 */
@Composable
private fun AutoConfirmRow(
    toolId: String,
    autoConfirm: Boolean,
    onToggle: (Boolean) -> Unit
) {
    UnifiedSwitch(
        field = SettingField(
            key = "autoconfirm_$toolId",
            type = FieldType.SWITCH,
            title = "└ 自动确认（不再弹窗）",
            description = "开启后此工具执行前不再要求用户确认,适合你已信任的低风险工具",
            defaultValue = autoConfirm
        ),
        value = autoConfirm,
        onValueChange = onToggle
    )
}

/* ==================== 工具详情卡片 ==================== */

/**
 * 单个工具的详情卡（B7）。
 *
 * 视觉规则：
 * - 顶部一行：图标 + 显示名 + 分类标签（BASIC / ADVANCED）
 * - 中部：描述
 * - 底部：参数列表（required + optional） + 版本 + 作者
 */
@Composable
private fun ToolDetailCard(tool: Tool) {
    Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing4)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
        ) {
            Text(
                text = tool.icon,
                style = Type.titleMedium
            )
            Text(
                text = tool.displayName,
                style = Type.titleSmall,
                color = Colors.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            CategoryTag(category = tool.category)
        }
        Text(
            text = tool.description,
            style = Type.bodySmall,
            color = Colors.onSurfaceVariant
        )
        Text(
            text = "ID: ${tool.id}  ·  版本: ${tool.version}  ·  作者: ${tool.author}",
            style = Type.labelSmall,
            color = Colors.onSurfaceVariant
        )
        val schema = tool.getParameterSchema()
        val requiredNames = schema.required.joinToString { it.name }
        val optionalNames = schema.optional.joinToString { it.name }
        if (requiredNames.isNotEmpty()) {
            Text(
                text = "必填参数: $requiredNames",
                style = Type.labelSmall,
                color = Colors.onSurface
            )
        }
        if (optionalNames.isNotEmpty()) {
            Text(
                text = "可选参数: $optionalNames",
                style = Type.labelSmall,
                color = Colors.onSurfaceVariant
            )
        }
    }
}

/**
 * 分类标签（B7）：紧凑展示 BASIC / ADVANCED。
 */
@Composable
private fun CategoryTag(category: ToolCategory) {
    val (text, color) = when (category) {
        ToolCategory.BASIC -> "基础" to Colors.primary
        ToolCategory.ADVANCED -> "高级" to Colors.error
    }
    androidx.compose.material3.Surface(
        color = color.copy(alpha = 0.12f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(DesignTokens.radiusSmall)
    ) {
        Text(
            text = text,
            style = Type.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(
                horizontal = DesignTokens.spacing8,
                vertical = DesignTokens.spacing4
            )
        )
    }
}

/** 空工具提示：当某分类无已注册工具时展示，避免空白区。 */
@Composable
private fun EmptyToolHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DesignTokens.spacing16, vertical = DesignTokens.spacing12),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = Type.bodySmall,
            color = Colors.onSurfaceVariant
        )
    }
}
