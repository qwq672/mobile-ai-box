package com.pocketllm.di

import android.content.Context
import com.pocketllm.data.repo.ChatRepository
import com.pocketllm.data.repo.ModelRepository
import com.pocketllm.data.repo.SettingsRepository
import com.pocketllm.domain.download.DownloadManager
import com.pocketllm.domain.download.HfMirrorSource
import com.pocketllm.domain.download.HuggingFaceSource
import com.pocketllm.domain.download.ModelScopeSource
import com.pocketllm.domain.download.ModelSource
import com.pocketllm.domain.inference.InferenceEngine
import com.pocketllm.infra.jni.NativeBridge
import com.pocketllm.util.CpuInfo
import com.pocketllm.util.ThermalMonitor

/**
 * 手写 DI 容器（不引入 Koin/Dagger 减小体积）。
 * App 启动时构造一次，全局共享。
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val thermalMonitor: ThermalMonitor = ThermalMonitor().also {
        ThermalMonitor.appContext = appContext
        it.start()
    }

    val cpuInfo: CpuInfo = CpuInfo
    val nativeBridge: NativeBridge = NativeBridge()

    val inferenceEngine: InferenceEngine = InferenceEngine(
        nativeBridge = nativeBridge,
        thermalMonitor = thermalMonitor,
        cpuInfo = cpuInfo,
        appContext = appContext
    )

    val modelRepository: ModelRepository = ModelRepository(appContext)
    val settingsRepository: SettingsRepository = SettingsRepository(appContext)
    val chatRepository: ChatRepository = ChatRepository(appContext)
    val downloadManager: DownloadManager = DownloadManager(appContext)

    /** 三下载源 */
    val downloadSources: List<ModelSource> = listOf(
        HfMirrorSource(),     // 默认放第一
        HuggingFaceSource(),
        ModelScopeSource()
    )

    fun sourceById(id: String): ModelSource =
        downloadSources.first { it.id == id }
}
