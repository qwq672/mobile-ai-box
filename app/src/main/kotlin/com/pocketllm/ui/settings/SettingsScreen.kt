package com.pocketllm.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketllm.domain.inference.BackendType
import com.pocketllm.ui.components.SliderWithLabel
import com.pocketllm.ui.components.SwitchRow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val cfg = ui.config
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    val drawerState = com.pocketllm.ui.nav.LocalDrawerState.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Outlined.Menu, contentDescription = "菜单")
                    }
                },
                title = { Text("设置", style = MaterialTheme.typography.titleLarge) }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            /* ============== 外观 ============== */
            item {
                SectionHeader(icon = Icons.Outlined.Palette, title = "外观")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        SwitchRow(
                            title = "Material You 动态取色",
                            subtitle = "跟随系统壁纸自动配色（仅 Android 12+）",
                            checked = ui.dynamicColor,
                            onCheckedChange = vm::onDynamicColorChange
                        )
                        HorizontalDivider()
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                "主题模式",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.size(6.dp))
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                val opts = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")
                                opts.forEachIndexed { i, (id, label) ->
                                    SegmentedButton(
                                        selected = ui.darkTheme == id,
                                        onClick = { vm.onDarkThemeChange(id) },
                                        shape = SegmentedButtonDefaults.itemShape(i, opts.size)
                                    ) { Text(label) }
                                }
                            }
                        }
                    }
                }
            }

            /* ============== 后端 ============== */
            item {
                SectionHeader(icon = Icons.Outlined.RocketLaunch, title = "后端")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        BackendSelector(cfg.backend, vm::onBackendChange, ui)
                        HelpText("仅 Vulkan / NPU / OpenCL 后端可调节 GPU 层数。不可用的后端会自动回退到 CPU。")
                        SliderWithLabel(
                            title = "GPU/NPU 层数",
                            value = cfg.nGpuLayers.toFloat(),
                            valueRange = 0f..100f,
                            valueText = if (cfg.nGpuLayers == 0) "自动（NPU=全部）" else cfg.nGpuLayers.toString(),
                            onValueChange = { vm.update(cfg.copy(nGpuLayers = it.toInt())) }
                        )
                    }
                }
            }

            /* ============== 性能 ============== */
            item {
                SectionHeader(icon = Icons.Outlined.Bolt, title = "性能")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            SliderWithLabel(
                                title = "CPU 线程数",
                                value = cfg.cpuThreads.toFloat(),
                                valueRange = 0f..8f,
                                steps = 7,
                                valueText = if (cfg.cpuThreads == 0) "自动（按大核数）" else cfg.cpuThreads.toString(),
                                onValueChange = { vm.update(cfg.copy(cpuThreads = it.toInt())) }
                            )
                            SliderWithLabel(
                                title = "物理批处理 (physical batch)",
                                value = cfg.physicalBatch.toFloat(),
                                valueRange = 64f..4096f,
                                valueText = cfg.physicalBatch.toString(),
                                onValueChange = { vm.update(cfg.copy(physicalBatch = it.toInt())) }
                            )
                            SliderWithLabel(
                                title = "逻辑批处理 (batch)",
                                value = cfg.batch.toFloat(),
                                valueRange = 256f..8192f,
                                valueText = cfg.batch.toString(),
                                onValueChange = { vm.update(cfg.copy(batch = it.toInt())) }
                            )
                        }
                        HorizontalDivider()
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                "KV Cache 量化",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.size(6.dp))
                            KvQuantSelector(cfg.kvQuant, vm::onKvQuantChange)
                            Spacer(Modifier.size(6.dp))
                            HelpText("更高级别量化 → 内存占用↓、带宽↓、温度↓；质量略降")
                            SliderWithLabel(
                                title = "上下文长度",
                                value = cfg.contextLength.toFloat(),
                                valueRange = 512f..32768f,
                                valueText = cfg.contextLength.toString(),
                                onValueChange = { vm.update(cfg.copy(contextLength = it.toInt())) }
                            )
                        }
                    }
                }
            }

            /* ============== 热管理（危险区） ============== */
            item {
                SectionHeader(icon = Icons.Outlined.Warning, title = "热管理")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(Modifier.padding(8.dp)) {
                        ListItem(
                            leadingContent = {
                                Icon(
                                    Icons.Outlined.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(28.dp)
                                )
                            },
                            headlineContent = {
                                Text(
                                    "防回退（Anti-Rollback）",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            },
                            supportingContent = {
                                Text(
                                    "开：保持 GPU/NPU 频率不被热降档，性能优先但发热更明显\n关：允许热感知软降（推荐，发烫治理）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = cfg.antiRollback,
                                    onCheckedChange = { vm.update(cfg.copy(antiRollback = it)) }
                                )
                            }
                        )
                    }
                }
            }

            /* ============== 采样 ============== */
            item {
                SectionHeader(icon = Icons.Outlined.Casino, title = "采样")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SliderWithLabel(
                            title = "Temperature",
                            value = cfg.temperature,
                            valueRange = 0.01f..2f,
                            valueText = "%.2f".format(cfg.temperature),
                            onValueChange = { vm.update(cfg.copy(temperature = it)) }
                        )
                        SliderWithLabel(
                            title = "Top-K",
                            value = cfg.topK.toFloat(),
                            valueRange = 1f..200f,
                            valueText = cfg.topK.toString(),
                            onValueChange = { vm.update(cfg.copy(topK = it.toInt())) }
                        )
                        SliderWithLabel(
                            title = "Top-P",
                            value = cfg.topP,
                            valueRange = 0.1f..1f,
                            valueText = "%.2f".format(cfg.topP),
                            onValueChange = { vm.update(cfg.copy(topP = it)) }
                        )
                        SliderWithLabel(
                            title = "Repeat Penalty",
                            value = cfg.repeatPenalty,
                            valueRange = 1f..2f,
                            valueText = "%.2f".format(cfg.repeatPenalty),
                            onValueChange = { vm.update(cfg.copy(repeatPenalty = it)) }
                        )
                    }
                }
            }

            /* ============== 启动行为 ============== */
            item {
                SectionHeader(icon = Icons.Outlined.RestartAlt, title = "启动行为")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        SwitchRow(
                            title = "启动时自动加载上次模型",
                            subtitle = "下次打开 App 自动恢复推理状态",
                            checked = cfg.autoLoadModel,
                            onCheckedChange = { vm.update(cfg.copy(autoLoadModel = it)) }
                        )
                    }
                }
            }

            /* ============== 对话 ============== */
            item {
                SectionHeader(icon = Icons.Outlined.ChatBubbleOutline, title = "对话")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = cfg.systemPrompt,
                            onValueChange = { vm.update(cfg.copy(systemPrompt = it)) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("系统提示词（System Prompt）") },
                            placeholder = { Text("例：你是一个严谨的助手，用中文简洁回答。留空则不附加。") },
                            minLines = 2,
                            maxLines = 5,
                            shape = MaterialTheme.shapes.small
                        )
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text("思考模式") },
                            supportingContent = { Text(
                                "开启后默认让模型进入思考模式（Qwen3 /think 标志）。" +
                                "支持的模型会先输出思考过程再回答。" +
                                "关闭则用 /no_think 标志强制不思考，更快但质量略降。") },
                            trailingContent = { Switch(checked = ui.thinkingMode, onCheckedChange = vm::onThinkingModeChange) },
                            leadingContent = { Icon(Icons.Outlined.Psychology, contentDescription = null, modifier = Modifier.size(24.dp)) }
                        )
                    }
                }
            }

            /* ============== 日志 ============== */
            item {
                SectionHeader(icon = Icons.Outlined.Edit, title = "日志")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        SwitchRow(
                            title = "启用文件日志",
                            subtitle = "写入外部存储，便于排查问题",
                            checked = ui.loggingEnabled,
                            onCheckedChange = vm::onLoggingEnabledChange
                        )
                        HorizontalDivider()
                        ListItem(
                            leadingContent = {
                                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(22.dp))
                            },
                            headlineContent = { Text("日志路径", style = MaterialTheme.typography.bodyMedium) },
                            supportingContent = {
                                Text(
                                    ui.logFilePath ?: "(未初始化)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    val path = ui.logFilePath
                                    if (path.isNullOrBlank()) {
                                        Toast.makeText(context, "日志路径尚未初始化", Toast.LENGTH_SHORT).show()
                                    } else {
                                        clipboard.setText(AnnotatedString(path))
                                        Toast.makeText(context, "已复制日志路径", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) {
                                Text("复制日志路径")
                            }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    vm.clearLog()
                                    Toast.makeText(context, "已清除日志", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("清除日志")
                            }
                        }
                    }
                }
            }

            /* ============== 关于 ============== */
            item {
                SectionHeader(icon = Icons.Outlined.Info, title = "关于")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        ListItem(
                            headlineContent = { Text("PocketLLM", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Kotlin + Compose + llama.cpp") },
                            leadingContent = { Icon(Icons.Outlined.Memory, contentDescription = null, modifier = Modifier.size(24.dp)) }
                        )
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text("Native 版本", style = MaterialTheme.typography.bodyMedium) },
                            trailingContent = { Text(ui.nativeVersion, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        )
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text("SoC", style = MaterialTheme.typography.bodyMedium) },
                            supportingContent = { Text("大核 ${ui.bigCores}") },
                            trailingContent = { Text(ui.socName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        )
                    }
                }
            }

            // 底部留白
            item { Spacer(Modifier.size(16.dp)) }
        }
    }
}

/* ---------------- Section Header ---------------- */

@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun HelpText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/* ---------------- Backend Selector ---------------- */

@Composable
private fun BackendSelector(
    selected: BackendType,
    onSelect: (BackendType) -> Unit,
    ui: SettingsViewModel.UiState
) {
    val opts = BackendType.entries.map { b ->
        val enabled = when (b) {
            BackendType.CPU -> true
            BackendType.VULKAN -> ui.vulkanAvailable
            BackendType.NPU -> ui.npuAvailable
            BackendType.OPENCL -> ui.openclAvailable
        }
        Triple(b, enabled, b.displayNameZh)
    }
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "推理后端",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            opts.forEachIndexed { i, (b, enabled, label) ->
                SegmentedButton(
                    selected = selected == b,
                    onClick = { if (enabled) onSelect(b) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(i, opts.size)
                ) {
                    val suffix = if (!enabled) " · N/A" else ""
                    Text(label + suffix)
                }
            }
        }
    }
}

/* ---------------- KV Quant Selector ---------------- */

@Composable
private fun KvQuantSelector(selected: String, onSelect: (String) -> Unit) {
    val opts = listOf(
        "f16" to "F16",
        "q8_0" to "Q8_0",
        "q4_0" to "Q4_0"
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        opts.forEachIndexed { i, (id, label) ->
            SegmentedButton(
                selected = selected == id,
                onClick = { onSelect(id) },
                shape = SegmentedButtonDefaults.itemShape(i, opts.size)
            ) { Text(label) }
        }
    }
}
