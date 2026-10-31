package com.aibox.feature.persona

import kotlinx.serialization.Serializable

/**
 * 角色卡片 — 完整定义（v2 扩展，含"我"是谁）。
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 角色扮演三要素（用户要求扩展）：
 *   1. AI 是谁 → [Persona]（已有）
 *   2. 我是谁 → [UserPersona]（新增，用户自身角色）
 *   3. 关系是什么 → [RelationStyle]（新增，影响对话语气）
 *
 * 三者组合后，组成 [PersonaComposition] 注入到 LLM 的 SystemPrompt
 *
 * 示例（"面试官 → 候选人"场景）：
 *   aiPersona = "资深面试官"
 *   userPersona = "前端工程师候选人"
 *   relation = "面试中"
 *   SystemPrompt 模板：
 *     "你是{aiPersona.name}，正在面试{userPersona.name}。
 *      当前关系：{relation.description}
 *      你的风格：{aiPersona.traits}
 *      用户希望被称呼为：{userPersona.preferredAddress}"
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 */
@Serializable
public data class Persona(
    public val id: String,
    public val name: String,
    public val avatar: String? = null,
    public val description: String = "",
    public val systemPrompt: String,
    public val greeting: String? = null,
    public val exampleDialogues: List<DialogueExample> = emptyList(),
    public val traits: Map<String, String> = emptyMap(),
    public val memoryStrategy: MemoryStrategy = MemoryStrategy.SLIDING_WINDOW,
    public val maxHistoryTurns: Int = 20,
)

/**
 * 用户自身角色 — "我"是谁。
 *
 * 这是 AIBox Persona 系统区别于其他工具的关键扩展。
 *
 * 影响 LLM 行为：
 * - LLM 知道用户是"前端工程师"，回复会用更专业的术语
 * - LLM 知道用户称呼习惯，自动用"老板"、"X工"等称呼
 * - LLM 知道用户偏好风格，避免冗长解释
 */
@Serializable
public data class UserPersona(
    public val id: String = "default",
    public val name: String = "我",
    public val preferredAddress: String = "你",  // LLM 称呼用户的词，如"你/您/X工"
    public val role: String = "用户",
    public val profession: String? = null,        // 职业（如"前端工程师"）
    public val expertise: String? = null,        // 专长（如"React, TypeScript"）
    public val preferredStyle: PreferredStyle = PreferredStyle.BALANCED,
    public val taboos: List<String> = emptyList(), // LLM 应避免的（如"避免提到年龄"）
)

/** 用户偏好的回复风格 */
@Serializable
public enum class PreferredStyle {
    /** 简洁直接，不要废话 */
    CONCISE,
    /** 平衡：要点 + 简短解释 */
    BALANCED,
    /** 详细：尽量给完整推理和例子 */
    DETAILED,
}

/**
 * AI 与用户的关系 — 影响对话语气。
 *
 * 用作 [PersonaComposition] 的一部分，让 LLM 知道"我们正在做什么"。
 */
@Serializable
public data class RelationStyle(
    public val name: String,                     // 关系名，如"面试中"
    public val description: String,             // 简述，如"AI 是面试官，用户是候选人"
    public val aiTone: String = "professional",  // AI 语气
    public val userTone: String = "respectful",  // 用户预期语气
    public val sceneTemplate: String? = null,    // 对话场景模板
)

/**
 * 角色组合 — 一次完整对话的"角色设定包"。
 *
 * 由 [Persona] + [UserPersona] + [RelationStyle] 组成。
 * 注入到 LLM 时由 [PersonaComposition.composeSystemPrompt] 拼接成完整 system prompt。
 */
@Serializable
public data class PersonaComposition(
    public val aiPersona: Persona,
    public val userPersona: UserPersona = UserPersona(),
    public val relation: RelationStyle? = null,
) {

    /**
     * 拼接出注入 LLM 的 SystemPrompt。
     *
     * 反屎山：模板集中在本函数，禁止 UI 或 ViewModel 自己拼字符串。
     */
    public fun composeSystemPrompt(): String = buildString {
        // 1. AI 是谁
        append("你是 ").append(aiPersona.name).append("。")
        append(aiPersona.systemPrompt).append('\n')

        // 2. AI 的特性
        if (aiPersona.traits.isNotEmpty()) {
            append("你的特性：")
            aiPersona.traits.forEach { (k, v) -> append("$k=$v; ") }
            append('\n')
        }

        // 3. 用户是谁
        if (userPersona.id != "default" || userPersona.profession != null) {
            append("用户身份：").append(userPersona.role)
            userPersona.profession?.let { append("，职业：$it") }
            userPersona.expertise?.let { append("，专长：$it") }
            append("。称呼用户为「").append(userPersona.preferredAddress).append("」。\n")
        }

        // 4. 用户偏好
        append("回复风格：").append(when (userPersona.preferredStyle) {
            PreferredStyle.CONCISE -> "简洁直接，不啰嗦"
            PreferredStyle.BALANCED -> "要点 + 简短解释"
            PreferredStyle.DETAILED -> "尽量详细，含完整推理和例子"
        }).append('\n')

        // 5. 用户禁忌
        if (userPersona.taboos.isNotEmpty()) {
            append("避免：").append(userPersona.taboos.joinToString("、")).append('\n')
        }

        // 6. 关系
        relation?.let {
            append("当前关系：").append(it.name).append("，").append(it.description).append('\n')
            append("AI 语气：").append(it.aiTone).append("；用户语气：").append(it.userTone).append('\n')
        }

        // 7. 示例对话
        if (aiPersona.exampleDialogues.isNotEmpty()) {
            append("示例对话：\n")
            aiPersona.exampleDialogues.take(3).forEach { ex ->
                append("用户：").append(ex.user).append('\n')
                append("你：").append(ex.assistant).append('\n')
            }
        }
    }
}

@Serializable
public data class DialogueExample(
    public val user: String,
    public val assistant: String,
)

public enum class MemoryStrategy {
    /** 滑动窗口：只保留最近 N 轮 */
    SLIDING_WINDOW,

    /** 摘要压缩：每 N 轮压缩成摘要再保留最新 N 轮 */
    SUMMARY_COMPRESSION,

    /** 全量上下文（仅适合超长 context 模型，不推荐） */
    FULL_CONTEXT,

    /** KV 量化压缩（保留全部上下文，但 KV 量化） */
    KV_QUANTIZATION,
}
