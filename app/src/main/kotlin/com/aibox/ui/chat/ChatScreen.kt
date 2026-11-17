package com.aibox.ui.chat

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aibox.data.model.ChatMessage
import com.aibox.data.model.ChatAttachment
import com.aibox.data.model.MsgPart
import com.aibox.ui.shortcut.ShortcutEditorLauncher
import com.aibox.ui.theme.LocalCornerScale
import com.aibox.ui.tool.ToolConfirmDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 聊天主界面。
 *
 * 布局选择（参考 ChatGPT / Claude / Gemini / DeepSeek）：
 * - 顶栏：会话标题 + 当前模型/后端副标题，右侧只留温度胶囊与菜单
 * - 消息区：无头像。默认「文档流」——用户右侧气泡、助手正文直接铺在背景上；
 *   Settings → 对话 → 文档流消息样式 可切回双方气泡
 * - 输入区：悬浮胶囊 composer（圆角 28dp + 描边），左侧 + 号、右侧 麦克风 + 发送/停止圆形按钮
 * - 运行状态（t/s、ctx、后端）只在生成时以胶囊形式浮在输入框上方
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel = viewModel(),
    sessionId: Long = 0L,
    onOpenSearch: () -> Unit = {},
    onOpenShortcutEditor: () -> Unit = {}
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val enabledTools by vm.enabledToolIds.collectAsStateWithLifecycle()
    val networkMode = com.aibox.domain.tool.NetworkMode.fromEnabled(enabledTools)
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbarHost = remember { SnackbarHostState() }

    // 当 sessionId 变化时加载该 session；若有角色扮演请求则优先新建角色会话
    LaunchedEffect(sessionId) {
        val req = com.aibox.ui.roleplay.RoleplayLauncher.consume()
        when {
            req == null -> if (sessionId != 0L) vm.loadSession(sessionId)
            req.first > 0L -> vm.startRoleplay(req.first, req.second)
            sessionId != 0L -> vm.loadSession(sessionId)
            else -> vm.resetToChat()
        }
    }
    val context = LocalContext.current
    val drawerState = com.aibox.ui.nav.LocalDrawerState.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // 始终滚到「底部占位项」，而不是 messages.size（那里其实是流式项的开头，
    // 这正是之前流式时只能看到回复开头、划不到最底的原因）。
    // 过滤掉空助手消息（旧数据里可能有 content="" 且无 parts 的记录），否则会渲染空气泡。
    val visibleMessages = remember(ui.messages) {
        ui.messages.filter { m ->
            m.role != "assistant" || m.content.isNotBlank() || m.parts.isNotEmpty()
        }
    }
    val listItemCount = visibleMessages.size +
        (if (ui.streaming) 1 else 0) + 1 // 最后一项是底部 spacer
    LaunchedEffect(listItemCount, ui.currentStream.length, ui.streamParts.size) {
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        // 用户主动往上翻时不要抢滚动；在底部附近（或首次）才自动跟随
        val nearBottom = lastVisible >= info.totalItemsCount - 2
        if (listItemCount > 0 && !listState.isScrollInProgress && nearBottom) {
            listState.scrollToItem(listItemCount - 1)
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
    var sheetOpen by remember { mutableStateOf(false) }
    // 用户消息编辑：编辑前的内容会作为历史版本保留
    var editTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var editText by remember { mutableStateOf("") }

    // 进入页面时内容渐显（导航 → 聊天）：透明度 + 轻微上移，避免只剩一瞬间的淡入
    var contentVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { contentVisible = true }
    val contentProgress by animateFloatAsState(
        targetValue = if (contentVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "chatFade"
    )
    // 输入栏稍晚一点出现，让「内容 → 输入」有层次而不是一起闪出来
    val composerProgress by animateFloatAsState(
        targetValue = if (contentVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = 420,
            delayMillis = 90,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        ),
        label = "composerFade"
    )

    // 相册 / 文件选择（系统 Photo Picker 与 SAF，无需存储权限）
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) vm.addImageAttachment(uri) }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.addFileAttachment(uri) }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Toast.makeText(
            context,
            if (granted) "麦克风已授权（语音输入即将上线）" else "麦克风权限被拒绝",
            Toast.LENGTH_SHORT
        ).show()
    }
    val requestMic: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
            == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(context, "语音输入开发中", Toast.LENGTH_SHORT).show()
        } else {
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Outlined.Menu, contentDescription = "菜单")
                    }
                },
                title = {
                    Column {
                        val isRoleplay = ui.mode == "ROLEPLAY" && ui.characterName.isNotBlank()
                        val title = if (isRoleplay) {
                            "${ui.characterEmoji} ${ui.characterName}".trim()
                        } else {
                            ui.sessionTitle.ifBlank { "AIBox" }
                        }
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold
                        )
                        val subtitle = buildString {
                            if (isRoleplay && ui.personaName.isNotBlank()) {
                                append("你 · ${ui.personaName} · ")
                            }
                            if (ui.modelName.isNotEmpty()) {
                                append("${ui.modelName} · ${ui.backendName}")
                            } else {
                                append("未加载模型")
                            }
                        }
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ui.modelName.isNotEmpty()) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    ThinkingPill(level = ui.thinkingLevel, onChange = vm::setThinkingLevel)
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Outlined.Search, contentDescription = "搜索")
                    }
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
                            if (ui.mode == "ROLEPLAY") {
                                DropdownMenuItem(
                                    text = { Text("退出角色扮演") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Close,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        vm.resetToChat()
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("保存为快捷方式") },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(20.dp))
                                },
                                onClick = {
                                    menuOpen = false
                                    // 构造当前配置草稿 → 通过 ShortcutEditorLauncher 桥接给编辑器
                                    val draft = vm.buildShortcutDraft()
                                    ShortcutEditorLauncher.setPendingDraft(draft)
                                    onOpenShortcutEditor()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("清空工具调用历史") },
                                leadingIcon = {
                                    Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(20.dp))
                                },
                                onClick = {
                                    menuOpen = false
                                    vm.clearToolInvocations()
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
            // 内容区：底部加一层渐隐遮罩，避免与输入栏之间出现生硬的截断线
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .alpha(contentProgress)
                    .offset(y = (18 * (1f - contentProgress)).dp)
            ) {
                when {
                    !ui.hasModel -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            EmptyModelState()
                        }
                    }
                    ui.messages.isEmpty() && ui.currentStream.isEmpty() && !ui.streaming -> {
                        EmptyChatState(
                            modifier = Modifier.fillMaxSize(),
                            onSuggestion = { vm.send(it) },
                            greeting = if (ui.mode == "ROLEPLAY") ui.characterGreeting else "",
                            characterName = if (ui.mode == "ROLEPLAY") ui.characterName else "",
                            characterEmoji = if (ui.mode == "ROLEPLAY") ui.characterEmoji else "",
                            personaName = if (ui.mode == "ROLEPLAY") ui.personaName else ""
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(if (ui.docStyle) 18.dp else 8.dp)
                        ) {
                            val lastAssistantIndex = visibleMessages.indexOfLast { it.role == "assistant" }
                            itemsIndexed(
                                items = visibleMessages,
                                key = { _, m -> "${m.id}-${m.ts}" }
                            ) { index, m ->
                                MessageItem(
                                    m = m,
                                    docStyle = ui.docStyle,
                                    showActions = !ui.streaming && index == lastAssistantIndex,
                                    animate = false,
                                    choicesEnabled = ui.choicesBeta && !ui.streaming && index == lastAssistantIndex,
                                    onCopy = {
                                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                                as android.content.ClipboardManager
                                        cm.setPrimaryClip(android.content.ClipData.newPlainText("AIBox", m.content))
                                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                    },
                                    onRegenerate = if (m.role == "assistant") {
                                        { vm.regenerate() }
                                    } else null,
                                    onDelete = { vm.deleteMessage(m) },
                                    onChoice = { vm.send(it) },
                                    onSwitchVersion = { vm.switchVersion(m, it) },
                                    onEdit = if (m.role == "user") {
                                        { editTarget = m; editText = m.content }
                                    } else null
                                )
                            }
                            // 工具调用不再单独渲染第二份：时间线里的 MsgPart.tool
                            // 已经是唯一来源，额外的 ui.toolInvocations 列表会造成
                            // “同一张工具卡出现两次、且一直残留在最底部”的问题。
                            if (ui.currentStream.isNotEmpty() || ui.streamParts.isNotEmpty()) {
                                item(key = "stream") {
                                    MessageItem(
                                        m = ChatMessage(
                                            role = "assistant",
                                            content = ui.currentStream,
                                            sessionId = 0,
                                            parts = ui.streamParts
                                        ),
                                        docStyle = ui.docStyle,
                                        showActions = false,
                                        animate = true,
                                        choicesEnabled = ui.choicesBeta,
                                        onChoice = { vm.send(it) }
                                    )
                                }
                            }
                            if (ui.streaming && ui.currentStream.isEmpty() && ui.streamParts.isEmpty()) {
                                item(key = "thinking") { ThinkingIndicator(docStyle = ui.docStyle) }
                            }
                            // 让最后一条消息不被输入栏挡住
                            item(key = "bottom_spacer") { Spacer(Modifier.height(8.dp)) }
                        }
                    }
                }

                // 底部渐隐：内容滚到输入栏附近时柔和消失，而不是被硬切
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(18.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, MaterialTheme.colorScheme.background)
                            )
                        )
                )

                // 加号面板打开时，点内容区任意位置收起（不吃滚动，只接管点击）
                if (sheetOpen) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { sheetOpen = false }
                    )
                }

                // 加号面板：内联展开（不用 ModalBottomSheet），避免与 IME / 列表
                // 重排抢同一段动画导致一卡一卡
                AnimatedVisibility(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    visible = sheetOpen,
                    enter = expandVertically(tween(170)) + fadeIn(tween(120)),
                    exit = shrinkVertically(tween(150)) + fadeOut(tween(90))
                ) {
                    ComposerPlusPanel(
                        thinkingLevel = ui.thinkingLevel,
                        onThinkingLevel = vm::setThinkingLevel,
                        networkMode = networkMode,
                        onNetworkMode = vm::setNetworkMode,
                        onPhoto = {
                            sheetOpen = false
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onFile = {
                            sheetOpen = false
                            filePicker.launch(
                                arrayOf(
                                    "text/*",
                                    "application/json",
                                    "application/xml",
                                    "application/octet-stream"
                                )
                            )
                        }
                    )
                }

            }

            // 运行状态：仅在生成时出现，不生成时完全隐形
            AnimatedVisibility(
                visible = ui.streaming,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150))
            ) {
                StatusPill(
                    tokensPerSecond = ui.tokensPerSecond,
                    contextUsed = ui.contextUsed,
                    contextMax = ui.contextMax,
                    backendName = ui.backendName,
                    thermalCelsius = ui.thermalCelsius,
                    thermalPercent = ui.thermalPercent
                )
            }

            // 低电量提示：只提示，不自动改用户手动选的 HTP 档
            PowerNoticeBanner()

            // 输入栏始终在底部
            Composer(
                streaming = ui.streaming,
                enabled = ui.hasModel,
                attachments = ui.pendingAttachments,
                onRemoveAttachment = vm::removePendingAttachment,
                networkMode = networkMode,
                onCycleNetwork = vm::cycleNetworkMode,
                onSend = { txt -> vm.send(txt); keyboard?.hide() },
                onStop = { vm.stop() },
                onOpenPlus = { sheetOpen = !sheetOpen },
                onMic = requestMic,
                modifier = Modifier.graphicsLayer {
                    alpha = composerProgress
                    translationY = (1f - composerProgress) * 26f
                }
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

    // 编辑用户消息：保存后旧内容进入版本历史
    editTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("编辑消息") },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.editMessage(target, editText)
                    editTarget = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) { Text("取消") }
            }
        )
    }

    // B6 工具调用 UI: 高级工具执行前弹确认 dialog
    ui.pendingToolConfirmation?.let { pending ->
        ToolConfirmDialog(
            pending = pending,
            onConfirm = { vm.confirmToolInvocation() },
            onDeny = { vm.denyToolInvocation() },
            onAlwaysAllow = { vm.alwaysAllowTool() }
        )
    }

    // user_choice：模型请求用户选择 / 补充
    ui.pendingUserChoice?.let { pending ->
        UserChoiceDialog(
            pending = pending,
            onSubmit = { vm.submitUserChoice(it) },
            onCancel = { vm.cancelUserChoice() }
        )
    }
}

/**
 * user_choice 交互面板：单选 / 多选 / 文字回答，可附带补充说明。
 */
@Composable
private fun UserChoiceDialog(
    pending: com.aibox.ui.tool.PendingUserChoice,
    onSubmit: (com.aibox.domain.tool.UserChoiceAnswer) -> Unit,
    onCancel: () -> Unit
) {
    val req = pending.request
    var selected by remember(pending.invocationId) { mutableStateOf(emptySet<String>()) }
    var text by remember(pending.invocationId) { mutableStateOf("") }
    val needsText = req.mode == com.aibox.domain.tool.ChoiceMode.TEXT
    val canSubmit = selected.isNotEmpty() || text.isNotBlank()

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(req.question) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (req.options.isNotEmpty()) {
                    req.options.forEach { option ->
                        FilterChip(
                            selected = option in selected,
                            onClick = {
                                selected = when (req.mode) {
                                    com.aibox.domain.tool.ChoiceMode.MULTIPLE ->
                                        if (option in selected) selected - option else selected + option
                                    else -> if (option in selected) emptySet() else setOf(option)
                                }
                            },
                            label = { Text(option) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                if (needsText || req.allowExtra) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                req.extraHint.ifBlank {
                                    if (needsText) "输入你的回答" else "补充说明（可选）"
                                }
                            )
                        },
                        minLines = 1,
                        maxLines = 4
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = {
                    onSubmit(
                        com.aibox.domain.tool.UserChoiceAnswer(
                            selected = selected.toList(),
                            text = text.trim()
                        )
                    )
                }
            ) { Text("发送回答") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        }
    )
}

/* ==================== 加号面板（内联） ==================== */

/**
 * 输入区加号面板。
 *
 * 刻意不用 ModalBottomSheet：sheet 是独立窗口，弹/收时要和 IME 动画、
 * 消息列表重排同时进行，低端机上很容易一卡一卡。这里改成聊天气泡流里
 * 内联展开的托盘，只有一段自身的高度动画。
 */
@Composable
private fun ComposerPlusPanel(
    thinkingLevel: String,
    onThinkingLevel: (String) -> Unit,
    networkMode: com.aibox.domain.tool.NetworkMode,
    onNetworkMode: (com.aibox.domain.tool.NetworkMode) -> Unit,
    onPhoto: () -> Unit,
    onFile: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 4.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                PanelChipRow(
                    label = "思考",
                    options = listOf(
                        "off" to "关",
                        "low" to "低",
                        "medium" to "中",
                        "high" to "高"
                    ),
                    selected = thinkingLevel,
                    onSelect = onThinkingLevel
                )
                PanelChipRow(
                    label = "联网",
                    options = com.aibox.domain.tool.NetworkMode.values()
                        .map { it.name to it.label },
                    selected = networkMode.name,
                    onSelect = { id ->
                        com.aibox.domain.tool.NetworkMode.values()
                            .firstOrNull { it.name == id }
                            ?.let(onNetworkMode)
                    }
                )
                HorizontalDivider(
                    Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    PanelAction(
                        icon = Icons.Outlined.Image,
                        label = "照片",
                        modifier = Modifier.weight(1f),
                        onClick = onPhoto
                    )
                    PanelAction(
                        icon = Icons.Outlined.AttachFile,
                        label = "文件",
                        modifier = Modifier.weight(1f),
                        onClick = onFile
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelChipRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .widthIn(min = 40.dp)
                .padding(start = 14.dp)
        )
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(end = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            options.forEach { (id, text) ->
                FilterChip(
                    selected = selected == id,
                    onClick = { onSelect(id) },
                    label = { Text(text, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }
    }
}

@Composable
private fun PanelAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
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

/**
 * 顶栏的思考档位胶囊：点击切换 关 / 低 / 中 / 高。
 * 本地模型按 chat template 注入 /no_think；云端按供应商下发 thinking / enable_thinking / reasoning_effort。
 */
@Composable
private fun ThinkingPill(level: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = when (level) {
        "off" -> "思考关"
        "low" -> "思考·低"
        "medium" -> "思考·中"
        "high" -> "思考·高"
        else -> "思考"
    }
    val active = level != "off"
    Box {
        Surface(
            shape = CircleShape,
            color = if (active) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.clickable { open = true }
        ) {
            Row(
                Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.Outlined.Psychology,
                    contentDescription = "思考强度",
                    modifier = Modifier.size(14.dp),
                    tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf("off" to "关闭", "low" to "低", "medium" to "中", "high" to "高").forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text("思考 · $name") },
                    leadingIcon = {
                        if (level == id) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Spacer(Modifier.size(18.dp))
                        }
                    },
                    onClick = {
                        open = false
                        onChange(id)
                    }
                )
            }
        }
    }
}

/** 生成中的运行状态胶囊：贴在手感区上方，不占常驻空间。 */
@Composable
private fun StatusPill(
    tokensPerSecond: Float,
    contextUsed: Int,
    contextMax: Int,
    backendName: String,
    thermalCelsius: Double = -1.0,
    thermalPercent: Int = -1
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = CircleShape
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    backendName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (tokensPerSecond > 0f) {
                    Dot()
                    Text(
                        "%.1f t/s".format(tokensPerSecond),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (contextMax > 0) {
                    Dot()
                    Text(
                        "${formatCtx(contextUsed)}/${formatCtx(contextMax)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (thermalCelsius > 0) {
                    Dot()
                    Text(
                        "%.1f°C".format(thermalCelsius),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (thermalCelsius >= 50.0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (thermalPercent >= 0) {
                    // 只有离散热档位、没有真实温度时，显示档位而不是伪造的摄氏度
                    Dot()
                    Text(
                        thermalLevelLabel(thermalPercent),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (thermalPercent >= 65) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 只有离散热档位时的文案（避免把百分比当成温度显示）。 */
private fun thermalLevelLabel(pct: Int): String = when {
    pct < 25 -> "凉爽"
    pct < 50 -> "常温"
    pct < 70 -> "偏热"
    pct < 88 -> "发烫"
    else -> "过热"
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

/** 空会话问候池：轮换展示，避免每次进来都是同一句。 */
private val CHAT_GREETINGS = listOf(
    "嗨！今天想聊些什么？",
    "聊点啥？",
    "今天又想聊啥了？",
    "有什么想说的？",
    "我在，说说看？",
    "想聊点正经的，还是随便扯扯？",
    "今天想解决什么问题？",
    "要不要聊聊你最近在想的事？",
    "有需要我搭把手的地方吗？",
    "来，随便开个头。",
    "今天有什么新鲜事？",
    "想聊什么都可以。"
)

/** 空会话建议池（18 条，每次展示 3 条并定时轮换）。 */
private val CHAT_SUGGESTIONS = listOf(
    "用三句话介绍一下你自己",
    "帮我列出今天的待办清单",
    "用通俗的话解释一下 MoE 模型",
    "把这段话改写得更简洁",
    "帮我写一段周末出行计划",
    "翻译成英文并润色",
    "总结一下这段文字的要点",
    "给我三个标题选项",
    "用一个比喻解释注意力机制",
    "帮我写一封请假邮件",
    "这段代码可能哪里有问题",
    "推荐三本入门书",
    "我想练口语，陪我聊两句",
    "把这段需求拆成任务列表",
    "帮我想几个产品名字",
    "用表格对比一下 A 和 B",
    "帮我安排一个番茄钟计划",
    "解释一下这件事为什么会这样"
)

/** 空会话：居中问候 + 建议提问（参考 Gemini / DeepSeek 的起始页）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmptyChatState(
    modifier: Modifier = Modifier,
    onSuggestion: (String) -> Unit,
    greeting: String = "",
    characterName: String = "",
    characterEmoji: String = "",
    personaName: String = ""
) {
    // 每次进入空会话 / 每 12 秒换一次问候与建议，避免永远是那三条。
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(12_000)
            tick++
        }
    }
    // 角色扮演时不推通用任务建议，改成不会出戏的开场白
    val suggestions = remember(tick, characterName) {
        if (characterName.isNotBlank()) {
            listOf("你好呀", "我们聊聊吧", "今天过得怎么样？")
        } else {
            List(3) { i -> CHAT_SUGGESTIONS[(tick * 3 + i) % CHAT_SUGGESTIONS.size] }
        }
    }
    val title = greeting.ifBlank { CHAT_GREETINGS[tick % CHAT_GREETINGS.size] }
    val subtitle = if (characterName.isNotBlank()) {
        buildString {
            append("正在扮演 $characterName")
            if (personaName.isNotBlank()) append(" · 你是 $personaName")
        }
    } else ""
    Column(
        modifier = modifier.padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (characterEmoji.isNotBlank()) {
                Text(characterEmoji, style = MaterialTheme.typography.headlineSmall)
            } else {
                Icon(
                    Icons.Outlined.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(24.dp))
        suggestions.forEach { s ->
            Surface(
                onClick = { onSuggestion(s) },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    s,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun ThinkingIndicator(docStyle: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (docStyle) 0.dp else 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        BouncingDot(delay = 0)
        BouncingDot(delay = 120)
        BouncingDot(delay = 240)
        Spacer(Modifier.width(4.dp))
        Text(
            "正在生成",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

/**
 * 单条消息。
 *
 * - [docStyle] = true：用户右侧浅色气泡，助手无气泡纯文本
 * - [docStyle] = false：双方气泡
 * - 长按任意消息弹出 复制 / 重新生成 / 删除
 * - 最后一条助手消息在非生成状态下常驻一行小操作图标
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageItem(
    m: ChatMessage,
    docStyle: Boolean,
    showActions: Boolean,
    animate: Boolean = false,
    choicesEnabled: Boolean = true,
    onCopy: () -> Unit = {},
    onRegenerate: (() -> Unit)? = null,
    onDelete: () -> Unit = {},
    onChoice: (String) -> Unit = {},
    onSwitchVersion: (Int) -> Unit = {},
    onEdit: (() -> Unit)? = null
) {
    val isUser = m.role == "user"
    var menuOpen by remember { mutableStateOf(false) }
    // 结构化时间线；旧消息没有 parts 时按正文解析（思考 / 工具标记）
    val parts = remember(m.content, m.parts) {
        if (m.parts.isNotEmpty()) m.parts else parseTimeline(m.content)
    }
    val scale = LocalCornerScale.current
    val bubbleRadius = (18 * scale).toInt().dp
    val tailRadius = (5 * scale).toInt().coerceAtLeast(2).dp

    val longPress = Modifier.combinedClickable(
        onClick = {},
        onLongClick = { menuOpen = true }
    )

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            when {
            // 用户：左右都靠右的浅色气泡
            isUser -> {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(
                            topStart = bubbleRadius, topEnd = bubbleRadius,
                            bottomEnd = tailRadius, bottomStart = bubbleRadius
                        ),
                        modifier = Modifier
                            .widthIn(max = 340.dp)
                            .then(longPress)
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            MessageAttachments(
                                m.attachments,
                                Modifier.padding(bottom = if (m.content.isBlank()) 0.dp else 8.dp)
                            )
                            if (m.content.isNotBlank()) {
                                Text(
                                    m.content,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    style = MaterialTheme.typography.bodyMedium,
                                    lineHeight = 22.sp
                                )
                            }
                        }
                    }
                }
            }

            // 助手 + 文档流：无气泡
            docStyle -> {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .then(longPress)
                ) {
                    AssistantParts(
                        parts = parts,
                        streaming = animate,
                        onChoice = onChoice,
                        choicesEnabled = choicesEnabled,
                        modifier = if (animate) {
                            Modifier.animateContentSize(animationSpec = tween(120))
                        } else Modifier
                    )
                }
            }

            // 助手 + 气泡
            else -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(
                            topStart = bubbleRadius, topEnd = bubbleRadius,
                            bottomEnd = bubbleRadius, bottomStart = tailRadius
                        ),
                        modifier = Modifier
                            .widthIn(max = 340.dp)
                            .then(longPress)
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            AssistantParts(
                                parts = parts,
                                streaming = animate,
                                onChoice = onChoice,
                                choicesEnabled = choicesEnabled
                            )
                        }
                    }
                }
            }
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
            if (onEdit != null) {
                DropdownMenuItem(
                    text = { Text("编辑") },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = { menuOpen = false; onEdit() }
                )
            }
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

    // 版本切换器：重新生成 / 编辑前的历史版本（1/3、2/3 …）
    if (m.versions.size > 1) {
        VersionSwitcher(m, onSwitchVersion)
    }

    // 最后一条助手消息的常驻小操作行（参考 ChatGPT / Claude）
    if (!isUser && showActions) {
        Row(
            Modifier.padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // 单条消息的真实生成速度（Kotlin 侧按首 token→结束的墙钟时间算）
            if (m.tokensPerSecond > 0f) {
                Text(
                    text = "%.1f t/s".format(m.tokensPerSecond),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
            MiniAction(Icons.Outlined.ContentCopy, "复制", onCopy)
            if (onRegenerate != null) {
                MiniAction(Icons.Outlined.Refresh, "重新生成", onRegenerate)
            }
            MiniAction(Icons.Outlined.MoreHoriz, "更多") { menuOpen = true }
        }
    }
    }
}

/** 助手消息时间线：思考 / 工具 / 正文按出现顺序交错渲染。 */
@Composable
private fun AssistantParts(
    parts: List<MsgPart>,
    streaming: Boolean,
    onChoice: (String) -> Unit,
    choicesEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        parts.forEachIndexed { index, part ->
            when (part.kind) {
                MsgPart.KIND_THINK -> ThinkingSection(
                    thinking = part.text,
                    streaming = streaming && index == parts.lastIndex
                )
                MsgPart.KIND_TOOL -> ToolPartRow(part)
                else -> if (part.text.isNotBlank()) {
                    MarkdownText(
                        text = part.text,
                        color = MaterialTheme.colorScheme.onSurface,
                        onChoice = onChoice,
                        choicesEnabled = choicesEnabled
                    )
                }
            }
        }
    }
}

/** 版本切换器：‹ 2/3 ›，切换重新生成或编辑前的历史版本。 */
@Composable
private fun VersionSwitcher(m: ChatMessage, onSwitch: (Int) -> Unit) {
    Row(
        Modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        VersionArrow("‹", enabled = m.versionIndex > 0) { onSwitch(m.versionIndex - 1) }
        Text(
            "${m.versionIndex + 1}/${m.versions.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        VersionArrow("›", enabled = m.versionIndex < m.versions.lastIndex) { onSwitch(m.versionIndex + 1) }
    }
}

@Composable
private fun VersionArrow(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.3f)
        )
    }
}

@Composable
private fun MiniAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * 悬浮胶囊输入区。
 *
 * 结构参考 ChatGPT / Gemini：
 * ```
 * ┌──────────────────────────────────┐
 * │ 输入内容（1~6 行自动增高）        │
 * │ [+]                    [🎤] [↑] │
 * └──────────────────────────────────┘
 * ```
 */
@Composable
private fun Composer(
    streaming: Boolean,
    enabled: Boolean,
    attachments: List<ChatAttachment>,
    onRemoveAttachment: (Int) -> Unit,
    networkMode: com.aibox.domain.tool.NetworkMode,
    onCycleNetwork: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onOpenPlus: () -> Unit,
    onMic: () -> Unit,
    modifier: Modifier = Modifier
) {
    var input by rememberSaveable { mutableStateOf("") }
    val canSend = enabled && (input.isNotBlank() || attachments.isNotEmpty()) && !streaming
    val doSend: () -> Unit = {
        if (canSend) {
            onSend(input.trim())
            input = ""
        }
    }
    // 圆角跟随外观设置，但设上限：半径过大时四角会“啃”掉文字左侧的留白
    val shapeRadius = (20 * LocalCornerScale.current).toInt().coerceIn(12, 22)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)
    ) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(shapeRadius.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        shadowElevation = 6.dp
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp)) {
            PendingAttachmentBar(
                attachments = attachments,
                onRemove = onRemoveAttachment,
                modifier = Modifier.padding(end = 6.dp)
            )
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .fillMaxWidth()
                    // 给文字本身留出呼吸高度，避免输入区看起来只有一条细线
                    .heightIn(min = 34.dp),
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 6,
                minLines = 1,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { doSend() }),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 3.dp)) {
                        if (input.isEmpty()) {
                            Text(
                                if (enabled) "发消息，或添加图片 / 文件" else "请先加载模型",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        inner()
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleAction(
                    size = 36,
                    container = MaterialTheme.colorScheme.primaryContainer,
                    enabled = enabled,
                    // composer 左右内边距不对称（左 14 / 右 8），往左挪 4dp 后
                    // 与右侧发送按钮的视觉边距保持一致
                    modifier = Modifier.offset(x = (-4).dp),
                    onClick = onOpenPlus
                ) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = "添加",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                NetworkModeChip(
                    mode = networkMode,
                    enabled = enabled,
                    onClick = onCycleNetwork
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(enabled = enabled, onClick = onMic),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Mic,
                        contentDescription = "语音",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
                // 发送 / 停止：同一个圆形按钮形变，生成中变实心停止块
                val circleColor = when {
                    streaming -> MaterialTheme.colorScheme.error
                    canSend -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                }
                val iconTint = when {
                    streaming -> MaterialTheme.colorScheme.onError
                    canSend -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                CircleAction(
                    size = 36,
                    container = circleColor,
                    enabled = streaming || canSend,
                    onClick = { if (streaming) onStop() else doSend() }
                ) {
                    if (streaming) {
                        Icon(
                            Icons.Outlined.Stop,
                            contentDescription = "停止",
                            tint = iconTint,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = iconTint,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun CircleAction(
    size: Int,
    container: Color,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (enabled) container else container.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

private fun formatCtx(n: Int): String = when {
    n >= 1024 -> "%.1fK".format(n / 1024.0)
    else -> n.toString()
}

/* ==================== 联网三态 chip ==================== */

/**
 * 输入区「联网」三态按钮：关 / 阅读 / 搜索。
 *
 * 视觉上固定一个地球图标家族，状态靠容器色 + 文案 + 小徽标区分，
 * 避免被误认为三个独立功能。点击循环切换，底层写回两个独立工具开关。
 */
@Composable
private fun NetworkModeChip(
    mode: com.aibox.domain.tool.NetworkMode,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val containerColor = when (mode) {
        com.aibox.domain.tool.NetworkMode.OFF -> MaterialTheme.colorScheme.surfaceContainerHigh
        com.aibox.domain.tool.NetworkMode.READ -> MaterialTheme.colorScheme.secondaryContainer
        com.aibox.domain.tool.NetworkMode.SEARCH -> MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = when (mode) {
        com.aibox.domain.tool.NetworkMode.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
        com.aibox.domain.tool.NetworkMode.READ -> MaterialTheme.colorScheme.onSecondaryContainer
        com.aibox.domain.tool.NetworkMode.SEARCH -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = containerColor,
        contentColor = contentColor,
        border = if (mode == com.aibox.domain.tool.NetworkMode.OFF) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
        } else null
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(16.dp)) {
                Icon(
                    imageVector = if (mode == com.aibox.domain.tool.NetworkMode.SEARCH) {
                        Icons.Outlined.TravelExplore
                    } else {
                        Icons.Outlined.Language
                    },
                    contentDescription = "联网：${mode.description}",
                    modifier = Modifier
                        .size(16.dp)
                        .alpha(if (mode == com.aibox.domain.tool.NetworkMode.OFF) 0.6f else 1f)
                )
                if (mode == com.aibox.domain.tool.NetworkMode.READ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .align(Alignment.TopEnd)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = mode.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/* ==================== 低电量提示条 ==================== */

/**
 * 低电量提示：只提醒与建议，绝不自动改用户手动选择的 HTP 档。
 *
 * 规则：
 * - 充电中 / 自动档 / 已是均衡或省电 → 不显示
 * - ≤阈值且手动选了性能优先 → 轻提示
 * - ≤5% 且手动选了性能优先 → 强警告（说明可能关机的后果）
 * - 本次忽略 / 不再提醒 都支持；永久关闭后仍可在设置页看到当前档位
 */
@Composable
private fun PowerNoticeBanner() {
    val container = com.aibox.AIBoxApp.instance.container
    val status by container.batteryMonitor.status.collectAsStateWithLifecycle()
    val settings by container.optimizationSettingsRepository.settingsFlow
        .collectAsStateWithLifecycle(initialValue = com.aibox.domain.optimization.OptimizationSettings.DEFAULT)
    val warnLowAllowed by container.settingsRepository.warnLowBattery
        .collectAsStateWithLifecycle(initialValue = true)
    val warnCriticalAllowed by container.settingsRepository.warnCriticalBattery
        .collectAsStateWithLifecycle(initialValue = true)
    var dismissedLow by rememberSaveable { mutableStateOf(false) }
    var dismissedCritical by rememberSaveable { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val mode = com.aibox.domain.power.PowerPolicy.resolve(settings, status)
    val level = com.aibox.domain.power.PowerPolicy.warningLevel(settings.htpPerfMode, mode)
    val visible = when (level) {
        com.aibox.domain.power.PowerPolicy.Warning.LOW -> warnLowAllowed && !dismissedLow
        com.aibox.domain.power.PowerPolicy.Warning.CRITICAL -> warnCriticalAllowed && !dismissedCritical
        null -> false
    }
    if (!visible) return

    val critical = level == com.aibox.domain.power.PowerPolicy.Warning.CRITICAL
    val setHtp: (String) -> Unit = { id ->
        scope.launch {
            runCatching {
                container.optimizationSettingsRepository.update { it.copy(htpPerfMode = id) }
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (critical) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = if (critical) {
                    "当前电量极低，Burst 模式会明显加快耗电，仍要使用吗？"
                } else {
                    "当前电量较低，均衡档能兼顾稳定速度与更低耗电。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (critical) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSecondaryContainer
            )
            if (critical) {
                Text(
                    "极端情况下可能触发系统关机，未保存内容会丢失。也可以先复制重要内容。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (critical) {
                    TextButton(onClick = { dismissedCritical = true }) { Text("仍要使用") }
                    TextButton(onClick = { setHtp(com.aibox.domain.power.HtpPerfMode.BALANCED) }) {
                        Text("降为均衡")
                    }
                    TextButton(onClick = { setHtp(com.aibox.domain.power.HtpPerfMode.POWERSAVE) }) {
                        Text("降为省电")
                    }
                } else {
                    TextButton(onClick = { setHtp(com.aibox.domain.power.HtpPerfMode.BALANCED) }) {
                        Text("切为均衡")
                    }
                    TextButton(onClick = { dismissedLow = true }) { Text("本次忽略") }
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            if (critical) {
                                container.settingsRepository.setWarnCriticalBattery(false)
                                dismissedCritical = true
                            } else {
                                container.settingsRepository.setWarnLowBattery(false)
                                dismissedLow = true
                            }
                        }
                    }
                ) { Text("不再提醒") }
            }
        }
    }
}
