package com.lifeos.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

private data class GithubAsset(val name: String, val browser_download_url: String)
private data class GithubRelease(val tag_name: String, val assets: List<GithubAsset>)

class UpdateChecker(private val context: Context, private val repo: String) {
    data class UpdateInfo(val version: String, val downloadUrl: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    /** Blocking call - run on a background dispatcher. Returns null if no newer release exists. */
    fun checkLatest(currentVersion: String): UpdateInfo? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val release = gson.fromJson(body, GithubRelease::class.java)
            val remoteVersion = release.tag_name.removePrefix("v")
            if (!isNewer(remoteVersion, currentVersion)) return null
            val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") } ?: return null
            return UpdateInfo(remoteVersion, apkAsset.browser_download_url)
        }
    }

    private fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    /** Blocking call - run on a background dispatcher. */
    fun downloadApk(info: UpdateInfo): Uri? {
        val request = Request.Builder().url(info.downloadUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
            val file = File(dir, "lifeos-${info.version}.apk")
            response.body?.byteStream()?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
    }

    fun installIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
}
