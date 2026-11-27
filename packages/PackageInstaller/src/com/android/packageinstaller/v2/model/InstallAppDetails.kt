package com.android.packageinstaller.v2.model

import android.content.pm.PackageInfo
import java.io.File

data class InstallAppDetails(
    val packageName: String,
    val sizeBytes: Long? = null,
    val version: String? = null,
    val targetSdk: Int? = null,
    val installedVersion: String? = null,
) {
    companion object {
        fun fromPackageInfo(
            info: PackageInfo,
            baseApk: File? = null,
            extraSizeBytes: Long = 0L,
        ): InstallAppDetails {
            val app = info.applicationInfo
            val paths = buildList<String> {
                (app?.sourceDir ?: baseApk?.path ?: app?.publicSourceDir)?.let(::add)
                app?.splitSourceDirs?.let(::addAll)
            }.distinct()
            val apkSizeBytes = try {
                paths.map { File(it).takeIf(File::isFile)?.length() ?: 0L }
                    .takeIf { it.isNotEmpty() && it.all { size -> size > 0 } }?.sum()
            } catch (_: SecurityException) {
                null
            }
            val sizeBytes = apkSizeBytes?.let { it + extraSizeBytes.coerceAtLeast(0L) }
            return InstallAppDetails(
                packageName = info.packageName,
                sizeBytes = sizeBytes,
                version = info.displayVersion(),
                targetSdk = app?.targetSdkVersion?.takeIf { it > 0 },
            )
        }
    }
}

internal fun PackageInfo.displayVersion(): String? =
    versionName?.takeIf { it.isNotBlank() }
        ?: longVersionCode.takeIf { it > 0 }?.toString()
