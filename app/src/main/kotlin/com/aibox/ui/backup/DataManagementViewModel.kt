package com.aibox.ui.backup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibox.AIBoxApp
import com.aibox.data.backup.BackupRepository
import com.aibox.data.backup.ExportSelection
import com.aibox.data.backup.ImportReport
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 数据导入导出页 ViewModel：管理导出勾选项、执行导出/导入、展示结果。 */
class DataManagementViewModel : ViewModel() {

    private val context: Context get() = AIBoxApp.instance.applicationContext
    private val repo by lazy { BackupRepository(context) }

    data class UiState(
        val selection: ExportSelection = ExportSelection(),
        val busy: Boolean = false,
        val message: String? = null,
        val report: ImportReport? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun setConversations(on: Boolean) = update { it.copy(selection = it.selection.copy(conversations = on)) }
    fun setCloudModels(on: Boolean) = update { it.copy(selection = it.selection.copy(cloudModels = on)) }
    fun setSettings(on: Boolean) = update { it.copy(selection = it.selection.copy(settings = on)) }
    fun setImages(on: Boolean) = update { it.copy(selection = it.selection.copy(images = on)) }
    fun setNote(note: String) = update { it.copy(selection = it.selection.copy(note = note)) }

    fun dismissMessage() = update { it.copy(message = null) }
    fun dismissReport() = update { it.copy(report = null) }

    private fun update(block: (UiState) -> UiState) {
        _ui.value = block(_ui.value)
    }

    /** 导出：把勾选的数据序列化后写入用户用 SAF 选择的目标文件。 */
    fun exportTo(uri: Uri) {
        if (_ui.value.busy) return
        update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val json = withContext(Dispatchers.IO) { repo.export(_ui.value.selection) }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入所选文件")
                }
                update { it.copy(busy = false, message = "导出成功：已保存到所选文件") }
            } catch (t: Throwable) {
                LogSystem.warn(TAG, "export failed: ${t.message}", t)
                update { it.copy(busy = false, message = "导出失败：${t.message ?: "未知错误"}") }
            }
        }
    }

    /** 导入：读取用户选择的文件（本软件备份 / ChatGPT / DeepSeek），自动识别格式。 */
    fun importFrom(uri: Uri) {
        if (_ui.value.busy) return
        update { it.copy(busy = true, message = null, report = null) }
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: error("无法读取所选文件")
                }
                val report = withContext(Dispatchers.IO) { repo.import(text) }
                update { it.copy(busy = false, report = report) }
            } catch (t: Throwable) {
                LogSystem.warn(TAG, "import failed: ${t.message}", t)
                update { it.copy(busy = false, message = "导入失败：${t.message ?: "未知错误"}") }
            }
        }
    }

    companion object {
        private const val TAG = "aibox.ui.backup"
    }
}
