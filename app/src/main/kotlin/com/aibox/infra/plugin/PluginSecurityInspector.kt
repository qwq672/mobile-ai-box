package com.aibox.infra.plugin

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import com.aibox.util.log.LogSystem

/**
 * 插件 APK 安全体检。
 *
 * 只做“启用前的最低门槛”，不替代系统沙箱：
 * - 必须有签名
 * - release 宿主默认拒绝 debuggable 插件
 * - targetSdk 不能过低
 * - 必须至少暴露一个 exported Service
 *
 * 危险权限只做提示，不阻断；插件是独立 App，系统权限由用户授予。
 */
class PluginSecurityInspector(private val context: Context) {

    private val tag = "aibox.plugin.security"

    data class Report(
        val packageName: String,
        val signatureValid: Boolean,
        val debuggable: Boolean,
        val targetSdk: Int,
        val minSdk: Int,
        val exportedServices: List<String>,
        val requestedPermissions: List<String>,
        val dangerousPermissions: List<String>,
        val nativeAbi: String?,
        val blockers: List<String>,
        val warnings: List<String>
    ) {
        val ok: Boolean get() = blockers.isEmpty()
    }

    fun inspect(packageName: String): Report {
        val pm = context.packageManager
        val pkgInfo = try {
            getPackageInfo(pm, packageName)
        } catch (e: Throwable) {
            return Report(
                packageName = packageName,
                signatureValid = false,
                debuggable = false,
                targetSdk = 0,
                minSdk = 0,
                exportedServices = emptyList(),
                requestedPermissions = emptyList(),
                dangerousPermissions = emptyList(),
                nativeAbi = null,
                blockers = listOf("无法读取包信息：${e.message}"),
                warnings = emptyList()
            )
        }

        val app = pkgInfo.applicationInfo
        val signatureValid = hasSignature(pkgInfo)
        val debuggable = (app?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val targetSdk = app?.targetSdkVersion ?: 0
        val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            app?.minSdkVersion ?: 0
        } else {
            0
        }
        val exportedServices = pkgInfo.services.orEmpty()
            .filter { it.exported }
            .mapNotNull { it.name }
        val requested = pkgInfo.requestedPermissions.orEmpty().toList()
        val dangerous = requested.filter { isDangerousPermission(pm, it) }
        val nativeAbi = detectAbi(app)

        val blockers = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (!signatureValid) blockers += "插件未签名"
        if (debuggable && !hostDebuggable()) blockers += "release 宿主拒绝 debuggable 插件"
        if (targetSdk < Build.VERSION_CODES.O) blockers += "targetSdk < 26"
        if (exportedServices.isEmpty()) blockers += "没有 exported Service"

        if (dangerous.isNotEmpty()) {
            warnings += "申请危险权限：${dangerous.joinToString(", ")}"
        }
        if (targetSdk < 34) warnings += "targetSdk=$targetSdk，建议 34+"
        if (exportedServices.size > 1) {
            warnings += "暴露 ${exportedServices.size} 个 Service，建议只暴露入口"
        }
        if (nativeAbi.isNullOrBlank()) {
            warnings += "未检测到 primaryCpuAbi（纯 Java 插件可忽略）"
        }

        blockers.forEach { LogSystem.warn(tag, "$packageName: $it") }
        warnings.forEach { LogSystem.info(tag, "$packageName: $it") }

        return Report(
            packageName = packageName,
            signatureValid = signatureValid,
            debuggable = debuggable,
            targetSdk = targetSdk,
            minSdk = minSdk,
            exportedServices = exportedServices,
            requestedPermissions = requested,
            dangerousPermissions = dangerous,
            nativeAbi = nativeAbi,
            blockers = blockers,
            warnings = warnings
        )
    }

    private fun getPackageInfo(pm: PackageManager, packageName: String): PackageInfo {
        val flags = PackageManager.GET_PERMISSIONS or
            PackageManager.GET_SERVICES or
            PackageManager.GET_SIGNING_CERTIFICATES
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, flags)
        }
    }

    private fun hasSignature(pkgInfo: PackageInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.signingInfo != null
        } else {
            @Suppress("DEPRECATION")
            !pkgInfo.signatures.isNullOrEmpty()
        }
    }

    private fun isDangerousPermission(pm: PackageManager, permission: String): Boolean {
        return try {
            val info = pm.getPermissionInfo(permission, 0)
            (info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) ==
                PermissionInfo.PROTECTION_DANGEROUS
        } catch (_: Throwable) {
            false
        }
    }

    private fun hostDebuggable(): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * 从 APK 的 `lib/<abi>/` 推断插件 ABI。
     * 不依赖 hidden API `ApplicationInfo.primaryCpuAbi`。
     */
    private fun detectAbi(app: ApplicationInfo?): String? {
        val apk = app?.sourceDir ?: return null
        return try {
            java.util.zip.ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .mapNotNull { e ->
                        val parts = e.name.split('/')
                        if (parts.size >= 3 && parts[0] == "lib") parts[1] else null
                    }
                    .firstOrNull { it in Build.SUPPORTED_ABIS }
            }
        } catch (_: Throwable) {
            null
        }
    }
}
