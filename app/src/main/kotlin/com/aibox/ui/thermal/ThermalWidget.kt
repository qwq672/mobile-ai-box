package com.aibox.ui.thermal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibox.AIBoxApp
import com.aibox.ui.theme.DesignTokens
import com.aibox.util.ThermalLevel

/**
 * 主页可嵌入的散热小组件（小型 pill 形状）。
 *
 * 用于放在 TopAppBar 的 actions 区或 Drawer 顶部，比 [ThermalMonitorCard] 紧凑，
 * 只显示状态图标 + 简短文案，无历史曲线。
 *
 * 与 ChatScreen 现有的 [com.aibox.ui.chat.ChatScreen] 中的 ThermalPill 类似；
 * 但本组件通过 [ThermalLevel] 自动着色（而非基于 percent 阈值），便于复用。
 *
 * @param onClick 若不为 null，则渲染为可点击（用于跳转到详细页）
 */
@Composable
fun ThermalWidget(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val monitor = AIBoxApp.instance.container.thermalMonitor
    val percent by monitor.thermalPercent.collectAsStateWithLifecycle()
    val level by monitor.state.collectAsStateWithLifecycle()

    val bgColor: Color = when (level) {
        ThermalLevel.UNKNOWN -> MaterialTheme.colorScheme.surfaceContainerHigh
        ThermalLevel.COOL -> MaterialTheme.colorScheme.primaryContainer
        ThermalLevel.WARM -> MaterialTheme.colorScheme.tertiaryContainer
        ThermalLevel.HOT, ThermalLevel.CRITICAL -> MaterialTheme.colorScheme.errorContainer
    }
    val fgColor: Color = when (level) {
        ThermalLevel.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
        ThermalLevel.COOL -> MaterialTheme.colorScheme.onPrimaryContainer
        ThermalLevel.WARM -> MaterialTheme.colorScheme.onTertiaryContainer
        ThermalLevel.HOT, ThermalLevel.CRITICAL -> MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        modifier = modifier.then(
            if (onClick != null) Modifier.clickable { onClick() } else Modifier
        ),
        color = bgColor,
        shape = RoundedCornerShape(DesignTokens.radiusSmall)
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = DesignTokens.spacing8,
                vertical = DesignTokens.spacing4
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing4)
        ) {
            Icon(
                Icons.Outlined.Thermostat,
                contentDescription = null,
                tint = fgColor,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = if (percent < 0) "—" else "$percent%",
                style = MaterialTheme.typography.labelSmall,
                color = fgColor
            )
        }
    }
}
