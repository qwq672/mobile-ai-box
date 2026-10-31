package com.aibox.ui.optimization

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
    vm: OptimizationViewModel = viewModel()
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val currentPreset by vm.currentPreset.collectAsStateWithLifecycle()
    val drawerState = com.aibox.ui.nav.LocalDrawerState.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Outlined.Menu, contentDescription = "菜单")
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
            contentPadding = PaddingValues(vertical = DesignTokens.spacing16),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
        ) {
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
                        description = "App 启动时异步加载常用模型，避开用户首次点击对话时的冷启动延迟",
                        value = settings.preloadModel,
                        onValueChange = { v -> vm.update { s -> s.copy(preloadModel = v) } }
                    )
                    SwitchRow(
                        key = "opt.warmupAfterLoad",
                        title = "加载后预热",
                        description = "加载完成后立即跑 1 token 假推理，初始化所有内部状态",
                        value = settings.warmupAfterLoad,
                        onValueChange = { v -> vm.update { s -> s.copy(warmupAfterLoad = v) } }
                    )
                    SwitchRow(
                        key = "opt.preallocateKV",
                        title = "KV cache 预分配",
                        description = "加载时按 contextLength 一次性分配 KV cache，避免逐 token 增长触发 realloc",
                        value = settings.preallocateKV,
                        onValueChange = { v -> vm.update { s -> s.copy(preallocateKV = v) } }
                    )
                    SwitchRow(
                        key = "opt.prebuildSampler",
                        title = "采样器预构造",
                        description = "加载时即构建采样器对象，避免首次推理时重建",
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
                        title = "强制 Flash Attention",
                        description = "Flash Attention 2 / 3；后端不支持则静默忽略",
                        value = settings.forceFlashAttention,
                        onValueChange = { v -> vm.update { s -> s.copy(forceFlashAttention = v) } }
                    )
                    SwitchRow(
                        key = "opt.aggressiveGraphOpt",
                        title = "激进算子融合",
                        description = "启用激进的算子融合 / 常量折叠（增加编译时间换推理速度）",
                        value = settings.aggressiveGraphOpt,
                        onValueChange = { v -> vm.update { s -> s.copy(aggressiveGraphOpt = v) } }
                    )
                    SwitchRow(
                        key = "opt.continuousKVLayout",
                        title = "KV cache 连续布局",
                        description = "使用连续 KV 内存布局（vs 分块），提速但占内存更整块",
                        value = settings.continuousKVLayout,
                        onValueChange = { v -> vm.update { s -> s.copy(continuousKVLayout = v) } }
                    )
                    SwitchRow(
                        key = "opt.reuseSampler",
                        title = "采样器复用",
                        description = "同会话内复用 sampler 实例，避免每个 token 重建",
                        value = settings.reuseSampler,
                        onValueChange = { v -> vm.update { s -> s.copy(reuseSampler = v) } }
                    )
                    SwitchRow(
                        key = "opt.zeroCopyStream",
                        title = "流式输出 zero-copy",
                        description = "流式输出走零拷贝路径（如 mmap 出来的 buffer 直接引用）",
                        value = settings.zeroCopyStream,
                        onValueChange = { v -> vm.update { s -> s.copy(zeroCopyStream = v) } }
                    )
                    SwitchRow(
                        key = "opt.enableGraphCache",
                        title = "推理图缓存",
                        description = "缓存编译后的计算图，加速同模型二次加载",
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
