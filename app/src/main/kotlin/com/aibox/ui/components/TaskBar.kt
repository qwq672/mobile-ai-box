package com.aibox.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.aibox.util.task.TaskCenter

/**
 * 任务条宿主：用 Popup 浮在窗口顶部，不占父布局空间，不影响页面测量。
 * 展示 [TaskCenter] 里所有活跃任务（通常只有模型加载 1 个），带进度与取消。
 */
@Composable
fun TaskBarHost() {
    val tasks by TaskCenter.tasks.collectAsState()
    if (tasks.isEmpty()) return

    Popup(
        alignment = Alignment.TopCenter,
        properties = PopupProperties(focusable = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                tasks.forEach { task ->
                    AnimatedVisibility(
                        visible = true,
                        enter = slideInVertically(animationSpec = tween(200)) { -it } + fadeIn(tween(150)),
                        exit = slideOutVertically(animationSpec = tween(150)) { -it } + fadeOut(tween(120))
                    ) {
                        Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            tonalElevation = 3.dp,
                            shadowElevation = 2.dp
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (task.progress < 0) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Box(
                                        Modifier.size(20.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "${task.progress}%",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                    Text(
                                        task.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (task.progress >= 0) {
                                        LinearProgressIndicator(
                                            progress = { task.progress / 100f },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 6.dp)
                                        )
                                    }
                                }
                                if (task.cancellable) {
                                    TextButton(onClick = { TaskCenter.cancel(task.id) }) {
                                        Text("取消")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
