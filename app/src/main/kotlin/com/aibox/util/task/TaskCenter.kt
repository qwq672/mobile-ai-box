package com.aibox.util.task

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 轻量任务中心：统一承载「有进度、可取消」的后台任务（当前主要是模型加载）。
 *
 * 设计取舍：
 * - 只保留有限个并发任务（模型加载同一时刻通常只有 1 个；下载/导入可并存）。
 * - [onCancel] 是「协作式取消」回调：调用方在阶段边界检查取消标志、跳出循环、释放资源。
 *   底层 native 加载（llama_model_load_from_file）是同步调用，无法在调用内部中断，
 *   所以取消的响应点是「进入 JNI 之前 / 返回之后 / 多后端回退的每轮之间」；点取消后
 *   任务条立即消失、状态复位，不会让界面假装还在跑。
 */
object TaskCenter {

    data class Task(
        val id: Long,
        val title: String,
        /** 0..100 表示确定进度；-1 表示不确定（转圈）。 */
        val progress: Int = -1,
        /** 是否显示取消按钮 */
        val cancellable: Boolean = true,
        /** 点击取消时回调，由调用方置取消标志 / 取消协程。 */
        val onCancel: (() -> Unit)? = null
    )

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private var seq = 0L

    /** 注册一个任务，返回 id。若同 title 的任务已存在则先移除旧的（防重复）。 */
    fun begin(title: String, cancellable: Boolean = true, onCancel: (() -> Unit)? = null): Long {
        val id = ++seq
        _tasks.update { prev ->
            prev.filterNot { it.title == title } + Task(id, title, -1, cancellable, onCancel)
        }
        return id
    }

    /** 更新进度。任务不存在时忽略。 */
    fun progress(id: Long, progress: Int) {
        _tasks.update { list -> list.map { if (it.id == id) it.copy(progress = progress) else it } }
    }

    /** 更新标题（如从「准备加载」变成「正在尝试 GPU」）。 */
    fun retitle(id: Long, title: String) {
        _tasks.update { list -> list.map { if (it.id == id) it.copy(title = title) else it } }
    }

    /** 结束（成功/失败/取消都调用）。可选延迟 dismiss 让成功态短暂可见。 */
    fun finish(id: Long) {
        _tasks.update { list -> list.filterNot { it.id == id } }
    }

    /** 由任务条「取消」按钮调用：先执行回调，再移除任务条。 */
    fun cancel(id: Long) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        task.onCancel?.invoke()
        _tasks.update { list -> list.filterNot { it.id == id } }
    }
}
