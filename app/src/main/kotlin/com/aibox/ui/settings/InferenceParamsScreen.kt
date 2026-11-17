package com.aibox.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aibox.domain.inference.BackendType
import com.aibox.domain.inference.InferenceConfig

/**
 * 推理参数与采样（从设置页拆出来的独立子页）。
 *
 * 分组：后端 → 性能 → FlashAttention → 采样。
 * 所有项都直接写回 SettingsRepository；采样是热更新，其余加载时生效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InferenceParamsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel()
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val cfg = ui.config
    val backendKind = when (cfg.backend) {
        BackendType.VULKAN, BackendType.OPENCL -> "gpu"
        BackendType.NPU -> "npu"
        else -> "cpu"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("推理参数与采样") }
            )
        }
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------- 后端 ----------
            GroupCard("后端") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "cpu" to "CPU",
                        "gpu" to "GPU",
                        "npu" to "NPU"
                    ).forEach { (id, label) ->
                        FilterChip(
                            selected = backendKind == id,
                            onClick = { vm.onUnifiedBackendChange(id) },
                            label = { Text(label) }
                        )
                    }
                }
                val gpuEnabled = if (cfg.gpuUseVulkan) ui.vulkanAvailable else ui.openclAvailable
                Text(
                    when (backendKind) {
                        "gpu" -> if (gpuEnabled) "GPU 可用" else "当前 GPU 后端不可用，会自动回退到 CPU"
                        "npu" -> if (ui.npuAvailable) "NPU 可用" else "NPU 需要 Hexagon 专用安装包"
                        else -> "CPU 兼容性最好，速度取决于大核与内存带宽"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (backendKind == "gpu") {
                    SwitchLine(
                        title = "GPU 后端使用 Vulkan",
                        subtitle = "关闭则改用 OpenCL。不同机型两者能效差异较大，建议实测",
                        checked = cfg.gpuUseVulkan,
                        onChange = vm::onGpuUseVulkanChange
                    )
                }
                ValueSlider(
                    title = "GPU/NPU 层数",
                    value = cfg.nGpuLayers.toFloat(),
                    range = 0f..100f,
                    valueText = if (cfg.nGpuLayers == 0) "自动（NPU=全部）" else cfg.nGpuLayers.toString(),
                    onChange = { vm.update(cfg.copy(nGpuLayers = it.toInt())) }
                )
            }

            // ---------- 性能 ----------
            GroupCard("性能") {
                ValueSlider(
                    title = "CPU 线程数",
                    value = cfg.cpuThreads.toFloat(),
                    range = 0f..8f,
                    steps = 7,
                    valueText = if (cfg.cpuThreads == 0) "自动（按大核数）" else cfg.cpuThreads.toString(),
                    onChange = { vm.update(cfg.copy(cpuThreads = it.toInt())) }
                )
                ValueSlider(
                    title = "物理批处理",
                    value = cfg.physicalBatch.toFloat(),
                    range = 64f..4096f,
                    valueText = cfg.physicalBatch.toString(),
                    onChange = { vm.update(cfg.copy(physicalBatch = it.toInt())) }
                )
                ValueSlider(
                    title = "逻辑批处理",
                    value = cfg.batch.toFloat(),
                    range = 256f..8192f,
                    valueText = cfg.batch.toString(),
                    onChange = { vm.update(cfg.copy(batch = it.toInt())) }
                )
                HorizontalDivider()
                Text(
                    "KV Cache 量化",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "f16" to "f16",
                        "q8_0" to "q8_0",
                        "q4_0" to "q4_0"
                    ).forEach { (id, label) ->
                        FilterChip(
                            selected = cfg.kvQuant == id,
                            onClick = { vm.onKvQuantChange(id) },
                            label = { Text(label) }
                        )
                    }
                }
                Text(
                    "量化 KV 需要 FlashAttention 才不会被降级为 f16。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ValueSlider(
                    title = "上下文长度",
                    value = cfg.contextLength.toFloat(),
                    range = 512f..32768f,
                    valueText = cfg.contextLength.toString(),
                    onChange = { vm.update(cfg.copy(contextLength = it.toInt())) }
                )
            }

            // ---------- FlashAttention ----------
            GroupCard("FlashAttention（加载时生效）") {
                SwitchLine(
                    title = "启用 FlashAttention",
                    subtitle = "降低注意力显存占用、提升长上下文速度；非 Transformer 模型会被自动跳过",
                    checked = cfg.flashAttention,
                    onChange = vm::onFlashAttentionChange
                )
                HorizontalDivider()
                SwitchLine(
                    title = "强制开启",
                    subtitle = "即使后端判断不支持也尝试开启；可能导致部分模型输出异常，默认关闭",
                    checked = cfg.forceFlashAttention,
                    onChange = vm::onForceFlashAttentionChange
                )
                Text(
                    "当前实际：${ui.runtimeModel.ifBlank { "未加载" }} · FA " +
                        (if (ui.effectiveFlashAttn) "开" else "关") +
                        " · KV ${ui.requestedKv} → ${ui.effectiveKv}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ---------- 采样 ----------
            GroupCard("采样（即时生效）") {
                ValueSlider(
                    title = "Temperature",
                    value = cfg.temperature,
                    range = 0.01f..2f,
                    valueText = "%.2f".format(cfg.temperature),
                    onChange = { vm.update(cfg.copy(temperature = it)) }
                )
                ValueSlider(
                    title = "Top-K",
                    value = cfg.topK.toFloat(),
                    range = 1f..200f,
                    valueText = cfg.topK.toString(),
                    onChange = { vm.update(cfg.copy(topK = it.toInt())) }
                )
                ValueSlider(
                    title = "Top-P",
                    value = cfg.topP,
                    range = 0.1f..1f,
                    valueText = "%.2f".format(cfg.topP),
                    onChange = { vm.update(cfg.copy(topP = it)) }
                )
                ValueSlider(
                    title = "Min-P",
                    value = cfg.minP,
                    range = 0f..0.5f,
                    valueText = if (cfg.minP <= 0f) "关闭" else "%.2f".format(cfg.minP),
                    onChange = { vm.update(cfg.copy(minP = it)) }
                )
                ValueSlider(
                    title = "Repeat Penalty",
                    value = cfg.repeatPenalty,
                    range = 1f..2f,
                    valueText = "%.2f".format(cfg.repeatPenalty),
                    onChange = { vm.update(cfg.copy(repeatPenalty = it)) }
                )
                ValueSlider(
                    title = "Repeat 窗口",
                    value = cfg.repeatLastN.toFloat(),
                    range = 32f..1024f,
                    valueText = "${cfg.repeatLastN} tokens",
                    onChange = { vm.update(cfg.copy(repeatLastN = it.toInt())) }
                )
                Text(
                    "推荐：temp 0.6–0.8 · top_p 0.95 · min_p 0.05 · repeat 1.1 / 256。" +
                        "小模型不要为了“稳”把温度压到 0，会变僵。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun GroupCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}

@Composable
private fun SwitchLine(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ValueSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    steps: Int = 0,
    onChange: (Float) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps
        )
    }
}
