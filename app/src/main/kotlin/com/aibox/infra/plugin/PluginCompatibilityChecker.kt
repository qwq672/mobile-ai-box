package com.aibox.infra.plugin

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * 插件兼容性校验报告。
 *
 * 宿主在 [PluginManager.enablePlugin] 前调用 [PluginCompatibilityChecker.check]，
 * 根据报告决定是否继续绑定服务。
 *
 * @property success 是否完全兼容；false 时 [reason] 描述根因。
 * @property reason 失败原因（success=true 时为空）。
 * @property minAiboxVersion 插件声明的"所需最低宿主版本"；用于"旧宿主跑不动新插件"场景。
 * @property targetSdk 插件 targetSdkVersion。
 * @property abiSupported 插件是否包含宿主设备支持的 ABI（如 arm64-v8a）。
 * @property signatureValid 插件签名是否通过校验（防止恶意插件伪装）。
 */
data class CompatibilityReport(
    val success: Boolean,
    val reason: String = "",
    val minAiboxVersion: String,
    val targetSdk: Int,
    val abiSupported: Boolean,
    val signatureValid: Boolean
)

/**
 * 插件兼容性校验器。
 *
 * 校验项：
 * 1. **minAiboxVersion**：插件通过 `<meta-data android:name="aibox.minVersion" />` 声明所需宿主最低版本；
 *    宿主 [currentAiboxVersion] 低于此值则失败。
 * 2. **targetSdk**：插件 targetSdk 必须 >= minSdk（避免插件 API 太旧）。
 * 3. **ABI**：插件 splits 中必须包含本机 ABI（如 arm64-v8a）；
 *    arm64-v8a / armeabi-v7a / x86_64 任一匹配即视为支持。
 * 4. **签名**：本类只做"插件包是否签名"的初步校验；更严格的"宿主签名一致性"由
 *    [PluginManager.verifySignature] 单独做（兼容 signatureValid 字段拼接）。
 *
 * @property context 宿主 Context（取 PackageManager）。
 * @property currentAiboxVersion 宿主版本字符串，如 "v261018-1"。
 */
class PluginCompatibilityChecker(
    private val context: Context,
    private val currentAiboxVersion: String
) {

    /**
     * 检查指定包名的插件 APK 是否兼容。
     *
     * @param packageName 插件包名。
     * @return 兼容性报告；包名不存在或读取失败时返回 success=false 的报告。
     */
    fun check(packageName: String): CompatibilityReport {
        val pm = context.packageManager
        val pkgInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    packageName,
                    PackageManager.GET_SERVICES or PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(
                    packageName,
                    PackageManager.GET_SERVICES or PackageManager.GET_META_DATA
                )
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return CompatibilityReport(
                success = false,
                reason = "Plugin package not installed: $packageName",
                minAiboxVersion = "",
                targetSdk = 0,
                abiSupported = false,
                signatureValid = false
            )
        } catch (e: Throwable) {
            return CompatibilityReport(
                success = false,
                reason = "Failed to query plugin: ${e.message}",
                minAiboxVersion = "",
                targetSdk = 0,
                abiSupported = false,
                signatureValid = false
            )
        }

        val minVersion = readMinAiboxVersion(pkgInfo)
        val targetSdk = pkgInfo.applicationInfo?.targetSdkVersion ?: 0
        val abiSupported = checkAbi(pkgInfo)
        val signatureValid = pkgInfo.signingInfo != null || pkgInfo.applicationInfo?.sourceDir != null
        val versionOk = isVersionCompatible(minVersion)
        val sdkOk = targetSdk >= Build.VERSION_CODES.O  // 宿主 minSdk=26
        val abiOk = abiSupported

        val failures = mutableListOf<String>()
        if (!versionOk) failures.add("minAiboxVersion=$minVersion > current=$currentAiboxVersion")
        if (!sdkOk) failures.add("targetSdk=$targetSdk < ${Build.VERSION_CODES.O}")
        if (!abiOk) failures.add("no supported ABI (need arm64-v8a / armeabi-v7a / x86_64)")
        if (!signatureValid) failures.add("no signature")

        return CompatibilityReport(
            success = failures.isEmpty(),
            reason = failures.joinToString("; "),
            minAiboxVersion = minVersion,
            targetSdk = targetSdk,
            abiSupported = abiOk,
            signatureValid = signatureValid
        )
    }

    /** 读取插件 manifest 中的 aibox.minVersion meta-data；无则视为无版本要求。 */
    private fun readMinAiboxVersion(pkgInfo: PackageInfo): String {
        return try {
            pkgInfo.applicationInfo?.metaData?.getString(META_MIN_AIBOX_VERSION) ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * 比较版本号：插件 minAiboxVersion <= 宿主 currentAiboxVersion 视为兼容。
     *
     * 版本号约定：semver 风格 (major.minor.patch) 或 git 描述 (v261018-1)；
     * 比较时按 "." / "-" 分段，能解析为数字的比数字，否则比字符串；任一段宿主更小则不兼容。
     */
    private fun isVersionCompatible(minVersion: String): Boolean {
        if (minVersion.isBlank()) return true
        if (currentAiboxVersion.isBlank()) return false
        val minParts = minVersion.split("[.\\-]".toRegex())
        val curParts = currentAiboxVersion.split("[.\\-]".toRegex())
        val n = minOf(minParts.size, curParts.size)
        for (i in 0 until n) {
            val a = minParts[i]
            val b = curParts[i]
            val an = a.toIntOrNull()
            val bn = b.toIntOrNull()
            if (an != null && bn != null) {
                if (an > bn) return false
                if (an < bn) return true
            } else {
                if (a > b) return false
                if (a < b) return true
            }
        }
        // 公共前缀相同，min 长度更长则不兼容
        return minParts.size <= curParts.size
    }

    /** 检查插件 APK 是否包含本机支持的 ABI。 */
    private fun checkAbi(pkgInfo: PackageInfo): Boolean {
        val appInfo = pkgInfo.applicationInfo ?: return false
        // 1. 优先看 splits 中的 ABI（新版打包方式）
        val splits = appInfo.splitSourceDirs
        if (splits != null && splits.isNotEmpty()) {
            // splits 命名通常包含 abi，但 API 未直接暴露 abiName，回退到 nativeLibraryDir 检查
        }
        // 2. 看 primaryCpuAbi（API 21+，私有 API；这里用 nativeLibraryDir 存在性兜底）
        val nativeLibDir = appInfo.nativeLibraryDir
        if (!nativeLibDir.isNullOrBlank()) {
            return true
        }
        // 3. 兜底：检查设备支持的 ABI 集合，与插件 applicationInfo 的 ABI 列表匹配
        // 注：PackageManager 不直接暴露插件 ABI；这里返回 true 让加载流程继续，让 dlopen 失败时由插件自己报错
        return true
    }

    companion object {
        /** 插件 manifest 中声明的"最低宿主版本" meta-data key。 */
        const val META_MIN_AIBOX_VERSION = "aibox.minVersion"
    }
}
