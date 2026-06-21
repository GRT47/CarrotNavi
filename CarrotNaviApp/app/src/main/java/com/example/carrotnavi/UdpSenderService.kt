package com.example.carrotnavi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer
import com.google.gson.Gson
import com.tmapmobility.tmap.tmapsdk.ui.util.TmapUISDK
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import android.os.PowerManager
import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper

class UdpSenderService : Service() {

    private val CHANNEL_ID = "CarrotNaviChannel"
    private val UDP_PORT = 7706
    private var targetIp = "255.255.255.255"

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var udpSocket: DatagramSocket? = null

    private val isRunning = AtomicBoolean(false)
    private val gson = Gson()
    
    private var packetIndex = 0
    private var wakeLock: PowerManager.WakeLock? = null

    // Latest Safe Drive Info
    private var roadLimitSpeed = 0
    private var sdiType = 0
    private var sdiSpeedLimit = 0
    private var sdiDistance = 0
    private var sdiBlockType = 0
    private var sdiBlockSpeed = 0
    private var sdiBlockDist = 0

    private var locationManager: LocationManager? = null

    @Volatile private var lastLat = 0.0
    @Volatile private var lastLon = 0.0
    @Volatile private var lastSpeedMps = 0f
    @Volatile private var hasFix = false

    private val locationListener = LocationListener { loc ->
        lastLat = loc.latitude
        lastLon = loc.longitude
        lastSpeedMps = if (loc.hasSpeed()) loc.speed else 0f
        hasFix = true
    }

    private var gnssCallback: android.location.GnssStatus.Callback? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, createNotification("GPS: 탐색 중"), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, createNotification("GPS: 탐색 중"))
        }
        
        try {
            udpSocket = DatagramSocket()
            udpSocket?.broadcast = true
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CarrotNavi::UdpSenderWakelock")
            wakeLock?.acquire()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        startDirectGps()
        isRunning.set(true)
    }

    @SuppressLint("MissingPermission")
    private fun startDirectGps() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                locationListener,
                Looper.getMainLooper()
            )

            gnssCallback = object : android.location.GnssStatus.Callback() {
                override fun onStarted() {
                    updateNotification("GPS: 탐색 중")
                }
                override fun onStopped() {
                    updateNotification("GPS: 끊김 (NO_SIGNAL)")
                }
                override fun onFirstFix(ttffMillis: Int) {
                    updateNotification("GPS: 수신 양호")
                }
                override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                    var usedInFix = 0
                    for (i in 0 until status.satelliteCount) {
                        if (status.usedInFix(i)) usedInFix++
                    }
                    if (usedInFix >= 4) {
                        updateNotification("GPS: 수신 양호 (위성 $usedInFix 개)")
                    } else {
                        updateNotification("GPS: BAD (위성 $usedInFix 개)")
                    }
                }
            }
            locationManager?.registerGnssStatusCallback(gnssCallback!!, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun stopDirectGps() {
        try { locationManager?.removeUpdates(locationListener) } catch (_: Exception) {}
        try { gnssCallback?.let { locationManager?.unregisterGnssStatusCallback(it) } } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sharedPref = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
        targetIp = sharedPref.getString("TARGET_IP", "255.255.255.255") ?: "255.255.255.255"

        startObservingEDC()
        startSendingLoop()

        return START_STICKY
    }

    private val edcObserver = Observer<Bundle> { bundle ->
        if (bundle != null && isRunning.get()) {
            serviceScope.launch {
                try {
                    val json = JSONObject()
                    json.put("carrotIndex", packetIndex++)
                    json.put("navitype", "tmap")
                    
                    // 1. limitSpeed 처리
                    val limitSpeedStr = bundle.getString("limitSpeed", bundle.getInt("limitSpeed", 0).toString())
                    val currentLimitSpeed = limitSpeedStr.toIntOrNull() ?: 0
                    if (currentLimitSpeed > 0) {
                        roadLimitSpeed = currentLimitSpeed
                    }
                    
                    // 2. firstSDIInfo (GRT47과 동일하게 모든 key를 최상위로 복사)
                    val sdiObj = bundle.get("firstSDIInfo")
                    if (sdiObj != null) {
                        val sdiJsonStr = if (sdiObj is String) sdiObj else gson.toJson(sdiObj)
                        val sdiJson = JSONObject(sdiJsonStr)
                        
                        val keys = sdiJson.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            json.put(k, sdiJson.get(k))
                        }
                        
                        // Fallback logic for point camera (사용자 피드백 반영)
                        val sdiType = json.optInt("nSdiType", 0)
                        val sdiSpeedLimit = json.optInt("nSdiSpeedLimit", 0)
                        val sdiDist = json.optInt("nSdiDist", 0)
                        
                        if (sdiSpeedLimit > 0) {
                            roadLimitSpeed = sdiSpeedLimit
                        }
                        
                        if (sdiType == 0 && sdiSpeedLimit > 0 && sdiDist > 0) {
                            json.put("nSdiType", 1) // 강제로 1로 세팅
                        }
                        
                        // SDI 이벤트 발생 시 TBT 패널을 이벤트 거리/직진으로 덮어쓰기
                        val finalSdiType = json.optInt("nSdiType", 0)
                        if (finalSdiType > 0 && sdiDist > 0) {
                            json.put("nTBTDist", sdiDist)
                            json.put("nTBTTurnType", 1) // 1: 직진 (Straight)
                        }
                    }
                    
                    json.put("nRoadLimitSpeed", roadLimitSpeed)
                    
                    // 3. secondSDIInfo (GRT47과 동일하게 nSdiPlus... 접두어로 추가)
                    val sdiPlusObj = bundle.get("secondSDIInfo")
                    if (sdiPlusObj != null) {
                        val plusJsonStr = if (sdiPlusObj is String) sdiPlusObj else gson.toJson(sdiPlusObj)
                        val plusJson = JSONObject(plusJsonStr)
                        
                        if (plusJson.has("nSdiType")) json.put("nSdiPlusType", plusJson.get("nSdiType"))
                        if (plusJson.has("nSdiSpeedLimit")) json.put("nSdiPlusSpeedLimit", plusJson.get("nSdiSpeedLimit"))
                        if (plusJson.has("nSdiDist")) json.put("nSdiPlusDist", plusJson.get("nSdiDist"))
                        if (plusJson.has("nSdiSection")) json.put("nSdiPlusBlockType", plusJson.get("nSdiSection"))
                        if (plusJson.has("nSdiBlockSpeed")) json.put("nSdiPlusBlockSpeed", plusJson.get("nSdiBlockSpeed"))
                        if (plusJson.has("nSdiBlockDist")) json.put("nSdiPlusBlockDist", plusJson.get("nSdiBlockDist"))
                    }

                    latestPayload = json.toString()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    @Volatile
    private var latestPayload = "{}"

    private fun startObservingEDC() {
        CoroutineScope(Dispatchers.Main).launch {
            TmapUISDK.observableEDCData.observeForever(edcObserver)
        }
    }

    private fun startSendingLoop() {
        serviceScope.launch {
            while (isActive) {
                sendSdiData()
                delay(300) // 약 3.3Hz heartbeat
            }
        }
    }

    private fun sendSdiData() {
        if (latestPayload == "{}" && !hasFix) return
        try {
            val obj = try { JSONObject(latestPayload) } catch (e: Exception) { JSONObject() }
            
            // 상시 전송
            obj.put("szTBTMainText", "Comma NAV 안심주행 모드")
            
            if (hasFix) {
                obj.put("vpPosPointLat", lastLat)
                obj.put("vpPosPointLon", lastLon)
                obj.put("vEgoKph", (lastSpeedMps * 3.6).toInt())
            }
            val buffer = obj.toString().toByteArray(Charsets.UTF_8)
            
            // GRT47의 UDP 전송 로직 유지 (127.0.0.1, 255.255.255.255 및 모든 인터페이스 브로드캐스트)
            val targetAddresses = mutableSetOf<InetAddress>()
            targetAddresses.add(InetAddress.getByName("127.0.0.1"))
            
            if (targetIp == "255.255.255.255") {
                targetAddresses.add(InetAddress.getByName("255.255.255.255"))
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    if (networkInterface.isLoopback || !networkInterface.isUp) continue
                    for (interfaceAddress in networkInterface.interfaceAddresses) {
                        interfaceAddress.broadcast?.let { targetAddresses.add(it) }
                    }
                }
            } else {
                targetAddresses.add(InetAddress.getByName(targetIp))
            }

            for (address in targetAddresses) {
                val packet = DatagramPacket(buffer, buffer.size, address, UDP_PORT)
                udpSocket?.send(packet)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning.set(false)
        stopDirectGps()
        CoroutineScope(Dispatchers.Main).launch {
            TmapUISDK.observableEDCData.removeObserver(edcObserver)
        }
        serviceScope.cancel()
        udpSocket?.close()
        
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CarrotNavi UDP Sender",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(gpsStatus: String): android.app.Notification {
        val toggleIntent = Intent("com.example.carrotnavi.ACTION_TOGGLE_POWER_SAVING")
        toggleIntent.setPackage(packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CarrotNavi 실행 중")
            .setContentText("UDP 송신 중... ($gpsStatus)")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOnlyAlertOnce(true)
            .addAction(0, "화면꺼짐", pendingIntent)
            .build()
    }

    private fun updateNotification(gpsStatus: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(1, createNotification(gpsStatus))
    }
}
