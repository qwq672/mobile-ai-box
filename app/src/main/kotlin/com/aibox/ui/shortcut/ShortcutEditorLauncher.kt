package com.aibox.ui.shortcut

import com.aibox.domain.shortcut.Shortcut

/**
 * 跨页面 Shortcut 草稿 / 编辑 id 传输桥（E 主页 Hybrid 设计）。
 *
 * 用途：
 * 1. **新建预填**：ChatScreen / ImageGenScreen 等的"保存为快捷方式"按钮把当前配置打包成 [Shortcut] 草稿
 *    传给 [ShortcutEditorScreen] 预填；由于 Compose Navigation 的 SavedStateHandle 只支持基本类型，
 *    无法直接传 [Shortcut] 这种复杂对象，故用本桥做进程内传递。
 * 2. **编辑入口**：HomeScreen 长按某 shortcut 调 [onOpenShortcutEditor] 时，把 id 通过本桥传给编辑器
 *    （避免 SavedStateHandle 在嵌套 navigate 场景下的不可靠性；与 sessionId 的现有 SavedStateHandle 用法
 *    保持一致的"显式桥"模式）。
 *
 * 使用流程：
 * 1. ChatScreen 调 `vm.buildShortcutDraft(...)` 构造草稿 → `setPendingDraft(draft)` → navigate 到 editor
 * 2. HomeScreen 长按 shortcut → `setPendingEditId(id)` → navigate 到 editor
 * 3. [com.aibox.ui.shortcut.ShortcutEditorViewModel] init 时：
 *    - 优先消费 `consumePendingDraft()`（预填草稿场景）
 *    - 其次消费 `consumePendingEditId()`（编辑入口场景）
 *    - 都没有则走默认 startNew(initialTargetType)
 *
 * 线程安全：`@Volatile` 字段；set 后立即 navigate（同步）→ init 读取 → 清空，整个链路在 UI 线程内完成。
 *
 * 边界情况：用户在跳转中途取消（如快速点返回），草稿会残留 → 下次进入编辑器被误用。
 * 兜底：ViewModel init 读取后立即清空；若残留，最多被消费一次。
 */
object ShortcutEditorLauncher {

    @Volatile
    private var _pendingDraft: Shortcut? = null

    @Volatile
    private var _pendingEditId: String? = null

    /** 取出草稿；如果存在则返回并清空内部引用（一次性消费）。 */
    fun consumePendingDraft(): Shortcut? {
        val draft = _pendingDraft
        _pendingDraft = null
        return draft
    }

    /** 取出待编辑 id；如果存在则返回并清空内部引用（一次性消费）。 */
    fun consumePendingEditId(): String? {
        val id = _pendingEditId
        _pendingEditId = null
        return id
    }

    /** 仅查询，不消费；用于调试。 */
    fun peekPendingDraft(): Shortcut? = _pendingDraft
    fun peekPendingEditId(): String? = _pendingEditId

    /** 设置待消费草稿（预填新建场景）。 */
    fun setPendingDraft(draft: Shortcut) {
        _pendingDraft = draft
    }

    /** 设置待编辑 id（编辑场景）。 */
    fun setPendingEditId(id: String) {
        _pendingEditId = id
    }
}
