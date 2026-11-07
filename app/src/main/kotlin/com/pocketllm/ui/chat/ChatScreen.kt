package com.pocketllm.ui.chat

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketllm.data.model.ChatMessage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel = viewModel(),
    sessionId: Long = 0L
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbarHost = remember { SnackbarHostState() }

    // 当 sessionId 变化时加载该 session
    LaunchedEffect(sessionId) {
        if (sessionId != 0L) vm.loadSession(sessionId)
    }
    val context = LocalContext.current
    val drawerState = com.pocketllm.ui.nav.LocalDrawerState.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // 仅在消息列表 size 变化时滚动到底，避免每个 token 都触发 animateScrollToItem
    LaunchedEffect(ui.messages.size) {
        if (ui.messages.isNotEmpty()) {
            listState.animateScrollToItem(ui.messages.lastIndex)
        }
    }
    // 流式 token 进来时确保可见
    LaunchedEffect(ui.currentStream.length) {
        if (ui.currentStream.isNotEmpty() && !listState.isScrollInProgress) {
            listState.animateScrollToItem(ui.messages.size)
        }
    }
    // 错误用 snackbar
    LaunchedEffect(ui.errorMessage) {
        ui.errorMessage?.let { msg ->
            snackbarHost.showSnackbar(msg)
            vm.dismissError()
        }
    }

    var menuOpen by remember { mutableStateOf(false) }
    var clearOpen by remember { mutableStateOf(false) }

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
                        ui.modelName.ifEmpty { "未加载模型" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    // 紧凑的 stats 行：backend · tps · ctx
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Text(
                            ui.backendName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (ui.tokensPerSecond > 0) {
                            Dot()
                            Text(
                                "%.1ft/s".format(ui.tokensPerSecond),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (ui.contextMax > 0) {
                            Dot()
                            Text(
                                "${formatCtx(ui.contextUsed)}/${formatCtx(ui.contextMax)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    ThermalPill(percent = ui.thermalPercent)
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("切换模型") },
                                leadingIcon = {
                                    Icon(Icons.Outlined.SwapHoriz, contentDescription = null, modifier = Modifier.size(20.dp))
                                },
                                onClick = {
                                    menuOpen = false
                                    Toast.makeText(context, "请到「模型」页加载", Toast.LENGTH_SHORT).show()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("清空对话") },
                                leadingIcon = {
                                    Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(20.dp))
                                },
                                onClick = {
                                    menuOpen = false
                                    clearOpen = true
                                }
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            if (!ui.hasModel) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    EmptyModelState()
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items = ui.messages, key = { m -> "${m.id}-${m.ts}" }) { m ->
                        MessageBubble(
                            m,
                            onCopy = {
                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("PocketLLM", m.content))
                                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                            },
                            onRegenerate = if (m.role == "assistant") {
                                { vm.regenerate() }
                            } else null,
                            onDelete = { vm.deleteMessage(m) }
                        )
                    }
                    if (ui.currentStream.isNotEmpty()) {
                        item(key = "stream") {
                            MessageBubble(
                                ChatMessage(role = "assistant", content = ui.currentStream, sessionId = 0),
                                animate = true
                            )
                        }
                    }
                    if (ui.streaming && ui.currentStream.isEmpty()) {
                        item(key = "thinking") { ThinkingIndicator() }
                    }
                    // 让最后一条消息不被输入栏挡住
                    item(key = "bottom_spacer") { Spacer(Modifier.height(8.dp)) }
                }
            }

            // 输入栏始终在底部
            InputBar(
                streaming = ui.streaming,
                enabled = ui.hasModel,
                onSend = { txt -> vm.send(txt); keyboard?.hide() },
                onStop = { vm.stop() }
            )
        }
    }

    if (clearOpen) {
        AlertDialog(
            onDismissRequest = { clearOpen = false },
            title = { Text("清空对话？") },
            text = { Text("当前对话的 ${ui.messages.size} 条消息将被全部清空。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearMessages()
                    clearOpen = false
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { clearOpen = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun Dot() {
    Box(
        Modifier
            .size(3.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.outline)
    )
}

@Composable
private fun ThermalPill(percent: Int) {
    // percent < 0 表示未读到温度，显示 "—" 而不是误导性的 "0%"
    if (percent < 0) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.small
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.Outlined.Thermostat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }
    val color = when {
        percent >= 85 -> MaterialTheme.colorScheme.error
        percent >= 70 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val containerColor = when {
        percent >= 85 -> MaterialTheme.colorScheme.errorContainer
        percent >= 70 -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    Surface(
        color = containerColor,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Outlined.Thermostat,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
            Text(
                "$percent%",
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun EmptyModelState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(48.dp)
                )
            }
            Text(
                "尚未加载模型",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "请到「模型」页加载已下载的 gguf，或下载推荐模型",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val ctx = LocalContext.current
            Button(onClick = {
                Toast.makeText(ctx, "请到「模型」页加载", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Outlined.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("前往模型管理")
            }
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    Row(
        modifier = Modifier
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 头像
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            BouncingDot(delay = 0)
            BouncingDot(delay = 120)
            BouncingDot(delay = 240)
        }
    }
}

@Composable
private fun BouncingDot(delay: Int) {
    val transition = rememberInfiniteTransition(label = "bounce")
    val dy by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = InfiniteRepeatableSpec(
            animation = keyframes {
                durationMillis = 900
                delayMillis = delay
                0f at 0
                -6f at 200
                0f at 400
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "offset"
    )
    Box(
        Modifier
            .size(8.dp)
            .offset(y = dy.toInt().dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
    )
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    m: ChatMessage,
    animate: Boolean = false,
    onCopy: () -> Unit = {},
    onRegenerate: (() -> Unit)? = null,
    onDelete: () -> Unit = {}
) {
    val isUser = m.role == "user"
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.surfaceContainerHigh
    val onBubbleColor = if (isUser) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isUser) {
            Avatar(
                icon = Icons.Outlined.SmartToy,
                bg = MaterialTheme.colorScheme.primaryContainer,
                fg = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.width(8.dp))
        }
        Box {
            Surface(
                color = bubbleColor,
                shape = bubbleShape(isUser),
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .then(if (animate) Modifier.animateContentSize(animationSpec = tween(120)) else Modifier)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { menuOpen = true }
                    )
            ) {
                Text(
                    m.content,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = onBubbleColor,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false }
            ) {
                DropdownMenuItem(
                    text = { Text("复制") },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = { menuOpen = false; onCopy() }
                )
                if (onRegenerate != null) {
                    DropdownMenuItem(
                        text = { Text("重新生成") },
                        leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        onClick = { menuOpen = false; onRegenerate() }
                    )
                }
                DropdownMenuItem(
                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; onDelete() }
                )
            }
        }
        if (isUser) {
            Spacer(Modifier.width(8.dp))
            Avatar(
                icon = Icons.Outlined.Person,
                bg = MaterialTheme.colorScheme.surfaceVariant,
                fg = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Avatar(
    icon: ImageVector,
    bg: Color,
    fg: Color
) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
    }
}

private fun bubbleShape(isUser: Boolean) = RoundedCornerShape(
    topStart = 16.dp,
    topEnd = 16.dp,
    bottomEnd = if (isUser) 4.dp else 16.dp,
    bottomStart = if (isUser) 16.dp else 4.dp
)

@Composable
private fun InputBar(
    streaming: Boolean,
    enabled: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    var input by remember { mutableStateOf("") }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息…") },
                maxLines = 5,
                enabled = enabled,
                shape = MaterialTheme.shapes.extraLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (input.isNotBlank() && !streaming) {
                            onSend(input); input = ""
                        }
                    }
                )
            )
            Spacer(Modifier.width(8.dp))
            AnimatedVisibility(
                visible = !streaming,
                enter = scaleIn(tween(200)) + fadeIn(tween(200)),
                exit = scaleOut(tween(200)) + fadeOut(tween(200))
            ) {
                IconButton(
                    onClick = {
                        if (input.isNotBlank()) {
                            onSend(input); input = ""
                        }
                    },
                    enabled = enabled && input.isNotBlank()
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Send,
                        contentDescription = "发送",
                        tint = if (enabled && input.isNotBlank()) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.outline
                    )
                }
            }
            AnimatedVisibility(
                visible = streaming,
                enter = scaleIn(tween(200)) + fadeIn(tween(200)),
                exit = scaleOut(tween(200)) + fadeOut(tween(200))
            ) {
                IconButton(onClick = onStop) {
                    Icon(
                        Icons.Outlined.Stop,
                        contentDescription = "停止",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

private fun formatCtx(n: Int): String = when {
    n >= 1024 -> "%.1fK".format(n / 1024.0)
    else -> n.toString()
}
