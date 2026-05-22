package com.example.tmapbridge

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object AutoUpdater {
    private const val TAG = "AutoUpdater"
    private const val GITHUB_API_URL = "https://api.github.com/repos/GRT47/CarrotNavi/releases/latest"

    fun checkForUpdates(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(GITHUB_API_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonObject = JSONObject(response)
                    val latestVersion = jsonObject.getString("tag_name").replace("v", "")
                    
                    val currentVersion = BuildConfig.VERSION_NAME

                    Log.d(TAG, "Current Version: $currentVersion, Latest Version: $latestVersion")

                    if (isNewerVersion(currentVersion, latestVersion)) {
                        val assets = jsonObject.getJSONArray("assets")
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            if (asset.getString("name").endsWith(".apk")) {
                                val downloadUrl = asset.getString("browser_download_url")
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "새 업데이트 다운로드 중: v$latestVersion", Toast.LENGTH_LONG).show()
                                }
                                downloadAndInstallApk(context, downloadUrl, "app-update.apk")
                                break
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Log.d(TAG, "Already up to date")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for updates", e)
            }
        }
    }

    private fun isNewerVersion(current: String, latest: String): Boolean {
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
        
        val maxLen = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until maxLen) {
            val c = currentParts.getOrNull(i) ?: 0
            val l = latestParts.getOrNull(i) ?: 0
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    private fun downloadAndInstallApk(context: Context, apkUrl: String, fileName: String) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = Uri.parse(apkUrl)

        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (file.exists()) {
            file.delete()
        }

        val request = DownloadManager.Request(uri)
            .setTitle("TmapBridge 업데이트")
            .setDescription("새로운 버전을 다운로드 중입니다.")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)

        val downloadId = downloadManager.enqueue(request)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(ctxt: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    installApk(context, file)
                    context.unregisterReceiver(this)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun installApk(context: Context, file: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            Toast.makeText(context, "업데이트 설치 창을 여는데 실패했습니다.", Toast.LENGTH_SHORT).show()
        }
    }
}
