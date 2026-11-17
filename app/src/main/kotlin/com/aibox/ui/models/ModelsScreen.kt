package com.aibox.ui.models

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.ReceiptLong
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aibox.data.model.ModelInfo
import com.aibox.domain.download.RemoteGgufFile
import com.aibox.ui.components.ModelCard
import com.aibox.ui.download.DownloadViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ModelsScreen(
    vm: ModelsViewModel = viewModel(),
    dl: DownloadViewModel = viewModel(),
    onAddCloudModel: () -> Unit = {},
    onEditCloudModel: (Long) -> Unit = {},
    onOpenNetworkDownload: () -> Unit = {},
    onOpenLogs: () -> Unit = {}
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val dlUi by dl.ui.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    val settingsRepo = remember { com.aibox.AIBoxApp.instance.container.settingsRepository }
    val scope = rememberCoroutineScope()

    var addSheetOpen by remember { mutableStateOf(false) }

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

    val drawerState = com.aibox.ui.nav.LocalDrawerState.current

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Outlined.Menu, contentDescription = "菜单")
                    }
                },
                title = { Text("模型", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = onOpenLogs) {
                        Icon(Icons.Outlined.ReceiptLong, contentDescription = "运行日志")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("添加模型") },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = { addSheetOpen = true }
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

            // ===== Section 0: 云端模型 =====
            stickyHeader(key = "header_cloud") {
                SectionHeader(
                    icon = Icons.Outlined.Cloud,
                    title = "云端模型",
                    count = ui.cloudModels.size
                )
            }
            if (ui.cloudModels.isEmpty()) {
                item(key = "empty_cloud") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "还没有云端模型",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "可以把 DeepSeek / OpenAI / Ollama 等配置成独立模型，和本地模型一起切换。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FilledTonalButton(onClick = onAddCloudModel) {
                                Icon(Icons.Outlined.Add, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text("添加云端模型")
                            }
                        }
                    }
                }
            } else {
                items(items = ui.cloudModels, key = { "cloud_${it.id}" }) { profile ->
                    CloudModelCard(
                        profile = profile,
                        isActive = ui.activeCloudId == profile.id,
                        onActivate = { vm.activateCloud(profile) },
                        onEdit = { onEditCloudModel(profile.id) },
                        onDelete = { vm.deleteCloud(profile) }
                    )
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
                    // 删除按钮放进卡片内部，避免卡片被右侧按钮挤窄、按钮悬空
                    Box {
                        ModelCard(
                            model = m,
                            isActive = ui.activeId == m.id,
                            loading = ui.loading && ui.activeId == m.id,
                            onClick = { vm.activate(m.id) },
                            modifier = Modifier.padding(end = 40.dp)
                        )
                        IconButton(
                            onClick = { deleteTarget = m },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 2.dp, end = 0.dp)
                                .size(36.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
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
                    task = dlUi.tasks[p.file],
                    onDownload = { dl.downloadPrefab(p) },
                    onPause = { dl.pausePrefab(p) },
                    onResume = { dl.downloadPrefab(p) },
                    onStop = { dl.stopPrefab(p) }
                )
            }

            // ===== Section 3: 下载设置 =====
            stickyHeader(key = "header_dl") {
                SectionHeader(
                    icon = Icons.Outlined.CloudDownload,
                    title = "下载设置"
                )
            }

            item(key = "dl_settings") {
                DownloadSourceCard(
                    sources = dlUi.sources,
                    currentSourceId = dlUi.currentSourceId,
                    fallback = dlUi.hfMirrorFallback,
                    onSelectSource = dl::selectSource,
                    onFallbackChange = { on ->
                        scope.launch { settingsRepo.setHfMirrorFallback(on) }
                    }
                )
            }

            item(key = "network_download") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenNetworkDownload)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("搜索并下载模型", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "按关键词搜索仓库，选择量化版本后下载",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // 给 FAB 留底（等比 FAB 高度 + 边距，保证最后一项不被遮挡）
            item(key = "bottom_spacer") {
                Spacer(Modifier.height(120.dp))
            }
        }
    }

    // 统一「添加模型」入口：云端 / 本地 / 网络下载
    if (addSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { addSheetOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(Modifier.padding(bottom = 16.dp)) {
                Text(
                    "添加模型",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                AddModelAction(
                    icon = Icons.Outlined.Cloud,
                    title = "添加云端模型",
                    subtitle = "DeepSeek / OpenAI / Ollama 等 API 服务"
                ) {
                    addSheetOpen = false
                    onAddCloudModel()
                }
                AddModelAction(
                    icon = Icons.Outlined.Memory,
                    title = "添加已下载的本地模型",
                    subtitle = "导入手机里的 .gguf 文件"
                ) {
                    addSheetOpen = false
                    launchImport()
                }
                AddModelAction(
                    icon = Icons.Outlined.CloudDownload,
                    title = "从网络下载模型",
                    subtitle = "通过 Hugging Face / HF-Mirror / 魔搭搜索并下载"
                ) {
                    addSheetOpen = false
                    onOpenNetworkDownload()
                }
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
    // 轻量分组头：不再做成“大按钮”，保证和卡片分层；sticky 时用背景色挡住内容
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            if (count != null) {
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
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

/* ---------------- 下载源设置 ---------------- */

@Composable
private fun DownloadSourceCard(
    sources: List<com.aibox.domain.download.ModelSource>,
    currentSourceId: String,
    fallback: Boolean,
    onSelectSource: (String) -> Unit,
    onFallbackChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("下载源", style = MaterialTheme.typography.titleSmall)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                sources.forEach { src ->
                    val short = when (src.id) {
                        "hf" -> "Hugging Face"
                        "hf_mirror" -> "HF-Mirror"
                        "modelscope" -> "魔搭"
                        else -> src.displayNameZh
                    }
                    FilterChip(
                        selected = src.id == currentSourceId,
                        onClick = { onSelectSource(src.id) },
                        modifier = Modifier.weight(1f),
                        label = {
                            Text(
                                short,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    )
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("HF-Mirror 失败时改用 .net", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "hf-mirror.com 下载失败时自动重试 hf-mirror.net",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = fallback, onCheckedChange = onFallbackChange)
            }
        }
    }
}

/* ---------------- 添加模型入口 ---------------- */

@Composable
private fun AddModelAction(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/* ---------------- Download Rows ---------------- */

@Composable
private fun PrefabRow(
    label: String,
    note: String,
    task: DownloadViewModel.DownloadTask?,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    val state = task?.state ?: DownloadViewModel.TaskState.IDLE
    val pct = task?.percent ?: 0

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
            // 标题永远保留；下载状态与进度条放在标题下方，行高稳定
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                when (state) {
                    DownloadViewModel.TaskState.RUNNING -> {
                        Text(
                            buildString {
                                append("$pct%")
                                val total = task?.total ?: -1
                                if (total > 0) {
                                    append(" · ${formatBytes(task?.downloaded ?: 0)} / ${formatBytes(total)}")
                                }
                                val speed = task?.speed ?: 0
                                if (speed > 0) append(" · ${formatSpeed(speed)}")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        LinearProgressIndicator(
                            progress = { pct / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(MaterialTheme.shapes.small),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    }
                    DownloadViewModel.TaskState.PAUSED -> Text(
                        "已暂停 · $pct%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    DownloadViewModel.TaskState.FAILED -> Text(
                        "下载失败，可重试",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    DownloadViewModel.TaskState.DONE -> Text(
                        "已下载",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    else -> Text(
                        note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            when (state) {
                DownloadViewModel.TaskState.RUNNING -> Row {
                    SmallIconButton(Icons.Outlined.Pause, "暂停", onPause)
                    SmallIconButton(Icons.Outlined.Close, "停止", onStop)
                }
                DownloadViewModel.TaskState.PAUSED -> Row {
                    SmallIconButton(Icons.Outlined.PlayArrow, "继续", onResume)
                    SmallIconButton(Icons.Outlined.Close, "停止", onStop)
                }
                DownloadViewModel.TaskState.DONE -> AssistChip(
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
                else -> AssistChip(
                    onClick = onDownload,
                    label = {
                        Text(
                            if (state == DownloadViewModel.TaskState.FAILED) "重试" else "下载",
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
            }
        }
    }
}

@Composable
private fun SmallIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
internal fun RemoteFileRow(file: RemoteGgufFile, progress: Int, onDownload: () -> Unit) {
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
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(MaterialTheme.shapes.small),
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Verified,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.size(6.dp))
                        Text("已下载", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
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

/* ---------------- Utils ---------------- */

private fun formatSize(mb: Long): String =
    if (mb >= 1024) "%.2f GB".format(mb / 1024.0) else "$mb MB"

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "$bytes B"
}

internal fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1024L * 1024 -> "%.1f MB/s".format(bytesPerSecond / 1024.0 / 1024.0)
    bytesPerSecond >= 1024L -> "%.0f KB/s".format(bytesPerSecond / 1024.0)
    else -> "$bytesPerSecond B/s"
}

/**
 * 云端模型卡片：与本地模型并列展示，点击即切换。
 *
 * 徽标用于一眼看出这个配置支持什么（多模态 / 音频 / 供应商自带联网）。
 */
@Composable
private fun CloudModelCard(
    profile: com.aibox.data.repo.CloudModelProfile,
    isActive: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onActivate() },
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Cloud,
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    profile.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${profile.model} · ${profile.baseUrl}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                val badges = buildList {
                    if (profile.supportsVision) add("多模态")
                    if (profile.supportsAudio) add("音频")
                    if (profile.builtinWebSearch) add("自带联网")
                    if (profile.builtinUrlReader) add("自带URL阅读")
                }
                if (badges.isNotEmpty()) {
                    Text(
                        badges.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (isActive) {
                    Text(
                        "当前使用中",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "编辑")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
