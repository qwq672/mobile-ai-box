package com.aibox.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aibox.ui.components.SwitchRow
import com.aibox.ui.components.unified.UnifiedSection
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.DesignTokens
import com.aibox.ui.theme.Type

/**
 * 数据导入导出页。
 *
 * - 导出：逐项勾选（对话 / 云端模型 / 设置 / 图片），SAF 选择保存位置，写出 JSON。
 * - 导入：SAF 选择文件，自动识别本软件备份 / ChatGPT / DeepSeek 格式，一律追加不覆盖。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataManagementScreen(
    onBack: () -> Unit,
    vm: DataManagementViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) vm.exportTo(uri) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importFrom(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("数据导入与导出", style = Type.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = DesignTokens.spacing16,
                vertical = DesignTokens.spacing8,
            ),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing12),
        ) {
            item {
                Text(
                    text = "导出可逐项选择要保存的数据；导入一律追加为新会话，不会删除或覆盖现有数据。",
                    style = Type.bodySmall, color = Colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = DesignTokens.spacing4),
                )
            }

            // ===== 导出 =====
            item {
                UnifiedSection(title = "导出数据", showTopDivider = false) {
                    Column(Modifier.padding(horizontal = DesignTokens.spacing16)) {
                        SwitchRow(
                            title = "历史对话",
                            subtitle = "全部会话与消息（含版本历史、工具调用时间线）",
                            checked = ui.selection.conversations,
                            onCheckedChange = vm::setConversations,
                        )
                        SwitchRow(
                            title = "云端模型配置",
                            subtitle = "保存的云端模型（不含 API Key，导入后需重新填写）",
                            checked = ui.selection.cloudModels,
                            onCheckedChange = vm::setCloudModels,
                        )
                        SwitchRow(
                            title = "设置",
                            subtitle = "推理 / 外观 / 行为等设置（不含各类 API Key）",
                            checked = ui.selection.settings,
                            onCheckedChange = vm::setSettings,
                        )
                        SwitchRow(
                            title = "图片附件",
                            subtitle = "对话中导入的图片（最多 200 张；可能使文件较大）",
                            checked = ui.selection.images,
                            onCheckedChange = vm::setImages,
                        )
                        Spacer(Modifier.size(DesignTokens.spacing8))
                        Button(
                            onClick = { exportLauncher.launch("aibox-backup-${System.currentTimeMillis()}.json") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !ui.busy && hasAnySelection(ui.selection),
                        ) {
                            Text(if (ui.busy) "处理中…" else "选择位置并导出")
                        }
                    }
                }
            }

            // ===== 导入 =====
            item {
                UnifiedSection(title = "导入数据") {
                    Column(Modifier.padding(horizontal = DesignTokens.spacing16)) {
                        Text(
                            text = "选择文件后自动识别格式：AIBox 备份 / ChatGPT conversations.json / " +
                                "DeepSeek conversations.json / DeepSeek 新版全量 zip（chat_sessions 目录，流式导入）。" +
                                "导入一律追加，不覆盖现有数据。",
                            style = Type.bodySmall, color = Colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(DesignTokens.spacing8))
                        OutlinedButton(
                            onClick = {
                                importLauncher.launch(
                                    arrayOf("application/zip", "application/json", "text/plain", "*/*")
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !ui.busy,
                        ) {
                            Text(
                                when {
                                    ui.busy && ui.importProgress != null ->
                                        "导入中… ${ui.importProgress!!.done}/${ui.importProgress!!.total} 会话"
                                    ui.busy -> "导入中…"
                                    else -> "选择文件并导入"
                                }
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = "提示：备份文件含对话原文与设置，请妥善保管；图片以 base64 内嵌，体积可能较大。",
                    style = Type.labelSmall, color = Colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = DesignTokens.spacing4),
                )
            }
        }
    }

    ui.message?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissMessage,
            title = { Text("数据操作") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = vm::dismissMessage) { Text("知道了") } },
        )
    }

    ui.report?.let { report ->
        AlertDialog(
            onDismissRequest = vm::dismissReport,
            title = { Text("导入完成") },
            text = {
                Column {
                    Text("新增会话：${report.conversations}", style = Type.bodyMedium)
                    Text("新增消息：${report.messages}", style = Type.bodyMedium)
                    if (report.cloudModels > 0) Text("云端模型：${report.cloudModels}", style = Type.bodyMedium)
                    if (report.settings > 0) Text("设置项：${report.settings}", style = Type.bodyMedium)
                    if (report.images > 0) Text("图片：${report.images}", style = Type.bodyMedium)
                    if (report.warnings.isNotEmpty()) {
                        Spacer(Modifier.size(DesignTokens.spacing8))
                        Text("提示：", style = Type.labelLarge, fontWeight = FontWeight.SemiBold, color = Colors.error)
                        report.warnings.take(5).forEach { w ->
                            Text("· $w", style = Type.labelSmall, color = Colors.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissReport) { Text("完成") } },
        )
    }
}

private fun hasAnySelection(s: com.aibox.data.backup.ExportSelection): Boolean =
    s.conversations || s.cloudModels || s.settings || s.images
