package com.aibox.ui.roleplay

/**
 * 角色扮演入口的“待启动请求”桥。
 *
 * 从角色页跳到聊天页时，`NavController` 的 SavedStateHandle 在
 * `launchSingleTop + restoreState` 下会被复用，容易读到上一次的旧值；
 * 这里用一个一次性请求更直接：角色页放请求，聊天页取走并清空。
 */
object RoleplayLauncher {

    @Volatile
    private var pending: Pair<Long, Long>? = null

    /** characterId = 0 表示“明确要一个普通对话”（用于从主页/抽屉进入聊天时复位）。 */
    fun setPending(characterId: Long, personaId: Long) {
        pending = characterId to personaId
    }

    /** 取走并清空待启动请求；null 表示没有显式请求。 */
    fun consume(): Pair<Long, Long>? {
        val p = pending
        pending = null
        return p
    }
}
