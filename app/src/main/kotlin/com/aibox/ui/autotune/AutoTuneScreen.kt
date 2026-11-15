package com.aibox.ui.autotune

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
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import com.aibox.ui.components.unified.UnifiedCard
import com.aibox.ui.components.unified.UnifiedCardTone
import com.aibox.ui.components.unified.UnifiedDropdown
import com.aibox.ui.components.unified.UnifiedSection
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.DesignTokens
import com.aibox.ui.theme.Type

/**
 * 自动调优页。
 *
 * 与基准测试的分工：
 * - 基准测试只测当前配置，给数字；
 * - 这里批量跑候选配置，对比后把最优配置写回设置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoTuneScreen(
    onBack: (() -> Unit)? = null,
    vm: AutoTuneViewModel = viewModel()
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

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
                        IconButton(onClick = onBack) {
                            Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                title = { Text("自动调优", style = Type.titleLarge) }
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
            item {
                UnifiedSection(title = "说明", showTopDivider = false) {
                    Text(
                        text = "自动调优会反复加载模型、逐项对比配置，耗时明显长于基准测试；" +
                            "结束后会把最优的线程 / KV 量化 / batch / 上下文写回「设置 → 推理」。",
                        style = Type.bodyMedium,
                        color = Colors.onSurfaceVariant,
                        modifier = Modifier.padding(DesignTokens.spacing16)
                    )
                }
            }

            item {
                UnifiedSection(title = "模型与后端") {
                    val field = SettingField(
                        key = "autotune.model",
                        type = FieldType.DROPDOWN,
                        title = "模型",
                        description = "调优只针对当前选择的模型；换模型后建议重新调优",
                        defaultValue = "",
                        options = ui.models.map { SettingOption(it.filePath, it.name) }
                    )
                    UnifiedDropdown(
                        field = field,
                        value = ui.selectedModelPath ?: "",
                        onValueChange = vm::selectModel
                    )
                    if (ui.backends.isNotEmpty()) {
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

            item {
                UnifiedSection(title = "调优档位") {
                    Column(
                        modifier = Modifier.padding(horizontal = DesignTokens.spacing16),
                        verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)) {
                            AutoTuneViewModel.Level.entries.forEach { level ->
                                FilterChip(
                                    selected = ui.level == level,
                                    onClick = { vm.selectLevel(level) },
                                    enabled = !ui.running,
                                    label = { Text(level.label) }
                                )
                            }
                        }
                        Text(
                            text = ui.level.desc,
                            style = Type.bodySmall,
                            color = Colors.onSurfaceVariant
                        )
                    }
                }
            }

            if (ui.level.ordinal >= AutoTuneViewModel.Level.HIGH.ordinal) {
                item {
                    UnifiedSection(title = "高温测试") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = DesignTokens.spacing16),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("在设备发烫时开始测试", style = Type.titleSmall, color = Colors.onSurface)
                                Text(
                                    "先跑一段持续负载再测量，结果更接近长时间使用后的状态；" +
                                        "会明显增加耗电与发热，可能加速电池老化。",
                                    style = Type.bodySmall,
                                    color = if (ui.hotTest) Colors.error else Colors.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = ui.hotTest,
                                onCheckedChange = vm::setHotTest,
                                enabled = !ui.running
                            )
                        }
                    }
                }
            }

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
                                text = if (ui.running) "调优中…" else "开始自动调优",
                                style = Type.titleMedium,
                                color = if (ui.running) Colors.onSurface else Colors.onPrimaryContainer,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (!ui.running) {
                                Text(
                                    text = "${ui.level.label}档 · 对比后自动应用最优配置",
                                    style = Type.bodySmall,
                                    color = Colors.onPrimaryContainer
                                )
                            }
                        }
                    }
                    if (ui.running) {
                        Spacer(Modifier.size(DesignTokens.spacing8))
                        LinearProgressIndicator(
                            progress = { ui.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = ui.progressText,
                            style = Type.labelSmall,
                            color = Colors.onSurfaceVariant,
                            modifier = Modifier.padding(top = DesignTokens.spacing4)
                        )
                    }
                }
            }

            ui.summary?.let { summary ->
                item {
                    UnifiedCard(
                        tone = UnifiedCardTone.SURFACE_HIGH,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = summary,
                            style = Type.bodyMedium,
                            color = Colors.onSurface
                        )
                    }
                }
            }

            if (ui.results.isNotEmpty()) {
                item {
                    UnifiedSection(title = "候选结果（${ui.results.size}）") {
                        Column(
                            modifier = Modifier.padding(horizontal = DesignTokens.spacing16),
                            verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
                        ) {
                            ui.results.sortedByDescending { it.score }.forEach { r ->
                                CandidateRow(r, isBest = r == ui.best)
                            }
                        }
                    }
                }
            }

            if (ui.hotTest) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = DesignTokens.spacing16),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = Colors.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            "高温测试会持续满载，请在通风处进行并留意机身温度。",
                            style = Type.labelSmall,
                            color = Colors.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(r: AutoTuneViewModel.CandidateResult, isBest: Boolean) {
    UnifiedCard(
        tone = if (isBest) UnifiedCardTone.PRIMARY_CONTAINER else UnifiedCardTone.SURFACE_LOW,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = buildString {
                    append(r.backend)
                    append(" · 线程 ").append(r.threads ?: "自动")
                    append(" · KV ").append(r.kv)
                    append(" · batch ").append(r.batch)
                    if (isBest) append(" · 最优")
                },
                style = Type.titleSmall,
                color = if (isBest) Colors.onPrimaryContainer else Colors.onSurface,
                fontWeight = if (isBest) FontWeight.SemiBold else null
            )
            if (r.failed) {
                Text(
                    text = "失败：${r.note.ifBlank { "未知原因" }}",
                    style = Type.bodySmall,
                    color = Colors.error
                )
            } else {
                Text(
                    text = "TG ${"%.2f".format(r.tgTps)} t/s · PP ${"%.2f".format(r.ppTps)} t/s · 得分 ${"%.3f".format(r.score)}",
                    style = Type.bodySmall,
                    color = if (isBest) Colors.onPrimaryContainer else Colors.onSurfaceVariant
                )
            }
        }
    }
}
