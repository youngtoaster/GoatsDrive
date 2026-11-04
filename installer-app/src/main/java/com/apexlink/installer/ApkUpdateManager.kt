package com.apexlink.installer

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Manages checking, downloading, and caching both GoatsDrive.apk and CruzeOS-Launcher.apk
 * directly from GitHub Releases (youngtoaster/GoatsDrive).
 *
 * Ensures zero-dependency, pure Android SDK implementation using HttpsURLConnection.
 */
object ApkUpdateManager {

    private const val TAG = "GD_ApkUpdate"
    private const val GITHUB_REPO_API = "https://api.github.com/repos/youngtoaster/GoatsDrive/releases/latest"
    private const val PREFS_NAME = "GoatsUpdatePrefs"

    enum class TargetAsset(
        val assetName: String,
        val internalFileName: String,
        val directFallbackUrl: String,
        val prefsPrefix: String,
        val displayName: String
    ) {
        GOATSDRIVE(
            "GoatsDrive.apk",
            "GoatsDrive-latest.apk",
            "https://github.com/youngtoaster/GoatsDrive/releases/latest/download/GoatsDrive.apk",
            "goatsdrive",
            "GoatsDrive"
        ),
        CRUZEOS(
            "CruzeOS-Launcher.apk",
            "CruzeOS-Launcher-latest.apk",
            "https://github.com/youngtoaster/GoatsDrive/releases/latest/download/CruzeOS-Launcher.apk",
            "cruzeos",
            "CruzeOS 3D Launcher"
        )
    }

    data class ReleaseInfo(
        val tagName: String,
        val downloadUrl: String,
        val sizeBytes: Long,
        val publishedAt: String,
        val target: TargetAsset = TargetAsset.GOATSDRIVE
    )

    /**
     * Queries GitHub REST API asynchronously to find the latest release tag and download URL for the target asset.
     */
    fun checkLatestRelease(target: TargetAsset = TargetAsset.GOATSDRIVE, onResult: (ReleaseInfo?, String?) -> Unit) {
        Thread {
            try {
                val url = URL(GITHUB_REPO_API)
                val conn = url.openConnection() as HttpsURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "GoatsInstaller-Android")
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.connectTimeout = 8000
                conn.readTimeout = 10000

                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    onResult(null, "GitHub API returned HTTP $code")
                    return@Thread
                }

                val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                reader.close()
                conn.disconnect()

                val json = JSONObject(sb.toString())
                val tagName = json.optString("tag_name", "latest")
                val publishedAt = json.optString("published_at", "")
                val assets = json.optJSONArray("assets")

                var downloadUrl = ""
                var apkSize = 0L

                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.equals(target.assetName, ignoreCase = true)) {
                            downloadUrl = asset.optString("browser_download_url", "")
                            apkSize = asset.optLong("size", 0L)
                            break
                        }
                    }
                }

                if (downloadUrl.isEmpty()) {
                    downloadUrl = target.directFallbackUrl
                }

                val info = ReleaseInfo(
                    tagName = tagName,
                    downloadUrl = downloadUrl,
                    sizeBytes = apkSize,
                    publishedAt = publishedAt,
                    target = target
                )
                onResult(info, null)

            } catch (e: Exception) {
                Log.w(TAG, "Failed to query GitHub API for ${target.displayName}: ${e.message}")
                onResult(null, e.message ?: "Connection error")
            }
        }.start()
    }

    /**
     * Checks if the locally stored APK file matches the remote release.
     */
    fun isCachedUpToDate(context: Context, release: ReleaseInfo, target: TargetAsset = release.target): Boolean {
        val file = File(context.filesDir, target.internalFileName)
        if (!file.exists() || file.length() <= 0) return false

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedTag = prefs.getString("${target.prefsPrefix}_cached_tag", "") ?: ""

        if (cachedTag.isNotEmpty() && cachedTag == release.tagName) {
            if (release.sizeBytes > 0 && Math.abs(file.length() - release.sizeBytes) > 1024) {
                return false
            }
            return true
        }
        return false
    }

    /**
     * Downloads the latest APK from the given URL, handling 301/302/307 redirects.
     */
    fun downloadLatestApk(
        context: Context,
        release: ReleaseInfo,
        target: TargetAsset = release.target,
        onProgress: (percent: Int, bytesRead: Long, totalBytes: Long) -> Unit,
        onComplete: (File?, String?) -> Unit
    ) {
        Thread {
            val tmpFile = File(context.filesDir, "${target.internalFileName}.tmp")
            val targetFile = File(context.filesDir, target.internalFileName)

            try {
                var currentUrl = release.downloadUrl
                var redirects = 0
                var conn: HttpURLConnection? = null
                var inStream: InputStream? = null

                while (redirects < 5) {
                    val url = URL(currentUrl)
                    conn = url.openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("User-Agent", "GoatsInstaller-Android")
                    conn.connectTimeout = 10000
                    conn.readTimeout = 15000

                    val status = conn.responseCode
                    if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                        status == HttpURLConnection.HTTP_MOVED_PERM ||
                        status == HttpURLConnection.HTTP_SEE_OTHER ||
                        status == 307
                    ) {
                        val newUrl = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (newUrl != null) {
                            currentUrl = newUrl
                            redirects++
                            continue
                        }
                    }
                    break
                }

                val finalConn = conn ?: throw IOException("Failed to establish HTTP connection")
                val responseCode = finalConn.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP error $responseCode downloading ${target.displayName}")
                }

                val totalLength = if (finalConn.contentLengthLong > 0) {
                    finalConn.contentLengthLong
                } else {
                    release.sizeBytes
                }

                inStream = finalConn.inputStream
                val outStream = FileOutputStream(tmpFile)
                val buffer = ByteArray(32768)
                var bytesReadTotal = 0L
                var read: Int

                while (inStream.read(buffer).also { read = it } != -1) {
                    outStream.write(buffer, 0, read)
                    bytesReadTotal += read
                    if (totalLength > 0) {
                        val pct = ((bytesReadTotal * 100) / totalLength).toInt().coerceIn(0, 100)
                        onProgress(pct, bytesReadTotal, totalLength)
                    }
                }

                outStream.flush()
                outStream.close()
                inStream.close()
                finalConn.disconnect()

                if (tmpFile.length() < 10000) {
                    tmpFile.delete()
                    throw IOException("Downloaded APK is too small or corrupt (${tmpFile.length()} bytes)")
                }

                // Atomic replace
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                if (!tmpFile.renameTo(targetFile)) {
                    tmpFile.copyTo(targetFile, overwrite = true)
                    tmpFile.delete()
                }

                // Persist cache metadata
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString("${target.prefsPrefix}_cached_tag", release.tagName)
                    .putLong("${target.prefsPrefix}_cached_size", targetFile.length())
                    .apply()

                Log.i(TAG, "Successfully downloaded & cached ${target.displayName} (${targetFile.length()} bytes)")
                onComplete(targetFile, null)

            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}", e)
                tmpFile.delete()
                onComplete(null, e.message ?: "Download failed")
            }
        }.start()
    }

    /**
     * Resolves the best available APK binary:
     * 1. Cached downloaded latest release from internal storage
     * 2. Embedded APK from app assets (target.assetName)
     * Returns ByteArray payload.
     */
    fun getBestApkBytes(context: Context, target: TargetAsset = TargetAsset.GOATSDRIVE, logCallback: (String) -> Unit): ByteArray {
        // Priority 1: Cached latest downloaded APK
        val cachedFile = File(context.filesDir, target.internalFileName)
        if (cachedFile.exists() && cachedFile.length() > 50000) {
            return try {
                val bytes = cachedFile.readBytes()
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val tag = prefs.getString("${target.prefsPrefix}_cached_tag", "latest")
                logCallback("📦 Using latest downloaded ${target.displayName} build [$tag] (${bytes.size / 1024} KB)")
                bytes
            } catch (e: Exception) {
                logCallback("⚠️ Error reading cached ${target.displayName}: ${e.message}. Falling back to assets.")
                loadFromAssets(context, target, logCallback)
            }
        }

        // Priority 2: Bundled assets
        return loadFromAssets(context, target, logCallback)
    }

    private fun loadFromAssets(context: Context, target: TargetAsset, logCallback: (String) -> Unit): ByteArray {
        return try {
            val inStream = context.assets.open(target.assetName)
            val byteOut = ByteArrayOutputStream()
            val buf = ByteArray(16384)
            var r: Int
            while (inStream.read(buf).also { r = it } != -1) {
                byteOut.write(buf, 0, r)
            }
            inStream.close()
            val bytes = byteOut.toByteArray()
            logCallback("📦 Using bundled ${target.displayName} build from assets (${bytes.size / 1024} KB)")
            bytes
        } catch (e: Exception) {
            logCallback("❌ No ${target.assetName} found in assets or cache!")
            ByteArray(0)
        }
    }

    /**
     * Returns description of currently installed/cached build.
     */
    fun getStatusDescription(context: Context, target: TargetAsset = TargetAsset.GOATSDRIVE): String {
        val cachedFile = File(context.filesDir, target.internalFileName)
        if (cachedFile.exists() && cachedFile.length() > 50000) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val tag = prefs.getString("${target.prefsPrefix}_cached_tag", "latest")
            return "$tag (Cached: ${cachedFile.length() / 1024} KB)"
        }
        return try {
            val afd = context.assets.openFd(target.assetName)
            val size = afd.length
            afd.close()
            "v1.0.0 (Embedded: ${size / 1024} KB)"
        } catch (e: Exception) {
            "v1.0.0 (Ready)"
        }
    }
}
