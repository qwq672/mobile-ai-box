package com.pocketllm.ui.nav

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
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
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
import com.pocketllm.R
import com.pocketllm.ui.chat.ChatScreen
import com.pocketllm.ui.history.HistoryScreen
import com.pocketllm.ui.models.ModelsScreen
import com.pocketllm.ui.settings.SettingsScreen

sealed class Dest(val route: String, val label: Int, val icon: ImageVector) {
    data object Chat     : Dest("chat",     R.string.nav_chat,     Icons.Outlined.ChatBubbleOutline)
    data object Models   : Dest("models",   R.string.nav_models,   Icons.Outlined.Memory)
    data object History  : Dest("history",  R.string.nav_chat,     Icons.Outlined.History) // 复用 nav_chat label
    data object Settings : Dest("settings", R.string.nav_settings, Icons.Outlined.Settings)
}

/** 抽屉菜单项（不含 History，History 是独立入口） */
private val drawerItems = listOf(Dest.Chat, Dest.Models, Dest.Settings)

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
                        "PocketLLM",
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
                                    popUpTo(Dest.Chat.route) { saveState = true }
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
                                popUpTo(Dest.Chat.route) { saveState = true }
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
                startDestination = Dest.Chat.route,
                enterTransition = {
                    fadeIn(tween(200)) + slideInHorizontally(tween(200)) { it / 12 }
                },
                exitTransition = { fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { -it / 12 } },
                popEnterTransition = {
                    fadeIn(tween(200)) + slideInHorizontally(tween(200)) { -it / 12 }
                },
                popExitTransition = { fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { it / 12 } }
            ) {
                composable(Dest.Chat.route) { backStackEntry ->
                    val sid = backStackEntry.savedStateHandle
                        .get<Long>("sessionId") ?: 0L
                    ChatScreen(sessionId = sid)
                }
                composable(Dest.Models.route)   { ModelsScreen() }
                composable(Dest.History.route)  {
                    HistoryScreen(
                        onOpenSession = { sid ->
                            // 跳转到 Chat 页，并把 sessionId 传给它
                            nav.navigate(Dest.Chat.route) {
                                popUpTo(Dest.Chat.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                            // 通过 SavedStateHandle 传递 sessionId
                            nav.currentBackStackEntry?.savedStateHandle?.set("sessionId", sid)
                        },
                        onCreateSession = { sid ->
                            nav.navigate(Dest.Chat.route) {
                                popUpTo(Dest.Chat.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                            nav.currentBackStackEntry?.savedStateHandle?.set("sessionId", sid)
                        }
                    )
                }
                composable(Dest.Settings.route) { SettingsScreen() }
            }
        }
    }
}
