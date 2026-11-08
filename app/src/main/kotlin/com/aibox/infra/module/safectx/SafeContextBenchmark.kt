package com.aibox.infra.module.safectx

import com.aibox.infra.module.manifest.SafeContextDto
import com.aibox.infra.module.manifest.SafeContextMetricDto
import com.aibox.util.log.LogSystem

/**
 * LiteRT 模块专属:模型在长输入下乱输出的实测临界点检测。
 *
 * **背景**:Gemma 4 E2B 标称 128K 上下文,实测在 ~3000 token 时开始乱输出;
 * E4B 在 ~2500 时就开始乱。这俩值无法从模型体积/量化推算,只能 runtime benchmark 实测。
 *
 * **算法**:
 * 1. 用一段固定语料,在不同输入长度(64/128/256/512/1024/2048/4096...)各跑一次推理
 * 2. 评估指标(cosine / exact_match / label_match / perplexity_ratio)
 * 3. 找到 metric 跌破 threshold 的临界长度 L
 * 4. 缓存 L 作为该模型的 "safe_context"
 * 5. 提供 safe/warn/block 三档阈值,通过 [SafeContextPolicy] 供 UI 提醒
 *
 * **零异常约定**:不抛异常,失败返回 [BenchmarkResult.Failed]。
 *
 * **线程**:跑 benchmark 是阻塞的,调用方应在后台线程调用。
 *
 * **与 LiteRT 的关系**:本类只定义**协议**(指标 + 阈值),不直接调用 LiteRT API。
 * 实际推理由 [InferenceProvider] 接口提供,让 LiteRT 模块自己实现,
 * 这样可单测(用 MockInferenceProvider),不依赖 LiteRT 编译。
 */
class SafeContextBenchmark(
    /** 推理提供者,由 LiteRT 模块实现。 */
    private val inferenceProvider: InferenceProvider,
    /** 缓存,可选。用于持久化 benchmark 结果避免每次启动都重测。 */
    private val cache: SafeContextCache? = null
) {

    private val tag = "aibox.safectx"

    /**
     * 跑 benchmark,测出安全上下文。
     *
     * @param config manifest 中的 safe_context 配置
     * @return [BenchmarkResult.Ok] 含 measured_safe_context + 推荐阈值; 或 [BenchmarkResult.Failed]
     */
    fun run(config: SafeContextDto): BenchmarkResult {
        if (!config.autoDetect) {
            // 静态模式:直接用 manifest 提供的值
            return BenchmarkResult.Ok(
                measuredSafe = config.defaultSafe ?: return BenchmarkResult.Failed(
                    "auto_detect=false 但 default_safe 未提供"
                ),
                measuredWarn = config.defaultWarn,
                measuredBlock = config.defaultBlock,
                fromCache = false,
                metric = config.metric ?: SafeContextMetricDto.PERPLEXITY_RATIO,
                threshold = config.threshold ?: 0.7f,
                sampleCount = 0
            )
        }

        // auto_detect=true: 检查缓存
        val cacheKey = config.cacheKey ?: "default"
        cache?.get(cacheKey)?.let { cached ->
            LogSystem.info(tag, "命中缓存: $cacheKey -> safe=${cached.measuredSafe}")
            return BenchmarkResult.Ok(
                measuredSafe = cached.measuredSafe,
                measuredWarn = cached.measuredWarn,
                measuredBlock = cached.measuredBlock,
                fromCache = true,
                metric = cached.metric,
                threshold = cached.threshold,
                sampleCount = 0
            )
        }

        // auto_detect=true: 跑 benchmark
        val corpus = inferenceProvider.loadCorpus(config.benchmarkCorpus)
            ?: return BenchmarkResult.Failed("无法加载标准语料: ${config.benchmarkCorpus}")

        val metric = config.metric ?: return BenchmarkResult.Failed("metric 未指定")
        val threshold = config.threshold ?: return BenchmarkResult.Failed("threshold 未指定")

        val testLengths = intArrayOf(64, 128, 256, 384, 512, 768, 1024, 1536, 2048, 3072, 4096, 6144, 8192)
        val results = mutableListOf<Pair<Int, Float>>()  // (length, metricValue)

        for (len in testLengths) {
            val input = corpus.take(len)
            val score = when (val r = inferenceProvider.score(input, metric)) {
                is InferenceProvider.ScoreResult.Ok -> r.score
                is InferenceProvider.ScoreResult.Err -> {
                    LogSystem.warn(tag, "len=$len 评分失败: ${r.reason},停止 benchmark")
                    break
                }
            }
            results.add(len to score)
            LogSystem.info(tag, "len=$len metric=$metric score=$score (threshold=$threshold)")

            if (score < threshold) {
                LogSystem.info(tag, "跌破阈值,len=$len,停止后续测试")
                break
            }
        }

        if (results.isEmpty()) {
            return BenchmarkResult.Failed("benchmark 全部失败")
        }

        // 找到第一个跌破阈值的长度 L_bad,安全值 = L_bad 前一个 * 0.7(留 30% 裕度)
        val firstBadIdx = results.indexOfFirst { it.second < threshold }
        val measuredSafe = if (firstBadIdx == -1) {
            // 全部通过,用最大测试长度
            results.last().first
        } else if (firstBadIdx == 0) {
            // 64 都跌破 → 极不可靠,直接返回 64
            64
        } else {
            // 安全值 = 上一个通过长度的 70%
            val lastGood = results[firstBadIdx - 1].first
            (lastGood * 0.7).toInt().coerceAtLeast(64)
        }

        val measuredWarn = (measuredSafe * 1.25f).toInt()
        val measuredBlock = if (firstBadIdx == -1) {
            (measuredSafe * 1.5f).toInt()
        } else {
            results[firstBadIdx].first
        }

        // 缓存
        cache?.put(cacheKey, CachedResult(
            measuredSafe = measuredSafe,
            measuredWarn = measuredWarn,
            measuredBlock = measuredBlock,
            metric = metric,
            threshold = threshold,
            cachedAt = System.currentTimeMillis()
        ))

        return BenchmarkResult.Ok(
            measuredSafe = measuredSafe,
            measuredWarn = measuredWarn,
            measuredBlock = measuredBlock,
            fromCache = false,
            metric = metric,
            threshold = threshold,
            sampleCount = results.size
        )
    }

    // ---- Result 类型 ----

    sealed class BenchmarkResult {
        data class Ok(
            val measuredSafe: Int,
            val measuredWarn: Int?,
            val measuredBlock: Int?,
            val fromCache: Boolean,
            val metric: SafeContextMetricDto,
            val threshold: Float,
            val sampleCount: Int
        ) : BenchmarkResult()

        data class Failed(val reason: String) : BenchmarkResult()
    }

    data class CachedResult(
        val measuredSafe: Int,
        val measuredWarn: Int,
        val measuredBlock: Int,
        val metric: SafeContextMetricDto,
        val threshold: Float,
        val cachedAt: Long
    )
}

/**
 * 推理提供者接口,由 LiteRT 模块实现。
 *
 * 通过这个接口,benchmark 不直接依赖 LiteRT API,可单测。
 */
interface InferenceProvider {
    /** 加载标准语料,返回 token 序列(直接用 List<Int> 简化)。 */
    fun loadCorpus(path: String?): List<Int>?

    /** 对输入跑一次推理,返回 metric 分数(0-1,1=完美)。 */
    fun score(input: List<Int>, metric: SafeContextMetricDto): ScoreResult

    sealed class ScoreResult {
        data class Ok(val score: Float) : ScoreResult()
        data class Err(val reason: String) : ScoreResult()
    }
}

/** 缓存接口。 */
interface SafeContextCache {
    fun get(key: String): SafeContextBenchmark.CachedResult?
    fun put(key: String, result: SafeContextBenchmark.CachedResult)
}

/**
 * 安全上下文策略:把 measured 值翻译成对用户的提示。
 *
 * 用法:
 * ```
 * val policy = SafeContextPolicy(measuredSafe=2048, measuredWarn=2500, measuredBlock=3000)
 * when (policy.judge(userInputTokens)) {
 *   is Judgment.Safe       -> inference()
 *   is Judgment.Warn       -> showWarning(...) -> if confirm inference() else truncate()
 *   is Judgment.Block      -> showStrongWarning(...) -> reject or truncate
 * }
 * ```
 */
data class SafeContextPolicy(
    val measuredSafe: Int,
    val measuredWarn: Int?,
    val measuredBlock: Int?
) {
    fun judge(inputTokens: Int): Judgment {
        return when {
            measuredBlock != null && inputTokens > measuredBlock -> Judgment.Block(inputTokens, measuredBlock)
            measuredWarn != null && inputTokens > measuredWarn -> Judgment.Warn(inputTokens, measuredWarn)
            inputTokens > measuredSafe -> Judgment.Warn(inputTokens, measuredSafe)
            else -> Judgment.Safe
        }
    }

    sealed class Judgment {
        object Safe : Judgment()
        data class Warn(val actualTokens: Int, val warnAt: Int) : Judgment()
        data class Block(val actualTokens: Int, val blockAt: Int) : Judgment()
    }
}
