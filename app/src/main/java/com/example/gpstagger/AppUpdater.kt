package com.example.gpstagger

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object AppUpdater {

    private const val RELEASES_URL =
        "https://api.github.com/repos/ctdenney/OrchardTracker/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class ReleaseInfo(
        val tagName: String,
        val apkUrl: String,
        val body: String
    )

    /** Fetch latest release info from GitHub. Returns null on failure. */
    suspend fun checkForUpdate(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(RELEASES_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val json = JSONObject(response.body!!.string())
            val tagName = json.getString("tag_name")
            val body = json.optString("body", "")

            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }
            if (apkUrl == null) return@withContext null

            ReleaseInfo(tagName, apkUrl, body)
        } catch (_: Exception) {
            null
        }
    }

    /** Compare version strings like "v1.2.0" > "1.1.0". */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        val l = local.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false
    }

    /** Show dialog offering the update, then download + install. */
    fun promptAndInstall(activity: Activity, release: ReleaseInfo) {
        val localVersion = activity.packageManager
            .getPackageInfo(activity.packageName, 0).versionName ?: "0"

        AlertDialog.Builder(activity)
            .setTitle("Update Available")
            .setMessage("A new version (${release.tagName}) is available.\nYou have v$localVersion.\n\n${release.body}")
            .setPositiveButton("Download & Install") { _, _ ->
                downloadAndInstall(activity, release.apkUrl)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadAndInstall(activity: Activity, apkUrl: String) {
        Toast.makeText(activity, "Downloading update…", Toast.LENGTH_SHORT).show()

        (activity as AppCompatActivity).lifecycleScope.launch {
            val destFile = withContext(Dispatchers.IO) {
                try {
                    val updateDir = File(activity.externalCacheDir, "updates")
                    updateDir.mkdirs()
                    updateDir.listFiles()?.forEach { it.delete() }

                    val request = Request.Builder().url(apkUrl).build()
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) return@withContext null

                    val file = File(updateDir, "update.apk")
                    response.body?.byteStream()?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    file
                } catch (_: Exception) {
                    null
                }
            }

            if (destFile != null && destFile.exists()) {
                installApk(activity, destFile)
            } else {
                Toast.makeText(activity, "Download failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun installApk(activity: Activity, file: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }
}
