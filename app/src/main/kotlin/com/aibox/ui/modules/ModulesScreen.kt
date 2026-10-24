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
    val ctx = LocalContext.current

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
            // 顶部统计
            Text(
                "已安装 ${modules.size} 个模块" + if (scanning) "（扫描中…）" else "",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (modules.isEmpty() && !scanning) {
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
                    items(modules) { module ->
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
