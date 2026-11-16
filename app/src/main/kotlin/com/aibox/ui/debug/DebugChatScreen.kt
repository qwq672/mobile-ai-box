package com.aibox.ui.debug

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.DesignTokens
import com.aibox.ui.theme.Type

/**
 * 调试页：纯净聊天。
 *
 * 这个页面刻意绕开正式聊天页的所有提示词工程：
 * - 不注入 system prompt、UI 能力说明、choices 协议、工具清单、角色设定；
 * - 消息只在内存里，不落库；
 * - 可以在「多轮 / 单轮」「思考开 / 关」「chat 模板 / 原始补全」之间切换。
 *
 * 用法：先用同一句话在正式聊天里问一次，再在这里问一次，对比复读/幻觉是否出现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugChatScreen(
    vm: DebugChatViewModel = viewModel(),
    onBack: () -> Unit
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbarHost = remember { SnackbarHostState() }
    var input by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(ui.error) {
        ui.error?.let {
            snackbarHost.showSnackbar(it)
            vm.dismissError()
        }
    }

    // 新消息 / 流式增长时贴底
    val streamItem = if (ui.streaming) 1 else 0
    val totalItems = ui.messages.size + streamItem + 1
    LaunchedEffect(totalItems, ui.current.length) {
        if (ui.messages.isNotEmpty() && !listState.isScrollInProgress) {
            listState.scrollToItem(totalItems - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("调试 · 纯净聊天", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = vm::clear, enabled = !ui.streaming) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = "清空")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .imePadding()
        ) {
            InfoCard(ui = ui)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = DesignTokens.spacing16, vertical = DesignTokens.spacing8),
                horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
            ) {
                FilterChip(
                    selected = ui.multiTurn,
                    onClick = { vm.setMultiTurn(!ui.multiTurn) },
                    enabled = !ui.rawMode,
                    label = { Text("多轮上下文") }
                )
                FilterChip(
                    selected = ui.thinking,
                    onClick = { vm.setThinking(!ui.thinking) },
                    enabled = !ui.rawMode,
                    label = { Text("思考模式") }
                )
                FilterChip(
                    selected = ui.rawMode,
                    onClick = { vm.setRawMode(!ui.rawMode) },
                    label = { Text("原始补全") }
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (ui.messages.isEmpty() && !ui.streaming) {
                    item(key = "empty") {
                        Text(
                            "这里是裸模型。正式聊天里出现复读/幻觉时，用同一句话在这里再问一次：\n\n" +
                                "· 这里也复读 → 更可能是模型 / 采样 / 后端本身\n" +
                                "· 这里正常 → 更可能是正式聊天页的提示词、工具或 choices 注入\n\n" +
                                "建议先保持「多轮 + 思考关」，再逐项打开对比。",
                            style = Type.bodySmall,
                            color = Colors.onSurfaceVariant
                        )
                    }
                }

                items(items = ui.messages) { m ->
                    DebugMessageRow(m)
                }

                if (ui.streaming) {
                    item(key = "stream") {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                "模型",
                                style = Type.labelSmall,
                                color = Colors.primary
                            )
                            Spacer(Modifier.height(2.dp))
                            SelectionContainer {
                                Text(
                                    text = ui.current.ifEmpty { "生成中…" },
                                    style = Type.bodyMedium,
                                    color = Colors.onSurface
                                )
                            }
                        }
                    }
                }
                item(key = "bottom_spacer") { Spacer(Modifier.height(8.dp)) }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = DesignTokens.spacing12, vertical = DesignTokens.spacing8),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入一句话，纯裸地发给模型") },
                    maxLines = 4
                )
                if (ui.streaming) {
                    IconButton(onClick = vm::stop) {
                        Icon(Icons.Outlined.Stop, contentDescription = "停止")
                    }
                } else {
                    IconButton(
                        onClick = {
                            if (input.isNotBlank()) {
                                vm.send(input)
                                input = ""
                            }
                        },
                        enabled = input.isNotBlank() && ui.hasModel
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(ui: DebugUiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DesignTokens.spacing16, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Colors.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(DesignTokens.spacing16)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ui.modelName.ifBlank { "未加载模型" },
                    style = Type.titleSmall,
                    color = if (ui.hasModel) Colors.onSurface else Colors.error,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(ui.backendName, style = Type.labelSmall, color = Colors.primary)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "无 system 提示 · 无工具 · 无 choices 协议 · 无角色设定；消息只存内存",
                style = Type.bodySmall,
                color = Colors.onSurfaceVariant
            )
            if (ui.samplerInfo.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(ui.samplerInfo, style = Type.labelSmall, color = Colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DebugMessageRow(m: DebugMsg) {
    val isUser = m.role == "user"
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = if (isUser) "你" else "模型",
            style = Type.labelSmall,
            color = if (isUser) Colors.onSurfaceVariant else Colors.primary
        )
        Spacer(Modifier.height(2.dp))
        SelectionContainer {
            Text(
                text = m.content,
                style = Type.bodyMedium,
                color = Colors.onSurface
            )
        }
        if (m.meta.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(m.meta, style = Type.labelSmall, color = Colors.onSurfaceVariant)
        }
    }
}
