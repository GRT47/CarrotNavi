package com.example.carrotnavi

import android.app.AlertDialog
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
import android.widget.ProgressBar
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
    private const val GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/GRT47/CarrotNavi/releases/latest"

    fun checkForUpdates(context: Context, isManual: Boolean = false, useServer: Boolean = false) {
        if (isManual) {
            Toast.makeText(context, "업데이트를 확인하는 중...", Toast.LENGTH_SHORT).show()
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(GITHUB_LATEST_RELEASE_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val tagName = json.getString("tag_name").replace("v", "") // e.g. "1.0.1"
                    
                    val currentVersion = BuildConfig.VERSION_NAME

                    if (isNewerVersion(currentVersion, tagName)) {
                        var downloadUrl = ""
                        if (useServer) {
                            downloadUrl = "https://comma_nav_guide.leegrt.org/apk/CommaNav.apk"
                        } else {
                            val assets = json.getJSONArray("assets")
                            if (assets.length() > 0) {
                                for (i in 0 until assets.length()) {
                                    val asset = assets.getJSONObject(i)
                                    if (asset.getString("name").endsWith(".apk")) {
                                        downloadUrl = asset.getString("browser_download_url")
                                        break
                                    }
                                }
                            }
                        }
                        
                        if (downloadUrl.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                showUpdateDialog(context, tagName, downloadUrl)
                            }
                        }
                    } else {
                        if (isManual) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "현재 최신 버전입니다.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    if (isManual) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "업데이트 서버에 연결할 수 없습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
                if (isManual) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "업데이트 확인 중 오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun isNewerVersion(current: String, remote: String): Boolean {
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val remoteParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
        
        for (i in 0 until maxOf(currentParts.size, remoteParts.size)) {
            val c = currentParts.getOrElse(i) { 0 }
            val r = remoteParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (c > r) return false
        }
        return false
    }

    private fun showUpdateDialog(context: Context, newVersion: String, downloadUrl: String) {
        AlertDialog.Builder(context)
            .setTitle("새로운 업데이트 발견")
            .setMessage("최신 버전($newVersion)이 등록되었습니다.\n지금 업데이트 하시겠습니까?")
            .setPositiveButton("업데이트 진행") { _, _ ->
                downloadAndInstall(context, downloadUrl, newVersion)
            }
            .setNegativeButton("나중에") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun downloadAndInstall(context: Context, downloadUrl: String, version: String) {
        val fileName = "CarrotNavi_$version.apk"
        val destinationDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val destinationFile = File(destinationDir, fileName)

        if (destinationFile.exists()) {
            destinationFile.delete()
        }

        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("CarrotNavi 업데이트 다운로드 중")
            .setDescription("버전 $version 다운로드 중입니다.")
            .setDestinationUri(Uri.fromFile(destinationFile))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        // Show progress dialog
        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            setPadding(50, 20, 50, 20)
        }
        val progressDialog = AlertDialog.Builder(context)
            .setTitle("업데이트 다운로드 중")
            .setMessage("준비 중...")
            .setView(progressBar)
            .setCancelable(false)
            .create()
        progressDialog.show()

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(ctxt: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    installApk(context, destinationFile)
                    try {
                        context.unregisterReceiver(this)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }

        // Track progress
        CoroutineScope(Dispatchers.IO).launch {
            var downloading = true
            while (downloading) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                if (cursor.moveToFirst()) {
                    val bytesDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val bytesTotalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)

                    if (statusIndex >= 0 && bytesDownloadedIndex >= 0 && bytesTotalIndex >= 0) {
                        val status = cursor.getInt(statusIndex)
                        val bytesDownloaded = cursor.getLong(bytesDownloadedIndex)
                        val bytesTotal = cursor.getLong(bytesTotalIndex)

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            downloading = false
                            withContext(Dispatchers.Main) {
                                progressBar.progress = 100
                                progressDialog.setMessage("다운로드 완료. 설치를 시작합니다.")
                            }
                            kotlinx.coroutines.delay(1000)
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            downloading = false
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "다운로드 실패", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            if (bytesTotal > 0) {
                                val progress = (bytesDownloaded * 100 / bytesTotal).toInt()
                                val downloadedMb = String.format("%.1f", bytesDownloaded / (1024.0 * 1024.0))
                                val totalMb = String.format("%.1f", bytesTotal / (1024.0 * 1024.0))
                                withContext(Dispatchers.Main) {
                                    progressBar.progress = progress
                                    progressDialog.setMessage("$downloadedMb MB / $totalMb MB ($progress%)")
                                }
                            }
                        }
                    }
                }
                cursor.close()
                if (downloading) kotlinx.coroutines.delay(500)
            }
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
            }
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists()) return

        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile),
                    "application/vnd.android.package-archive"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "업데이트 설치에 실패했습니다.", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Install Failed", e)
        }
    }
}
