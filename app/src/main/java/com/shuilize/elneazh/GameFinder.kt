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

    private fun toInfo(pm: PackageManager, ai: ApplicationInfo): GameInfo {
        val apks = buildList {
            add(File(ai.sourceDir))
            ai.splitSourceDirs?.forEach { add(File(it)) }
        }
        return GameInfo(
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
        for (p in KNOWN) appInfo(pm, p)?.let { return toInfo(pm, it) }

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != ctx.packageName }

        apps.firstOrNull {
            val label = pm.getApplicationLabel(it).toString()
            label.contains("Elnea", ignoreCase = true) || it.packageName.contains("elnea", ignoreCase = true)
        }?.let { return toInfo(pm, it) }

        apps.filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .firstOrNull { containsGameData(it) }
            ?.let { return toInfo(pm, it) }
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
