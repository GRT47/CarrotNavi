package com.example.carrotnavi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.content.res.Configuration
import android.view.MotionEvent
import android.view.View
import android.os.Handler
import android.os.Looper

import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import com.kakaomobility.knsdk.common.objects.KNPOI

import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.example.carrotnavi.databinding.ActivityKakaoMapBinding
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Observer

import com.example.carrotnavi.OpenpilotStateRepository
import com.kakaomobility.knsdk.KNRouteAvoidOption
import com.kakaomobility.knsdk.KNRoutePriority
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.guidance.knguidance.*
import com.kakaomobility.knsdk.ui.view.KNNaviView_StateDelegate
import com.kakaomobility.knsdk.ui.view.KNNaviViewState
import com.kakaomobility.knsdk.ui.component.MapViewCameraMode
import com.kakaomobility.knsdk.guidance.knguidance.locationguide.KNGuide_Location
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.KNGuide_Route
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.KNGuide_Safety
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety
import com.kakaomobility.knsdk.ui.view.KNNaviView
import com.kakaomobility.knsdk.trip.kntrip.knroute.KNRoute
import com.kakaomobility.knsdk.guidance.knguidance.common.KNLocation
import com.kakaomobility.knsdk.trip.kntrip.KNTrip
import com.kakaomobility.knsdk.common.objects.KNError

class KakaoMapActivity : AppCompatActivity(), 
    KNGuidance_GuideStateDelegate,
    KNGuidance_LocationGuideDelegate,
    KNGuidance_RouteGuideDelegate,
    KNGuidance_SafetyGuideDelegate,
    KNGuidance_VoiceGuideDelegate,
    KNGuidance_CitsGuideDelegate,
    LocationListener,
    KNNaviView_StateDelegate {

    private lateinit var binding: ActivityKakaoMapBinding
    private lateinit var naviView: KNNaviView
    private var isGuidanceActive = false
    private var isMuted = false
    private var pendingDestination: KakaoDocument? = null
    private var isShowingPreview = false
    private var previewTimer: android.os.CountDownTimer? = null
    private lateinit var sharedPref: SharedPreferences
    private lateinit var locationManager: LocationManager
    private lateinit var hudOverlayManager: HudOverlayManager
    
    
    private var lastCameraSpeedLimit = 0
    private var hasStartedRouteGuidance = false
    private var lastRoadType: com.kakaomobility.knsdk.KNRoadType? = null
    private var currentSafetyGuide: com.kakaomobility.knsdk.guidance.knguidance.safetyguide.KNGuide_Safety? = null

    private val cancelRouteReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.carrotnavi.ACTION_CANCEL_ROUTE") {
                Log.d("KakaoMapActivity", "Cancel Route received, stopping guidance")
                KNSDK.sharedGuidance()?.stop()
                if (!isFinishing) finish()
            }
        }
    }

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
                val title = intent.getStringExtra("title") ?: "재생중인 곡 없음"
                val artist = intent.getStringExtra("artist") ?: "아티스트 없음"
                val isPlaying = intent.getBooleanExtra("isPlaying", false)
                val duration = intent.getLongExtra("duration", 0L)
                
                val ivAlbumArt = binding.root.findViewById<android.widget.ImageView>(R.id.ivAlbumArt)
                val ivAlbumArtThumbnail = binding.root.findViewById<android.widget.ImageView>(R.id.ivAlbumArtThumbnail)
                val tvMediaTitle = binding.root.findViewById<android.widget.TextView>(R.id.tvMediaTitle)
                val tvMediaArtist = binding.root.findViewById<android.widget.TextView>(R.id.tvMediaArtist)
                val btnPlayPause = binding.root.findViewById<android.widget.ImageButton>(R.id.btnPlayPause)
                val tvDuration = binding.root.findViewById<android.widget.TextView>(R.id.tvDuration)
                
                tvMediaTitle?.text = title
                tvMediaArtist?.text = artist
                ivAlbumArt?.setImageBitmap(MediaNotificationListenerService.currentAlbumArt)
                ivAlbumArtThumbnail?.setImageBitmap(MediaNotificationListenerService.currentAlbumArt)
                
                btnPlayPause?.setImageResource(if (isPlaying) R.drawable.ic_round_pause_24 else R.drawable.ic_round_play_arrow_24)
                
                val durSecs = duration / 1000
                tvDuration?.text = String.format("%d:%02d", durSecs / 60, durSecs % 60)
                
                mediaProgressHandler.removeCallbacks(mediaProgressRunnable)
                if (isPlaying) {
                    mediaProgressHandler.post(mediaProgressRunnable)
                }
            }
        }
    }

    private val preferenceChangeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == "MEDIA_SPLIT_RATIO" || key == "MEDIA_SPLIT_RATIO_F") {
            runOnUiThread {
                updateMediaLayout(resources.configuration.orientation)
            }
        }
    }
    companion object {
        private var knsdkInitialized = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        getSharedPreferences("CarrotNaviPrefs", android.content.Context.MODE_PRIVATE).edit().putBoolean("IS_DEBUG_MODE", false).apply()
        super.onCreate(savedInstanceState)
        
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Do nothing
            }
        })

        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            100
        )

        val filter = android.content.IntentFilter("com.example.carrotnavi.ACTION_CANCEL_ROUTE")
        val mediaFilter = android.content.IntentFilter("com.example.carrotnavi.ACTION_MEDIA_UPDATE")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cancelRouteReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(mediaUpdateReceiver, mediaFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(cancelRouteReceiver, filter)
            registerReceiver(mediaUpdateReceiver, mediaFilter)
        }

        sharedPref = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
        sharedPref.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        sharedPref.edit().putString("ACTIVE_NAVI", "kakao").apply()

        val dbPath = filesDir.absolutePath + "/knsdk"
        val nativeAppKey = sharedPref.getString("KAKAO_NATIVE_APP_KEY", "") ?: ""
        if (nativeAppKey.isEmpty()) {
            Toast.makeText(this, "Kakao Native App Key가 설정되지 않았습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        try {
            if (knsdkInitialized) {
                setupContentAndStart()
            } else {
                Log.d("CarrotNavi", "Installing KNSDK to: $dbPath")
                KNSDK.install(application, dbPath)
                KNSDK.initializeWithAppKey(
                    nativeAppKey,
                    "1.0",
                    "user_001",
                    "ko",
                    com.kakaomobility.knsdk.KNLanguageType.KNLanguageType_KOREAN
                ) { error ->
                    runOnUiThread {
                        if (error == null) {
                            Log.d("CarrotNavi", "KNSDK Init Success")
                            knsdkInitialized = true
                            setupContentAndStart()
                        } else {
                            Log.e("CarrotNavi", "KNSDK Init Failed: ${error.code} / ${error.msg}")
                            Toast.makeText(this@KakaoMapActivity, "지도 초기화 실패: ${error.msg}", Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupContentAndStart() {
        binding = ActivityKakaoMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        checkNotificationPermissionAndPrompt()
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(binding.root)
        naviView = binding.naviView
        naviView.stateDelegate = this@KakaoMapActivity
        
        SdiDataRepository.isNightMode.observe(this, androidx.lifecycle.Observer { isNight ->
            if (::naviView.isInitialized) {
                naviView.useDarkMode = isNight ?: false
            }
        })
        
        val hudBinding = com.example.carrotnavi.databinding.LayoutHudOverlaysBinding.bind(binding.root)
        hudOverlayManager = HudOverlayManager(this@KakaoMapActivity, hudBinding, this@KakaoMapActivity)
        
        hudOverlayManager.binding.btnSearchAddress.setOnClickListener {
            val searchIntent = Intent(this@KakaoMapActivity, SearchActivity::class.java)
            startActivity(searchIntent)
        }
        
        setupUI()
        setupObservers()
        
        startSafeDrivingMode()
        
        val destPlaceName = intent.getStringExtra("dest_place_name")
        if (destPlaceName != null) {
            binding.llLoadingOverlay.visibility = android.view.View.VISIBLE
            val destRoadAddressName = intent.getStringExtra("dest_road_address_name") ?: ""
            val destAddressName = intent.getStringExtra("dest_address_name") ?: ""
            val destX = intent.getStringExtra("dest_x") ?: ""
            val destY = intent.getStringExtra("dest_y") ?: ""
            
            val doc = KakaoDocument(destPlaceName, destRoadAddressName, destAddressName, destX, destY)
            
            if (intent.getBooleanExtra("auto_start_navi", false)) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    binding.llLoadingOverlay.visibility = android.view.View.GONE
                    startRouteGuidance(doc)
                }, 1000)
            } else {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    binding.llLoadingOverlay.visibility = android.view.View.GONE
                    val destName = destPlaceName.ifEmpty { destRoadAddressName.ifEmpty { destAddressName } }
                    showPreviewOverlay(doc, destName)
                }, 1000)
            }
        }

        startUdpSenderService()

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, this)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                locationManager.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 0L, 0f, this)
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun setupUI() {
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
            pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        } catch (e: Exception) {
            Log.e("KakaoMapActivity", "Failed to rebind media service: ${e.message}")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::binding.isInitialized) {
            updateMediaLayout(newConfig.orientation)
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
            }
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (::hudOverlayManager.isInitialized && hudOverlayManager.shouldBlockTouch(ev)) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun setupObservers() {
        OpenpilotStateRepository.state.observe(this, Observer { state ->
            
            if (state.ip != "-" && state.ip.isNotEmpty()) {
            } else {
            }
        })

        // 백그라운드 서비스(UdpSenderService)에서 갱신하는 최신 티맵 도로 제한속도 옵저빙
        SdiDataRepository.observableRoadLimitSpeed.observe(this, Observer { limitSpeed ->
            runOnUiThread {
                if (limitSpeed >= 30) {
                    hudOverlayManager.binding.llRoadSpeedLimit?.visibility = android.view.View.VISIBLE
                    hudOverlayManager.binding.tvRoadSpeedLimit?.text = limitSpeed.toString()
                } else {
                    hudOverlayManager.binding.llRoadSpeedLimit?.visibility = android.view.View.GONE
                }
            }
        })
    }

    private fun startUdpSenderService() {
        val intent = Intent(this, UdpSenderService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun startSafeDrivingMode() {
        KNSDK.sharedGuidance()?.apply {
            guideStateDelegate     = this@KakaoMapActivity
            locationGuideDelegate  = this@KakaoMapActivity
            routeGuideDelegate     = this@KakaoMapActivity
            safetyGuideDelegate    = this@KakaoMapActivity
            voiceGuideDelegate     = this@KakaoMapActivity
            citsGuideDelegate      = this@KakaoMapActivity
            naviView.stateDelegate = this@KakaoMapActivity

            naviView.mapComponent?.mapView?.isVisibleTraffic = true

            Log.d("CarrotNavi", "Calling initWithGuidance (trip=null)")
            naviView.initWithGuidance(
                this,
                null,
                KNRoutePriority.KNRoutePriority_Recommand,
                KNRouteAvoidOption.KNRouteAvoidOption_None.value
            )
            
            // 안내음성 기본 볼륨을 0으로 설정
            naviView.sndVolume = 0.0f
        }
    }

    override fun onLocationChanged(location: Location) {
        try {
            val cleanLocation = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = location.latitude
                longitude = location.longitude
                altitude = location.altitude
                accuracy = location.accuracy
                time = location.time
                speed = location.speed
                bearing = location.bearing
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    verticalAccuracyMeters = location.verticalAccuracyMeters
                    speedAccuracyMetersPerSecond = location.speedAccuracyMetersPerSecond
                    bearingAccuracyDegrees = location.bearingAccuracyDegrees
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    elapsedRealtimeNanos = location.elapsedRealtimeNanos
                }
            }

            val gpsManager = KNSDK.sharedGpsManager()
            if (gpsManager != null) {
                try {
                    val m = gpsManager.javaClass.getMethod("onLocationChanged", Location::class.java)
                    m.invoke(gpsManager, cleanLocation)
                } catch (e: Exception) {
                    Log.e("CarrotNavi", "Failed to invoke onLocationChanged: ${e.message}")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun guidanceDidUpdateSafetyGuide(guidance: KNGuidance, safetyGuide: KNGuide_Safety?) {
        if(::naviView.isInitialized) naviView.guidanceDidUpdateSafetyGuide(guidance, safetyGuide)
        currentSafetyGuide = safetyGuide
        processSafeties(guidance.locationGuide)
    }

    private fun processSafeties(locationGuide: com.kakaomobility.knsdk.guidance.knguidance.locationguide.KNGuide_Location?) {
        val guide = currentSafetyGuide
        guide?.let { g ->
            val safeties = g.safetiesOnGuide
            if (safeties != null && safeties.isNotEmpty()) {
                val s1 = safeties[0]
                val s1Type = s1.safetyType().value
                val s1Limit = (s1 as? com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety_Camera)?.speedLimit ?: 0
                val curLoc = locationGuide?.location
                val s1Dist = curLoc?.distToLocation(s1.location) ?: s1.location.distFromS
                
                if (s1Limit > 0) {
                    lastCameraSpeedLimit = s1Limit
                } else {
                    lastCameraSpeedLimit = 0
                }
                
                var s2Type = 0
                var s2Limit = 0
                var s2Dist = 0
                
                if (safeties.size > 1) {
                    val s2 = safeties[1]
                    s2Type = s2.safetyType().value
                    s2Limit = (s2 as? com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety_Camera)?.speedLimit ?: 0
                    s2Dist = curLoc?.distToLocation(s2.location) ?: s2.location.distFromS
                }
                
                KakaoSdiRepository.updateSafeties(
                    roadLimitSpeed = 0, // Ignored, handled by updateLocation
                    sdiType1 = s1Type,
                    speedLimit1 = s1Limit,
                    dist1 = s1Dist,
                    isBlock1 = false,
                    blockAvgSpeed = 0,
                    sdiType2 = s2Type,
                    speedLimit2 = s2Limit,
                    dist2 = s2Dist
                )
                
                runOnUiThread {
                                    }
            } else {
                lastCameraSpeedLimit = 0
                KakaoSdiRepository.updateSafeties(0, 0, 0, 0, false, 0, 0, 0, 0)
                runOnUiThread {
                                    }
            }
        }
    }

    override fun guidanceDidUpdateAroundSafeties(guidance: KNGuidance, safeties: List<KNSafety>?) {
        if(::naviView.isInitialized) naviView.guidanceDidUpdateAroundSafeties(guidance, safeties)
    }

    override fun guidanceGuideStarted(guidance: KNGuidance) {
        if(::naviView.isInitialized) naviView.guidanceGuideStarted(guidance)
        isGuidanceActive = true
    }

    override fun guidanceGuideEnded(guidance: KNGuidance) {
        if(::naviView.isInitialized) naviView.guidanceGuideEnded(guidance)
        if (hasStartedRouteGuidance && !isFinishing) finish()
    }

    override fun guidanceDidUpdateLocation(guidance: KNGuidance, locationGuide: KNGuide_Location) {
        processSafeties(locationGuide)
        if(::naviView.isInitialized && !isShowingPreview) naviView.guidanceDidUpdateLocation(guidance, locationGuide)
        
        if (isGuidanceActive && guidance.routeGuide == null && hasStartedRouteGuidance) {
            if (!isFinishing) finish()
            return
        }

        val speed = locationGuide.gpsMatched?.speed ?: 0
        val roadName = locationGuide.location?.roadName ?: ""
        
        val roadType = locationGuide.location?.roadType
        if (roadType != lastRoadType) {
            lastCameraSpeedLimit = 0 
            lastRoadType = roadType
        }

        val roadLimitSpeed = if (lastCameraSpeedLimit > 0) {
            lastCameraSpeedLimit
        } else {
            when (roadType) {
                com.kakaomobility.knsdk.KNRoadType.KNRoadType_Highway -> 100
                com.kakaomobility.knsdk.KNRoadType.KNRoadType_GeneralRoad -> 50
                else -> 0
            }
        }
        
        var tbtDist = -1
        var tbtTurnType = -1
        var tbtText = ""
        
        try {
            val routeGuide = guidance.routeGuide
            if (routeGuide == null) {
                tbtText = "routeGuide Null"
                tbtDist = 0
            } else if (locationGuide.location == null) {
                tbtText = "loc Null"
                tbtDist = 0
            } else {
                val curDir = routeGuide.curDirection
                if (curDir == null) {
                    tbtText = "curDir Null"
                    tbtDist = 0
                } else {
                    val targetLoc = curDir.location
                    if (targetLoc != null) {
                        tbtDist = locationGuide.location!!.distToLocation(targetLoc)
                    } else {
                        tbtText = "targetLoc Null"
                        tbtDist = 0
                    }
                    
                    val turnCode = curDir.rgCode
                    tbtTurnType = when (turnCode.name) {
                          
                          "KNRGCode_Straight", "KNRGCode_LeftStraight", "KNRGCode_RightStraight", "KNRGCode_JoinAfterBranch" -> 0
                          
                          "KNRGCode_LeftTurn", "KNRGCode_UnprotectedLeftTurn" -> 12 
                          "KNRGCode_RightTurn" -> 13 
                          "KNRGCode_UTurn" -> 14 
                          
                          "KNRGCode_LeftDirection" -> 7 
                          "KNRGCode_RightDirection" -> 6 
                          
                          "KNRGCode_OutHighway", "KNRGCode_OutCityway" -> 101 
                          "KNRGCode_LeftOutHighway", "KNRGCode_LeftOutCityway" -> 102 
                          "KNRGCode_RightOutHighway", "KNRGCode_RightOutCityway" -> 101 
                          "KNRGCode_InHighway", "KNRGCode_InCityway", "KNRGCode_RightInHighway", "KNRGCode_RightInCityway" -> 6 
                          "KNRGCode_LeftInHighway", "KNRGCode_LeftInCityway" -> 7 
                          
                          "KNRGCode_OverPath", "KNRGCode_LeftOverPath" -> 18 
                          "KNRGCode_OverPathSide", "KNRGCode_LeftOverPathSide", "KNRGCode_RightOverPathSide" -> 6 
                          "KNRGCode_UnderPath", "KNRGCode_LeftUnderPath", "KNRGCode_RightUnderPath" -> 0 
                          "KNRGCode_UnderPathSide", "KNRGCode_LeftUnderPathSide", "KNRGCode_RightUnderPathSide" -> 6 
                          
                          "KNRGCode_RotaryDirection_1", "KNRGCode_RotaryDirection_2", "KNRGCode_RoundaboutDirection_1", "KNRGCode_RoundaboutDirection_2" -> 131 
                          "KNRGCode_RotaryDirection_3", "KNRGCode_RoundaboutDirection_3" -> 133 
                          "KNRGCode_RotaryDirection_4", "KNRGCode_RotaryDirection_5", "KNRGCode_RoundaboutDirection_4", "KNRGCode_RoundaboutDirection_5" -> 134 
                          "KNRGCode_RotaryDirection_6", "KNRGCode_RoundaboutDirection_6" -> 14 
                          "KNRGCode_RotaryDirection_7", "KNRGCode_RotaryDirection_8", "KNRGCode_RoundaboutDirection_7", "KNRGCode_RoundaboutDirection_8" -> 136 
                          "KNRGCode_RotaryDirection_9", "KNRGCode_RoundaboutDirection_9" -> 139 
                          "KNRGCode_RotaryDirection_10", "KNRGCode_RotaryDirection_11", "KNRGCode_RoundaboutDirection_10", "KNRGCode_RoundaboutDirection_11" -> 140 
                          "KNRGCode_RotaryDirection_12", "KNRGCode_RoundaboutDirection_12" -> 142 
                          
                          "KNRGCode_Direction_1", "KNRGCode_Direction_2" -> 6
                          "KNRGCode_Direction_3" -> 13
                          "KNRGCode_Direction_4", "KNRGCode_Direction_5" -> 19
                          "KNRGCode_Direction_6" -> 14
                          "KNRGCode_Direction_7", "KNRGCode_Direction_8" -> 16
                          "KNRGCode_Direction_9" -> 12
                          "KNRGCode_Direction_10", "KNRGCode_Direction_11" -> 7
                          "KNRGCode_Direction_12" -> 0
                          
                          "KNRGCode_Tunnel", "KNRGCode_LeftTunnel", "KNRGCode_RightTunnel" -> 20 
                          "KNRGCode_Tollgate", "KNRGCode_NonstopTollgate" -> 153 
                          "KNRGCode_Start" -> 200
                          "KNRGCode_Goal" -> 201
                          else -> 1 
                    }
                    
                    if (tbtText.isEmpty()) {
                        tbtText = "${curDir.nodeName ?: ""} ${turnCode.name}"
                    }
                }
            }
            
            val currentRoute = guidance.routesOnGuide?.firstOrNull()
            if (currentRoute != null && locationGuide.location != null) {
                val remainDist = currentRoute.remainDistFromLocation(locationGuide.location!!)
                val remainTime = currentRoute.remainTimeFromLocation(locationGuide.location!!)
                
                var goalPosX = 0.0
                var goalPosY = 0.0
                val goal = guidance.trip?.goal
                val goalName = goal?.name ?: ""
                
                try {
                    val goalPos = goal?.pos
                    if (goalPos != null) {
                        val wgs84 = com.kakaomobility.knsdk.common.gps.KATECToWGS84(goalPos.x.toDouble(), goalPos.y.toDouble())
                        goalPosX = wgs84.x
                        goalPosY = wgs84.y
                    }
                } catch(e: Exception) {}

                KakaoSdiRepository.updateRouteInfo(remainDist, remainTime, goalName, goalPosX, goalPosY)
            }
            
        } catch(e: Exception) {
            android.util.Log.e("CarrotNavi", "TBT Ext Error: \${e.message}")
        }
        
        var lat = 0.0
        var lon = 0.0
        try {
            val katecPos = locationGuide.gpsMatched?.pos ?: locationGuide.location?.pos
            if (katecPos != null) {
                val wgs84 = com.kakaomobility.knsdk.common.gps.KATECToWGS84(katecPos.x, katecPos.y)
                lon = wgs84.x
                lat = wgs84.y
            }
        } catch(e: Exception) {}
        
        KakaoSdiRepository.updateLocation(speed, roadName, roadLimitSpeed, tbtDist = tbtDist, tbtTurnType = tbtTurnType, tbtText = tbtText, lat = lat, lon = lon)
    }

    override fun guidanceDidUpdateRouteGuide(guidance: KNGuidance, routeGuide: KNGuide_Route) {
        if(::naviView.isInitialized) naviView.guidanceDidUpdateRouteGuide(guidance, routeGuide)
    }

    override fun guidanceCheckingRouteChange(guidance: KNGuidance) {
        if(::naviView.isInitialized) naviView.guidanceCheckingRouteChange(guidance)
    }
    override fun guidanceRouteUnchanged(guidance: KNGuidance) {
        if(::naviView.isInitialized) naviView.guidanceRouteUnchanged(guidance)
    }
    override fun guidanceRouteUnchangedWithError(guidance: KNGuidance, error: KNError) {
        if(::naviView.isInitialized) naviView.guidanceRouteUnchangedWithError(guidance, error)
    }
    override fun guidanceOutOfRoute(guidance: KNGuidance) {
        if(::naviView.isInitialized) naviView.guidanceOutOfRoute(guidance)
    }
    override fun guidanceRouteChanged(guidance: KNGuidance, fromRoute: KNRoute, fromLocation: KNLocation, toRoute: KNRoute, toLocation: KNLocation, changeReason: KNGuideRouteChangeReason) {
        if(::naviView.isInitialized) naviView.guidanceRouteChanged(guidance)
    }
    override fun guidanceDidUpdateRoutes(guidance: KNGuidance, routes: List<KNRoute>, multiRouteInfo: com.kakaomobility.knsdk.guidance.knguidance.routeguide.objects.KNMultiRouteInfo?) {
        if(::naviView.isInitialized) naviView.guidanceDidUpdateRoutes(guidance, routes, multiRouteInfo)
    }
    override fun guidanceDidUpdateIndoorRoute(guidance: KNGuidance, route: KNRoute?) {
        
    }
    override fun shouldPlayVoiceGuide(guidance: KNGuidance, voiceGuide: com.kakaomobility.knsdk.guidance.knguidance.voiceguide.KNGuide_Voice, data: MutableList<ByteArray>): Boolean = true
    override fun willPlayVoiceGuide(guidance: KNGuidance, voiceGuide: com.kakaomobility.knsdk.guidance.knguidance.voiceguide.KNGuide_Voice) {
        if(::naviView.isInitialized) naviView.willPlayVoiceGuide(guidance, voiceGuide)
    }
    override fun didFinishPlayVoiceGuide(guidance: KNGuidance, voiceGuide: com.kakaomobility.knsdk.guidance.knguidance.voiceguide.KNGuide_Voice) {
        if(::naviView.isInitialized) naviView.didFinishPlayVoiceGuide(guidance, voiceGuide)
    }
    override fun didUpdateCitsGuide(guidance: KNGuidance, citsGuide: com.kakaomobility.knsdk.guidance.knguidance.citsguide.KNGuide_Cits) {
        if(::naviView.isInitialized) naviView.didUpdateCitsGuide(guidance, citsGuide)
    }

    
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        
        val destPlaceName = intent.getStringExtra("dest_place_name")
        if (destPlaceName != null) {
            val destRoadAddressName = intent.getStringExtra("dest_road_address_name") ?: ""
            val destAddressName = intent.getStringExtra("dest_address_name") ?: ""
            val destX = intent.getStringExtra("dest_x") ?: ""
            val destY = intent.getStringExtra("dest_y") ?: ""
            
            val doc = KakaoDocument(destPlaceName, destRoadAddressName, destAddressName, destX, destY)
            val destName = destPlaceName.ifEmpty { destRoadAddressName.ifEmpty { destAddressName } }
            
            showPreviewOverlay(doc, destName)
        }
    }

    private fun startRouteGuidance(doc: KakaoDocument) {
        var startPoi: KNPOI? = null
        val gpsManager = com.kakaomobility.knsdk.KNSDK.sharedGpsManager()
        val currentGps = gpsManager?.recentGpsData

        if (currentGps != null && currentGps.pos.x > 0 && currentGps.pos.y > 0) {
            startPoi = KNPOI("현 위치", currentGps.pos.x.toInt(), currentGps.pos.y.toInt(), "")
        } else {
            try {
                val loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) 
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (loc != null) {
                    val katec = com.kakaomobility.knsdk.KNSDK.convertWGS84ToKATEC(loc.longitude, loc.latitude)
                    startPoi = KNPOI("현 위치", katec.x.toInt(), katec.y.toInt(), "")
                }
            } catch (e: SecurityException) { }
        }

        if (startPoi == null) {
            Toast.makeText(this@KakaoMapActivity, "GPS 확인 중입니다...", Toast.LENGTH_SHORT).show()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                startRouteGuidance(doc)
            }, 1000)
            return
        }
        
        val offset = sharedPref.getInt("BLOCK_SPEED_OFFSET", 0)
        val mode = sharedPref.getInt("BLOCK_SPEED_BOOST_MODE", 0)
        val fakeDrop = sharedPref.getInt("BLOCK_SPEED_FAKE_DROP", 10)
        val goalX = doc.x.toDoubleOrNull() ?: 0.0
        val goalY = doc.y.toDoubleOrNull() ?: 0.0
        val katec = com.kakaomobility.knsdk.KNSDK.convertWGS84ToKATEC(goalX, goalY)
        val goalPoi = KNPOI(doc.place_name.ifEmpty { doc.road_address_name }, katec.x.toInt(), katec.y.toInt(), doc.address_name)

        Toast.makeText(this@KakaoMapActivity, "寃쎈줈 ?먯깋 以?..", Toast.LENGTH_SHORT).show()

        com.kakaomobility.knsdk.KNSDK.makeTripWithStart(startPoi, goalPoi, null) { error, trip ->
            runOnUiThread {
            if (error != null || trip == null) {
                Toast.makeText(this@KakaoMapActivity, "경로 탐색 실패: ${error?.msg ?: "알 수 없는 오류"}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@KakaoMapActivity, "경로 안내를 시작합니다.", Toast.LENGTH_SHORT).show()
                val guidance = com.kakaomobility.knsdk.KNSDK.sharedGuidance()!!
                
                binding.naviView.guideNewDestinations(
                    trip,
                    com.kakaomobility.knsdk.KNRoutePriority.KNRoutePriority_Recommand,
                    com.kakaomobility.knsdk.KNRouteAvoidOption.KNRouteAvoidOption_None.value
                )

                guidance.guideStateDelegate = this@KakaoMapActivity
                guidance.routeGuideDelegate = this@KakaoMapActivity
                guidance.safetyGuideDelegate = this@KakaoMapActivity
                guidance.voiceGuideDelegate = this@KakaoMapActivity
                guidance.citsGuideDelegate = this@KakaoMapActivity
                guidance.locationGuideDelegate = this@KakaoMapActivity
                
                hasStartedRouteGuidance = true
                intent.removeExtra("dest_place_name")
            }
        }
    }
}

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(cancelRouteReceiver)
            unregisterReceiver(mediaUpdateReceiver)
        } catch (e: Exception) {}
        sharedPref.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        sharedPref.edit().putString("ACTIVE_NAVI", "tmap").apply()
        if (::hudOverlayManager.isInitialized) hudOverlayManager.onDestroy()
        locationManager.removeUpdates(this)
        
        // 인스턴스 재생성 시 이전 인스턴스의 onDestroy가 새 인스턴스의 초기화를 방해하지 않도록 조건 추가
        if (isFinishing || hasStartedRouteGuidance) {
            KNSDK.sharedGuidance()?.stop()
        }
    }

    private fun showPreviewOverlay(doc: KakaoDocument, destName: String) {
        isShowingPreview = true
        
        binding.tvPreviewDestName.text = destName
        binding.tvPreviewAddress.text = doc.address_name
        binding.llPreviewOverlay.visibility = android.view.View.VISIBLE
        
        try {
            val goalX = doc.x.toDoubleOrNull() ?: 0.0
            val goalY = doc.y.toDoubleOrNull() ?: 0.0
            val katec = com.kakaomobility.knsdk.KNSDK.convertWGS84ToKATEC(goalX, goalY)
            val floatPoint = com.kakaomobility.knsdk.common.util.FloatPoint(katec.x.toFloat(), katec.y.toFloat())
            
            binding.naviView.mapComponent?.mapView?.removeMarkersAll()
            val marker = com.kakaomobility.knsdk.map.uicustomsupport.renewal.KNMapMarker(floatPoint)
            marker.icon = createMarkerBitmap()
            binding.naviView.mapComponent?.mapView?.addMarker(marker)

            val cameraUpdate = com.kakaomobility.knsdk.map.knmaprenderer.objects.KNMapCameraUpdate.Creator.targetTo(floatPoint).anchorTo(com.kakaomobility.knsdk.common.util.FloatPoint(0.5f, 0.4f))
            binding.naviView.mapComponent?.mapView?.moveCamera(cameraUpdate, false, false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        hudOverlayManager.binding.llSpeedGroup.visibility = android.view.View.GONE
        hudOverlayManager.binding.llStatusGroup.visibility = android.view.View.GONE
        hudOverlayManager.binding.btnToggleVisibility.visibility = android.view.View.GONE
        hudOverlayManager.binding.btnSearchAddress.visibility = android.view.View.GONE
        hudOverlayManager.binding.btnEditMode.visibility = android.view.View.GONE
        hudOverlayManager.binding.btnRestoreDefaults.visibility = android.view.View.GONE

        binding.btnPreviewStart.setOnClickListener {
            hidePreviewOverlay()
            startRouteGuidance(doc)
        }
        
        binding.btnPreviewCancel.setOnClickListener {
            hidePreviewOverlay()
            finish()
        }

        // Start 5 second countdown timer
        previewTimer?.cancel()
        previewTimer = object : android.os.CountDownTimer(5000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = (millisUntilFinished / 1000).toInt() + 1
                binding.btnPreviewStart.text = "안내시작(${secondsLeft})"
            }

            override fun onFinish() {
                binding.btnPreviewStart.text = "안내시작(0)"
                if (isShowingPreview) {
                    binding.btnPreviewStart.performClick()
                }
            }
        }.start()
    }

    private fun hidePreviewOverlay() {
        isShowingPreview = false
        
        previewTimer?.cancel()
        previewTimer = null
        binding.btnPreviewStart.text = "안내시작"
        binding.llPreviewOverlay.visibility = android.view.View.GONE
        binding.naviView.mapComponent?.mapView?.removeMarkersAll()
        
        
        hudOverlayManager.binding.btnToggleVisibility.visibility = android.view.View.VISIBLE
        hudOverlayManager.updateOverlayVisibility()
    }

    private fun createMarkerBitmap(): android.graphics.Bitmap {
        val size = 120
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = android.graphics.Color.parseColor("#E91E63") 
        
        
        val cx = size / 2f
        val cy = size / 2f
        
        canvas.drawCircle(cx, cy, 30f, paint)
        
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(cx, cy, 12f, paint)
        
        
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = android.graphics.Color.parseColor("#FFFFFF")
        canvas.drawCircle(cx, cy, 30f, paint)
        
        return bitmap
    }
    // KNNaviView_StateDelegate
    override fun naviViewDidUpdateStatusBarColor(aColor: Int) {}
    override fun naviViewDidUpdateUseDarkMode(aMode: Boolean) {}
    override fun naviViewDidUpdateMapCameraMode(aCameraMode: MapViewCameraMode) {}
    override fun naviViewDidUpdateSndVolume(aVolume: Float) {}
    override fun naviViewDidUpdateCustomButton(id: Int, toggleOn: Boolean?) {}
    override fun naviViewPopupOpenCheck(aOpen: Boolean) {}
    override fun naviViewIsArrival(aIsArrival: Boolean) {}

    override fun naviViewScreenState(viewState: KNNaviViewState) {
        android.util.Log.d("CarrotNavi", "naviViewScreenState: $viewState")
        if (viewState == KNNaviViewState.NONE) {
            // 카카오내비가 안전운행 모드(NONE)로 진입하면 즉시 액티비티를 종료하여
            // 기존에 떠있는 T맵 안전운행 모드로 돌아갑니다.
            // 단, 아직 경로안내를 시작하지 않은 미리보기 상태에서는 종료하지 않도록 방어합니다.
            if (hasStartedRouteGuidance && !isFinishing) {
                finish()
            }
        }
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
                    val intent = android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    startActivity(intent)
                }
                .setNegativeButton("다음에", null)
                .show()
        }
    }
}
