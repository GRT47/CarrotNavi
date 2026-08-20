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
    private var isTmapInitialized = false
    private var tmapInitRetryCount = 0
    
    private val mediaProgressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val mediaProgressRunnable = object : Runnable {
        override fun run() {
            if (MediaNotificationListenerService.isPlaying) {
                val elapsed = android.os.SystemClock.elapsedRealtime() - MediaNotificationListenerService.lastUpdateTime
                val currentPos = MediaNotificationListenerService.position + elapsed
                
                val sbMediaProgress = binding.root.findViewById<android.widget.SeekBar>(R.id.sbMediaProgress)
                val tvCurrentTime = binding.root.findViewById<android.widget.TextView>(R.id.tvCurrentTime)
                
                sbMediaProgress?.max = MediaNotificationListenerService.duration.toInt()
                sbMediaProgress?.progress = currentPos.toInt()
                
                val currentSecs = currentPos / 1000
                tvCurrentTime?.text = String.format("%d:%02d", currentSecs / 60, currentSecs % 60)
                
                mediaProgressHandler.postDelayed(this, 1000)
            }
        }
    }

    private val mediaUpdateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.carrotnavi.ACTION_MEDIA_UPDATE") {
                updateMediaUIFromService()
            }
        }
    }

    private fun updateMediaUIFromService() {
        if (!::binding.isInitialized) return
        
        val title = MediaNotificationListenerService.currentTitle
        val artist = MediaNotificationListenerService.currentArtist
        val isPlaying = MediaNotificationListenerService.isPlaying
        val duration = MediaNotificationListenerService.duration
        
        val ivAlbumArt = binding.root.findViewById<android.widget.ImageView>(R.id.ivAlbumArt)
        val ivAlbumArtThumbnail = binding.root.findViewById<android.widget.ImageView>(R.id.ivAlbumArtThumbnail)
        val tvMediaTitle = binding.root.findViewById<android.widget.TextView>(R.id.tvMediaTitle)
        val tvMediaArtist = binding.root.findViewById<android.widget.TextView>(R.id.tvMediaArtist)
        val btnPlayPause = binding.root.findViewById<android.widget.ImageButton>(R.id.btnPlayPause)
        val tvDuration = binding.root.findViewById<android.widget.TextView>(R.id.tvDuration)
        
        val fakeEqView = binding.root.findViewById<com.example.carrotnavi.FakeEqView>(R.id.fakeEqView)
        
        tvMediaTitle?.text = title
        tvMediaArtist?.text = artist
        ivAlbumArt?.setImageBitmap(MediaNotificationListenerService.currentAlbumArt)
        ivAlbumArtThumbnail?.setImageBitmap(MediaNotificationListenerService.currentAlbumArt)
        
        val sharedPref = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
        val showAlbumWithEq = sharedPref.getBoolean("SHOW_ALBUM_ART_WITH_EQ", false)
        val bgStyle = sharedPref.getString("MEDIA_BG_STYLE", "album")
        if (bgStyle == "eq" || bgStyle == "eq_bar" || bgStyle == "eq_wave" || bgStyle == "eq_circle") {
            ivAlbumArt?.visibility = if (showAlbumWithEq) android.view.View.VISIBLE else android.view.View.GONE
            fakeEqView?.visibility = android.view.View.VISIBLE
            
            val styleInt = when (bgStyle) {
                "eq_wave" -> com.example.carrotnavi.FakeEqView.STYLE_WAVE
                "eq_circle" -> com.example.carrotnavi.FakeEqView.STYLE_CIRCLE
                else -> com.example.carrotnavi.FakeEqView.STYLE_BAR
            }
            fakeEqView?.setEqStyle(styleInt)
            fakeEqView?.setPlaying(isPlaying)
        } else {
            ivAlbumArt?.visibility = android.view.View.VISIBLE
            fakeEqView?.visibility = android.view.View.GONE
            fakeEqView?.setPlaying(false)
        }
        
        btnPlayPause?.setImageResource(if (isPlaying) R.drawable.ic_round_pause_24 else R.drawable.ic_round_play_arrow_24)
        
        val durSecs = duration / 1000
        tvDuration?.text = String.format("%d:%02d", durSecs / 60, durSecs % 60)
        
        mediaProgressHandler.removeCallbacks(mediaProgressRunnable)
        if (isPlaying) {
            mediaProgressHandler.post(mediaProgressRunnable)
        }
    }

    private var pendingSafeDriveRestart = false

    private val cancelRouteReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.carrotnavi.ACTION_CANCEL_ROUTE") {
                Log.d("MapActivity", "Cancel Route received, stopping navigation")
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    navigationFragment?.startSafeDrive()
                } else {
                    pendingSafeDriveRestart = true
                }
            }
        }
    }

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
            "MEDIA_SPLIT_RATIO", "MEDIA_SPLIT_RATIO_F" -> {
                runOnUiThread {
                    updateMediaLayout(resources.configuration.orientation)
                }
            }
            "MEDIA_BG_STYLE", "SHOW_ALBUM_ART_WITH_EQ" -> {
                runOnUiThread {
                    updateMediaUIFromService()
                }
            }
            "VOICE_VOLUME" -> {
                val voiceRatio = sharedPreferences.getFloat("VOICE_VOLUME", 1.0f)
                var maxVol = 10
                try {
                    for (m in TmapUISDK::class.java.methods) {
                        if (m.name == "getMaxVolume") {
                            maxVol = if (m.parameterTypes.isEmpty()) m.invoke(null) as Int else m.invoke(null, this@MapActivity) as Int
                            break
                        }
                    }
                } catch (e: Exception) {}
                TmapUISDK.setVolume(this@MapActivity, (maxVol * voiceRatio).toInt())
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::binding.isInitialized) {
            updateMediaLayout(newConfig.orientation)
        }
        if (!isResumedState) {
            needFragmentRecreate = true
            Log.d("MapActivity", "Orientation changed in background, will recreate fragment view on resume")
        }
    }

    private fun updateMediaLayout(orientation: Int) {
        val sharedPref = getSharedPreferences("CarrotNaviPrefs", android.content.Context.MODE_PRIVATE)
        val ratio = if (sharedPref.contains("MEDIA_SPLIT_RATIO_F")) {
            sharedPref.getFloat("MEDIA_SPLIT_RATIO_F", 3.5f)
        } else {
            val oldRatio = sharedPref.getInt("MEDIA_SPLIT_RATIO", 4).toFloat()
            if (oldRatio >= 5f) 5f else oldRatio
        }
        
        val mainContainer = binding.root.findViewById<android.widget.LinearLayout>(R.id.llSplitContainer)
        val tmapLayout = binding.root.findViewById<android.widget.FrameLayout>(R.id.mapOverlayContainer)
        val mediaContainer = binding.root.findViewById<android.widget.FrameLayout>(R.id.flMediaContainer)
        
        if (mainContainer != null && tmapLayout != null && mediaContainer != null) {
            mainContainer.weightSum = 5f
            
            val mediaWeight = 5f - ratio
            if (mediaWeight <= 0f) {
                mediaContainer.visibility = android.view.View.GONE
            } else {
                mediaContainer.visibility = android.view.View.VISIBLE
            }

            if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                mainContainer.orientation = android.widget.LinearLayout.HORIZONTAL
                val tmapParams = tmapLayout.layoutParams as android.widget.LinearLayout.LayoutParams
                tmapParams.width = 0
                tmapParams.height = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                tmapParams.weight = ratio
                tmapLayout.layoutParams = tmapParams
                
                val mediaParams = mediaContainer.layoutParams as android.widget.LinearLayout.LayoutParams
                mediaParams.width = 0
                mediaParams.height = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                mediaParams.weight = mediaWeight
                mediaContainer.layoutParams = mediaParams
                
                binding.root.findViewById<android.view.View>(R.id.clMediaControls)?.visibility = android.view.View.VISIBLE
            } else {
                mainContainer.orientation = android.widget.LinearLayout.VERTICAL
                val tmapParams = tmapLayout.layoutParams as android.widget.LinearLayout.LayoutParams
                tmapParams.width = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                tmapParams.height = 0
                tmapParams.weight = ratio
                tmapLayout.layoutParams = tmapParams
                
                val mediaParams = mediaContainer.layoutParams as android.widget.LinearLayout.LayoutParams
                mediaParams.width = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                mediaParams.height = 0
                mediaParams.weight = mediaWeight
                mediaContainer.layoutParams = mediaParams
                
                val clMediaControls = binding.root.findViewById<android.view.View>(R.id.clMediaControls)
                if (ratio >= 4.0f) {
                    clMediaControls?.visibility = android.view.View.GONE
                } else {
                    clMediaControls?.visibility = android.view.View.VISIBLE
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        getSharedPreferences("CarrotNaviPrefs", android.content.Context.MODE_PRIVATE).edit().putBoolean("IS_DEBUG_MODE", false).apply()
        super.onCreate(savedInstanceState)
        
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Do nothing
            }
        })

        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        checkNotificationPermissionAndPrompt()
        
        val sharedPref = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
        sharedPref.registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        // 현재 앱이 Tmap 모드임을 명시적으로 설정하여 JSON 로그 송신 오류 수정
        sharedPref.edit().putString("ACTIVE_NAVI", "tmap").apply()
        VoiceDuckingManager.init(this)
        startUdpSenderService()

        hudBinding = com.example.carrotnavi.databinding.LayoutHudOverlaysBinding.bind(binding.root)
        hudOverlayManager = HudOverlayManager(this, hudBinding, this)
        hudOverlayManager.binding.btnSearchAddress.setOnClickListener { showSearchDialog() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val appKey = sharedPref.getString("APP_KEY", "") ?: ""

        // Force Tmap SDK to run in background
        getSharedPreferences("user.settings.info", Context.MODE_PRIVATE).edit().putBoolean("set_suspend_in_background", false).apply()
        
        if (appKey.isEmpty()) {
            Toast.makeText(this, "App Key가 설정되지 않았습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val intentFilter = android.content.IntentFilter("com.example.carrotnavi.ACTION_MEDIA_UPDATE")
        val cancelRouteFilter = android.content.IntentFilter("com.example.carrotnavi.ACTION_CANCEL_ROUTE")
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaUpdateReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(cancelRouteReceiver, cancelRouteFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mediaUpdateReceiver, intentFilter)
            registerReceiver(cancelRouteReceiver, cancelRouteFilter)
        }
        
        updateMediaLayout(resources.configuration.orientation)
        
        // request current media info manually
        val ivAlbumArt = binding.root.findViewById<android.widget.ImageView>(R.id.ivAlbumArt)
        val ivAlbumArtThumbnail = binding.root.findViewById<android.widget.ImageView>(R.id.ivAlbumArtThumbnail)
        val tvMediaTitle = binding.root.findViewById<android.widget.TextView>(R.id.tvMediaTitle)
        val tvMediaArtist = binding.root.findViewById<android.widget.TextView>(R.id.tvMediaArtist)
        val btnPrev = binding.root.findViewById<android.widget.ImageButton>(R.id.btnPrev)
        val btnPlayPause = binding.root.findViewById<android.widget.ImageButton>(R.id.btnPlayPause)
        val btnNext = binding.root.findViewById<android.widget.ImageButton>(R.id.btnNext)
        val sbMediaProgress = binding.root.findViewById<android.widget.SeekBar>(R.id.sbMediaProgress)
        
        ivAlbumArt?.setImageBitmap(MediaNotificationListenerService.currentAlbumArt)
        ivAlbumArtThumbnail?.setImageBitmap(MediaNotificationListenerService.currentAlbumArt)
        tvMediaTitle?.text = MediaNotificationListenerService.currentTitle
        tvMediaArtist?.text = MediaNotificationListenerService.currentArtist

        fun sendMediaCommand(cmd: String) {
            val intent = Intent(MediaNotificationListenerService.ACTION_MEDIA_CONTROL).apply {
                setPackage(packageName)
                putExtra("command", cmd)
            }
            sendBroadcast(intent)
        }
        
        btnPrev?.setOnClickListener { sendMediaCommand("prev") }
        btnPlayPause?.setOnClickListener { 
            sendMediaCommand(if (MediaNotificationListenerService.isPlaying) "pause" else "play") 
        }
        btnNext?.setOnClickListener { sendMediaCommand("next") }
        
        sbMediaProgress?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                seekBar?.let {
                    val intent = Intent(MediaNotificationListenerService.ACTION_MEDIA_CONTROL).apply {
                        setPackage(packageName)
                        putExtra("command", "seek")
                        putExtra("seekPos", it.progress.toLong())
                    }
                    sendBroadcast(intent)
                }
            }
        })

        // 강제로 서비스 리바인딩 시도 (앱 업데이트 후 서비스 끊김 방지)
        try {
            val component = android.content.ComponentName(this, MediaNotificationListenerService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                android.service.notification.NotificationListenerService.requestRebind(component)
            }
            // 확실한 리바인딩을 위한 컴포넌트 토글 트릭
            val pm = packageManager
            pm.setComponentEnabledSetting(component, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(component, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP)
        } catch (e: Exception) {
            Log.e("MapActivity", "Failed to rebind media service: ${e.message}")
        }

        initTmapSdk(appKey)

    }

    
    private fun initTmapSdk(appKey: String) {
        if (isTmapInitialized) return

        if (!NetworkUtil.isNetworkAvailable(this)) {
            runOnUiThread {
                Toast.makeText(this@MapActivity, "네트워크 연결 대기 중...", Toast.LENGTH_SHORT).show()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    initTmapSdk(appKey)
                }, 3000)
            }
            return
        }

        // TmapUISDK 초기화
        initialize(this, "", appKey, "", "", object : TmapUISDK.InitializeListener {
            override fun onSuccess() {
                isTmapInitialized = true
                tmapInitRetryCount = 0
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
                    tmapInitRetryCount++
                    Toast.makeText(this@MapActivity, "네트워크 불안정으로 지도 초기화 재시도 중... ($tmapInitRetryCount)", Toast.LENGTH_LONG).show()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        initTmapSdk(appKey)
                    }, 3000)
                }
            }

            override fun savedRouteInfoExists(dest: String?) {
                // 경로 안내 중이 아니므로 무시
            }
        })
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (::hudOverlayManager.isInitialized && hudOverlayManager.shouldBlockTouch(ev)) {
            return true
        }
        return super.dispatchTouchEvent(ev)
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
                
                // 안전운행 모드가 완전히 시작된 후 카카오내비 인텐트를 처리
                val destPlaceName = intent.getStringExtra("dest_place_name")
                if (!destPlaceName.isNullOrEmpty()) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        val naviIntent = Intent(this@MapActivity, KakaoMapActivity::class.java).apply {
                            putExtras(intent)
                        }
                        startActivity(naviIntent)
                        
                        intent.removeExtra("dest_place_name")
                        intent.removeExtra("dest_lat")
                        intent.removeExtra("dest_lng")
                    }, 500)
                }
            }, 1000)

            frag.setNavigationScreenStateListener(object : com.tmapmobility.tmap.tmapsdk.ui.data.NavigationScreenStateListener {
                override fun onChanged(state: com.tmapmobility.tmap.tmapsdk.ui.data.NavigationScreenState) {
                    val stateName = state.javaClass.simpleName
                    Log.d("MapActivity", "NavigationScreenState changed: $stateName")
                    if (stateName.contains("DefaultScreen")) {
                        // TMap Safe Driving has ended (probably user clicked X).
                        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                if (!isFinishing) {
                                    frag.startSafeDrive()
                                }
                            }, 500)
                        } else {
                            pendingSafeDriveRestart = true
                        }
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
            frag.nightModeLiveData.observe(this@MapActivity, Observer { isNight ->
                isNight?.let {
                    Log.d("CarrotNavi", "TMap Night Mode changed: $it")
                    SdiDataRepository.isNightMode.postValue(it)
                }
            })
            TmapUISDK.observableEDCData.observe(this@MapActivity, Observer { data ->
                data?.let {
                    Log.e("SdiDebug", "observableEDCData class: ${it.javaClass.name}")
                    Log.e("SdiDebug", "observableEDCData: $it")
                    
                    val sp = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
                    val voiceRatio = sp.getFloat("VOICE_VOLUME", 1.0f)
                    var maxVol = 10
                    try {
                        for (m in TmapUISDK::class.java.methods) {
                            if (m.name == "getMaxVolume") {
                                maxVol = if (m.parameterTypes.isEmpty()) m.invoke(null) as Int else m.invoke(null, this@MapActivity) as Int
                                break
                            }
                        }
                    } catch (e: Exception) {}
                    TmapUISDK.setVolume(this@MapActivity, (maxVol * voiceRatio).toInt())
                    
                    // 도로 기본 제한속도 추출 및 UI 업데이트
                    var realRoadLimit = getRoadLimitSpeedFromEngine()
                    if (realRoadLimit <= 0 && data is android.os.Bundle) {
                        val limitObj = data.get("limitSpeed")
                        val currentLimitSpeed = when (limitObj) {
                            is Int -> limitObj
                            is Double -> limitObj.toInt()
                            is String -> limitObj.toIntOrNull() ?: 0
                            else -> 0
                        }
                        realRoadLimit = currentLimitSpeed
                    }
                    runOnUiThread {
                        if (realRoadLimit >= 30 && hudOverlayManager.isOverlayVisible) {
                            hudBinding.llRoadSpeedLimit?.visibility = android.view.View.VISIBLE
                            hudBinding.tvRoadSpeedLimit?.text = realRoadLimit.toString()
                        } else {
                            hudBinding.llRoadSpeedLimit?.visibility = android.view.View.GONE
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
    private var isResumedState = false
    private var needFragmentRecreate = false

    override fun onPause() {
        super.onPause()
        isResumedState = false
    }

    override fun onResume() {
        super.onResume()
        isResumedState = true
        
        if (needFragmentRecreate || pendingSafeDriveRestart) {
            needFragmentRecreate = false
            navigationFragment?.let {
                supportFragmentManager.beginTransaction()
                    .detach(it)
                    .attach(it)
                    .commitAllowingStateLoss()
                Log.d("MapActivity", "Recreated navigationFragment view due to background orientation change or route cancel")
            }
        }
        updateMediaUIFromService()
        val intent = android.content.Intent(MediaNotificationListenerService.ACTION_MEDIA_CONTROL).apply {
            setPackage(packageName)
            putExtra("command", "refresh")
        }
        sendBroadcast(intent)
        
        if (pendingSafeDriveRestart) {
            pendingSafeDriveRestart = false
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!isFinishing) {
                    try {
                        navigationFragment?.startSafeDrive()
                        Log.d("MapActivity", "startSafeDrive() called from onResume after route cancel")
                    } catch(e: Exception) {
                        e.printStackTrace()
                    }
                }
            }, 1000)
        }
    }

    override fun onNewIntent(newIntent: android.content.Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        
        val destPlaceName = newIntent.getStringExtra("dest_place_name")
        if (!destPlaceName.isNullOrEmpty()) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val naviIntent = android.content.Intent(this@MapActivity, KakaoMapActivity::class.java).apply {
                    putExtras(newIntent)
                }
                startActivity(naviIntent)
                
                newIntent.removeExtra("dest_place_name")
                newIntent.removeExtra("dest_lat")
                newIntent.removeExtra("dest_lng")
            }, 1000)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(mediaUpdateReceiver)
        } catch (e: Exception) {}
        try {
            unregisterReceiver(cancelRouteReceiver)
        } catch (e: Exception) {}
        
        if (::hudOverlayManager.isInitialized) {
            hudOverlayManager.onDestroy()
        }
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
                    return 0
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
        val intent = Intent(this, SearchActivity::class.java)
        startActivity(intent)
    }

    private fun isNotificationPermissionGranted(): Boolean {
        val enabledListeners = android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabledListeners != null && enabledListeners.contains(packageName)
    }

    private fun checkNotificationPermissionAndPrompt() {
        if (!isNotificationPermissionGranted()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("알림 접근 권한 필요")
                .setMessage("미디어 재생 정보(현재 재생 중인 음악 등)를 내비게이션 화면에 표시하려면 '알림 접근 허용'이 필요합니다.\n\n설정 화면으로 이동하시겠습니까?")
                .setPositiveButton("이동") { _, _ ->
                    val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    startActivity(intent)
                }
                .setNegativeButton("다음에", null)
                .show()
        }
    }
}
