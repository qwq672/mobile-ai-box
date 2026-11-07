package com.pocketllm.ui.models

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketllm.data.model.ModelInfo
import com.pocketllm.domain.download.RemoteGgufFile
import com.pocketllm.ui.components.ModelCard
import com.pocketllm.ui.download.DownloadViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ModelsScreen(
    vm: ModelsViewModel = viewModel(),
    dl: DownloadViewModel = viewModel()
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val dlUi by dl.ui.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { vm.importFromUri(it) } }

    val launchImport = {
        pickFile.launch(arrayOf("application/octet-stream", "*/*"))
    }

    var deleteTarget by remember { mutableStateOf<ModelInfo?>(null) }

    // 模型侧错误用 snackbar
    LaunchedEffect(ui.error) {
        ui.error?.let { msg ->
            snackbarHost.showSnackbar(msg)
            vm.clearError()
        }
    }
    // 下载侧错误用 snackbar
    LaunchedEffect(dlUi.error) {
        dlUi.error?.let { msg ->
            snackbarHost.showSnackbar(msg)
            dl.clearError()
        }
    }

    val drawerState = com.pocketllm.ui.nav.LocalDrawerState.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Outlined.Menu, contentDescription = "菜单")
                    }
                },
                title = { Text("模型", style = MaterialTheme.typography.titleLarge) }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("导入本地模型") },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = launchImport
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ===== 顶部进度条（导入 / 加载时） =====
            if (ui.importing || ui.loading) {
                item(key = "top_progress") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            if (ui.importing) "正在导入…" else "正在加载模型…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ===== Section 1: 已下载模型 =====
            stickyHeader(key = "header_local") {
                SectionHeader(
                    icon = Icons.Outlined.Memory,
                    title = "已下载模型",
                    count = ui.models.size
                )
            }

            if (ui.models.isEmpty() && !ui.importing) {
                item(key = "empty_local") {
                    EmptyDownloadedState(onImport = launchImport)
                }
            } else {
                items(items = ui.models, key = { it.id }) { m ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ModelCard(
                            modifier = Modifier.weight(1f),
                            model = m,
                            isActive = ui.activeId == m.id,
                            loading = ui.loading && ui.activeId == m.id,
                            onClick = { vm.activate(m.id) }
                        )
                        Spacer(Modifier.size(8.dp))
                        IconButton(onClick = { deleteTarget = m }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // ===== Section 2: 推荐一键下载 =====
            stickyHeader(key = "header_prefab") {
                SectionHeader(
                    icon = Icons.Outlined.Verified,
                    title = "推荐一键下载",
                    count = dl.prefabs.size
                )
            }

            items(items = dl.prefabs, key = { it.file }) { p ->
                PrefabRow(
                    label = p.label,
                    note = p.note,
                    progress = dlUi.progress[p.file] ?: 0,
                    onDownload = { dl.downloadPrefab(p) }
                )
            }

            // ===== Section 3: 从仓库浏览 =====
            stickyHeader(key = "header_browse") {
                SectionHeader(
                    icon = Icons.Outlined.Search,
                    title = "从仓库浏览"
                )
            }

            item(key = "browse_input") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 源 chip 行
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            dlUi.sources.forEach { src ->
                                FilterChip(
                                    selected = src.id == dlUi.currentSourceId,
                                    onClick = { dl.selectSource(src.id) },
                                    label = { Text(src.displayNameZh) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = dlUi.repoInput,
                            onValueChange = dl::onRepoInput,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("仓库 ID") },
                            placeholder = { Text("例：Qwen/Qwen2.5-1.5B-Instruct-GGUF") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.small
                        )
                        Button(
                            onClick = { dl.listFiles() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !dlUi.loading
                        ) {
                            Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(if (dlUi.loading) "正在列出…" else "列出文件")
                        }
                        if (dlUi.loading) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            if (dlUi.files.isEmpty() && !dlUi.loading) {
                item(key = "empty_files") {
                    EmptyBrowseState()
                }
            }

            items(items = dlUi.files, key = { it.path }) { f ->
                RemoteFileRow(
                    file = f,
                    progress = dlUi.progress[f.path] ?: 0,
                    onDownload = { dl.download(dlUi.repoInput, f) }
                )
            }

            // 给 FAB 留底
            item(key = "bottom_spacer") {
                Spacer(Modifier.height(88.dp))
            }
        }
    }

    // 删除确认对话框
    deleteTarget?.let { m ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除模型？") },
            text = { Text("将永久删除 ${m.name}（${formatSize(m.sizeMb)}）。此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(m)
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

/* ---------------- Section Header ---------------- */

@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
    count: Int? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            if (count != null) {
                Spacer(Modifier.size(2.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        count.toString(),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/* ---------------- Empty States ---------------- */

@Composable
private fun EmptyDownloadedState(onImport: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(36.dp)
                )
            }
            Text(
                "还没有模型",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "从下方「推荐一键下载」拉一个，或点右下角按钮导入已下载的 .gguf 文件",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onImport) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("导入本地模型")
            }
        }
    }
}

@Composable
private fun EmptyBrowseState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Outlined.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp)
            )
            Text(
                "暂无文件",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "输入仓库 ID 后点击「列出文件」",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/* ---------------- Download Rows ---------------- */

@Composable
private fun PrefabRow(label: String, note: String, progress: Int, onDownload: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (progress in 1..99) {
                Column(
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .widthIn(min = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "$progress%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (progress >= 100) {
                AssistChip(
                    onClick = onDownload,
                    label = { Text("已下载", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Verified, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            } else {
                AssistChip(
                    onClick = onDownload,
                    label = { Text("下载", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
            }
        }
    }
}

@Composable
private fun RemoteFileRow(file: RemoteGgufFile, progress: Int, onDownload: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
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
                    file.path.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
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
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("$progress%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                progress >= 100 -> {
                    Text("已下载", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
                else -> {
                    OutlinedButton(onClick = onDownload) {
                        Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("下载")
                    }
                }
            }
        }
    }
}

/* ---------------- Utils ---------------- */

private fun formatSize(mb: Long): String =
    if (mb >= 1024) "%.2f GB".format(mb / 1024.0) else "$mb MB"

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "$bytes B"
}
