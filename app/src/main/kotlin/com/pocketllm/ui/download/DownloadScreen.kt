package com.pocketllm.ui.download

import androidx.compose.runtime.Composable

/**
 * DownloadScreen 已合并到 ModelsScreen（三段式：已下载 / 推荐一键下载 / 从仓库浏览）。
 * 该文件保留为占位以避免破坏外部引用；若需要重新启用独立 Download 标签，
 * 请到 `ui/nav/AppNav.kt` 取消 `Dest.Download` 的注释并恢复 `composable(Dest.Download.route) { DownloadScreen() }`。
 */
@Composable
fun DownloadScreen() {
    /* no-op stub */
}
