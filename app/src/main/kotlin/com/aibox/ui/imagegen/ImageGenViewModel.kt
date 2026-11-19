package com.aibox.ui.imagegen

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibox.AIBoxApp
import com.aibox.domain.module.DiscoveredModule
import com.aibox.domain.module.ModuleDiscoveryService
import com.aibox.domain.module.ModuleType
import com.aibox.util.log.LogSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 图像生成页 ViewModel（框架阶段）。
 *
 * 职责：模块发现 / 生成模式 / 参数状态 / 队列历史占位。
 * 不负责真实推理——生图由安装的 IMAGE_GEN_MODULE 提供，宿主调用接口待接入。
 */
class ImageGenViewModel : ViewModel() {

    private val context: Context get() = AIBoxApp.instance.applicationContext

    data class UiState(
        val module: DiscoveredModule? = null,
        val scanning: Boolean = false,
        val mode: ImageGenMode = ImageGenMode.TXT2IMG,
        val params: Map<String, Any> = defaultParams(),
        val jobs: List<String> = emptyList(),
    ) {
        companion object {
            fun defaultParams(): Map<String, Any> = mapOf(
                "ig.seed" to "-1",
                "ig.steps" to 20f,
                "ig.cfg" to 7.0f,
                "ig.size" to "512x512",
                "ig.sampler" to "euler_a",
                "ig.denoise" to 0.6f,
                "ig.nsfw" to true,
            )
        }
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        rescan()
    }

    /** 扫描已安装的 IMAGE_GEN_MODULE。 */
    fun rescan() {
        if (_ui.value.scanning) return
        _ui.value = _ui.value.copy(scanning = true)
        viewModelScope.launch {
            try {
                val modules = withContext(Dispatchers.IO) {
                    ModuleDiscoveryService(context).discoverAll()
                }
                val imageModule = modules.firstOrNull { it.type == ModuleType.IMAGE_GEN_MODULE }
                _ui.value = _ui.value.copy(module = imageModule, scanning = false)
            } catch (t: Throwable) {
                LogSystem.warn(TAG, "rescan failed: ${t.message}", t)
                _ui.value = _ui.value.copy(scanning = false)
            }
        }
    }

    fun setMode(mode: ImageGenMode) {
        _ui.value = _ui.value.copy(mode = mode)
    }

    /** 参数变更：只更新对应 key，保留其余（含模式专属参数）。 */
    fun onParamChange(key: String, value: Any) {
        val next = _ui.value.params.toMutableMap()
        next[key] = value
        _ui.value = _ui.value.copy(params = next)
    }

    companion object {
        private const val TAG = "aibox.ui.imagegen"
    }
}
