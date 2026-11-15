package com.aibox.domain.inference

import android.content.Context
import com.aibox.util.log.LogSystem
import java.io.File

/**
 * Hexagon（高通 HTP）运行时资产部署。
 *
 * `AIBOX_NPU=ON` 构建会把 QNN/Hexagon SDK 产出的 skel 打进 `assets/hexagon/`。
 * DSP 侧不能直接读 APK，所以启动前要解压到应用私有目录，并把该目录写进
 * `ADSP_LIBRARY_PATH`，否则 `dlopen(libQnnHtp.so)` 之后找不到 skel 会直接失败。
 *
 * 普通构建（AIBOX_NPU=OFF）assets 为空，这里会直接跳过，不影响任何现有流程。
 */
object HexagonAssets {

    private const val TAG = "aibox.hexagon"
    private const val ASSET_DIR = "hexagon"

    @Volatile private var deployedDir: String? = null

    /**
     * 确保 skel 已部署，返回可用的目录；没有资产或解压失败返回 null。
     *
     * 幂等：同一进程只会真正解压一次。
     */
    fun prepare(context: Context?): String? {
        deployedDir?.let { return it }
        val ctx = context ?: return null

        val names = runCatching { ctx.assets.list(ASSET_DIR)?.toList().orEmpty() }
            .getOrDefault(emptyList())
        if (names.isEmpty()) {
            LogSystem.debug(TAG, "assets/$ASSET_DIR 为空，跳过 HTP skel 部署")
            return null
        }

        val outDir = File(ctx.filesDir, ASSET_DIR)
        if (!outDir.exists()) outDir.mkdirs()

        var copied = 0
        names.forEach { name ->
            val target = File(outDir, name)
            // 版本升级后 skel 可能变化：按大小粗略判断是否需要覆盖
            val srcSize = runCatching {
                ctx.assets.open("$ASSET_DIR/$name").use { it.available().toLong() }
            }.getOrDefault(-1L)
            if (target.exists() && srcSize >= 0 && target.length() == srcSize) return@forEach

            runCatching {
                ctx.assets.open("$ASSET_DIR/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                copied++
            }.onFailure {
                LogSystem.warn(TAG, "部署 $name 失败: ${it.message}")
            }
        }

        val dir = outDir.absolutePath
        deployedDir = dir
        LogSystem.info(TAG, "HTP skel 已就绪: $dir (files=${names.size}, copied=$copied)")
        return dir
    }
}
