package com.example.carrotnavi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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

class UdpSenderService : Service() {

    private val CHANNEL_ID = "CarrotNaviChannel"
    private var targetIp = "255.255.255.255"
    private var udpPort = 7706

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

    @Volatile
    private var currentGpsStatusText = "탐색 중"

    private var loopsStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or 2 /* FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK */)
            } else {
                startForeground(1, createNotification())
            }
        } catch (e: Exception) {
            RemoteLogManager.e("UdpSenderService", "Error in startForeground", e)
            e.printStackTrace()
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

        isRunning.set(true)

        // GPS 상태 모니터링 등록
        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            locationManager.registerGnssStatusCallback(object : android.location.GnssStatus.Callback() {
                override fun onStarted() {
                    currentGpsStatusText = "탐색 중"
                }
                override fun onStopped() {
                    currentGpsStatusText = "NO_SIGNAL"
                }
                override fun onFirstFix(ttffMillis: Int) {
                    currentGpsStatusText = "수신 양호"
                }
                override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                    var usedInFix = 0
                    for (i in 0 until status.satelliteCount) {
                        if (status.usedInFix(i)) usedInFix++
                    }
                    if (usedInFix >= 4) {
                        currentGpsStatusText = "GOOD($usedInFix)"
                    } else {
                        currentGpsStatusText = "BAD($usedInFix)"
                    }
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (e: SecurityException) {
            currentGpsStatusText = "권한 없음"
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sharedPref = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
        targetIp = sharedPref.getString("TARGET_IP", "255.255.255.255") ?: "255.255.255.255"

        if (loopsStarted.compareAndSet(false, true)) {
            startObservingEDC()
            startSendingLoop()
            startReceivingLoop()
        }

        return START_STICKY
    }

    private var lastSdiJsonStr: String? = null
    private var lastSdiUpdateTime = 0L
    private var lastSdiPlusJsonStr: String? = null
    private var lastSdiPlusUpdateTime = 0L
    private var tmapRoadLimitSpeed = 0

    private val edcObserver = Observer<Bundle> { bundle ->
        if (bundle != null && isRunning.get()) {
            serviceScope.launch {
                try {
                    val naviType = bundle.getString("navitype", "tmap")
                    val sharedPref = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
                    val activeNavi = sharedPref.getString("ACTIVE_NAVI", "tmap") ?: "tmap"
                    
                    // 1. limitSpeed 처리
                    val limitObj = bundle.get("limitSpeed")
                    val currentLimitSpeed = when (limitObj) {
                        is Int -> limitObj
                        is String -> limitObj.toIntOrNull() ?: 0
                        else -> 0
                    }
                    if (naviType == "tmap") {
                        val engineLimit = getRoadLimitSpeedFromEngine()
                        tmapRoadLimitSpeed = if (engineLimit > 0) engineLimit else currentLimitSpeed
                    }
                    if (currentLimitSpeed >= 30) {
                        roadLimitSpeed = currentLimitSpeed
                    }

                    if (naviType != activeNavi) {
                        return@launch
                    }

                    val json = JSONObject()
                    json.put("carrotIndex", packetIndex++)
                    json.put("navitype", "tmap")
                    var isBoosting = false
                    
                    // 2. firstSDIInfo (GRT47과 동일하게 모든 key를 최상위로 복사)
                    val sdiObj = bundle.get("firstSDIInfo")
                    if (sdiObj != null) {
                        lastSdiJsonStr = if (sdiObj is String) sdiObj else gson.toJson(sdiObj)
                        lastSdiUpdateTime = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - lastSdiUpdateTime > 2000) {
                        lastSdiJsonStr = null
                    }
                    
                    if (lastSdiJsonStr != null) {
                        val sdiJson = JSONObject(lastSdiJsonStr)
                        
                        val keys = sdiJson.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            json.put(k, sdiJson.get(k))
                        }
                        
                        // Fallback logic for point camera (사용자 피드백 반영)
                        val sdiType = json.optInt("nSdiType", 0)
                        var sdiSpeedLimit = json.optInt("nSdiSpeedLimit", 0)
                        var sdiDist = json.optInt("nSdiDist", 0)
                        val bSdiBlockSection = json.optBoolean("bSdiBlockSection", false)
                        
                        var nSdiBlockType = 0
                        if (bSdiBlockSection) {
                            nSdiBlockType = if (sdiType == 3) 3 else 2
                        } else if (sdiType == 2) {
                            nSdiBlockType = 1
                        }
                        if (nSdiBlockType > 0) {
                            json.put("nSdiBlockType", nSdiBlockType)
                            json.put("nSdiSection", 1) // 사용자 요청에 따라 강제로 1 고정
                            
                            // 오픈파일럿 최신 버전(carrot_serv.py)은 구간단속 진행 중일지라도 nSdiSpeedLimit > 0 조건을 필수적으로 체크하므로,
                            // sdiSpeedLimit이 0으로 빠져있다면 구간단속 제한속도(nSdiBlockSpeed) 값으로 복사해 줍니다.
                            if (sdiSpeedLimit <= 0) {
                                val blockSpeed = json.optInt("nSdiBlockSpeed", 0)
                                if (blockSpeed > 0) {
                                    sdiSpeedLimit = blockSpeed
                                    json.put("nSdiSpeedLimit", sdiSpeedLimit)
                                }
                            }
                        }

                        // 구간단속 제한속도 상향 로직 (평균속도는 원본 유지, 제한속도만 뻥튀기)
                        val sp = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
                        val punchPower = sp.getInt("BLOCK_SPEED_OFFSET", 0) // 위젯 (+/-): 최대 가속력 (속임값)
                        val boostMode = sp.getInt("BLOCK_SPEED_BOOST_MODE", 0) // 0: 점진적, 1: 고정
                        val targetOffset = sp.getInt("BLOCK_SPEED_FAKE_DROP", 10) // 슬라이더: 목표 평균속도 상향값
                        
                        if (nSdiBlockType == 2 && punchPower > 0 && sdiSpeedLimit > 0) {
                            val avgSpeed = json.optInt("nSdiBlockAverageSpeed", 0)
                            // 1, 3, 6, 7: 과속, 구간종점, 신호위반, 이동식 등 포인트 카메라 (4: 구간단속중은 제외)
                            val hasPointCameraAhead = (sdiType == 1 || sdiType == 3 || sdiType == 6 || sdiType == 7) && sdiDist > 0
                            
                            if (avgSpeed > 0 && !hasPointCameraAhead) {
                                val targetAvgSpeed = sdiSpeedLimit + targetOffset
                                var fakeSpeedLimit = targetAvgSpeed // 기본적으로 목표 평균속도로 제한속도를 상향
                                
                                if (avgSpeed < targetAvgSpeed) {
                                    if (boostMode == 1) {
                                        // 고정 가속: 펀치력만큼 무조건 제한속도를 더 상향
                                        fakeSpeedLimit = targetAvgSpeed + punchPower
                                    } else {
                                        // 점진적 가속: 남은 여유 속도 비율에 따라 펀치력을 점진적으로 가감
                                        val diff = targetAvgSpeed - avgSpeed
                                        val ratio = if (targetOffset > 0) diff.toDouble() / targetOffset.toDouble() else 1.0
                                        val progressiveBoost = Math.ceil(punchPower * ratio).toInt()
                                        fakeSpeedLimit = targetAvgSpeed + progressiveBoost
                                    }
                                }
                                
                                // 평균속도(nSdiBlockAverageSpeed)는 변조하지 않고 원본 그대로 둠
                                // 제한속도와 구간단속 속도만 변조하여 오픈파일럿의 가속 락을 해제함
                                json.put("nSdiSpeedLimit", fakeSpeedLimit)
                                json.put("nSdiBlockSpeed", fakeSpeedLimit)
                                isBoosting = true
                            }
                        }
                        
                        if (sdiType == 22) {
                            if (sdiDist <= 0) {
                                sdiDist = 150
                                json.put("nSdiDist", 150)
                            }
                            json.put("roadcate", 8)
                        }
                        
                        if (sdiSpeedLimit >= 30) {
                            roadLimitSpeed = sdiSpeedLimit
                        }
                        
                        if (sdiType == 0 && sdiSpeedLimit > 0 && sdiDist > 0) {
                            json.put("nSdiType", 1) // 강제로 1로 세팅
                        }
                        
                        // SDI 이벤트 발생 시 TBT 패널을 이벤트 거리/직진으로 덮어쓰기
                        val finalSdiType = json.optInt("nSdiType", 0)
                        val finalSdiDist = json.optInt("nSdiDist", 0)
                        val isBlockSection = json.optInt("nSdiSection", 0) == 1 || json.optInt("nSdiBlockType", 0) == 2
                        val blockDist = json.optInt("nSdiBlockDist", 0)

                        if (isBlockSection && blockDist > 0) {
                            json.put("nTBTDist", blockDist)
                            json.put("nTBTTurnType", 1)
                        } else if (finalSdiType > 0 && finalSdiDist > 0) {
                            json.put("nTBTDist", finalSdiDist)
                            json.put("nTBTTurnType", 1) // 1: 직진 (Straight)
                        }
                    }
                    
                    // Kakao 모드일 경우 tmap의 엔진 속도가 유효하면 덮어쓰기
                    if (activeNavi == "kakao") {
                        val engineLimit = getRoadLimitSpeedFromEngine()
                        if (engineLimit >= 0) {
                            roadLimitSpeed = engineLimit
                        } else if (tmapRoadLimitSpeed > 0) {
                            roadLimitSpeed = tmapRoadLimitSpeed
                        }
                    } else if (activeNavi == "tmap") {
                        if (tmapRoadLimitSpeed > 0) {
                            roadLimitSpeed = tmapRoadLimitSpeed
                        }
                    }
                    
                    json.put("nRoadLimitSpeed", roadLimitSpeed)
                    SdiDataRepository.updateRoadLimitSpeed(roadLimitSpeed)
                    
                    // 3. secondSDIInfo (GRT47과 동일하게 nSdiPlus... 접두어로 추가)
                    val sdiPlusObj = bundle.get("secondSDIInfo")
                    if (sdiPlusObj != null) {
                        lastSdiPlusJsonStr = if (sdiPlusObj is String) sdiPlusObj else gson.toJson(sdiPlusObj)
                        lastSdiPlusUpdateTime = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - lastSdiPlusUpdateTime > 2000) {
                        lastSdiPlusJsonStr = null
                    }
                    
                    if (lastSdiPlusJsonStr != null) {
                        val plusJson = JSONObject(lastSdiPlusJsonStr)
                        
                        val plusType = plusJson.optInt("nSdiType", 0)
                        var plusDist = plusJson.optInt("nSdiDist", 0)
                        if (plusType == 22 && plusDist <= 0) plusDist = 150
                        
                        if (plusJson.has("nSdiType")) json.put("nSdiPlusType", plusJson.get("nSdiType"))
                        if (plusJson.has("nSdiSpeedLimit")) json.put("nSdiPlusSpeedLimit", plusJson.get("nSdiSpeedLimit"))
                        if (plusJson.has("nSdiDist") || plusDist > 0) json.put("nSdiPlusDist", plusDist)
                        if (plusJson.has("nSdiSection")) json.put("nSdiPlusBlockType", plusJson.get("nSdiSection"))
                        if (plusJson.has("nSdiBlockSpeed")) json.put("nSdiPlusBlockSpeed", plusJson.get("nSdiBlockSpeed"))
                        if (plusJson.has("nSdiBlockDist")) json.put("nSdiPlusBlockDist", plusJson.get("nSdiBlockDist"))
                    }
                    
                    // 목적지 남은 거리 및 소요 시간 처리 (안심주행 모드 대응을 위해 값이 없거나 0이면 더미 값 주입)
                    val nGoPosDist = bundle.getInt("nGoPosDist", bundle.getInt("remainDistanceToGoPositionInMeter", 0))
                    val nGoPosTime = bundle.getInt("nGoPosTime", bundle.getInt("remainTimeToGoPositionInSec", 0))
                    if (nGoPosDist > 0 && nGoPosTime > 0) {
                        json.put("nGoPosDist", nGoPosDist)
                        json.put("nGoPosTime", nGoPosTime)
                    } else {
                        // 오픈파일럿 HUD TBT 패널을 항상 띄우기 위해 최소 dummy 값 주입
                        json.put("nGoPosDist", 1)
                        json.put("nGoPosTime", 1)
                    }
                    
                    // 4. tbtInfo 에서 도로명(szPosRoadName) 등 추출
                    var kakaoTbtDist = -1
                    var kakaoTbtTurnType = -1
                    var kakaoTbtText = ""
                    val tbtObj = bundle.get("tbtInfo")
                    if (tbtObj != null) {
                        val tbtStr = if (tbtObj is String) tbtObj else gson.toJson(tbtObj)
                        try {
                            val tbtJson = JSONObject(tbtStr)
                            if (tbtJson.has("szPosRoadName")) {
                                json.put("szPosRoadName", tbtJson.get("szPosRoadName"))
                            }
                            if (tbtJson.has("nTBTDist")) kakaoTbtDist = tbtJson.getInt("nTBTDist")
                            if (tbtJson.has("nTBTTurnType")) kakaoTbtTurnType = tbtJson.getInt("nTBTTurnType")
                            if (tbtJson.has("szTBTMainText")) kakaoTbtText = tbtJson.getString("szTBTMainText")
                        } catch (e: Exception) {}
                    }
                    
                    // 목적지 정보 추출
                    val szGoalName = bundle.getString("szGoalName")
                    if (!szGoalName.isNullOrEmpty()) {
                        json.put("szGoalName", szGoalName)
                        val goalPosX = bundle.getDouble("goalPosX", 0.0)
                        val goalPosY = bundle.getDouble("goalPosY", 0.0)
                        if (goalPosX > 0.0) json.put("goalPosX", goalPosX)
                        if (goalPosY > 0.0) json.put("goalPosY", goalPosY)
                    }
                    
                    // 위치(위경도) 정보 추출
                    val lat = bundle.getDouble("lat", 0.0)
                    val lon = bundle.getDouble("lon", 0.0)
                    if (lat > 0.0) json.put("lat", lat)
                    if (lon > 0.0) json.put("lon", lon)
                    
                    // RouteInfoRepository 업데이트
                    RouteInfoRepository.updateRouteInfo(szGoalName ?: "", nGoPosDist, nGoPosTime, activeNavi)

                    // 상시 안내 텍스트 표시를 위한 필수 TBT 더미 값 주입
                    // 우선순위: 1차 이벤트 -> 2차 이벤트 -> 구간단속
                    var tbtDist = 0
                    var activeType = 0
                    
                    val type1 = json.optInt("nSdiType", 0)
                    val dist1 = json.optInt("nSdiDist", 0)
                    val type2 = json.optInt("nSdiPlusType", 0)
                    val dist2 = json.optInt("nSdiPlusDist", 0)
                    val blockType = json.optInt("nSdiBlockType", 0)
                    val blockDist = json.optInt("nSdiBlockDist", 0)

                    if (type1 > 0 && dist1 > 0) {
                        tbtDist = dist1
                        activeType = type1
                    } else if (type2 > 0 && dist2 > 0) {
                        tbtDist = dist2
                        activeType = type2
                    } else if (blockType > 0 && blockDist > 0) {
                        tbtDist = blockDist
                        // blockType(1:시작, 2:진행, 3:종료) -> activeType(2, 4, 3) 매핑
                        activeType = when (blockType) {
                            1 -> 2
                            2 -> 4
                            3 -> 3
                            else -> 4
                        }
                    } else if (dist1 > 0) {
                        tbtDist = dist1
                        activeType = 0
                    } else {
                        tbtDist = 9999
                        activeType = 0
                    }

                    var eventText = ""
                    var tbtTurnType = 51 // 기본값 (안내지점)

                    when (activeType) {
                        1 -> { eventText = "고정식 단속"; tbtTurnType = 201 }
                        2 -> { eventText = "구간단속 시작"; tbtTurnType = 201 }
                        3 -> { eventText = "구간단속 종료"; tbtTurnType = 201 }
                        4 -> { eventText = "구간단속중"; tbtTurnType = 201 }
                        7 -> { eventText = "이동식 단속"; tbtTurnType = 201 }
                        22 -> { eventText = "과속방지턱"; tbtTurnType = 201 }
                        33 -> { eventText = "스쿨존"; tbtTurnType = 51 }
                        else -> { 
                            eventText = if (tbtDist < 9999) "주의구간" else "안심주행"
                            tbtTurnType = 51 
                        }
                    }

                    if (isBoosting) {
                        eventText += " (가속중)"
                    }

                    // TBT 강제 오버라이드
                    val spOverride = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
                    val overrideTurnType = spOverride.getInt("OVERRIDE_TBT_TURN_TYPE", -1)
                    targetIp = spOverride.getString("TARGET_IP", "255.255.255.255") ?: "255.255.255.255"
                    udpPort = spOverride.getInt("TARGET_UDP_PORT", 7706)
                    
                    val textOutputTarget = spOverride.getString("TEXT_OUTPUT_TARGET", "szTBTMainText")
                    val spaceBefore = spOverride.getInt("TBT_SPACE_BEFORE", 0)
                    val spaceAfter = spOverride.getInt("TBT_SPACE_AFTER", 0)
                    val outputText = " ".repeat(spaceBefore) + "GPS: $currentGpsStatusText" + " ".repeat(spaceAfter)
                    
                    // Kakao 경로안내 중 실제 TBT 값이 존재하면 해당 값을 사용 (단, 텍스트는 무조건 GPS 정보로 고정)
                    if (kakaoTbtDist >= 0 && activeNavi == "kakao") {
                        json.put("nTBTDist", kakaoTbtDist)
                        json.put("nTBTTurnType", kakaoTbtTurnType)
                    } else {
                        if (overrideTurnType <= 0) {
                            // 끄기 모드: 팝업이 뜨지 않도록 무효화
                            json.put("nTBTTurnType", -1)
                            json.put("nTBTDist", 9999)
                        } else {
                            if (overrideTurnType > 0) {
                                tbtTurnType = overrideTurnType
                            }
                            json.put("nTBTDist", tbtDist)
                            json.put("nTBTTurnType", tbtTurnType)
                        }
                    }
                    
                    if (textOutputTarget == "szTBTMainText") {
                        json.put("szTBTMainText", outputText)
                        json.put("szPosRoadName", "")
                    } else {
                        json.put("szTBTMainText", "")
                        json.put("szPosRoadName", outputText)
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
            try {
                TmapUISDK.observableEDCData.observeForever(edcObserver)
            } catch (e: Exception) { e.printStackTrace() }
            try {
                TmapUISDK.observableRouteData.observeForever { _ -> }
            } catch (e: Exception) { e.printStackTrace() }
            try {
                KakaoSdiRepository.observableKakaoData.observeForever(edcObserver)
            } catch (e: Exception) { e.printStackTrace() }
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

    private var receiveSocket: DatagramSocket? = null

    private fun startReceivingLoop() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                receiveSocket = DatagramSocket(null)
                receiveSocket?.reuseAddress = true
                receiveSocket?.bind(java.net.InetSocketAddress("0.0.0.0", 7705))
                
                val buffer = ByteArray(4096)
                while (isActive && isRunning.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    receiveSocket?.receive(packet)
                    val data = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    try {
                        val json = JSONObject(data)
                        val carrot2 = json.optString("Carrot2", "-")
                        val ip = json.optString("ip", "-")
                        val trafficState = json.optInt("trafficState", 0)
                        val xState = json.optInt("xState", 0)
                        val active = json.optBoolean("active", false)
                        
                        OpenpilotStateRepository.updateState(carrot2, ip, trafficState, xState, active)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                RemoteLogManager.e("UdpSenderService", "Error in receive loop", e)
                e.printStackTrace()
            }
        }
    }

    private fun sendSdiData() {
        android.util.Log.d("UdpSender", "sendSdiData called. payload=" + latestPayload)
        if (latestPayload == "{}") return
        val sharedPref = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
        if (sharedPref.getBoolean("IS_DEBUG_MODE", false)) return

        try {
            val buffer = latestPayload.toByteArray(Charsets.UTF_8)
            
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
                val packet = DatagramPacket(buffer, buffer.size, address, udpPort)
                udpSocket?.send(packet)
            }
        } catch (e: Exception) {
            RemoteLogManager.e("UdpSenderService", "Error in sendSdiData", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning.set(false)
        CoroutineScope(Dispatchers.Main).launch {
            TmapUISDK.observableEDCData.removeObserver(edcObserver)
        }
        serviceScope.cancel()
        udpSocket?.close()
        receiveSocket?.close()
        
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

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("CarrotNavi 실행 중")
        .setContentText("MD 가이드 기반 안전운행 정보 UDP 송신 중...")
        .setSmallIcon(R.mipmap.ic_launcher)
        .build()

    // Reflection Caching
    private var sdkManagerCompanion: Any? = null
    private var getInstanceMethod: java.lang.reflect.Method? = null
    private var getRecentRGDataMethod: java.lang.reflect.Method? = null
    private var nRoadLimitSpeedField: java.lang.reflect.Field? = null

    private fun getRoadLimitSpeedFromEngine(): Int {
        try {
            if (sdkManagerCompanion == null) {
                val sdkManagerClass = Class.forName("com.skt.tmap.engine.navigation.SDKManager")
                val companionField = sdkManagerClass.getField("Companion")
                sdkManagerCompanion = companionField.get(null)
                getInstanceMethod = sdkManagerCompanion?.javaClass?.getMethod("getInstance")
            }
            
            val sdkManager = getInstanceMethod?.invoke(sdkManagerCompanion)
            if (sdkManager != null) {
                if (getRecentRGDataMethod == null) {
                    getRecentRGDataMethod = sdkManager.javaClass.getMethod("getRecentRGData")
                }
                val rgData = getRecentRGDataMethod?.invoke(sdkManager)
                if (rgData != null) {
                    if (nRoadLimitSpeedField == null) {
                        nRoadLimitSpeedField = rgData.javaClass.getField("nRoadLimitSpeed")
                    }
                    val rawLimitSpeed = nRoadLimitSpeedField?.getInt(rgData) ?: 0
                    if (rawLimitSpeed > 0) {
                        return (rawLimitSpeed - 20) / 10
                    }
                    return 0
                }
            }
        } catch (e: Exception) {
            RemoteLogManager.e("UdpSenderService", "Reflection error: ${e.message}", e)
        }
        return -1
    }
}
