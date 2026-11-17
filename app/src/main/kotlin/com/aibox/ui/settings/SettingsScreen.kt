package com.aibox.ui.settings

import android.widget.Toast
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatAlignLeft
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aibox.domain.inference.BackendType
import com.aibox.ui.components.SliderWithLabel
import com.aibox.ui.components.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel = viewModel(),
    onOpenThermal: () -> Unit = {},
    onOpenToolSettings: () -> Unit = {},
    // G-settings-fix：新增入口跳转到推理优化设置页（开关式优化统一归口处）
    onOpenOptimization: () -> Unit = {},
    // 调试：纯净聊天（不注入任何提示词 / 工具，用于定位幻觉与复读来源）
    onOpenDebugChat: () -> Unit = {},
    // 运行日志查看器
    onOpenLogs: () -> Unit = {},
    // 帮助与教程
    onOpenHelp: () -> Unit = {}
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val cfg = ui.config
    var pendingReset by remember { mutableStateOf<ResetTarget?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    val drawerState = com.aibox.ui.nav.LocalDrawerState.current
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
                        HorizontalDivider()
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                "主色调",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                if (ui.dynamicColor) "动态取色已开启，主色调将在关闭动态取色后生效"
                                else "选一个喜欢的强调色，界面会按它推导整套配色",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.size(10.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val presets = listOf(
                                    "dynamic" to null,
                                    "默认" to "#6750A4",
                                    "蓝" to "#3D6DE0",
                                    "青" to "#00897B",
                                    "绿" to "#2E7D32",
                                    "橙" to "#F57C00",
                                    "玫红" to "#D81B60"
                                )
                                presets.forEach { (label, hex) ->
                                    val selected = ui.accentColor == (hex ?: "dynamic")
                                    val swatchColor = if (hex == null) MaterialTheme.colorScheme.surfaceContainerHighest
                                    else Color(android.graphics.Color.parseColor(hex))
                                    Box(
                                        Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .background(swatchColor)
                                            .border(
                                                width = if (selected) 2.dp else 1.dp,
                                                color = if (selected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.outlineVariant,
                                                shape = CircleShape
                                            )
                                            .clickable { vm.onAccentColorChange(hex ?: "dynamic") },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (hex == null) {
                                            Icon(
                                                Icons.Outlined.Palette,
                                                contentDescription = "跟随系统",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.size(10.dp))
                            // 自定义色相：拖动后按该色相重建整套主题（不只换主色）
                            var hue by remember(ui.accentColor) {
                                mutableStateOf(
                                    hueOfHex(ui.accentColor).takeIf { it >= 0f } ?: 210f
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "自定义色相",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.size(12.dp))
                                Slider(
                                    value = hue,
                                    onValueChange = { hue = it },
                                    onValueChangeFinished = {
                                        vm.onAccentColorChange(hsvToHex(hue))
                                    },
                                    valueRange = 0f..360f,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.size(10.dp))
                                Box(
                                    Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(Color(android.graphics.Color.parseColor(hsvToHex(hue))))
                                )
                            }
                        }
                        HorizontalDivider()
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            SliderWithLabel(
                                title = "圆角大小",
                                value = ui.cornerScale,
                                valueRange = 0.5f..1.6f,
                                valueText = "%.1fx".format(ui.cornerScale),
                                onValueChange = vm::onCornerScaleChange
                            )
                            HelpText("统一调整气泡、卡片、输入框的圆角；0.5x 更方正，1.6x 更圆润。")
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
                        HelpText(
                            buildString {
                                append("仅 Vulkan / NPU / OpenCL 后端可调节 GPU 层数。")
                                if (!ui.npuAvailable) {
                                    append(" NPU 需要 Hexagon 专用构建（普通 CI 包不含 ggml-hexagon 与 skel）；")
                                }
                                append("不可用的后端会禁用。")
                            }
                        )
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

            /* ============== 设备状态（A2 ThermalMonitorCard + 综合状态） ============== */
            item {
                SectionHeader(icon = Icons.Outlined.Thermostat, title = "设备状态")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            "状态刷新频率",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.size(6.dp))
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val opts = listOf(1000 to "1 秒", 2000 to "2 秒", 5000 to "5 秒")
                            opts.forEachIndexed { i, (ms, label) ->
                                SegmentedButton(
                                    selected = ui.thermalIntervalMs == ms,
                                    onClick = { vm.onThermalIntervalChange(ms) },
                                    shape = SegmentedButtonDefaults.itemShape(i, opts.size)
                                ) { Text(label) }
                            }
                        }
                        Spacer(Modifier.size(6.dp))
                        HelpText("频率越高越实时，但读取 /sys 也更耗电。后台时系统会自动降频。")
                    }
                }
                Spacer(Modifier.size(12.dp))
                com.aibox.ui.thermal.ThermalMonitorCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onOpenThermal
                )
            }

            /* ============== 诊断 ============== */
            item {
                SectionHeader(icon = Icons.Outlined.Info, title = "诊断")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DiagnosticRow("SoC", ui.socName.ifBlank { "未知" })
                        DiagnosticRow("大核数", ui.bigCores.toString())
                        DiagnosticRow("原生库", ui.nativeVersion.ifBlank { "未加载" })
                        DiagnosticRow(
                            "后端可用",
                            buildString {
                                append("Vulkan ")
                                append(if (ui.vulkanAvailable) "✓" else "✗")
                                append(" · NPU ")
                                append(if (ui.npuAvailable) "✓" else "✗")
                                append(" · OpenCL ")
                                append(if (ui.openclAvailable) "✓" else "✗")
                            }
                        )
                        HorizontalDivider()
                        DiagnosticRow("当前模型", ui.runtimeModel.ifBlank { "未加载" })
                        DiagnosticRow("生效后端", ui.runtimeBackend.ifBlank { "--" })
                        DiagnosticRow(
                            "KV 请求 → 实际",
                            "${ui.requestedKv} → ${ui.effectiveKv}" +
                                if (ui.requestedKv != ui.effectiveKv &&
                                    ui.effectiveKv != "unknown"
                                ) "（已降级）" else ""
                        )
                        DiagnosticRow("FlashAttention", if (ui.effectiveFlashAttn) "开" else "关")
                        DiagnosticRow("实际线程", if (ui.effectiveThreads > 0) ui.effectiveThreads.toString() else "--")
                        DiagnosticRow("上下文", if (ui.runtimeContextMax > 0) ui.runtimeContextMax.toString() else "--")
                        if (ui.requestedKv != ui.effectiveKv && ui.effectiveKv != "unknown") {
                            Text(
                                "KV 量化请求 ${ui.requestedKv}，实际生效 ${ui.effectiveKv}：量化 V cache 需要 FlashAttention；" +
                                    "当前模型或后端未启用 FA，已自动降级以保证能加载。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!ui.npuAvailable) {
                            DiagnosticRow(
                                "NPU 说明",
                                "当前安装包未包含 ggml-hexagon 后端（普通 CI 包）。NPU 版需要在装有 Hexagon SDK 的自托管 runner 上构建。"
                            )
                        }
                        if (ui.lastNativeError.isNotBlank()) {
                            Text(
                                "最近一次原生错误",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                ui.lastNativeError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        OutlinedButton(onClick = { vm.refreshDiagnostics() }) {
                            Text("刷新诊断信息")
                        }
                    }
                }
            }

            /* ============== 推理框架（LiteRT / ONNX / MNN） ============== */
            item {
                SectionHeader(icon = Icons.Outlined.Memory, title = "推理框架")
                val frameworkDiagnostics by produceState(
                    initialValue = emptyList<com.aibox.infra.plugin.PluginDiagnostic>()
                ) {
                    value = withContext(Dispatchers.IO) {
                        runCatching {
                            com.aibox.AIBoxApp.instance.container.pluginManager.diagnoseKnown()
                        }.getOrDefault(emptyList())
                    }
                }
                FrameworkStatusCard(
                    diagnostics = frameworkDiagnostics.filter { it.installed }
                )
            }

            /* ============== 调试 ============== */
            item {
                SectionHeader(icon = Icons.Outlined.Info, title = "调试")
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
                            leadingContent = {
                                Icon(
                                    Icons.Outlined.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            headlineContent = { Text("纯净聊天") },
                            supportingContent = {
                                Text(
                                    "不注入 system 提示 / 工具 / choices / 角色设定，" +
                                        "用于对比判断复读与幻觉来源"
                                )
                            },
                            trailingContent = {
                                Icon(
                                    androidx.compose.material.icons.Icons.AutoMirrored.Outlined.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            modifier = Modifier.clickable { onOpenDebugChat() }
                        )
                        HorizontalDivider()
                        ListItem(
                            leadingContent = {
                                Icon(
                                    Icons.Outlined.FormatAlignLeft,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            headlineContent = { Text("运行日志") },
                            supportingContent = {
                                Text("实时查看推理 / 工具 / 插件 / 模块日志，可按级别与 tag 过滤、复制")
                            },
                            trailingContent = {
                                Icon(
                                    androidx.compose.material.icons.Icons.AutoMirrored.Outlined.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            modifier = Modifier.clickable { onOpenLogs() }
                        )
                    }
                }
            }

            /* ============== 重置 ============== */
            item {
                SectionHeader(icon = Icons.Outlined.RestartAlt, title = "重置")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        ResetRow(
                            title = "重置推理参数",
                            subtitle = "后端 / 线程 / 批大小 / KV / 上下文 / 采样 / 系统提示词"
                        ) {
                            pendingReset = ResetTarget(
                                title = "重置推理参数？",
                                message = "当前已加载的模型不会立即变化，重新加载后生效。"
                            ) { vm.resetInference() }
                        }
                        HorizontalDivider()
                        ResetRow(
                            title = "重置外观",
                            subtitle = "动态取色 / 主题 / 主色 / 圆角 / 消息呈现"
                        ) {
                            pendingReset = ResetTarget("重置外观？", "界面会立即恢复默认外观。") {
                                vm.resetAppearance()
                            }
                        }
                        HorizontalDivider()
                        ResetRow(
                            title = "重置行为与搜索",
                            subtitle = "下载源 / 思考档位 / 日志开关 / Tavily、Exa API key"
                        ) {
                            pendingReset = ResetTarget("重置行为与搜索设置？", "API key 也会被清空。") {
                                vm.resetBehavior()
                            }
                        }
                        HorizontalDivider()
                        ResetRow(
                            title = "重置云端模型",
                            subtitle = "清空 API key / Base URL / 模型名，并关闭云端"
                        ) {
                            pendingReset = ResetTarget("重置云端模型配置？", "需要重新填写 API key。") {
                                vm.resetCloud()
                            }
                        }
                        HorizontalDivider()
                        ResetRow(
                            title = "重置工具设置",
                            subtitle = "工具开关恢复默认、自动确认白名单与工具选项清空"
                        ) {
                            pendingReset = ResetTarget("重置工具设置？", "基础工具会恢复默认开启，高级工具默认关闭。") {
                                vm.resetToolSettings()
                            }
                        }
                        HorizontalDivider()
                        ResetRow(
                            title = "重置推理优化设置",
                            subtitle = "TTFT / TG / 能耗开关恢复默认档"
                        ) {
                            pendingReset = ResetTarget("重置推理优化设置？", "会恢复到「默认（均衡）」档。") {
                                vm.resetOptimizationSettings()
                            }
                        }
                        HorizontalDivider()
                        ResetRow(
                            title = "重置所有设置",
                            subtitle = "以上全部 + 常规设置；模型文件与欢迎页状态保留",
                            danger = true
                        ) {
                            pendingReset = ResetTarget(
                                title = "重置所有设置？",
                                message = "该操作不可撤销。模型文件、聊天记录与模型列表不会被删除。"
                            ) { vm.resetAllSettings() }
                        }
                    }
                }
            }

            /* ============== 帮助 ============== */
            item {
                SectionHeader(icon = Icons.Outlined.Info, title = "帮助")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    ListItem(
                        leadingContent = {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        headlineContent = { Text("帮助与教程") },
                        supportingContent = {
                            Text("上手指南、设置说明、工具与联网、低电量模式、常见问题")
                        },
                        trailingContent = {
                            Icon(
                                androidx.compose.material.icons.Icons.AutoMirrored.Outlined.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.clickable { onOpenHelp() }
                    )
                }
            }

            /* ============== 工具调用（B7 工具设置面板入口） ============== */
            item {
                SectionHeader(icon = Icons.Outlined.Build, title = "工具调用")
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
                            leadingContent = {
                                Icon(
                                    Icons.Outlined.Build,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            headlineContent = { Text("工具调用设置") },
                            supportingContent = { Text("管理 LLM 可调用的工具：启用 / 禁用、高级工具确认、自动确认白名单") },
                            trailingContent = {
                                Icon(
                                    androidx.compose.material.icons.Icons.AutoMirrored.Outlined.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            modifier = Modifier.clickable { onOpenToolSettings() }
                        )
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
                        HelpText("越高越发散、越低越保守。小模型（≤2B）建议 0.6~0.8，过高更容易胡编。")
                        SliderWithLabel(
                            title = "Top-K",
                            value = cfg.topK.toFloat(),
                            valueRange = 1f..200f,
                            valueText = cfg.topK.toString(),
                            onValueChange = { vm.update(cfg.copy(topK = it.toInt())) }
                        )
                        HelpText("每步只保留概率最高的 K 个候选；40 是通用默认值。")
                        SliderWithLabel(
                            title = "Top-P",
                            value = cfg.topP,
                            valueRange = 0.1f..1f,
                            valueText = "%.2f".format(cfg.topP),
                            onValueChange = { vm.update(cfg.copy(topP = it)) }
                        )
                        HelpText("累积概率阈值；0.9~0.95 通常足够，越低越保守。")
                        SliderWithLabel(
                            title = "Min-P",
                            value = cfg.minP,
                            valueRange = 0f..0.5f,
                            valueText = if (cfg.minP <= 0f) "关闭" else "%.2f".format(cfg.minP),
                            onValueChange = { vm.update(cfg.copy(minP = it)) }
                        )
                        HelpText(
                            "只保留相对最大概率不低于该比例的候选。0.05 是稳妥值，0 表示关闭；" +
                                "比单纯压 Top-P 更不容易误删正确但概率略低的词。"
                        )
                        SliderWithLabel(
                            title = "Repeat Penalty",
                            value = cfg.repeatPenalty,
                            valueRange = 1f..2f,
                            valueText = "%.2f".format(cfg.repeatPenalty),
                            onValueChange = { vm.update(cfg.copy(repeatPenalty = it)) }
                        )
                        HelpText(
                            "对最近若干 token 做重复惩罚。1.1 是通用值；调太高会让小模型用词变怪，" +
                                "调太低容易复读。改动即时生效，无需重新加载模型。"
                        )
                        SliderWithLabel(
                            title = "Repeat 窗口",
                            value = cfg.repeatLastN.toFloat(),
                            valueRange = 32f..1024f,
                            valueText = "${cfg.repeatLastN} tokens",
                            onValueChange = { vm.update(cfg.copy(repeatLastN = it.toInt())) }
                        )
                        HelpText(
                            "参与重复统计的最近 token 数。256 覆盖常见段落复读；" +
                                "长文创作可降到 128，短问答可升到 384 减少复读。"
                        )
                        HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "当前生效：temp=${"%.2f".format(cfg.temperature)} · " +
                                    "top_k=${cfg.topK} · top_p=${"%.2f".format(cfg.topP)} · " +
                                    "min_p=${if (cfg.minP <= 0f) "关" else "%.2f".format(cfg.minP)} · " +
                                    "repeat=${"%.2f".format(cfg.repeatPenalty)}/${cfg.repeatLastN}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                onClick = {
                                    vm.update(
                                        cfg.copy(
                                            temperature = 0.8f,
                                            topK = 40,
                                            topP = 0.95f,
                                            minP = 0.05f,
                                            repeatPenalty = 1.1f,
                                            repeatLastN = 256
                                        )
                                    )
                                }
                            ) { Text("恢复推荐值") }
                        }
                    }
                }
            }

            /* ============== 推理优化（G-settings-fix：归口开关式优化）============== */
            item {
                SectionHeader(icon = Icons.Outlined.Tune, title = "推理优化")
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
                            leadingContent = {
                                Icon(
                                    Icons.Outlined.Tune,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            headlineContent = { Text("推理优化与电量") },
                            supportingContent = {
                                Text(
                                    "电量与性能档（HTP 档位 / 低电量阈值与提示）/ 模型预加载 / " +
                                    "Flash Attention / KV 连续布局 / 空闲超时卸载等统一在此管理"
                                )
                            },
                            trailingContent = {
                                Icon(
                                    androidx.compose.material.icons.Icons.AutoMirrored.Outlined.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            modifier = Modifier.clickable { onOpenOptimization() }
                        )
                        HorizontalDivider()
                        HelpText(
                            "「启动时自动加载上次模型」开关已合并到此处「模型预加载」。"
                        )
                    }
                }
            }

            /* G-settings-fix：原"启动行为"autoLoadModel 开关已删除
             * （重叠 OptimizationSettings.preloadModel，用户只在推理优化页改）。 */

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
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.Psychology,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(Modifier.size(12.dp))
                                Text(
                                    "思考强度",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(Modifier.size(4.dp))
                            Text(
                                "仅对支持思考的模型生效：本地需 Qwen3 等带思考模板的模型，云端按供应商下发（DeepSeek thinking / 通义 enable_thinking / OpenAI reasoning_effort）。普通对话模型无论选哪档都不会有变化。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.size(8.dp))
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                val opts = listOf(
                                    "off" to "关闭",
                                    "low" to "低",
                                    "medium" to "中",
                                    "high" to "高"
                                )
                                opts.forEachIndexed { i, (id, label) ->
                                    SegmentedButton(
                                        selected = ui.thinkingLevel == id,
                                        onClick = { vm.onThinkingLevelChange(id) },
                                        shape = SegmentedButtonDefaults.itemShape(i, opts.size)
                                    ) { Text(label) }
                                }
                            }
                        }
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text("文档流消息样式") },
                            supportingContent = { Text(
                                "开启后助手回复不带气泡，正文直接铺在背景上（类似 ChatGPT / Claude），长回答更好读。" +
                                "关闭则用户与助手都使用气泡。") },
                            trailingContent = { Switch(checked = ui.chatDocStyle, onCheckedChange = vm::onChatDocStyleChange) },
                            leadingContent = { Icon(Icons.Outlined.FormatAlignLeft, contentDescription = null, modifier = Modifier.size(24.dp)) }
                        )
                    }
                }
            }

            /* ============== 云端模型 ============== */
            item {
                SectionHeader(icon = Icons.Outlined.Cloud, title = "云端模型")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SwitchRow(
                            title = "使用云端模型",
                            subtitle = "OpenAI 兼容协议，支持 DeepSeek / OpenAI / Moonshot / Ollama / vLLM",
                            checked = ui.cloudEnabled,
                            onCheckedChange = vm::onCloudEnabledChange
                        )
                        if (ui.cloudEnabled) {
                            HorizontalDivider()
                            OutlinedTextField(
                                value = ui.cloudBaseUrl,
                                onValueChange = vm::onCloudBaseUrlChange,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Base URL") },
                                placeholder = { Text("https://api.deepseek.com/v1") },
                                singleLine = true,
                                shape = MaterialTheme.shapes.small
                            )
                            OutlinedTextField(
                                value = ui.cloudModel,
                                onValueChange = vm::onCloudModelChange,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("模型名") },
                                placeholder = { Text("deepseek-chat") },
                                singleLine = true,
                                shape = MaterialTheme.shapes.small
                            )
                            OutlinedTextField(
                                value = ui.cloudApiKey,
                                onValueChange = vm::onCloudApiKeyChange,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("API Key") },
                                placeholder = { Text("sk-...") },
                                singleLine = true,
                                shape = MaterialTheme.shapes.small
                            )
                            Text(
                                "开启后聊天优先走云端，并用已启用的工具做 function calling；" +
                                    "关闭则回到本地模型。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            /* ============== 权限 ============== */
            item {
                SectionHeader(icon = Icons.Outlined.Lock, title = "权限")
                PermissionCard()
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
                            headlineContent = { Text("AIBox", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
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

            // 底部版本信息：便于确认装的是哪一版（覆盖安装失败时最先看这里）
            item {
                Text(
                    "AIBox ${com.aibox.BuildConfig.VERSION_NAME} (${com.aibox.BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }

            // 底部留白
            item { Spacer(Modifier.size(16.dp)) }
        }
    }

    // 重置确认对话框（所有重置都走这里，避免误触）
    pendingReset?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingReset = null },
            title = { Text(target.title) },
            text = { Text(target.message) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        target.action()
                        pendingReset = null
                        scope.launch {
                            snackbarHost.showSnackbar("已重置：${target.title.removeSuffix("？")}")
                        }
                    }
                ) { Text("确认重置", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingReset = null }) {
                    Text("取消")
                }
            }
        )
    }
}

/** 重置项：标题 + 说明 + 实际动作，确认后才执行。 */
private data class ResetTarget(
    val title: String,
    val message: String,
    val action: () -> Unit
)

/** 重置列表中的一行。 */
@Composable
private fun ResetRow(
    title: String,
    subtitle: String,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                title,
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = {
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            Icon(
                androidx.compose.material.icons.Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (danger) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier.clickable { onClick() }
    )
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

/** 诊断区的一行：左侧标签 + 右侧值（值可能很长，允许换行）。 */
@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1.6f)
        )
    }
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

/* ---------------- 运行时权限 ---------------- */

private data class PermissionItem(val permission: String, val title: String, val desc: String)

/**
 * 权限面板：列出聊天 / 多模态会用到的运行时权限，展示授权状态并支持按需申请。
 *
 * 相册走系统 Photo Picker 时不需要存储权限，但部分 ROM 仍会走 READ_MEDIA_IMAGES，
 * 所以这里也一并列出，便于用户提前授权。
 */
@Composable
private fun PermissionCard() {
    val context = LocalContext.current
    val items = remember {
        buildList {
            add(PermissionItem(Manifest.permission.RECORD_AUDIO, "麦克风", "语音输入 / 语音对话"))
            add(PermissionItem(Manifest.permission.CAMERA, "相机", "拍照后直接发送给模型"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(PermissionItem(Manifest.permission.POST_NOTIFICATIONS, "通知", "生成完成 / 下载完成的提醒"))
                add(PermissionItem(Manifest.permission.READ_MEDIA_IMAGES, "图片", "从相册选择图片作为输入"))
            }
        }
    }
    var granted by remember { mutableStateOf(setOf<String>()) }

    fun refresh() {
        granted = items.map { it.permission }
            .filter { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
            .toSet()
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    LaunchedEffect(Unit) { refresh() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            items.forEach { item ->
                val ok = item.permission in granted
                ListItem(
                    leadingContent = {
                        Icon(
                            if (ok) Icons.Outlined.Lock else Icons.Outlined.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    headlineContent = { Text(item.title) },
                    supportingContent = { Text(item.desc) },
                    trailingContent = {
                        Text(
                            if (ok) "已授权" else "去授权",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    },
                    modifier = Modifier.clickable(enabled = !ok) {
                        launcher.launch(arrayOf(item.permission))
                    }
                )
                HorizontalDivider()
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedButton(
                    onClick = {
                        val missing = items.map { it.permission }.filterNot { it in granted }
                        if (missing.isEmpty()) {
                            Toast.makeText(context, "全部权限已授权", Toast.LENGTH_SHORT).show()
                        } else {
                            launcher.launch(missing.toTypedArray())
                        }
                    }
                ) {
                    Text("申请缺失权限")
                }
            }
        }
    }
}

/** 从 #RRGGBB 提取色相（0..360）；解析失败返回 -1。 */
private fun hueOfHex(spec: String): Float {
    if (spec.isBlank() || spec.equals("dynamic", true)) return -1f
    return try {
        val c = android.graphics.Color.parseColor(spec)
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c, hsv)
        hsv[0]
    } catch (_: Exception) { -1f }
}

/** 固定饱和度/明度，按色相生成一个适合做主题种子的 #RRGGBB。 */
private fun hsvToHex(hue: Float): String {
    val c = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.62f, 0.62f))
    return String.format("#%06X", 0xFFFFFF and c)
}

/**
 * 推理框架状态卡片：LiteRT / ONNX / MNN 是否安装、是否已绑定。
 *
 * 只展示“确实装了”的包；没装的框架不占位，避免设置页堆一排红字。
 */
@Composable
private fun FrameworkStatusCard(
    diagnostics: List<com.aibox.infra.plugin.PluginDiagnostic>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (diagnostics.isEmpty()) {
                Text(
                    "未检测到 LiteRT / ONNX / MNN 插件。安装模块 APK 后会自动发现并绑定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                diagnostics.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                frameworkDisplayName(d.packageName),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                d.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!d.active) {
                                Text(
                                    d.reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Text(
                            if (d.active) "已启用" else "未启用",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (d.active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

private fun frameworkDisplayName(packageName: String): String = when {
    packageName.contains("litert", ignoreCase = true) -> "LiteRT-LM"
    packageName.contains("onnx", ignoreCase = true) -> "ONNX Runtime"
    packageName.contains("mnn", ignoreCase = true) -> "MNN"
    else -> packageName
}
