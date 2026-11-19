package com.aibox.ui.benchmark

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aibox.infra.plugin.FieldType
import com.aibox.infra.plugin.SettingField
import com.aibox.infra.plugin.SettingOption
import com.aibox.infra.benchmark.BenchmarkMode
import com.aibox.infra.benchmark.BenchmarkReport
import com.aibox.infra.benchmark.BenchmarkResult
import com.aibox.infra.benchmark.BenchmarkSample
import com.aibox.infra.benchmark.Stats
import com.aibox.data.db.BenchmarkResultEntity
import com.aibox.ui.components.unified.SettingsListRenderer
import com.aibox.ui.components.unified.UnifiedCard
import com.aibox.ui.components.unified.UnifiedCardTone
import com.aibox.ui.components.unified.UnifiedDropdown
import com.aibox.ui.components.unified.UnifiedSection
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.DesignTokens
import com.aibox.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * 基准测试页（P2）。
 *
 * 视觉布局（顶到底）：
 * 1. TopAppBar：标题 "基准测试" + 汉堡菜单
 * 2. Section "模型选择"：UnifiedDropdown（单选，已下载模型）
 * 3. Section "后端选择"：FilterChip 多选（已注册 V2 后端）
 * 4. Section "测试矩阵"：SettingsListRenderer（矩阵选择 + 重复次数 / 预热 / 线程 / KV 覆盖）
 * 5. 运行按钮：UnifiedCard 风格的 clickable Card
 * 6. 运行中：LinearProgressIndicator + 当前任务文本
 * 7. 最新结果：CardListTemplate 风格，每个 [BenchmarkResult] 一张 UnifiedCard
 * 8. 高级结果（如已运行高级矩阵）：单独卡片显示冷启动 / 多轮 / 平滑度
 * 9. 历史：BenchmarkResultEntity 列表
 *
 * 所有颜色 / 字体 / 间距走 [Colors] / [Type] / [DesignTokens]；不硬编码字面量。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(
    onBack: (() -> Unit)? = null,
    onOpenAutoTune: (() -> Unit)? = null,
    vm: BenchmarkViewModel = viewModel()
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val drawerState = com.aibox.ui.nav.LocalDrawerState.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    // 错误 / 信息消息用 snackbar
    LaunchedEffect(ui.errorMessage) {
        ui.errorMessage?.let { snackbarHost.showSnackbar(it); vm.dismissError() }
    }
    LaunchedEffect(ui.infoMessage) {
        ui.infoMessage?.let { snackbarHost.showSnackbar(it); vm.dismissInfo() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (onBack != null) {
                        // 从 Home 进入时显示返回按钮
                        IconButton(onClick = onBack) {
                            Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    } else {
                        // 直接启动时显示汉堡菜单
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Outlined.Menu, contentDescription = "菜单")
                        }
                    }
                },
                title = { Text("基准测试", style = Type.titleLarge) },
                actions = {
                    if (onOpenAutoTune != null) {
                        IconButton(onClick = onOpenAutoTune) {
                            Icon(Icons.Outlined.Tune, contentDescription = "自动调优")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = DesignTokens.spacing16,
                vertical = DesignTokens.spacing8
            ),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing12)
        ) {
            // ===== Section 1: 模型选择 =====
            item {
                UnifiedSection(title = "模型选择", showTopDivider = false) {
                    ModelSelectionField(
                        models = ui.models,
                        selectedPath = ui.selectedModelPath,
                        onSelect = vm::selectModel
                    )
                }
            }

            // ===== Section 2: 后端选择（多选） =====
            item {
                UnifiedSection(title = "后端选择（多选）") {
                    if (ui.backends.isEmpty()) {
                        Text(
                            text = "尚未注册任何 V2 后端。请先在设置页启用插件，或等待内置 V2 适配器。",
                            style = Type.bodyMedium,
                            color = Colors.onSurfaceVariant,
                            modifier = Modifier.padding(DesignTokens.spacing16)
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = DesignTokens.spacing16),
                            horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
                        ) {
                            ui.backends.forEach { desc ->
                                val name = desc.getName()
                                FilterChip(
                                    selected = name in ui.selectedBackendNames,
                                    onClick = { vm.toggleBackend(name) },
                                    label = { Text(desc.getDisplayName()) }
                                )
                            }
                        }
                    }
                }
            }

            // ===== Section 2.5: 模式切换（基础 / 高级） =====
            item {
                UnifiedSection(title = "测试模式") {
                    Text(
                        text = "基础：跑一次看汇总数字，结果存入历史。高级：边推理边采样，绘制 6 条性能曲线并给出波动统计（耗时更长）。",
                        style = Type.bodySmall,
                        color = Colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = DesignTokens.spacing16, vertical = DesignTokens.spacing4)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = DesignTokens.spacing16),
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
                    ) {
                        FilterChip(
                            selected = ui.mode == BenchmarkMode.BASIC,
                            onClick = { vm.setMode(BenchmarkMode.BASIC) },
                            enabled = !ui.running,
                            label = { Text("基础") },
                        )
                        FilterChip(
                            selected = ui.mode == BenchmarkMode.ADVANCED,
                            onClick = { vm.setMode(BenchmarkMode.ADVANCED) },
                            enabled = !ui.running,
                            label = { Text("高级 · 6 曲线") },
                        )
                    }
                }
            }

            // ===== Section 3: 测试参数（自定义 PP / TG） =====
            item {
                UnifiedSection(title = "测试参数") {
                    Text(
                        text = "只做一次基础测量：用当前推理配置跑一遍，不做场景矩阵、不做跨后端对比。",
                        style = Type.bodySmall,
                        color = Colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = DesignTokens.spacing16, vertical = DesignTokens.spacing4)
                    )
                    val fields = buildParameterSettings()
                    val currentValues = buildCurrentValues(ui)
                    SettingsListRenderer(
                        fields = fields,
                        currentValues = currentValues,
                        onValueChange = { key, value -> handleSettingChange(key, value, vm) }
                    )
                }
            }

            // ===== Section 4: 运行按钮 + 进度 =====
            item {
                UnifiedCard(
                    tone = if (ui.running) UnifiedCardTone.SURFACE_LOW else UnifiedCardTone.PRIMARY_CONTAINER,
                    onClick = if (ui.running) null else ({ vm.run() }),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing12)
                    ) {
                        Icon(
                            imageVector = if (ui.running) Icons.Outlined.Speed else Icons.Outlined.PlayArrow,
                            contentDescription = null,
                            tint = if (ui.running) Colors.onSurfaceVariant else Colors.onPrimaryContainer
                        )
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = if (ui.running) "运行中…${ui.progress?.current ?: ""}" else "开始基准测试",
                                style = Type.titleMedium,
                                color = if (ui.running) Colors.onSurface else Colors.onPrimaryContainer,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (!ui.running) {
                                Text(
                                    text = "PP=${ui.ppSize} · TG=${ui.tgSize} · ${if (ui.repCount > 1) "重复 ${ui.repCount} 次" else "单次"}",
                                    style = Type.bodySmall,
                                    color = Colors.onPrimaryContainer
                                )
                            }
                        }
                    }
                    if (ui.running) {
                        Spacer(Modifier.size(DesignTokens.spacing8))
                        val pct = ui.progress?.let { p ->
                            if (p.total > 0) p.completed.toFloat() / p.total else null
                        }
                        if (pct != null) {
                            LinearProgressIndicator(
                                progress = { pct },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        ui.progress?.let { p ->
                            Text(
                                text = "完成 ${p.completed}/${p.total} · 当前 ${p.current ?: "—"}",
                                style = Type.labelSmall,
                                color = Colors.onSurfaceVariant,
                                modifier = Modifier.padding(top = DesignTokens.spacing4)
                            )
                        }
                    }
                }
            }

            // ===== Section 4.5: 高级模式曲线 + 汇总报告 =====
            if (ui.mode == BenchmarkMode.ADVANCED && (ui.samples.isNotEmpty() || ui.report != null)) {
                item {
                    AdvancedResultSection(samples = ui.samples, report = ui.report)
                }
            }

            // ===== Section 5: 最新结果 =====
            if (ui.latestResults.isNotEmpty()) {
                item {
                    UnifiedSection(title = "最新结果（${ui.latestResults.size}）") {
                        Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)) {
                            ui.latestResults.forEach { r ->
                                ResultCard(result = r)
                            }
                        }
                    }
                }
            }

            // ===== Section 6: 历史 =====
            item {
                UnifiedSection(title = "历史结果（${ui.history.size}）") {
                    if (ui.history.isEmpty()) {
                        Text(
                            text = "暂无历史记录",
                            style = Type.bodyMedium,
                            color = Colors.onSurfaceVariant,
                            modifier = Modifier.padding(DesignTokens.spacing16)
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)) {
                            ui.history.take(20).forEach { e -> HistoryCard(entity = e) }
                            if (ui.history.size > 20) {
                                Text(
                                    text = "仅显示最近 20 条，共 ${ui.history.size} 条",
                                    style = Type.labelSmall,
                                    color = Colors.onSurfaceVariant,
                                    modifier = Modifier.padding(DesignTokens.spacing16)
                                )
                            }
                            androidx.compose.material3.TextButton(onClick = { vm.clearHistory() }) {
                                Icon(Icons.Outlined.Clear, contentDescription = null)
                                Spacer(Modifier.size(DesignTokens.spacing4))
                                Text("清空历史")
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============== 子组件 ==============

@Composable
private fun ModelSelectionField(
    models: List<com.aibox.data.model.ModelInfo>,
    selectedPath: String?,
    onSelect: (String?) -> Unit
) {
    val options = models.map { SettingOption(value = it.filePath, label = "${it.name} (${it.sizeMb}MB)") }
    val field = SettingField(
        key = "benchmark.model",
        type = FieldType.DROPDOWN,
        title = "模型文件",
        description = "选择要跑基准测试的模型（来自「模型」页已下载列表）",
        defaultValue = "",
        options = options
    )
    UnifiedDropdown(
        field = field,
        value = selectedPath ?: "",
        onValueChange = { v -> onSelect(v.ifBlank { null }) }
    )
}

@Composable
private fun ResultCard(result: BenchmarkResult) {
    UnifiedCard(
        tone = if (result.failed) UnifiedCardTone.SURFACE_LOW else UnifiedCardTone.SURFACE,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing12)
        ) {
            Icon(
                imageVector = if (result.failed) Icons.Outlined.Warning else Icons.Outlined.Speed,
                contentDescription = null,
                tint = if (result.failed) Colors.error else Colors.primary
            )
            Column {
                Text(
                    text = "PP=${result.spec.ppSize} TG=${result.spec.tgSize} rep=${result.spec.repCount}",
                    style = Type.titleSmall,
                    color = Colors.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                if (result.failed) {
                    Text(
                        text = "失败:${result.errorMessage ?: "未知"}",
                        style = Type.bodySmall,
                        color = Colors.error
                    )
                } else {
                    Text(
                        text = "PP ${"%.2f".format(result.ppTps)} t/s · TG ${"%.2f".format(result.tgTps)} t/s · TTFT ${result.ttftMs}ms · 总 ${result.totalTimeMs}ms",
                        style = Type.bodyMedium,
                        color = Colors.onSurface
                    )
                    Text(
                        text = "峰值内存 ${result.peakMemoryBytes / 1024 / 1024}MB · 热档 ${result.environment.thermalState} · 电量 ${result.environment.batteryLevel}%",
                        style = Type.labelSmall,
                        color = Colors.onSurfaceVariant
                    )
                    // 新增:增强指标行
                    val line2 = buildString {
                        if (result.appPssMb > 0) append(" · PSS ${result.appPssMb}MB")
                        if (result.appNativeMb > 0) append(" · Native ${result.appNativeMb}MB")
                        if (result.memUsagePercent in 0..100) append(" · 内存占用 ${result.memUsagePercent}%")
                        if (result.avgCpuUsage in 0..100) append(" · CPU ${result.avgCpuUsage}%")
                        if (result.avgGpuUsage in 0..100) append(" · GPU ${result.avgGpuUsage}%")
                        if (result.energyEfficiencyTokensPerJoule != null) {
                            append(" · 能耗比 ${"%.2f".format(result.energyEfficiencyTokensPerJoule)} tokens/J")
                        }
                        if (result.energyMilliJoules != null && result.energyMilliJoules > 0) {
                            append(" · 能耗 ${result.energyMilliJoules / 1000.0}J")
                        }
                    }
                    if (line2.isNotBlank()) {
                        Text(
                            text = line2.removePrefix(" · "),
                            style = Type.labelSmall,
                            color = Colors.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(entity: BenchmarkResultEntity) {
    UnifiedCard(
        tone = if (entity.failed) UnifiedCardTone.SURFACE_LOW else UnifiedCardTone.SURFACE,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing4)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
            ) {
                Text(
                    text = entity.backendName,
                    style = Type.titleSmall,
                    color = Colors.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = entity.modelPath.substringAfterLast('/'),
                    style = Type.bodySmall,
                    color = Colors.onSurfaceVariant
                )
            }
            Text(
                text = "PP=${entity.specPpSize} TG=${entity.specTgSize} · threads=${entity.specThreads ?: "auto"} · kv=${entity.specKvQuant}",
                style = Type.bodySmall,
                color = Colors.onSurfaceVariant
            )
            if (entity.failed) {
                Text(
                    text = "失败：${entity.errorMessage ?: "未知"}",
                    style = Type.bodySmall,
                    color = Colors.error
                )
            } else {
                Text(
                    text = "PP ${"%.2f".format(entity.ppTps)} · TG ${"%.2f".format(entity.tgTps)} · TTFT ${entity.ttftMs}ms · 总 ${entity.totalTimeMs}ms",
                    style = Type.bodyMedium,
                    color = Colors.onSurface
                )
                Text(
                    text = "SoC=${entity.deviceSoC} · 热档 ${entity.thermalState} · 电量 ${entity.batteryLevel}% · ${entity.totalRamMb - entity.freeRamMb}/${entity.totalRamMb}MB RAM",
                    style = Type.labelSmall,
                    color = Colors.onSurfaceVariant
                )
            }
            Text(
                text = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(entity.timestamp)),
                style = Type.labelSmall,
                color = Colors.onSurfaceVariant
            )
        }
    }
}

// ============== SettingField 工具 ==============

/**
 * 基础测试参数：自定义 PP / TG，加上重复与预热。
 * 不做场景选择——基准测试就是"跑一次看数字"。
 */
private fun buildParameterSettings(): List<SettingField> = listOf(
    SettingField(
        key = "benchmark.pp",
        type = FieldType.SLIDER,
        title = "提示词长度 PP（tokens）",
        description = "模拟提示词长度；默认 512，接近一段带历史上下文的真实提问",
        defaultValue = BenchmarkViewModel.DEFAULT_PP,
        min = 32, max = 4096, step = 32
    ),
    SettingField(
        key = "benchmark.tg",
        type = FieldType.SLIDER,
        title = "生成长度 TG（tokens）",
        description = "默认 64；生成太短会被首 token 开销影响，太长则耗时增加",
        defaultValue = BenchmarkViewModel.DEFAULT_TG,
        min = 8, max = 1024, step = 8
    ),
    SettingField(
        key = "benchmark.repCount",
        type = FieldType.SLIDER,
        title = "重复次数",
        description = "默认 1 次即可；想减少偶然波动可调到 3 次取中位数",
        defaultValue = 1,
        min = 1, max = 3, step = 1
    ),
    SettingField(
        key = "benchmark.warmup",
        type = FieldType.SLIDER,
        title = "预热次数",
        description = "预热不计入统计，避免首次推理的 mmap / 编译开销污染结果",
        defaultValue = 1,
        min = 0, max = 2, step = 1
    )
)

/** 把 UiState 折算成"当前值"Map，key 必须与 [buildParameterSettings] 一致。 */
private fun buildCurrentValues(ui: BenchmarkViewModel.UiState): Map<String, Any> = mapOf(
    "benchmark.pp" to ui.ppSize.toFloat(),
    "benchmark.tg" to ui.tgSize.toFloat(),
    "benchmark.repCount" to ui.repCount.toFloat(),
    "benchmark.warmup" to ui.warmup.toFloat()
)

/** SettingsListRenderer 回调派发。 */
private fun handleSettingChange(key: String, value: Any, vm: BenchmarkViewModel) {
    when (key) {
        "benchmark.pp" -> vm.setPpSize((value as? Number)?.toInt() ?: BenchmarkViewModel.DEFAULT_PP)
        "benchmark.tg" -> vm.setTgSize((value as? Number)?.toInt() ?: BenchmarkViewModel.DEFAULT_TG)
        "benchmark.repCount" -> vm.setRepCount((value as? Number)?.toInt() ?: 1)
        "benchmark.warmup" -> vm.setWarmup((value as? Number)?.toInt() ?: 1)
    }
}

// ============== 高级模式：6 曲线 + 汇总报告 ==============

/**
 * 高级结果区块：依次绘制 6 条曲线（读不到硬件的曲线显示"不支持"占位），下方给出汇总报告。
 * 所有颜色 / 字体走 [Colors] / [Type] / [DesignTokens]。
 */
@Composable
private fun AdvancedResultSection(
    samples: List<BenchmarkSample>,
    report: BenchmarkReport?,
) {
    UnifiedSection(title = "高级结果（${samples.size} 采样点）") {
        Column(
            modifier = Modifier.padding(horizontal = DesignTokens.spacing16),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing12),
        ) {
            val advanced = samples.mapNotNull { it.advanced }

            // 6 条曲线：零点过滤（预填充是单点，0 表示无值）
            LineChartCard(
                title = "输出速度 (tok/s)",
                points = advanced.map { it.outputSpeedCurve.timestampMs to it.outputSpeedCurve.value },
                color = Colors.primary,
            )
            val prefillPoints = advanced.map { it.prefillSpeedCurve }.filter { it.value > 0 }
            LineChartCard(
                title = "预填充速度 (tok/s，估算单点)",
                points = prefillPoints.map { it.timestampMs to it.value },
                color = Colors.secondary,
                emptyText = "首 token 后生成，暂无预填充采样点",
            )
            LineChartCard(
                title = "内存占用率 (%)",
                points = advanced.map { it.memoryUsageRatioCurve.timestampMs to (it.memoryUsageRatioCurve.value * 100) },
                color = Colors.tertiary,
            )
            val cpuPoints = advanced.map { it.cpuUtilizationCurve }
            LineChartCard(
                title = "CPU 占用 (本进程 %)",
                points = cpuPoints.map { it.timestampMs to (it.appCpuUsage * 100).toDouble() },
                color = Colors.primary,
            )
            val tempPoints = advanced.mapNotNull { it.temperatureCurve }
            LineChartCard(
                title = "CPU 温度 (℃)",
                points = tempPoints.map { it.timestampMs to it.cpuTempCelsius.toDouble() },
                color = Colors.error,
                emptyText = "设备未提供 CPU 温度接口",
            )
            val npuPoints = advanced.mapNotNull { it.npuUtilizationCurve }
            LineChartCard(
                title = "NPU 占用 (%)",
                points = npuPoints.map { it.timestampMs to (it.utilization * 100).toDouble() },
                color = Colors.secondary,
                emptyText = "设备未提供 NPU 占用接口（多数机型不支持）",
            )

            // 汇总报告
            report?.let { ReportCard(report = it) }
        }
    }
}

/**
 * 轻量折线图：用 Canvas 直接绘制，不引入第三方图表库。
 * 自动按点数归一化 X（时间）与 Y（数值）到画布，最小值/最大值标注在右上角。
 */
@Composable
private fun LineChartCard(
    title: String,
    points: List<Pair<Long, Double>>,
    color: androidx.compose.ui.graphics.Color,
    emptyText: String = "无数据",
) {
    UnifiedCard(tone = UnifiedCardTone.SURFACE, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(DesignTokens.spacing12)) {
            Text(text = title, style = Type.titleSmall, color = Colors.onSurface)
            Spacer(Modifier.size(DesignTokens.spacing4))
            if (points.size < 2) {
                Text(text = emptyText, style = Type.bodySmall, color = Colors.onSurfaceVariant)
            } else {
                val minV = points.minOf { it.second }
                val maxV = points.maxOf { it.second }
                androidx.compose.foundation.Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp),
                ) {
                    val w = size.width
                    val h = size.height
                    val spanY = (maxV - minV).coerceAtLeast(1e-6)
                    val xMin = points.first().first
                    val xMax = points.last().first
                    val spanX = (xMax - xMin).coerceAtLeast(1L).toDouble()
                    val path = androidx.compose.ui.graphics.Path().apply {
                        points.forEachIndexed { idx, (x, y) ->
                            val px = ((x - xMin) / spanX).toFloat() * w
                            val py = h - ((y - minV) / spanY).toFloat() * h * 0.9f - h * 0.05f
                            if (idx == 0) moveTo(px, py) else lineTo(px, py)
                        }
                    }
                    drawPath(
                        path = path,
                        color = color,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
                    )
                }
                Text(
                    text = "min ${"%.2f".format(minV)} · max ${"%.2f".format(maxV)}",
                    style = Type.labelSmall,
                    color = Colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = DesignTokens.spacing4),
                )
            }
        }
    }
}

/** 汇总报告卡：展示均值与 p50/p95/p99 波动统计。 */
@Composable
private fun ReportCard(report: BenchmarkReport) {
    UnifiedCard(tone = UnifiedCardTone.PRIMARY_CONTAINER, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(DesignTokens.spacing12),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing4),
        ) {
            Text(
                text = "汇总 · ${report.backendName} / ${report.modelName}",
                style = Type.titleSmall,
                color = Colors.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "平均输出 ${"%.2f".format(report.averages.outputTokensPerSecond)} tok/s · " +
                    "预填充 ${"%.2f".format(report.averages.prefillTokensPerSecond)} tok/s · " +
                    "采样 ${report.totalSamples}",
                style = Type.bodyMedium,
                color = Colors.onPrimaryContainer,
            )
            Text(text = statsLine("输出速度", report.outputSpeedStats), style = Type.labelSmall, color = Colors.onPrimaryContainer)
            Text(text = statsLine("CPU 占用", report.cpuStats), style = Type.labelSmall, color = Colors.onPrimaryContainer)
            Text(text = statsLine("内存占用", report.memoryStats), style = Type.labelSmall, color = Colors.onPrimaryContainer)
        }
    }
}

private fun statsLine(label: String, stats: Stats?): String {
    if (stats == null) return "$label：无数据"
    return "$label：p50 ${"%.2f".format(stats.p50)} · p95 ${"%.2f".format(stats.p95)} · " +
        "p99 ${"%.2f".format(stats.p99)} · σ ${"%.2f".format(stats.stdDev)}"
}
