package com.aibox.ui.modules

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aibox.domain.module.DiscoveredModule
import com.aibox.domain.module.ModuleType
import com.aibox.ui.components.unified.UnifiedCard

/**
 * 模块管理页面：列出所有已安装的 AIBox 模块 APK（含宿主本身）。
 *
 * - 扫描 4 种 Intent Action：
 *   - com.aibox.action.BACKEND_PLUGIN（LiteRT-LM 等推理框架插件）
 *   - com.aibox.action.CHAT_MODULE（Easy Chat 等聊天模块）
 *   - com.aibox.action.TRANSLATE_MODULE（翻译模块）
 *   - com.aibox.action.IMAGE_GEN_MODULE（未来图像生成模块）
 * - 宿主自己作为"系统模块"显示在第一位
 * - 每个模块卡片显示：图标 / 类型 / 名称 / 版本 / 包名
 * - 顶部右侧刷新按钮：用户安装新模块后手动刷新
 * - 长按模块（非宿主）：跳转系统应用详情页（卸载/查看权限）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModulesScreen(
    onBack: () -> Unit,
    viewModel: ModulesViewModel = viewModel(factory = ModulesViewModelFactory(LocalContext.current))
) {
    val modules by viewModel.modules.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()
    val dependencyReport by viewModel.dependencyReport.collectAsState()
    val ctx = LocalContext.current
    // 模块管理增强（最小可用）：可只显示第三方插件、隐藏系统宿主模块。
    var pluginsOnly by remember { mutableStateOf(false) }
    val shownModules = if (pluginsOnly) modules.filter { !it.isHost } else modules

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("模块管理") },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, enabled = !scanning) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // 顶部统计 + 筛选
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "已安装 ${modules.size} 个模块" + if (scanning) "（扫描中…）" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = pluginsOnly,
                    onClick = { pluginsOnly = !pluginsOnly },
                    label = { Text("仅看插件") }
                )
            }

            // 后端插件诊断：只显示确实装了的包，回答「装了为什么还提示未安装」
            val installedDiagnostics = diagnostics.filter { it.installed }
            if (installedDiagnostics.isNotEmpty()) {
                DiagnosticsCard(
                    diagnostics = installedDiagnostics,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            dependencyReport?.let { report ->
                DependencyPlanCard(
                    report = report,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            if (shownModules.isEmpty() && !scanning) {
                // 空状态
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.Extension,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "尚未发现任何 AIBox 模块",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "请安装 AIBox 模块 APK（如 Easy Chat / Translate / LiteRT）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(shownModules) { module ->
                        ModuleCard(
                            module = module,
                            onClick = {
                                // 非宿主模块：长按跳转应用详情页（卸载/查看权限）
                                if (!module.isHost) {
                                    runCatching {
                                        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.parse("package:${module.packageName}")
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        ctx.startActivity(intent)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(
    diagnostics: List<com.aibox.infra.plugin.PluginDiagnostic>,
    modifier: Modifier = Modifier
) {
    UnifiedCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "后端插件诊断",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            diagnostics.forEach { d ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            d.packageName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            when {
                                d.active -> "已启用"
                                !d.discovered -> "未被发现"
                                !d.compatible -> "不兼容"
                                else -> "未绑定"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (d.active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        d.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (d.services.isNotEmpty()) {
                        Text(
                            "Service: ${d.services.first()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DependencyPlanCard(
    report: com.aibox.infra.module.loader.ModuleGraphService.Report,
    modifier: Modifier = Modifier
) {
    val plan = report.plan
    UnifiedCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "依赖计划",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            val withManifest = report.entries.filter { it.moduleId != null }
            if (withManifest.isEmpty()) {
                Text(
                    "已安装模块都没有提供 module_manifest/manifest.json，无法解析依赖。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                if (plan.groups.isEmpty()) {
                    Text(
                        "没有可加载的模块（全部被依赖/冲突排除）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                plan.groups.forEachIndexed { idx, group ->
                    Text(
                        "${idx + 1}. " + (if (group.cyclic) "🔁 " else "") +
                            group.moduleIds.joinToString(" → "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                plan.excluded.forEach { e ->
                    Text(
                        "已排除 ${e.moduleId}：${e.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                plan.optionalMissing.forEach { o ->
                    Text(
                        "可选前置 ${o.moduleId} → ${o.dependencyId}：${o.reason}" +
                            "（${o.promptAt}，降级 ${o.fallback}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                plan.warnings.take(4).forEach { w ->
                    Text(
                        "提示：$w",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val missingManifest = report.entries.filter { it.moduleId == null }
                if (missingManifest.isNotEmpty()) {
                    Text(
                        "无 manifest：" + missingManifest.joinToString(", ") { it.packageName },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ModuleCard(
    module: DiscoveredModule,
    onClick: () -> Unit = {}
) {
    UnifiedCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 图标（emoji）
            Text(
                module.type.icon,
                style = MaterialTheme.typography.headlineMedium
            )

            // 主信息
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        module.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (module.isHost) {
                        Spacer(Modifier.width(8.dp))
                        AssistChip(
                            onClick = {},
                            label = { Text("系统", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Text(
                    "v${module.version}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    module.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 右侧类型标签
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    module.type.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
