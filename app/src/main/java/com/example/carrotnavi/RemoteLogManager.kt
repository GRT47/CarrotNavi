package com.example.carrotnavi

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
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

    private var logcatJob: Job? = null
    private var pollingJob: Job? = null
    private val logBuffer = mutableListOf<JSONObject>()
    private val logBufferLock = Any()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
        LOG_SERVER_URL = "https://comma-nav-server.leegrt.org"

        deviceId = prefs.getString("DEVICE_ID", null) ?: generateDeviceId().also {
            prefs.edit().putString("DEVICE_ID", it).apply()
        }
        
        startPollingConfig()
        
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("FATAL", "Uncaught Exception", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun getDeviceId(): String {
        return deviceId
    }

    fun regenerateDeviceId(): String {
        val newId = generateDeviceId()
        deviceId = newId
        prefs.edit().putString("DEVICE_ID", newId).apply()
        return newId
    }

    private fun generateDeviceId(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..5).map { chars.random() }.joinToString("")
    }

    private fun startPollingConfig() {
        pollingJob?.cancel()
        pollingJob = CoroutineScope(Dispatchers.IO).launch {
            // First check config immediately
            checkConfigSync()
            while (isActive) {
                delay(10000) // check every 10 seconds
                checkConfigSync()
            }
        }
    }

    private fun checkConfigSync() {
        try {
            val url = URL("$LOG_SERVER_URL/api/config?device_id=$deviceId&app_version=${BuildConfig.VERSION_NAME}")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val newEnabled = json.optBoolean("logging_enabled", false)
                
                // If status changed or if it's enabled and logcat is not running
                if (newEnabled != isLoggingEnabled || (newEnabled && logcatJob?.isActive != true)) {
                    isLoggingEnabled = newEnabled
                    Log.d(TAG, "Logging enabled changed to: $isLoggingEnabled")
                    if (isLoggingEnabled) {
                        startLogcatCapture()
                    } else {
                        stopLogcatCapture()
                    }
                }
            }
            connection.disconnect()
        } catch (e: Exception) {
            // ignore
        }
    }

    fun checkConfig() {
        CoroutineScope(Dispatchers.IO).launch {
            checkConfigSync()
        }
    }

    private fun startLogcatCapture() {
        if (logcatJob?.isActive == true) return
        
        logcatJob = CoroutineScope(Dispatchers.IO).launch {
            var process: Process? = null
            try {
                // Clear existing logs to avoid sending old logs
                Runtime.getRuntime().exec("logcat -c").waitFor()
                
                val myPid = android.os.Process.myPid()
                process = Runtime.getRuntime().exec("logcat -v threadtime --pid=$myPid")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                
                var lastSendTime = System.currentTimeMillis()
                
                while (isActive) {
                    val line = reader.readLine()
                    if (line != null) {
                        var level = "INFO"
                        if (line.contains(" W ")) level = "WARN"
                        else if (line.contains(" E ")) level = "ERROR"
                        else if (line.contains(" F ")) level = "FATAL"
                        else if (line.contains(" D ")) level = "DEBUG"
                        else if (line.contains(" V ")) level = "VERBOSE"
                        
                        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
                        val logObj = JSONObject().apply {
                            put("level", level)
                            put("message", line)
                            put("app_version", BuildConfig.VERSION_NAME)
                            put("timestamp", sdf.format(Date()))
                        }
                        
                        var shouldSend = false
                        synchronized(logBufferLock) {
                            logBuffer.add(logObj)
                            if (logBuffer.size >= 50 || System.currentTimeMillis() - lastSendTime >= 1000) {
                                shouldSend = true
                            }
                        }
                        
                        if (shouldSend) {
                            sendBatch()
                            lastSendTime = System.currentTimeMillis()
                        }
                    } else {
                        // No more lines, wait a bit
                        delay(100)
                    }
                }
            } catch (e: Exception) {
                // Ignore
            } finally {
                process?.destroy()
            }
        }
    }

    private fun stopLogcatCapture() {
        logcatJob?.cancel()
        logcatJob = null
        synchronized(logBufferLock) {
            logBuffer.clear()
        }
    }

    private fun sendBatch() {
        val batch = mutableListOf<JSONObject>()
        synchronized(logBufferLock) {
            if (logBuffer.isEmpty()) return
            batch.addAll(logBuffer)
            logBuffer.clear()
        }
        
        try {
            val url = URL("$LOG_SERVER_URL/api/logs/batch")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 3000
            connection.readTimeout = 3000

            val jsonArray = JSONArray()
            for (obj in batch) {
                jsonArray.put(obj)
            }
            
            val payload = JSONObject().apply {
                put("device_id", deviceId)
                put("logs", jsonArray)
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(payload.toString())
            }

            connection.responseCode
            connection.disconnect()
        } catch (e: Exception) {
            // Do not print anything here to avoid infinite logcat loop
        }
    }

    // Keep compatibility for existing code that uses e, w, i
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }
}
