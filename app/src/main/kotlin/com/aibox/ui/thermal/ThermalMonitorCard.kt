package com.aibox.ui.thermal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibox.AIBoxApp
import com.aibox.ui.components.unified.UnifiedCard
import com.aibox.ui.components.unified.UnifiedCardTone
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.DesignTokens
import com.aibox.ui.theme.Type
import com.aibox.util.ThermalLevel
import com.aibox.util.ThermalSample

/**
 * 散热监控卡片：用于设置页 / 详细页展示当前散热状态。
 *
 * 布局：
 * - 顶部：状态徽标 + 当前热度 + 摄氏度 + CPU 频率
 * - 中部：最近 60 秒历史曲线（Canvas 绘制）
 * - 底部：后端建议（HOT 时建议降线程；CRITICAL 时建议暂停）
 *
 * 视觉规则：
 * - 用 [UnifiedCard]（P0.2 统一组件），不直接写颜色；状态色通过 [stateColor] 取 Material role。
 * - 历史 Canvas 用 [stateColor] 的描边 + 透明 fill；60 秒窗口显示。
 *
 * @param onClick 若不为 null，则渲染为可点击卡片（用于跳转到 ThermalMonitorScreen 详细页）
 */
@Composable
fun ThermalMonitorCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val monitor = AIBoxApp.instance.container.thermalMonitor
    val percent by monitor.thermalPercent.collectAsStateWithLifecycle()
    val celsius by monitor.celsius.collectAsStateWithLifecycle()
    val cpuFreqKhz by monitor.cpuFreqKhz.collectAsStateWithLifecycle()
    val level by monitor.state.collectAsStateWithLifecycle()
    val history by monitor.history.collectAsStateWithLifecycle()

    UnifiedCard(
        modifier = modifier,
        tone = UnifiedCardTone.SURFACE_LOW,
        onClick = onClick
    ) {
        // ============== 顶部：状态徽标 + 当前值 ==============
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing12)
        ) {
            Icon(
                Icons.Outlined.Thermostat,
                contentDescription = null,
                tint = stateColor(level),
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stateLabel(level),
                    style = Type.titleMedium,
                    color = stateColor(level),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
                Text(
                    text = if (percent < 0) "热度 —" else "热度 $percent% · ${formatCelsius(celsius)}",
                    style = Type.bodySmall,
                    color = Colors.onSurfaceVariant
                )
            }
            if (cpuFreqKhz > 0) {
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Memory,
                            contentDescription = null,
                            tint = Colors.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            text = formatFreq(cpuFreqKhz),
                            style = Type.labelMedium,
                            color = Colors.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "CPU 主频",
                        style = Type.labelSmall,
                        color = Colors.onSurfaceVariant
                    )
                }
            }
            if (onClick != null) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = "查看详细",
                    tint = Colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(Modifier.height(DesignTokens.spacing12))

        // ============== 中部：历史曲线 ==============
        HistorySparkline(
            history = history,
            lineColor = stateColor(level),
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
        )

        Spacer(Modifier.height(DesignTokens.spacing12))

        // ============== 底部：后端建议 ==============
        Text(
            text = recommendation(level),
            style = Type.bodySmall,
            color = Colors.onSurfaceVariant
        )
    }
}

/**
 * 历史曲线：把 [history] 中的 percent 拟合成一条折线 + 半透明填充。
 *
 * 仅绘制 percent >= 0 的样本；不足 30 个时按时间均匀分布到画布宽度。
 */
@Composable
private fun HistorySparkline(
    history: List<ThermalSample>,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val valid = history.filter { it.percent >= 0 }
        if (valid.isEmpty()) {
            // 无数据：画一条灰色基线
            val y = size.height / 2
            drawLine(
                color = Color.Gray.copy(alpha = 0.3f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 2f,
                cap = StrokeCap.Round
            )
            return@Canvas
        }

        val w = size.width
        val h = size.height
        val n = valid.size

        // 把 percent (0..100) 映射到 y (h..0)：percent 越高，y 越小（顶部）
        fun point(i: Int): Offset {
            val x = if (n <= 1) w / 2 else (w * i / (n - 1))
            val p = valid[i].percent.coerceIn(0, 100)
            val y = h - (h * p / 100f)
            return Offset(x, y)
        }

        // 1. 填充：path 从基线到折线，alpha 0.15
        val fillPath = Path().apply {
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
            color = lineColor.copy(alpha = 0.15f),
            style = androidx.compose.ui.graphics.drawscope.Fill
        )

        // 2. 描边：折线
        val strokePath = Path().apply {
            valid.forEachIndexed { i, _ ->
                val pt = point(i)
                if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
            }
        }
        drawPath(
            path = strokePath,
            color = lineColor,
            style = Stroke(width = 2.5f, cap = StrokeCap.Round)
        )

        // 3. 端点圆
        val last = point(n - 1)
        drawCircle(
            color = lineColor,
            radius = 4f,
            center = last
        )
    }
}

/* ==================== 颜色 / 文案工具 ==================== */

/** 状态对应颜色（用 Material role，避免硬编码 hex）。 */
@Composable
private fun stateColor(level: ThermalLevel): Color = when (level) {
    ThermalLevel.UNKNOWN -> Colors.onSurfaceVariant
    ThermalLevel.COOL -> MaterialTheme.colorScheme.primary
    ThermalLevel.WARM -> MaterialTheme.colorScheme.tertiary
    ThermalLevel.HOT -> MaterialTheme.colorScheme.error
    ThermalLevel.CRITICAL -> MaterialTheme.colorScheme.error
}

/** 状态对应中文名。 */
fun stateLabel(level: ThermalLevel): String = when (level) {
    ThermalLevel.UNKNOWN -> "散热状态 —"
    ThermalLevel.COOL    -> "散热状态：凉"
    ThermalLevel.WARM    -> "散热状态：温"
    ThermalLevel.HOT     -> "散热状态：热"
    ThermalLevel.CRITICAL -> "散热状态：极热"
}

/** 后端建议文案。 */
fun recommendation(level: ThermalLevel): String = when (level) {
    ThermalLevel.UNKNOWN -> "无法读取温度传感器，建议在 /sys/class/thermal 可读的设备上使用"
    ThermalLevel.COOL    -> "温度正常，可全速推理"
    ThermalLevel.WARM    -> "轻微发热，可继续推理；建议关注长时间高频任务"
    ThermalLevel.HOT     -> "明显发热，建议减少 CPU 线程数或关闭 GPU/NPU；优化页可一键切到省电模式"
    ThermalLevel.CRITICAL -> "极热，建议暂停长测并让设备冷却；继续推理可能触发热降频或保护性重启"
}

/** 格式化摄氏度。 */
private fun formatCelsius(c: Double): String =
    if (c < 0) "—" else "%.1f°C".format(c)

/** 格式化 CPU 主频（kHz → GHz / MHz）。 */
private fun formatFreq(khz: Long): String {
    val mhz = khz / 1000
    return if (mhz >= 1000) "%.2f GHz".format(mhz / 1000.0) else "$mhz MHz"
}
