package com.sleepclub.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Самообновление через GitHub Releases (репозиторий должен быть публичным). */
object Updater {
    data class Release(val tag: String, val versionCode: Int, val apkUrl: String, val notes: String)

    /** v1.2.3 → 10203 (так же считает Gradle). */
    fun versionCodeOf(tag: String): Int? {
        val parts = tag.removePrefix("v").split(".")
        if (parts.size != 3) return null
        val (a, b, c) = parts.map { it.toIntOrNull() ?: return null }
        return a * 10000 + b * 100 + c
    }

    suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        val repo = BuildConfig.GITHUB_REPO
        if (repo.isBlank() || repo.startsWith("OWNER/")) return@withContext null
        val conn = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (conn.responseCode != 200) return@withContext null
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = o.getString("tag_name")
            val code = versionCodeOf(tag) ?: return@withContext null
            val assets = o.getJSONArray("assets").objects()
            val apk = assets.firstOrNull { it.getString("name").endsWith(".apk") } ?: return@withContext null
            Release(tag, code, apk.getString("browser_download_url"), o.optString("body"))
        } finally {
            conn.disconnect()
        }
    }

    /** Раз в сутки из фоновой задачи: если есть новая версия — уведомление. */
    suspend fun checkIfDue(ctx: Context) {
        val prefs = Prefs(ctx)
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheck < 24 * 3_600_000L) return
        prefs.lastUpdateCheck = now
        val r = fetchLatest() ?: return
        if (r.versionCode > BuildConfig.VERSION_CODE) Notifications.showUpdate(ctx, r.tag)
    }

    suspend fun download(ctx: Context, r: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "sleepclub-${r.tag}.apk")
        val conn = URL(r.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = true
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        file
    }

    /** Нужно ли сначала разрешить установку из этого приложения. */
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
