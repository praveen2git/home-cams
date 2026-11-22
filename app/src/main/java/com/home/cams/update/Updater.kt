package com.home.cams.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class Update(val version: String, val apkUrl: String, val apkSize: Long)

object Releases {
    /** True when [candidate] ("v0.2.0") is a higher version than [current] ("0.1.0"). */
    fun isNewer(current: String, candidate: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V").substringBefore('-')
            .split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(current)
        val b = parts(candidate)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return y > x
        }
        return false
    }

    /** The update in GitHub's "latest release" answer, or null when it is not newer or has no APK. */
    fun parseLatest(json: String, current: String): Update? {
        val o = JSONObject(json)
        val tag = o.optString("tag_name")
        if (tag.isEmpty() || !isNewer(current, tag)) return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                return Update(tag.removePrefix("v").removePrefix("V"), a.getString("browser_download_url"), a.optLong("size"))
            }
        }
        return null
    }
}

/** Updates from the public GitHub repo's releases, so no token is needed. */
class Updater(
    private val context: Context,
    private val client: OkHttpClient,
    private val currentVersion: String,
    private val latestUrl: String = "https://api.github.com/repos/praveen2git/home-cams/releases/latest"
) {
    /** The newer release, or null when up to date. Throws when GitHub can't be reached. */
    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(latestUrl)
            .header("Accept", "application/vnd.github+json").header("User-Agent", "HomeCams-AndroidTV").build()
        client.newCall(request).execute().use { res ->
            if (res.code == 404) return@withContext null // no release published yet
            if (!res.isSuccessful) throw IOException("GitHub said HTTP ${res.code}")
            Releases.parseLatest(res.body?.string().orEmpty(), currentVersion)
        }
    }

    /** Downloads the APK and opens the system installer. */
    suspend fun install(update: Update, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // old downloads
        val file = File(dir, "home-cams-${update.version}.apk")
        val request = Request.Builder().url(update.apkUrl).header("User-Agent", "HomeCams-AndroidTV").build()
        client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) throw IOException("Download failed: HTTP ${res.code}")
            val body = res.body ?: throw IOException("Download was empty")
            val total = if (update.apkSize > 0) update.apkSize else body.contentLength()
            var done = 0L
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        if (update.apkSize > 0 && file.length() != update.apkSize) {
            file.delete()
            throw IOException("The download was cut short. Try again.")
        }
        // The system installer checks the APK signature against the installed app.
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }
}
