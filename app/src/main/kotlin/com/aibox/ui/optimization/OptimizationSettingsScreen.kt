@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aibox.ui.optimization

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aibox.infra.plugin.FieldType
import com.aibox.infra.plugin.SettingField
import com.aibox.infra.plugin.SettingOption
import com.aibox.ui.components.unified.UnifiedDropdown
import com.aibox.ui.components.unified.UnifiedSection
import com.aibox.ui.components.unified.UnifiedSlider
import com.aibox.ui.components.unified.UnifiedSwitch
import com.aibox.ui.theme.DesignTokens
import com.aibox.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * 推理优化设置页（P3）。
 *
 * 布局：
 * 1. TopAppBar：标题 "推理优化" + 汉堡菜单
 * 2. 顶部 Section "优化预置"：UnifiedDropdown（默认 / 性能优先 / 省电模式 / 自定义）
 * 3. Section "TTFT 优化（首 token 延迟）"：5 个 UnifiedSwitch
 * 4. Section "TG 优化（生成速度）"：6 个 UnifiedSwitch
 * 5. Section "能耗优化"：3 个 UnifiedSwitch + 1 个 UnifiedSlider（idleUnloadTimeoutMs）
 * 6. 底部："重置为默认" 按钮（OutlinedButton 样式）
 *
 * 设计原则：
 * - 所有颜色 / 字体 / 间距走 [com.aibox.ui.theme.Colors] / [Type] / [DesignTokens]；不硬编码字面量。
 * - 所有开关 / 下拉 / 滑块用 P0.2 统一组件库（[UnifiedSwitch] / [UnifiedDropdown] / [UnifiedSlider]）。
 * - 每个 [SettingField] 用全局唯一 key（如 `opt.preloadModel`），便于以后单独导出 / migrate。
 * - 通用 UI：不针对任何特定模型；Qwen / LFM / Hunyuan / SmolLM 用户看到的设置项完全一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptimizationSettingsScreen(
    onBack: () -> Unit,
    vm: OptimizationViewModel = viewModel()
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val currentPreset by vm.currentPreset.collectAsStateWithLifecycle()
    val runtime by vm.runtime.collectAsStateWithLifecycle()
    val powerStatus by vm.powerStatus.collectAsStateWithLifecycle()
    val powerMode by vm.powerMode.collectAsStateWithLifecycle()
    val effectiveHtp by vm.effectiveHtp.collectAsStateWithLifecycle()
    val warnLow by vm.warnLowBattery.collectAsStateWithLifecycle()
    val warnCritical by vm.warnCriticalBattery.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("推理优化", style = Type.titleLarge) }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(
                horizontal = DesignTokens.spacing4,
                vertical = DesignTokens.spacing16
            ),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing12)
        ) {
            // ============== 顶部：当前实际生效 ==============
            item {
                UnifiedSection(title = "当前生效（实际）", showTopDivider = false) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.padding(horizontal = DesignTokens.spacing16),
                        verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
                    ) {
                        Text(
                            "模型：${runtime.model.ifBlank { "未加载" }}",
                            style = Type.bodyMedium
                        )
                        Text(
                            "后端：${runtime.backend.ifBlank { "--" }} · 线程：${if (runtime.threads > 0) runtime.threads else "--"} · 上下文：${if (runtime.contextMax > 0) runtime.contextMax else "--"}",
                            style = Type.bodySmall,
                            color = com.aibox.ui.theme.Colors.onSurfaceVariant
                        )
                        Text(
                            "KV：${runtime.kv} · FlashAttention：${if (runtime.flashAttn) "开" else "关"}",
                            style = Type.bodySmall,
                            color = com.aibox.ui.theme.Colors.onSurfaceVariant
                        )
                        Text(
                            "标有「加载时生效」的开关需要重新加载模型才会应用；其余开关即时生效。",
                            style = Type.labelSmall,
                            color = com.aibox.ui.theme.Colors.onSurfaceVariant,
                            modifier = Modifier.padding(top = DesignTokens.spacing4)
                        )
                    }
                }
            }

            // ============== 电量与后端性能档 ==============
            item {
                UnifiedSection(title = "电量与性能档", showTopDivider = false) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.padding(horizontal = DesignTokens.spacing16),
                        verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
                    ) {
                        val levelText = if (powerStatus.levelKnown) "${powerStatus.level}%" else "未知"
                        Text(
                            "电量：$levelText" +
                                (if (powerStatus.charging) "（充电中，不提示低电量）" else "") +
                                " · 当前档位：${powerMode.label}",
                            style = Type.bodyMedium
                        )
                        Text(
                            "HTP 实际档位：${com.aibox.domain.power.HtpPerfMode.label(effectiveHtp)}" +
                                if (settings.htpPerfMode == com.aibox.domain.power.HtpPerfMode.AUTO) "（自动解析）" else "（手动）",
                            style = Type.bodySmall,
                            color = com.aibox.ui.theme.Colors.onSurfaceVariant
                        )
                        androidx.compose.foundation.layout.FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
                        ) {
                            com.aibox.domain.power.HtpPerfMode.all.forEach { id ->
                                FilterChip(
                                    selected = settings.htpPerfMode == id,
                                    onClick = { vm.setHtpPerfMode(id) },
                                    label = {
                                        Text(com.aibox.domain.power.HtpPerfMode.label(id))
                                    }
                                )
                            }
                        }
                        Text(
                            "手动选择性能优先时，低电量只会提示，不会自动降档；充电中不提示。" +
                                "性能优先会让首 token 更快，但长文生成更容易发热降速；低电量时可能加快掉电。" +
                                "当前版本通过批大小近似该档位，接入厂商性能接口后会直接下发给 DSP。",
                            style = Type.labelSmall,
                            color = com.aibox.ui.theme.Colors.onSurfaceVariant
                        )

                        // ---- 虚拟 NPU 会话（大模型加载，Hexagon 专用）----
                        HorizontalDivider(
                            Modifier.padding(vertical = DesignTokens.spacing8)
                        )
                        Text(
                            "NPU 虚拟设备 / 分片（大模型加载）",
                            style = Type.titleSmall,
                            color = com.aibox.ui.theme.Colors.onSurface
                        )
                        androidx.compose.foundation.layout.FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
                        ) {
                            com.aibox.domain.power.HtpSessionMode.all.forEach { id ->
                                FilterChip(
                                    selected = settings.htpSessionMode == id,
                                    onClick = { vm.setHtpSessionMode(id) },
                                    label = {
                                        Text(com.aibox.domain.power.HtpSessionMode.label(id))
                                    }
                                )
                            }
                        }
                        if (settings.htpSessionMode == com.aibox.domain.power.HtpSessionMode.MANUAL) {
                            UnifiedSlider(
                                field = SettingField(
                                    key = "opt.htpSessionCount",
                                    type = FieldType.SLIDER,
                                    title = "虚拟设备数量",
                                    description = "手动指定虚拟设备/分片数；越多能装下越大的模型，但跨设备访问开销更高",
                                    defaultValue = settings.htpSessionCount,
                                    min = 1,
                                    max = 8,
                                    step = 1
                                ),
                                value = settings.htpSessionCount.toFloat(),
                                onValueChange = { vm.setHtpSessionCount(it.toInt()) }
                            )
                        }
                        Text(
                            "这里的“虚拟设备/分片”是把 Hexagon 的 IOVA 地址账本拆开，用于加载更大的模型；" +
                                "它不是对话上下文窗口，不会增加单次可读的上下文长度。" +
                                "自动：按模型体积决定分片数（>3.5GB 自动拆分，推荐）；关闭：只用单个设备。" +
                                "加载时生效，改完需重新加载模型。仅 Hexagon NPU 后端有效。",
                            style = Type.labelSmall,
                            color = com.aibox.ui.theme.Colors.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(
                        Modifier.padding(
                            horizontal = DesignTokens.spacing16,
                            vertical = DesignTokens.spacing8
                        )
                    )

                    UnifiedSwitch(
                        field = SettingField(
                            key = "opt.lowPowerMode",
                            type = FieldType.SWITCH,
                            title = "低电量模式",
                            description = "电量低于阈值时提示，并在下次加载模型时缩小批大小；不会擅自改你手动选的 HTP 档",
                            defaultValue = settings.lowPowerMode
                        ),
                        value = settings.lowPowerMode,
                        onValueChange = vm::setLowPowerMode
                    )
                    UnifiedSlider(
                        field = SettingField(
                            key = "opt.lowPowerThreshold",
                            type = FieldType.SLIDER,
                            title = "低电量阈值",
                            description = "电量低于该值时进入省电档；≤5% 为极低电量",
                            defaultValue = settings.lowPowerThreshold,
                            min = 5,
                            max = 50,
                            step = 1
                        ),
                        value = settings.lowPowerThreshold.toFloat(),
                        onValueChange = { vm.setLowPowerThreshold(it.toInt()) },
                        modifier = Modifier.padding(horizontal = DesignTokens.spacing16)
                    )
                    UnifiedSwitch(
                        field = SettingField(
                            key = "opt.warnLowBattery",
                            type = FieldType.SWITCH,
                            title = "低电量提示",
                            description = "手动选择性能优先且电量偏低时，提示一次（可在这里永久关闭）",
                            defaultValue = warnLow
                        ),
                        value = warnLow,
                        onValueChange = vm::setWarnLowBattery
                    )
                    UnifiedSwitch(
                        field = SettingField(
                            key = "opt.warnCriticalBattery",
                            type = FieldType.SWITCH,
                            title = "极低电量警告",
                            description = "电量 ≤5% 且使用 Burst 时警告一次；关闭后不再打扰",
                            defaultValue = warnCritical
                        ),
                        value = warnCritical,
                        onValueChange = vm::setWarnCriticalBattery
                    )
                }
            }

            // ============== 顶部：优化预置 ==============
            item {
                UnifiedSection(title = "优化预置", showTopDivider = false) {
                    UnifiedDropdown(
                        field = SettingField(
                            key = "opt.preset",
                            type = FieldType.DROPDOWN,
                            title = "优化预设",
                            description = "一键切换优化模式；手动改任何开关后会自动切到「自定义」",
                            defaultValue = "default",
                            options = listOf(
                                SettingOption("default", "默认（均衡）"),
                                SettingOption("performance", "性能优先"),
                                SettingOption("power_save", "省电模式"),
                                SettingOption("custom", "自定义")
                            )
                        ),
                        value = currentPreset,
                        onValueChange = { vm.applyPreset(it) }
                    )
                }
            }

            // ============== TTFT 优化 ==============
            item {
                UnifiedSection(title = "TTFT 优化（首 token 延迟）") {
                    SwitchRow(
                        key = "opt.preloadModel",
                        title = "模型预加载",
                        description = "App 启动时异步加载常用模型，避开首次点击对话时的冷启动延迟。默认关闭；开启后启动即加载本地权重，会增加发热与耗电",
                        value = settings.preloadModel,
                        onValueChange = { v -> vm.update { s -> s.copy(preloadModel = v) } }
                    )
                    SwitchRow(
                        key = "opt.warmupAfterLoad",
                        title = "加载后预热",
                        description = "加载完成后立即跑 1 token 假推理，初始化所有内部状态。默认关闭；开启后每次加载模型都会额外发热",
                        value = settings.warmupAfterLoad,
                        onValueChange = { v -> vm.update { s -> s.copy(warmupAfterLoad = v) } }
                    )
                    SwitchRow(
                        key = "opt.preallocateKV",
                        title = "KV cache 预分配（加载时生效）",
                        description = "按 contextLength 一次性分配 KV cache；llama.cpp 后端本身即一次性分配，" +
                            "该开关主要预留给插件后端",
                        value = settings.preallocateKV,
                        onValueChange = { v -> vm.update { s -> s.copy(preallocateKV = v) } }
                    )
                    SwitchRow(
                        key = "opt.prebuildSampler",
                        title = "采样器预构造（加载时生效）",
                        description = "加载时预构建采样器；当前 llama.cpp 路径每轮自行构建，该开关暂未消费",
                        value = settings.prebuildSampler,
                        onValueChange = { v -> vm.update { s -> s.copy(prebuildSampler = v) } }
                    )
                    SwitchRow(
                        key = "opt.streamFirstTokenImmediately",
                        title = "首 token 立即返回",
                        description = "流式输出首 token 一就绪就 push，不等批次攒批",
                        value = settings.streamFirstTokenImmediately,
                        onValueChange = { v -> vm.update { s -> s.copy(streamFirstTokenImmediately = v) } }
                    )
                }
            }

            // ============== TG 优化 ==============
            item {
                UnifiedSection(title = "TG 优化（生成速度）") {
                    SwitchRow(
                        key = "opt.forceFlashAttention",
                        title = "强制 Flash Attention（加载时生效）",
                        description = "仅 Transformer 模型有效；打开后量化 KV（q8_0/q4_0）才不会被降级为 f16。" +
                            "非 Transformer（Mamba/RWKV）会被强制关闭",
                        value = settings.forceFlashAttention,
                        onValueChange = { v -> vm.update { s -> s.copy(forceFlashAttention = v) } }
                    )
                    SwitchRow(
                        key = "opt.aggressiveGraphOpt",
                        title = "激进算子融合（加载时生效）",
                        description = "算子融合 / 常量折叠；当前 llama.cpp 后端暂未消费，预留给插件后端",
                        value = settings.aggressiveGraphOpt,
                        onValueChange = { v -> vm.update { s -> s.copy(aggressiveGraphOpt = v) } }
                    )
                    SwitchRow(
                        key = "opt.continuousKVLayout",
                        title = "KV cache 连续布局",
                        description = "连续 KV 内存布局；当前 llama.cpp 后端暂未消费，预留给插件后端",
                        value = settings.continuousKVLayout,
                        onValueChange = { v -> vm.update { s -> s.copy(continuousKVLayout = v) } }
                    )
                    SwitchRow(
                        key = "opt.reuseSampler",
                        title = "采样器复用",
                        description = "同会话复用 sampler；当前 llama.cpp 路径每轮构建，暂未消费",
                        value = settings.reuseSampler,
                        onValueChange = { v -> vm.update { s -> s.copy(reuseSampler = v) } }
                    )
                    SwitchRow(
                        key = "opt.zeroCopyStream",
                        title = "流式输出 zero-copy",
                        description = "零拷贝流式输出；当前 llama.cpp 后端暂未消费，预留给插件后端",
                        value = settings.zeroCopyStream,
                        onValueChange = { v -> vm.update { s -> s.copy(zeroCopyStream = v) } }
                    )
                    SwitchRow(
                        key = "opt.enableGraphCache",
                        title = "推理图缓存（加载时生效）",
                        description = "缓存编译后的计算图；当前 llama.cpp 后端暂未消费，预留给插件后端",
                        value = settings.enableGraphCache,
                        onValueChange = { v -> vm.update { s -> s.copy(enableGraphCache = v) } }
                    )
                }
            }

            // ============== 能耗优化 ==============
            item {
                UnifiedSection(title = "能耗优化") {
                    SwitchRow(
                        key = "opt.thermalAwareThrottle",
                        title = "散热感知降频",
                        description = "温度过高时主动通知后端降低 batch / 线程数（由后端自行实现降档）",
                        value = settings.thermalAwareThrottle,
                        onValueChange = { v -> vm.update { s -> s.copy(thermalAwareThrottle = v) } }
                    )
                    SwitchRow(
                        key = "opt.idleUnloadTimeout",
                        title = "空闲超时卸载",
                        description = "App 长时间未推理时自动卸载模型省电",
                        value = settings.idleUnloadTimeout,
                        onValueChange = { v -> vm.update { s -> s.copy(idleUnloadTimeout = v) } }
                    )
                    UnifiedSlider(
                        field = SettingField(
                            key = "opt.idleUnloadTimeoutMs",
                            type = FieldType.SLIDER,
                            title = "空闲超时时长",
                            description = "多久无推理后卸载模型（分钟）",
                            defaultValue = 10f,
                            min = 1f,
                            max = 30f,
                            step = 1f
                        ),
                        value = (settings.idleUnloadTimeoutMs / 60_000f).coerceIn(1f, 30f),
                        onValueChange = { minutes ->
                            vm.update { s -> s.copy(idleUnloadTimeoutMs = (minutes * 60_000f).toLong()) }
                        }
                    )
                    SwitchRow(
                        key = "opt.lowPowerOnBattery",
                        title = "电池模式低功耗",
                        description = "电池供电时进入低功耗档（仅提示，不强制）；降低线程数 / 关闭 GPU",
                        value = settings.lowPowerOnBattery,
                        onValueChange = { v -> vm.update { s -> s.copy(lowPowerOnBattery = v) } }
                    )
                }
            }

            // ============== 底部：重置按钮 ==============
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = DesignTokens.spacing16,
                            vertical = DesignTokens.spacing16
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Button(
                        onClick = {
                            vm.resetToDefault()
                            scope.launch { snackbarHost.showSnackbar("已重置为默认优化设置") }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.padding(end = DesignTokens.spacing8)
                        )
                        Text("重置为默认")
                    }
                }
            }
        }
    }
}

/**
 * 单个开关行的便捷封装：内部用 [UnifiedSwitch] 渲染 [SettingField]。
 *
 * 提取这个 helper 是因为本页有 14+ 个开关，每个 SettingField 都要构造太冗长；
 * 用一行参数（key / title / description / value / onValueChange）即可。
 */
@Composable
private fun SwitchRow(
    key: String,
    title: String,
    description: String,
    value: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    UnifiedSwitch(
        field = SettingField(
            key = key,
            type = FieldType.SWITCH,
            title = title,
            description = description,
            defaultValue = false
        ),
        value = value,
        onValueChange = onValueChange
    )
}
