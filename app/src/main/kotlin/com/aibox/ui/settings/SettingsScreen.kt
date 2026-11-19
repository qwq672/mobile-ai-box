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
    // 模型管理（快捷入口）
    onOpenModels: () -> Unit = {},
    // 推理参数与采样（独立子页）
    onOpenInferenceParams: () -> Unit = {},
    // 重置设置（独立子页）
    onOpenReset: () -> Unit = {},
    // 数据导入与导出（独立子页）
    onOpenDataManagement: () -> Unit = {}
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val cfg = ui.config
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
            /* ============== 常用（所有子页入口统一走箭头） ============== */
            item {
                SectionHeader(icon = Icons.Outlined.Tune, title = "常用")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        QuickEntryRow(
                            title = "推理参数与采样",
                            subtitle = "CPU / GPU / NPU · Vulkan · FlashAttention · 线程 / KV / 上下文 · 采样",
                            onClick = onOpenInferenceParams
                        )
                        HorizontalDivider()
                        QuickEntryRow(
                            title = "推理优化与电量",
                            subtitle = "HTP 档位 / 低电量阈值与提示 / 模型预加载 / KV 连续布局 / 空闲卸载",
                            onClick = onOpenOptimization
                        )
                        HorizontalDivider()
                        QuickEntryRow(
                            title = "工具与联网",
                            subtitle = "工具开关 / 搜索服务商与 Key / URL 阅读 / GPS 权限",
                            onClick = onOpenToolSettings
                        )
                        HorizontalDivider()
                        QuickEntryRow(
                            title = "模型与云端",
                            subtitle = "本地模型 / 云端模型 / 下载源与搜索下载",
                            onClick = onOpenModels
                        )
                        HorizontalDivider()
                        HorizontalDivider()
                        QuickEntryRow(
                            title = "数据导入与导出",
                            subtitle = "备份对话 / 云端模型 / 设置 / 图片；导入 DeepSeek / ChatGPT / AIBox 备份",
                            onClick = onOpenDataManagement
                        )
                        HorizontalDivider()
                        QuickEntryRow(
                            title = "重置设置",
                            subtitle = "按分类重置 / 重置所有设置（带确认）",
                            onClick = onOpenReset
                        )
                    }
                }
            }

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
                            subtitle = if (ui.neutralTheme)
                                "中性配色已开启，动态取色不可用"
                            else "跟随系统壁纸自动配色（仅 Android 12+）",
                            checked = ui.dynamicColor,
                            enabled = !ui.neutralTheme,
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
                        SwitchRow(
                            title = "中性配色（黑白主题）",
                            subtitle = "固定黑白灰主题 + 少量彩色点缀，配色不可选；主题模式（明/暗）仍可自选",
                            checked = ui.neutralTheme,
                            onCheckedChange = vm::onNeutralThemeChange
                        )
                        HorizontalDivider()
                        // 中性主题下配色固定，隐藏主色调/自定义色相选择，用户不可改
                        if (ui.neutralTheme) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(
                                    "主色调（固定）",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "中性主题使用固定的黑白灰底色与少量彩色点缀（停止/删除仍为红色），配色不可更改。" +
                                        "如需自选主色调，请关闭「中性配色」。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                "主色调",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                when {
                                    ui.dynamicColor -> "动态取色已开启，主色调将在关闭动态取色后生效"
                                    else -> "选一个喜欢的强调色，界面会按它推导整套配色"
                                },
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
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text("回车发送") },
                            supportingContent = { Text(
                                "关闭（默认）：回车换行，发送只走输入框右侧按钮；开启：回车直接发送。"
                            ) },
                            trailingContent = { Switch(checked = ui.enterToSend, onCheckedChange = vm::onEnterToSendChange) },
                            leadingContent = { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(24.dp)) }
                        )
                    }
                }
            }

            /* ============== 实验功能 ============== */
            item {
                SectionHeader(icon = Icons.Outlined.Casino, title = "实验功能")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    SwitchRow(
                        title = "Beta：aibox:choices 选项卡片",
                        subtitle = "让模型把 2–4 个选项渲染成按钮。离线小模型容易滥用，出现满屏 A/B/C，默认关闭",
                        checked = ui.choicesBeta,
                        onCheckedChange = vm::onChoicesBetaChange
                    )
                }
            }

            /* ============== 权限 ============== */
            item {
                SectionHeader(icon = Icons.Outlined.Lock, title = "权限")
                PermissionsCard()
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
}

/** 设置页顶部快捷入口行。 */
@Composable
private fun QuickEntryRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            Icon(
                androidx.compose.material.icons.Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
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
