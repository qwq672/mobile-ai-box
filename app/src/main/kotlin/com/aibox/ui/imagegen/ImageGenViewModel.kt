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
        /**
         * 推理后端（A 宿主 / B 生图模块 / C 依赖框架 三层关系）：
         * - [ImageBackend.NPU_QNN]：B1，QNN 2.28 so 内置于生图模块，支持 SD1.5 + SDXL，无需外部依赖；
         * - [ImageBackend.CPU_GPU_MNN]：B2，依赖 C=MNN 推理框架模块，仅支持 SD1.5。
         */
        val backend: ImageBackend = ImageBackend.NPU_QNN,
        /** C：MNN 推理框架模块是否就绪（B2 的必选前置依赖）。 */
        val mnnModule: DiscoveredModule? = null,
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
                "ig.style" to "anime", // 生图受众以二次元为主，默认二次元风格预设
            )
        }
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** 内置 sd.cpp 框架版本（诊断用）；native 库缺失时降级为字符串提示。 */
    val sdCppVersion: String by lazy {
        runCatching { com.aibox.infra.jni.SdCppBridge.sdVersion() }
            .getOrDefault("sd.cpp 未编译进本包")
    }

    /** 内置 sd.cpp 引擎是否已接入（阶段一恒为 false）。 */
    val sdCppAvailable: Boolean by lazy {
        runCatching { com.aibox.infra.jni.SdCppBridge.sdAvailable() }
            .getOrDefault(false)
    }

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
                // C 层：MNN 推理框架模块（B2 CPU/GPU 生图的必选前置依赖）
                val mnnModule = modules.firstOrNull { isMnnFramework(it) }
                _ui.value = _ui.value.copy(module = imageModule, mnnModule = mnnModule, scanning = false)
            } catch (t: Throwable) {
                LogSystem.warn(TAG, "rescan failed: ${t.message}", t)
                _ui.value = _ui.value.copy(scanning = false)
            }
        }
    }

    fun setMode(mode: ImageGenMode) {
        _ui.value = _ui.value.copy(mode = mode)
    }

    fun setBackend(backend: ImageBackend) {
        _ui.value = _ui.value.copy(backend = backend)
    }

    /** 参数变更：只更新对应 key，保留其余（含模式专属参数）。 */
    fun onParamChange(key: String, value: Any) {
        val next = _ui.value.params.toMutableMap()
        next[key] = value
        _ui.value = _ui.value.copy(params = next)
    }

    companion object {
        private const val TAG = "aibox.ui.imagegen"

        /** 识别 C=MNN 推理框架模块：type 为推理框架，且包名/名称含 mnn。 */
        internal fun isMnnFramework(m: DiscoveredModule): Boolean {
            val text = "${m.packageName} ${m.name} ${m.displayName}".lowercase()
            return (m.type == ModuleType.BACKEND_PLUGIN || text.contains("backend")) && text.contains("mnn")
        }
    }
}

/**
 * 生图推理后端（对齐 A 宿主 / B 生图功能 / C MNN 框架 三层关系）：
 * - [NPU_QNN]（B1）：QNN 2.28 so 内置于生图模块自身，支持 SD1.5 与 SDXL；无外部前置依赖。
 * - [CPU_GPU_MNN]（B2）：依赖 C=MNN 推理框架模块；仅支持 SD1.5；C 为必选前置，缺失时入口置灰。
 */
enum class ImageBackend(
    val label: String,
    val shortDesc: String,
    val supportedSd: String,
    val dependencyLabel: String,
) {
    NPU_QNN(
        "NPU · Hexagon",
        "B1：QNN 2.28 运行时（so 内置于生图模块），走骁龙 NPU，速度最快",
        "SD 1.5 / SDXL",
        "自带 QNN，无外部依赖",
    ),
    CPU_GPU_MNN(
        "CPU / GPU · MNN",
        "B2：依赖 C=MNN 推理框架模块，兼容普通设备，仅支持 SD 1.5",
        "仅 SD 1.5",
        "需先安装 MNN 推理框架模块（必选）",
    ),
    SDCPP(
        "CPU · sd.cpp（内置）",
        "B0：宿主内置 sd.cpp 推理框架骨架，当前为接口阶段，尚未接入真实出图",
        "待接入",
        "宿主内置，无需安装",
    ),
}
