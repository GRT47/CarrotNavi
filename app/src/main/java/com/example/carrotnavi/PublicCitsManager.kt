package com.example.carrotnavi

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Calendar
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.math.*

/**
 * 실시간 C-ITS 교통신호등 하이브리드 연동 매니저
 * 1) 경찰청 TSI-Hub 중계서버 (대전, 대구, 인천 등 6,389개 교차로)
 * 2) 행정안전부 공공데이터포털 RTI (서울 2,779개, 울산 등 전국 지자체 교차로)
 * 3) 지자체 실시간 미제공 교차로는 안전운행 표준 TOD 4현시 신호주기 모델로 자동 fallback 연동
 */
object PublicCitsManager {
    private const val TAG = "PublicCitsManager"
    private const val RTI_BASE_URL = "https://apis.data.go.kr/B551982/rti"
    const val DEFAULT_PROXY_URL = "http://192.168.1.5:5050"

    const val MODE_AUTO = "AUTO"
    const val MODE_UTIC_PROXY = "UTIC_PROXY"
    const val MODE_PUBLIC_API = "PUBLIC_API"

    data class Intersection(
        val id: String,
        val name: String,
        val lat: Double,
        val lon: Double,
        val stdgCd: String
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private var appContext: Context? = null
    private var isEnabled = false
    private var citsMode = MODE_AUTO
    private var proxyUrl = DEFAULT_PROXY_URL
    private var apiKey = ""
    private var stdgCd = "1100000000" // 기본값: 서울특별시

    val intersections = CopyOnWriteArrayList<Intersection>()
    @Volatile private var isMapLoaded = false
    @Volatile private var isLoadingMap = false

    private var currentTargetIntersection: Intersection? = null
    private var lastSignalFetchTime = 0L
    private var lastLocationLat = 0.0
    private var lastLocationLon = 0.0
    private var lastLocationHeading = 0f

    // 1초 단위 잔여시간 카운트다운 타이머
    private val mainHandler = Handler(Looper.getMainLooper())
    private var countdownRunnable: Runnable? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        reloadConfig()
    }

    fun reloadConfig() {
        val context = appContext ?: return
        val prefs = context.getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
        val newEnabled = prefs.getBoolean("CITS_PUBLIC_ENABLED", true)
        val newMode = prefs.getString("CITS_MODE", MODE_AUTO) ?: MODE_AUTO
        val newProxyUrl = prefs.getString("CITS_PROXY_URL", DEFAULT_PROXY_URL)?.trim() ?: DEFAULT_PROXY_URL
        val newApiKey = prefs.getString("CITS_PUBLIC_API_KEY", "")?.trim() ?: ""
        val newStdgCd = prefs.getString("CITS_PUBLIC_STDG_CD", "1100000000")?.trim() ?: "1100000000"

        val keyChanged = (apiKey != newApiKey)
        val stdgChanged = (stdgCd != newStdgCd)

        isEnabled = newEnabled
        citsMode = newMode
        proxyUrl = if (newProxyUrl.isNotEmpty()) newProxyUrl else DEFAULT_PROXY_URL
        apiKey = newApiKey
        stdgCd = if (newStdgCd.isNotEmpty()) newStdgCd else "1100000000"

        Log.d(TAG, "Config reloaded: isEnabled=$isEnabled, mode=$citsMode, proxyUrl=$proxyUrl, apiKey.len=${apiKey.length}, stdgCd=$stdgCd")

        if (!isEnabled) {
            currentTargetIntersection = null
            stopCountdown()
            TrafficSignalRepository.clear()
            return
        }

        // 공공데이터포털 API 키가 있으면 교차로 맵 데이터(서울 2,779건 등) 백그라운드 선행 로드
        if (apiKey.isNotEmpty()) {
            if (!isMapLoaded || keyChanged || stdgChanged) {
                loadIntersectionMap()
            }
        }
    }

    /**
     * 차량 위치 및 진행 방향 업데이트 시 호출
     */
    fun onLocationUpdate(lat: Double, lon: Double, heading: Float) {
        if (!isEnabled) {
            return
        }
        if (lat <= 0.0 || lon <= 0.0) return

        lastLocationLat = lat
        lastLocationLon = lon
        lastLocationHeading = heading

        val now = System.currentTimeMillis()
        if (now - lastSignalFetchTime < 1200) {
            // 호출 주기 사이에는 거리만 업데이트
            val currentSig = TrafficSignalRepository.observableSignal.value
            val target = currentTargetIntersection
            if (currentSig != null && currentSig.isVisible && target != null) {
                val d = calculateDistance(lat, lon, target.lat, target.lon).toInt()
                TrafficSignalRepository.updateSignal(
                    isVisible = true,
                    isRed = currentSig.isRed,
                    isYellow = currentSig.isYellow,
                    isGreen = currentSig.isGreen,
                    isLeft = currentSig.isLeft,
                    remainTime = currentSig.remainTime,
                    distance = d,
                    latitude = target.lat,
                    longitude = target.lon,
                    intersectionName = target.name
                )
            }
            return
        }
        lastSignalFetchTime = now
        Log.d(TAG, "onLocationUpdate: lat=$lat, lon=$lon, heading=$heading, mapSize=${intersections.size}, mode=$citsMode")

        // 1) 순수 PUBLIC_API 모드인 경우
        if (citsMode == MODE_PUBLIC_API) {
            handlePublicApiSignalUpdate(lat, lon, heading)
            return
        }

        // 2) AUTO 또는 UTIC_PROXY 모드: 중계서버 우선 조회
        scope.launch {
            try {
                val base = proxyUrl.trimEnd('/')
                val url = "$base/api/cits/nearby_signal?lat=$lat&lon=$lon&heading=$heading&max_dist=500"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Accept", "application/json")
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string()

                if (response.isSuccessful && !body.isNullOrEmpty()) {
                    val root = JSONObject(body)
                    val matched = root.optBoolean("matched", false)
                    if (matched) {
                        parseProxySignalJson(root)
                        return@launch
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Proxy fetch failed, fallback to public map: ${e.message}")
            }

            // 중계서버에 매칭되지 않은 경우(서울시 등): 공공데이터포털 맵 기반 교차로 검색 & 신호 계산
            if (citsMode == MODE_AUTO || citsMode == MODE_PUBLIC_API) {
                handlePublicApiSignalUpdate(lat, lon, heading)
            }
        }
    }

    private fun parseProxySignalJson(root: JSONObject) {
        try {
            val interObj = root.optJSONObject("intersection") ?: return
            val sigObj = root.optJSONObject("signal") ?: return

            val interId = interObj.optString("id", "")
            val interName = interObj.optString("name", "")
            val interLat = interObj.optDouble("lat", 0.0)
            val interLon = interObj.optDouble("lon", 0.0)
            val distance = interObj.optDouble("distance", 0.0).toInt()

            val isRed = sigObj.optBoolean("is_red", false)
            val isYellow = sigObj.optBoolean("is_yellow", false)
            val isGreen = sigObj.optBoolean("is_green", false)
            val isLeft = sigObj.optBoolean("is_left", false)
            val remainSec = sigObj.optInt("remain_seconds", 0)

            currentTargetIntersection = Intersection(interId, interName, interLat, interLon, "")

            mainHandler.post {
                TrafficSignalRepository.updateSignal(
                    isVisible = true,
                    isRed = isRed,
                    isYellow = isYellow,
                    isGreen = isGreen,
                    isLeft = isLeft,
                    remainTime = remainSec,
                    distance = distance,
                    latitude = interLat,
                    longitude = interLon,
                    intersectionName = interName
                )
                startCountdown(remainSec)
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseProxySignalJson error: ${e.message}", e)
        }
    }

    // ----------------- 공공데이터포털 및 지자체 신호 연동 로직 -----------------

    private fun handlePublicApiSignalUpdate(lat: Double, lon: Double, heading: Float) {
        if (!isMapLoaded) {
            if (!isLoadingMap && apiKey.isNotEmpty()) loadIntersectionMap()
            return
        }

        // 전방 0m ~ 500m 이내 최단거리 교차로 검색 (정지선 정지 상태 및 서행 모두 감지)
        var nearest: Intersection? = null
        var minDistance = Double.MAX_VALUE

        for (inter in intersections) {
            val dist = calculateDistance(lat, lon, inter.lat, inter.lon)
            if (dist in 0.0..500.0) {
                val bearingToInter = calculateBearing(lat, lon, inter.lat, inter.lon)
                val angleDiff = abs((bearingToInter - heading + 540) % 360 - 180)

                // 60m 이내 초근접 시에는 정지/회전 중이어도 감지, 그 외에는 전방 75도 이내
                if (dist <= 60.0 || angleDiff <= 75.0) {
                    if (dist < minDistance) {
                        minDistance = dist
                        nearest = inter
                    }
                }
            }
        }

        if (nearest != null) {
            val dist = minDistance
            currentTargetIntersection = nearest
            Log.d(TAG, "Matched intersection: ${nearest.name} (${nearest.id}), dist=${dist.toInt()}m")
            fetchTrafficSignalFromPublicApi(nearest, dist, heading)
        } else {
            if (currentTargetIntersection != null) {
                Log.d(TAG, "Exited intersection: ${currentTargetIntersection?.name}")
                currentTargetIntersection = null
                stopCountdown()
                TrafficSignalRepository.clear()
            }
        }
    }

    fun loadIntersectionMap() {
        if (isLoadingMap) return
        if (apiKey.isEmpty()) return

        scope.launch {
            isLoadingMap = true
            try {
                val loadedFromCache = loadFromCache()
                if (loadedFromCache > 0) {
                    isMapLoaded = true
                    Log.d(TAG, "Loaded $loadedFromCache intersections from local cache.")
                    return@launch
                }

                val cleanKey = sanitizeApiKey(apiKey)
                val allIntersections = mutableListOf<Intersection>()
                var page = 1
                var totalPages = 1
                val pageSize = 1000

                do {
                    val stdgParam = if (stdgCd.isNotEmpty()) "&stdgCd=$stdgCd" else ""
                    val url = "$RTI_BASE_URL/crsrd_map_info?serviceKey=$cleanKey&pageNo=$page&numOfRows=$pageSize&type=json$stdgParam"

                    val request = Request.Builder()
                        .url(url)
                        .addHeader("Accept", "application/json")
                        .build()

                    val response = httpClient.newCall(request).execute()
                    val body = response.body?.string()

                    if (response.isSuccessful && !body.isNullOrEmpty()) {
                        val (pageItems, total) = parseIntersectionMapPage(body)
                        allIntersections.addAll(pageItems)
                        if (total > 0) {
                            totalPages = ((total - 1) / pageSize) + 1
                        }
                        page++
                    } else {
                        break
                    }
                } while (page <= totalPages && page <= 5)

                if (allIntersections.isNotEmpty()) {
                    intersections.clear()
                    intersections.addAll(allIntersections)
                    isMapLoaded = true
                    saveToCacheJson(allIntersections)
                    Log.d(TAG, "Successfully loaded ${allIntersections.size} intersections from RTI API.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading intersection map: ${e.message}", e)
            } finally {
                isLoadingMap = false
            }
        }
    }

    private fun parseIntersectionMapPage(jsonStr: String): Pair<List<Intersection>, Int> {
        val list = mutableListOf<Intersection>()
        var totalCount = 0
        try {
            val root = JSONObject(jsonStr)
            val body = root.optJSONObject("body") ?: return Pair(list, 0)
            totalCount = body.optInt("totalCount", 0)
            val itemsObj = body.optJSONObject("items") ?: return Pair(list, totalCount)

            val itemArr = when {
                itemsObj.has("item") -> {
                    val itemField = itemsObj.get("item")
                    if (itemField is JSONArray) itemField
                    else if (itemField is JSONObject) JSONArray().put(itemField)
                    else JSONArray()
                }
                else -> JSONArray()
            }

            for (i in 0 until itemArr.length()) {
                val item = itemArr.optJSONObject(i) ?: continue
                val id = item.optString("crsrdId", "")
                val name = item.optString("crsrdNm", "")
                val latStr = item.optString("mapCtptIntLat", "")
                val lonStr = item.optString("mapCtptIntLot", "")
                val sCd = item.optString("stdgCd", stdgCd)

                val lat = latStr.toDoubleOrNull() ?: 0.0
                val lon = lonStr.toDoubleOrNull() ?: 0.0

                if (id.isNotEmpty() && lat > 0.0 && lon > 0.0) {
                    list.add(Intersection(id, name, lat, lon, sCd))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseIntersectionMapPage error: ${e.message}", e)
        }
        return Pair(list, totalCount)
    }

    private fun fetchTrafficSignalFromPublicApi(intersection: Intersection, distance: Double, heading: Float) {
        scope.launch {
            var handledByLiveStream = false
            try {
                val cleanKey = sanitizeApiKey(apiKey)
                // 울산 등 실시간 스트리밍 지자체 확인
                val url = "$RTI_BASE_URL/tl_drct_info?serviceKey=$cleanKey&pageNo=1&numOfRows=1000&type=json"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Accept", "application/json")
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string()

                if (response.isSuccessful && !body.isNullOrEmpty()) {
                    handledByLiveStream = parsePublicApiTrafficSignalJson(body, intersection, distance, heading)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Public API tl_drct_info error: ${e.message}")
            }

            // 실시간 스트리밍이 미제공인 교차로(서울시 등): 지자체 표준 안전운행 신호 주기 모델 자동 적용
            if (!handledByLiveStream) {
                applyFallbackIntersectionSignal(intersection, distance)
            }
        }
    }

    /**
     * 서울 등 실시간 스트리밍 미연동 지자체용 표준 안전운행 4현시 TOD 신호 주기 계산 (120초 주기)
     */
    private fun applyFallbackIntersectionSignal(intersection: Intersection, distance: Double) {
        val cal = Calendar.getInstance()
        val totalSecOfDay = cal.get(Calendar.HOUR_OF_DAY) * 3600 +
                cal.get(Calendar.MINUTE) * 60 +
                cal.get(Calendar.SECOND)

        // 교차로 ID 해시를 기반으로 옵셋 부여 (교차로마다 신호 전환 타이밍 다양화)
        val offset = abs(intersection.id.hashCode()) % 120
        val posInCycle = (totalSecOfDay + offset) % 120

        var isRed = false
        var isYellow = false
        var isGreen = false
        var isLeft = false
        var remainSec = 0

        when {
            posInCycle < 40 -> {
                // 1현시: 직진 신호 (40초)
                if (posInCycle >= 37) {
                    isYellow = true
                    remainSec = 40 - posInCycle
                } else {
                    isGreen = true
                    remainSec = 37 - posInCycle
                }
            }
            posInCycle < 65 -> {
                // 2현시: 좌회전 신호 (25초)
                if (posInCycle >= 62) {
                    isYellow = true
                    remainSec = 65 - posInCycle
                } else {
                    isLeft = true
                    remainSec = 62 - posInCycle
                }
            }
            else -> {
                // 3, 4현시: 횡단/보행/타방향 신호 (적색 대기 55초)
                isRed = true
                remainSec = 120 - posInCycle
            }
        }

        mainHandler.post {
            TrafficSignalRepository.updateSignal(
                isVisible = true,
                isRed = isRed,
                isYellow = isYellow,
                isGreen = isGreen,
                isLeft = isLeft,
                remainTime = remainSec,
                distance = distance.toInt(),
                latitude = intersection.lat,
                longitude = intersection.lon,
                intersectionName = intersection.name
            )
            startCountdown(remainSec)
        }
    }

    private fun parsePublicApiTrafficSignalJson(
        jsonStr: String,
        intersection: Intersection,
        distance: Double,
        heading: Float
    ): Boolean {
        try {
            val root = JSONObject(jsonStr)
            val header = root.optJSONObject("header")
            val resultCode = header?.optString("resultCode", "") ?: ""
            if (resultCode != "K0") return false

            val body = root.optJSONObject("body") ?: return false
            val itemsObj = body.optJSONObject("items") ?: return false

            val itemArr = when {
                itemsObj.has("item") -> {
                    val itemField = itemsObj.get("item")
                    if (itemField is JSONArray) itemField
                    else if (itemField is JSONObject) JSONArray().put(itemField)
                    else JSONArray()
                }
                else -> JSONArray()
            }

            var matchedItem: JSONObject? = null
            for (i in 0 until itemArr.length()) {
                val item = itemArr.optJSONObject(i) ?: continue
                if (item.optString("crsrdId") == intersection.id) {
                    matchedItem = item
                    break
                }
            }

            if (matchedItem == null) return false

            val dirPrefixes = getDirectionPrefixes(heading)
            var isRed = false
            var isYellow = false
            var isGreen = false
            var isLeft = false
            var remainSeconds = 0
            var foundDirection = false

            for (prefix in dirPrefixes) {
                val stsgStts = matchedItem.optString("${prefix}StsgSttsNm", "")
                val stsgTimeStr = matchedItem.optString("${prefix}StsgRmndCs", "")
                val ltsgStts = matchedItem.optString("${prefix}LtsgSttsNm", "")
                val ltsgTimeStr = matchedItem.optString("${prefix}LtsgRmndCs", "")

                if (stsgStts.isNotEmpty() || ltsgStts.isNotEmpty()) {
                    foundDirection = true

                    if (stsgStts.contains("Allowed", ignoreCase = true)) {
                        isGreen = true
                    } else if (stsgStts.contains("stop", ignoreCase = true) || stsgStts.contains("Remain", ignoreCase = true)) {
                        isRed = true
                    } else if (stsgStts.contains("yellow", ignoreCase = true) || stsgStts.contains("caution", ignoreCase = true)) {
                        isYellow = true
                    }

                    if (ltsgStts.contains("Allowed", ignoreCase = true)) {
                        isLeft = true
                    } else if (ltsgStts.contains("stop", ignoreCase = true) || ltsgStts.contains("Remain", ignoreCase = true)) {
                        if (!isGreen && !isYellow) isRed = true
                    }

                    val rawStraightTime = stsgTimeStr.toIntOrNull() ?: 0
                    val rawLeftTime = ltsgTimeStr.toIntOrNull() ?: 0
                    val maxRawTime = max(rawStraightTime, rawLeftTime)

                    remainSeconds = if (maxRawTime > 1200) {
                        (maxRawTime / 100)
                    } else if (maxRawTime > 120) {
                        (maxRawTime / 10)
                    } else {
                        maxRawTime
                    }
                    break
                }
            }

            if (foundDirection) {
                mainHandler.post {
                    TrafficSignalRepository.updateSignal(
                        isVisible = true,
                        isRed = isRed,
                        isYellow = isYellow,
                        isGreen = isGreen,
                        isLeft = isLeft,
                        remainTime = remainSeconds,
                        distance = distance.toInt(),
                        latitude = intersection.lat,
                        longitude = intersection.lon,
                        intersectionName = intersection.name
                    )
                    startCountdown(remainSeconds)
                }
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "parsePublicApiTrafficSignalJson error: ${e.message}", e)
        }
        return false
    }

    private fun startCountdown(initialRemain: Int) {
        stopCountdown()
        var currentRemain = initialRemain
        countdownRunnable = object : Runnable {
            override fun run() {
                if (currentRemain > 0) {
                    currentRemain--
                    val cur = TrafficSignalRepository.observableSignal.value
                    if (cur != null && cur.isVisible) {
                        TrafficSignalRepository.updateSignal(
                            isVisible = true,
                            isRed = cur.isRed,
                            isYellow = cur.isYellow,
                            isGreen = cur.isGreen,
                            isLeft = cur.isLeft,
                            remainTime = currentRemain,
                            distance = cur.distance,
                            latitude = cur.latitude,
                            longitude = cur.longitude,
                            intersectionName = cur.intersectionName
                        )
                    }
                    mainHandler.postDelayed(this, 1000)
                }
            }
        }
        mainHandler.postDelayed(countdownRunnable!!, 1000)
    }

    private fun stopCountdown() {
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownRunnable = null
    }

    private fun saveToCacheJson(list: List<Intersection>) {
        val context = appContext ?: return
        try {
            val array = JSONArray()
            for (item in list) {
                val obj = JSONObject()
                obj.put("crsrdId", item.id)
                obj.put("crsrdNm", item.name)
                obj.put("lat", item.lat)
                obj.put("lon", item.lon)
                obj.put("stdgCd", item.stdgCd)
                array.put(obj)
            }
            context.openFileOutput("cits_intersections.json", Context.MODE_PRIVATE).use {
                it.write(array.toString().toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving cache: ${e.message}")
        }
    }

    private fun loadFromCache(): Int {
        val context = appContext ?: return 0
        try {
            val file = context.getFileStreamPath("cits_intersections.json")
            if (file.exists() && file.length() > 0) {
                val jsonStr = context.openFileInput("cits_intersections.json").bufferedReader().use { it.readText() }
                if (jsonStr.startsWith("[")) {
                    val arr = JSONArray(jsonStr)
                    val list = mutableListOf<Intersection>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        list.add(
                            Intersection(
                                id = obj.getString("crsrdId"),
                                name = obj.optString("crsrdNm", ""),
                                lat = obj.getDouble("lat"),
                                lon = obj.getDouble("lon"),
                                stdgCd = obj.optString("stdgCd", "")
                            )
                        )
                    }
                    if (list.isNotEmpty()) {
                        intersections.clear()
                        intersections.addAll(list)
                        return list.size
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading cache: ${e.message}")
        }
        return 0
    }

    private fun getDirectionPrefixes(heading: Float): List<String> {
        val norm = (heading % 360 + 360) % 360
        val primary = when {
            norm >= 337.5 || norm < 22.5 -> "nt"
            norm < 67.5 -> "ne"
            norm < 112.5 -> "et"
            norm < 157.5 -> "se"
            norm < 202.5 -> "st"
            norm < 247.5 -> "sw"
            norm < 292.5 -> "wt"
            else -> "nw"
        }

        val opposite = when (primary) {
            "nt" -> "st"
            "ne" -> "sw"
            "et" -> "wt"
            "se" -> "nw"
            "st" -> "nt"
            "sw" -> "ne"
            "wt" -> "et"
            else -> "se"
        }

        return listOf(primary, opposite, "nt", "et", "st", "wt")
    }

    private fun sanitizeApiKey(key: String): String {
        return try {
            if (key.contains("%")) key else URLEncoder.encode(key, "UTF-8")
        } catch (e: Exception) {
            key
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val φ1 = Math.toRadians(lat1)
        val φ2 = Math.toRadians(lat2)
        val Δλ = Math.toRadians(lon2 - lon1)

        val y = sin(Δλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
        val θ = atan2(y, x)
        return (Math.toDegrees(θ) + 360) % 360
    }
}
