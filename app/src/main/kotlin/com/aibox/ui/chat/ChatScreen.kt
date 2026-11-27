package com.aibox.ui.chat

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aibox.data.model.ChatMessage
import com.aibox.data.model.ChatAttachment
import com.aibox.data.model.MsgPart
import com.aibox.ui.shortcut.ShortcutEditorLauncher
import com.aibox.ui.theme.LocalCornerScale
import com.aibox.ui.tool.ToolConfirmDialog
import com.aibox.util.skinFeel
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
    onOpenShortcutEditor: () -> Unit ={},
    onOpenToolSettings: () -> Unit = {}
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val enabledTools by vm.enabledToolIds.collectAsStateWithLifecycle()
    val networkMode = com.aibox.domain.tool.NetworkMode.fromEnabled(enabledTools)
    val listState = rememberLazyListState()
    // 进入会话默认停在最新消息：切换会话时置位，等消息加载出来后一次性瞬时跳到底。
    var pendingJumpToBottom by remember { mutableStateOf(true) }
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbarHost = remember { SnackbarHostState() }

    // 当 sessionId 变化时加载该 session；若有角色扮演请求则优先新建角色会话
    LaunchedEffect(sessionId) {
        pendingJumpToBottom = true
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
        // 只展示当前用户消息版本对应的助手回复分支（旧版本分支保留但不渲染）
        vm.visibleMessages(ui.messages).filter { m ->
            m.role != "assistant" || m.content.isNotBlank() || m.parts.isNotEmpty()
        }
    }
    // 页内查找状态：在当前会话消息里定位关键字，命中项高亮并可逐条跳转
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findCursor by remember { mutableStateOf(0) }

    // 页内查找：命中项在 visibleMessages 中的下标（忽略大小写，正文 + 思考 + 工具输出都算）
    val findMatches = remember(visibleMessages, findQuery) {
        val q = findQuery.trim()
        if (q.isBlank()) emptyList()
        else visibleMessages.mapIndexedNotNull { idx, m ->
            val haystack = buildString {
                append(m.content)
                m.parts.forEach { append('\n').append(it.text).append('\n').append(it.output) }
            }
            if (haystack.contains(q, ignoreCase = true)) idx else null
        }
    }
    // 查询变化时把游标归零
    LaunchedEffect(findQuery) { findCursor = 0 }
    val currentFindIndex = findMatches.getOrNull(findCursor.coerceIn(0, (findMatches.size - 1).coerceAtLeast(0)))

    val listItemCount = visibleMessages.size +
        (if (ui.streaming) 1 else 0) + 1 // 最后一项是底部 spacer
    // 会话切换后首次拿到消息：瞬时落到最新一条（不走动画，避免进页看到飞滚）。
    LaunchedEffect(listItemCount) {
        if (pendingJumpToBottom && listItemCount > 1 && ui.messages.isNotEmpty()) {
            listState.scrollToItem(listItemCount - 1)
            pendingJumpToBottom = false
        }
    }
    // D：区分「流式逐 token 跟随」与「新增一条消息」。前者打字机每 20ms 推进，必须瞬时跟随
    // （动画会被反复打断反而更抖）；后者是一次性跳动，用短 tween 平滑落位。减弱动态时一律瞬时。
    var lastKnownItemCount by remember { mutableIntStateOf(listItemCount) }
    LaunchedEffect(listItemCount, ui.currentStream.length, ui.streamParts.size) {
        val structuralChange = listItemCount != lastKnownItemCount
        // 无论是否真的滚动都更新基线，避免用户上滑期间跳过的变化在回到底部后误触发动画
        lastKnownItemCount = listItemCount
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        // 用户主动往上翻时不要抢滚动；在底部附近（或首次）才自动跟随
        val nearBottom = lastVisible >= info.totalItemsCount - 2
        if (listItemCount > 0 && !listState.isScrollInProgress && nearBottom && !findOpen) {
            if (structuralChange && !ui.reduceMotion && !ui.streaming) {
                // 该 Compose 版本的 animateScrollToItem 无 animationSpec 参数，用默认动画即可
                listState.animateScrollToItem(listItemCount - 1)
            } else {
                listState.scrollToItem(listItemCount - 1)
            }
        }
    }
    // 页内查找：命中游标或当前命中项变化时，把该项滚到可视区中部。
    LaunchedEffect(currentFindIndex, findCursor) {
        if (findOpen && currentFindIndex != null) {
            listState.animateScrollToItem(currentFindIndex)
        }
    }

    // 用户上滑翻看更早消息时，显示"回到底部"按钮；流式生成期间不显示（自动跟随中）。
    val showJumpDown by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 3 && lastVisible < info.totalItemsCount - 3
        }
    }
    // 错误改为聊天流内的持久红条（见下方 error item）：Snackbar 一闪而过且会被立刻
    // dismiss，用户往往只看到「模型没有输出可见文本」却不知道哪出错了。红条会一直
    // 留到用户下一次发送或手动点关闭才消失，真正能看见。
    // snackbarHost 保留给其它临时提示使用，这里不再自动弹错误、也不再立即清除。

    var menuOpen by remember { mutableStateOf(false) }
    var clearOpen by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    // 加号菜单：回到和「添加模型」一致的底部面板（拖拽把 + 标题 + 图标行），
    // 动画交给 ModalBottomSheet，避免自己实现高度展开导致的折纸感
    val plusSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var thinkingDialog by remember { mutableStateOf(false) }
    var networkDialog by remember { mutableStateOf(false) }
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

    // 语音输入：先走系统识别（零体积、走厂商 DSP，耗电最低）。
    // 麦克风权限由系统识别界面自己申请，宿主不需要 RECORD_AUDIO。
    var voiceText by remember { mutableStateOf<String?>(null) }
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            val text = res.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) voiceText = text
        }
    }
    val requestMic: () -> Unit = {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE,
                java.util.Locale.getDefault().toLanguageTag()
            )
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "说话，识别结果会填入输入框")
        }
        runCatching { speechLauncher.launch(intent) }
            .onFailure {
                Toast.makeText(context, "此设备没有可用的语音识别服务", Toast.LENGTH_SHORT).show()
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
                    if (findOpen) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.TextField(
                                value = findQuery,
                                onValueChange = { findQuery = it },
                                placeholder = { Text("在本页查找…") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = androidx.compose.material3.TextFieldDefaults.colors(
                                    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent
                                )
                            )
                            Text(
                                if (findQuery.isBlank()) "" else "${if (findMatches.isEmpty()) 0 else findCursor + 1}/${findMatches.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 2.dp)
                            )
                            IconButton(
                                onClick = {
                                    if (findMatches.isNotEmpty()) {
                                        findCursor = (findCursor - 1 + findMatches.size) % findMatches.size
                                    }
                                },
                                enabled = findMatches.isNotEmpty(),
                                modifier = Modifier.size(32.dp)
                            ) { Text("↑", style = MaterialTheme.typography.titleMedium) }
                            IconButton(
                                onClick = {
                                    if (findMatches.isNotEmpty()) {
                                        findCursor = (findCursor + 1) % findMatches.size
                                    }
                                },
                                enabled = findMatches.isNotEmpty(),
                                modifier = Modifier.size(32.dp)
                            ) { Text("↓", style = MaterialTheme.typography.titleMedium) }
                        }
                    } else Column {
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
                    IconButton(onClick = { findOpen = !findOpen; if (!findOpen) findQuery = "" }) {
                        Icon(
                            if (findOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                            contentDescription = if (findOpen) "关闭查找" else "页内查找"
                        )
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
                            DropdownMenuItem(
                                text = { Text("全局搜索所有会话") },
                                leadingIcon = {
                                    Icon(Icons.Outlined.TravelExplore, contentDescription = null, modifier = Modifier.size(20.dp))
                                },
                                onClick = {
                                    menuOpen = false
                                    onOpenSearch()
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
        // 底部 inset 交给 Composer 自己处理（ime ∪ navigationBars），
        // 否则 Scaffold 的导航栏留白会和 IME 叠加，在输入框和键盘之间多出一段空隙
        contentWindowInsets = WindowInsets.systemBars.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        ),
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
                                    highlight = findOpen && findQuery.isNotBlank() && index == currentFindIndex,
                                    animate = false,
                                    choicesEnabled = ui.choicesBeta && !ui.streaming && index == lastAssistantIndex,
                                    htmlPreview = ui.htmlPreview,
                                    onCopy = {
                                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                                as android.content.ClipboardManager
                                        cm.setPrimaryClip(android.content.ClipData.newPlainText("AIBox", m.content))
                                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                    },
                                    onRegenerate = if (m.role == "assistant") {
                                        { vm.regenerate() }
                                    } else null,
                                    onRegenerateAt = if (m.role == "assistant") {
                                        { vm.regenerateAt(m) }
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
                                        reduceMotion = ui.reduceMotion,
                                        choicesEnabled = ui.choicesBeta,
                                        onChoice = { vm.send(it) }
                                    )
                                }
                            }
                            if (ui.streaming && ui.currentStream.isEmpty() && ui.streamParts.isEmpty()) {
                                item(key = "thinking") {
                                    ThinkingIndicator(docStyle = ui.docStyle, reduceMotion = ui.reduceMotion)
                                }
                            }
                            // 错误红条：推理失败 / 无可见输出时给出可读、可关闭的提示。
                            // 用 M3 语义色 errorContainer/onErrorContainer 自动适配深色/浅色模式。
                            ui.errorMessage?.let { err ->
                                item(key = "error") {
                                    ErrorBar(text = err, onDismiss = { vm.dismissError() })
                                }
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

                // 回到底部：上滑翻看更早消息时出现，点击平滑落到最新一条。
                // 不再要求 !ui.streaming：流式生成期间上翻看历史时同样需要能一键回到底部，
                // 自动跟随逻辑只在 nearBottom 时触发，用户上翻时不会抢滚动，两者不冲突。
                // 用 if 直接控制而非 AnimatedVisibility：此处处于 BoxScope，
                // 而外层 Column 的 ColumnScope 隐式接收者仍在作用域内，
                // 会让 AnimatedVisibility 错误解析成 ColumnScope 重载并报接收者歧义。
                if (showJumpDown && ui.hasModel) {
                    androidx.compose.material3.FloatingActionButton(
                        onClick = {
                            scope.launch {
                                if (ui.reduceMotion) listState.scrollToItem(listItemCount - 1)
                                else listState.animateScrollToItem(listItemCount - 1)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 28.dp)
                            .size(48.dp)
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = "回到最新消息"
                        )
                    }
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
                    skinCelsius = ui.skinCelsius,
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
                onSend = { txt -> vm.send(txt); keyboard?.hide() },
                onStop = { vm.stop() },
                // 先收键盘再开面板：否则 IME 收起和面板滑入两段动画同时跑，会“顿一下”
                onOpenPlus = {
                    keyboard?.hide()
                    sheetOpen = true
                },
                onMic = requestMic,
                voiceText = voiceText,
                onVoiceConsumed = { voiceText = null },
                enterToSend = ui.enterToSend,
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

    // ===== 加号菜单：与「添加模型」同款底部面板 =====
    if (sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = plusSheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(Modifier.padding(bottom = 20.dp)) {
                Text(
                    "本轮设置",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 10.dp)
                )
                SheetSettingRow(
                    icon = Icons.Outlined.Psychology,
                    title = "思考",
                    value = when (ui.thinkingLevel) {
                        "off" -> "关闭"
                        "low" -> "低"
                        "high" -> "高"
                        else -> "中"
                    },
                    onClick = { thinkingDialog = true }
                )
                SheetSettingRow(
                    icon = if (networkMode == com.aibox.domain.tool.NetworkMode.SEARCH) {
                        Icons.Outlined.TravelExplore
                    } else {
                        Icons.Outlined.Language
                    },
                    title = "联网",
                    value = networkMode.label,
                    onClick = { networkDialog = true }
                )
                SheetSettingRow(
                    icon = Icons.Outlined.Build,
                    title = "工具",
                    value = "已启用 ${enabledTools.size} 个",
                    onClick = {
                        sheetOpen = false
                        onOpenToolSettings()
                    }
                )
                HorizontalDivider(
                    Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                SheetActionRow(
                    icon = Icons.Outlined.Image,
                    title = "照片",
                    subtitle = "从相册选择图片",
                    onClick = {
                        sheetOpen = false
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )
                SheetActionRow(
                    icon = Icons.Outlined.AttachFile,
                    title = "文件",
                    subtitle = "文本 / JSON / 附件",
                    onClick = {
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
    }

    if (thinkingDialog) {
        AlertDialog(
            onDismissRequest = { thinkingDialog = false },
            title = { Text("思考") },
            text = {
                Column {
                    listOf("off" to "关闭", "low" to "低", "medium" to "中", "high" to "高")
                        .forEach { (id, label) ->
                            SheetRadioRow(
                                label = label,
                                selected = ui.thinkingLevel == id,
                                onClick = {
                                    vm.setThinkingLevel(id)
                                    thinkingDialog = false
                                }
                            )
                        }
                }
            },
            confirmButton = {
                TextButton(onClick = { thinkingDialog = false }) { Text("完成") }
            }
        )
    }

    if (networkDialog) {
        AlertDialog(
            onDismissRequest = { networkDialog = false },
            title = { Text("联网") },
            text = {
                Column {
                    listOf(
                        com.aibox.domain.tool.NetworkMode.OFF to "关闭",
                        com.aibox.domain.tool.NetworkMode.READ to "仅 URL 阅读",
                        com.aibox.domain.tool.NetworkMode.SEARCH to "打开"
                    ).forEach { (mode, label) ->
                        SheetRadioRow(
                            label = label,
                            selected = networkMode == mode,
                            onClick = {
                                vm.setNetworkMode(mode)
                                networkDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { networkDialog = false }) { Text("完成") }
            }
        )
    }
}

/** 底部面板里的「设置项」行：标题 + 当前值 + 箭头。 */
@Composable
private fun SheetSettingRow(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** 底部面板里的「动作」行：图标 + 标题 + 副标题，和添加模型面板一致。 */
@Composable
private fun SheetActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.width(16.dp))
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

@Composable
private fun SheetRadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
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
    skinCelsius: Double = -1.0,
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
                // 体感优先：有 skin 热区读数时显示七档文案，读不到回退结温数值，再回退热档位。
                val feelTemp = if (skinCelsius > 0) skinCelsius else thermalCelsius
                if (feelTemp > 0) {
                    val feel = skinFeel(feelTemp)
                    Dot()
                    Text(
                        // 有体感读数时“文案 + 数值”，只有结温时仅数值（避免把结温说成体感）
                        if (skinCelsius > 0) "${feel.label} · %.1f°C".format(feelTemp)
                        else "%.1f°C".format(feelTemp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (feel.severity >= 5) MaterialTheme.colorScheme.error
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

/**
 * 聊天流内的错误红条。
 *
 * 用 M3 语义色 errorContainer / onErrorContainer，深色与浅色主题自动适配。
 * 持久展示直到用户下一次发送（errorMessage 被清空）或手动点 × 关闭，
 * 避免 Snackbar 一闪而过让用户只看到「模型没有输出可见文本」却不知原因。
 */
@Composable
private fun ErrorBar(text: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "!",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.15f)),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "关闭错误提示",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun ThinkingIndicator(docStyle: Boolean, reduceMotion: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (docStyle) 0.dp else 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (reduceMotion) {
            // 减弱动态：静态三连点，不做无限循环动画
            repeat(3) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        } else {
            BouncingDot(delay = 0)
            BouncingDot(delay = 120)
            BouncingDot(delay = 240)
        }
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
    reduceMotion: Boolean = false,
    choicesEnabled: Boolean = true,
    htmlPreview: Boolean = false,
    highlight: Boolean = false,
    onCopy: () -> Unit = {},
    onRegenerate: (() -> Unit)? = null,
    onRegenerateAt: (() -> Unit)? = null,
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

    val highlightModifier = if (highlight) {
        Modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), MaterialTheme.shapes.medium)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    } else Modifier

    Column(Modifier.fillMaxWidth().then(highlightModifier)) {
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
                        reduceMotion = reduceMotion,
                        onChoice = onChoice,
                        choicesEnabled = choicesEnabled,
                        htmlPreview = htmlPreview,
                        modifier = if (animate && !reduceMotion) {
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
                                reduceMotion = reduceMotion,
                                onChoice = onChoice,
                                choicesEnabled = choicesEnabled,
                                htmlPreview = htmlPreview
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
            if (onRegenerateAt != null) {
                DropdownMenuItem(
                    text = { Text("从此处分支") },
                    leadingIcon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = { menuOpen = false; onRegenerateAt() }
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
    // 跟随气泡方向：用户消息靠右，助手消息靠左，避免切换器孤零零掉到另一侧。
    if (m.versions.size > 1) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            VersionSwitcher(m, onSwitchVersion)
        }
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
    reduceMotion: Boolean = false,
    onChoice: (String) -> Unit,
    choicesEnabled: Boolean = true,
    htmlPreview: Boolean = false,
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
                        choicesEnabled = choicesEnabled,
                        streaming = streaming,
                        // 光标只挂在最后一段正文末尾；减弱动态时彻底关闭
                        showCaret = streaming && !reduceMotion && index == parts.lastIndex,
                        htmlPreview = htmlPreview
                    )
                }
            }
        }
    }
}

/** 分支/版本切换器：‹ 2/3 ›，切换重新生成、编辑或分叉出的多个版本。 */
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
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onOpenPlus: () -> Unit,
    onMic: () -> Unit,
    voiceText: String? = null,
    onVoiceConsumed: () -> Unit = {},
    enterToSend: Boolean = false,
    modifier: Modifier = Modifier
) {
    var input by rememberSaveable { mutableStateOf("") }
    // 展开态锁存：一旦进入多行就保持，直到文本明显变短或清空。
    // 避免中文组合文本宽度变化导致单行/多行来回切，把输入框反复重建。
    var expanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(voiceText) {
        val v = voiceText?.trim().orEmpty()
        if (v.isNotEmpty()) {
            input = if (input.isBlank()) v else input.trimEnd() + " " + v
            onVoiceConsumed()
        }
    }

    val canSend = enabled && (input.isNotBlank() || attachments.isNotEmpty()) && !streaming
    val doSend: () -> Unit = {
        if (canSend) {
            onSend(input.trim())
            input = ""
        }
    }
    val cornerScale = LocalCornerScale.current
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        color = if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 17.sp,
        lineHeight = 26.sp,
        // 固定行高下 Compose 默认按行框顶部排字，单行胶囊里字母/数字会显得偏上。
        // 关键：单行输入必须用 Trim.None 保留完整行框（~26sp），再在行框内 Center；
        // 若用 Trim.Both 会把行框顶部裁掉，文字反而贴顶（截图中的「文字偏上」根因）。
        // Alignment.Center 保证 ASCII/CJK 混排时基线稳定、不上下跳。
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None
        )
    )
    val placeholder = if (enabled) "输入消息…" else "请先加载模型"
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val inlineTextWidth = maxWidth - 100.dp
            val naturalWidth = if (input.isBlank()) {
                0.dp
            } else with(density) {
                measurer.measure(
                    text = AnnotatedString(input),
                    style = textStyle,
                    maxLines = 1,
                    softWrap = false
                ).size.width.toDp()
            }
            val mustExpand = attachments.isNotEmpty() ||
                input.contains('\n') ||
                naturalWidth > inlineTextWidth
            val shouldCollapse = (input.isBlank() && attachments.isEmpty()) ||
                (expanded && naturalWidth < inlineTextWidth * 0.8f)
            LaunchedEffect(mustExpand, shouldCollapse) {
                if (mustExpand) expanded = true
                else if (shouldCollapse) expanded = false
            }

            val radius by animateDpAsState(
                targetValue = if (expanded) {
                    (24f * cornerScale).dp.coerceAtMost(32.dp)
                } else {
                    28.dp
                },
                animationSpec = tween(180),
                label = "composerRadius"
            )
            // 0.85r：矩形文字块的角不进入圆弧，保留至少 15% 的呼吸间距
            val textInset = if (expanded) {
                (radius * 0.85f).coerceAtLeast(18.dp)
            } else {
                50.dp
            }
            val buttonInset = if (expanded) {
                (radius * 0.5f).coerceIn(12.dp, 18.dp)
            } else {
                8.dp
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(radius),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                ),
                // 浅色主题下 6dp 投影会形成硬边 + 双描边，改为纯边框分层
                shadowElevation = 0.dp
            ) {
                Column(Modifier.fillMaxWidth()) {
                    PendingAttachmentBar(
                        attachments = attachments,
                        onRemove = onRemoveAttachment,
                        modifier = Modifier.padding(
                            start = textInset,
                            end = textInset,
                            top = 12.dp
                        )
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            // B 方案：胶囊 56→52dp，配合单行 padding 15→13dp，
                            // 让圆按钮四周留白统一为 8dp（上下 (52−36)/2=8，左右 8）。
                            .heightIn(min = 52.dp)
                    ) {
                        // 唯一的输入框实例：形态切换只改 padding，不重建节点，IME 连接不断
                        ComposerInput(
                            input = input,
                            onValueChange = { input = it },
                            enabled = enabled,
                            textStyle = textStyle,
                            placeholder = placeholder,
                            enterToSend = enterToSend,
                            onSend = doSend,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = textInset,
                                    end = textInset,
                                    top = if (expanded) 20.dp else 13.dp,
                                    bottom = if (expanded) 60.dp else 13.dp
                                )
                        )
                        if (expanded) {
                            Row(
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .padding(
                                        start = buttonInset,
                                        end = buttonInset,
                                        bottom = 12.dp
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ComposerPlusButton(enabled = enabled, onClick = onOpenPlus)
                                Spacer(Modifier.weight(1f))
                                ComposerActionButton(
                                    streaming = streaming,
                                    canSend = canSend,
                                    enabled = enabled,
                                    onSend = doSend,
                                    onStop = onStop,
                                    onMic = onMic
                                )
                            }
                        } else {
                            Row(
                                Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(start = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ComposerPlusButton(enabled = enabled, onClick = onOpenPlus)
                            }
                            Box(
                                Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 8.dp)
                            ) {
                                ComposerActionButton(
                                    streaming = streaming,
                                    canSend = canSend,
                                    enabled = enabled,
                                    onSend = doSend,
                                    onStop = onStop,
                                    onMic = onMic
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerInput(
    input: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    textStyle: TextStyle,
    placeholder: String,
    enterToSend: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = input,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        maxLines = 6,
        minLines = 1,
        keyboardOptions = KeyboardOptions(
            imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default
        ),
        keyboardActions = KeyboardActions(onSend = { if (enterToSend) onSend() }),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth()) {
                if (input.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                inner()
            }
        }
    )
}

@Composable
private fun ComposerPlusButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(
                    alpha = if (enabled) 1f else 0.5f
                )
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Outlined.Add,
            contentDescription = "添加",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun ComposerActionButton(
    streaming: Boolean,
    canSend: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onMic: () -> Unit
) {
    // 发送按钮是主操作，必须与 composer 背景明显区分。中性主题下 primaryContainer
    // 与 surfaceContainerLow 同为灰阶、对比不足（深色模式尤其看不清）。改为高对比中性色：
    // 深色底 → 近白按钮 + 近黑箭头；浅色底 → 近黑按钮 + 近白箭头。判断用实际 surface 亮度，
    // 兼容 light/dark/system 三种主题模式，且不引入任何强调色。
    val isDarkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val sendContainer = if (isDarkSurface) Color(0xFFF2F2F2) else Color(0xFF1C1C1C)
    val sendTint = if (isDarkSurface) Color(0xFF141414) else Color(0xFFFFFFFF)
    val container = when {
        streaming -> MaterialTheme.colorScheme.errorContainer
        canSend || enabled -> sendContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val tint = when {
        streaming -> MaterialTheme.colorScheme.onErrorContainer
        canSend || enabled -> sendTint
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = streaming || canSend || enabled) {
                when {
                    streaming -> onStop()
                    canSend -> onSend()
                    else -> onMic()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Crossfade(
            targetState = when {
                streaming -> 0
                canSend -> 1
                else -> 2
            },
            animationSpec = tween(150),
            label = "composerAction"
        ) { mode ->
            when (mode) {
                0 -> Icon(
                    Icons.Outlined.Stop,
                    contentDescription = "停止",
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
                1 -> Icon(
                    Icons.Outlined.ArrowUpward,
                    contentDescription = "发送",
                    tint = tint,
                    modifier = Modifier.size(20.dp)
                )
                else -> Icon(
                    Icons.Outlined.Mic,
                    contentDescription = "语音输入",
                    tint = tint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private fun formatCtx(n: Int): String = when {
    n >= 1024 -> "%.1fK".format(n / 1024.0)
    else -> n.toString()
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
