package com.example.carrotnavi

import android.content.Context
import android.content.SharedPreferences
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.math.*

/**
 * 실시간 C-ITS 교통신호등 연동 매니저
 * 1) UTIC 중계서버 (MODE_UTIC_PROXY):
 *    경찰청 도시교통정보센터(TSI-Hub) 6,389개 교차로 TOD 기반 실시간 신호/잔여시간 연동
 *    GET {proxyUrl}/api/cits/nearby_signal?lat=...&lon=...&heading=...&max_dist=500
 * 2) 행정안전부 공공데이터포털 (MODE_PUBLIC_API):
 *    한국지역정보개발원 RTI API 직접 호출 (/crsrd_map_info, /tl_drct_info)
 */
object PublicCitsManager {
    private const val TAG = "PublicCitsManager"
    private const val RTI_BASE_URL = "https://apis.data.go.kr/B551982/rti"
    const val DEFAULT_PROXY_URL = "https://comma-nav-server.leegrt.org"

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
    private var citsMode = MODE_UTIC_PROXY
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
        val newEnabled = prefs.getBoolean("CITS_PUBLIC_ENABLED", false)
        val newMode = prefs.getString("CITS_MODE", MODE_UTIC_PROXY) ?: MODE_UTIC_PROXY
        val newProxyUrl = prefs.getString("CITS_PROXY_URL", DEFAULT_PROXY_URL)?.trim() ?: DEFAULT_PROXY_URL
        val newApiKey = prefs.getString("CITS_PUBLIC_API_KEY", "")?.trim() ?: ""
        val newStdgCd = prefs.getString("CITS_PUBLIC_STDG_CD", "1100000000")?.trim() ?: "1100000000"

        val modeChanged = (citsMode != newMode)
        val keyChanged = (apiKey != newApiKey)
        val stdgChanged = (stdgCd != newStdgCd)

        isEnabled = newEnabled
        citsMode = newMode
        proxyUrl = if (newProxyUrl.isNotEmpty()) newProxyUrl else DEFAULT_PROXY_URL
        apiKey = newApiKey
        stdgCd = if (newStdgCd.isNotEmpty()) newStdgCd else "1100000000"

        Log.d(TAG, "Config reloaded: isEnabled=$isEnabled, mode=$citsMode, proxyUrl=$proxyUrl, apiKey.len=${apiKey.length}, stdgCd=$stdgCd")

        if (!isEnabled || (citsMode == MODE_PUBLIC_API && apiKey.isEmpty())) {
            currentTargetIntersection = null
            stopCountdown()
            TrafficSignalRepository.clear()
            return
        }

        if (citsMode == MODE_PUBLIC_API) {
            if (!isMapLoaded || keyChanged || stdgChanged || modeChanged) {
                loadIntersectionMap()
            }
        }
    }

    /**
     * 차량 위치 및 진행 방향 업데이트 시 호출
     * @param lat 차량 위도
     * @param lon 차량 경도
     * @param heading 차량 진행 방위각 (0~360도, 북=0, 동=90, 남=180, 서=270)
     */
    fun onLocationUpdate(lat: Double, lon: Double, heading: Float) {
        if (!isEnabled) return
        if (lat <= 0.0 || lon <= 0.0) return

        lastLocationLat = lat
        lastLocationLon = lon
        lastLocationHeading = heading

        // 1) UTIC 중계서버 모드 (경찰청 TSI-Hub 6,389개 교차로)
        if (citsMode == MODE_UTIC_PROXY) {
            handleProxySignalUpdate(lat, lon, heading)
            return
        }

        // 2) 행정안전부 공공데이터포털 직접 연동 모드
        if (apiKey.isEmpty()) return
        if (!isMapLoaded) {
            if (!isLoadingMap) loadIntersectionMap()
            return
        }

        handlePublicApiSignalUpdate(lat, lon, heading)
    }

    /**
     * 중계서버 실시간 신호 조회 핸들러
     */
    private fun handleProxySignalUpdate(lat: Double, lon: Double, heading: Float) {
        val now = System.currentTimeMillis()
        if (now - lastSignalFetchTime >= 1200) {
            lastSignalFetchTime = now
            fetchNearbySignalFromProxy(lat, lon, heading)
        } else {
            // 폴링 주기 사이에는 거리만 업데이트
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
        }
    }

    private fun fetchNearbySignalFromProxy(lat: Double, lon: Double, heading: Float) {
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
                    parseProxySignalJson(body)
                } else {
                    Log.w(TAG, "Proxy returned code=${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching from proxy: ${e.message}")
            }
        }
    }

    private fun parseProxySignalJson(jsonStr: String) {
        try {
            val root = JSONObject(jsonStr)
            val matched = root.optBoolean("matched", false)
            if (!matched) {
                if (currentTargetIntersection != null) {
                    currentTargetIntersection = null
                    stopCountdown()
                    TrafficSignalRepository.clear()
                }
                return
            }

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

            // UI 업데이트
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

            // 초 단위 카운트다운 타이머 시작/동기화
            startCountdown(remainSec)

        } catch (e: Exception) {
            Log.e(TAG, "parseProxySignalJson error: ${e.message}", e)
        }
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

    // ----------------- 공공데이터포털 직접 연동 로직 (MODE_PUBLIC_API) -----------------

    private fun handlePublicApiSignalUpdate(lat: Double, lon: Double, heading: Float) {
        var nearest: Intersection? = null
        var minDistance = Double.MAX_VALUE

        for (inter in intersections) {
            val dist = calculateDistance(lat, lon, inter.lat, inter.lon)
            if (dist in 15.0..450.0) {
                val bearingToInter = calculateBearing(lat, lon, inter.lat, inter.lon)
                val angleDiff = abs((bearingToInter - heading + 540) % 360 - 180)

                if (angleDiff <= 65.0) {
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

            val now = System.currentTimeMillis()
            if (now - lastSignalFetchTime >= 1400) {
                lastSignalFetchTime = now
                fetchTrafficSignalFromPublicApi(nearest, dist, heading)
            } else {
                val currentSig = TrafficSignalRepository.observableSignal.value
                if (currentSig != null && currentSig.isVisible) {
                    TrafficSignalRepository.updateSignal(
                        isVisible = true,
                        isRed = currentSig.isRed,
                        isYellow = currentSig.isYellow,
                        isGreen = currentSig.isGreen,
                        isLeft = currentSig.isLeft,
                        remainTime = currentSig.remainTime,
                        distance = dist.toInt(),
                        latitude = nearest.lat,
                        longitude = nearest.lon,
                        intersectionName = nearest.name
                    )
                }
            }
        } else {
            if (currentTargetIntersection != null) {
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
                if (loadedFromCache > 0 && !isMapLoaded) {
                    isMapLoaded = true
                    Log.d(TAG, "Loaded $loadedFromCache intersections from local cache.")
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

    private fun fetchTrafficSignalFromPublicApi(intersection: Intersection, distance: Double, heading: Float) {
        scope.launch {
            try {
                val cleanKey = sanitizeApiKey(apiKey)
                val stdgParam = if (stdgCd.isNotEmpty()) "&stdgCd=$stdgCd" else ""
                val url = "$RTI_BASE_URL/tl_drct_info?serviceKey=$cleanKey&pageNo=1&numOfRows=1000&type=json$stdgParam"

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Accept", "application/json")
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string()

                if (response.isSuccessful && !body.isNullOrEmpty()) {
                    parsePublicApiTrafficSignalJson(body, intersection, distance, heading)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching tl_drct_info: ${e.message}")
            }
        }
    }

    private fun parsePublicApiTrafficSignalJson(
        jsonStr: String,
        intersection: Intersection,
        distance: Double,
        heading: Float
    ) {
        try {
            val root = JSONObject(jsonStr)
            val header = root.optJSONObject("header")
            val resultCode = header?.optString("resultCode", "") ?: ""
            if (resultCode != "K0") return

            val body = root.optJSONObject("body") ?: return
            val itemsObj = body.optJSONObject("items") ?: return

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

            if (matchedItem == null) return

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
        } catch (e: Exception) {
            Log.e(TAG, "parsePublicApiTrafficSignalJson error: ${e.message}", e)
        }
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
