package com.aibox.ui.shortcut

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibox.data.model.ModelInfo
import com.aibox.data.repo.ModelRepository
import com.aibox.domain.shortcut.PresetConfig
import com.aibox.domain.shortcut.Shortcut
import com.aibox.domain.shortcut.ShortcutRepository
import com.aibox.domain.shortcut.ShortcutTargetType
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 快捷方式编辑器 ViewModel（E 主页 Hybrid 设计）。
 *
 * 持有当前正在编辑的 [Shortcut] 草稿；UI 字段变更通过 [update] 系列方法应用；保存时调 [save]。
 *
 * 数据源：
 * - [ShortcutRepository]：load existing / save / delete。
 * - [ModelRepository]：list 已下载模型供"绑定模型"下拉。
 *
 * 状态机：
 * - 进入时调 [startNew] 或 [loadExisting]：初始化 [draft]。
 * - UI 通过 [update] / [updatePreset] 修改字段。
 * - 用户点保存：[save] 创建 / 更新；回调 [onSaved] 让 UI popBack。
 * - 用户点删除：[delete] 仅对 existing 有效；回调 [onDeleted] 让 UI popBack。
 */
class ShortcutEditorViewModel(
    private val shortcutRepository: ShortcutRepository,
    private val modelRepository: ModelRepository
) : ViewModel() {

    private val tag = "aibox.ui.shortcut.editor"

    /** 编辑器 UI 状态。 */
    data class UiState(
        /** 当前草稿；null 表示尚未启动（不应出现，UI 进入时必须 startNew 或 loadExisting）。 */
        val draft: Shortcut? = null,
        /** 可选模型列表（绑定模型下拉用）。 */
        val availableModels: List<ModelInfo> = emptyList(),
        /** 是否是已存在的快捷方式（影响"删除"按钮可见性）。 */
        val isExisting: Boolean = false,
        /** 一次性错误信息；UI 弹 snackbar 后调 [clearError]。 */
        val error: String? = null,
        /** 一次性完成事件（保存 / 删除成功）；UI 收到后 popBack。 */
        val finished: Boolean = false
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        // 监听模型列表变化（用户从外部导入模型后编辑器同步显示）
        viewModelScope.launch {
            modelRepository.observeAll().collectLatest { list ->
                _ui.value = _ui.value.copy(availableModels = list)
            }
        }
        // 优先级 1：消费 ChatScreen / ImageGenScreen 等通过 ShortcutEditorLauncher 传过来的预填草稿。
        // 必须在 init 同步执行（LaunchedEffect 触发 startNew / loadExisting 之前），避免被覆盖。
        val pendingDraft = ShortcutEditorLauncher.consumePendingDraft()
        if (pendingDraft != null) {
            LogSystem.info(tag, "init: consumed pending draft title=${pendingDraft.title} type=${pendingDraft.targetType}")
            startWithDraft(pendingDraft)
        } else {
            // 优先级 2：消费 HomeScreen 长按 shortcut 时传过来的 edit id。
            // 异步触发 loadExisting（避免阻塞 init）。
            val pendingEditId = ShortcutEditorLauncher.consumePendingEditId()
            if (pendingEditId != null) {
                LogSystem.info(tag, "init: consumed pending edit id=$pendingEditId")
                loadExisting(pendingEditId)
            }
            // 否则等待 Screen 的 LaunchedEffect 触发 startNew(initialTargetType)
        }
    }

    /**
     * 启动新建快捷方式。给定默认 targetType；UI 可后续修改。
     */
    fun startNew(targetType: ShortcutTargetType = ShortcutTargetType.CHAT) {
        val now = System.currentTimeMillis()
        val draft = Shortcut(
            id = "", // 待保存时分配
            title = "",
            subtitle = null,
            icon = ShortcutTargetType.defaultIcon(targetType),
            targetType = targetType,
            presetConfig = PresetConfig(),
            modelId = null,
            color = null,
            createdAt = now,
            lastUsedAt = null
        )
        _ui.value = _ui.value.copy(draft = draft, isExisting = false, finished = false, error = null)
        LogSystem.info(tag, "startNew: targetType=$targetType")
    }

    /**
     * 加载已存在的快捷方式进行编辑。
     */
    fun loadExisting(id: String) {
        viewModelScope.launch {
            val sc = runCatching { shortcutRepository.get(id) }
                .onFailure { LogSystem.error(tag, "loadExisting failed: ${it.message}", it) }
                .getOrNull()
            if (sc == null) {
                _ui.value = _ui.value.copy(error = "快捷方式不存在", finished = true)
                return@launch
            }
            _ui.value = _ui.value.copy(draft = sc, isExisting = true, finished = false, error = null)
            LogSystem.info(tag, "loadExisting: id=$id title=${sc.title}")
        }
    }

    /**
     * 用 [Shortcut.id] == "" 的新草稿加载；用于 ChatScreen / ImageGenScreen "保存为快捷方式" 入口
     * 预填配置后跳到编辑器。
     */
    fun startWithDraft(draft: Shortcut) {
        _ui.value = _ui.value.copy(
            draft = draft,
            isExisting = false,
            finished = false,
            error = null
        )
        LogSystem.info(tag, "startWithDraft: pre-filled title=${draft.title} type=${draft.targetType}")
    }

    /** 全字段更新（幂等；用于 UI 字段绑定）。 */
    fun update(transform: (Shortcut) -> Shortcut) {
        _ui.value.draft?.let { d ->
            _ui.value = _ui.value.copy(draft = transform(d))
        }
    }

    /** 仅更新预填配置。 */
    fun updatePreset(transform: (PresetConfig) -> PresetConfig) {
        update { it.copy(presetConfig = transform(it.presetConfig)) }
    }

    /** 保存（创建或更新）。 */
    fun save() {
        val draft = _ui.value.draft ?: run {
            _ui.value = _ui.value.copy(error = "草稿为空")
            return
        }
        if (draft.title.isBlank()) {
            _ui.value = _ui.value.copy(error = "请填写标题")
            return
        }
        viewModelScope.launch {
            try {
                if (_ui.value.isExisting && draft.id.isNotBlank()) {
                    shortcutRepository.update(draft.id) { existing ->
                        // 保留 id / createdAt / lastUsedAt，更新其它字段
                        draft.copy(
                            id = existing.id,
                            createdAt = existing.createdAt,
                            lastUsedAt = existing.lastUsedAt
                        )
                    }
                    LogSystem.info(tag, "Updated shortcut id=${draft.id}")
                } else {
                    shortcutRepository.create(draft)
                }
                _ui.value = _ui.value.copy(finished = true)
            } catch (t: Throwable) {
                LogSystem.error(tag, "save failed: ${t.message}", t)
                _ui.value = _ui.value.copy(error = "保存失败：${t.message}")
            }
        }
    }

    /** 删除（仅 existing 有效）。 */
    fun delete() {
        val draft = _ui.value.draft ?: return
        if (!_ui.value.isExisting || draft.id.isBlank()) return
        viewModelScope.launch {
            try {
                shortcutRepository.delete(draft.id)
                LogSystem.info(tag, "Deleted shortcut id=${draft.id}")
                _ui.value = _ui.value.copy(finished = true)
            } catch (t: Throwable) {
                LogSystem.error(tag, "delete failed: ${t.message}", t)
                _ui.value = _ui.value.copy(error = "删除失败：${t.message}")
            }
        }
    }

    /** 清空一次性错误。 */
    fun clearError() {
        _ui.value = _ui.value.copy(error = null)
    }

    /** 清空一次性 finished 标志（UI 在 popBack 后调用，避免再次进入时误判）。 */
    fun clearFinished() {
        _ui.value = _ui.value.copy(finished = false)
    }
}
