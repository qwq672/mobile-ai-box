package com.aibox.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aibox.R
import com.aibox.domain.search.SearchableEntity
import com.aibox.domain.search.SearchDomain
import com.aibox.domain.shortcut.ShortcutTargetType
import com.aibox.ui.chat.ChatScreen
import com.aibox.ui.audio.AsrScreen
import com.aibox.ui.audio.TtsScreen
import com.aibox.ui.history.HistoryScreen
import com.aibox.ui.home.HomeScreen
import com.aibox.ui.models.ModelsScreen
import com.aibox.ui.optimization.OptimizationSettingsScreen
import com.aibox.ui.search.SearchScreen
import com.aibox.ui.settings.SettingsScreen
import com.aibox.ui.shortcut.ShortcutEditorScreen
import com.aibox.ui.thermal.ThermalMonitorScreen
import com.aibox.ui.tool.ToolSettingsScreen
import com.aibox.ui.modules.ModulesScreen
import com.aibox.ui.benchmark.BenchmarkScreen

sealed class Dest(val route: String, val label: Int, val icon: ImageVector) {
    // E 主页 Hybrid：Home 作为新启动路由；Chat 保留为子页面（从 Home 进入）
    data object Home             : Dest("home",      R.string.nav_home,    Icons.Outlined.Home)
    data object Chat             : Dest("chat",     R.string.nav_chat,    Icons.Outlined.ChatBubbleOutline)
    data object ShortcutEditor   : Dest("shortcut_editor", R.string.nav_settings, Icons.Outlined.Settings)
    data object Models   : Dest("models",   R.string.nav_models,   Icons.Outlined.Memory)
    data object History  : Dest("history",  R.string.nav_chat,     Icons.Outlined.History) // 复用 nav_chat label
    data object Settings : Dest("settings", R.string.nav_settings, Icons.Outlined.Settings)
    data object Search   : Dest("search",   R.string.nav_settings, Icons.Outlined.Settings)
    data object Thermal  : Dest("thermal",  R.string.nav_settings, Icons.Outlined.Thermostat)
    // B7 工具设置面板独立路由（从 Settings 进入）
    data object ToolSettings : Dest("tool_settings", R.string.nav_settings, Icons.Outlined.Settings)
    // G-settings-fix：推理优化设置页独立路由（从 Settings 进入）
    // 把开关式优化（Flash Attention / 模型预加载 / KV 连续布局 / 空闲超时卸载 等）统一归口
    data object Optimization : Dest("optimization", R.string.nav_settings, Icons.Outlined.Settings)
    // D1 / D2 语音识别 / 语音合成独立路由（从 Home 进入）
    data object Asr : Dest("asr", R.string.nav_settings, Icons.Outlined.Mic)
    data object Tts : Dest("tts",  R.string.nav_settings, Icons.Outlined.VolumeUp)
    // 模块管理：扫描所有 AIBox 模块 APK（宿主 + Easy Chat + Translate + LiteRT + …）
    data object Modules : Dest("modules", R.string.nav_settings, Icons.Filled.Apps)
    // 基准测试：独立路由（从 Home "基准测试" tile 进入）
    data object Benchmark : Dest("benchmark", R.string.nav_settings, Icons.Filled.Analytics)
    // 翻译：独立路由（从 Home "翻译" tile 进入；目前占位跳 Chat,后续接 Translate 模块）
    data object Translate : Dest("translate", R.string.nav_settings, Icons.Filled.Public)
    // 图像生成：占位路由（等 ImageGen 模块开发）
    data object ImageGen : Dest("image_gen", R.string.nav_settings, Icons.Filled.Add)
}

/** 抽屉菜单项（不含 History，History 是独立入口） */
private val drawerItems = listOf(Dest.Home, Dest.Chat, Dest.Models, Dest.Settings)

/**
 * 提供 drawerState 给子屏幕，让它们在 TopAppBar 里放汉堡图标。
 * 用法：val drawerState = LocalDrawerState.current
 *      IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Menu) }
 */
val LocalDrawerState = staticCompositionLocalOf<androidx.compose.material3.DrawerState> {
    error("LocalDrawerState not provided")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        // 限制抽屉宽度，不铺满全屏
        modifier = Modifier,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(280.dp)
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text(
                        "AIBox",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "本地大模型推理",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                drawerItems.forEach { d ->
                    NavigationDrawerItem(
                        label = { Text(stringResource(d.label)) },
                        selected = currentRoute == d.route,
                        icon = { Icon(d.icon, contentDescription = null) },
                        colors = NavigationDrawerItemDefaults.colors(),
                        onClick = {
                            scope.launch { drawerState.close() }
                            if (currentRoute != d.route) {
                                nav.navigate(d.route) {
                                    popUpTo(Dest.Home.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
                // 历史话题独立入口
                NavigationDrawerItem(
                    label = { Text("历史话题") },
                    selected = currentRoute == Dest.History.route,
                    icon = { Icon(Icons.Outlined.History, contentDescription = null) },
                    colors = NavigationDrawerItemDefaults.colors(),
                    onClick = {
                        scope.launch { drawerState.close() }
                        if (currentRoute != Dest.History.route) {
                            nav.navigate(Dest.History.route) {
                                popUpTo(Dest.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    ) {
        CompositionLocalProvider(LocalDrawerState provides drawerState) {
            NavHost(
                navController = nav,
                startDestination = Dest.Home.route,
                enterTransition = {
                    fadeIn(tween(200)) + slideInHorizontally(tween(200)) { it / 12 }
                },
                exitTransition = { fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { -it / 12 } },
                popEnterTransition = {
                    fadeIn(tween(200)) + slideInHorizontally(tween(200)) { -it / 12 }
                },
                popExitTransition = { fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { it / 12 } }
            ) {
                // E 主页 Hybrid：Home 为启动路由
                composable(Dest.Home.route) {
                    HomeScreen(
                        onOpenChat = { shortcut ->
                            // 通过 SavedStateHandle 传 shortcutId（ChatScreen 加载后应用预填；TODO）
                            nav.navigate(Dest.Chat.route) {
                                popUpTo(Dest.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                            val sid = shortcut?.id
                            if (sid != null) {
                                nav.currentBackStackEntry?.savedStateHandle?.set("shortcutId", sid)
                            }
                        },
                        onOpenImageGen = { shortcut ->
                            // 跳到图像生成页(占位页面,等模块装了才有真实功能)
                            nav.navigate(Dest.ImageGen.route) {
                                popUpTo(Dest.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onOpenASR = {
                            nav.navigate(Dest.Asr.route) {
                                popUpTo(Dest.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onOpenTTS = {
                            nav.navigate(Dest.Tts.route) {
                                popUpTo(Dest.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onOpenTranslate = {
                            // 跳到翻译页(占位页面,等 Translate 模块装了才有真实功能)
                            nav.navigate(Dest.Translate.route) {
                                popUpTo(Dest.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onOpenBenchmark = {
                            // 跳到基准测试独立页(已落地 BenchmarkScreen + V1→V2 适配器)
                            nav.navigate(Dest.Benchmark.route) {
                                popUpTo(Dest.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onOpenModuleManager = {
                            // 跳到独立的模块管理页（扫描所有 AIBox 模块 APK）
                            nav.navigate(Dest.Modules.route) {
                                popUpTo(Dest.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onOpenSettings = {
                            nav.navigate(Dest.Settings.route) {
                                popUpTo(Dest.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onOpenSearch = {
                            nav.navigate(Dest.Search.route)
                        },
                        onOpenShortcutEditor = { shortcutId ->
                            // 通过 ShortcutEditorLauncher 桥接编辑 id（避免 SavedStateHandle 在嵌套
                            // navigate 场景下的不可靠性；与 pendingDraft 同源）。
                            shortcutId?.let {
                                com.aibox.ui.shortcut.ShortcutEditorLauncher.setPendingEditId(it)
                            }
                            nav.navigate(Dest.ShortcutEditor.route) {
                                popUpTo(Dest.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onOpenModelDetail = { model ->
                            // 暂无模型详情页；跳到 Models 页（用户可在该页加载该模型）
                            nav.navigate(Dest.Models.route) {
                                popUpTo(Dest.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
                composable(Dest.Chat.route) { backStackEntry ->
                    val sid = backStackEntry.savedStateHandle
                        .get<Long>("sessionId") ?: 0L
                    ChatScreen(
                        sessionId = sid,
                        onOpenSearch = { nav.navigate(Dest.Search.route) },
                        onOpenShortcutEditor = {
                            // E 主页 Hybrid：ChatScreen "保存为快捷方式" 菜单
                            // 草稿已通过 ShortcutEditorLauncher.setPendingDraft 传递，编辑器 init{} 自动消费
                            nav.navigate(Dest.ShortcutEditor.route) {
                                popUpTo(Dest.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                    // 未来工作：从 HomeScreen 跳转时通过 SavedStateHandle 传 shortcutId，
                    // 让 ChatScreen 加载后应用 shortcut 的预填配置（systemPrompt / temperature 等）。
                    // 当前 onOpenChat 回调已设 shortcutId 到 SavedStateHandle（HomeScreen 内），
                    // 但 ChatViewModel 未消费 → 等后续重构 ChatViewModel 支持运行时切换 systemPrompt 再启用。
                }
                composable(Dest.ShortcutEditor.route) {
                    // shortcutId 通过 ShortcutEditorLauncher.setPendingEditId 传递（HomeScreen 长按入口）；
                    // pendingDraft 通过 ShortcutEditorLauncher.setPendingDraft 传递（ChatScreen 保存为快捷方式入口）。
                    // initialShortcutId 参数固定传 null，由 ViewModel init{} 消费 launcher 中的数据。
                    ShortcutEditorScreen(
                        initialShortcutId = null,
                        initialTargetType = ShortcutTargetType.CHAT,
                        onBack = { nav.popBackStack() }
                    )
                }
                composable(Dest.Models.route)   { ModelsScreen() }
                composable(Dest.History.route)  {
                    HistoryScreen(
                        onOpenSession = { sid ->
                            // 跳转到 Chat 页，并把 sessionId 传给它
                            nav.navigate(Dest.Chat.route) {
                                popUpTo(Dest.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                            // 通过 SavedStateHandle 传递 sessionId
                            nav.currentBackStackEntry?.savedStateHandle?.set("sessionId", sid)
                        },
                        onCreateSession = { sid ->
                            nav.navigate(Dest.Chat.route) {
                                popUpTo(Dest.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                            nav.currentBackStackEntry?.savedStateHandle?.set("sessionId", sid)
                        }
                    )
                }
                composable(Dest.Settings.route) {
                    SettingsScreen(
                        onOpenThermal = { nav.navigate(Dest.Thermal.route) },
                        onOpenToolSettings = { nav.navigate(Dest.ToolSettings.route) },
                        // G-settings-fix：传递推理优化页跳转回调
                        onOpenOptimization = { nav.navigate(Dest.Optimization.route) }
                    )
                }
                composable(Dest.Search.route) {
                    SearchScreen(
                        onBack = { nav.popBackStack() },
                        onNavigate = { entity -> dispatchSearchClick(entity, nav) }
                    )
                }
                composable(Dest.Thermal.route) {
                    ThermalMonitorScreen(
                        onBack = { nav.popBackStack() }
                    )
                }
                composable(Dest.ToolSettings.route) {
                    ToolSettingsScreen(
                        onBack = { nav.popBackStack() }
                    )
                }
                // G-settings-fix：推理优化设置页（开关式优化统一归口）
                composable(Dest.Optimization.route) {
                    OptimizationSettingsScreen()
                }
                composable(Dest.Asr.route) {
                    AsrScreen(
                        onBack = { nav.popBackStack() }
                    )
                }
                composable(Dest.Tts.route) {
                    TtsScreen(
                        onBack = { nav.popBackStack() }
                    )
                }
                composable(Dest.Modules.route) {
                    ModulesScreen(
                        onBack = { nav.popBackStack() }
                    )
                }
                composable(Dest.Benchmark.route) {
                    BenchmarkScreen(
                        onBack = { nav.popBackStack() }
                    )
                }
                composable(Dest.Translate.route) {
                    // 翻译页占位:目前显示"需要安装翻译模块"提示
                    // 后续接入 Translate 模块后改为真实 TranslateScreen
                    TranslatePlaceholderScreen(onBack = { nav.popBackStack() })
                }
                composable(Dest.ImageGen.route) {
                    // 图像生成页占位:目前显示"需要安装图像生成模块"提示
                    ImageGenPlaceholderScreen(onBack = { nav.popBackStack() })
                }
            }
        }
    }
}

/**
 * 翻译页占位:目前无 Translate 模块时显示。
 * 等 Translate 模块 APK 安装后,宿主扫描发现模块,本页面替换为真实 TranslateScreen。
 */
@Composable
private fun TranslatePlaceholderScreen(onBack: () -> Unit) {
    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { androidx.compose.material3.Text("翻译") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { padding ->
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Filled.Public,
                    contentDescription = null,
                    modifier = androidx.compose.ui.Modifier.size(48.dp),
                    tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(16.dp))
                androidx.compose.material3.Text(
                    "翻译模块尚未安装",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                )
                androidx.compose.material3.Text(
                    "请到「模块管理」或插件市场安装 Translate 模块 APK",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 图像生成页占位:目前无 ImageGen 模块时显示。
 */
@Composable
private fun ImageGenPlaceholderScreen(onBack: () -> Unit) {
    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { androidx.compose.material3.Text("图像生成") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { padding ->
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Filled.Add,
                    contentDescription = null,
                    modifier = androidx.compose.ui.Modifier.size(48.dp),
                    tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(16.dp))
                androidx.compose.material3.Text(
                    "图像生成模块尚未安装",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                )
                androidx.compose.material3.Text(
                    "请到「模块管理」或插件市场安装 ImageGen 模块 APK",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 把搜索结果点击分发到对应页面（在 AppNav 内集中处理，避免 SearchScreen 持有 navController 引用）。
 *
 * 分发规则（与各 [SearchableEntity.searchData] 约定对齐）：
 * - CHAT_CONVERSATION / CHAT_MESSAGE：跳到 Chat 页 + SavedStateHandle 设 sessionId
 * - MODEL：跳到 Models 页（用户可在该页加载该模型）
 * - SETTING：按 searchData["screen"] 分发到 Settings / Optimization（G-settings-fix：Optimization 路由已加）
 * - MODULE：跳到 Settings（暂无模块管理页）
 * - TOOL：no-op（B1 接入 ToolRegistry 后扩展）
 * - BENCHMARK：跳到 Settings（暂无 benchmark 路由 → 后续 P2 已有 BenchmarkScreen 但未接路由，待加）
 */
private fun dispatchSearchClick(entity: SearchableEntity, nav: androidx.navigation.NavController) {
    val data = entity.searchData
    when (entity.searchDomain) {
        SearchDomain.CHAT_CONVERSATION,
        SearchDomain.CHAT_MESSAGE -> {
            val sid = (data["sessionId"] as? Number)?.toLong() ?: 0L
            nav.navigate(Dest.Chat.route) {
                popUpTo(Dest.Home.route) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            nav.currentBackStackEntry?.savedStateHandle?.set("sessionId", sid)
        }
        SearchDomain.MODEL -> {
            nav.navigate(Dest.Models.route) {
                popUpTo(Dest.Home.route) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        SearchDomain.SETTING -> {
            // G-settings-fix：Optimization 已有独立路由，按 screen 字段分发
            val screen = data["screen"] as? String
            val target = if (screen == "optimization") Dest.Optimization.route else Dest.Settings.route
            nav.navigate(target) {
                popUpTo(Dest.Home.route) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        SearchDomain.MODULE -> {
            // 模块管理页已落地：跳到 ModulesScreen 而不是 Settings
            nav.navigate(Dest.Modules.route) {
                popUpTo(Dest.Home.route) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        SearchDomain.TOOL -> {
            // B7 工具设置面板已落地：点击工具搜索结果跳到工具设置页
            nav.navigate(Dest.ToolSettings.route) {
                popUpTo(Dest.Home.route) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        SearchDomain.BENCHMARK -> {
            // 暂无 Benchmark 独立路由；跳 Settings（用户可手动进 Benchmark 页）
            nav.navigate(Dest.Settings.route) {
                popUpTo(Dest.Home.route) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
}
