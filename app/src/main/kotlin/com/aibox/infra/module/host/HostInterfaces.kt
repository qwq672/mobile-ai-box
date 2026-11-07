package com.aibox.infra.module.host

/**
 * 宿主核心接口:为模块(agent/roleplay/chat 等)提供宿主级能力。
 *
 * **设计原则**:
 * - 接口在宿主定义,模块通过 AIDL 或 IPC 拿到实现
 * - 接口尽量稳定,以增加方法不破坏既有实现为原则
 * - 不直接暴露 native 句柄等不可序列化对象
 *
 * 当前为接口预定义阶段,实现将在 ModuleLoader 完整集成后接入。
 */

// ============================================================
// 1. SessionManager:会话生命周期(含临时会话)
// ============================================================

/**
 * 会话管理器:支持普通会话和临时会话。
 *
 * 临时会话特性:
 * - `isEphemeral=true` 创建,不写入持久历史
 * - `promote()` 转正:生成正式 ID,持久化,从临时区移出
 * - 关闭 APP 即销毁(隐私更强,与 GPT 不同)
 *
 * 用途:
 * - 用户主动发起的"无痕聊天"
 * - agent 模块的中间态(ReAct 中间步骤不污染历史)
 * - roleplay 模块的"试玩"模式
 */
interface ISessionManager {
    /** 创建会话。返回 sessionId。 */
    fun createSession(ephemeral: Boolean = false): String

    /** 临时会话转正。返回新的正式 sessionId(原 ID 失效)。 */
    fun promoteEphemeral(sessionId: String, title: String?): String

    /** 删除会话(含消息)。 */
    fun deleteSession(sessionId: String): Boolean

    /** 列出会话。includeEphemeral=false 时只返回持久化的。 */
    fun listSessions(includeEphemeral: Boolean = false): List<SessionInfo>

    /** 锁定某条消息,锁定内容永不压缩、永不滑出窗口。 */
    fun lockMessage(sessionId: String, messageId: String): Boolean

    /** 解锁消息。 */
    fun unlockMessage(sessionId: String, messageId: String): Boolean

    /** 查询某会话的锁定消息列表。 */
    fun getLockedMessages(sessionId: String): List<String>
}

data class SessionInfo(
    val id: String,
    val title: String,
    val isEphemeral: Boolean,
    val messageCount: Int,
    val createdAt: Long,
    val updatedAt: Long
)

// ============================================================
// 2. ContextBudget:上下文 token 预算管理
// ============================================================

/**
 * 上下文预算协调器:跨模块的 token 预算查询和压缩触发。
 *
 * 各模块(chat/agent/roleplay)在拼装 prompt 前查询"还有多少 token 预算",
 * 由本接口协调锁定消息 + 实体记忆 + 滑动窗口 + 选择性总结的拼接顺序。
 *
 * 设计对应"混合上下文压缩策略":
 * 1. 永久锁定消息(最高优先级,占满先扣)
 * 2. 实体记忆 RAG top-k(次优先,毫秒级)
 * 3. 最近 K 轮原文 + KV cache(默认)
 * 4. [溢出兜底] 选择性总结(触发后存向量库,不进 context)
 */
interface IContextBudget {
    /** 查询当前剩余 token 预算。 */
    fun availableTokens(): Int

    /** 查询已用 token 预算。 */
    fun consumedTokens(): Int

    /**
     * 请求压缩到 targetTokens。
     *
     * 模块自己决定怎么压(滑动窗口 / RAG 检索 / 选择性总结),
     * 本接口只负责触发和协调。
     */
    fun requestCompression(targetTokens: Int): CompressionResult
}

data class CompressionResult(
    val originalTokens: Int,
    val compressedTokens: Int,
    val strategy: CompressionStrategy,
    val droppedMessageIds: List<String>,
    val summaryStoredToMemory: Boolean
)

enum class CompressionStrategy {
    SLIDING_WINDOW,         // 切 token(免费)
    RAG_RETRIEVAL,          // RAG 检索替代历史
    SELECTIVE_SUMMARY,     // 选择性总结(成本高)
    ROPE_SHIFT             // 位置压缩(免费,仅 Qwen 等)
}

// ============================================================
// 3. MemoryStore:实体记忆 + 向量库
// ============================================================

/**
 * 实体记忆存储:agent / roleplay 模块的核心能力之一。
 *
 * 替代"模型自己总结",以毫秒级向量检索保留具体词。
 * 实现:SQLite + sqlite-vss 或 hnswlib-java,~3MB。
 *
 * 各模块用例:
 * - chat:存"我叫张三" → 后续检索 → "你是张三"
 * - agent:ReAct 中间步骤的事实抽取
 * - roleplay:角色卡的"长期记忆"(NPC 与用户的关系演进)
 */
interface IMemoryStore {
    /** 添加记忆。 */
    fun add(memory: Memory)

    /** 按 query 向量检索 top-k 相关记忆。 */
    fun search(query: String, topK: Int = 5): List<Memory>

    /** 删除满足条件的记忆。返回删除条数。 */
    fun delete(predicate: (Memory) -> Boolean): Int

    /** 锁定消息(与 SessionManager.lockMessage 协同)。 */
    fun lock(messageId: String)

    /** 解锁消息。 */
    fun unlock(messageId: String)

    /** 查询所有锁定记忆(永久注入 context)。 */
    fun listLocked(): List<Memory>
}

data class Memory(
    val id: String,
    val content: String,
    val embedding: ByteArray,
    val sourceMessageId: String?,
    val sessionId: String?,
    val createdAt: Long,
    val metadata: Map<String, String> = emptyMap()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Memory) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}

// ============================================================
// 4. HostApi:版本与能力查询
// ============================================================

interface IHostApi {
    fun currentHostApiVersion(): Int
    fun hostVersionName(): String
    fun deviceAbi(): List<String>
    fun deviceModelName(): String
    fun availableMemoryBytes(): Long
    fun totalMemoryBytes(): Long
}
