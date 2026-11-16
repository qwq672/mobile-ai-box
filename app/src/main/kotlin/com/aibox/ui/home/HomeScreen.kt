package com.aibox.ui.home

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aibox.AIBoxApp
import com.aibox.data.model.ModelInfo
import com.aibox.domain.shortcut.Shortcut
import com.aibox.domain.shortcut.ShortcutTargetType
import com.aibox.ui.components.unified.UnifiedCard
import com.aibox.ui.components.unified.UnifiedCardTone
import com.aibox.ui.shortcut.ShortcutRow
import com.aibox.ui.thermal.ThermalMonitorCard
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.DesignTokens
import com.aibox.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 主页 Screen（E 主页 Hybrid 设计）。
 *
 * 布局：TopAppBar（汉堡 + "AIBox" + 搜索 + 设置）+ LazyColumn 三大区块 + 末尾 ThermalMonitorCard。
 *
 * 区块顺序：
 * 1. **快捷方式**：[ShortcutRow] 横滑卡片列表 + "添加"卡片。
 * 2. **功能**：8 个 [FunctionTile] 两列网格（聊天 / 生图 / ASR / TTS / 翻译 / 基准 / 模块 / 设置）。
 * 3. **最近模型**：最多 5 个 [ModelListTile]。
 * 4. **散热监控**：复用 [ThermalMonitorCard]（来自 A2）。
 *
 * 性能：整页用 [LazyColumn]；内部嵌套滚动（[ShortcutRow] 的 LazyRow + 功能区两列）。
 *
 * 导航回调：
 * - [onOpenChat]：进入聊天页；shortcut 非空时预填配置（暂未集成，预留参数）。
 * - [onOpenImageGen]：进入图像生成页（占位）。
 * - [onOpenASR] / [onOpenTTS] / [onOpenTranslate] / [onOpenBenchmark]：占位回调。
 * - [onOpenModuleManager]：进入模块管理（当前跳到 Settings + 后续）。
 * - [onOpenSettings]：进入设置。
 * - [onOpenSearch]：进入统一搜索。
 * - [onOpenShortcutEditor]：进入快捷方式编辑器；参数为 null 表示新建，非 null 表示编辑指定 id。
 * - [onOpenModelDetail]：进入模型详情（当前跳到 Models 页加载该模型）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenChat: (Shortcut?) -> Unit,
    onOpenImageGen: (Shortcut?) -> Unit,
    onOpenASR: () -> Unit,
    onOpenTTS: () -> Unit,
    onOpenTranslate: () -> Unit,
    onOpenBenchmark: () -> Unit,
    onOpenModuleManager: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenShortcutEditor: (String?) -> Unit,
    onOpenModelDetail: (ModelInfo) -> Unit,
    vm: HomeViewModel = viewModel(
        factory = HomeViewModelFactory(
            shortcutRepository = AIBoxApp.instance.container.shortcutRepository,
            modelRepository = AIBoxApp.instance.container.modelRepository,
            benchmarkStorage = AIBoxApp.instance.container.benchmarkSystem.storage,
            pluginManager = AIBoxApp.instance.container.pluginManager
        )
    )
) {
    val shortcuts by vm.shortcuts.collectAsStateWithLifecycle()
    val recentModels by vm.recentModels.collectAsStateWithLifecycle()
    val stats by vm.functionStats.collectAsStateWithLifecycle()

    val drawerState = com.aibox.ui.nav.LocalDrawerState.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Outlined.Menu, contentDescription = "菜单")
                    }
                },
                title = {
                    Text(
                        "AIBox",
                        style = Type.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Outlined.Search, contentDescription = "搜索")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "设置")
                    }
                }
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            state = listState,
            contentPadding = PaddingValues(vertical = DesignTokens.spacing12),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing16)
        ) {
            // ============== 0. 问候卡片（时段问候 + 子问候 + 轮换建议） ==============
            item(key = "greeting") {
                GreetingCard(modifier = Modifier.padding(horizontal = DesignTokens.spacing16))
            }

            // ============== 1. 实时设备状态 ==============
            item(key = "live_status") {
                LiveDeviceStatus(
                    modifier = Modifier.padding(horizontal = DesignTokens.spacing16)
                )
            }

            // ============== 2. 快捷方式区 ==============
            item(key = "section_shortcuts_header") {
                SectionHeader(title = "快捷方式")
            }
            item(key = "shortcuts_row") {
                if (shortcuts.isEmpty()) {
                    EmptyShortcutsHint(onAdd = { onOpenShortcutEditor(null) })
                } else {
                    ShortcutRow(
                        shortcuts = shortcuts,
                        onShortcutClick = { shortcut ->
                            // 点击：标记使用 + 按 targetType 分发
                            vm.touchShortcutUsage(shortcut.id)
                            dispatchShortcutClick(
                                shortcut = shortcut,
                                onOpenChat = onOpenChat,
                                onOpenImageGen = onOpenImageGen,
                                onOpenASR = onOpenASR,
                                onOpenTTS = onOpenTTS,
                                onOpenTranslate = onOpenTranslate,
                                onOpenBenchmark = onOpenBenchmark
                            )
                        },
                        onAddShortcutClick = { onOpenShortcutEditor(null) },
                        onLongPress = { shortcut ->
                            // 长按：进入编辑器
                            onOpenShortcutEditor(shortcut.id)
                        }
                    )
                }
            }

            // ============== 3. 功能区 ==============
            item(key = "section_functions_header") {
                SectionHeader(title = "功能")
            }
            // 功能区两列网格；用 item 块 + 手动两两分组（避免在 LazyColumn 内嵌 LazyVerticalGrid）
            val functionTiles = buildList {
                add(FunctionTileData("聊天", "💬", "${stats.chatModelCount} 个模型") { onOpenChat(null) })
                add(FunctionTileData("图像生成", "🎨", "${stats.imageModelCount} 个模型") { onOpenImageGen(null) })
                add(FunctionTileData("语音识别", "🗣️", "系统默认", onOpenASR))
                add(FunctionTileData("语音合成", "📢", "系统默认", onOpenTTS))
                add(FunctionTileData("翻译", "🌐", "${stats.translateCount} 个快捷方式", onOpenTranslate))
                add(FunctionTileData("基准测试", "📊", "${stats.benchmarkCount} 个测试", onOpenBenchmark))
                add(FunctionTileData("模块管理", "🧩", "${stats.moduleCount} 个已安装", onOpenModuleManager))
                add(FunctionTileData("设置", "⚙️", "AIBox 配置", onOpenSettings))
            }
            // 每 2 个一组渲染为 Row
            functionTiles.chunked(2).forEachIndexed { idx, pair ->
                item(key = "functions_row_$idx") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = DesignTokens.spacing16),
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing12)
                    ) {
                        FunctionTile(
                            title = pair[0].title,
                            icon = pair[0].icon,
                            subtitle = pair[0].subtitle,
                            onClick = pair[0].onClick,
                            modifier = Modifier.weight(1f)
                        )
                        if (pair.size > 1) {
                            FunctionTile(
                                title = pair[1].title,
                                icon = pair[1].icon,
                                subtitle = pair[1].subtitle,
                                onClick = pair[1].onClick,
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            // ============== 4. 最近模型 ==============
            item(key = "section_recent_models_header") {
                SectionHeader(title = "最近模型")
            }
            if (recentModels.isEmpty()) {
                item(key = "empty_models") {
                    EmptyModelsHint()
                }
            } else {
                items(items = recentModels, key = { "model_${it.id}" }) { model ->
                    ModelListTile(
                        model = model,
                        onClick = { onOpenModelDetail(model) },
                        modifier = Modifier.padding(horizontal = DesignTokens.spacing16)
                    )
                }
            }

            // ============== 5. 设备状态 ==============
            item(key = "section_thermal_header") {
                SectionHeader(title = "设备状态")
            }
            item(key = "thermal_card") {
                ThermalMonitorCard(
                    modifier = Modifier.padding(horizontal = DesignTokens.spacing16)
                )
            }

            // 底部安全间距
            item(key = "bottom_spacer") {
                Spacer(Modifier.height(DesignTokens.spacing24))
            }
        }
    }
}

/* ============================================================
 * 分区标题（与 UnifiedSection 视觉一致，但用 fillMaxWidth 让它在 LazyColumn 中独立成 item）
 * ============================================================ */

@Composable
private fun SectionHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DesignTokens.spacing16, vertical = DesignTokens.spacing4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
    ) {
        // 左侧一小段主题色竖条，让分区标题更像“块”而不是漂浮的文字
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Colors.primary)
        )
        Text(
            text = title,
            style = Type.titleMedium,
            color = Colors.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/* ============================================================
 * 空状态提示
 * ============================================================ */

@Composable
private fun EmptyShortcutsHint(onAdd: () -> Unit) {
    UnifiedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DesignTokens.spacing16),
        tone = UnifiedCardTone.SURFACE_LOW,
        onClick = onAdd
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing12)
        ) {
            Text("✨", style = Type.headingMedium)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "还没有快捷方式",
                    style = Type.titleSmall,
                    color = Colors.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "把常用配置存为快捷方式，一键进入预设场景",
                    style = Type.bodySmall,
                    color = Colors.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyModelsHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DesignTokens.spacing16, vertical = DesignTokens.spacing8),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "暂无已下载模型",
                style = Type.bodyMedium,
                color = Colors.onSurfaceVariant
            )
            Text(
                "前往「设置 → 模型」下载或导入",
                style = Type.labelSmall,
                color = Colors.onSurfaceVariant
            )
        }
    }
}

/* ============================================================
 * 快捷方式点击分发：按 targetType 路由到对应功能页
 * ============================================================ */

private fun dispatchShortcutClick(
    shortcut: Shortcut,
    onOpenChat: (Shortcut?) -> Unit,
    onOpenImageGen: (Shortcut?) -> Unit,
    onOpenASR: () -> Unit,
    onOpenTTS: () -> Unit,
    onOpenTranslate: () -> Unit,
    onOpenBenchmark: () -> Unit
) {
    when (shortcut.targetType) {
        ShortcutTargetType.CHAT -> onOpenChat(shortcut)
        ShortcutTargetType.IMAGE_GEN -> onOpenImageGen(shortcut)
        ShortcutTargetType.ASR -> onOpenASR()
        ShortcutTargetType.TTS -> onOpenTTS()
        ShortcutTargetType.TRANSLATE -> onOpenTranslate()
        ShortcutTargetType.BENCHMARK -> onOpenBenchmark()
        ShortcutTargetType.PLUGIN_PAGE -> {
            // 占位：未来按 shortcut.presetConfig.customPayload 中的 pluginId 分发
        }
    }
}

/**
 * 功能区 tile 数据（用于在 LazyColumn 中分组成两列行）。
 */
private data class FunctionTileData(
    val title: String,
    val icon: String,
    val subtitle: String,
    val onClick: () -> Unit
)

/**
 * 首页实时设备状态条：温度 / CPU / GPU / 内存 / 电量。
 *
 * 数据全部来自 [com.aibox.util.ThermalMonitor] 的 StateFlow，采样周期由设置页控制（默认 2s）。
 * 右上角显示「x 秒前」，超过 2 个周期没更新会变红 —— 用户能一眼看出是数据没动还是界面卡了。
 */
@Composable
private fun LiveDeviceStatus(modifier: Modifier = Modifier) {
    val monitor = AIBoxApp.instance.container.thermalMonitor
    val celsius by monitor.celsius.collectAsStateWithLifecycle()
    val cpuUsage by monitor.cpuUsage.collectAsStateWithLifecycle()
    val cpuFreqKhz by monitor.cpuFreqKhz.collectAsStateWithLifecycle()
    val gpuUsage by monitor.gpuUsage.collectAsStateWithLifecycle()
    val memUsage by monitor.memUsage.collectAsStateWithLifecycle()
    val batteryLevel by monitor.batteryLevel.collectAsStateWithLifecycle()
    val lastUpdate by monitor.lastUpdateMs.collectAsStateWithLifecycle()
    val interval by monitor.intervalMs.collectAsStateWithLifecycle()

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    val agoSec = if (lastUpdate > 0L) ((nowMs - lastUpdate) / 1000).coerceAtLeast(0) else -1L
    val stale = agoSec < 0 || agoSec > (interval / 1000 + 2)

    UnifiedCard(modifier = modifier, tone = UnifiedCardTone.SURFACE_LOW) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (stale) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
            )
            Spacer(Modifier.size(DesignTokens.spacing8))
            Text(
                "设备实时状态",
                style = Type.labelLarge,
                color = Colors.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (agoSec < 0) "采样中…" else "${agoSec}s 前",
                style = Type.labelSmall,
                color = if (stale) MaterialTheme.colorScheme.error else Colors.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(DesignTokens.spacing8))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            LiveMetric("温度", if (celsius > 0) String.format("%.1f°C", celsius) else "--")
            LiveMetric("CPU", if (cpuUsage in 0..100) "$cpuUsage%" else "--", cpuFreqKhz)
            LiveMetric("GPU", if (gpuUsage in 0..100) "$gpuUsage%" else "--")
            LiveMetric("内存", if (memUsage in 0..100) "$memUsage%" else "--")
            LiveMetric("电量", if (batteryLevel in 0..100) "$batteryLevel%" else "--")
        }
    }
}

@Composable
private fun LiveMetric(title: String, value: String, freqKhz: Long = 0L) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = Type.labelLarge, color = Colors.onSurface)
        if (freqKhz > 0) {
            Text(
                String.format("%.1fGHz", freqKhz / 1_000_000f),
                style = Type.labelSmall,
                color = Colors.onSurfaceVariant
            )
        }
        Text(title, style = Type.labelSmall, color = Colors.onSurfaceVariant)
    }
}

