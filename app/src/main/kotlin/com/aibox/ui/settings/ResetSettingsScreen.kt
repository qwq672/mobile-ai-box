package com.aibox.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

/**
 * 重置设置（独立页）。
 *
 * 设置项变多后，把“重置”从主设置页挪出来，避免误触，也让主页面更清晰。
 * 每个动作都先弹确认；模型文件、聊天记录、欢迎页状态不会被删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResetSettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel()
) {
    var pending by remember { mutableStateOf<ResetAction?>(null) }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("重置设置") }
            )
        },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) }
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "所有重置都会先确认。模型文件、聊天记录与欢迎页状态不会被删除。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column {
                    ResetActionRow("重置推理参数", "后端 / 线程 / 批大小 / KV / 上下文 / 采样 / 系统提示词") {
                        pending = ResetAction("重置推理参数？", "当前已加载的模型不会立即变化，重新加载后生效。") {
                            vm.resetInference()
                        }
                    }
                    HorizontalDivider()
                    ResetActionRow("重置外观", "动态取色 / 主题 / 主色 / 圆角 / 消息呈现") {
                        pending = ResetAction("重置外观？", "界面会立即恢复默认外观。") { vm.resetAppearance() }
                    }
                    HorizontalDivider()
                    ResetActionRow("重置行为与搜索", "下载源 / 思考档位 / 日志开关 / 搜索 API key") {
                        pending = ResetAction("重置行为与搜索设置？", "API key 也会被清空。") { vm.resetBehavior() }
                    }
                    HorizontalDivider()
                    ResetActionRow("重置云端模型", "清空当前 API key / Base URL / 模型名，并关闭云端") {
                        pending = ResetAction("重置云端模型配置？", "需要重新填写 API key。") { vm.resetCloud() }
                    }
                    HorizontalDivider()
                    ResetActionRow("重置工具设置", "工具开关恢复默认、自动确认与工具选项清空") {
                        pending = ResetAction("重置工具设置？", "基础工具会恢复默认开启，高级工具默认关闭。") {
                            vm.resetToolSettings()
                        }
                    }
                    HorizontalDivider()
                    ResetActionRow("重置推理优化设置", "TTFT / TG / 能耗开关恢复默认档") {
                        pending = ResetAction("重置推理优化设置？", "会恢复到「默认（均衡）」档。") {
                            vm.resetOptimizationSettings()
                        }
                    }
                    HorizontalDivider()
                    ResetActionRow(
                        "重置所有设置",
                        "以上全部；模型文件与聊天记录保留",
                        danger = true
                    ) {
                        pending = ResetAction("重置所有设置？", "该操作不可撤销。") { vm.resetAllSettings() }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    pending?.let { action ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(action.title) },
            text = { Text(action.message) },
            confirmButton = {
                TextButton(onClick = {
                    action.run()
                    pending = null
                    scope.launch { snackbar.showSnackbar("已重置：${action.title.removeSuffix("？")}") }
                }) {
                    Text("确认重置", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("取消") }
            }
        )
    }
}

private data class ResetAction(
    val title: String,
    val message: String,
    val run: () -> Unit
)

@Composable
private fun ResetActionRow(
    title: String,
    subtitle: String,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                title,
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Normal
            )
        },
        supportingContent = {
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (danger) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier.clickable { onClick() }
    )
}
