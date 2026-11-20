package com.aibox.domain.tool

/**
 * UI 交互回调桥：把「需要前台弹面板」的工具回调从 UI 层注入到 domain 层工具循环。
 *
 * 背景：user_choice / 高级工具确认这类工具，执行时必须弹 UI 面板等用户响应。
 * - 本地路径：ToolInvocationViewModel.runInvocation 直接构造带 callback 的 ToolContext（不需要本桥）。
 * - **云端路径**：CloudChatService 在 domain 层跑工具循环，拿不到 ViewModel，
 *   此前构造 ToolContext 未传 choice/confirm callback，导致云端调用 user_choice 时
 *   必然返回「当前没有可用的选择界面」。
 *
 * 解法：ChatViewModel 在 init 时把 UI 回调注册到本桥；CloudChatService 构造 ToolContext
 * 时从本桥取。回调为空时退化为默认失败（后台无界面场景），不会挂起。
 */
object UiInteractionBridge {

    /** 由前台 ChatViewModel 注册；返回 null 表示用户取消 / 无可用界面。 */
    @Volatile
    var choiceHandler: (suspend (UserChoiceRequest) -> UserChoiceAnswer?)? = null

    /** 由前台 ChatViewModel 注册；高级工具确认。 */
    @Volatile
    var confirmHandler: (suspend (String, Map<String, Any>) -> Boolean)? = null

    fun clear() {
        choiceHandler = null
        confirmHandler = null
    }
}
