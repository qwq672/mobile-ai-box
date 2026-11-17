package com.aibox.util.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用内通知总线：只保留最新一条，避免排队堆积打断操作。
 *
 * 放在 util 层，便于电量策略、模型加载、下载等非 UI 代码上报事件；
 * 具体展示由 `ui/components/InAppNotifier.kt` 里的宿主负责。
 */
object InAppNotifier {

    data class Notice(val id: Long, val text: String)

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    private var seq = 0L

    fun show(text: String) {
        if (text.isBlank()) return
        _notice.value = Notice(++seq, text)
    }

    fun dismiss(id: Long) {
        if (_notice.value?.id == id) _notice.value = null
    }
}
