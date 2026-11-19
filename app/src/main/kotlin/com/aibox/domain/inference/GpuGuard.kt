package com.aibox.domain.inference

import android.content.Context
import com.aibox.util.log.LogSystem

/**
 * GPU 后端崩溃护栏。
 *
 * Vulkan / OpenCL 驱动层崩溃是 SIGSEGV/SIGABRT，C++ 的 try/catch 拦不住，
 * 进程会直接消失（用户看到的就是「闪退」，日志里也常常没有异常栈）。
 *
 * 做法：每次推理开始前同步写一个「进行中」标记，正常结束时清除。
 * - 标记被清除 → 上一次推理正常跑完
 * - 标记残留 → 上一次推理过程中进程没了（崩溃或被强杀）
 *
 * 连续 [MAX_STRIKES] 次在 GPU 后端上留下残留标记，就把 GPU 后端标记为不可信，
 * 本次启动内直接回退 CPU，避免用户反复被闪退。任意一次 GPU 推理正常结束会清零计数。
 */
class GpuGuard(appContext: Context?) {

    private val prefs = appContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    @Volatile private var vulkanBlocked = false

    init {
        val p = prefs
        if (p != null && p.getBoolean(KEY_IN_FLIGHT, false)) {
            val last = p.getString(KEY_BACKEND, null)
            LogSystem.warn(TAG, "上一次推理未正常结束（进程在推理中消失），backend=$last")
            if (last == BackendType.VULKAN.name || last == BackendType.OPENCL.name) {
                val strikes = p.getInt(KEY_STRIKES, 0) + 1
                p.edit()
                    .putInt(KEY_STRIKES, strikes)
                    .putBoolean(KEY_IN_FLIGHT, false)
                    .remove(KEY_BACKEND)
                    .commit()
                if (strikes >= MAX_STRIKES) {
                    vulkanBlocked = true
                    LogSystem.warn(
                        TAG,
                        "GPU 后端连续 $strikes 次在推理中崩溃，本次启动禁用 Vulkan/OpenCL，自动回退 CPU"
                    )
                }
            } else {
                p.edit().putBoolean(KEY_IN_FLIGHT, false).remove(KEY_BACKEND).commit()
            }
        }
        vulkanBlocked = vulkanBlocked || (p?.getInt(KEY_STRIKES, 0) ?: 0) >= MAX_STRIKES
    }

    /** GPU 后端是否已被判定为不稳定（本进程内有效）。 */
    fun isVulkanBlocked(): Boolean = vulkanBlocked

    /** 推理开始：同步落盘标记（commit 而不是 apply，避免崩溃时丢写）。 */
    fun onInferenceStart(backend: BackendType) {
        prefs?.edit()
            ?.putBoolean(KEY_IN_FLIGHT, true)
            ?.putString(KEY_BACKEND, backend.name)
            ?.commit()
    }

    /** 推理正常结束：清标记；GPU 后端跑通说明驱动可用，把连续崩溃计数清零。 */
    fun onInferenceEnd(backend: BackendType) {
        onInferenceEnd(backend, clearedSuccessfully = true)
    }

    /**
     * 结束一次受保护的操作并清标记。
     *
     * @param clearedSuccessfully 操作是否"真正跑通"。只有真正跑通才把 GPU 连续崩溃计数清零；
     *   仅"进程没崩但加载失败"（如 load() 返回 false、设备初始化抛 C++ 异常被转成失败）
     *   不应清零，否则反复软失败会让 MAX_STRIKES 永远凑不齐、护栏形同虚设。
     *
     * 注意：本方法在进程被 SIGSEGV 带走时根本不会执行，in-flight 标记因此残留，
     * 由下次启动的 [init] 累计 strike —— 这正是崩溃护栏生效的原理。
     */
    fun onInferenceEnd(backend: BackendType, clearedSuccessfully: Boolean) {
        val p = prefs ?: return
        val e = p.edit().putBoolean(KEY_IN_FLIGHT, false).remove(KEY_BACKEND)
        if (backend == BackendType.VULKAN || backend == BackendType.OPENCL) {
            if (clearedSuccessfully) {
                e.putInt(KEY_STRIKES, 0)
                vulkanBlocked = false
            }
        }
        e.apply()
    }

    private companion object {
        const val TAG = "aibox.inference.gpu"
        const val PREF_NAME = "aibox_gpu_guard"
        const val KEY_IN_FLIGHT = "in_flight"
        const val KEY_BACKEND = "backend"
        const val KEY_STRIKES = "strikes"
        const val MAX_STRIKES = 2
    }
}
