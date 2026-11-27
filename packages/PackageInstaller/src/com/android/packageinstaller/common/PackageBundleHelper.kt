/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.packageinstaller.common

import android.content.Context
import android.content.pm.PackageInstaller
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileUtils
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.abs

object PackageBundleHelper {

    private const val TAG = "PackageBundleHelper"
    private const val BUFFER_SIZE = 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 2048
    private const val MAX_MANIFEST_BYTES = 1024 * 1024
    private const val ROOT_ANDROID_MANIFEST = "AndroidManifest.xml"
    private const val XAPK_MANIFEST_JSON = "manifest.json"
    private const val APK_EXTENSION = ".apk"
    private const val OBB_EXTENSION = ".obb"

    const val EXTRA_BUNDLE_URI = "com.android.packageinstaller.extra.BUNDLE_URI"

    private val BUNDLE_MIME_TYPES = setOf(
        "application/vnd.android.xapk",
        "application/vnd.android.apkm",
        "application/vnd.android.apks",
        "application/x-xapk",
        "application/vnd.apkm",
        "application/vnd.apks",
        "application/x-apks",
    )

    private val ABI_ALIASES = mapOf(
        "arm64_v8a" to "arm64-v8a",
        "arm64-v8a" to "arm64-v8a",
        "armeabi_v7a" to "armeabi-v7a",
        "armeabi-v7a" to "armeabi-v7a",
        "armeabi" to "armeabi",
        "x86_64" to "x86_64",
        "x86-64" to "x86_64",
        "x86" to "x86",
        "mips64" to "mips64",
        "mips" to "mips",
        "riscv64" to "riscv64",
    )

    private val ABI_SUFFIXES = ABI_ALIASES.keys.sortedByDescending { it.length }

    private val DENSITY_DPI_MAP = mapOf(
        "ldpi" to 120,
        "mdpi" to 160,
        "tvdpi" to 213,
        "hdpi" to 240,
        "xhdpi" to 320,
        "xxhdpi" to 480,
        "xxxhdpi" to 640,
        "nodpi" to 0,
        "anydpi" to 0,
    )

    private val NON_LANGUAGE_TOKENS = setOf(
        "tv", "vr", "wear", "auto", "car", "watch", "master", "base", "split", "config",
        "standalone", "universal", "xxhdpi", "xxxhdpi", "xhdpi", "hdpi", "mdpi", "ldpi",
        "tvdpi", "nodpi", "anydpi", "armeabi", "x86", "mips", "mips64", "riscv64",
    )

    private val ISO_LANGUAGES: Set<String> by lazy {
        buildSet {
            addAll(Locale.getISOLanguages())
            add("in")
            add("iw")
            add("ji")
            add("fil")
            add("tl")
        }
    }

    private val NUMERIC_DPI_REGEX = Regex("^(\\d{2,4})dpi$")
    private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")
    private val SAFE_FILENAME_CHARS_REGEX = Regex("[^A-Za-z0-9._-]")

    data class BundleStageResult(
        val success: Boolean,
        val isBundle: Boolean,
        val hasObb: Boolean,
        val obbSizeBytes: Long,
    )

    private enum class ConfigDimension {
        ABI,
        DENSITY,
        LANGUAGE,
    }

    private data class ApkZipEntry(
        val entryName: String,
        val size: Long,
        val isStandalone: Boolean,
        val isInSplitsDir: Boolean,
    )

    private data class ObbZipEntry(
        val entryName: String,
        val packageDir: String?,
        val fileName: String,
        val size: Long,
    )

    private data class BundleInspection(
        val apkEntries: List<ApkZipEntry>,
        val obbEntries: List<ObbZipEntry>,
        val manifestBaseFile: String?,
    )

    private data class SplitClassification(
        val featurePrefix: String,
        val dimension: ConfigDimension,
        val value: String,
    )

    fun interface CancelCheck {
        fun isRunning(): Boolean
    }

    fun interface ProgressCallback {
        fun onProgress(progress: Int)
    }

    @JvmStatic
    fun isBundleMimeType(mimeType: String?): Boolean {
        return mimeType != null && BUNDLE_MIME_TYPES.contains(mimeType.lowercase(Locale.US))
    }

    @JvmStatic
    fun isSingleApk(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r").use { afd ->
                val pfd = afd?.parcelFileDescriptor ?: return false
                context.packageManager.packageInstaller.readInstallInfo(pfd, uri.toString(), 0)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    @JvmStatic
    fun stageUriIntoSession(
        context: Context,
        uri: Uri,
        mimeType: String?,
        session: PackageInstaller.Session,
        cancelCheck: CancelCheck,
        progressCallback: ProgressCallback,
    ): BundleStageResult {
        if (!isBundleMimeType(mimeType) && isSingleApk(context, uri)) {
            val staged = stageSingleApk(context, uri, session, cancelCheck, progressCallback)
            return BundleStageResult(
                success = staged,
                isBundle = false,
                hasObb = false,
                obbSizeBytes = 0L,
            )
        }

        val inspection = inspectBundle(context, uri, cancelCheck)
            ?: return BundleStageResult(false, isBundle = false, hasObb = false, obbSizeBytes = 0L)

        val selectedApks = selectCompatibleApks(context, inspection)
        if (selectedApks.isNullOrEmpty()) {
            Log.w(TAG, "No compatible APKs found in bundle")
            return BundleStageResult(false, isBundle = true, hasObb = false, obbSizeBytes = 0L)
        }

        val stagedNameMap = buildStagedNameMap(selectedApks, inspection.manifestBaseFile)
        val expectedApkBytes = selectedApks
            .takeIf { list -> list.all { it.size > 0L } }
            ?.sumOf { it.size }
            ?: getContentSizeBytes(context, uri)
        val stagedOk = stageBundleApks(
            context,
            uri,
            session,
            stagedNameMap,
            expectedApkBytes,
            cancelCheck,
            progressCallback,
        )
        if (!stagedOk) {
            return BundleStageResult(false, isBundle = true, hasObb = false, obbSizeBytes = 0L)
        }

        val obbBytes = inspection.obbEntries
            .map { it.size }
            .filter { it > 0L }
            .sum()

        return BundleStageResult(
            success = true,
            isBundle = true,
            hasObb = inspection.obbEntries.isNotEmpty(),
            obbSizeBytes = obbBytes,
        )
    }

    private fun stageSingleApk(
        context: Context,
        uri: Uri,
        session: PackageInstaller.Session,
        cancelCheck: CancelCheck,
        progressCallback: ProgressCallback,
    ): Boolean {
        val sizeBytes = getContentSizeBytes(context, uri)
        context.contentResolver.openInputStream(uri).use { instream ->
            session.setStagingProgress(0f)
            if (instream == null) {
                return false
            }
            progressCallback.onProgress(if (sizeBytes > 0) 0 else -1)
            var totalRead = 0L
            session.openWrite("PackageInstaller", 0, sizeBytes).use { out ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    if (!cancelCheck.isRunning()) {
                        return false
                    }
                    val numRead = instream.read(buffer)
                    if (numRead == -1) {
                        session.fsync(out)
                        break
                    }
                    out.write(buffer, 0, numRead)
                    if (sizeBytes > 0) {
                        totalRead += numRead.toLong()
                        val fraction = (totalRead.toFloat() / sizeBytes.toFloat()).coerceIn(0f, 1f)
                        session.setStagingProgress(fraction)
                        progressCallback.onProgress((fraction * 100.0f).toInt())
                    }
                }
            }
        }
        return cancelCheck.isRunning()
    }

    private fun inspectBundle(
        context: Context,
        uri: Uri,
        cancelCheck: CancelCheck,
    ): BundleInspection? {
        val apkEntries = ArrayList<ApkZipEntry>()
        val obbEntries = ArrayList<ObbZipEntry>()
        var manifestBaseFile: String? = null

        try {
            context.contentResolver.openInputStream(uri).use { rawStream ->
                if (rawStream == null) {
                    return null
                }
                val buffered = BufferedInputStream(rawStream, BUFFER_SIZE)
                if (!hasZipSignature(buffered)) {
                    return null
                }
                ZipInputStream(buffered).use { zis ->
                    var entryCount = 0
                    while (true) {
                        if (!cancelCheck.isRunning()) {
                            return null
                        }
                        val entry = zis.nextEntry ?: break
                        entryCount++
                        if (entryCount > MAX_ZIP_ENTRIES) {
                            Log.w(TAG, "Bundle exceeds maximum entry count")
                            return null
                        }
                        val normalized = normalizeZipEntryName(entry.name)
                        if (normalized == null || entry.isDirectory) {
                            zis.closeEntry()
                            continue
                        }
                        if (normalized == ROOT_ANDROID_MANIFEST) {
                            return null
                        }
                        if (normalized == XAPK_MANIFEST_JSON) {
                            val jsonBytes = readBoundedBytes(zis, MAX_MANIFEST_BYTES)
                            if (jsonBytes != null) {
                                parseXapkManifest(jsonBytes) { manifestBaseFile = it }
                            }
                            zis.closeEntry()
                            continue
                        }
                        val lower = normalized.lowercase(Locale.US)
                        if (lower.endsWith(APK_EXTENSION) && !isIgnoredApkPath(lower)) {
                            apkEntries.add(
                                ApkZipEntry(
                                    entryName = normalized,
                                    size = entry.size,
                                    isStandalone = lower.startsWith("standalones/"),
                                    isInSplitsDir = lower.startsWith("splits/"),
                                )
                            )
                        } else if (lower.endsWith(OBB_EXTENSION)) {
                            parseObbEntry(normalized, entry.size)?.let { obbEntries.add(it) }
                        }
                        zis.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect bundle archive", e)
            return null
        }

        if (apkEntries.isEmpty()) {
            return null
        }
        return BundleInspection(apkEntries, obbEntries, manifestBaseFile)
    }

    private fun stageBundleApks(
        context: Context,
        uri: Uri,
        session: PackageInstaller.Session,
        stagedNameMap: Map<String, String>,
        totalSizeBytes: Long,
        cancelCheck: CancelCheck,
        progressCallback: ProgressCallback,
    ): Boolean {
        var stagedCount = 0
        var totalStagedBytes = 0L

        context.contentResolver.openInputStream(uri).use { rawStream ->
            session.setStagingProgress(0f)
            if (rawStream == null) {
                return false
            }
            progressCallback.onProgress(if (totalSizeBytes > 0) 0 else -1)

            ZipInputStream(BufferedInputStream(rawStream, BUFFER_SIZE)).use { zis ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    if (!cancelCheck.isRunning()) {
                        return false
                    }
                    val entry = zis.nextEntry ?: break
                    val normalized = normalizeZipEntryName(entry.name)
                    val stagedName = if (normalized != null && !entry.isDirectory) {
                        stagedNameMap[normalized]
                    } else {
                        null
                    }
                    if (stagedName == null) {
                        zis.closeEntry()
                        continue
                    }

                    val expectedLength = if (entry.size > 0L) entry.size else -1L
                    session.openWrite(stagedName, 0, expectedLength).use { out ->
                        while (true) {
                            if (!cancelCheck.isRunning()) {
                                return false
                            }
                            val numRead = zis.read(buffer)
                            if (numRead == -1) {
                                session.fsync(out)
                                break
                            }
                            out.write(buffer, 0, numRead)
                            totalStagedBytes += numRead.toLong()
                            if (totalSizeBytes > 0L) {
                                val fraction = (totalStagedBytes.toFloat() / totalSizeBytes.toFloat())
                                    .coerceIn(0f, 0.99f)
                                session.setStagingProgress(fraction)
                                progressCallback.onProgress((fraction * 100.0f).toInt())
                            }
                        }
                    }
                    stagedCount++
                    zis.closeEntry()
                }
            }
        }

        if (stagedCount == 0 || !cancelCheck.isRunning()) {
            return false
        }
        session.setStagingProgress(1f)
        progressCallback.onProgress(100)
        return true
    }

    @JvmStatic
    @Throws(IOException::class, SecurityException::class)
    fun extractObbFiles(
        context: Context,
        uri: Uri,
        targetPackageName: String,
        cancelCheck: CancelCheck,
    ): List<File> {
        if (!PACKAGE_NAME_REGEX.matches(targetPackageName)) {
            throw IOException("Invalid package name for OBB extraction: $targetPackageName")
        }

        val obbDir = File(
            Environment.getExternalStorageDirectory(),
            "Android/obb/$targetPackageName",
        )
        val createdFiles = ArrayList<File>()
        var currentTempFile: File? = null

        try {
            context.contentResolver.openInputStream(uri).use { rawStream ->
                if (rawStream == null) {
                    throw IOException("Unable to open bundle stream for OBB extraction")
                }
                ZipInputStream(BufferedInputStream(rawStream, BUFFER_SIZE)).use { zis ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var entryCount = 0
                    while (true) {
                        if (!cancelCheck.isRunning()) {
                            throw IOException("OBB extraction cancelled")
                        }
                        val entry = zis.nextEntry ?: break
                        entryCount++
                        if (entryCount > MAX_ZIP_ENTRIES) {
                            throw IOException("Bundle exceeds maximum entry count")
                        }
                        val normalized = normalizeZipEntryName(entry.name)
                        if (normalized == null || entry.isDirectory
                            || !normalized.lowercase(Locale.US).endsWith(OBB_EXTENSION)
                        ) {
                            zis.closeEntry()
                            continue
                        }

                        val obbEntry = parseObbEntry(normalized, entry.size)
                        if (obbEntry == null) {
                            zis.closeEntry()
                            continue
                        }
                        if (obbEntry.packageDir != null && obbEntry.packageDir != targetPackageName) {
                            Log.w(
                                TAG,
                                "Skipping OBB entry for mismatched package: ${obbEntry.packageDir}",
                            )
                            zis.closeEntry()
                            continue
                        }

                        if (!obbDir.exists() && !obbDir.mkdirs() && !obbDir.isDirectory) {
                            throw IOException("Failed to create OBB directory: $obbDir")
                        }

                        val destFile = File(obbDir, obbEntry.fileName)
                        val existedBefore = destFile.exists()
                        val tempFile = File(obbDir, "${obbEntry.fileName}.tmp")
                        currentTempFile = tempFile

                        FileOutputStream(tempFile).use { fos ->
                            while (true) {
                                if (!cancelCheck.isRunning()) {
                                    throw IOException("OBB extraction cancelled")
                                }
                                val numRead = zis.read(buffer)
                                if (numRead == -1) {
                                    break
                                }
                                fos.write(buffer, 0, numRead)
                            }
                            fos.flush()
                            fos.fd.sync()
                        }

                        if (!tempFile.renameTo(destFile)) {
                            if (destFile.exists() && !destFile.delete()) {
                                throw IOException("Failed to replace existing OBB file: $destFile")
                            }
                            if (!tempFile.renameTo(destFile)) {
                                throw IOException("Failed to finalize OBB file: $destFile")
                            }
                        }
                        currentTempFile = null
                        if (!existedBefore) {
                            createdFiles.add(destFile)
                        }
                        zis.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            currentTempFile?.delete()
            cleanupObbFiles(createdFiles)
            if (e is IOException) {
                throw e
            }
            if (e is SecurityException) {
                throw e
            }
            throw IOException("Failed to extract OBB files", e)
        }

        return createdFiles
    }

    @JvmStatic
    fun cleanupObbFiles(files: List<File>?) {
        if (files.isNullOrEmpty()) {
            return
        }
        for (file in files) {
            try {
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete OBB file: $file", e)
            }
        }
    }

    private fun selectCompatibleApks(
        context: Context,
        inspection: BundleInspection,
    ): List<ApkZipEntry>? {
        val hasSplits = inspection.apkEntries.any { !it.isStandalone }
        val candidates = if (hasSplits) {
            val nonStandalone = inspection.apkEntries.filter { !it.isStandalone }
            if (nonStandalone.any { it.isInSplitsDir }) {
                nonStandalone.filter { it.isInSplitsDir }
            } else {
                nonStandalone
            }
        } else {
            val bestStandalone = selectBestStandalone(context, inspection.apkEntries)
            return if (bestStandalone != null) listOf(bestStandalone) else null
        }

        val alwaysKeep = ArrayList<ApkZipEntry>()
        val grouped = LinkedHashMap<Pair<String, ConfigDimension>, MutableList<Pair<ApkZipEntry, String>>>()

        for (entry in candidates) {
            if (inspection.manifestBaseFile != null
                && entry.entryName == inspection.manifestBaseFile
            ) {
                alwaysKeep.add(entry)
                continue
            }
            val classification = classifySplitApk(entry.entryName)
            if (classification == null) {
                alwaysKeep.add(entry)
            } else {
                val key = classification.featurePrefix to classification.dimension
                grouped.getOrPut(key) { ArrayList() }.add(entry to classification.value)
            }
        }

        val selected = ArrayList<ApkZipEntry>(alwaysKeep)
        val supportedAbis = Build.SUPPORTED_ABIS.toList()
        val deviceDpi = context.resources.displayMetrics.densityDpi
        val deviceLocales = getDeviceLocales(context)

        for ((key, entries) in grouped) {
            when (key.second) {
                ConfigDimension.ABI -> {
                    val picked = pickBestAbiSplit(entries, supportedAbis) ?: return null
                    selected.add(picked)
                }

                ConfigDimension.DENSITY -> {
                    val picked = pickBestDensitySplit(entries, deviceDpi)
                    if (picked != null) {
                        selected.add(picked)
                    }
                }

                ConfigDimension.LANGUAGE -> {
                    selected.addAll(pickBestLanguageSplits(entries, deviceLocales))
                }
            }
        }

        return selected
    }

    private fun selectBestStandalone(
        context: Context,
        standalones: List<ApkZipEntry>,
    ): ApkZipEntry? {
        if (standalones.size == 1) {
            return standalones[0]
        }
        val supportedAbis = Build.SUPPORTED_ABIS.toList()
        val deviceDpi = context.resources.displayMetrics.densityDpi

        var bestEntry: ApkZipEntry? = null
        var bestScore = Int.MIN_VALUE

        for (entry in standalones) {
            val stem = fileStem(entry.entryName).lowercase(Locale.US)
            var abiRank = -1
            for (suffix in ABI_SUFFIXES) {
                if (stem.contains(suffix)) {
                    val abi = ABI_ALIASES[suffix]!!
                    abiRank = supportedAbis.indexOf(abi)
                    if (abiRank == -1) {
                        abiRank = Int.MAX_VALUE
                    }
                    break
                }
            }
            if (abiRank == Int.MAX_VALUE) {
                continue
            }

            var dpiPenalty = 1000
            for ((name, dpi) in DENSITY_DPI_MAP) {
                if (dpi > 0 && stem.contains(name)) {
                    dpiPenalty = abs(dpi - deviceDpi)
                    break
                }
            }

            val abiScore = if (abiRank >= 0) (100 - abiRank * 20) * 10000 else 500000
            val score = abiScore - dpiPenalty
            if (score > bestScore) {
                bestScore = score
                bestEntry = entry
            }
        }

        return bestEntry ?: standalones.firstOrNull()
    }

    private fun pickBestAbiSplit(
        entries: List<Pair<ApkZipEntry, String>>,
        supportedAbis: List<String>,
    ): ApkZipEntry? {
        var bestEntry: ApkZipEntry? = null
        var bestRank = Int.MAX_VALUE
        for ((entry, abi) in entries) {
            val rank = supportedAbis.indexOf(abi)
            if (rank in 0 until bestRank) {
                bestRank = rank
                bestEntry = entry
            }
        }
        return bestEntry
    }

    private fun pickBestDensitySplit(
        entries: List<Pair<ApkZipEntry, String>>,
        deviceDpi: Int,
    ): ApkZipEntry? {
        var exactOrHigher: ApkZipEntry? = null
        var exactOrHigherDiff = Int.MAX_VALUE
        var lower: ApkZipEntry? = null
        var lowerDiff = Int.MAX_VALUE
        var fallback: ApkZipEntry? = null

        for ((entry, value) in entries) {
            val dpi = parseDensityDpi(value)
            if (dpi == null || dpi == 0) {
                if (fallback == null) {
                    fallback = entry
                }
                continue
            }
            if (dpi >= deviceDpi) {
                val diff = dpi - deviceDpi
                if (diff < exactOrHigherDiff) {
                    exactOrHigherDiff = diff
                    exactOrHigher = entry
                }
            } else {
                val diff = deviceDpi - dpi
                if (diff < lowerDiff) {
                    lowerDiff = diff
                    lower = entry
                }
            }
        }

        return exactOrHigher ?: lower ?: fallback ?: entries.firstOrNull()?.first
    }

    private fun pickBestLanguageSplits(
        entries: List<Pair<ApkZipEntry, String>>,
        deviceLocales: List<Locale>,
    ): List<ApkZipEntry> {
        val matched = ArrayList<ApkZipEntry>()
        val deviceLangs = deviceLocales.map { canonicalLanguage(it.language) }.toSet()
        val deviceTags = deviceLocales.mapNotNull { locale ->
            val lang = canonicalLanguage(locale.language)
            val country = locale.country.lowercase(Locale.US)
            if (country.isNotEmpty()) "${lang}_$country" else null
        }.toSet()

        for ((entry, rawLocale) in entries) {
            val normalized = normalizeLocaleToken(rawLocale)
            val baseLang = canonicalLanguage(normalized.substringBefore('_'))
            if (deviceTags.contains(normalized) || (baseLang == normalized && deviceLangs.contains(baseLang))) {
                matched.add(entry)
            }
        }

        if (matched.isEmpty()) {
            for ((entry, rawLocale) in entries) {
                val baseLang = canonicalLanguage(normalizeLocaleToken(rawLocale).substringBefore('_'))
                if (deviceLangs.contains(baseLang)) {
                    matched.add(entry)
                }
            }
        }

        if (matched.isEmpty()) {
            val english = entries.filter { (_, rawLocale) ->
                canonicalLanguage(normalizeLocaleToken(rawLocale).substringBefore('_')) == "en"
            }.map { it.first }
            if (english.isNotEmpty()) {
                matched.addAll(english)
            } else if (entries.isNotEmpty()) {
                matched.add(entries[0].first)
            }
        }

        return matched
    }

    private fun classifySplitApk(entryName: String): SplitClassification? {
        val stem = fileStem(entryName)
        val lower = stem.lowercase(Locale.US)

        if (lower == "base" || lower.endsWith("-master") || lower.endsWith("_master")) {
            return null
        }

        val configMarkerIndex = lower.indexOf("config.")
        if (configMarkerIndex >= 0) {
            val prefixPart = lower.substring(0, configMarkerIndex)
            if (prefixPart.isEmpty() || prefixPart == "split_" || prefixPart.endsWith(".")
                || prefixPart.endsWith("_") || prefixPart.endsWith("-")
            ) {
                val token = lower.substring(configMarkerIndex + "config.".length)
                val feature = prefixPart
                    .removePrefix("split_")
                    .trimEnd('.', '_', '-')
                    .ifEmpty { "base" }
                return classifyToken(feature, token)
            }
        }

        for (abiSuffix in ABI_SUFFIXES) {
            val dashedSuffix = "-$abiSuffix"
            if (lower.endsWith(dashedSuffix) && lower.length > dashedSuffix.length) {
                val feature = lower.substring(0, lower.length - dashedSuffix.length)
                return SplitClassification(feature, ConfigDimension.ABI, ABI_ALIASES[abiSuffix]!!)
            }
        }

        val lastDash = lower.lastIndexOf('-')
        if (lastDash > 0 && lastDash < lower.length - 1) {
            val feature = lower.substring(0, lastDash)
            val token = lower.substring(lastDash + 1)
            val secondLastDash = feature.lastIndexOf('-')
            if (secondLastDash > 0 && token.startsWith("r") && token.length == 3) {
                val langToken = feature.substring(secondLastDash + 1)
                val langFeature = feature.substring(0, secondLastDash)
                val combined = "${langToken}_$token"
                if (isLanguageToken(combined)) {
                    return SplitClassification(langFeature, ConfigDimension.LANGUAGE, combined)
                }
            }
            return classifyToken(feature, token)
        }

        return null
    }

    private fun classifyToken(feature: String, token: String): SplitClassification? {
        if (token.isEmpty()) {
            return null
        }
        ABI_ALIASES[token]?.let { abi ->
            return SplitClassification(feature, ConfigDimension.ABI, abi)
        }
        if (parseDensityDpi(token) != null) {
            return SplitClassification(feature, ConfigDimension.DENSITY, token)
        }
        if (isLanguageToken(token)) {
            return SplitClassification(feature, ConfigDimension.LANGUAGE, token)
        }
        return null
    }

    private fun parseDensityDpi(token: String): Int? {
        DENSITY_DPI_MAP[token]?.let { return it }
        val match = NUMERIC_DPI_REGEX.matchEntire(token) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun isLanguageToken(token: String): Boolean {
        if (NON_LANGUAGE_TOKENS.contains(token)) {
            return false
        }
        if (token.startsWith("b+")) {
            val parts = token.removePrefix("b+").split('+')
            return parts.isNotEmpty() && ISO_LANGUAGES.contains(canonicalLanguage(parts[0]))
        }
        val normalized = normalizeLocaleToken(token)
        val parts = normalized.split('_')
        if (parts.isEmpty() || parts.size > 2) {
            return false
        }
        val lang = canonicalLanguage(parts[0])
        if (lang.length !in 2..3 || !ISO_LANGUAGES.contains(lang)) {
            return false
        }
        if (parts.size == 2) {
            val region = parts[1]
            if (region.length != 2 && region.length != 3) {
                return false
            }
        }
        return true
    }

    private fun normalizeLocaleToken(token: String): String {
        val lower = token.lowercase(Locale.US)
        if (lower.startsWith("b+")) {
            val parts = lower.removePrefix("b+").split('+')
            return if (parts.size >= 2) "${parts[0]}_${parts[1]}" else parts[0]
        }
        val unified = lower.replace('-', '_')
        val idx = unified.indexOf("_r")
        return if (idx in 2..3 && unified.length == idx + 4) {
            "${unified.substring(0, idx)}_${unified.substring(idx + 2)}"
        } else {
            unified
        }
    }

    private fun canonicalLanguage(language: String): String {
        return when (val lower = language.lowercase(Locale.US)) {
            "in" -> "id"
            "iw" -> "he"
            "ji" -> "yi"
            "tl" -> "fil"
            else -> lower
        }
    }

    private fun getDeviceLocales(context: Context): List<Locale> {
        val list = context.resources.configuration.locales
        return buildList {
            for (i in 0 until list.size()) {
                add(list[i])
            }
            if (isEmpty()) {
                add(Locale.getDefault())
            }
        }
    }

    private fun buildStagedNameMap(
        selectedApks: List<ApkZipEntry>,
        manifestBaseFile: String?,
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val usedNames = HashSet<String>()

        val baseCandidate = selectedApks.firstOrNull { entry ->
            entry.entryName == manifestBaseFile
        } ?: selectedApks.firstOrNull { entry ->
            val lower = fileStem(entry.entryName).lowercase(Locale.US)
            lower == "base" || lower == "base-master" || lower == "base_master"
        } ?: selectedApks.singleOrNull()

        if (baseCandidate != null) {
            result[baseCandidate.entryName] = "base.apk"
            usedNames.add("base.apk")
        }

        var counter = 0
        for (entry in selectedApks) {
            if (result.containsKey(entry.entryName)) {
                continue
            }
            val rawName = entry.entryName.substringAfterLast('/')
            val sanitizedBase = SAFE_FILENAME_CHARS_REGEX.replace(rawName, "_")
                .trimStart('.')
                .ifEmpty { "split_${counter++}.apk" }
            var candidateName = if (sanitizedBase.lowercase(Locale.US).endsWith(APK_EXTENSION)) {
                sanitizedBase
            } else {
                "$sanitizedBase$APK_EXTENSION"
            }
            if (!FileUtils.isValidExtFilename(candidateName)) {
                candidateName = "split_${counter++}.apk"
            }
            while (!usedNames.add(candidateName)) {
                candidateName = "split_${counter++}_$candidateName"
            }
            result[entry.entryName] = candidateName
        }

        return result
    }

    private fun parseXapkManifest(
        bytes: ByteArray,
        onBaseFile: (String) -> Unit,
    ) {
        try {
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            val splitApks = root.optJSONArray("split_apks")
            if (splitApks != null) {
                for (i in 0 until splitApks.length()) {
                    val item = splitApks.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    val file = normalizeZipEntryName(item.optString("file")) ?: continue
                    if (id == "base") {
                        onBaseFile(file)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring malformed XAPK manifest.json", e)
        }
    }

    private fun parseObbEntry(normalizedEntryName: String, size: Long): ObbZipEntry? {
        val segments = normalizedEntryName.split('/')
        val fileName = segments.lastOrNull() ?: return null
        if (fileName.isEmpty() || !fileName.lowercase(Locale.US).endsWith(OBB_EXTENSION)
            || !FileUtils.isValidExtFilename(fileName)
        ) {
            return null
        }

        var packageDir: String? = null
        val obbIndex = segments.indexOfFirst { it.equals("obb", ignoreCase = true) }
        if (obbIndex >= 0 && obbIndex + 2 < segments.size) {
            val candidatePkg = segments[obbIndex + 1]
            if (PACKAGE_NAME_REGEX.matches(candidatePkg)) {
                packageDir = candidatePkg
            }
        }

        return ObbZipEntry(
            entryName = normalizedEntryName,
            packageDir = packageDir,
            fileName = fileName,
            size = size,
        )
    }

    private fun normalizeZipEntryName(rawName: String?): String? {
        if (rawName.isNullOrBlank()) {
            return null
        }
        if (rawName.indexOf('\u0000') >= 0) {
            return null
        }
        val trimmed = rawName.replace('\\', '/').trimStart('/')
        if (trimmed.isEmpty() || trimmed.startsWith("__MACOSX/")) {
            return null
        }
        val segments = trimmed.split('/')
        for (segment in segments) {
            if (segment == "." || segment == ".." || segment.isEmpty()) {
                return null
            }
        }
        return trimmed
    }

    private fun isIgnoredApkPath(lowerEntryName: String): Boolean {
        return lowerEntryName.startsWith("assets/")
            || lowerEntryName.startsWith("res/")
            || lowerEntryName.startsWith("meta-inf/")
    }

    private fun fileStem(entryName: String): String {
        val base = entryName.substringAfterLast('/')
        return if (base.lowercase(Locale.US).endsWith(APK_EXTENSION)) {
            base.substring(0, base.length - APK_EXTENSION.length)
        } else {
            base
        }
    }

    private fun hasZipSignature(stream: BufferedInputStream): Boolean {
        stream.mark(4)
        val header = ByteArray(4)
        var offset = 0
        while (offset < 4) {
            val read = stream.read(header, offset, 4 - offset)
            if (read == -1) {
                stream.reset()
                return false
            }
            offset += read
        }
        stream.reset()
        return header[0] == 0x50.toByte()
            && header[1] == 0x4B.toByte()
            && header[2] == 0x03.toByte()
            && header[3] == 0x04.toByte()
    }

    private fun readBoundedBytes(stream: InputStream, maxBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read == -1) {
                break
            }
            total += read
            if (total > maxBytes) {
                return null
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun getContentSizeBytes(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver
                .openAssetFileDescriptor(uri, "r")
                .use { afd -> afd?.length ?: AssetFileDescriptor.UNKNOWN_LENGTH }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to open asset file descriptor", e)
            AssetFileDescriptor.UNKNOWN_LENGTH
        }
    }
}
