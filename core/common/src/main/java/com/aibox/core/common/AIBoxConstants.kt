package com.aibox.core.common

/**
 * AIBox 全局常量与约定。
 *
 * 防止散落的魔法字符串：所有"全局共享"的常量必须放在这里，
 * 禁止在多个模块各写一遍。否则升级期会出现命名漂移导致的屎山问题。
 */
public object AIBoxConstants {
    /** 宿主 APK 包名，用于插件校验调用方身份 */
    public const val HOST_PACKAGE: String = "com.aibox"

    /** 宿主签名 SHA-256 占位：正式版需由 CI 注入实际签名摘要 */
    public const val HOST_SIGNATURE_SHA256: String = "PLACEHOLDER_HOST_SIG"

    /** 插件 Intent Action：声明此 action 的 APK 视为推理后端插件 */
    public const val ACTION_INFERENCE_BACKEND: String = "com.aibox.plugin.INFERENCE_BACKEND"

    /** 插件 Intent Action：声明此 action 的 APK 视为能力插件（生图/音频等） */
    public const val ACTION_CAPABILITY: String = "com.aibox.plugin.CAPABILITY"

    /** 插件 Intent Action：声明此 action 的 APK 视为 NPU 委托插件 */
    public const val ACTION_NPU_DELEGATE: String = "com.aibox.plugin.NPU_DELEGATE"

    /** 插件注册到宿主用的广播 Action */
    public const val ACTION_REGISTER: String = "com.aibox.host.REGISTER"

    /** 自定义权限：插件注册必须持有此权限，防止第三方伪造注册 */
    public const val PERMISSION_REGISTER_PLUGIN: String = "com.aibox.permission.REGISTER_PLUGIN"

    /** AIDL 包名（共享接口） */
    public const val AIDL_PACKAGE: String = "com.aibox.plugin.aidl"

    /** 外部日志目录名（相对于 getExternalFilesDir） */
    public const val LOG_DIR_NAME: String = "aibox/logs"

    /** Crash 日志子目录 */
    public const val CRASH_DIR_NAME: String = "aibox/crashes"

    /** 模型下载目录 */
    public const val MODELS_DIR_NAME: String = "aibox/models"

    /** 默认下载分块大小：4MB */
    public const val DOWNLOAD_CHUNK_BYTES: Long = 4L * 1024 * 1024
}
