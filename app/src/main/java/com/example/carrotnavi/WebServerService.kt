package com.example.carrotnavi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.IOException

class WebServerService : Service() {

    private val CHANNEL_ID = "WebServerChannel"
    private var webServer: AppWebServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(2, createNotification())
        }

        webServer = AppWebServer(this, 8080)
        try {
            webServer?.start()
            android.util.Log.d("WebServerService", "Web server started on port 8080")
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        webServer?.stop()
        android.util.Log.d("WebServerService", "Web server stopped")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Web Server Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("원격 설정 서버 실행 중")
            .setContentText("같은 Wi-Fi 내 브라우저에서 설정 페이지에 접속 가능합니다.")
            .setSmallIcon(R.mipmap.ic_launcher) // TODO: Check if ic_launcher exists or use a generic icon
            .build()
    }
}
