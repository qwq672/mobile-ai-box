package com.aibox.ui.thermal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibox.AIBoxApp
import com.aibox.ui.components.unified.UnifiedCard
import com.aibox.ui.components.unified.UnifiedCardTone
import com.aibox.ui.components.unified.UnifiedSection
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.DesignTokens
import com.aibox.ui.theme.Type
import com.aibox.util.ThermalLevel
import com.aibox.util.ThermalSample

/**
 * 散热监控详细页：展示完整历史曲线 + 各项明细 + 后端建议。
 *
 * 入口：设置页的 [ThermalMonitorCard] 点击 → [com.aibox.ui.nav.Dest.Thermal] 路由 → 本页。
 *
 * 布局：
 * 1. TopAppBar：返回 + 标题 "散热监控"
 * 2. Section "当前状态"：大号状态徽标 + 摄氏度 + 主频 + 热度
 * 3. Section "历史 60 秒"：大尺寸 Canvas 曲线 + Y 轴刻度（0% / 50% / 100%）
 * 4. Section "明细"：采样条数 / 最近更新时间 / sysfs 路径数等
 * 5. Section "后端建议"：根据当前档位给出推理建议
 *
 * 实时刷新：本页通过 [collectAsStateWithLifecycle] 直接订阅 thermalMonitor 的 StateFlow；
 * 离开页面时自动停止订阅（Compose lifecycle 感知）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThermalMonitorScreen(
    onBack: () -> Unit
) {
    val monitor = AIBoxApp.instance.container.thermalMonitor
    val percent by monitor.thermalPercent.collectAsStateWithLifecycle()
    val celsius by monitor.celsius.collectAsStateWithLifecycle()
    val cpuFreqKhz by monitor.cpuFreqKhz.collectAsStateWithLifecycle()
    val level by monitor.state.collectAsStateWithLifecycle()
    val history by monitor.history.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("散热监控", style = Type.titleLarge) }
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(vertical = DesignTokens.spacing16),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing12)
        ) {
            // ============== 当前状态 ==============
            item {
                UnifiedSection(title = "当前状态", showTopDivider = false) {
                    UnifiedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = DesignTokens.spacing16),
                        tone = UnifiedCardTone.SURFACE_LOW
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing16)
                        ) {
                            Icon(
                                Icons.Outlined.Thermostat,
                                contentDescription = null,
                                tint = Colors.primary,
                                modifier = Modifier.size(48.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stateLabel(level),
                                    style = Type.headingSmall,
                                    color = Colors.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (percent < 0) "热度 —" else "热度 $percent%",
                                    style = Type.bodyLarge,
                                    color = Colors.onSurfaceVariant
                                )
                                Text(
                                    text = "温度 ${formatCelsius(celsius)} · CPU ${formatFreq(cpuFreqKhz)}",
                                    style = Type.bodyMedium,
                                    color = Colors.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // ============== 历史 60 秒 ==============
            item {
                UnifiedSection(title = "历史 60 秒") {
                    UnifiedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = DesignTokens.spacing16),
                        tone = UnifiedCardTone.SURFACE
                    ) {
                        BigHistoryChart(
                            history = history,
                            lineColor = stateColorFor(level),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                        )
                        Spacer(Modifier.height(DesignTokens.spacing8))
                        Text(
                            text = "采样条数 ${history.size}/${com.aibox.util.ThermalMonitor.HISTORY_MAX}（每 2 秒 1 条）",
                            style = Type.labelSmall,
                            color = Colors.onSurfaceVariant
                        )
                    }
                }
            }

            // ============== 明细 ==============
            item {
                UnifiedSection(title = "明细") {
                    UnifiedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = DesignTokens.spacing16),
                        tone = UnifiedCardTone.SURFACE
                    ) {
                        DetailRow("热度档位", stateLabel(level))
                        DetailRow("原始温度", formatCelsius(celsius))
                        DetailRow("热度百分比", if (percent < 0) "—" else "$percent%")
                        DetailRow("CPU 主频", if (cpuFreqKhz > 0) formatFreq(cpuFreqKhz) else "未读到")
                        DetailRow("采样条数", "${history.size}/${com.aibox.util.ThermalMonitor.HISTORY_MAX}")
                        DetailRow(
                            "最近更新",
                            history.lastOrNull()?.timestamp?.let { formatTime(it) } ?: "无数据"
                        )
                    }
                }
            }

            // ============== 后端建议 ==============
            item {
                UnifiedSection(title = "后端建议") {
                    UnifiedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = DesignTokens.spacing16),
                        tone = when (level) {
                            ThermalLevel.HOT, ThermalLevel.CRITICAL -> UnifiedCardTone.SURFACE_HIGH
                            else -> UnifiedCardTone.SURFACE
                        }
                    ) {
                        Text(
                            text = recommendation(level),
                            style = Type.bodyMedium,
                            color = Colors.onSurface
                        )
                        if (level == ThermalLevel.HOT || level == ThermalLevel.CRITICAL) {
                            Spacer(Modifier.height(DesignTokens.spacing8))
                            Text(
                                text = "推荐操作：\n" + (
                                    if (level == ThermalLevel.CRITICAL)
                                        "1. 暂停当前推理任务\n2. 退出推理密集页面（基准测试 / 长对话）\n3. 让设备冷却 1-2 分钟"
                                    else
                                        "1. 进入 推理优化 → 预置 → 省电模式\n2. 减少并发基准测试\n3. 必要时切到 CPU 后端 + 较小模型"
                                ),
                                style = Type.bodySmall,
                                color = Colors.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ==================== 大尺寸历史曲线（带 Y 轴刻度） ==================== */

@Composable
private fun BigHistoryChart(
    history: List<ThermalSample>,
    lineColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val valid = history.filter { it.percent >= 0 }

        // Y 轴刻度线：0% / 50% / 100%
        listOf(0, 50, 100).forEach { p ->
            val y = h - (h * p / 100f)
            drawLine(
                color = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.2f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1f
            )
        }

        if (valid.isEmpty()) {
            // 无数据基线
            val y = h / 2
            drawLine(
                color = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.4f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 2f
            )
            return@Canvas
        }

        val n = valid.size
        fun point(i: Int): Offset {
            val x = if (n <= 1) w / 2 else (w * i / (n - 1))
            val p = valid[i].percent.coerceIn(0, 100)
            val y = h - (h * p / 100f)
            return Offset(x, y)
        }

        // 填充
        val fillPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, h)
            valid.forEachIndexed { i, _ ->
                val pt = point(i)
                lineTo(pt.x, pt.y)
            }
            lineTo(if (n <= 1) w / 2 else w, h)
            close()
        }
        drawPath(
            path = fillPath,
            color = lineColor.copy(alpha = 0.18f),
            style = androidx.compose.ui.graphics.drawscope.Fill
        )

        // 描边
        val strokePath = androidx.compose.ui.graphics.Path().apply {
            valid.forEachIndexed { i, _ ->
                val pt = point(i)
                if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
            }
        }
        drawPath(
            path = strokePath,
            color = lineColor,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        )

        // 端点圆
        drawCircle(color = lineColor, radius = 5f, center = point(n - 1))
    }
}

/* ==================== 明细行 ==================== */

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = DesignTokens.spacing4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = Type.bodyMedium, color = Colors.onSurfaceVariant)
        Text(text = value, style = Type.bodyMedium, color = Colors.onSurface)
    }
}

/* ==================== 工具 ==================== */

/** 状态对应颜色（与 ThermalMonitorCard 中保持一致；独立函数避免跨文件循环引用）。 */
@Composable
private fun stateColorFor(level: ThermalLevel): androidx.compose.ui.graphics.Color = when (level) {
    ThermalLevel.UNKNOWN -> Colors.onSurfaceVariant
    ThermalLevel.COOL -> MaterialTheme.colorScheme.primary
    ThermalLevel.WARM -> MaterialTheme.colorScheme.tertiary
    ThermalLevel.HOT -> MaterialTheme.colorScheme.error
    ThermalLevel.CRITICAL -> MaterialTheme.colorScheme.error
}

private fun formatCelsius(c: Double): String = if (c < 0) "—" else "%.1f°C".format(c)

private fun formatFreq(khz: Long): String {
    val mhz = khz / 1000
    return if (mhz >= 1000) "%.2f GHz".format(mhz / 1000.0) else if (mhz > 0) "$mhz MHz" else "未读到"
}

private fun formatTime(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000 -> "${diff / 1000} 秒前"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        else -> "${diff / 3_600_000} 小时前"
    }
}
