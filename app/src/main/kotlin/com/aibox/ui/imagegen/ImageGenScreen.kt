package com.aibox.ui.imagegen

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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aibox.domain.module.DiscoveredModule
import com.aibox.domain.module.ModuleDiscoveryService
import com.aibox.domain.module.ModuleType
import com.aibox.infra.plugin.FieldType
import com.aibox.infra.plugin.SettingField
import com.aibox.infra.plugin.SettingOption
import com.aibox.ui.components.unified.SettingsListRenderer
import com.aibox.ui.components.unified.UnifiedCard
import com.aibox.ui.components.unified.UnifiedCardTone
import com.aibox.ui.components.unified.UnifiedSection
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.DesignTokens
import com.aibox.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * 图像生成页框架（参照 Local Dream 的能力模型）。
 *
 * 本轮范围：宿主侧框架 + 契约 + 模块发现 + 参数表单 + 队列/历史占位。
 * 推理后端留成可插拔接口——检测到 IMAGE_GEN_MODULE 时显示"模块已就绪"，
 * 但宿主调用真实生图（QNN/MNN/txt2img）尚未实现，不出真图。
 *
 * 能力模型（对齐 Local Dream + feature/image 契约）：
 * - 生成模式：文生图 txt2img / 图生图 img2img / 局部重绘 inpaint
 * - 增强：超分 Real-ESRGAN / 擦除（占位）
 * - 参数：seed / steps / CFG / 尺寸 / 采样器 / clip skip / denoise
 * - 后端能力：NPU 加速（骁龙 Hexagon）/ CPU（MNN）/ GPU（占位）
 * - 内容过滤：NSFW 开关（默认开，隐私友好）
 *
 * 所有颜色 / 字体 / 间距走 [Colors] / [Type] / [DesignTokens]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageGenScreen(
    onBack: () -> Unit,
    vm: ImageGenViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("图像生成", style = Type.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.rescan() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "重新扫描模块")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = DesignTokens.spacing16,
                vertical = DesignTokens.spacing8,
            ),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing12),
        ) {
            // ===== 模块状态 =====
            item {
                ModuleStatusCard(
                    module = ui.module,
                    scanning = ui.scanning,
                    onRescan = { vm.rescan() },
                )
            }

            // ===== 生成模式（txt2img / img2img / inpaint / 超分） =====
            item {
                UnifiedSection(title = "生成模式", showTopDivider = false) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = DesignTokens.spacing16),
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8),
                    ) {
                        ImageGenMode.entries.forEach { mode ->
                            FilterChip(
                                selected = ui.mode == mode,
                                onClick = { vm.setMode(mode) },
                                label = { Text(mode.label) },
                            )
                        }
                    }
                    Text(
                        text = ui.mode.hint,
                        style = Type.bodySmall,
                        color = Colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = DesignTokens.spacing16, vertical = DesignTokens.spacing4),
                    )
                }
            }

            // ===== 参数表单 =====
            item {
                UnifiedSection(title = "生成参数") {
                    SettingsListRenderer(
                        fields = buildParameterFields(ui.mode),
                        currentValues = buildCurrentValues(ui),
                        onValueChange = { key, value -> vm.onParamChange(key, value) },
                    )
                }
            }

            // ===== 推理后端（A 宿主 / B 生图 / C 依赖框架 三层关系） =====
            item {
                UnifiedSection(title = "推理后端与依赖") {
                    Column(modifier = Modifier.padding(horizontal = DesignTokens.spacing16)) {
                        Text(
                            text = "生图由「B 生图模块」执行，宿主 A 只提供入口与参数；不同后端的前置依赖不同。",
                            style = Type.bodySmall,
                            color = Colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(DesignTokens.spacing8))
                        ImageBackend.entries.forEach { backend ->
                            val selected = ui.backend == backend
                            val depMissing = backend == ImageBackend.CPU_GPU_MNN && ui.mnnModule == null
                            BackendChoiceCard(
                                label = backend.label,
                                shortDesc = backend.shortDesc,
                                supportedSd = backend.supportedSd,
                                dependencyLabel = backend.dependencyLabel,
                                depMissing = depMissing,
                                selected = selected,
                                onClick = { vm.setBackend(backend) },
                            )
                            Spacer(Modifier.size(DesignTokens.spacing8))
                        }
                        Text(
                            text = "B1（NPU）自带 QNN 2.28 运行时（so 内置于生图模块），支持 SD1.5 与 SDXL；" +
                                "B2（CPU/GPU）依赖 C=MNN 推理框架模块，仅 SD1.5，缺 C 时该后端不可用。" +
                                "依赖为必选，未装不会自动下载，需你确认后安装。",
                            style = Type.labelSmall,
                            color = Colors.onSurfaceVariant,
                        )
                    }
                }
            }

            // ===== 生成队列 / 历史（占位） =====
            item {
                UnifiedSection(title = "生成队列与历史") {
                    Column(modifier = Modifier.padding(horizontal = DesignTokens.spacing16)) {
                        if (ui.jobs.isEmpty()) {
                            Text(
                                text = "暂无任务。安装生图模块后，生成任务与历史会显示在这里。",
                                style = Type.bodyMedium,
                                color = Colors.onSurfaceVariant,
                            )
                        } else {
                            ui.jobs.forEach { job ->
                                Text(
                                    text = job,
                                    style = Type.bodySmall,
                                    color = Colors.onSurface,
                                    modifier = Modifier.padding(vertical = DesignTokens.spacing4),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModuleStatusCard(
    module: DiscoveredModule?,
    scanning: Boolean,
    onRescan: () -> Unit,
) {
    UnifiedCard(
        tone = if (module != null) UnifiedCardTone.PRIMARY_CONTAINER else UnifiedCardTone.SURFACE,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing12),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = if (module != null) Colors.onPrimaryContainer else Colors.onSurfaceVariant,
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = when {
                        scanning -> "正在扫描生图模块…"
                        module != null -> "已检测到：${module.displayName}"
                        else -> "尚未安装图像生成模块"
                    },
                    style = Type.titleMedium,
                    color = if (module != null) Colors.onPrimaryContainer else Colors.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when {
                        module != null -> "${module.version} · ${module.packageName} · 宿主调用开发中"
                        else -> "请到「模块管理」安装 ImageGen 模块 APK（action: IMAGE_GEN_MODULE）"
                    },
                    style = Type.bodySmall,
                    color = if (module != null) Colors.onPrimaryContainer else Colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** 生成模式，对齐 Local Dream / feature/image 契约的四类能力。 */
enum class ImageGenMode(val label: String, val hint: String) {
    TXT2IMG("文生图", "输入提示词生成图片。参数含 seed / steps / CFG / 尺寸 / 采样器。"),
    IMG2IMG("图生图", "以一张参考图重绘，可调 denoise 强度保留构图。"),
    INPAINT("局部重绘", "涂抹蒙版区域重绘，用于去除物体或替换背景。"),
    UPSCALE("超分放大", "Real-ESRGAN 放大 2x/4x，可叠加面部增强（占位）。"),
}

private fun buildParameterFields(mode: ImageGenMode): List<SettingField> = buildList {
    add(
        SettingField(
            key = "ig.seed",
            type = FieldType.INPUT,
            title = "随机种子 (seed)",
            description = "-1 表示随机；相同 seed + 相同参数可复现结果",
            defaultValue = "-1",
            keyboardHint = "number",
        )
    )
    add(
        SettingField(
            key = "ig.steps",
            type = FieldType.SLIDER,
            title = "采样步数 (steps)",
            description = "步数越多越精细但越慢；默认 20",
            defaultValue = 20f,
            min = 1f, max = 50f, step = 1f,
        )
    )
    add(
        SettingField(
            key = "ig.cfg",
            type = FieldType.SLIDER,
            title = "提示词引导 (CFG scale)",
            description = "越高越贴合提示词；默认 7.0",
            defaultValue = 7.0f,
            min = 1f, max = 20f, step = 0.5f,
        )
    )
    add(
        SettingField(
            key = "ig.size",
            type = FieldType.DROPDOWN,
            title = "输出尺寸",
            description = "NPU 多固定 512×512；CPU 支持灵活尺寸",
            defaultValue = "512x512",
            options = listOf(
                SettingOption("256x256", "256 × 256"),
                SettingOption("384x384", "384 × 384"),
                SettingOption("512x512", "512 × 512（推荐）"),
            ),
        )
    )
    add(
        SettingField(
            key = "ig.sampler",
            type = FieldType.DROPDOWN,
            title = "采样器",
            description = "由生图模块决定实际支持列表",
            defaultValue = "euler_a",
            options = listOf(
                SettingOption("euler_a", "Euler a"),
                SettingOption("euler", "Euler"),
                SettingOption("ddim", "DDIM"),
                SettingOption("dpmpp_2m", "DPM++ 2M"),
            ),
        )
    )
    if (mode == ImageGenMode.IMG2IMG || mode == ImageGenMode.INPAINT) {
        add(
            SettingField(
                key = "ig.denoise",
                type = FieldType.SLIDER,
                title = "重绘强度 (denoise)",
                description = "0 保留原图，1 完全重画；默认 0.6",
                defaultValue = 0.6f,
                min = 0f, max = 1f, step = 0.05f,
            )
        )
    }
    add(
        SettingField(
            key = "ig.style",
            type = FieldType.DROPDOWN,
            title = "模型风格预设",
            description = "生图受众以二次元为主，预设默认二次元；具体模型由生图模块提供",
            defaultValue = "anime",
            options = listOf(
                SettingOption("anime", "二次元 / 动漫（推荐）"),
                SettingOption("illustration", "插画 / 厚涂"),
                SettingOption("realistic", "写实 / 真人"),
                SettingOption("pixel", "像素风"),
                SettingOption("3d", "3D / 卡通渲染"),
            ),
        )
    )
    add(
        SettingField(
            key = "ig.nsfw",
            type = FieldType.SWITCH,
            title = "NSFW 内容过滤",
            description = "默认开启，避免生成不当内容（Local Dream 默认内置过滤）",
            defaultValue = true,
        )
    )
}

/** 后端选择卡：选中态用 PRIMARY_CONTAINER，B2 缺依赖时整体置灰但仍可点选查看说明。 */
@Composable
private fun BackendChoiceCard(
    label: String,
    shortDesc: String,
    supportedSd: String,
    dependencyLabel: String,
    depMissing: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tone = if (selected) UnifiedCardTone.PRIMARY_CONTAINER else UnifiedCardTone.SURFACE
    val contentColor = if (selected) Colors.onPrimaryContainer else Colors.onSurface
    val secondaryColor = if (selected) Colors.onPrimaryContainer else Colors.onSurfaceVariant
    UnifiedCard(
        tone = tone,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (depMissing && !selected) 0.55f else 1f)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = DesignTokens.spacing16, vertical = DesignTokens.spacing12)) {
            Text(text = label, style = Type.titleMedium, color = contentColor, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(DesignTokens.spacing4))
            Text(text = shortDesc, style = Type.bodySmall, color = secondaryColor)
            Spacer(Modifier.size(DesignTokens.spacing4))
            Text(text = "模型：$supportedSd　·　依赖：$dependencyLabel", style = Type.labelSmall, color = secondaryColor)
            if (depMissing) {
                Spacer(Modifier.size(DesignTokens.spacing4))
                Text(text = "⚠ 缺少 C=MNN 框架模块，选此后端前需先安装（必选依赖）", style = Type.labelSmall, color = Colors.error)
            }
        }
    }
}

private fun buildCurrentValues(ui: ImageGenViewModel.UiState): Map<String, Any> = ui.params
