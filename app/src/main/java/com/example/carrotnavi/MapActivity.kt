package com.example.carrotnavi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.carrotnavi.databinding.ActivityMapBinding

import com.tmapmobility.tmap.tmapsdk.ui.util.TmapUISDK
import com.tmapmobility.tmap.tmapsdk.ui.util.TmapUISDK.Companion.getFragment
import com.tmapmobility.tmap.tmapsdk.ui.util.TmapUISDK.Companion.initialize
import com.tmapmobility.tmap.tmapsdk.ui.fragment.NavigationFragment
import androidx.lifecycle.Observer
import android.content.res.Configuration
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MapActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMapBinding
    private lateinit var hudBinding: com.example.carrotnavi.databinding.LayoutHudOverlaysBinding
    private lateinit var hudOverlayManager: HudOverlayManager

    private val searchRetrofit: retrofit2.Retrofit by lazy {
        retrofit2.Retrofit.Builder()
            .baseUrl("https://dapi.kakao.com/")
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
    }
    private val kakaoSearchApi: KakaoSearchApi by lazy {
        searchRetrofit.create(KakaoSearchApi::class.java)
    }

private var navigationFragment: NavigationFragment? = null
        private var isOverlayVisible = true

    private val preferenceChangeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        when (key) {
            "BLOCK_SPEED_OFFSET" -> {
                runOnUiThread {
                    val currentOffset = sharedPreferences.getInt(key, 0)
                                    }
            }
            "OVERRIDE_TBT_TURN_TYPE" -> {
                runOnUiThread {
                    val turnTypeOverride = sharedPreferences.getInt(key, -1)
                    val displayText = when (turnTypeOverride) {
                        -1 -> "끄기"
                        0 -> "끄기"
                        else -> turnTypeOverride.toString()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        getSharedPreferences("CarrotNaviPrefs", android.content.Context.MODE_PRIVATE).edit().putBoolean("IS_DEBUG_MODE", false).apply()
        super.onCreate(savedInstanceState)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        hudBinding = com.example.carrotnavi.databinding.LayoutHudOverlaysBinding.bind(binding.root)
        hudOverlayManager = HudOverlayManager(this, hudBinding, this)
        hudOverlayManager.binding.btnSearchAddress.setOnClickListener { showSearchDialog() }
// 자동 업데이트 체크
        AutoUpdater.checkForUpdates(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

                val sharedPref = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
        val appKey = sharedPref.getString("APP_KEY", "") ?: ""
        
        sharedPref.registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        // Force Tmap SDK to run in background
        getSharedPreferences("user.settings.info", Context.MODE_PRIVATE).edit().putBoolean("set_suspend_in_background", false).apply()
        
        if (appKey.isEmpty()) {
            Toast.makeText(this, "App Key가 설정되지 않았습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        

        initTmapSdk(appKey)
    }

    
    private fun initTmapSdk(appKey: String) {
        // TmapUISDK 초기화
        initialize(this, "", appKey, "", "", object : TmapUISDK.InitializeListener {
            override fun onSuccess() {
                runOnUiThread {
                    
                    try {
                        val methods = NavigationFragment::class.java.methods
                        for (m in methods) {
                            if (m.name.contains("mute", ignoreCase = true) || m.name.contains("volume", ignoreCase = true) || m.name.contains("sound", ignoreCase = true) || m.name.contains("audio", ignoreCase = true)) {
                                Log.e("TmapVolume", "NavigationFragment method: ${m.name}")
                            }
                        }
                        val uiMethods = TmapUISDK::class.java.methods
                        for (m in uiMethods) {
                            if (m.name.contains("mute", ignoreCase = true) || m.name.contains("volume", ignoreCase = true) || m.name.contains("sound", ignoreCase = true) || m.name.contains("audio", ignoreCase = true)) {
                                Log.e("TmapVolume", "TmapUISDK method: ${m.name}")
                            }
                        }
                        
                        // Let's also check TmapUISDK.Companion methods just in case
                        val compMethods = TmapUISDK.Companion::class.java.methods
                        for (m in compMethods) {
                            if (m.name.contains("mute", ignoreCase = true) || m.name.contains("volume", ignoreCase = true) || m.name.contains("sound", ignoreCase = true) || m.name.contains("audio", ignoreCase = true)) {
                                Log.e("TmapVolume", "TmapUISDK.Companion method: ${m.name}")
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    startSafeDriveMode()
                    startUdpSenderService()
                }
            }

            override fun onFail(errorCode: Int, errorMsg: String?) {
                runOnUiThread {
                    Toast.makeText(this@MapActivity, "Tmap SDK 초기화 실패: $errorMsg", Toast.LENGTH_LONG).show()
                }
            }

            override fun savedRouteInfoExists(dest: String?) {
                // 경로 안내 중이 아니므로 무시
            }
        })
    }

    private fun startSafeDriveMode() {
        navigationFragment = getFragment() as NavigationFragment
        
        supportFragmentManager.beginTransaction()
            .add(R.id.tmapUILayout, navigationFragment!!)
            .commitAllowingStateLoss()

        navigationFragment?.let { frag ->
            // 프래그먼트가 완전히 뷰에 등록된 후 안전운행 모드를 시작하도록 약간의 딜레이를 줍니다.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                frag.startSafeDrive()
                Log.d("MapActivity", "startSafeDrive() called")
                try {
                    // Tmap SDK의 NavigationFragment에 교통 정보 표시 설정 시도
                    val methods = frag.javaClass.methods
                    for (m in methods) {
                        // 1. 교통정보 켜기
                        if (m.name.contains("traffic", ignoreCase = true) && m.parameterTypes.size == 1 && m.parameterTypes[0] == Boolean::class.javaPrimitiveType) {
                            m.invoke(frag, true)
                            Log.d("MapActivity", "Successfully invoked ${m.name}(true)")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MapActivity", "Failed to set map options via reflection: ${e.message}")
                }
            }, 1000)

            frag.setNavigationScreenStateListener(object : com.tmapmobility.tmap.tmapsdk.ui.data.NavigationScreenStateListener {
                override fun onChanged(state: com.tmapmobility.tmap.tmapsdk.ui.data.NavigationScreenState) {
                    Log.d("MapActivity", "NavigationScreenState changed: ${state.javaClass.simpleName}")
                    if (state.javaClass.simpleName.contains("DefaultScreen")) {
                        // TMap Safe Driving has ended (probably user clicked X).
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (!isFinishing) {
                                frag.startSafeDrive()
                            }
                        }, 500)
                    }
                }
            })

            /* 
            // TODO: Use ObservableRouteData instead of DriveStatusListener
            */

            TmapUISDK.observableRouteData.observe(this@MapActivity, Observer { data ->
                data?.let {
                    Log.e("SdiDebug", "observableRouteData class: ${it.javaClass.name}")
                    Log.e("SdiDebug", "observableRouteData: $it")
                }
            })
            TmapUISDK.observableEDCData.observe(this@MapActivity, Observer { data ->
                data?.let {
                    Log.e("SdiDebug", "observableEDCData class: ${it.javaClass.name}")
                    Log.e("SdiDebug", "observableEDCData: $it")
                    
                    TmapUISDK.setVolume(this@MapActivity, 0)
                    
                    // 도로 기본 제한속도 추출 및 UI 업데이트
                    val realRoadLimit = getRoadLimitSpeedFromEngine()
                    runOnUiThread {
                        if (realRoadLimit >= 30) {
                            hudBinding.llRoadSpeedLimit?.visibility = android.view.View.VISIBLE
                            hudBinding.tvRoadSpeedLimit?.text = realRoadLimit.toString()
                        }
                    }

                    if (it is android.os.Bundle) {
                        extractAndDisplaySdiInfo(it)
                    }
                }
            })

            // 안드로이드 기본 GPS 상태 리스너 등록
            try {
                val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                locationManager.registerGnssStatusCallback(object : android.location.GnssStatus.Callback() {
                    override fun onStarted() {
                        hudBinding.ivGpsIcon?.setColorFilter(android.graphics.Color.YELLOW)
                        hudBinding.tvGpsStatus?.text = "탐색 중"
                        hudBinding.tvGpsStatus?.setTextColor(android.graphics.Color.YELLOW)
                    }
                    override fun onStopped() {
                        hudBinding.ivGpsIcon?.setColorFilter(android.graphics.Color.RED)
                        hudBinding.tvGpsStatus?.text = "끊김 (NO)"
                        hudBinding.tvGpsStatus?.setTextColor(android.graphics.Color.RED)
                    }
                    override fun onFirstFix(ttffMillis: Int) {
                        hudBinding.ivGpsIcon?.setColorFilter(android.graphics.Color.GREEN)
                        hudBinding.tvGpsStatus?.text = "수신 양호"
                        hudBinding.tvGpsStatus?.setTextColor(android.graphics.Color.GREEN)
                    }
                    override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                        var usedInFix = 0
                        for (i in 0 until status.satelliteCount) {
                            if (status.usedInFix(i)) usedInFix++
                        }
                        if (usedInFix >= 4) {
                            hudBinding.ivGpsIcon?.setColorFilter(android.graphics.Color.GREEN)
                            hudBinding.tvGpsStatus?.text = "GOOD (위성 $usedInFix)"
                            hudBinding.tvGpsStatus?.setTextColor(android.graphics.Color.GREEN)
                        } else {
                            hudBinding.ivGpsIcon?.setColorFilter(android.graphics.Color.RED)
                            hudBinding.tvGpsStatus?.text = "BAD (위성 $usedInFix)"
                            hudBinding.tvGpsStatus?.setTextColor(android.graphics.Color.RED)
                        }
                    }
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            } catch (e: SecurityException) {
                Log.e("MapActivity", "GPS Permission Error")
            }
        }
    }

    private fun startUdpSenderService() {
        val intent = Intent(this, UdpSenderService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val sharedPref = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
        sharedPref.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        val intent = Intent(this, UdpSenderService::class.java)
        stopService(intent)
    }


    private fun extractAndDisplaySdiInfo(bundle: android.os.Bundle) {
        try {
            val sdiObj = bundle.get("firstSDIInfo")
            if (sdiObj != null) {
                val sdiJsonStr = if (sdiObj is String) sdiObj else com.google.gson.Gson().toJson(sdiObj)
                val json = org.json.JSONObject(sdiJsonStr)
                
                val sdiType = json.optInt("nSdiType", 0)
                var sdiSpeedLimit = json.optInt("nSdiSpeedLimit", 0)
                val sdiDist = json.optInt("nSdiDist", 0)
                
                val blockDist = json.optInt("nSdiBlockDist", 0)
                val blockTime = json.optInt("nSdiBlockTime", 0)
                val blockAvgSpeed = json.optInt("nSdiBlockAverageSpeed", 0)
                val isBlockSection = sdiType == 2 || sdiType == 3 || sdiType == 4 || json.optBoolean("bSdiBlockSection", false)
                
                var isBoosting = false
                val sharedPref = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
                val offset = sharedPref.getInt("BLOCK_SPEED_OFFSET", 0)
                if (isBlockSection && offset > 0 && sdiSpeedLimit > 0) {
                    if (blockAvgSpeed > 0 && sdiSpeedLimit - blockAvgSpeed >= 1) {
                        isBoosting = true
                    }
                }
                
                if (sdiType == 22 && sdiSpeedLimit <= 0) {
                    sdiSpeedLimit = 30
                }
                
                val useKmFormat = sharedPref.getBoolean("USE_KM_DISTANCE_FORMAT", true)
                fun formatDistance(dist: Int): String {
                    return if (useKmFormat && dist >= 1000) {
                        String.format("%.1fkm", dist / 1000.0)
                    } else {
                        "${dist}m"
                    }
                }
                
                runOnUiThread {
                                        if (isBoosting) {
                                            } else {
                                            }
                    
                    if (isBlockSection && blockDist > 0) {
                        hudBinding.llBlockInfo?.visibility = android.view.View.VISIBLE
                        hudBinding.tvBlockAvgSpeed?.text = "평균: ${blockAvgSpeed}km/h"
                        hudBinding.tvBlockDistTime?.text = String.format("남은: %s (%d:%02d)", formatDistance(blockDist), blockTime / 60, blockTime % 60)
                    } else {
                        hudBinding.llBlockInfo?.visibility = android.view.View.GONE
                    }
                    
                    if (sdiType > 0 || (sdiSpeedLimit > 0 && sdiDist > 0)) {
                        
                        val typeName = when (sdiType) {
                            1 -> "과속 단속"
                            2 -> "구간단속 시작"
                            3 -> "구간단속 종료"
                            4 -> "구간단속 중"
                            7 -> "이동식 단속"
                            22 -> "과속방지턱"
                            33 -> "어린이보호구역"
                            else -> if (sdiSpeedLimit > 0) "단속 카메라" else "주의 구간"
                        }
                    } else {
                    }
                }
            } else {
                runOnUiThread {
                                        hudBinding.llBlockInfo?.visibility = android.view.View.GONE
                }
            }
        } catch (e: Exception) {
            Log.e("MapActivity", "Error extracting SDI Info: ${e.message}")
        }
    }

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
                }
            }
        } catch (e: Exception) {
            Log.e("MapActivity", "Reflection error: ${e.message}")
        }
        return -1
    }

    private fun setAutoRepeatButton(button: android.view.View?, action: () -> Unit) {
        if (button == null) return
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                action()
                handler.postDelayed(this, 100) // 0.1초마다 반복
            }
        }
        button.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    action() // 첫 클릭 시 1회 즉시 실행
                    handler.postDelayed(runnable, 400) // 0.4초 후부터 연속 실행
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    handler.removeCallbacks(runnable)
                    true
                }
                else -> false
            }
        }
    }
    private fun clampAndPreventOverlap(v: View, targetX: Float, targetY: Float, otherViews: List<View>): Pair<Float, Float> {
        var x = targetX
        var y = targetY
        
        // 1. Clamp to parent boundaries
        val parent = v.parent as? View
        if (parent != null) {
            val maxX = (parent.width - v.width).toFloat().coerceAtLeast(0f)
            val maxY = (parent.height - v.height).toFloat().coerceAtLeast(0f)
            x = x.coerceIn(0f, maxX)
            y = y.coerceIn(0f, maxY)
        }

        // 2. Prevent overlap with other views
        val targetRect = android.graphics.RectF(x, y, x + v.width, y + v.height)
        for (other in otherViews) {
            if (other.visibility == View.VISIBLE) {
                val otherRect = android.graphics.RectF(other.x, other.y, other.x + other.width, other.y + other.height)
                if (android.graphics.RectF.intersects(targetRect, otherRect)) {
                    // Try moving only X
                    val rectX = android.graphics.RectF(x, v.y, x + v.width, v.y + v.height)
                    // Try moving only Y
                    val rectY = android.graphics.RectF(v.x, y, v.x + v.width, y + v.height)
                    
                    val canMoveX = !android.graphics.RectF.intersects(rectX, otherRect)
                    val canMoveY = !android.graphics.RectF.intersects(rectY, otherRect)
                    
                    if (canMoveX && !canMoveY) {
                        y = v.y
                    } else if (!canMoveX && canMoveY) {
                        x = v.x
                    } else {
                        // Cannot move independently without collision, stop movement
                        x = v.x
                        y = v.y
                    }
                    targetRect.set(x, y, x + v.width, y + v.height)
                }
            }
        }
        return Pair(x, y)
    }



    private fun showSearchDialog() {
        val prefs = getSharedPreferences("CarrotNaviPrefs", android.content.Context.MODE_PRIVATE)
        val restApiKey = prefs.getString("KAKAO_REST_API_KEY", "") ?: ""
        val appKey = prefs.getString("APP_KEY", "") ?: ""
        
        val keyToUse = if (restApiKey.isNotEmpty()) restApiKey else appKey
        if (keyToUse.isEmpty()) {
            Toast.makeText(this@MapActivity, "App Key 또는 REST API Key가 설정되어 있지 않습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val authorizationHeader = "KakaoAK $keyToUse" 

        val dialogView = layoutInflater.inflate(R.layout.dialog_address_search, null)
        val etKeyword = dialogView.findViewById<android.widget.EditText>(R.id.etSearchKeyword)
        val rbSortDistance = dialogView.findViewById<android.widget.RadioButton>(R.id.rbSortDistance)
        val rgSort = dialogView.findViewById<android.widget.RadioGroup>(R.id.rgSort)
        val btnSearch = dialogView.findViewById<android.widget.Button>(R.id.btnSearch)
        val rvResults = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvSearchResults)
        val pbLoading = dialogView.findViewById<android.widget.ProgressBar>(R.id.pbLoading)
        val tvEmpty = dialogView.findViewById<android.widget.TextView>(R.id.tvEmptyResult)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this@MapActivity)
            .setTitle("목적지 검색")
            .setView(dialogView)
            .setNegativeButton("닫기", null)
            .create()

        val adapter = AddressSearchAdapter { doc ->
            dialog.dismiss()
            
            // Launch KakaoMapActivity with the selected destination
            val intent = Intent(this@MapActivity, KakaoMapActivity::class.java).apply {
                putExtra("dest_place_name", doc.place_name)
                putExtra("dest_road_address_name", doc.road_address_name)
                putExtra("dest_address_name", doc.address_name)
                putExtra("dest_x", doc.x)
                putExtra("dest_y", doc.y)
            }
            startActivity(intent)
        }
        rvResults.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@MapActivity)
        rvResults.adapter = adapter

        rgSort.setOnCheckedChangeListener { _, _ ->
            if (etKeyword.text.toString().trim().isNotEmpty()) {
                btnSearch.performClick()
            }
        }

        btnSearch.setOnClickListener {
            val query = etKeyword.text.toString().trim()
            if (query.isEmpty()) return@setOnClickListener

            pbLoading.visibility = android.view.View.VISIBLE
            tvEmpty.visibility = android.view.View.GONE
            rvResults.visibility = android.view.View.GONE

            var sortParam: String? = null
            var xParam: String? = null
            var yParam: String? = null
            var radiusParam: Int? = null

            if (rbSortDistance.isChecked) {
                sortParam = "distance"
                try {
                    val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                    val location = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                        ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                    
                    if (location != null) {
                        xParam = location.longitude.toString()
                        yParam = location.latitude.toString()
                        radiusParam = 20000
                    } else {
                        Toast.makeText(this@MapActivity, "현재 위치를 알 수 없어 거리순 정렬을 사용할 수 없습니다.", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                } catch (e: SecurityException) {
                    Toast.makeText(this@MapActivity, "위치 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            kakaoSearchApi.searchKeyword(authorizationHeader, query, sortParam, xParam, yParam, radiusParam).enqueue(object : retrofit2.Callback<KakaoSearchResponse> {
                override fun onResponse(call: retrofit2.Call<KakaoSearchResponse>, response: retrofit2.Response<KakaoSearchResponse>) {
                    pbLoading.visibility = android.view.View.GONE
                    if (response.isSuccessful && response.body() != null) {
                        val docs = response.body()!!.documents
                        if (docs.isEmpty()) {
                            tvEmpty.visibility = android.view.View.VISIBLE
                        } else {
                            rvResults.visibility = android.view.View.VISIBLE
                            adapter.submitList(docs)
                        }
                    } else {
                        if (response.code() == 401) {
                            Toast.makeText(this@MapActivity, "API 인증 실패 (401). REST API 키를 확인해주세요.", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@MapActivity, "검색 실패: ${response.code()}", Toast.LENGTH_SHORT).show()
                        }
                        tvEmpty.text = "검색 오류 발생"
                        tvEmpty.visibility = android.view.View.VISIBLE
                    }
                }

                override fun onFailure(call: retrofit2.Call<KakaoSearchResponse>, t: Throwable) {
                    pbLoading.visibility = android.view.View.GONE
                    Toast.makeText(this@MapActivity, "네트워크 오류: ${t.message}", Toast.LENGTH_SHORT).show()
                    tvEmpty.text = "네트워크 오류 발생"
                    tvEmpty.visibility = android.view.View.VISIBLE
                }
            })
        }

        dialog.show()
    }

}
