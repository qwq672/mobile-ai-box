package com.aibox.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.aibox.util.notify.InAppNotifier
import kotlinx.coroutines.delay

/**
 * 通知宿主：用 Popup 浮在窗口顶部，不占父布局空间，
 * 因此可以放在导航容器旁边而不影响页面测量。
 */
@Composable
fun InAppNoticeHost() {
    val notice by InAppNotifier.notice.collectAsState()

    LaunchedEffect(notice?.id) {
        val id = notice?.id ?: return@LaunchedEffect
        delay(3200)
        InAppNotifier.dismiss(id)
    }

    if (notice == null) return

    Popup(
        alignment = Alignment.TopCenter,
        properties = PopupProperties(focusable = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            AnimatedVisibility(
                visible = notice != null,
                enter = slideInVertically(animationSpec = tween(220)) { -it } + fadeIn(tween(160)),
                exit = slideOutVertically(animationSpec = tween(160)) { -it } + fadeOut(tween(120))
            ) {
                notice?.let { n ->
                    Surface(
                        onClick = { InAppNotifier.dismiss(n.id) },
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(n.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
