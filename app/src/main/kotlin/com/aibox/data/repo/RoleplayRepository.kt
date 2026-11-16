package com.aibox.data.repo

import android.content.Context
import com.aibox.data.db.AppDatabase
import com.aibox.data.db.CharacterEntity
import com.aibox.data.db.PersonaEntity
import kotlinx.coroutines.flow.Flow

/**
 * 角色扮演仓库：AI 角色 + 用户身份。
 *
 * 与会话表解耦：会话只记 `characterId` / `personaId`，角色或身份被删后
 * 旧会话仍能打开（只是失去设定），不会把消息一起删掉。
 *
 * [ensureDefaults] 在角色页首次打开时调用，只在两张表都为空时写入
 * 一条默认身份 + 三个示例角色，让功能开箱可用。
 */
class RoleplayRepository(context: Context) {

    private val db = AppDatabase.get(context)
    private val characterDao = db.characterDao()
    private val personaDao = db.personaDao()

    // ---- Characters ----
    val characters: Flow<List<CharacterEntity>> = characterDao.observeAll()

    suspend fun getCharacter(id: Long): CharacterEntity? = characterDao.getById(id)

    suspend fun upsertCharacter(c: CharacterEntity): Long = characterDao.upsert(c)

    suspend fun deleteCharacter(c: CharacterEntity) = characterDao.delete(c)

    /** 记录最近使用，让列表把常用角色排前面。 */
    suspend fun touchCharacter(id: Long) {
        val c = characterDao.getById(id) ?: return
        characterDao.upsert(c.copy(lastUsedAt = System.currentTimeMillis()))
    }

    // ---- Personas ----
    val personas: Flow<List<PersonaEntity>> = personaDao.observeAll()

    suspend fun getPersona(id: Long): PersonaEntity? = personaDao.getById(id)

    suspend fun upsertPersona(p: PersonaEntity): Long = personaDao.upsert(p)

    suspend fun deletePersona(p: PersonaEntity) = personaDao.delete(p)

    suspend fun touchPersona(id: Long) {
        val p = personaDao.getById(id) ?: return
        personaDao.upsert(p.copy(lastUsedAt = System.currentTimeMillis()))
    }

    // ---- 初始化 ----

    /**
     * 首次进入角色页时补默认数据；已有数据则不动。
     * 默认身份名为“我”，用户可改名或补充描述。
     */
    suspend fun ensureDefaults() {
        if (personaDao.count() == 0) {
            personaDao.upsert(
                PersonaEntity(
                    name = "我",
                    description = "普通用户，喜欢直来直去的交流"
                )
            )
        }
        if (characterDao.count() > 0) return

        val now = System.currentTimeMillis()
        listOf(
            CharacterEntity(
                name = "严谨助教",
                emoji = "🎓",
                description = "逐条讲清概念，拒绝含糊",
                systemPrompt = "你是一位严谨但耐心的助教。先确认问题，再分点解释，必要时给出例子；" +
                    "不确定的地方要明确说不确定，不要编造。语气克制、清楚。",
                greeting = "有什么没弄懂的地方？我们一条一条捋。",
                createdAt = now
            ),
            CharacterEntity(
                name = "温柔树洞",
                emoji = "🌙",
                description = "先听你说，再慢慢给建议",
                systemPrompt = "你是一个温柔的倾听者。先接住对方的情绪，再轻声给出建议；" +
                    "不急着解决问题，不说教，不评判。句子短一些，像在夜里聊天。",
                greeting = "今天过得怎么样？想说什么都可以。",
                createdAt = now + 1
            ),
            CharacterEntity(
                name = "赛博侦探",
                emoji = "🕵️",
                description = "从细节里找线索",
                systemPrompt = "你是一位冷静的赛博侦探。用推理的口吻分析问题：先列线索，再给推断，" +
                    "最后给结论；可以适度反问，但不要故弄玄虚。",
                greeting = "案子来了？把你知道的细节都告诉我。",
                createdAt = now + 2
            )
        ).forEach { characterDao.upsert(it) }
    }
}
