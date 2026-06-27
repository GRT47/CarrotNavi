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
    private var nsdManager: android.net.nsd.NsdManager? = null
    private var registrationListener: android.net.nsd.NsdManager.RegistrationListener? = null
    private val SERVICE_NAME = "carrotnavi"
    private val SERVICE_TYPE = "_http._tcp."

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
            registerService(8080)
            startIpReporting()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun registerService(port: Int) {
        val serviceInfo = android.net.nsd.NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        nsdManager = getSystemService(android.content.Context.NSD_SERVICE) as android.net.nsd.NsdManager

        registrationListener = object : android.net.nsd.NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: android.net.nsd.NsdServiceInfo) {
                android.util.Log.d("WebServerService", "mDNS Service registered: ${NsdServiceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {
                android.util.Log.e("WebServerService", "mDNS Registration failed: $errorCode")
            }

            override fun onServiceUnregistered(arg0: android.net.nsd.NsdServiceInfo) {
                android.util.Log.d("WebServerService", "mDNS Service unregistered: ${arg0.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {
                android.util.Log.e("WebServerService", "mDNS Unregistration failed: $errorCode")
            }
        }

        nsdManager?.registerService(
            serviceInfo, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, registrationListener
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        webServer?.stop()
        registrationListener?.let {
            nsdManager?.unregisterService(it)
        }
        reportTimer?.cancel()
        reportTimer = null
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
            .setContentText("http://carrotnavi.local:8080/ (또는 표시된 IP) 로 접속하세요.")
            .setSmallIcon(R.mipmap.ic_launcher) // TODO: Check if ic_launcher exists or use a generic icon
            .build()
    }

    private var reportTimer: java.util.Timer? = null

    private fun startIpReporting() {
        val sharedPref = getSharedPreferences("CarrotNaviPrefs", android.content.Context.MODE_PRIVATE)
        val serverUrl = sharedPref.getString("IP_REPORT_SERVER_URL", "")?.trim()
        val deviceId = sharedPref.getString("DEVICE_ID", "carrot")?.trim()

        if (serverUrl.isNullOrEmpty()) return

        val targetUrl = if (serverUrl.endsWith("/")) "${serverUrl}api/update_ip" else "$serverUrl/api/update_ip"

        reportTimer = java.util.Timer()
        reportTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                try {
                    val ipAddress = getLocalIpAddress() ?: return
                    val json = org.json.JSONObject().apply {
                        put("device_id", deviceId)
                        put("local_ip", ipAddress)
                        put("port", 8080)
                    }

                    val url = java.net.URL(targetUrl)
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    conn.doOutput = true
                    
                    conn.outputStream.use { os ->
                        val input = json.toString().toByteArray(Charsets.UTF_8)
                        os.write(input, 0, input.size)
                    }

                    val responseCode = conn.responseCode
                    android.util.Log.d("WebServerService", "Reported IP: $ipAddress to $targetUrl, code: $responseCode")
                    conn.disconnect()
                } catch (e: Exception) {
                    android.util.Log.e("WebServerService", "Failed to report IP", e)
                }
            }
        }, 0, 60000) // Report every 60 seconds
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
