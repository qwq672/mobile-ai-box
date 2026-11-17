package com.aibox.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aibox.domain.download.RemoteGgufFile
import com.aibox.domain.download.RemoteModelSummary
import com.aibox.ui.download.DownloadViewModel

/**
 * 网络下载模型页。
 *
 * 统一走「搜索仓库 → 选 GGUF 文件（量化）→ 下载」，下载源可在本页切换；
 * 魔搭没有公开搜索接口时降级为「输入仓库 ID → 列出文件」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDownloadScreen(
    vm: DownloadViewModel = viewModel(),
    onBack: () -> Unit = {}
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(ui.error) {
        ui.error?.let {
            snackbarHost.showSnackbar(it)
            vm.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("网络下载") }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ===== 下载源 =====
            item(key = "sources") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("下载源", style = MaterialTheme.typography.titleSmall)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            ui.sources.forEach { src ->
                                FilterChip(
                                    selected = src.id == ui.currentSourceId,
                                    onClick = { vm.selectSource(src.id) },
                                    label = { Text(src.displayNameZh) }
                                )
                            }
                        }
                        if (ui.currentSourceId == "hf_mirror") {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "失败时改用 hf-mirror.net",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        "hf-mirror.com 下载失败时自动重试备用域名",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = ui.hfMirrorFallback,
                                    onCheckedChange = { on ->
                                        // 切开关直接写 SettingsRepository，由 Flow 回流到本页
                                        vm.setHfMirrorFallback(on)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // ===== 搜索 / 仓库输入 =====
            if (ui.activeRepo.isBlank()) {
                item(key = "search") {
                    if (ui.searchSupported) {
                        OutlinedTextField(
                            value = ui.searchQuery,
                            onValueChange = vm::onSearchQuery,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("搜索模型，例如 qwen2.5 1.5b gguf") },
                            singleLine = true,
                            leadingIcon = {
                                Icon(Icons.Outlined.Search, contentDescription = null)
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                            trailingIcon = {
                                TextButton(
                                    onClick = { vm.search() },
                                    enabled = ui.searchQuery.isNotBlank() && !ui.searching
                                ) { Text("搜索") }
                            }
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = ui.repoInput,
                                onValueChange = vm::onRepoInput,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("仓库 ID") },
                                placeholder = { Text("例：Qwen/Qwen2.5-1.5B-Instruct-GGUF") },
                                singleLine = true
                            )
                            Text(
                                "魔搭暂不提供关键词搜索，请直接输入仓库 ID；也可切到 Hugging Face / HF-Mirror 搜索。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = { vm.openRepo(ui.repoInput.trim()) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = ui.repoInput.isNotBlank() && !ui.loading
                            ) {
                                Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text("列出文件")
                            }
                        }
                    }
                }

                if (ui.searching || ui.loading) {
                    item(key = "search_progress") {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                if (!ui.searching && ui.searchResults.isEmpty()) {
                    item(key = "search_hint") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Outlined.CloudDownload,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                if (ui.searchSupported) "输入关键词开始搜索" else "输入仓库 ID 开始",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                items(items = ui.searchResults, key = { "repo_${it.repo}" }) { m ->
                    SearchResultRow(summary = m, onClick = { vm.openRepo(m.repo) })
                }
            } else {
                // ===== 仓库文件列表（按量化选） =====
                item(key = "repo_header") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "仓库",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                                Text(
                                    ui.activeRepo,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(onClick = vm::closeRepo) { Text("返回搜索") }
                        }
                    }
                }

                if (ui.loading) {
                    item(key = "files_progress") {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                if (!ui.loading && ui.files.isEmpty()) {
                    item(key = "files_empty") {
                        Text(
                            "这个仓库里没有找到 .gguf 文件。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 20.dp)
                        )
                    }
                }

                items(items = ui.files, key = { it.path }) { f ->
                    DownloadFileCard(
                        file = f,
                        progress = ui.progress[f.path] ?: 0,
                        onDownload = { vm.download(ui.activeRepo, f) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(summary: RemoteModelSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                summary.repo,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            val meta = buildString {
                if (summary.downloads > 0) append("↓ ${formatCount(summary.downloads)}")
                if (summary.likes > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("♥ ${formatCount(summary.likes)}")
                }
                if (summary.note.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(summary.note)
                }
            }
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DownloadFileCard(
    file: RemoteGgufFile,
    progress: Int,
    onDownload: () -> Unit
) {
    val name = file.path.substringAfterLast('/')
    val quant = quantizationOf(name)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (quant != null) {
                    QuantBadge(quant)
                }
                if (file.sizeBytes > 0) {
                    Text(
                        formatBytes(file.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            when {
                progress in 1..99 -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            "$progress%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                progress >= 100 -> {
                    Text(
                        "已下载",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                else -> {
                    FilledTonalButton(
                        onClick = onDownload,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("下载")
                    }
                }
            }
        }
    }
}

@Composable
private fun QuantBadge(quant: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            quant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 从文件名里粗提量化标识，例如 q4_k_m / q8_0 / iq3_xxs / bf16。 */
private fun quantizationOf(name: String): String? {
    val m = Regex(
        "(?i)(?<![a-z0-9])(iq\\d+(?:_[a-z0-9]+)*|q\\d+(?:_[a-z0-9]+)*|bf16|f16|f32)(?![a-z0-9])"
    ).find(name) ?: return null
    return m.value.uppercase()
}

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}
