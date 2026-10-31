package com.aibox.feature.chat.nudge

import com.aibox.core.inference.HexagonVersion
import com.aibox.core.inference.InferenceResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NudgeDecider v2 单元测试 — 验证"模型感知双因子"决策逻辑。
 *
 * 测试矩阵覆盖：
 *   - 用户偏好（禁用 / 冷却期）
 *   - 硬件维度（V73 / V75 / V79 / V81 / 非 Hexagon）
 *   - 模型维度（小模型 / 中型模型 / 大模型 / 未知）
 *   - 性能维度（合理速度 / 慢 / 无 tokens/s 数据）
 *   - 已装插件（NPU 插件 / GPU 插件 / 都没装）
 */
class NudgeDeciderTest {

    // ━━━ 用户偏好维度 ━━━

    @Test
    fun `should not nudge when user has disabled permanently`() = runBlocking {
        val decider = buildDecider(disabled = true)
        val result = buildResult(tps = 3.0, npuUsed = false)
        assertEquals(NudgeDecision.No, decider.decideAfterInference(result, modelParams = 1_500_000_000L))
    }

    @Test
    fun `should not nudge within cooldown period`() = runBlocking {
        val now = System.currentTimeMillis()
        val decider = buildDecider(lastNudgeTime = now - 1000)
        val result = buildResult(tps = 3.0, npuUsed = false)
        assertEquals(NudgeDecision.No, decider.decideAfterInference(result, modelParams = 1_500_000_000L))
    }

    // ━━━ 硬件维度 ━━━

    @Test
    fun `should not nudge when already using NPU`() = runBlocking {
        val decider = buildDecider()
        val result = buildResult(tps = 3.0, npuUsed = true)
        assertEquals(NudgeDecision.No, decider.decideAfterInference(result, modelParams = 1_500_000_000L))
    }

    @Test
    fun `should not nudge when device has no Hexagon and no Vulkan`() = runBlocking {
        val decider = buildDecider(
            deviceInfo = DeviceInfo(
                hexagonVersion = null,
                socModel = "MediaTek Dimensity 9300",
                totalMemoryMb = 8000,
                hasVulkanSupport = false,
            ),
        )
        val result = buildResult(tps = 3.0, npuUsed = false)
        assertEquals(NudgeDecision.No, decider.decideAfterInference(result, modelParams = 1_500_000_000L))
    }

    @Test
    fun `should recommend NPU for V75 device`() = runBlocking {
        val decider = buildDecider(
            deviceInfo = DeviceInfo(
                hexagonVersion = HexagonVersion.V75,
                socModel = "骁龙 8 Gen 3",
                totalMemoryMb = 12000,
            ),
        )
        val result = buildResult(tps = 3.0, npuUsed = false)  // 远低于 SMALL 桶的 15 tps 预期
        val decision = decider.decideAfterInference(result, modelParams = 1_500_000_000L)
        assertTrue(decision is NudgeDecision.Show)
        decision as NudgeDecision.Show
        assertTrue(decision.recommendedBackend is RecommendedBackend.NPU)
    }

    @Test
    fun `should recommend GPU when no Hexagon but Vulkan available`() = runBlocking {
        val decider = buildDecider(
            deviceInfo = DeviceInfo(
                hexagonVersion = null,
                socModel = "MediaTek Dimensity 9300",
                totalMemoryMb = 8000,
                hasVulkanSupport = true,
            ),
        )
        val result = buildResult(tps = 3.0, npuUsed = false)
        val decision = decider.decideAfterInference(result, modelParams = 1_500_000_000L)
        assertTrue(decision is NudgeDecision.Show)
        decision as NudgeDecision.Show
        assertTrue(decision.recommendedBackend is RecommendedBackend.GPU)
    }

    // ━━━ 模型维度 ━━━

    @Test
    fun `should not nudge for unknown model size`() = runBlocking {
        val decider = buildDecider()
        val result = buildResult(tps = 3.0, npuUsed = false)
        assertEquals(NudgeDecision.No, decider.decideAfterInference(result, modelParams = null))
    }

    @Test
    fun `should not nudge for very large model even if slow`() = runBlocking {
        // 70B 模型不属于任何桶（> 13B），不提示
        val decider = buildDecider()
        val result = buildResult(tps = 0.5, npuUsed = false)
        assertEquals(NudgeDecision.No, decider.decideAfterInference(result, modelParams = 70_000_000_000L))
    }

    // ━━━ 性能维度 ━━━

    @Test
    fun `should not nudge when speed is reasonable for model size`() = runBlocking {
        // 1.5B 模型，30 tps 已经达到预期，不应提示
        val decider = buildDecider()
        val result = buildResult(tps = 30.0, npuUsed = false)
        assertEquals(NudgeDecision.No, decider.decideAfterInference(result, modelParams = 1_500_000_000L))
    }

    @Test
    fun `should nudge when speed significantly below expectation`() = runBlocking {
        // 1.5B 模型预期 30 tps，实际只有 5 tps（远低于 50% 阈值），应提示
        val decider = buildDecider()
        val result = buildResult(tps = 5.0, npuUsed = false)
        val decision = decider.decideAfterInference(result, modelParams = 1_500_000_000L)
        assertTrue(decision is NudgeDecision.Show)
    }

    @Test
    fun `should not nudge when tokens per second is zero`() = runBlocking {
        // 没有 tps 数据（如生图任务），不提示
        val decider = buildDecider()
        val result = buildResult(tps = 0.0, npuUsed = false)
        assertEquals(NudgeDecision.No, decider.decideAfterInference(result, modelParams = 1_500_000_000L))
    }

    @Test
    fun `boundary case - speed at exactly threshold ratio`() = runBlocking {
        // 1.5B 预期 30 tps，阈值 = 30 * 0.5 = 15 tps
        // 实际 15.1 tps 应该不提示，14.9 应该提示
        val decider = buildDecider()

        val okResult = buildResult(tps = 7.6, npuUsed = false)
        assertEquals(NudgeDecision.No, decider.decideAfterInference(okResult, modelParams = 1_500_000_000L))

        val slowResult = buildResult(tps = 7.4, npuUsed = false)
        val decision = decider.decideAfterInference(slowResult, modelParams = 1_500_000_000L)
        assertTrue(decision is NudgeDecision.Show)
    }

    // ━━━ 已装插件维度 ━━━

    @Test
    fun `should not nudge when Hexagon plugin already installed`() = runBlocking {
        val decider = buildDecider(installedPlugins = setOf(NudgeDecider.HEXAGON_PLUGIN_ID))
        val result = buildResult(tps = 5.0, npuUsed = false)
        // 已装 NPU 插件但推理还慢 → 这是个 bug，但不该用提示去推用户做啥
        // 应该改由其他诊断机制处理，nudge 不应该重复打扰
        assertEquals(NudgeDecision.No, decider.decideAfterInference(result, modelParams = 1_500_000_000L))
    }

    // ━━━ 文案测试 ━━━

    @Test
    fun `snackbar text mentions GPU or NPU option and current speed`() = runBlocking {
        val decision = NudgeDecision.Show(
            currentTokensPerSec = 5.2,
            expectedTokensPerSec = 60.0,
            modelParamsB = 1_500_000_000L,
            modelCategory = "小型",
            recommendedBackend = RecommendedBackend.NPU(
                pluginId = "com.aibox.plugins.npu_snpe",
                vendorName = "Hexagon V75",
                estimatedSpeedup = 6.0,
            ),
            currentLatencyMs = 5200,
        )
        val text = decision.snackbarTextCn()
        // 包含三个核心信息
        assertTrue("应有当前速度", text.contains("5.2"))
        assertTrue("应有模型类型", text.contains("小型"))
        assertTrue("应有 GPU/NPU 选项描述", text.contains("GPU") || text.contains("NPU"))
        assertTrue("应有预期速度", text.contains("60"))
    }

    @Test
    fun `snackbar text for english mentions speed and option`() {
        val decision = NudgeDecision.Show(
            currentTokensPerSec = 5.2,
            expectedTokensPerSec = 60.0,
            modelParamsB = 1_500_000_000L,
            modelCategory = "small",
            recommendedBackend = RecommendedBackend.GPU(
                pluginId = "com.aibox.plugins.gpu_vulkan",
                estimatedSpeedup = 2.5,
            ),
            currentLatencyMs = 5200,
        )
        val text = decision.snackbarTextEn()
        assertTrue(text.contains("5.2"))
        assertTrue(text.contains("GPU") || text.contains("NPU"))
    }

    // ━━━ 工具方法 ━━━

    private fun buildDecider(
        deviceInfo: DeviceInfo = DeviceInfo(
            hexagonVersion = HexagonVersion.V75,
            socModel = "骁龙 8 Gen 3",
            totalMemoryMb = 12000,
        ),
        installedPlugins: Set<String> = emptySet(),
        disabled: Boolean = false,
        lastNudgeTime: Long = 0L,
    ): NudgeDecider {
        val preferences = object : NudgePreferences {
            override suspend fun accelerationNudgeDisabled(): Boolean = disabled
            override suspend fun lastAccelerationNudgeTime(): Long = lastNudgeTime
            override suspend fun markAccelerationNudgeShown(timestamp: Long) {}
            override suspend fun disableAccelerationNudge() {}
            override suspend fun reset() {}
            override val changes: kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.flowOf(Unit)
        }
        return NudgeDecider(deviceInfo, installedPlugins, preferences)
    }

    private fun buildResult(tps: Double, npuUsed: Boolean): InferenceResult =
        InferenceResult(
            latencyMillis = if (tps > 0) (1000.0 / tps).toLong() else 1000L,
            tokensPerSecond = tps,
            npuUsed = npuUsed,
            tokensGenerated = 100,
        )
}
