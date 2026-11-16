package com.aibox.ui.help

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aibox.ui.chat.MarkdownText

/**
 * 应用内帮助页。
 *
 * 内容来自 `assets/help` 下的 Markdown 文件，与仓库根目录 `help/` 保持同步（同一份文案两份拷贝，
 * 一份给仓库读者，一份给应用内离线阅读）。
 *
 * 交互：列表 → 点开一篇 → 返回；返回键在「文章」态回到列表而不是直接退出设置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    var selected by remember { mutableStateOf<HelpTopic?>(null) }
    val current = selected

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (current != null) selected = null else onBack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Text(
                        current?.title ?: "帮助与教程",
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            )
        }
    ) { inner ->
        if (current == null) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(HelpTopic.values().toList()) { topic ->
                    HelpRow(topic) { selected = topic }
                }
                item {
                    Text(
                        "内容随版本更新；如果与界面不一致，以界面为准。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        } else {
            val context = LocalContext.current
            val body by produceState(initialValue = "正在读取…", key1 = current.file) {
                value = runCatching {
                    context.assets.open("help/${current.file}")
                        .bufferedReader()
                        .use { it.readText() }
                }.getOrElse { "读取失败：${it.message}" }
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                MarkdownText(
                    text = body,
                    color = MaterialTheme.colorScheme.onBackground,
                    baseFontSize = 15,
                    lineHeight = 23
                )
            }
        }
    }
}

@Composable
private fun HelpRow(topic: HelpTopic, onClick: () -> Unit) {
    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = MaterialTheme.shapes.large,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(Modifier.weight(1f)) {
                Text(
                    topic.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    topic.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 帮助目录（与 assets/help 下的文件名一一对应）。 */
private enum class HelpTopic(
    val file: String,
    val title: String,
    val summary: String
) {
    GETTING_STARTED("01-getting-started.md", "上手指南", "准备模型、加载、开始对话"),
    SETTINGS("02-settings.md", "设置说明", "推理、采样、优化与重置"),
    TOOLS("03-tools-network.md", "工具与联网", "三态联网、工具选项、调用排查"),
    POWER("04-power.md", "低电量与性能档", "档位、Burst、提示与关闭提醒"),
    FAQ("05-faq.md", "常见问题", "复读、幻觉、加载失败、耗电")
}
