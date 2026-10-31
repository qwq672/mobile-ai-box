package com.aibox.feature.home

/**
 * 首次启动向导契约。
 *
 * 流程步骤（顺序固定，禁止打乱）：
 *   1. WELCOME         : 欢迎页 + 隐私说明
 *   2. LANGUAGE        : 选择界面语言
 *   3. USE_CASE        : 选择主要使用场景（影响后端推荐）
 *   4. NPU_DETECT      : 检测设备 NPU，告知是否有加速空间
 *   5. PERFORMANCE     : 选择性能档位（极速/平衡/节能）
 *   6. MODEL_DOWNLOAD  : 引导下载第一个模型（可选跳过）
 *   7. PERSONA_SETUP   : 让用户设置"我"是谁（可选跳过）
 *   8. DONE            : 完成页 → 跳转到主页
 *
 * 反屎山：
 *   - 步骤枚举集中定义，禁止散落字符串
 *   - 用户可"跳过"任一步骤，不强制完成
 *   - 步骤完成状态通过 [OnboardingState] 持久化
 *   - 重置应用时 [OnboardingState.completed] 回 false，重走流程
 */
public interface OnboardingFlow {
    public val state: kotlinx.coroutines.flow.Flow<OnboardingState>
    public suspend fun start()
    public suspend fun completeStep(step: OnboardingStep, data: Map<String, String>)
    public suspend fun skipStep(step: OnboardingStep)
    public suspend fun complete()
    public suspend fun reset()
}

public data class OnboardingState(
    public val currentStep: OnboardingStep = OnboardingStep.WELCOME,
    public val completedSteps: Set<OnboardingStep> = emptySet(),
    public val skippedSteps: Set<OnboardingStep> = emptySet(),
    public val collectedData: Map<String, String> = emptyMap(),
    public val completed: Boolean = false,
)

public enum class OnboardingStep {
    WELCOME,
    LANGUAGE,
    USE_CASE,
    NPU_DETECT,
    PERFORMANCE,
    MODEL_DOWNLOAD,
    PERSONA_SETUP,
    DONE,
    ;

    public val nextStep: OnboardingStep?
        get() = when (this) {
            WELCOME -> LANGUAGE
            LANGUAGE -> USE_CASE
            USE_CASE -> NPU_DETECT
            NPU_DETECT -> PERFORMANCE
            PERFORMANCE -> MODEL_DOWNLOAD
            MODEL_DOWNLOAD -> PERSONA_SETUP
            PERSONA_SETUP -> DONE
            DONE -> null
        }
}
