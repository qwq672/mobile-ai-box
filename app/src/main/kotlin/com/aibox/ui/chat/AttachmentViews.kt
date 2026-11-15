package com.aibox.ui.chat

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aibox.data.model.ChatAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 输入框上方的待发送附件条（横向滚动）。 */
@Composable
fun PendingAttachmentBar(
    attachments: List<ChatAttachment>,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (attachments.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        attachments.forEachIndexed { i, a ->
            Box {
                if (a.kind == "image") {
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center
                    ) {
                        AttachmentImage(a, Modifier.fillMaxSize())
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Row(
                            Modifier.padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Description,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                a.name,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 120.dp)
                            )
                        }
                    }
                }
                // 删除角标
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                        .clickable { onRemove(i) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "移除附件",
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

/** 消息里展示的附件（用户消息下方）。 */
@Composable
fun MessageAttachments(
    attachments: List<ChatAttachment>,
    modifier: Modifier = Modifier
) {
    if (attachments.isEmpty()) return
    val images = attachments.filter { it.kind == "image" }
    val files = attachments.filter { it.kind == "text" }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (images.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                images.forEach { img ->
                    Box(
                        Modifier
                            .widthIn(max = 160.dp)
                            .height(120.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    ) {
                        AttachmentImage(img, Modifier.fillMaxSize())
                    }
                }
            }
        }
        files.forEach { f ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Outlined.Description,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        f.name,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 200.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentImage(a: ChatAttachment, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bmp by produceState<ImageBitmap?>(initialValue = null, key1 = a.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = if (a.uri.startsWith("content://")) {
                    context.contentResolver.openInputStream(Uri.parse(a.uri))?.use {
                        BitmapFactory.decodeStream(it)
                    }
                } else {
                    BitmapFactory.decodeFile(a.uri)
                }
                bitmap?.asImageBitmap()
            }.getOrNull()
        }
    }
    val image = bmp
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = a.name,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
