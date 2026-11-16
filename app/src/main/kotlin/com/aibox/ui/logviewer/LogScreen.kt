package com.aibox.ui.logviewer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.aibox.util.log.LogEntry
import com.aibox.util.log.LogLevel
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.delay

/**
 * 运行日志查看器（"少黑盒"）。
 *
 * - 直接读取 [LogSystem] 的 ring buffer，每秒刷新一次
 * - 可按级别 / tag 前缀 / 关键字过滤
 * - 可复制当前视图、清空日志
 * - 显示日志目录，方便导出
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    var entries by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var minLevel by remember { mutableStateOf(LogLevel.DEBUG) }
    var query by remember { mutableStateOf("") }
    var tagPrefix by remember { mutableStateOf("") }
    var refreshTick by remember { mutableStateOf(0) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    LaunchedEffect(refreshTick) {
        while (true) {
            entries = LogSystem.snapshot(2000)
            delay(1000)
        }
    }

    val filtered = remember(entries, minLevel, query, tagPrefix) {
        entries.asReversed()
            .filter { it.level.priority >= minLevel.priority }
            .filter { tagPrefix.isBlank() || it.tag.startsWith(tagPrefix) }
            .filter {
                query.isBlank() ||
                    it.message.contains(query, ignoreCase = true) ||
                    it.tag.contains(query, ignoreCase = true)
            }
            .take(500)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("运行日志", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { refreshTick++ }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                    IconButton(
                        onClick = {
                            val text = filtered.reversed().joinToString("\n") { it.formatOneLine() }
                            clipboard.setText(AnnotatedString(text))
                            Toast.makeText(context, "已复制 ${filtered.size} 条日志", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "复制")
                    }
                    IconButton(
                        onClick = {
                            LogSystem.clearAll()
                            entries = emptyList()
                        }
                    ) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = "清空")
                    }
                }
            )
        }
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            Text(
                "共 ${entries.size} 条缓存 · 当前显示 ${filtered.size} 条" +
                    (LogSystem.getLogDirPath()?.let { "\n文件：$it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    LogLevel.VERBOSE to "全部",
                    LogLevel.DEBUG to "D+",
                    LogLevel.INFO to "I+",
                    LogLevel.WARN to "W+",
                    LogLevel.ERROR to "E"
                ).forEach { (level, label) ->
                    FilterChip(
                        selected = minLevel == level,
                        onClick = { minLevel = level },
                        label = { Text(label) }
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val tags = listOf(
                    "" to "全部 tag",
                    "aibox.llama" to "推理",
                    "aibox.ui.chat" to "聊天",
                    "aibox.tool" to "工具",
                    "aibox.plugin" to "插件",
                    "aibox.module" to "模块",
                    "aibox.benchmark" to "基准",
                    "aibox.thermal" to "温度"
                )
                tags.forEach { (tag, label) ->
                    FilterChip(
                        selected = tagPrefix == tag,
                        onClick = { tagPrefix = tag },
                        label = { Text(label) }
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("搜索消息或 tag…") },
                singleLine = true
            )

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有匹配的日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(items = filtered) { entry ->
                        LogRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(e: LogEntry) {
    val color = when (e.level) {
        LogLevel.ERROR, LogLevel.FATAL -> MaterialTheme.colorScheme.error
        LogLevel.WARN -> Color(0xFFE8A33D)
        LogLevel.INFO -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(8.dp)
            )
            .padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                e.timestamp.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS")),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(6.dp))
            Text(
                e.level.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(Modifier.size(6.dp))
            Text(
                e.tag,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            e.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (e.throwable != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                e.throwable.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
