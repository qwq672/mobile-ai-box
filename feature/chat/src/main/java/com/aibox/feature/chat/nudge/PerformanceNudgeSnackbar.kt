package com.aibox.feature.chat.nudge

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 性能提示 UI — 基于 NudgeDecider 输出渲染 Snackbar。
 *
 * v2 改进（按用户要求）：
 *   两个明确按钮：
 *     1. [前往插件市场] → onGoToMarket
 *     2. [永不提示]    → onDisablePermanently（永久关闭）
 *
 * 文案（中文）：
 *   "当前 小型 模型仅 5.2 tokens/s。
 *    下载配套的 GPU 或 NPU 后端模块，速度也许会更快。"
 *
 * 反屎山原则：
 * - UI 不做决策：只接收 [NudgeDecision.Show] 渲染，决策在 ViewModel 层
 * - 文案在决策类里生成：UI 不拼接中英文，避免文案散落
 * - 用户点"永不提示"立即调 [NudgePreferences.disableAccelerationNudge] 永久关闭
 *   并 dismiss 当前 Snackbar
 *
 * 设计参考：docs/PERFORMANCE_NUDGE.md "v2: 双按钮方案"
 */
@Composable
public fun PerformanceNudgeSnackbar(
    decision: NudgeDecision.Show?,
    onGoToMarket: (RecommendedBackend) -> Unit,
    onDisablePermanently: () -> Unit,
    onDismissed: () -> Unit = {},
) {
    val hostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(decision) {
        if (decision == null) return@LaunchedEffect
        val result = hostState.showSnackbar(
            message = decision.snackbarTextCn(),
            actionLabel = "前往插件市场",
            duration = SnackbarDuration.Long,
            withDismissAction = true,
        )
        when (result) {
            androidx.compose.material3.SnackbarResult.ActionPerformed -> onGoToMarket(decision.recommendedBackend)
            androidx.compose.material3.SnackbarResult.Dismissed -> onDismissed()
        }
    }

    // 自定义 Snackbar：含"前往插件市场"主按钮 + "永不提示"次按钮
    if (decision != null) {
        Snackbar(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            actionContentColor = MaterialTheme.colorScheme.primary,
            action = {
                Row {
                    TextButton(onClick = { onGoToMarket(decision.recommendedBackend) }) {
                        Text("前往插件市场")
                    }
                    TextButton(onClick = {
                        onDisablePermanently()
                        onDismissed()
                    }) {
                        Text("永不提示")
                    }
                }
            },
        ) {
            Text(decision.snackbarTextCn(), modifier = Modifier.padding(end = 8.dp))
        }
    }
}

/**
 * 兼容旧接口：直接展示 Snackbar 用 SnackbarHost（用于嵌入 Scaffold）
 */
@Composable
public fun rememberNudgeSnackbarHost(): SnackbarHostState = remember { SnackbarHostState() }
