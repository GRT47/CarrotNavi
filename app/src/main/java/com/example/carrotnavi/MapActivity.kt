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
    private var navigationFragment: NavigationFragment? = null
    private var isEditMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 자동 업데이트 체크
        AutoUpdater.checkForUpdates(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.tvAppVersion?.text = "v${BuildConfig.VERSION_NAME}"
        
        val sharedPref = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
        val appKey = sharedPref.getString("APP_KEY", "") ?: ""
        
        // Force Tmap SDK to run in background
        getSharedPreferences("user.settings.info", Context.MODE_PRIVATE).edit().putBoolean("set_suspend_in_background", false).apply()
        
        if (appKey.isEmpty()) {
            Toast.makeText(this, "App Key가 설정되지 않았습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.btnEditKey.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("auto_start", false)
            startActivity(intent)
            finish()
        }

        binding.btnExitApp.setOnClickListener {
            finishAffinity()
        }

        // 여유가속(마진) 조절 로직
        var currentOffset = sharedPref.getInt("BLOCK_SPEED_OFFSET", 0)
        binding.tvOffsetValue?.text = if (currentOffset > 0) "+$currentOffset" else currentOffset.toString()

        binding.btnOffsetInfo?.setOnClickListener {
            val currentMode = sharedPref.getInt("BLOCK_SPEED_BOOST_MODE", 0)
            var fakeDrop = sharedPref.getInt("BLOCK_SPEED_FAKE_DROP", 10)
            
            val layout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(50, 40, 50, 0)
            }
            
            val rg = android.widget.RadioGroup(this)
            val rbProgressive = android.widget.RadioButton(this).apply { text = "점진적 가속 (기본: 목표속도 도달 시 정지)" }
            val rbFixed = android.widget.RadioButton(this).apply { text = "고정 가속 (강제 풀가속)" }
            rg.addView(rbProgressive)
            rg.addView(rbFixed)
            rg.check(if (currentMode == 0) rbProgressive.id else rbFixed.id)
            
            val tvDrop = android.widget.TextView(this).apply { 
                text = "평균속도 속임값 (km/h): $fakeDrop"
                setPadding(0, 30, 0, 10)
            }
            val sbDrop = android.widget.SeekBar(this).apply {
                max = 30
                progress = fakeDrop
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                        fakeDrop = progress
                        tvDrop.text = "평균속도 속임값 (km/h): $fakeDrop"
                    }
                    override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
                })
            }
            
            rg.setOnCheckedChangeListener { _, _ ->
                // 속임값 설정은 이제 두 모드 모두에서 지원됨
                tvDrop.visibility = android.view.View.VISIBLE
                sbDrop.visibility = android.view.View.VISIBLE
            }
            
            tvDrop.visibility = android.view.View.VISIBLE
            sbDrop.visibility = android.view.View.VISIBLE
            
            layout.addView(rg)
            layout.addView(tvDrop)
            layout.addView(sbDrop)
            
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("보상가속 모드 설정")
                .setView(layout)
                .setPositiveButton("저장") { _, _ ->
                    val mode = if (rg.checkedRadioButtonId == rbProgressive.id) 0 else 1
                    sharedPref.edit()
                        .putInt("BLOCK_SPEED_BOOST_MODE", mode)
                        .putInt("BLOCK_SPEED_FAKE_DROP", fakeDrop)
                        .apply()
                    android.widget.Toast.makeText(this@MapActivity, "설정이 저장되었습니다.", android.widget.Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("도움말") { _, _ ->
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("안내")
                        .setMessage("- 점진적 가속: 평균속도를 (실제평균 - 위젯설정값)으로 보내어 부드럽게 가속합니다.\n- 고정 가속: 평균속도를 (제한속도 - 속임값)으로 보내어 강하게 가속을 유도합니다.")
                        .setPositiveButton("확인", null)
                        .show()
                }
                .setNegativeButton("취소", null)
                .show()
        }

        setAutoRepeatButton(binding.btnDecreaseOffset) {
            if (currentOffset > 0) { // 음수 방지
                currentOffset--
                binding.tvOffsetValue?.text = if (currentOffset > 0) "+$currentOffset" else currentOffset.toString()
                sharedPref.edit().putInt("BLOCK_SPEED_OFFSET", currentOffset).apply()
            }
        }

        setAutoRepeatButton(binding.btnIncreaseOffset) {
            if (currentOffset < 50) { // 최대 50km/h 제한
                currentOffset++
                binding.tvOffsetValue?.text = if (currentOffset > 0) "+$currentOffset" else currentOffset.toString()
                sharedPref.edit().putInt("BLOCK_SPEED_OFFSET", currentOffset).apply()
            }
        }

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        
        val draggables = listOfNotNull(
            binding.llSpeedGroup,
            binding.llStatusGroup,
            binding.llOffset
        )

        draggables.forEach { view ->
            view.post {
                val others = draggables.filter { it != view }
                val viewIdName = resources.getResourceEntryName(view.id)
                restorePosition(view, viewIdName, isLandscape, others)
            }
        }

        draggables.forEach { view ->
            val others = draggables.filter { it != view }
            val viewIdName = resources.getResourceEntryName(view.id)
            makeDraggable(view, viewIdName, isLandscape, others)
        }

        binding.btnEditMode?.setOnClickListener {
            isEditMode = !isEditMode
            updateEditModeForegrounds()
            if (isEditMode) {
                binding.btnEditMode?.setBackgroundResource(R.drawable.shape_circle_green)
                binding.btnRestoreDefaults?.visibility = android.view.View.VISIBLE
                Toast.makeText(this, "오버레이 편집 모드 켜짐", Toast.LENGTH_SHORT).show()
            } else {
                binding.btnEditMode?.setBackgroundResource(R.drawable.shape_circle_gray)
                binding.btnRestoreDefaults?.visibility = android.view.View.GONE
                Toast.makeText(this, "오버레이 편집 모드 꺼짐", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRestoreDefaults?.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("오버레이 배치 초기화")
                .setMessage("모든 위젯의 배치를 기본값으로 복원하시겠습니까?")
                .setPositiveButton("복원") { _, _ ->
                    val sharedPref = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
                    sharedPref.edit()
                        .remove("llSpeedGroup_x_port")
                        .remove("llSpeedGroup_y_port")
                        .remove("llSpeedGroup_x_land")
                        .remove("llSpeedGroup_y_land")
                        .remove("llStatusGroup_x_port")
                        .remove("llStatusGroup_y_port")
                        .remove("llStatusGroup_x_land")
                        .remove("llStatusGroup_y_land")
                        .remove("llOffset_x_port")
                        .remove("llOffset_y_port")
                        .remove("llOffset_x_land")
                        .remove("llOffset_y_land")
                        .apply()

                    // Reset views to layout defaults immediately
                    binding.llSpeedGroup.translationX = 0f
                    binding.llSpeedGroup.translationY = 0f
                    binding.llStatusGroup.translationX = 0f
                    binding.llStatusGroup.translationY = 0f
                    binding.llOffset.translationX = 0f
                    binding.llOffset.translationY = 0f

                    Toast.makeText(this, "기본값으로 복원되었습니다.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null)
                .show()
        }

        OpenpilotStateRepository.state.observe(this) { state ->
            binding.tvCarrotVersion.text = "Ver: ${state.carrot2}"
            binding.tvCarrotIp.text = "IP: ${state.ip}"
            
            // Connection
            if (state.ip != "-" && state.ip.isNotEmpty()) {
                binding.vConnectionDot.setBackgroundResource(R.drawable.shape_circle_green)
                binding.tvConnectionStatus.text = "OP 연결됨"
                binding.tvConnectionStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            } else {
                binding.vConnectionDot.setBackgroundResource(R.drawable.shape_circle_gray)
                binding.tvConnectionStatus.text = "OP 연결 대기"
                binding.tvConnectionStatus.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
            }
            
            // Active
            if (state.active) {
                binding.tvActiveStatus.text = "ON"
                binding.tvActiveStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                binding.ivOpIcon?.setColorFilter(android.graphics.Color.parseColor("#4CAF50"))
            } else {
                binding.tvActiveStatus.text = "OFF"
                binding.tvActiveStatus.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
                binding.ivOpIcon?.setColorFilter(android.graphics.Color.parseColor("#AAAAAA"))
            }

            // Traffic
            when (state.trafficState) {
                1 -> binding.vTrafficLight.setBackgroundResource(R.drawable.shape_circle_red)
                2 -> binding.vTrafficLight.setBackgroundResource(R.drawable.shape_circle_green)
                else -> binding.vTrafficLight.setBackgroundResource(R.drawable.shape_circle_gray)
            }

            // xState
            val (xText, xColor) = when (state.xState) {
                0 -> "LEAD" to "#2196F3"
                1 -> "CRUISE" to "#4CAF50"
                2 -> "E2E CRZ" to "#00BCD4"
                3 -> "E2E STP" to "#F44336"
                4 -> "PREPARE" to "#FFC107"
                5 -> "STOPPED" to "#B71C1C"
                else -> "-" to "#AAAAAA"
            }
            binding.tvXStateBadge.text = xText
            binding.tvXStateBadge.setTextColor(android.graphics.Color.parseColor(xColor))
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
            }, 1000)

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
                            binding.llRoadSpeedLimit?.visibility = android.view.View.VISIBLE
                            binding.tvRoadSpeedLimit?.text = realRoadLimit.toString()
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
                        binding.ivGpsIcon?.setColorFilter(android.graphics.Color.YELLOW)
                        binding.tvGpsStatus.text = "탐색 중"
                        binding.tvGpsStatus.setTextColor(android.graphics.Color.YELLOW)
                    }
                    override fun onStopped() {
                        binding.ivGpsIcon?.setColorFilter(android.graphics.Color.RED)
                        binding.tvGpsStatus.text = "끊김 (NO)"
                        binding.tvGpsStatus.setTextColor(android.graphics.Color.RED)
                    }
                    override fun onFirstFix(ttffMillis: Int) {
                        binding.ivGpsIcon?.setColorFilter(android.graphics.Color.GREEN)
                        binding.tvGpsStatus.text = "수신 양호"
                        binding.tvGpsStatus.setTextColor(android.graphics.Color.GREEN)
                    }
                    override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                        var usedInFix = 0
                        for (i in 0 until status.satelliteCount) {
                            if (status.usedInFix(i)) usedInFix++
                        }
                        if (usedInFix >= 4) {
                            binding.ivGpsIcon?.setColorFilter(android.graphics.Color.GREEN)
                            binding.tvGpsStatus.text = "GOOD (위성 $usedInFix)"
                            binding.tvGpsStatus.setTextColor(android.graphics.Color.GREEN)
                        } else {
                            binding.ivGpsIcon?.setColorFilter(android.graphics.Color.RED)
                            binding.tvGpsStatus.text = "BAD (위성 $usedInFix)"
                            binding.tvGpsStatus.setTextColor(android.graphics.Color.RED)
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
                    binding.tvOffsetTitle?.text = if (isBoosting) "추가가속중" else "구간단속"
                    if (isBoosting) {
                        binding.ivSpeedometerIcon?.setColorFilter(android.graphics.Color.parseColor("#FFEB3B"))
                    } else {
                        binding.ivSpeedometerIcon?.setColorFilter(android.graphics.Color.parseColor("#FFFFFF"))
                    }
                    
                    if (isBlockSection && blockDist > 0) {
                        binding.llBlockInfo?.visibility = android.view.View.VISIBLE
                        binding.tvBlockAvgSpeed?.text = "평균: ${blockAvgSpeed}km/h"
                        binding.tvBlockDistTime?.text = String.format("남은: %s (%d:%02d)", formatDistance(blockDist), blockTime / 60, blockTime % 60)
                    } else {
                        binding.llBlockInfo?.visibility = android.view.View.GONE
                    }
                    
                    binding.llSdiEvent.visibility = android.view.View.VISIBLE
                    if (sdiType > 0 || (sdiSpeedLimit > 0 && sdiDist > 0)) {
                        binding.ivCameraIcon?.setColorFilter(android.graphics.Color.parseColor("#F44336"))
                        binding.tvEventSpeedLimit.text = if (sdiSpeedLimit > 0) sdiSpeedLimit.toString() else "-"
                        binding.tvEventDist.text = formatDistance(sdiDist)
                        
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
                        binding.tvEventType.text = typeName
                    } else {
                        binding.ivCameraIcon?.setColorFilter(android.graphics.Color.parseColor("#AAAAAA"))
                        binding.tvEventSpeedLimit.text = "-"
                        binding.tvEventDist.text = "0m"
                        binding.tvEventType.text = "이벤트 없음"
                    }
                }
            } else {
                runOnUiThread {
                    binding.tvOffsetTitle?.text = "구간단속"
                    binding.llBlockInfo?.visibility = android.view.View.GONE
                    binding.llSdiEvent.visibility = android.view.View.VISIBLE
                    binding.tvEventSpeedLimit.text = "-"
                    binding.tvEventDist.text = "0m"
                    binding.tvEventType.text = "이벤트 없음"
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

    private var resizingView: View? = null
    private var resizeInitialRawX = 0f
    private var resizeInitialRawY = 0f
    private var resizeInitialScale = 1f
    private var resizeCornerSignX = 1f
    private var resizeCornerSignY = 1f

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!isEditMode) return super.dispatchTouchEvent(ev)

        val touchX = ev.rawX
        val touchY = ev.rawY

        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                val views = listOf(binding.llSpeedGroup, binding.llStatusGroup, binding.llOffset)
                for (v in views) {
                    if (v.visibility != View.VISIBLE) continue

                    val loc = IntArray(2)
                    v.getLocationOnScreen(loc)
                    val vX = loc[0].toFloat()
                    val vY = loc[1].toFloat()
                    val scaledW = v.width * v.scaleX
                    val scaledH = v.height * v.scaleY

                    val left = vX + (v.width - scaledW) / 2f
                    val top = vY + (v.height - scaledH) / 2f
                    val right = left + scaledW
                    val bottom = top + scaledH

                    val margin = 80f // 80 screen pixels physical touch margin

                    val isTopLeft = touchX in (left - margin)..(left + margin) && touchY in (top - margin)..(top + margin)
                    val isTopRight = touchX in (right - margin)..(right + margin) && touchY in (top - margin)..(top + margin)
                    val isBottomLeft = touchX in (left - margin)..(left + margin) && touchY in (bottom - margin)..(bottom + margin)
                    val isBottomRight = touchX in (right - margin)..(right + margin) && touchY in (bottom - margin)..(bottom + margin)

                    if (isTopLeft || isTopRight || isBottomLeft || isBottomRight) {
                        resizingView = v
                        resizeInitialScale = v.scaleX
                        resizeInitialRawX = touchX
                        resizeInitialRawY = touchY
                        resizeCornerSignX = if (isTopLeft || isBottomLeft) -1f else 1f
                        resizeCornerSignY = if (isTopLeft || isTopRight) -1f else 1f
                        return true // Intercept touch and start resizing
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val v = resizingView
                if (v != null) {
                    val deltaX = touchX - resizeInitialRawX
                    val deltaY = touchY - resizeInitialRawY
                    
                    val diagX = resizeCornerSignX * (v.width / 2f)
                    val diagY = resizeCornerSignY * (v.height / 2f)
                    val diagLen = Math.hypot(diagX.toDouble(), diagY.toDouble()).toFloat()
                    
                    if (diagLen > 0) {
                        val unitX = diagX / diagLen
                        val unitY = diagY / diagLen
                        val projectedDelta = deltaX * unitX + deltaY * unitY
                        
                        var newScale = resizeInitialScale + (projectedDelta / diagLen)
                        newScale = Math.max(0.5f, Math.min(newScale, 2.5f))
                        
                        v.scaleX = newScale
                        v.scaleY = newScale
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val v = resizingView
                if (v != null) {
                    val sharedPref = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
                    val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    val suffix = if (isLandscape) "land" else "port"
                    val keyPrefix = when (v) {
                        binding.llSpeedGroup -> "llSpeedGroup"
                        binding.llStatusGroup -> "llStatusGroup"
                        binding.llOffset -> "llOffset"
                        else -> ""
                    }
                    if (keyPrefix.isNotEmpty()) {
                        sharedPref.edit().putFloat("${keyPrefix}_scale_${suffix}", v.scaleX).apply()
                    }
                    resizingView = null
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun makeDraggable(view: View, keyPrefix: String, isLandscape: Boolean, otherViews: List<View> = emptyList()) {
        var dX = 0f
        var dY = 0f

        view.setOnTouchListener { v, event ->
            if (!isEditMode) return@setOnTouchListener false
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val rawX = event.rawX + dX
                    val rawY = event.rawY + dY
                    val (clampedX, clampedY) = clampAndPreventOverlap(v, rawX, rawY, otherViews)
                    
                    v.animate()
                        .x(clampedX)
                        .y(clampedY)
                        .setDuration(0)
                        .start()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val sharedPref = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
                    val suffix = if (isLandscape) "land" else "port"
                    sharedPref.edit()
                        .putFloat("${keyPrefix}_x_${suffix}", v.x)
                        .putFloat("${keyPrefix}_y_${suffix}", v.y)
                        .apply()
                    true
                }
                else -> false
            }
        }
    }

    private fun restorePosition(view: View, keyPrefix: String, isLandscape: Boolean, otherViews: List<View> = emptyList()) {
        val sharedPref = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
        val suffix = if (isLandscape) "land" else "port"
        val x = sharedPref.getFloat("${keyPrefix}_x_${suffix}", -1f)
        val y = sharedPref.getFloat("${keyPrefix}_y_${suffix}", -1f)
        val scale = sharedPref.getFloat("${keyPrefix}_scale_${suffix}", 1f)
        
        view.scaleX = scale
        view.scaleY = scale

        if (x != -1f && y != -1f) {
            val (clampedX, clampedY) = clampAndPreventOverlap(view, x, y, otherViews)
            view.x = clampedX
            view.y = clampedY
        }
    }

    private fun updateEditModeForegrounds() {
        if (isEditMode) {
            binding.llSpeedGroup.foreground = HatchedDrawable(binding.llSpeedGroup)
            binding.llStatusGroup.foreground = HatchedDrawable(binding.llStatusGroup)
            binding.llOffset.foreground = HatchedDrawable(binding.llOffset)
        } else {
            binding.llSpeedGroup.foreground = null
            binding.llStatusGroup.foreground = null
            binding.llOffset.foreground = null
        }
    }
}

private class HatchedDrawable(private val targetView: View, baseColor: Int = android.graphics.Color.parseColor("#33FFC107")) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#80FFC107") // Semi-transparent yellow stripes
        style = android.graphics.Paint.Style.STROKE
    }
    private val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = baseColor
        style = android.graphics.Paint.Style.FILL
    }
    private val cornerPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#FF5722") // Deep orange color for corners
        style = android.graphics.Paint.Style.STROKE
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val bounds = bounds
        val width = bounds.width()
        val height = bounds.height()

        // Get view scales to keep physical rendering size constant
        val scaleX = Math.max(0.1f, targetView.scaleX)
        val scaleY = Math.max(0.1f, targetView.scaleY)

        // Draw semi-transparent background
        canvas.drawRect(bounds, bgPaint)

        // Draw diagonal stripes (lines at 45 degrees)
        paint.strokeWidth = 6f / scaleX
        val step = 30f / scaleX // Distance between stripes
        var x = -height.toFloat()
        while (x < width) {
            canvas.drawLine(x, 0f, (x + height), height.toFloat(), paint)
            x += step
        }

        // Draw corner brackets (always around 80 screen pixels size)
        cornerPaint.strokeWidth = 20f / scaleX
        val cornerSizeX = 80f / scaleX
        val cornerSizeY = 80f / scaleY
        
        // Top-left
        canvas.drawLine(0f, 0f, cornerSizeX, 0f, cornerPaint)
        canvas.drawLine(0f, 0f, 0f, cornerSizeY, cornerPaint)
        
        // Top-right
        canvas.drawLine(width - cornerSizeX, 0f, width.toFloat(), 0f, cornerPaint)
        canvas.drawLine(width.toFloat(), 0f, width.toFloat(), cornerSizeY, cornerPaint)
        
        // Bottom-left
        canvas.drawLine(0f, height.toFloat(), cornerSizeX, height.toFloat(), cornerPaint)
        canvas.drawLine(0f, height - cornerSizeY, 0f, height.toFloat(), cornerPaint)
        
        // Bottom-right
        canvas.drawLine(width - cornerSizeX, height.toFloat(), width.toFloat(), height.toFloat(), cornerPaint)
        canvas.drawLine(width.toFloat(), height - cornerSizeY, width.toFloat(), height.toFloat(), cornerPaint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        bgPaint.alpha = alpha
        cornerPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
        bgPaint.colorFilter = colorFilter
        cornerPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int {
        return android.graphics.PixelFormat.TRANSLUCENT
    }
}

