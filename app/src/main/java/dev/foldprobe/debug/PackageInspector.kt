package dev.foldprobe.debug

import android.content.Context
import android.content.pm.PackageManager

class PackageInspector(private val context: Context, private val log: EventLog) {
    companion object {
        val packages = listOf("com.samsung.android.goodlock", "com.samsung.android.app.homestar", "com.samsung.android.multistar",
            "com.samsung.android.sidegesturepad", "com.samsung.android.app.launcher", "com.sec.android.app.launcher", "com.android.systemui",
            "com.samsung.systemui.lockstar", "com.samsung.android.wonderland.wallpaper")
    }
    fun inspect(): String {
        val pm = context.packageManager
        val text = StringBuilder("Read-only metadata. Exported does not guarantee callable; additional runtime checks may exist.\n")
        fun permission(name: String) {
            try {
                val p = pm.getPermissionInfo(name, 0)
                log.record("packages", "permission", mapOf("name" to name, "owner" to p.packageName,
                    "protectionLevel" to p.protectionLevel, "protectionBase" to (p.protectionLevel and 15),
                    "grantedToProbe" to (pm.checkPermission(name, context.packageName) == PackageManager.PERMISSION_GRANTED)))
            } catch (e: Exception) { log.failure("packages", "permission:$name", e) }
        }
        permission("com.samsung.permission.SSENSOR")
        text.append("SSENSOR grant: ${pm.checkPermission("com.samsung.permission.SSENSOR", context.packageName) == PackageManager.PERMISSION_GRANTED}\n")
        packages.forEach { pkg ->
            try {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                    PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA)
                val platformSigned = pm.checkSignatures("android", pkg) == PackageManager.SIGNATURE_MATCH
                text.append("\n$pkg\n${info.versionName} (${info.longVersionCode}); platform signature match=$platformSigned\n")
                log.record("packages", "installed", mapOf("package" to pkg, "version" to info.versionName,
                    "versionCode" to info.longVersionCode, "applicationFlags" to info.applicationInfo?.flags,
                    "platformSignatureMatch" to platformSigned, "metadata" to info.applicationInfo?.metaData?.toString(),
                    "requestedPermissions" to info.requestedPermissions?.toList(), "requestedPermissionFlags" to info.requestedPermissionsFlags?.toList()))
                info.permissions?.forEach { permission(it.name) }
                fun component(kind: String, name: String, exported: Boolean, enabled: Boolean, required: String?) {
                    log.record("packages", "component", mapOf("package" to pkg, "kind" to kind, "name" to name,
                        "exported" to exported, "enabled" to enabled, "permission" to required,
                        "permissionGrantedToProbe" to (required == null || pm.checkPermission(required, context.packageName) == PackageManager.PERMISSION_GRANTED),
                        "callability" to "Not invoked; metadata alone does not establish API contract"))
                    if (required != null) permission(required)
                    if (exported) text.append("  $kind: $name\n  permission=${required ?: "none declared"}\n")
                }
                info.activities?.forEach { component("activity", it.name, it.exported, it.enabled, it.permission) }
                info.receivers?.forEach { component("receiver", it.name, it.exported, it.enabled, it.permission) }
                info.services?.forEach { component("service", it.name, it.exported, it.enabled, it.permission) }
                info.providers?.forEach {
                    component("provider", it.name, it.exported, it.enabled, it.readPermission)
                    log.record("packages", "providerPermissions", mapOf("name" to it.name, "writePermission" to it.writePermission, "authority" to it.authority))
                }
            } catch (e: Exception) { text.append("\n$pkg: $e\n"); log.failure("packages", "inspect:$pkg", e) }
        }
        return text.toString()
    }
}
