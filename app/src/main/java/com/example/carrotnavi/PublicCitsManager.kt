package com.example.carrotnavi

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.math.*

/**
 * 행정안전부 한국지역정보개발원 (전국 통합데이터) 교통안전 신호등 실시간 정보 Open API 연동 매니저
 * Base URL: https://apis.data.go.kr/B551982/rti
 * 1) GET /crsrd_map_info : 지자체별 교차로 위경도 맵 정보
 * 2) GET /tl_drct_info : 실시간 신호등 상태 및 잔여시간 정보
 */
object PublicCitsManager {
    private const val TAG = "PublicCitsManager"
    private const val BASE_URL = "https://apis.data.go.kr/B551982/rti"

    data class Intersection(
        val id: String,
        val name: String,
        val lat: Double,
        val lon: Double,
        val stdgCd: String
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private var appContext: Context? = null
    private var isEnabled = false
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

    fun init(context: Context) {
        appContext = context.applicationContext
        reloadConfig()
    }

    fun reloadConfig() {
        val context = appContext ?: return
        val prefs = context.getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
        val newEnabled = prefs.getBoolean("CITS_PUBLIC_ENABLED", false)
        val newApiKey = prefs.getString("CITS_PUBLIC_API_KEY", "")?.trim() ?: ""
        val newStdgCd = prefs.getString("CITS_PUBLIC_STDG_CD", "1100000000")?.trim() ?: "1100000000"

        val keyChanged = (apiKey != newApiKey)
        val stdgChanged = (stdgCd != newStdgCd)

        isEnabled = newEnabled
        apiKey = newApiKey
        stdgCd = if (newStdgCd.isNotEmpty()) newStdgCd else "1100000000"

        Log.d(TAG, "Config reloaded: isEnabled=$isEnabled, apiKey.length=${apiKey.length}, stdgCd=$stdgCd")

        if (!isEnabled || apiKey.isEmpty()) {
            currentTargetIntersection = null
            TrafficSignalRepository.clear()
            return
        }

        if (!isMapLoaded || keyChanged || stdgChanged) {
            loadIntersectionMap()
        }
    }

    /**
     * 교차로 맵 데이터(위경도 및 교차로ID) 로딩
     */
    fun loadIntersectionMap() {
        if (isLoadingMap) return
        if (apiKey.isEmpty()) return

        scope.launch {
            isLoadingMap = true
            try {
                // 로컬 캐시 먼저 확인
                val loadedFromCache = loadFromCache()
                if (loadedFromCache > 0 && !isMapLoaded) {
                    isMapLoaded = true
                    Log.d(TAG, "Loaded $loadedFromCache intersections from local cache.")
                }

                // API 키 인코딩 정리 (공공데이터포털 serviceKey는 이미 인코딩되어 있거나 디코딩된 상태가 섞여있음)
                val cleanKey = sanitizeApiKey(apiKey)
                val allIntersections = mutableListOf<Intersection>()
                var page = 1
                var totalPages = 1
                val pageSize = 1000

                do {
                    val stdgParam = if (stdgCd.isNotEmpty()) "&stdgCd=$stdgCd" else ""
                    val url = "$BASE_URL/crsrd_map_info?serviceKey=$cleanKey&pageNo=$page&numOfRows=$pageSize&type=json$stdgParam"

                    Log.d(TAG, "Fetching intersection map page $page: stdgCd=$stdgCd")
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
                        Log.d(TAG, "Page $page loaded ${pageItems.size} items (total $total)")
                        page++
                    } else {
                        Log.w(TAG, "Failed to fetch crsrd_map_info page $page: code=${response.code}")
                        break
                    }
                } while (page <= totalPages && page <= 5)

                if (allIntersections.isNotEmpty()) {
                    intersections.clear()
                    intersections.addAll(allIntersections)
                    isMapLoaded = true
                    saveToCacheJson(allIntersections)
                    Log.d(TAG, "Successfully loaded and cached ${allIntersections.size} intersections from API.")
                } else {
                    Log.w(TAG, "No intersections found from API.")
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

    /**
     * 차량 위치 및 진행 방향 업데이트 시 호출
     * @param lat 차량 위도
     * @param lon 차량 경도
     * @param heading 차량 진행 방위각 (0~360도, 북=0, 동=90, 남=180, 서=270)
     */
    fun onLocationUpdate(lat: Double, lon: Double, heading: Float) {
        if (!isEnabled || apiKey.isEmpty()) return
        if (lat <= 0.0 || lon <= 0.0) return

        lastLocationLat = lat
        lastLocationLon = lon
        lastLocationHeading = heading

        if (!isMapLoaded) {
            if (!isLoadingMap) loadIntersectionMap()
            return
        }

        // 전방 교차로 검색 (15m ~ 450m 이내, 차량 진행 각도 ±65도 이내)
        var nearest: Intersection? = null
        var minDistance = Double.MAX_VALUE

        for (inter in intersections) {
            val dist = calculateDistance(lat, lon, inter.lat, inter.lon)
            if (dist in 15.0..450.0) {
                val bearingToInter = calculateBearing(lat, lon, inter.lat, inter.lon)
                val angleDiff = abs((bearingToInter - heading + 540) % 360 - 180)

                // 전방 65도 범위 내
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
            // 1.5초 주기로 실시간 신호 API 조회
            if (now - lastSignalFetchTime >= 1400) {
                lastSignalFetchTime = now
                fetchTrafficSignal(nearest, dist, heading)
            } else {
                // 주기 사이에는 거리만 보정하여 업데이트
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
            // 전방 교차로가 없거나 통과한 경우
            if (currentTargetIntersection != null) {
                currentTargetIntersection = null
                TrafficSignalRepository.clear()
            }
        }
    }

    /**
     * 특정 교차로의 실시간 신호 조회 (/tl_drct_info)
     */
    private fun fetchTrafficSignal(intersection: Intersection, distance: Double, heading: Float) {
        scope.launch {
            try {
                val cleanKey = sanitizeApiKey(apiKey)
                val stdgParam = if (stdgCd.isNotEmpty()) "&stdgCd=$stdgCd" else ""
                val url = "$BASE_URL/tl_drct_info?serviceKey=$cleanKey&pageNo=1&numOfRows=1000&type=json$stdgParam"

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Accept", "application/json")
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string()

                if (response.isSuccessful && !body.isNullOrEmpty()) {
                    parseTrafficSignalJson(body, intersection, distance, heading)
                } else {
                    Log.w(TAG, "Failed tl_drct_info: code=${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching tl_drct_info: ${e.message}")
            }
        }
    }

    private fun parseTrafficSignalJson(
        jsonStr: String,
        intersection: Intersection,
        distance: Double,
        heading: Float
    ) {
        try {
            val root = JSONObject(jsonStr)
            val header = root.optJSONObject("header")
            val resultCode = header?.optString("resultCode", "") ?: ""
            if (resultCode != "K0") {
                val resultMsg = header?.optString("resultMsg", "") ?: ""
                Log.d(TAG, "tl_drct_info result: $resultCode ($resultMsg) for stdgCd=$stdgCd")
                return
            }

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

            if (matchedItem == null) {
                Log.d(TAG, "Signal data for crsrdId=${intersection.id} not found in response")
                return
            }

            // 차량의 진입 방향에 따른 8방향 접두사 결정 (북, 북동, 동, 남동, 남, 남서, 서, 북서)
            val dirPrefixes = getDirectionPrefixes(heading)

            var isRed = false
            var isYellow = false
            var isGreen = false
            var isLeft = false
            var remainSeconds = 0
            var foundDirection = false

            for (prefix in dirPrefixes) {
                val stsgStts = matchedItem.optString("${prefix}StsgSttsNm", "") // 직진 신호 상태
                val stsgTimeStr = matchedItem.optString("${prefix}StsgRmndCs", "") // 직진 잔여시간
                val ltsgStts = matchedItem.optString("${prefix}LtsgSttsNm", "") // 좌회전 신호 상태
                val ltsgTimeStr = matchedItem.optString("${prefix}LtsgRmndCs", "") // 좌회전 잔여시간

                if (stsgStts.isNotEmpty() || ltsgStts.isNotEmpty()) {
                    foundDirection = true

                    // 직진 상태
                    if (stsgStts.contains("Allowed", ignoreCase = true)) {
                        isGreen = true
                    } else if (stsgStts.contains("stop", ignoreCase = true) || stsgStts.contains("Remain", ignoreCase = true)) {
                        isRed = true
                    } else if (stsgStts.contains("yellow", ignoreCase = true) || stsgStts.contains("caution", ignoreCase = true)) {
                        isYellow = true
                    }

                    // 좌회전 상태
                    if (ltsgStts.contains("Allowed", ignoreCase = true)) {
                        isLeft = true
                    } else if (ltsgStts.contains("stop", ignoreCase = true) || ltsgStts.contains("Remain", ignoreCase = true)) {
                        if (!isGreen && !isYellow) isRed = true
                    }

                    // 잔여시간 파싱 (Cs 단위: 센티초 또는 데시초)
                    val rawStraightTime = stsgTimeStr.toIntOrNull() ?: 0
                    val rawLeftTime = ltsgTimeStr.toIntOrNull() ?: 0
                    val maxRawTime = max(rawStraightTime, rawLeftTime)

                    remainSeconds = if (maxRawTime > 1200) {
                        // 100단위 센티초 (예: 3500 -> 35초)
                        (maxRawTime / 100)
                    } else if (maxRawTime > 120) {
                        // 10단위 데시초 (예: 350 -> 35초)
                        (maxRawTime / 10)
                    } else {
                        maxRawTime
                    }

                    Log.d(TAG, "Signal parsed: dir=$prefix, stsg=$stsgStts, ltsg=$ltsgStts, remain=${remainSeconds}s, dist=${distance.toInt()}m")
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseTrafficSignalJson error: ${e.message}", e)
        }
    }

    /**
     * 차량 진행 방위각에 따른 우선순위 8방향 접두사 목록 반환
     */
    private fun getDirectionPrefixes(heading: Float): List<String> {
        val norm = (heading % 360 + 360) % 360
        val primary = when {
            norm >= 337.5 || norm < 22.5 -> "nt" // 북쪽 진행 -> 북쪽 신호 또는 남쪽 진입
            norm < 67.5 -> "ne"
            norm < 112.5 -> "et"
            norm < 157.5 -> "se"
            norm < 202.5 -> "st"
            norm < 247.5 -> "sw"
            norm < 292.5 -> "wt"
            else -> "nw"
        }

        // 마주보는 반대 방향 (신호제어기 설치 기준에 따라 진입 방향 또는 진행 방향으로 기재됨)
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
            if (key.contains("%")) {
                // 이미 URL 인코딩된 상태면 그대로 사용
                key
            } else {
                URLEncoder.encode(key, "UTF-8")
            }
        } catch (e: Exception) {
            key
        }
    }

    /**
     * 두 위경도 좌표 간 거리 계산 (Haversine 공식, 단위: 미터)
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // 지구 반지름 (m)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    /**
     * 좌표 1에서 좌표 2로의 방위각(Bearing) 계산 (0~360도)
     */
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
