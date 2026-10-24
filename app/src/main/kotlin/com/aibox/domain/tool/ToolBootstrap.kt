package com.aibox.domain.tool

import android.content.Context
import com.aibox.data.repo.SettingsRepository
import com.aibox.domain.tool.builtin.CalculatorTool
import com.aibox.domain.tool.builtin.DateTimeTool
import com.aibox.domain.tool.builtin.DeviceInfoAdvancedTool
import com.aibox.domain.tool.builtin.DeviceInfoBasicTool
import com.aibox.domain.tool.builtin.EmailTool
import com.aibox.domain.tool.builtin.FileReadTool
import com.aibox.domain.tool.builtin.FileWriteTool
import com.aibox.domain.tool.builtin.PhoneControlTool
import com.aibox.domain.tool.builtin.UrlReaderTool
import com.aibox.domain.tool.builtin.WebSearchTool
import com.aibox.util.log.LogSystem

/**
 * 工具注册引导（B1 工具调用抽象层）。
 *
 * App 启动时由 [com.aibox.AIBoxApp.onCreate] 调一次 [registerBuiltinTools]，
 * 把所有内置工具一次性注册到 [ToolRegistry]。
 *
 * 当前状态：B2-B5 基础工具 + C1-C5 高级工具均已落地并注册。
 * - **B2**: [DateTimeTool] / [CalculatorTool]（基础，自动执行）
 * - **B3**: [WebSearchTool]（基础，自动执行；依赖 [SettingsRepository] 读 Tavily/Exa API key）
 * - **B4**: [UrlReaderTool]（基础，自动执行）
 * - **B5**: [DeviceInfoBasicTool]（基础，自动执行；依赖 [Context] 读 ActivityManager / BatteryManager）
 * - **C1**: [FileReadTool] / [FileWriteTool]（高级，需确认）
 * - **C2**: [EmailTool]（高级，需确认；通过 Intent 启动系统邮件 App）
 * - **C3**: [DeviceInfoAdvancedTool]（高级，需确认；依赖 [ToolSettingsRepository] 检查基础设备信息开关）
 * - **C4**: [PhoneControlTool]（高级，需确认；通过 Intent 启动系统拨号 / 短信 / 浏览器）
 *
 * 注：[ToolSettingsRepository.defaultEnabledTools] 中默认开启的 5 个工具 id 必须与
 * 这里 register 的工具 [Tool.id] 严格一致，否则开关不生效。
 *
 * @param registry 工具注册中心
 * @param settingsRepository 用于 [WebSearchTool] 读 Tavily / Exa API key（DuckDuckGo 默认引擎不需要）
 * @param appContext 用于 [DeviceInfoBasicTool] / [EmailTool] / [DeviceInfoAdvancedTool] / [PhoneControlTool]
 *   读系统服务（ActivityManager / BatteryManager / SensorManager / PackageManager / ClipboardManager）
 *   及启动 Activity Intent
 * @param toolSettingsRepository 用于 [DeviceInfoAdvancedTool] 检查基础设备信息工具是否启用
 */
class ToolBootstrap(
    private val registry: ToolRegistry,
    private val settingsRepository: SettingsRepository,
    private val appContext: Context,
    private val toolSettingsRepository: ToolSettingsRepository
) {
    /**
     * 注册所有内置工具。
     *
     * 失败不阻塞：单个工具注册抛异常时记录 error，继续注册下一个（与 [com.aibox.AIBoxApp]
     * "每个 Phase 失败不阻塞"约定一致）。最后打 info 日志统计成功数。
     */
    fun registerBuiltinTools() {
        var okCount = 0
        var failCount = 0

        // ===================================================
        // 基础工具（自动执行，默认开启）
        // B2-B5 已实现并注册
        // ===================================================
        okCount += safeRegister("DateTimeTool") { registry.register(DateTimeTool()) }
        okCount += safeRegister("CalculatorTool") { registry.register(CalculatorTool()) }
        okCount += safeRegister("WebSearchTool") {
            registry.register(WebSearchTool(settingsRepository))
        }
        okCount += safeRegister("UrlReaderTool") { registry.register(UrlReaderTool()) }
        okCount += safeRegister("DeviceInfoBasicTool") {
            registry.register(DeviceInfoBasicTool(appContext))
        }
        // JS 运行（基础）：B 系列后续 task 落地后取消注释
        // okCount += safeRegister("JsRunTool") { registry.register(JsRunTool()) }

        // ===================================================
        // 高级工具（需用户确认，默认关闭）
        // C1-C4 已实现并注册；用户需在工具设置面板手动启用
        // ===================================================
        okCount += safeRegister("FileReadTool") { registry.register(FileReadTool()) }
        okCount += safeRegister("FileWriteTool") { registry.register(FileWriteTool()) }
        okCount += safeRegister("EmailTool") { registry.register(EmailTool(appContext)) }
        okCount += safeRegister("DeviceInfoAdvancedTool") {
            registry.register(DeviceInfoAdvancedTool(appContext, toolSettingsRepository))
        }
        okCount += safeRegister("PhoneControlTool") {
            registry.register(PhoneControlTool(appContext))
        }

        failCount = EXPECTED_BUILTIN_TOOLS - okCount
        if (failCount == 0) {
            LogSystem.info(TAG, "ToolBootstrap: $okCount builtin tools registered")
        } else {
            LogSystem.warn(TAG, "ToolBootstrap: $okCount registered, $failCount failed (expected $EXPECTED_BUILTIN_TOOLS)")
        }
    }

    /**
     * 安全注册：单个工具注册失败不阻塞后续。
     *
     * @return 1 表示成功，0 表示失败（用于统计）
     */
    private inline fun safeRegister(name: String, block: () -> Unit): Int {
        return try {
            block()
            1
        } catch (e: Throwable) {
            LogSystem.error(TAG, "Failed to register $name: ${e.message}", e)
            0
        }
    }

    companion object {
        private const val TAG = "aibox.tool.bootstrap"

        /** 当前已实现工具数：5 个基础 + 5 个高级 = 10。 */
        private const val EXPECTED_BUILTIN_TOOLS = 10
    }
}

