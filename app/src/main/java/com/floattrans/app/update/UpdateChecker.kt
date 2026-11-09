package com.floattrans.app.update

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionName: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?,
    val apkName: String?,
    val apkSize: Long
)

/**
 * Update checks read the public GitHub Releases API of this repository, so no
 * server and no account are involved. If an APK asset matching the device ABI
 * exists it is downloaded and installed; otherwise the release page is opened.
 */
object UpdateChecker {

    private const val REPO = "AjarHer08/FloatTrans"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val FEED_URL = "https://github.com/$REPO/releases.atom"
    private const val TIMEOUT_MS = 9000

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        fromApi() ?: fromFeed()
    }

    /** Primary path: the documented API gives assets and notes directly. */
    private fun fromApi(): UpdateInfo? {
        val body = get(LATEST_URL) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null

        val version = json.optString("tag_name").trim().removePrefix("v").removePrefix("V")
        if (version.isBlank()) return null

        var matchingRelease: JSONObject? = null
        var matchingDebug: JSONObject? = null
        var universalRelease: JSONObject? = null
        var universalDebug: JSONObject? = null
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                val isRelease = name.contains("release", ignoreCase = true)
                if (name.contains("universal", ignoreCase = true)) {
                    if (isRelease) universalRelease = asset else universalDebug = asset
                }
                if (matchesAbi(name)) {
                    if (isRelease) matchingRelease = asset else matchingDebug = asset
                }
            }
        }
        // Prefer a consistently signed release APK (updateable over installs that
        // already use the same release key); fall back to debug/universal.
        val chosen = matchingRelease ?: universalRelease ?: matchingDebug ?: universalDebug

        return UpdateInfo(
            versionName = version,
            notes = json.optString("body").trim(),
            pageUrl = json.optString("html_url"),
            apkUrl = chosen?.optString("browser_download_url"),
            apkName = chosen?.optString("name"),
            apkSize = chosen?.optLong("size") ?: 0L
        )
    }

    /**
     * Fallback when the API answers 403 (the anonymous rate limit is 60 per hour
     * and mobile carriers share addresses). The Atom feed is not rate limited;
     * it only names the tag, so the APK URL is built from the CI naming scheme.
     */
    private fun fromFeed(): UpdateInfo? {
        val feed = get(FEED_URL) ?: return null
        val entry = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
            .find(feed)?.groupValues?.get(1) ?: return null
        val pageUrl = Regex("<link[^>]*href=\"([^\"]+)\"")
            .find(entry)?.groupValues?.get(1) ?: return null
        val version = Regex("<title>([^<]+)</title>")
            .find(entry)?.groupValues?.get(1)?.trim()
            ?.removePrefix("v")?.removePrefix("V") ?: return null
        if (version.isBlank() || !pageUrl.contains("/tag/")) return null

        val rawNotes = Regex("<content[^>]*>(.*?)</content>", RegexOption.DOT_MATCHES_ALL)
            .find(entry)?.groupValues?.get(1) ?: ""
        // Feed content is escaped HTML; unescape first, then drop the tags.
        val notes = unescape(rawNotes)
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val abi = abiToken()
        // The feed does not list assets. Prefer the signed release naming (used
        // once a fixed keystore is configured); if that file is absent the
        // download step will fail and the UI opens the release page.
        val apkName = abi?.let { "FloatTrans-$it-release-$version.apk" }
        val apkUrl = apkName?.let { pageUrl.replace("/tag/", "/download/") + "/$it" }

        return UpdateInfo(
            versionName = version,
            notes = notes,
            pageUrl = pageUrl,
            apkUrl = apkUrl,
            apkName = apkName,
            apkSize = 0L
        )
    }

    private fun unescape(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

    private fun abiToken(): String? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        return when {
            abi == "arm64-v8a" -> "arm64-v8a"
            abi.startsWith("armeabi") -> "armeabi-v7a"
            abi.startsWith("x86") -> "x86_64"
            else -> null
        }
    }

    fun isNewer(latest: String, current: String): Boolean {
        val a = numbers(latest)
        val b = numbers(current)
        if (a.isEmpty()) return false
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val url = info.apkUrl ?: return@withContext null
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        directory.listFiles()?.forEach { runCatching { it.delete() } }

        val name = (info.apkName ?: "FloatTrans-${info.versionName}.apk")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(directory, name)
        val partial = File(directory, "$name.part")

        val connection = runCatching {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "FloatTrans-Updater")
            }
        }.getOrNull() ?: return@withContext null

        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                    output.flush()
                }
            }
            partial.renameTo(target)
        } catch (error: Throwable) {
            runCatching { partial.delete() }
            return@withContext null
        } finally {
            runCatching { connection.disconnect() }
        }

        if (!target.exists() || target.length() == 0L) return@withContext null
        // A proxy/portal page is not an APK; refuse to hand it to the installer.
        val looksLikeApk = runCatching {
            FileInputStream(target).use { stream ->
                val header = ByteArray(2)
                stream.read(header) == 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
            }
        }.getOrDefault(false)
        if (!looksLikeApk) {
            runCatching { target.delete() }
            return@withContext null
        }
        target
    }

    private fun numbers(version: String): List<Int> =
        version.removePrefix("v").removePrefix("V")
            .split('.', '-', '+', '_')
            .mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }

    private fun matchesAbi(name: String): Boolean {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return false
        return when {
            abi == "arm64-v8a" -> name.contains("arm64-v8a", ignoreCase = true)
            abi.startsWith("armeabi") -> name.contains("armeabi-v7a", ignoreCase = true)
            abi.startsWith("x86") -> name.contains("x86_64", ignoreCase = true)
            else -> false
        }
    }

    private fun get(url: String): String? {
        val connection = runCatching {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "FloatTrans-Updater")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
        }.getOrNull() ?: return null
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        } catch (error: Throwable) {
            null
        } finally {
            runCatching { connection.disconnect() }
        }
    }
}
