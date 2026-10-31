package com.aibox.ui.search

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aibox.domain.search.SearchDomain
import com.aibox.domain.search.SearchResult
import com.aibox.domain.search.SearchableEntity
import com.aibox.ui.components.unified.UnifiedCard
import com.aibox.ui.components.unified.UnifiedCardTone
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.DesignTokens
import com.aibox.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * 统一搜索页。
 *
 * 布局：
 * 1. TopAppBar：返回 + 标题 "搜索"
 * 2. 搜索输入框（auto focus）+ 清空按钮
 * 3. FilterChip 多选行：聊天 / 模型 / 设置 / 工具 / 模块 / 基准
 * 4. 结果列表：按域分组（每个域一个 section header）
 * 5. 每条结果用 [UnifiedCard] 渲染（标题 + 副标题 + 高亮片段）
 * 6. 点击调用 [onNavigate] 跳转到目标页
 *
 * @param onNavigate 点击搜索结果的回调；参数是命中的 [SearchableEntity]；
 *   AppNav 根据 searchDomain 分发到对应页面（详见 [dispatchSearchClick]）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onNavigate: (SearchableEntity) -> Unit,
    onBack: () -> Unit,
    vm: SearchViewModel = viewModel(factory = SearchViewModel.Factory)
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 错误显示 snackbar
    LaunchedEffect(ui.error) {
        ui.error?.let {
            snackbarHost.showSnackbar(it)
            vm.clearError()
        }
    }

    // 自动聚焦搜索框
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("统一搜索", style = Type.titleLarge) }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            // ============== 搜索框 ==============
            OutlinedTextField(
                value = ui.query,
                onValueChange = vm::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = DesignTokens.spacing16,
                        vertical = DesignTokens.spacing8
                    )
                    .focusRequester(focusRequester),
                placeholder = { Text("搜索聊天 / 模型 / 设置 / 模块 / 基准…", style = Type.bodyMedium) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (ui.query.isNotEmpty()) {
                        IconButton(onClick = vm::onClear) {
                            Icon(Icons.Outlined.Clear, contentDescription = "清空")
                        }
                    }
                },
                singleLine = true,
                shape = DesignTokens.shapeMedium,
                textStyle = Type.bodyLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = {
                        keyboard?.hide()
                        vm.onSearchNow()
                    }
                )
            )

            // ============== 域过滤 ==============
            DomainFilterRow(
                selected = ui.selectedDomains,
                onToggle = vm::onToggleDomain
            )

            // ============== 结果列表 ==============
            if (ui.searching) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(DesignTokens.spacing12))
                        Text("搜索中…", style = Type.bodyMedium, color = Colors.onSurfaceVariant)
                    }
                }
                return@Column
            }

            if (!ui.hasSearched) {
                EmptyHintState()
                return@Column
            }

            if (ui.results.isEmpty() && ui.query.isNotBlank()) {
                NoResultState(query = ui.query)
                return@Column
            }

            // 按域分组
            val grouped = ui.results.groupBy { it.entity.searchDomain }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = DesignTokens.spacing16,
                    end = DesignTokens.spacing16,
                    bottom = DesignTokens.spacing24
                ),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
            ) {
                grouped.forEach { (domain, results) ->
                    item(key = "header_${domain.name}") {
                        DomainHeader(domain = domain, count = results.size)
                    }
                    items(items = results, key = { r -> r.entity.searchId }) { r ->
                        ResultCard(result = r, query = ui.query, onClick = { onNavigate(r.entity) })
                    }
                }
            }
        }
    }
}

/* ==================== 域过滤 ==================== */

@Composable
private fun DomainFilterRow(
    selected: Set<SearchDomain>,
    onToggle: (SearchDomain) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = DesignTokens.spacing16),
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
    ) {
        items(items = DOMAIN_ORDER, key = { it.name }) { domain ->
            FilterChip(
                selected = domain in selected,
                onClick = { onToggle(domain) },
                label = { Text(domainLabel(domain)) },
                leadingIcon = { Icon(domainIcon(domain), contentDescription = null, modifier = Modifier.size(16.dp)) },
                shape = DesignTokens.shapeSmall,
                colors = FilterChipDefaults.filterChipColors()
            )
        }
    }
}

/** 域在 UI 上的显示顺序。 */
private val DOMAIN_ORDER: List<SearchDomain> = listOf(
    SearchDomain.CHAT_CONVERSATION,
    SearchDomain.CHAT_MESSAGE,
    SearchDomain.MODEL,
    SearchDomain.SETTING,
    SearchDomain.TOOL,
    SearchDomain.MODULE,
    SearchDomain.BENCHMARK
)

/** 域对应中文名。 */
fun domainLabel(d: SearchDomain): String = when (d) {
    SearchDomain.CHAT_CONVERSATION -> "聊天会话"
    SearchDomain.CHAT_MESSAGE -> "聊天消息"
    SearchDomain.MODEL -> "模型"
    SearchDomain.SETTING -> "设置"
    SearchDomain.TOOL -> "工具"
    SearchDomain.MODULE -> "模块"
    SearchDomain.BENCHMARK -> "基准测试"
}

/** 域对应图标。 */
fun domainIcon(d: SearchDomain): ImageVector = when (d) {
    SearchDomain.CHAT_CONVERSATION -> Icons.Outlined.Chat
    SearchDomain.CHAT_MESSAGE -> Icons.Outlined.ChatBubbleOutline
    SearchDomain.MODEL -> Icons.Outlined.Memory
    SearchDomain.SETTING -> Icons.Outlined.Settings
    SearchDomain.TOOL -> Icons.Outlined.Build
    SearchDomain.MODULE -> Icons.Outlined.Extension
    SearchDomain.BENCHMARK -> Icons.Outlined.Speed
}

/* ==================== 域分组标题 ==================== */

@Composable
private fun DomainHeader(domain: SearchDomain, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = DesignTokens.spacing12,
                bottom = DesignTokens.spacing4
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
    ) {
        Icon(
            domainIcon(domain),
            contentDescription = null,
            tint = Colors.primary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = domainLabel(domain),
            style = Type.titleSmall,
            color = Colors.primary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "$count",
            style = Type.labelSmall,
            color = Colors.onSurfaceVariant
        )
    }
}

/* ==================== 结果卡片 ==================== */

@Composable
private fun ResultCard(
    result: SearchResult,
    query: String,
    onClick: () -> Unit
) {
    val e = result.entity
    val highlightColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
    UnifiedCard(
        modifier = Modifier.fillMaxWidth(),
        tone = UnifiedCardTone.SURFACE_LOW,
        onClick = onClick
    ) {
        Text(
            text = highlightQuery(e.searchTitle, query, highlightColor),
            style = Type.titleMedium,
            color = Colors.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Spacer(Modifier.height(DesignTokens.spacing4))
        Text(
            text = e.searchSubtitle,
            style = Type.bodySmall,
            color = Colors.onSurfaceVariant,
            maxLines = 1
        )
        if (result.matchedSnippet.isNotBlank() && result.matchedSnippet != e.searchTitle) {
            Spacer(Modifier.height(DesignTokens.spacing4))
            Text(
                text = highlightQuery(result.matchedSnippet, query, highlightColor),
                style = Type.bodySmall,
                color = Colors.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}

/* ==================== 高亮 ==================== */

/** 把 query 子串在 text 中加底色（用粗体 + 主色代替背景，避免与深色主题冲突）。 */
private fun highlightQuery(text: String, query: String, highlightColor: androidx.compose.ui.graphics.Color): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    return buildAnnotatedString {
        val lower = text.lowercase()
        val q = query.lowercase()
        var i = 0
        while (i < text.length) {
            val hit = lower.indexOf(q, i)
            if (hit < 0) {
                append(text.substring(i))
                break
            }
            if (hit > i) append(text.substring(i, hit))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = highlightColor)) {
                append(text.substring(hit, hit + query.length))
            }
            i = hit + query.length
        }
    }
}

/* ==================== 空状态 ==================== */

@Composable
private fun EmptyHintState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing12)
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                tint = Colors.onSurfaceVariant,
                modifier = Modifier.size(56.dp)
            )
            Text(
                text = "输入关键词开始搜索",
                style = Type.titleMedium,
                color = Colors.onSurfaceVariant
            )
            Text(
                text = "支持聊天记录、模型、设置、工具、模块、基准测试",
                style = Type.bodyMedium,
                color = Colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NoResultState(query: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing12)
        ) {
            Text(
                text = "“$query”",
                style = Type.titleMedium,
                color = Colors.onSurfaceVariant
            )
            Text(
                text = "没有匹配的结果",
                style = Type.bodyMedium,
                color = Colors.onSurfaceVariant
            )
        }
    }
}
