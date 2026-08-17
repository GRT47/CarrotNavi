package com.example.carrotnavi

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RemoteLogManager {
    private const val TAG = "RemoteLogManager"
    private var LOG_SERVER_URL = "http://127.0.0.1:5000"
    private var deviceId: String = "unknown"
    var isLoggingEnabled: Boolean = false
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("LOG_SERVER_URL", "") ?: ""
        if (serverUrl.isNotEmpty()) {
            LOG_SERVER_URL = if (serverUrl.startsWith("http")) serverUrl else "http://$serverUrl"
        } else {
            // Fallback to TARGET_UDP_IP if LOG_SERVER_URL is not set (for backward compatibility, but won't send if empty)
            val targetIp = prefs.getString("TARGET_UDP_IP", "192.168.43.1") ?: "192.168.43.1"
            LOG_SERVER_URL = "http://$targetIp:5000"
        }

        deviceId = prefs.getString("DEVICE_ID", null) ?: generateDeviceId(context).also {
            prefs.edit().putString("DEVICE_ID", it).apply()
        }
        
        checkConfig()
        
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            sendLogSync("FATAL", "Uncaught Exception", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    @SuppressLint("HardwareIds")
    private fun generateDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val randomStr = (1..5).map { chars.random() }.joinToString("")
        return "${androidId}_$randomStr"
    }

    fun checkConfig() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("$LOG_SERVER_URL/api/config?device_id=$deviceId")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    isLoggingEnabled = json.optBoolean("logging_enabled", false)
                    Log.d(TAG, "Logging enabled: $isLoggingEnabled")
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.d(TAG, "Failed to check config: ${e.message}")
            }
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        sendLog("ERROR", "[$tag] $message", throwable)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        sendLog("WARN", "[$tag] $message", null)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        sendLog("INFO", "[$tag] $message", null)
    }

    private fun sendLog(level: String, message: String, throwable: Throwable?) {
        if (!isLoggingEnabled) return
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendLogSync(level, message, throwable)
            } catch (e: Exception) {
                // Ignore failure in background
            }
        }
    }

    private fun sendLogSync(level: String, message: String, throwable: Throwable?) {
        try {
            val url = URL("$LOG_SERVER_URL/api/logs")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 3000
            connection.readTimeout = 3000

            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
            
            val json = JSONObject().apply {
                put("device_id", deviceId)
                put("level", level)
                put("message", message)
                put("app_version", "2.0.10") 
                put("timestamp", sdf.format(Date()))
                if (throwable != null) {
                    put("stacktrace", Log.getStackTraceString(throwable))
                }
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(json.toString())
            }

            connection.responseCode
            connection.disconnect()
        } catch (e: Exception) {
            // Do not log to avoid infinite loop
        }
    }
}
