package com.aibox.infra.benchmark

import com.aibox.domain.inference.InferenceConfigV2
import com.aibox.domain.inference.QuantType

/**
 * 单次基准测试规格（Layer 1 基础基准）。
 *
 * 一个 [BenchmarkSpec] 描述"用哪份模型 + 哪份配置 + 多长的 prompt + 生成多少 token + 重复多少次"。
 * [BasicBenchmark] 按 spec 跑 warmup + repCount 次推理，取中位数得到 [BenchmarkResult]。
 *
 * @property modelPath 模型文件绝对路径（gguf / tflite 等）。
 * @property config 推理配置（threads / kvQuant / batchSize 等）。
 * @property ppSize 提示词长度（近似 token 数；实际 prompt 字符串按 [BenchmarkSpec.promptText] 拼出）。
 * @property tgSize 生成 token 数（覆盖 [InferenceConfigV2.maxTokens]）。
 * @property repCount 重复次数（取中位数；最低 3 次以稳定结果）。
 * @property warmup 预热次数（不计入统计；保证首次推理的 mmap / 编译 / 缓存分配开销不污染结果）。
 */
data class BenchmarkSpec(
    val modelPath: String,
    val config: InferenceConfigV2,
    val ppSize: Int,
    val tgSize: Int,
    val repCount: Int = 3,
    val warmup: Int = 1
)

/**
 * 基础测试矩阵：覆盖"轻 / 中 / 重"三种 prompt×生成组合，加上线程扫描与 KV 量化扫描。
 *
 * 设计目标：单次完整跑完 [BASIC] 即可对比"模型 + 后端 + 配置"组合的吞吐性能；
 * [THREAD_SCAN] / [KV_QUANT_SCAN] 用于调优单变量扫描，找出最优线程数与 KV 量化级别。
 *
 * 注：[BenchmarkSpec.modelPath] / [BenchmarkSpec.config] 在矩阵里留空 / 用默认配置占位，
 * 实际跑前由 [BasicBenchmark] 调用方注入真实模型路径与基线配置（即"补全 spec"）。
 */
object BenchmarkMatrix {

    /** 基础三档负载：覆盖典型聊天 / 长文阅读 / 长生成场景。 */
    val BASIC: List<BenchmarkSpec> = listOf(
        // 轻负载：短 prompt + 少量生成（典型短消息回复）
        BenchmarkSpec(
            modelPath = "",
            config = InferenceConfigV2(),
            ppSize = 64,
            tgSize = 32,
            repCount = 3
        ),
        // 中负载：典型长 prompt + 中等生成（RAG / 长文摘要）
        BenchmarkSpec(
            modelPath = "",
            config = InferenceConfigV2(),
            ppSize = 512,
            tgSize = 128,
            repCount = 3
        ),
        // 重负载：长 prompt + 长生成（长文档处理 / 大段续写）
        BenchmarkSpec(
            modelPath = "",
            config = InferenceConfigV2(),
            ppSize = 1024,
            tgSize = 256,
            repCount = 3
        )
    )

    /**
     * 线程数扫描矩阵：1 / 2 / 4 / 6 线程各跑一遍轻负载（PP=64, TG=32）。
     *
     * 调用方在使用时需把每个 spec 的 `config.threads` 设为对应的 [threads] 值。
     * 这里仅声明要扫描的线程档位，实际 spec 由调用方用 [buildThreadScan] 生成。
     */
    val THREAD_SCAN_THREADS: List<Int> = listOf(1, 2, 4, 6)

    /** KV cache 量化扫描档位（F16 → Q8_0 → Q4_0，精度 / 内存 / 速度三维权衡）。 */
    val KV_QUANT_SCAN_QUANTS: List<QuantType> = listOf(
        QuantType.F16,
        QuantType.Q8_0,
        QuantType.Q4_0
    )

    /**
     * 构造一份"线程扫描" spec 列表：把基线 [baseSpec] 复制 N 份，每份只改 `config.threads`。
     *
     * 调用方典型用法：
     * ```
     * val threadSpecs = BenchmarkMatrix.buildThreadScan(
     *     baseSpec = BenchmarkSpec(modelPath = "/path/to.gguf", config = InferenceConfigV2(),
     *                              ppSize = 64, tgSize = 32)
     * )
     * threadSpecs.forEach { basicBenchmark.run(backend, loaded, it) }
     * ```
     */
    fun buildThreadScan(
        baseSpec: BenchmarkSpec,
        threads: List<Int> = THREAD_SCAN_THREADS
    ): List<BenchmarkSpec> = threads.map { t ->
        baseSpec.copy(config = baseSpec.config.copy(threads = t))
    }

    /**
     * 构造一份"KV 量化扫描" spec 列表：把基线 [baseSpec] 复制 N 份，每份只改 `config.kvQuant`。
     */
    fun buildKvQuantScan(
        baseSpec: BenchmarkSpec,
        quants: List<QuantType> = KV_QUANT_SCAN_QUANTS
    ): List<BenchmarkSpec> = quants.map { q ->
        baseSpec.copy(config = baseSpec.config.copy(kvQuant = q))
    }

    /**
     * 把基础矩阵 [BASIC] 的占位 modelPath / config 替换为真实值，得到可跑的 spec 列表。
     *
     * @param modelPath 实际模型文件路径。
     * @param baseConfig 基线配置（threads / kvQuant 等由调用方按需调整，本方法只填 modelPath）。
     */
    fun resolveBasic(
        modelPath: String,
        baseConfig: InferenceConfigV2
    ): List<BenchmarkSpec> = BASIC.map { it.copy(modelPath = modelPath, config = baseConfig) }
}
