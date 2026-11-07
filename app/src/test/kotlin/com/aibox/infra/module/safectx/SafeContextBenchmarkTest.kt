package com.aibox.infra.module.safectx

import com.aibox.infra.module.manifest.SafeContextDto
import com.aibox.infra.module.manifest.SafeContextMetricDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SafeContextBenchmark 单测:验证 benchmark 算法 + 策略判定。
 *
 * 不依赖 LiteRT,使用 [MockInferenceProvider] 模拟模型在不同长度下的输出质量曲线。
 */
class SafeContextBenchmarkTest {

    @Test
    fun `static mode returns manifest values without benchmark`() {
        val config = SafeContextDto(
            autoDetect = false,
            defaultSafe = 2048,
            defaultWarn = 2500,
            defaultBlock = 3000,
            metric = SafeContextMetricDto.PERPLEXITY_RATIO,
            threshold = 0.7f,
            cacheKey = "test-static"
        )
        val benchmark = SafeContextBenchmark(MockInferenceProvider(emptyList()))
        val r = benchmark.run(config)
        assertTrue(r is SafeContextBenchmark.BenchmarkResult.Ok)
        val ok = r as SafeContextBenchmark.BenchmarkResult.Ok
        assertEquals(2048, ok.measuredSafe)
        assertEquals(2500, ok.measuredWarn)
        assertEquals(3000, ok.measuredBlock)
        assertEquals(false, ok.fromCache)
    }

    @Test
    fun `auto_detect finds threshold at expected length and applies 70 percent margin`() {
        // 模拟 Gemma 4 E2B:从 256 到 2048 都 OK,3072 跌破 0.7 阈值
        val scores = listOf(
            64 to 1.0f,
            128 to 1.0f,
            256 to 0.99f,
            384 to 0.98f,
            512 to 0.96f,
            768 to 0.93f,
            1024 to 0.90f,
            1536 to 0.85f,
            2048 to 0.75f,    // 仍 >= 0.7,通过
            3072 to 0.50f,    // 跌破,停止
            4096 to 0.30f
        )
        val provider = MockInferenceProvider(scores)
        val config = SafeContextDto(
            autoDetect = true,
            benchmarkCorpus = "assets://corpus.txt",
            metric = SafeContextMetricDto.PERPLEXITY_RATIO,
            threshold = 0.7f,
            cacheKey = "test-e2b"
        )
        val benchmark = SafeContextBenchmark(provider)
        val r = benchmark.run(config)
        assertTrue("got $r", r is SafeContextBenchmark.BenchmarkResult.Ok)
        val ok = r as SafeContextBenchmark.BenchmarkResult.Ok
        // last good = 2048, safe = 2048 * 0.7 = 1433
        assertEquals(1433, ok.measuredSafe)
        // warn = 1433 * 1.25 = 1791
        assertEquals(1791, ok.measuredWarn)
        // block = 3072(第一个跌破的)
        assertEquals(3072, ok.measuredBlock)
        assertEquals(false, ok.fromCache)
        assertEquals(10, ok.sampleCount)
    }

    @Test
    fun `auto_detect with cache hit returns cached value`() {
        val cache = InMemoryCache()
        cache.put("cached-key", SafeContextBenchmark.CachedResult(
            measuredSafe = 999,
            measuredWarn = 1200,
            measuredBlock = 1500,
            metric = SafeContextMetricDto.COSINE_SIMILARITY,
            threshold = 0.85f,
            cachedAt = System.currentTimeMillis()
        ))
        val config = SafeContextDto(
            autoDetect = true,
            benchmarkCorpus = "assets://corpus.txt",
            metric = SafeContextMetricDto.COSINE_SIMILARITY,
            threshold = 0.85f,
            cacheKey = "cached-key"
        )
        val benchmark = SafeContextBenchmark(MockInferenceProvider(emptyList()), cache)
        val r = benchmark.run(config)
        assertTrue(r is SafeContextBenchmark.BenchmarkResult.Ok)
        val ok = r as SafeContextBenchmark.BenchmarkResult.Ok
        assertEquals(999, ok.measuredSafe)
        assertEquals(true, ok.fromCache)
    }

    @Test
    fun `auto_detect all-pass returns max test length`() {
        // 所有长度都通过,返回最大测试长度
        val scores = listOf(
            64 to 1.0f, 128 to 1.0f, 256 to 0.99f, 384 to 0.98f,
            512 to 0.95f, 768 to 0.94f, 1024 to 0.93f, 1536 to 0.92f,
            2048 to 0.91f, 3072 to 0.90f, 4096 to 0.89f, 6144 to 0.88f,
            8192 to 0.87f
        )
        val provider = MockInferenceProvider(scores)
        val config = SafeContextDto(
            autoDetect = true,
            benchmarkCorpus = "assets://corpus.txt",
            metric = SafeContextMetricDto.PERPLEXITY_RATIO,
            threshold = 0.7f,
            cacheKey = "all-pass"
        )
        val benchmark = SafeContextBenchmark(provider)
        val r = benchmark.run(config)
        assertTrue(r is SafeContextBenchmark.BenchmarkResult.Ok)
        val ok = r as SafeContextBenchmark.BenchmarkResult.Ok
        // max test length = 8192, all pass so safe = 8192 * 0.7 = 5734
        assertEquals(5734, ok.measuredSafe)
        assertNull("measuredBlock 应为 null", ok.measuredBlock)
    }

    @Test
    fun `auto_detect 64-already-fails returns 64`() {
        // 极不可靠:64 都跌破阈值
        val scores = listOf(64 to 0.5f, 128 to 0.4f)
        val provider = MockInferenceProvider(scores)
        val config = SafeContextDto(
            autoDetect = true,
            benchmarkCorpus = "assets://corpus.txt",
            metric = SafeContextMetricDto.PERPLEXITY_RATIO,
            threshold = 0.7f,
            cacheKey = "broken"
        )
        val benchmark = SafeContextBenchmark(provider)
        val r = benchmark.run(config)
        assertTrue(r is SafeContextBenchmark.BenchmarkResult.Ok)
        val ok = r as SafeContextBenchmark.BenchmarkResult.Ok
        assertEquals(64, ok.measuredSafe)
    }

    @Test
    fun `auto_detect provider error returns Failed`() {
        val provider = MockInferenceProvider(emptyList(), failAt = 64)
        val config = SafeContextDto(
            autoDetect = true,
            benchmarkCorpus = "assets://corpus.txt",
            metric = SafeContextMetricDto.PERPLEXITY_RATIO,
            threshold = 0.7f,
            cacheKey = "fail"
        )
        val benchmark = SafeContextBenchmark(provider)
        val r = benchmark.run(config)
        // 第一次 score 失败 → break out of loop,results 为空 → Failed
        // 实际:第一次失败 break,但 results 仍空,所以 Failed
        // 但 corpus 加载 OK 才进 loop,所以:
        assertTrue("got $r", r is SafeContextBenchmark.BenchmarkResult.Failed)
    }

    @Test
    fun `SafeContextPolicy judgment logic`() {
        // E2B 模拟结果:safe=1433, warn=1791, block=3072
        val policy = SafeContextPolicy(
            measuredSafe = 1433,
            measuredWarn = 1791,
            measuredBlock = 3072
        )

        // 500 token: 安全
        assertEquals(SafeContextPolicy.Judgment.Safe, policy.judge(500))

        // 1433: 仍是 Safe(等于不超)
        assertEquals(SafeContextPolicy.Judgment.Safe, policy.judge(1433))

        // 1500: > safe 但 <= warn → Warn
        assertTrue(policy.judge(1500) is SafeContextPolicy.Judgment.Warn)

        // 2000: > warn → Warn
        assertTrue(policy.judge(2000) is SafeContextPolicy.Judgment.Warn)

        // 4000: > block → Block
        assertTrue(policy.judge(4000) is SafeContextPolicy.Judgment.Block)

        // 3072: = block → 严格 > 才算 Block,所以仍是 Warn
        // 实际上 judge() 用 > measuredBlock,所以 3072 仍是 Warn
        assertTrue(policy.judge(3072) is SafeContextPolicy.Judgment.Warn)
    }

    @Test
    fun `E4B safe_lower_than_E2B scenario validates`() {
        // 实测:E4B safe=1500,E2B safe=2048。E4B 反而更脆
        val e2bPolicy = SafeContextPolicy(measuredSafe = 2048, measuredWarn = 2500, measuredBlock = 3000)
        val e4bPolicy = SafeContextPolicy(measuredSafe = 1500, measuredWarn = 2000, measuredBlock = 2500)

        // 给 1800 token:
        //  - E2B: 1800 < 2048 → Safe
        //  - E4B: 1800 > 1500 → Warn(可能乱输出)
        assertEquals(SafeContextPolicy.Judgment.Safe, e2bPolicy.judge(1800))
        assertTrue(e4bPolicy.judge(1800) is SafeContextPolicy.Judgment.Warn)
    }

    // ---- helpers ----

    private class MockInferenceProvider(
        /** 模拟 (length -> score),按 length 查;不在表中返回失败。 */
        private val scores: List<Pair<Int, Float>>,
        /** 在指定长度时失败(模拟加载模型崩溃)。 */
        private val failAt: Int? = null
    ) : InferenceProvider {
        private val scoreMap = scores.toMap()
        override fun loadCorpus(path: String?): List<Int>? {
            return (1..10000).toList()  // 模拟 10000 token 的语料
        }
        override fun score(input: List<Int>, metric: SafeContextMetricDto): InferenceProvider.ScoreResult {
            val len = input.size
            if (failAt != null && len == failAt) {
                return InferenceProvider.ScoreResult.Err("inference crashed at len=$len")
            }
            val score = scoreMap[len] ?: 1.0f
            return InferenceProvider.ScoreResult.Ok(score)
        }
    }

    private class InMemoryCache : SafeContextCache {
        private val map = mutableMapOf<String, SafeContextBenchmark.CachedResult>()
        override fun get(key: String) = map[key]
        override fun put(key: String, result: SafeContextBenchmark.CachedResult) {
            map[key] = result
        }
    }
}
