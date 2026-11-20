package com.aibox.domain.inference

import android.app.ActivityManager
import android.content.Context
import com.aibox.util.log.LogSystem
import java.io.File

/**
 * 内存护栏。
 *
 * 端侧加载大模型的失败方式通常不是「报错」，而是被系统直接 OOM kill —— 用户看到的是闪退。
 * 这里做两层保护：
 *
 * 1. 加载前预估：模型文件大小 + 运行缓冲（KV / compute buffer / 系统余量）
 *    与当前可用内存比较，明显放不下就直接拒绝并给出可读原因。
 * 2. 加载后复核：系统整体占用超过阈值时立刻卸载，避免刚加载完就被系统杀掉。
 *
 * 运行期的 `onTrimMemory` 由 [InferenceEngine.onTrimMemory] 处理（中断生成 + 释放缓存）。
 */
object MemoryGuard {

    private const val TAG = "aibox.memory"
    private const val MB = 1024L * 1024L

    /** 加载后系统占用超过该比例就认为随时会被 OOM kill。 */
    private const val POST_LOAD_LIMIT = 0.95

    /**
     * 运行缓冲预估上限（MB）：KV cache + compute buffer + 系统余量。
     *
     * GGUF 权重走 mmap、按需换页，并不在加载时一次性常驻，旧版按“总内存 1/5”估算
     * （16GB 机器 ≈ 3057MB）过于保守：会把 4.4GB 的 Q4_0 模型误判为放不下、直接拦截。
     * 实测 KV（8K 上下文、Q8_0）+ compute（batch 512）合计约 1GB，这里按 1GB 封顶。
     * 加载后仍有 [postLoadProblem] 复核兜底，因此预检偏宽松是安全的。
     */
    private const val RUN_BUFFER_MB = 1024L

    data class Snapshot(
        val totalMb: Long,
        val availableMb: Long,
        val usedMb: Long,
        val appRssMb: Long
    ) {
        val pressure: Double get() = if (totalMb <= 0) 0.0 else usedMb.toDouble() / totalMb
    }

    fun snapshot(context: Context?): Snapshot {
        val am = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val total = mi.totalMem / MB
        val avail = mi.availMem / MB
        return Snapshot(total, avail, (total - avail).coerceAtLeast(0), appRssMb())
    }

    /**
     * 加载前预检。
     *
     * 注意：GGUF 是 mmap 加载，文件大小不等于常驻内存；权重按需换页，加载期真正额外
     * 占用的是 KV cache + compute buffer，用 [RUN_BUFFER_MB]（约 1GB）估算即可。
     * 加载后另有 [postLoadProblem] 按实际占用复核，避免误拦截可运行的模型。
     */
    fun canLoad(context: Context?, modelPath: String): Result<Unit> {
        val snap = snapshot(context)
        if (snap.totalMb <= 0) return Result.success(Unit)

        val fileMb = runCatching { File(modelPath).length() / MB }.getOrDefault(0L)
        val bufferMb = RUN_BUFFER_MB
        val needMb = fileMb + bufferMb

        if (needMb > snap.availableMb) {
            val msg = "内存不足：模型约 ${fileMb}MB + 运行缓冲 ${bufferMb}MB，当前可用仅 ${snap.availableMb}MB" +
                "（总内存 ${snap.totalMb}MB）。请换更小的量化或关闭后台应用。"
            LogSystem.warn(TAG, msg)
            return Result.failure(IllegalStateException(msg))
        }
        LogSystem.info(
            TAG,
            "内存预检通过：model=${fileMb}MB need=${needMb}MB avail=${snap.availableMb}MB total=${snap.totalMb}MB"
        )
        return Result.success(Unit)
    }

    /** 加载后复核；返回非空表示需要立即卸载。 */
    fun postLoadProblem(context: Context?): String? {
        val snap = snapshot(context)
        if (snap.totalMb <= 0) return null
        if (snap.pressure >= POST_LOAD_LIMIT) {
            return "加载后内存占用 ${(snap.pressure * 100).toInt()}%" +
                "（${snap.usedMb}/${snap.totalMb}MB，进程 ${snap.appRssMb}MB），已接近系统上限，" +
                "继续推理会被 OOM 杀掉。已自动卸载，请降低上下文或换更小的模型。"
        }
        return null
    }

    /** 当前进程 RSS（/proc/self/statm 的 resident 页数）。 */
    private fun appRssMb(): Long = runCatching {
        val statm = File("/proc/self/statm").readText().trim().split(' ')
        val pages = statm.getOrNull(1)?.toLongOrNull() ?: return@runCatching 0L
        val pageSize = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
        pages * pageSize / MB
    }.getOrDefault(0L)
}
