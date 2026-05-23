package com.example.tmapbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import androidx.lifecycle.Observer
import com.tmapmobility.tmap.tmapsdk.ui.util.TmapUISDK
import com.tmapmobility.tmap.tmapsdk.ui.data.ObservableRouteData
import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager

class TmapService : Service() {

    private val CHANNEL_ID = "TmapBridgeChannel"
    private val TAG = "TmapService"
    private val OPENPILOT_PORT = 7706
    
    // Broadcast IP
    private val BROADCAST_IP = "255.255.255.255" 

    private var isRunning = AtomicBoolean(false)
    private val gson = Gson()
    private var udpSocket: DatagramSocket? = null
    
    private var locationManager: LocationManager? = null

    // Latest Safe Drive Info (From TMAP EDC)
    private var nRoadLimitSpeed = 0
    private var nSdiType = -1
    private var nSdiSpeedLimit = 0
    private var nSdiDist = -1
    private var nSdiBlockType = -1
    private var nTBTDist = 0
    private var nTBTTurnType = -1
    private var szTBTMainText = ""
    private var nGoPosDist = 0
    
    // Latest Location Info (From Phone GPS)
    private var vpPosPointLat = 0.0
    private var vpPosPointLon = 0.0
    private var nPosAngle = 0f
    private var nPosSpeed = 0f
    
    // Debug Bundle
    private var rawBundleMap: Map<String, Any> = emptyMap()
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createNotification())
        
        try {
            udpSocket = DatagramSocket()
            udpSocket?.broadcast = true
        } catch (e: Exception) {
            Log.e(TAG, "Socket creation failed", e)
        }
        
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        isRunning.set(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startObservingEDC()
        return START_STICKY // Ensures the service stays running
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning.set(false)
        udpSocket?.close()
        
        // Stop Safe Drive Mode and unregister observer
        TmapUISDK.observableEDCData.removeObserver(edcObserver)
        locationManager?.removeUpdates(locationListener)
        TmapUISDK.Companion.finish()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "안전운행모드 서비스",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("안전운행모드")
            .setContentText("오픈파일럿에 주행 정보를 송신 중입니다.")
            .setSmallIcon(R.mipmap.ic_launcher) // Make sure you have this icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private val edcObserver = Observer<Bundle> { bundle ->
        if (bundle != null && isRunning.get()) {
            
            // 1. 기본 도로 제한 속도
            nRoadLimitSpeed = bundle.getInt("limitSpeed", 0) 
            
            // 2. SDI(안전운행) 정보 추출
            val sdiObj = bundle.get("firstSDIInfo")
            if (sdiObj != null) {
                try {
                    val sdiStr = gson.toJson(sdiObj)
                    val sdiJson = org.json.JSONObject(sdiStr)
                    
                    nSdiType = sdiJson.optInt("nSdiType", -1)
                    nSdiSpeedLimit = sdiJson.optInt("nSdiSpeedLimit", 0)
                    nSdiDist = sdiJson.optInt("nSdiDist", -1)
                    // 블록/구간 단속 변수들 매핑 (Openpilot 필수)
                    nSdiBlockType = sdiJson.optInt("nSdiSection", sdiJson.optInt("nSdiBlockType", -1))
                } catch (e: Exception) {
                    Log.e(TAG, "SDI 파싱 에러", e)
                }
            } else {
                nSdiType = -1
                nSdiSpeedLimit = 0
                nSdiDist = -1
                nSdiBlockType = -1
            }

            // 3. TBT(경로/방향) 정보 추출 (Tmap SDK 2.x 에서는 TBT 정보가 다른 Key로 올 수 있음)
            val tbtObj = bundle.get("firstTBTInfo") ?: bundle.get("tbtInfo")
            if (tbtObj != null) {
                try {
                    val tbtStr = gson.toJson(tbtObj)
                    val tbtJson = org.json.JSONObject(tbtStr)
                    nTBTDist = tbtJson.optInt("nTbtDist", tbtJson.optInt("tbtDist", 0))
                    nTBTTurnType = tbtJson.optInt("nTurnType", tbtJson.optInt("turnType", -1))
                    szTBTMainText = tbtJson.optString("szMainText", tbtJson.optString("szTBTMainText", ""))
                } catch (e: Exception) {}
            }
            
            nGoPosDist = bundle.getInt("remainDistanceToGoPositionInMeter", 0)
            
            // 로그 기록 (UI 확인용)
            TmapDataManager.addLog("EDC: nRoadLimit=$nRoadLimitSpeed, nSdiSpeed=$nSdiSpeedLimit, nSdiDist=$nSdiDist")
            
            sendCurrentData()
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (isRunning.get()) {
                vpPosPointLat = location.latitude
                vpPosPointLon = location.longitude
                nPosAngle = location.bearing
                
                // location.speed is in m/s. Openpilot/TMAP uses km/h for speed values usually.
                nPosSpeed = location.speed * 3.6f
                
                sendCurrentData()
                
                // Update UI State on Main Thread
                CoroutineScope(Dispatchers.Main).launch {
                    TmapDataManager.driveData.value = DriveData(
                        speed = nPosSpeed.toInt(),
                        speedLimit = nSdiSpeedLimit,
                        sdiType = nSdiType,
                        sdiDist = nSdiDist
                    )
                }
            }
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    private fun startObservingEDC() {
        TmapUISDK.observableEDCData.observeForever(edcObserver)
        
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L, // Request updates every 1 second
                0f,    // regardless of distance
                locationListener
            )
        } catch (e: Exception) {
            Log.e(TAG, "Location request failed", e)
        }
    }

    private fun sendCurrentData() {
        val dataMap = mutableMapOf<String, Any>()
        if (nRoadLimitSpeed > 0) {
            dataMap["nRoadLimitSpeed"] = nRoadLimitSpeed
        }
        dataMap["nSdiType"] = nSdiType
        dataMap["nSdiSpeedLimit"] = nSdiSpeedLimit
        dataMap["nSdiDist"] = nSdiDist
        dataMap["nSdiBlockType"] = nSdiBlockType
        dataMap["nTBTDist"] = nTBTDist
        dataMap["nTBTTurnType"] = nTBTTurnType
        dataMap["szTBTMainText"] = szTBTMainText
        dataMap["nGoPosDist"] = nGoPosDist
        dataMap["vpPosPointLat"] = vpPosPointLat
        dataMap["vpPosPointLon"] = vpPosPointLon
        dataMap["nPosAngle"] = nPosAngle
        dataMap["nPosSpeed"] = nPosSpeed
        
        // Openpilot이 요구하는 추가 변수들 자동 매핑
        val bundle = TmapUISDK.observableEDCData.value
        if (bundle != null) {
            dataMap["roadcate"] = bundle.getInt("roadType", 8) // 기본값 8(일반도로)로 설정해 방지턱 무시 방지
            
            // firstSDIInfo의 모든 키를 루트로 복사 (nSdiBlockDist, nSdiBlockSpeed 등 포함)
            val sdiObj = bundle.get("firstSDIInfo")
            if (sdiObj != null) {
                try {
                    val sdiJson = org.json.JSONObject(gson.toJson(sdiObj))
                    val keys = sdiJson.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        dataMap[k] = sdiJson.get(k)
                    }
                    if (sdiJson.has("nSdiSection")) dataMap["nSdiBlockType"] = sdiJson.get("nSdiSection")
                } catch (e: Exception) {}
            }
            
            // secondSDIInfo 변환 (nSdiPlusType 등으로 매핑)
            val sdiPlusObj = bundle.get("secondSDIInfo")
            if (sdiPlusObj != null) {
                try {
                    val sdiJson = org.json.JSONObject(gson.toJson(sdiPlusObj))
                    if (sdiJson.has("nSdiType")) dataMap["nSdiPlusType"] = sdiJson.get("nSdiType")
                    if (sdiJson.has("nSdiSpeedLimit")) dataMap["nSdiPlusSpeedLimit"] = sdiJson.get("nSdiSpeedLimit")
                    if (sdiJson.has("nSdiDist")) dataMap["nSdiPlusDist"] = sdiJson.get("nSdiDist")
                    if (sdiJson.has("nSdiSection")) dataMap["nSdiPlusBlockType"] = sdiJson.get("nSdiSection")
                    if (sdiJson.has("nSdiBlockSpeed")) dataMap["nSdiPlusBlockSpeed"] = sdiJson.get("nSdiBlockSpeed")
                    if (sdiJson.has("nSdiBlockDist")) dataMap["nSdiPlusBlockDist"] = sdiJson.get("nSdiBlockDist")
                } catch (e: Exception) {}
            }
            
            // 디버그용 원본 데이터 전송 (raw_bundle)
            val rawMap = mutableMapOf<String, Any>()
            for (key in bundle.keySet()) {
                val value = bundle.get(key)
                if (value != null && key != "firstSDIInfo" && key != "secondSDIInfo") {
                    rawMap[key] = value
                }
            }

            // 엔진에서 직접 RGData 빼오기 시도
            try {
                val engine = com.skt.tmap.engine.navigation.TmapNavigation.getInstance()
                val lastRg = engine?.lastRGData
                val guidRg = engine?.routeGuidance
                
                if (lastRg != null) {
                    rawMap["hidden_last_limit"] = lastRg.nRoadLimitSpeed
                    rawMap["hidden_last_roadcate"] = lastRg.roadcate
                    
                    // 쓰레기 값(520 등) 방지를 위해 1~200km 사이의 정상 값일 때만 매핑
                    // Tmap 엔진의 더미 값(120 등)이 실제 도로 제한속도를 덮어쓰지 않도록, EDC의 제한속도가 0일 때만 사용
                    if (lastRg.nRoadLimitSpeed in 1..200 && nRoadLimitSpeed == 0) {
                        dataMap["nRoadLimitSpeed"] = lastRg.nRoadLimitSpeed
                    }
                }
                
                if (guidRg != null) {
                    rawMap["hidden_guidance_limit"] = guidRg.nRoadLimitSpeed
                    
                    // 만약 lastRg가 실패하고 guidRg만 정상일 경우를 대비 (단, EDC 제한속도가 없을 때만)
                    if (guidRg.nRoadLimitSpeed in 1..200 && (lastRg == null || lastRg.nRoadLimitSpeed !in 1..200) && nRoadLimitSpeed == 0) {
                        dataMap["nRoadLimitSpeed"] = guidRg.nRoadLimitSpeed
                    }
                }
            } catch (e: Exception) {
                rawMap["hidden_engine_error"] = e.message.toString()
            }
            dataMap["raw_bundle"] = rawMap
        }

        val jsonPayload = gson.toJson(dataMap)
        val buffer = jsonPayload.toByteArray()
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 오픈파일럿이 같은 기기(로컬)에 있거나, 핫스팟/와이파이 등 어떤 네트워크 구성이든 
                // 데이터를 확실하게 받을 수 있도록 모든 브로드캐스트 주소로 전송
                val targetAddresses = mutableSetOf<InetAddress>()
                targetAddresses.add(InetAddress.getByName("127.0.0.1"))
                targetAddresses.add(InetAddress.getByName("255.255.255.255"))
                
                try {
                    val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                    while (interfaces.hasMoreElements()) {
                        val networkInterface = interfaces.nextElement()
                        if (networkInterface.isLoopback || !networkInterface.isUp) continue
                        for (interfaceAddress in networkInterface.interfaceAddresses) {
                            interfaceAddress.broadcast?.let { targetAddresses.add(it) }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get network interfaces", e)
                }

                for (address in targetAddresses) {
                    val packet = DatagramPacket(buffer, buffer.size, address, OPENPILOT_PORT)
                    udpSocket?.send(packet)
                }
                
                Log.d(TAG, "Sent UDP: $jsonPayload")
                TmapDataManager.addLog("UDP: $jsonPayload")
            } catch (e: Exception) {
                Log.e(TAG, "UDP Send failed", e)
                TmapDataManager.addLog("UDP Error: ${e.message}")
            }
        }
    }
}
