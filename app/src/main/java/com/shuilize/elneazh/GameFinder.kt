package com.shuilize.elneazh

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.shuilize.elneazh.core.ApkRebuilder
import com.shuilize.elneazh.core.Pipeline
import java.io.File
import java.io.RandomAccessFile

data class GameInfo(
    val label: String,
    val packageName: String,
    val versionName: String?,
    /** base APK first, then split APKs */
    val apks: List<File>,
    val fromFile: Boolean,
    /** installed game is already our localized build (signed with the bundled key) -> can update in place */
    val alreadyZh: Boolean = false,
)

object GameFinder {
    /** Real package name of Elnea Kingdom (English, Google Play), read from the 2.10.7 AndroidManifest. */
    const val KNOWN_PACKAGE = "jp.co.althi.elnea.en"
    private val KNOWN = listOf(KNOWN_PACKAGE, "jp.co.althi.elnea")
    /** uncompressed size of the 2.10.7 data.unity3d, used to recognise the game cheaply */
    const val BASE_DATA_SIZE = 28131438L

    @Suppress("DEPRECATION")
    private fun appInfo(pm: PackageManager, pkg: String): ApplicationInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        else pm.getApplicationInfo(pkg, 0)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    @Suppress("DEPRECATION")
    fun versionOf(pm: PackageManager, pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0)).versionName
        else pm.getPackageInfo(pkg, 0).versionName
    } catch (e: Exception) {
        null
    }

    fun isInstalled(ctx: Context, pkg: String): Boolean = appInfo(ctx.packageManager, pkg) != null

    /** SHA-256 of the signing certificate(s) of an installed package. */
    @Suppress("DEPRECATION")
    private fun certHashes(pm: PackageManager, pkg: String): Set<String> = try {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            pi.signingInfo?.let { si -> if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory }
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
        sigs?.map { sha256(it.toByteArray()) }?.toSet() ?: emptySet()
    } catch (e: Exception) {
        emptySet()
    }

    private fun sha256(b: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** True when the installed game is signed with the localizer's bundled certificate (assets/sign.x509.der). */
    fun isOurSigned(ctx: Context, pkg: String): Boolean = try {
        val ours = ctx.assets.open("sign.x509.der").use { sha256(it.readBytes()) }
        ours in certHashes(ctx.packageManager, pkg)
    } catch (e: Exception) {
        false
    }

    /** Copies of the original APKs kept after the first successful run, so later updates need no uninstall. */
    fun origDir(ctx: Context) = File(ctx.filesDir, "orig")

    fun cachedOriginals(ctx: Context): List<File> {
        val d = origDir(ctx)
        val base = File(d, "base.apk")
        if (!base.isFile) return emptyList()
        return listOf(base) + (d.listFiles { f -> f.name.startsWith("split") && f.name.endsWith(".apk") }?.sortedBy { it.name } ?: emptyList())
    }

    fun saveOriginals(ctx: Context, apks: List<File>) {
        val d = origDir(ctx)
        if (apks.isNotEmpty() && apks[0].canonicalPath.startsWith(d.canonicalPath)) return
        d.deleteRecursively(); d.mkdirs()
        apks.forEachIndexed { i, f -> f.copyTo(File(d, if (i == 0) "base.apk" else "split%02d.apk".format(i)), overwrite = true) }
    }

    private fun toInfo(ctx: Context, pm: PackageManager, ai: ApplicationInfo): GameInfo {
        var apks: List<File> = buildList {
            add(File(ai.sourceDir))
            ai.splitSourceDirs?.forEach { add(File(it)) }
        }
        val zh = isOurSigned(ctx, ai.packageName)
        if (zh) cachedOriginals(ctx).takeIf { it.isNotEmpty() }?.let { apks = it }
        return GameInfo(
            alreadyZh = zh,
            label = pm.getApplicationLabel(ai).toString(),
            packageName = ai.packageName,
            versionName = versionOf(pm, ai.packageName),
            apks = apks,
            fromFile = false,
        )
    }

    private fun dataSize(apk: File): Long = try {
        RandomAccessFile(apk, "r").use { f ->
            ApkRebuilder.find(ApkRebuilder.readCentralDirectory(f), Pipeline.DATA_ENTRY)?.size() ?: -1L
        }
    } catch (e: Exception) {
        -1L
    }

    private fun containsGameData(ai: ApplicationInfo): Boolean {
        val files = buildList { add(ai.sourceDir); ai.splitSourceDirs?.let { addAll(it) } }
        return files.any { dataSize(File(it)) == BASE_DATA_SIZE }
    }

    /** Find the installed game: known package → label/package contains "elnea" → any app carrying the 2.10.7 data file. */
    @Suppress("DEPRECATION")
    fun find(ctx: Context): GameInfo? {
        val pm = ctx.packageManager
        for (p in KNOWN) appInfo(pm, p)?.let { return toInfo(ctx, pm, it) }

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != ctx.packageName }

        apps.firstOrNull {
            val label = pm.getApplicationLabel(it).toString()
            label.contains("Elnea", ignoreCase = true) || it.packageName.contains("elnea", ignoreCase = true)
        }?.let { return toInfo(ctx, pm, it) }

        apps.filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .firstOrNull { containsGameData(it) }
            ?.let { return toInfo(ctx, pm, it) }
        return null
    }

    /** Describe an APK the user picked with the file picker (already copied to [file]). */
    @Suppress("DEPRECATION")
    fun fromFile(ctx: Context, file: File, displayName: String?): GameInfo {
        val pm = ctx.packageManager
        val pi = pm.getPackageArchiveInfo(file.path, 0)
        var label = displayName ?: file.name
        pi?.applicationInfo?.let { ai ->
            ai.sourceDir = file.path
            ai.publicSourceDir = file.path
            runCatching { label = pm.getApplicationLabel(ai).toString() }
        }
        return GameInfo(
            label = label,
            packageName = pi?.packageName ?: KNOWN_PACKAGE,
            versionName = pi?.versionName,
            apks = listOf(file),
            fromFile = true,
        )
    }
}
