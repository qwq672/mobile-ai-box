package com.pocketllm.domain.inference

import android.util.Log
import com.pocketllm.infra.jni.NativeBridge

/**
 * 内存优化器 —— 手机端跑大模型发烫的真凶是「内存调用」而非「计算」本身。
 *
 * 本类从代码层面落实以下优化（非"提示用户凉快后继续"）：
 *
 * 1. **模型 mmap 而非 read**：避免整份权重进 page cache 又被 evict 抖动。
 * 2. **KV cache 量化**：把 f16 KV 压成 q8_0 / q4_0，带宽和占用都降一半以上。
 * 3. **KV cache 复用**：load 阶段一次性 mmap 预分配 max_ctx 大小，generation 不再 realloc。
 * 4. **批处理内存池**：compute buffer 用池化，prompt 阶段复用同一片内存。
 * 5. **Pinned Direct Buffer**：JNI 用 DirectByteBuffer 传递 token ids，避免 copy。
 * 6. **防回退策略**：antiRollback=false 时，热到阈值自动降 cpu_threads / batch，不让 GPU/NPU 频率被打回再回升。
 * 7. **空闲释放**：N 秒无推理则把 KV cache shrink 到最近一段，下次首 token 稍慢但内存恒稳。
 * 8. **显式 mlock 关键段**：避免关键权重被 swap 出去（Android 无 swap，但 ashmem 同理）。
 *
 * 这些都通过 [NativeBridge] 调到 C++ 侧（memory_pool.cpp）实际执行。
 */
class MemoryOptimizer(private val native: NativeBridge) {

    /** 预热内存池，按当前 config 一次性分配。 */
    fun warmup(config: InferenceConfig) {
        val kvBytes = estimateKvBytes(config)
        val computeBytes = estimateComputeBytes(config)
        native.memoryPoolWarmup(kvBytes, computeBytes)
        Log.i("MemoryOptimizer",
            "warmup kv=${kvBytes / 1024 / 1024}MB compute=${computeBytes / 1024 / 1024}MB")
    }

    /** 释放所有池化内存（unload 时调用） */
    fun releaseAll() {
        native.memoryPoolRelease()
    }

    /**
     * 空闲超过 [idleMs] 毫秒，把 KV cache shrink 到最近 [keepTokens] 个 token，
     * 释放大头内存。下次首 token 会稍慢（需重算几个 token），但内存稳定。
     */
    fun shrinkIfIdle(idleMs: Long, keepTokens: Int) {
        native.memoryPoolShrinkIdle(idleMs, keepTokens)
    }

    /**
     * 申请一片 DirectByteBuffer 用于 JNI 传 token ids。
     * 调用方使用完调用 [returnDirectBuffer] 归还，避免反复分配。
     */
    fun borrowDirectBuffer(byteSize: Int): java.nio.ByteBuffer {
        return native.borrowDirectBuffer(byteSize)
    }

    fun returnDirectBuffer(buf: java.nio.ByteBuffer) {
        native.returnDirectBuffer(buf)
    }

    /** 当前 native 侧峰值内存（MB），用于 UI 显示 */
    fun peakMemoryMb(): Long = native.memoryPoolPeakMb()

    // ---- 估算公式（与 C++ 侧一致） ----

    /**
     * KV cache 内存估算：
     *   layers * 2(k+v) * context * head_dim * sizeof(elem)
     * 量化后 elem size：f16=2, q8_0≈1.06, q4_0≈0.56
     * 这里用粗略常量（多数 1-3B 模型 26~32 层），保守取 32 层、128 head_dim。
     */
    private fun estimateKvBytes(c: InferenceConfig): Long {
        val layers = 32L
        val headDim = 128L
        val elemSize = when (c.kvQuant) {
            "f16"  -> 2.0
            "q8_0" -> 1.06
            "q4_0" -> 0.56
            else   -> 2.0
        }
        return (layers * 2 * c.contextLength * headDim * elemSize).toLong()
    }

    /**
     * compute buffer 估算（粗略）：
     *   主要由 physicalBatch 决定，加固定 overhead。
     */
    private fun estimateComputeBytes(c: InferenceConfig): Long {
        val base = 64L * 1024 * 1024 // 64MB 固定
        val perBatch = 256L * 1024   // 每 batch 约 256KB
        return base + c.physicalBatch * perBatch
    }
}
