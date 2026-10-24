package com.aibox.litert.backend

import android.os.Build
import com.aibox.litert.LiteRTLogger

/**
 * Delegate 可用性检测:决定 LiteRT-LM 在 NPU / GPU / CPU 之间选哪个。
 *
 * 检测路径:
 *  1. **Hexagon NPU**:
 *     - 通过 dlopen 检查 `libQnnHtp.so` / `libhexagon_interface.so` 是否可加载
 *       (QNN SDK 与设备 Hexagon DSP 版本一致时才存在)。
 *     - 通过 SoC 型号白名单(骁龙 8 Gen1+ 起 Hexagon V73+)确认 NPU 支持 LiteRT-LM。
 *     - 骨架版:看 Build.HARDWARE / Build.SOC_MANUFACTURER 字段粗略判断。
 *
 *  2. **GPU Delegate**:
 *     - 通过 dlopen 检查 `libtensorflowlite_gpu_gl.so` 是否可加载。
 *     - OpenGL ES 3.1+ 设备支持 GPU Delegate FP16。
 *     - 骨架版:永远返回 true(几乎所有 Android 9+ 设备都支持 GLES 3.1)。
 *
 *  3. **CPU**:
 *     - 永远返回 true(CPU 是兜底)。
 *
 * 上述 dlopen 调用在 native 层(litert_bridge.cpp 后续实现);本 Kotlin 侧只
 * 调用 [isHexagonAvailable] / [isGpuAvailable] / [isCpuAvailable] 三个 boolean 入口。
 */
object DelegateDetector {

    private const val TAG = "aibox.plugin.litert.delegate"

    /**
     * Hexagon NPU 是否可用。
     *
     * 骨架实现:检查 Build 字段 + SoC 型号字符串;真实判断在 nativeLoadModel 中
     * 通过 dlopen QNN libraries 二次确认。
     */
    fun isHexagonAvailable(): Boolean {
        // Build.SOC_MANUFACTURER 在 API 31+ 暴露;低于 API 31 通过 BOARD 字段降级判断。
        val socManufacturer = try {
            Build::class.java.getField("SOC_MANUFACTURER").get(null) as? String
        } catch (_: Throwable) {
            null
        } ?: ""

        val socModel = try {
            Build::class.java.getField("SOC_MODEL").get(null) as? String
        } catch (_: Throwable) {
            null
        } ?: ""

        val hardware = Build.HARDWARE ?: ""  // 如 "qcom", "mt6XXX"
        val board = Build.BOARD ?: ""        // 如 "taro", "kalama"

        // 高通平台判据:Build.SOC_MANUFACTURER == "QTI" 或 hardware/board 含 "qcom"。
        // 真实 Hexagon V73+ (LiteRT-LM 要求最低版本) 在骁龙 8 Gen1 (SM8450 / "taro") 起出现。
        val isQualcomm = socManufacturer.equals("QTI", ignoreCase = true) ||
            hardware.equals("qcom", ignoreCase = true) ||
            board.contains("taro", ignoreCase = true) ||
            board.contains("kalama", ignoreCase = true) ||
            board.contains("pineapple", ignoreCase = true)  // 8 Gen3

        // 骨架版:即使判定为高通,也要等 native 真实 dlopen QNN .so 才生效;
        // 这里给乐观估计,NPU 在 native 加载阶段若失败会降级到 GPU。
        val available = isQualcomm
        LiteRTLogger.debug(TAG, "Hexagon NPU available=$available (soc=$socManufacturer/$socModel hw=$hardware board=$board)")
        return available
    }

    /**
     * GPU Delegate 是否可用。
     *
     * 骨架实现:Android 9+ (API 28+) 几乎都支持 OpenGL ES 3.1+;本工程 minSdk=28
     * 即保证最低门槛。真实判断在 native 加载 libtensorflowlite_gpu_gl.so 后生效。
     */
    fun isGpuAvailable(): Boolean {
        // Build.VERSION.SDK_INT >= 28 即本工程 minSdk,等价于"所有可安装本插件的设备"。
        val available = android.os.Build.VERSION.SDK_INT >= 28
        LiteRTLogger.debug(TAG, "GPU delegate available=$available (sdk=${android.os.Build.VERSION.SDK_INT})")
        return available
    }

    /**
     * CPU 兜底永远可用。
     */
    fun isCpuAvailable(): Boolean = true

    /**
     * 按 NPU → GPU → CPU 顺序挑第一个可用的 delegate。
     */
    fun pickBest(): String = when {
        isHexagonAvailable() -> "npu"
        isGpuAvailable() -> "gpu"
        else -> "cpu"
    }
}
