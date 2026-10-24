package com.aibox.ui.shortcut

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aibox.AIBoxApp
import com.aibox.data.model.ModelInfo
import com.aibox.domain.shortcut.PresetConfig
import com.aibox.domain.shortcut.ShortcutTargetType
import com.aibox.ui.components.unified.UnifiedSection
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.DesignTokens
import com.aibox.ui.theme.Type

/**
 * 快捷方式编辑器（E 主页 Hybrid 设计）。
 *
 * 布局：TopAppBar（返回 + 保存 + 删除）+ 滚动列：基础信息 / 绑定模型 / 预填配置。
 *
 * 入口：
 * - 从首页"添加"卡片进入：[initialShortcutId] = null + [initialTargetType] 给定默认。
 * - 从首页长按某 shortcut 进入：[initialShortcutId] 给定 → ViewModel.loadExisting。
 *
 * @param initialShortcutId 编辑已有快捷方式时传入 id；新建时传 null
 * @param initialTargetType 新建时的默认 targetType；编辑时忽略
 * @param onBack 返回回调（保存 / 删除成功后或用户点返回时触发）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutEditorScreen(
    initialShortcutId: String?,
    initialTargetType: ShortcutTargetType = ShortcutTargetType.CHAT,
    onBack: () -> Unit,
    vm: ShortcutEditorViewModel = viewModel(
        factory = ShortcutEditorViewModelFactory(
            shortcutRepository = AIBoxApp.instance.container.shortcutRepository,
            modelRepository = AIBoxApp.instance.container.modelRepository
        )
    )
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 初始化（只在初次进入时执行一次）
    // 用 LaunchedEffect(Unit) + vm.ui.value 直接读 StateFlow 当前值，避免与 init{} 中
    // 的 consumePendingDraft 竞争（collectAsStateWithLifecycle 是异步的，第一帧可能拿到旧值）。
    LaunchedEffect(initialShortcutId, initialTargetType) {
        if (initialShortcutId != null) {
            vm.loadExisting(initialShortcutId)
        } else if (vm.ui.value.draft == null) {
            // 只有当 init{} 没有消费 pendingDraft 时才走默认 startNew
            vm.startNew(initialTargetType)
        }
    }

    // 一次性错误：弹 Toast
    LaunchedEffect(ui.error) {
        ui.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearError()
        }
    }

    // 一次性完成事件：popBack
    LaunchedEffect(ui.finished) {
        if (ui.finished) {
            vm.clearFinished()
            onBack()
        }
    }

    val draft = ui.draft
    val title = if (ui.isExisting) "编辑快捷方式" else "新建快捷方式"

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text(title, style = Type.titleLarge) },
                actions = {
                    if (ui.isExisting) {
                        IconButton(onClick = { vm.delete() }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "删除",
                                tint = Colors.error
                            )
                        }
                    }
                    IconButton(onClick = { vm.save() }) {
                        Icon(
                            Icons.Outlined.Save,
                            contentDescription = "保存",
                            tint = Colors.primary
                        )
                    }
                }
            )
        }
    ) { inner ->
        if (draft == null) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Text("加载中…", color = Colors.onSurfaceVariant)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing12)
        ) {
            // ===== 1. 基础信息 =====
            UnifiedSection(title = "基础信息", showTopDivider = false) {
                Column(modifier = Modifier.padding(horizontal = DesignTokens.spacing16)) {
                    // Target type
                    Text("目标类型", style = Type.titleSmall, color = Colors.onSurface)
                    Spacer(Modifier.height(DesignTokens.spacing4))
                    Text(
                        "编辑时不可修改；如需切换请重新创建。",
                        style = Type.bodySmall, color = Colors.onSurfaceVariant
                    )
                    Spacer(Modifier.height(DesignTokens.spacing8))
                    // 用 FilterChip 横向滚动展示所有 targetType；编辑时禁用切换
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        ShortcutTargetType.values().forEach { type ->
                            FilterChip(
                                selected = draft.targetType == type,
                                onClick = {
                                    if (!ui.isExisting) {
                                        vm.update { it.copy(targetType = type) }
                                    }
                                },
                                enabled = !ui.isExisting,
                                label = {
                                    Text(
                                        "${ShortcutTargetType.defaultIcon(type)} " +
                                            ShortcutTargetType.displayName(type)
                                    )
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(DesignTokens.spacing16))

                    // 图标 emoji
                    Text("图标", style = Type.titleSmall, color = Colors.onSurface)
                    Spacer(Modifier.height(DesignTokens.spacing8))
                    EmojiPicker(
                        selected = draft.icon,
                        onPick = { emoji -> vm.update { it.copy(icon = emoji) } }
                    )

                    Spacer(Modifier.height(DesignTokens.spacing16))

                    // 标题
                    OutlinedTextField(
                        value = draft.title,
                        onValueChange = { v -> vm.update { it.copy(title = v) } },
                        label = { Text("标题") },
                        placeholder = { Text("例：和林黛玉聊天") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = DesignTokens.shapeMedium
                    )

                    Spacer(Modifier.height(DesignTokens.spacing12))

                    // 副标题（可空）
                    OutlinedTextField(
                        value = draft.subtitle.orEmpty(),
                        onValueChange = { v ->
                            vm.update { it.copy(subtitle = v.ifBlank { null }) }
                        },
                        label = { Text("副标题（可选）") },
                        placeholder = { Text("例：Qwen2.5-0.5B Q4_K_M") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = DesignTokens.shapeMedium
                    )
                }
            }

            // ===== 2. 绑定模型 =====
            UnifiedSection(title = "绑定模型") {
                Column(modifier = Modifier.padding(horizontal = DesignTokens.spacing16)) {
                    Text(
                        "为快捷方式指定一个模型；不指定则使用当前激活模型。",
                        style = Type.bodySmall, color = Colors.onSurfaceVariant
                    )
                    Spacer(Modifier.height(DesignTokens.spacing8))
                    ModelPickerRow(
                        selectedId = draft.modelId,
                        models = ui.availableModels,
                        onSelect = { id -> vm.update { it.copy(modelId = id) } }
                    )
                }
            }

            // ===== 3. 预填配置 =====
            PresetConfigSection(
                targetType = draft.targetType,
                preset = draft.presetConfig,
                onUpdatePreset = { transform -> vm.updatePreset(transform) }
            )

            Spacer(Modifier.height(DesignTokens.spacing32))
        }
    }
}

/* ============================================================
 * Emoji 选择器：横滑一排常用 emoji
 * ============================================================ */

@Composable
private fun EmojiPicker(
    selected: String,
    onPick: (String) -> Unit
) {
    val emojis = remember {
        listOf(
            "💬", "📝", "🎨", "🖼️", "🗣️", "📢", "🌐", "🌍",
            "📊", "🧩", "⚙️", "🚀", "🤖", "🦾", "🧠", "💡",
            "📚", "✍️", "🔍", "💻", "🎯", "🎵", "📷", "✨"
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
    ) {
        emojis.forEach { emoji ->
            AssistChip(
                onClick = { onPick(emoji) },
                label = { Text(emoji, style = Type.titleLarge) },
                leadingIcon = if (emoji == selected) {
                    {
                        Text(
                            "✓",
                            color = Colors.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else null
            )
        }
    }
}

/* ============================================================
 * 模型选择器：横滑 chips（"当前激活" + 已下载模型列表）
 * ============================================================ */

@Composable
private fun ModelPickerRow(
    selectedId: String?,
    models: List<ModelInfo>,
    onSelect: (String?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
    ) {
        // 第一项："使用当前激活模型"
        FilterChip(
            selected = selectedId == null,
            onClick = { onSelect(null) },
            label = { Text("当前激活") }
        )
        // 已下载模型
        models.forEach { m ->
            FilterChip(
                selected = selectedId == m.id.toString(),
                onClick = { onSelect(m.id.toString()) },
                label = { Text(m.name, maxLines = 1) }
            )
        }
    }
}

/* ============================================================
 * 预填配置（按 targetType 分支）
 * ============================================================ */

@Composable
private fun PresetConfigSection(
    targetType: ShortcutTargetType,
    preset: PresetConfig,
    onUpdatePreset: ((PresetConfig) -> PresetConfig) -> Unit
) {
    val title = "预填配置（${ShortcutTargetType.displayName(targetType)}）"
    UnifiedSection(title = title) {
        Column(modifier = Modifier.padding(horizontal = DesignTokens.spacing16)) {
            when (targetType) {
                ShortcutTargetType.CHAT -> ChatPresetFields(preset, onUpdatePreset)
                ShortcutTargetType.IMAGE_GEN -> ImageGenPresetFields(preset, onUpdatePreset)
                ShortcutTargetType.TRANSLATE -> TranslatePresetFields(preset, onUpdatePreset)
                ShortcutTargetType.BENCHMARK -> BenchmarkPresetFields(preset, onUpdatePreset)
                ShortcutTargetType.ASR,
                ShortcutTargetType.TTS,
                ShortcutTargetType.PLUGIN_PAGE -> {
                    Text(
                        "此类型暂无预填配置项。",
                        style = Type.bodySmall, color = Colors.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatPresetFields(
    preset: PresetConfig,
    onUpdate: ((PresetConfig) -> PresetConfig) -> Unit
) {
    // systemPrompt（多行）
    OutlinedTextField(
        value = preset.systemPrompt.orEmpty(),
        onValueChange = { v -> onUpdate { it.copy(systemPrompt = v.ifBlank { null }) } },
        label = { Text("System Prompt（角色设定）") },
        placeholder = { Text("例：你是林黛玉，言辞清雅，多愁善感…") },
        minLines = 2, maxLines = 4,
        modifier = Modifier.fillMaxWidth(),
        shape = DesignTokens.shapeMedium
    )
    Spacer(Modifier.height(DesignTokens.spacing8))
    NumberTextField(
        label = "Temperature",
        placeholder = "0.8",
        value = preset.temperature?.toString(),
        onValueChange = { v -> onUpdate { it.copy(temperature = v?.toFloat()) } }
    )
    Spacer(Modifier.height(DesignTokens.spacing8))
    NumberTextField(
        label = "Top-P",
        placeholder = "0.95",
        value = preset.topP?.toString(),
        onValueChange = { v -> onUpdate { it.copy(topP = v?.toFloat()) } }
    )
    Spacer(Modifier.height(DesignTokens.spacing8))
    NumberTextField(
        label = "Max Tokens",
        placeholder = "2048",
        isInteger = true,
        value = preset.maxTokens?.toString(),
        onValueChange = { v -> onUpdate { it.copy(maxTokens = v?.toInt()) } }
    )
}

@Composable
private fun ImageGenPresetFields(
    preset: PresetConfig,
    onUpdate: ((PresetConfig) -> PresetConfig) -> Unit
) {
    NumberTextField(
        label = "采样步数 (Sampling Steps)",
        placeholder = "20",
        isInteger = true,
        value = preset.samplingSteps?.toString(),
        onValueChange = { v -> onUpdate { it.copy(samplingSteps = v?.toInt()) } }
    )
    Spacer(Modifier.height(DesignTokens.spacing8))
    OutlinedTextField(
        value = preset.sampler.orEmpty(),
        onValueChange = { v -> onUpdate { it.copy(sampler = v.ifBlank { null }) } },
        label = { Text("采样器") },
        placeholder = { Text("Euler a / DPM++ 2M") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = DesignTokens.shapeMedium
    )
    Spacer(Modifier.height(DesignTokens.spacing8))
    NumberTextField(
        label = "Guidance Scale",
        placeholder = "7.5",
        value = preset.guidanceScale?.toString(),
        onValueChange = { v -> onUpdate { it.copy(guidanceScale = v?.toFloat()) } }
    )
}

@Composable
private fun TranslatePresetFields(
    preset: PresetConfig,
    onUpdate: ((PresetConfig) -> PresetConfig) -> Unit
) {
    OutlinedTextField(
        value = preset.sourceLanguage.orEmpty(),
        onValueChange = { v -> onUpdate { it.copy(sourceLanguage = v.ifBlank { null }) } },
        label = { Text("源语言") },
        placeholder = { Text("auto / zh / en / ja…") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = DesignTokens.shapeMedium
    )
    Spacer(Modifier.height(DesignTokens.spacing8))
    OutlinedTextField(
        value = preset.targetLanguage.orEmpty(),
        onValueChange = { v -> onUpdate { it.copy(targetLanguage = v.ifBlank { null }) } },
        label = { Text("目标语言") },
        placeholder = { Text("zh / en / ja…") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = DesignTokens.shapeMedium
    )
}

@Composable
private fun BenchmarkPresetFields(
    preset: PresetConfig,
    onUpdate: ((PresetConfig) -> PresetConfig) -> Unit
) {
    OutlinedTextField(
        value = preset.backendName.orEmpty(),
        onValueChange = { v -> onUpdate { it.copy(backendName = v.ifBlank { null }) } },
        label = { Text("后端名") },
        placeholder = { Text("llamaCpp / litert") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = DesignTokens.shapeMedium
    )
    Spacer(Modifier.height(DesignTokens.spacing8))
    NumberTextField(
        label = "线程数",
        placeholder = "0（自动）",
        isInteger = true,
        value = preset.threads?.toString(),
        onValueChange = { v -> onUpdate { it.copy(threads = v?.toInt()) } }
    )
    Spacer(Modifier.height(DesignTokens.spacing8))
    OutlinedTextField(
        value = preset.kvQuant.orEmpty(),
        onValueChange = { v -> onUpdate { it.copy(kvQuant = v.ifBlank { null }) } },
        label = { Text("KV 量化类型") },
        placeholder = { Text("Q8_0 / F16") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = DesignTokens.shapeMedium
    )
}

/**
 * 数字输入框：支持 Float / Int 两种。
 * 空字符串视为 null（用户清空表示用默认）。
 */
@Composable
private fun NumberTextField(
    label: String,
    placeholder: String,
    value: String?,
    isInteger: Boolean = false,
    onValueChange: (Number?) -> Unit
) {
    val keyboardType = if (isInteger) KeyboardType.Number else KeyboardType.Decimal
    OutlinedTextField(
        value = value.orEmpty(),
        onValueChange = { v ->
            val parsed: Number? = if (v.isBlank()) null
            else if (isInteger) v.toIntOrNull()
            else v.toFloatOrNull()
            onValueChange(parsed)
        },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        shape = DesignTokens.shapeMedium
    )
}
