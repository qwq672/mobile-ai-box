package com.aibox.ui.roleplay

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibox.AIBoxApp
import com.aibox.data.db.CharacterEntity
import com.aibox.data.db.PersonaEntity
import com.aibox.ui.components.unified.UnifiedCard
import com.aibox.ui.components.unified.UnifiedCardTone
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.DesignTokens
import com.aibox.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * 角色扮演页（MVP）。
 *
 * 两段内容：
 * 1. 我的身份（[PersonaEntity]）：选择 / 新建 / 编辑“我是谁”；
 * 2. 角色（[CharacterEntity]）：选择 AI 角色并进入聊天。
 *
 * 角色和身份互相独立：换角色不影响身份，同一条会话同时记住两者。
 * 数据直接走 [com.aibox.data.repo.RoleplayRepository]，页面本身不持有 ViewModel，
 * 避免为两个简单列表再加一层样板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoleplayScreen(
    onBack: () -> Unit,
    onStartChat: (characterId: Long, personaId: Long) -> Unit
) {
    val repo = AIBoxApp.instance.container.roleplayRepository
    val scope = rememberCoroutineScope()
    val characters by repo.characters.collectAsStateWithLifecycle(initialValue = emptyList())
    val personas by repo.personas.collectAsStateWithLifecycle(initialValue = emptyList())

    var activePersonaId by remember { mutableLongStateOf(0L) }
    var editingCharacter by remember { mutableStateOf<CharacterEntity?>(null) }
    var creatingCharacter by remember { mutableStateOf(false) }
    var editingPersona by remember { mutableStateOf<PersonaEntity?>(null) }
    var creatingPersona by remember { mutableStateOf(false) }

    // 首次进入补默认身份 + 示例角色（只写一次）
    LaunchedEffect(Unit) { runCatching { repo.ensureDefaults() } }
    // 选中的身份被删 / 首次加载时，自动落到第一个
    LaunchedEffect(personas) {
        if (personas.isNotEmpty() && personas.none { it.id == activePersonaId }) {
            activePersonaId = personas.first().id
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("角色扮演", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { creatingCharacter = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "新建角色")
                    }
                }
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(vertical = DesignTokens.spacing12),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing12)
        ) {
            item(key = "persona_header") { SectionTitle("我的身份") }

            if (personas.isEmpty()) {
                item(key = "persona_empty") {
                    HintCard("还没有身份，新建一个“我是谁”")
                }
            } else {
                items(items = personas, key = { "persona_${it.id}" }) { p ->
                    PersonaRow(
                        persona = p,
                        selected = p.id == activePersonaId,
                        onSelect = { activePersonaId = p.id },
                        onEdit = { editingPersona = p },
                        modifier = Modifier.padding(horizontal = DesignTokens.spacing16)
                    )
                }
            }
            item(key = "persona_add") {
                TextButton(
                    onClick = { creatingPersona = true },
                    modifier = Modifier.padding(horizontal = DesignTokens.spacing16)
                ) {
                    Text("＋ 新建身份")
                }
            }

            item(key = "character_header") { SectionTitle("角色") }

            if (characters.isEmpty()) {
                item(key = "character_empty") {
                    HintCard("还没有角色，点右上角 ＋ 新建一个")
                }
            } else {
                items(items = characters, key = { "character_${it.id}" }) { c ->
                    CharacterCard(
                        character = c,
                        onStart = { onStartChat(c.id, activePersonaId) },
                        onEdit = { editingCharacter = c },
                        modifier = Modifier.padding(horizontal = DesignTokens.spacing16)
                    )
                }
            }

            item(key = "bottom_spacer") { Spacer(Modifier.height(DesignTokens.spacing24)) }
        }
    }

    if (creatingCharacter || editingCharacter != null) {
        val initial = editingCharacter
        CharacterEditDialog(
            initial = initial,
            onDismiss = { creatingCharacter = false; editingCharacter = null },
            onSave = { c ->
                scope.launch { runCatching { repo.upsertCharacter(c) } }
                creatingCharacter = false
                editingCharacter = null
            },
            onDelete = if (initial != null) {
                {
                    scope.launch { runCatching { repo.deleteCharacter(initial) } }
                    creatingCharacter = false
                    editingCharacter = null
                }
            } else null
        )
    }

    if (creatingPersona || editingPersona != null) {
        val initial = editingPersona
        PersonaEditDialog(
            initial = initial,
            onDismiss = { creatingPersona = false; editingPersona = null },
            onSave = { p ->
                scope.launch { runCatching { repo.upsertPersona(p) } }
                creatingPersona = false
                editingPersona = null
            },
            onDelete = if (initial != null) {
                {
                    scope.launch { runCatching { repo.deletePersona(initial) } }
                    creatingPersona = false
                    editingPersona = null
                }
            } else null
        )
    }
}

/* ============================================================
 * 列表项
 * ============================================================ */

@Composable
private fun PersonaRow(
    persona: PersonaEntity,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    UnifiedCard(
        modifier = modifier.fillMaxWidth(),
        tone = if (selected) UnifiedCardTone.PRIMARY_CONTAINER else UnifiedCardTone.SURFACE_LOW,
        onClick = onSelect
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = persona.name,
                    style = Type.titleSmall,
                    color = Colors.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (persona.description.isNotBlank()) {
                    Text(
                        text = persona.description,
                        style = Type.bodySmall,
                        color = Colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (selected) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = "当前身份",
                    tint = Colors.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(DesignTokens.spacing8))
            }
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "编辑身份",
                    tint = Colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * 从 content URI 加载缩略位图（零第三方依赖）。
 * 仅在 uri 非空时解码，并限制采样尺寸避免大图占内存。
 */
@Composable
private fun rememberUriBitmap(uri: String): android.graphics.Bitmap? {
    val context = LocalContext.current
    return remember(uri) {
        if (uri.isBlank()) {
            null
        } else {
            runCatching {
                val fd = context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")
                if (fd == null) {
                    null
                } else {
                    fd.use {
                        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                        BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, opts)
                    }
                }
            }.getOrNull()
        }
    }
}

/** 角色头像来源：共用卡片图 → 独立头像图 → emoji 兜底。 */
private fun avatarUriOf(c: CharacterEntity): String =
    if (c.avatarUseCard) {
        c.cardImageUri.ifBlank { c.avatarImageUri }
    } else {
        c.avatarImageUri.ifBlank { c.cardImageUri }
    }

@Composable
private fun CharacterCard(
    character: CharacterEntity,
    onStart: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardBitmap = rememberUriBitmap(character.cardImageUri)
    val avatarUri = avatarUriOf(character)
    // 头像若与卡片图同源，直接复用已解码的位图，避免重复解码
    val avatarBitmap = if (avatarUri == character.cardImageUri && cardBitmap != null) {
        cardBitmap
    } else {
        rememberUriBitmap(avatarUri)
    }
    UnifiedCard(
        modifier = modifier.fillMaxWidth(),
        tone = UnifiedCardTone.SURFACE_LOW
    ) {
        // 卡片图横幅：设置了卡片图才显示，体现“图片样式”卡片
        if (cardBitmap != null) {
            Image(
                bitmap = cardBitmap.asImageBitmap(),
                contentDescription = "${character.name} 的卡片图",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
            Spacer(Modifier.height(DesignTokens.spacing8))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Colors.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (avatarBitmap != null) {
                    Image(
                        bitmap = avatarBitmap.asImageBitmap(),
                        contentDescription = "${character.name} 的头像",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(CircleShape)
                    )
                } else {
                    Text(
                        text = character.emoji.ifBlank { "🙂" },
                        style = Type.titleLarge
                    )
                }
            }
            Spacer(Modifier.width(DesignTokens.spacing12))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = character.name,
                    style = Type.titleSmall,
                    color = Colors.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val sub = character.personality.ifBlank { character.description }
                if (sub.isNotBlank()) {
                    Text(
                        text = sub,
                        style = Type.bodySmall,
                        color = Colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(DesignTokens.spacing8))
            TextButton(onClick = onStart) { Text("开始") }
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "编辑角色",
                    tint = Colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DesignTokens.spacing16, vertical = DesignTokens.spacing4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(16.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
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

@Composable
private fun HintCard(text: String) {
    UnifiedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DesignTokens.spacing16),
        tone = UnifiedCardTone.SURFACE_LOW
    ) {
        Text(text = text, style = Type.bodyMedium, color = Colors.onSurfaceVariant)
    }
}

/* ============================================================
 * 编辑弹窗
 * ============================================================ */

@Composable
private fun CharacterEditDialog(
    initial: CharacterEntity?,
    onDismiss: () -> Unit,
    onSave: (CharacterEntity) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var emoji by remember(initial) { mutableStateOf(initial?.emoji ?: "🙂") }
    var description by remember(initial) { mutableStateOf(initial?.description.orEmpty()) }
    var systemPrompt by remember(initial) { mutableStateOf(initial?.systemPrompt.orEmpty()) }
    var greeting by remember(initial) { mutableStateOf(initial?.greeting.orEmpty()) }
    var cardImageUri by remember(initial) { mutableStateOf(initial?.cardImageUri.orEmpty()) }
    var avatarImageUri by remember(initial) { mutableStateOf(initial?.avatarImageUri.orEmpty()) }
    var avatarUseCard by remember(initial) { mutableStateOf(initial?.avatarUseCard ?: true) }
    var personality by remember(initial) { mutableStateOf(initial?.personality.orEmpty()) }
    var worldview by remember(initial) { mutableStateOf(initial?.worldview.orEmpty()) }

    // 选图后必须 takePersistableUriPermission，否则重启后 URI 权限失效、图片丢失
    val pickCard = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            cardImageUri = uri.toString()
        }
    }
    val pickAvatar = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            avatarImageUri = uri.toString()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建角色" else "编辑角色") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("角色名") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = emoji,
                        onValueChange = { emoji = it.take(4) },
                        label = { Text("头像 Emoji") },
                        singleLine = true,
                        modifier = Modifier.width(96.dp)
                    )
                }

                // ===== 卡片图片 / 头像图片 =====
                Text("卡片图片", style = Type.titleSmall, color = Colors.onSurface)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { pickCard.launch(arrayOf("image/*")) }) {
                        Text(if (cardImageUri.isBlank()) "选择卡片图" else "更换卡片图")
                    }
                    if (cardImageUri.isNotBlank()) {
                        TextButton(onClick = { cardImageUri = "" }) { Text("清除") }
                    }
                }
                FilterChip(
                    selected = avatarUseCard,
                    onClick = { avatarUseCard = !avatarUseCard },
                    label = { Text("头像与卡片图共用") }
                )
                if (!avatarUseCard) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing8),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { pickAvatar.launch(arrayOf("image/*")) }) {
                            Text(if (avatarImageUri.isBlank()) "选择独立头像" else "更换头像")
                        }
                        if (avatarImageUri.isNotBlank()) {
                            TextButton(onClick = { avatarImageUri = "" }) { Text("清除") }
                        }
                    }
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("一句话简介") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = personality,
                    onValueChange = { personality = it },
                    label = { Text("性格（如：冷静、毒舌、乐观）") },
                    minLines = 1,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = worldview,
                    onValueChange = { worldview = it },
                    label = { Text("所属世界观 / 背景设定") },
                    minLines = 1,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("角色设定（说话方式 / 补充约束）") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = greeting,
                    onValueChange = { greeting = it },
                    label = { Text("开场白（空会话时展示）") },
                    minLines = 1,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        CharacterEntity(
                            id = initial?.id ?: 0L,
                            name = name.trim(),
                            emoji = emoji.ifBlank { "🙂" },
                            description = description.trim(),
                            systemPrompt = systemPrompt.trim(),
                            greeting = greeting.trim(),
                            cardImageUri = cardImageUri,
                            avatarImageUri = avatarImageUri,
                            avatarUseCard = avatarUseCard,
                            personality = personality.trim(),
                            worldview = worldview.trim(),
                            createdAt = initial?.createdAt ?: System.currentTimeMillis(),
                            lastUsedAt = initial?.lastUsedAt ?: 0L
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (initial != null && onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

@Composable
private fun PersonaEditDialog(
    initial: PersonaEntity?,
    onDismiss: () -> Unit,
    onSave: (PersonaEntity) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember(initial) { mutableStateOf(initial?.description.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建身份" else "编辑身份") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("身份名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("身份描述（性格 / 背景，可留空）") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        PersonaEntity(
                            id = initial?.id ?: 0L,
                            name = name.trim(),
                            description = description.trim(),
                            createdAt = initial?.createdAt ?: System.currentTimeMillis(),
                            lastUsedAt = initial?.lastUsedAt ?: 0L
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (initial != null && onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}
