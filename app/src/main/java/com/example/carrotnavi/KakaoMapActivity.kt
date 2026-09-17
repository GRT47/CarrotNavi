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

    private var currentRoadLimitSpeed = 0
    private var isCameraEventActive = false
    private var lastCameraSignX: Float = -1f
    private var lastCameraSignY: Float = -1f
    private var splitHandleManager: SplitHandleManager? = null

    private val bottomBarId by lazy {
        val id = resources.getIdentifier("component_bottom", "id", packageName)
        if (id != 0) id else resources.getIdentifier("bottom_drive_constraint_layout", "id", packageName)
    }
    private val goalTextId by lazy {
        resources.getIdentifier("bottom_drive_goal_text", "id", packageName)
    }
    private var isGoalTextWatcherAttached = false
    private var lastKnownAddress: String = ""
    private var lastKnownRoadName: String = ""
    private var lastAddressFetchTime: Long = 0L
    private var isShowingRemainingTime = false
    private var currentRemainDist: Long = 0L
    private var currentRemainTime: Long = 0L

    private var v2Client: com.example.carrotnavi.v2.OpenpilotV2Client? = null
    private var streamingManager: com.example.carrotnavi.v2.VideoStreamingManager? = null
    private var presentation: com.example.carrotnavi.v2.MapPresentation? = null

    private fun startV2Stream() {
        val ip = sharedPref.getString("TARGET_UDP_IP", "192.168.1.33") ?: "192.168.1.33"
        v2Client = com.example.carrotnavi.v2.OpenpilotV2Client(ip) { sessionId, streamHandle, manifestRev ->
            runOnUiThread {
                streamingManager = com.example.carrotnavi.v2.VideoStreamingManager(this, v2Client!!.renderClient!!, sessionId, streamHandle, manifestRev)
                streamingManager?.start()
                
                val display = streamingManager?.display
                if (display != null) {
                    presentation = com.example.carrotnavi.v2.MapPresentation(this, display)
                    presentation?.show()
                }
            }
        }
        v2Client?.start()
    }

    private val cancelRouteReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.carrotnavi.ACTION_CANCEL_ROUTE") {
                Log.d("KakaoMapActivity", "Cancel Route received, stopping guidance")
                sharedPref.edit().putString("ACTIVE_NAVI", "tmap").apply()
                // 사용자가 명시적으로 취소했으므로 최근 목적지 복구 정보 삭제
                sharedPref.edit().apply {
                    remove("RECENT_DEST_NAME")
                    remove("RECENT_DEST_ROAD_ADDRESS")
                    remove("RECENT_DEST_ADDRESS")
                    remove("RECENT_DEST_X")
                    remove("RECENT_DEST_Y")
                    remove("RECENT_DEST_TIMESTAMP")
                    apply()
                }
                RouteInfoRepository.updateRouteInfo("", 0, 0, "tmap")
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
        
        val currentTitle = tvMediaTitle?.text?.toString() ?: ""
        val infoSection = binding.root.findViewById<android.view.View>(R.id.llMediaInfoSection)
        val cvAlbum = binding.root.findViewById<android.view.View>(R.id.cvAlbumArtContainer)

        if (currentTitle != title && currentTitle.isNotEmpty() && currentTitle != "음악을 재생해 주세요" && title.isNotEmpty()) {
            infoSection?.animate()?.alpha(0f)?.translationX(-50f)?.setDuration(150)?.withEndAction {
                tvMediaTitle?.text = title
                tvMediaArtist?.text = artist
                ivAlbumArt?.setImageBitmap(MediaNotificationListenerService.currentAlbumArt)
                ivAlbumArtThumbnail?.setImageBitmap(MediaNotificationListenerService.currentAlbumArt)
                
                infoSection.translationX = 50f
                infoSection.animate().alpha(1f).translationX(0f).setDuration(150).start()
            }?.start()
            
            cvAlbum?.animate()?.alpha(0f)?.scaleX(0.8f)?.scaleY(0.8f)?.setDuration(150)?.withEndAction {
                cvAlbum.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start()
            }?.start()
        } else {
            tvMediaTitle?.text = title
            tvMediaArtist?.text = artist
            ivAlbumArt?.setImageBitmap(MediaNotificationListenerService.currentAlbumArt)
            ivAlbumArtThumbnail?.setImageBitmap(MediaNotificationListenerService.currentAlbumArt)
        }
        
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

        if (::hudOverlayManager.isInitialized) {
            hudOverlayManager.updateMediaOverlayUi(
                title,
                artist,
                MediaNotificationListenerService.currentAlbumArt ?: MediaNotificationListenerService.fetchedAlbumArt,
                isPlaying
            )
        }
    }

    private val preferenceChangeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == "MEDIA_SPLIT_RATIO" || key == "MEDIA_SPLIT_RATIO_F" || key == "MEDIA_SPLIT_RATIO_PORTRAIT_F" || key == "MEDIA_SPLIT_RATIO_LANDSCAPE_F") {
            runOnUiThread {
                updateMediaLayout(resources.configuration.orientation)
            }
        } else if (key == "MEDIA_BG_STYLE" || key == "SHOW_ALBUM_ART_WITH_EQ") {
            runOnUiThread {
                updateMediaUIFromService()
            }
        } else if (key == "VOICE_VOLUME") {
            if (::naviView.isInitialized) {
                naviView.sndVolume = sharedPreferences.getFloat("VOICE_VOLUME", 1.0f)
            }
        }
    }
    companion object {
        private var knsdkInitialized = false
    }

    private var kakaoInitRetryCount = 0


    override fun onCreate(savedInstanceState: Bundle?) {
        getSharedPreferences("CarrotNaviPrefs", android.content.Context.MODE_PRIVATE).edit().putBoolean("IS_DEBUG_MODE", false).apply()
        super.onCreate(savedInstanceState)
        
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
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
        VoiceDuckingManager.init(this)
        startUdpSenderService()

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
                initKakaoSdk(nativeAppKey)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initKakaoSdk(nativeAppKey: String) {
        if (!NetworkUtil.isNetworkAvailable(this)) {
            runOnUiThread {
                Toast.makeText(this@KakaoMapActivity, "네트워크 연결 대기 중...", Toast.LENGTH_SHORT).show()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    initKakaoSdk(nativeAppKey)
                }, 3000)
            }
            return
        }

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
                    kakaoInitRetryCount = 0
                    setupContentAndStart()
                } else {
                    kakaoInitRetryCount++
                    Log.e("CarrotNavi", "KNSDK Init Failed: ${error.code} / ${error.msg}")
                    Toast.makeText(this@KakaoMapActivity, "네트워크 불안정으로 지도 초기화 재시도 중... ($kakaoInitRetryCount)", Toast.LENGTH_LONG).show()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        initKakaoSdk(nativeAppKey)
                    }, 3000)
                }
            }
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

        val flSplitHandle = binding.root.findViewById<android.widget.FrameLayout>(R.id.flSplitHandle)
        val vSplitHandleIndicator = binding.root.findViewById<android.view.View>(R.id.vSplitHandleIndicator)
        val mainContainer = binding.root.findViewById<android.widget.LinearLayout>(R.id.llSplitContainer)
        val mapContainer = binding.root.findViewById<android.widget.FrameLayout>(R.id.mapOverlayContainer)
        val mediaContainer = binding.root.findViewById<android.widget.FrameLayout>(R.id.flMediaContainer)

        if (flSplitHandle != null && vSplitHandleIndicator != null && mainContainer != null && mapContainer != null && mediaContainer != null) {
            splitHandleManager = SplitHandleManager(
                this, mainContainer, mapContainer, mediaContainer, flSplitHandle, vSplitHandleIndicator
            ) {
                updateMediaLayout(resources.configuration.orientation)
            }
        }

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

        // 앨범아트 클릭 시 미디어 화면 분할 설정 메뉴 표시
        binding.root.findViewById<android.view.View>(R.id.cvAlbumArtContainer)?.setOnClickListener {
            hudOverlayManager.showMediaSettingsDialog()
        }
        binding.root.findViewById<android.view.View>(R.id.ivAlbumArtThumbnail)?.setOnClickListener {
            hudOverlayManager.showMediaSettingsDialog()
        }

        hudOverlayManager.binding.btnGpsCancelRoute.setOnClickListener {
            cancelRouteAndReturnToTmap()
        }

        hudOverlayManager.binding.btnToggleEtaTime.setOnClickListener {
            isShowingRemainingTime = !isShowingRemainingTime
            updateEtaUi()
        }

        hudOverlayManager.onQuickDestinationSelected = { doc ->
            val destName = doc.place_name.ifEmpty { doc.road_address_name.ifEmpty { doc.address_name } }
            showPreviewOverlay(doc, destName)
        }
        hudOverlayManager.onOverlayVisibilityChanged = {
            updateRoadSpeedLimitVisibility()
            alignGpsOverlayWithBottomBar()
        }
        binding.mapOverlayContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            alignSpeedGroupWithCameraSign()
            alignGpsOverlayWithBottomBar()
            alignQuickDestGroupWithTbt()
        }
        binding.root.postDelayed({
            alignGpsOverlayWithBottomBar()
            alignQuickDestGroupWithTbt()
        }, 1000)
        
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
            Log.e("KakaoMapActivity", "GPS Permission Error")
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
        
        startV2Stream()

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
        updateRoadSpeedLimitVisibility()
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
        lastCameraSignX = -1f
        lastCameraSignY = -1f
        if (::binding.isInitialized) {
            updateMediaLayout(newConfig.orientation)
            updateRoadSpeedLimitVisibility()
            binding.root.postDelayed({
                alignSpeedGroupWithCameraSign()
                alignGpsOverlayWithBottomBar()
            }, 300)
            binding.root.postDelayed({
                alignGpsOverlayWithBottomBar()
            }, 800)
        }
    }

    private fun updateMediaLayout(orientation: Int) {
        val sharedPref = getSharedPreferences("CarrotNaviPrefs", android.content.Context.MODE_PRIVATE)
        val ratioKey = if (orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) "MEDIA_SPLIT_RATIO_PORTRAIT_F" else "MEDIA_SPLIT_RATIO_LANDSCAPE_F"
        val ratio = if (sharedPref.contains(ratioKey)) {
            sharedPref.getFloat(ratioKey, 3.5f)
        } else if (sharedPref.contains("MEDIA_SPLIT_RATIO_F")) {
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
            
            splitHandleManager?.updateHandleLayout(orientation)
            
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
                
                // Landscape UI configuration
                binding.root.findViewById<android.widget.LinearLayout>(R.id.llMediaTopSection)?.orientation = android.widget.LinearLayout.VERTICAL
                binding.root.findViewById<androidx.cardview.widget.CardView>(R.id.cvAlbumArtContainer)?.let { cv ->
                    val cvParams = cv.layoutParams as android.widget.LinearLayout.LayoutParams
                    cvParams.width = (120 * resources.displayMetrics.density).toInt()
                    cvParams.height = (120 * resources.displayMetrics.density).toInt()
                    cvParams.marginEnd = 0
                    cv.layoutParams = cvParams
                }
                binding.root.findViewById<android.widget.LinearLayout>(R.id.llMediaInfoSection)?.let { info ->
                    val infoParams = info.layoutParams as android.widget.LinearLayout.LayoutParams
                    infoParams.width = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                    infoParams.weight = 0f
                    info.layoutParams = infoParams
                    info.gravity = android.view.Gravity.CENTER
                }
                binding.root.findViewById<android.widget.TextView>(R.id.tvMediaTitle)?.let { title ->
                    title.gravity = android.view.Gravity.CENTER
                    val titleParams = title.layoutParams as android.widget.LinearLayout.LayoutParams
                    titleParams.topMargin = (24 * resources.displayMetrics.density).toInt()
                    title.layoutParams = titleParams
                }
                binding.root.findViewById<android.widget.TextView>(R.id.tvMediaArtist)?.gravity = android.view.Gravity.CENTER
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
                
                // Portrait UI configuration
                binding.root.findViewById<android.widget.LinearLayout>(R.id.llMediaTopSection)?.orientation = android.widget.LinearLayout.HORIZONTAL
                binding.root.findViewById<androidx.cardview.widget.CardView>(R.id.cvAlbumArtContainer)?.let { cv ->
                    val cvParams = cv.layoutParams as android.widget.LinearLayout.LayoutParams
                    cvParams.width = (80 * resources.displayMetrics.density).toInt()
                    cvParams.height = (80 * resources.displayMetrics.density).toInt()
                    cvParams.marginEnd = (16 * resources.displayMetrics.density).toInt()
                    cv.layoutParams = cvParams
                }
                binding.root.findViewById<android.widget.LinearLayout>(R.id.llMediaInfoSection)?.let { info ->
                    val infoParams = info.layoutParams as android.widget.LinearLayout.LayoutParams
                    infoParams.width = 0
                    infoParams.weight = 1f
                    info.layoutParams = infoParams
                    info.gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                }
                binding.root.findViewById<android.widget.TextView>(R.id.tvMediaTitle)?.let { title ->
                    title.gravity = android.view.Gravity.START
                    val titleParams = title.layoutParams as android.widget.LinearLayout.LayoutParams
                    titleParams.topMargin = 0
                    title.layoutParams = titleParams
                }
                binding.root.findViewById<android.widget.TextView>(R.id.tvMediaArtist)?.gravity = android.view.Gravity.START
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
            currentRoadLimitSpeed = limitSpeed
            updateRoadSpeedLimitVisibility()
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
            
            // 안내음성 볼륨을 SharedPreferences 설정값으로 설정
            naviView.sndVolume = sharedPref.getFloat("VOICE_VOLUME", 1.0f)
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
                
                val isBlock1 = (s1Type == 2)
                val blockAvgSpeed1 = if (isBlock1) s1Limit else 0

                KakaoSdiRepository.updateSafeties(
                    roadLimitSpeed = 0, // Ignored, handled by updateLocation
                    sdiType1 = s1Type,
                    speedLimit1 = s1Limit,
                    dist1 = s1Dist,
                    isBlock1 = isBlock1,
                    blockAvgSpeed = blockAvgSpeed1,
                    sdiType2 = s2Type,
                    speedLimit2 = s2Limit,
                    dist2 = s2Dist
                )
                
                isCameraEventActive = (s1Limit > 0 && s1Dist > 0) || (s1Type > 0 && s1Dist > 0)
                if (isCameraEventActive) {
                    findKakaoViewById("component_sign_first")?.let { sign ->
                        if (sign.visibility == android.view.View.VISIBLE && sign.width > 0) {
                            val signLoc = IntArray(2)
                            val containerLoc = IntArray(2)
                            sign.getLocationOnScreen(signLoc)
                            findViewById<android.view.View>(R.id.mapOverlayContainer)?.getLocationOnScreen(containerLoc)
                            val relX = signLoc[0] - containerLoc[0]
                            val relY = signLoc[1] - containerLoc[1]
                            if (relX >= 0 && relY >= 0) {
                                lastCameraSignX = relX.toFloat()
                                lastCameraSignY = relY.toFloat()
                            }
                        }
                    }
                }
                updateRoadSpeedLimitVisibility()
            } else {
                lastCameraSpeedLimit = 0
                KakaoSdiRepository.updateSafeties(0, 0, 0, 0, false, 0, 0, 0, 0)
                isCameraEventActive = false
                updateRoadSpeedLimitVisibility()
            }
        } ?: run {
            isCameraEventActive = false
            updateRoadSpeedLimitVisibility()
        }
    }

    private fun findKakaoViewById(name: String): android.view.View? {
        val id = resources.getIdentifier(name, "id", packageName)
        return if (id != 0) findViewById(id) else null
    }

    private fun updateRoadSpeedLimitVisibility() {
        if (!::hudOverlayManager.isInitialized) return
        runOnUiThread {
            if (!::hudOverlayManager.isInitialized) return@runOnUiThread
            // 단속 카메라 이벤트가 없을 때만 도로 기본 제한속도(30 이상) 파란색 원을 표시
            // 단속 이벤트 발생 시 파란색 원을 숨겨서 KNSDK 단속 카메라 위젯만 보이도록 함
            val shouldShow = !isCameraEventActive && currentRoadLimitSpeed >= 30 && hudOverlayManager.isOverlayVisible
            if (shouldShow) {
                hudOverlayManager.binding.llSpeedGroup.visibility = View.VISIBLE
                hudOverlayManager.binding.llRoadSpeedLimit.visibility = View.VISIBLE
                hudOverlayManager.binding.tvRoadSpeedLimit.text = currentRoadLimitSpeed.toString()
                alignSpeedGroupWithCameraSign()
            } else {
                hudOverlayManager.binding.llSpeedGroup.visibility = View.GONE
                hudOverlayManager.binding.llRoadSpeedLimit.visibility = View.GONE
            }
        }
    }

    private fun alignSpeedGroupWithCameraSign() {
        if (!::hudOverlayManager.isInitialized || !::binding.isInitialized) return
        val speedGroup = hudOverlayManager.binding.llSpeedGroup ?: return
        val mapContainer = findViewById<android.view.View>(R.id.mapOverlayContainer) ?: return

        // 1. KNSDK 단속 카메라 표지판(component_sign_first)이 표시 중이고 유효한 크기이면 그 위치 우선 사용
        val signFirst = findKakaoViewById("component_sign_first")
        if (signFirst != null && signFirst.visibility == android.view.View.VISIBLE && signFirst.width > 0 && signFirst.height > 0) {
            val signLoc = IntArray(2)
            val containerLoc = IntArray(2)
            signFirst.getLocationOnScreen(signLoc)
            mapContainer.getLocationOnScreen(containerLoc)
            val relX = signLoc[0] - containerLoc[0]
            val relY = signLoc[1] - containerLoc[1]
            if (relX >= 0 && relY >= 0) {
                lastCameraSignX = relX.toFloat()
                lastCameraSignY = relY.toFloat()
                applySpeedGroupMargin(speedGroup, relX, relY)
                return
            }
        }

        // 2. KNSDK 현재속도계(component_speed / speed_meter)가 화면에 유효하게 존재하면 실시간 측정하여 바로 아래에 배치
        // (경로안내 모드 시 좌측 경로 탭의 오른쪽에 위치한 속도계 아래로 실시간 동적 정렬)
        val speedView = findKakaoViewById("component_speed") ?: findKakaoViewById("speed_meter")
        if (speedView != null && speedView.visibility == android.view.View.VISIBLE && speedView.width > 0 && speedView.height > 0) {
            val speedLoc = IntArray(2)
            val containerLoc = IntArray(2)
            speedView.getLocationOnScreen(speedLoc)
            mapContainer.getLocationOnScreen(containerLoc)
            val density = resources.displayMetrics.density
            val groupWidth = if (speedGroup.width > 0) speedGroup.width else (72 * density).toInt()

            val speedCenterX = (speedLoc[0] - containerLoc[0]) + (speedView.width / 2)
            val speedBottomY = (speedLoc[1] - containerLoc[1]) + speedView.height

            val targetX = (speedCenterX - (groupWidth / 2)).coerceAtLeast(0)
            val targetY = (speedBottomY + (8 * density).toInt()).coerceAtLeast(0)

            lastCameraSignX = targetX.toFloat()
            lastCameraSignY = targetY.toFloat()
            applySpeedGroupMargin(speedGroup, targetX, targetY)
            return
        }

        // 3. 이전에 캡처된 단속 카메라 좌표가 유효하면 해당 위치 사용
        if (lastCameraSignX >= 0 && lastCameraSignY >= 0) {
            applySpeedGroupMargin(speedGroup, lastCameraSignX.toInt(), lastCameraSignY.toInt())
            return
        }

        // 4. 아직 뷰 크기가 측정되지 않았으면 post로 재시도
        speedView?.post { alignSpeedGroupWithCameraSign() }
    }

    private fun applySpeedGroupMargin(speedGroup: android.view.View, left: Int, top: Int) {
        speedGroup.translationX = 0f
        speedGroup.translationY = 0f
        val params = speedGroup.layoutParams as? android.widget.FrameLayout.LayoutParams
            ?: android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
        params.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        params.leftMargin = left
        params.topMargin = top
        speedGroup.layoutParams = params
    }

    private fun alignQuickDestGroupWithTbt() {
        if (!::hudOverlayManager.isInitialized || !::binding.isInitialized) return
        val quickDestGroup = hudOverlayManager.binding.llQuickDestGroup ?: return
        val topUiGroup = hudOverlayManager.binding.llTopUiGroup
        val mapContainer = binding.mapOverlayContainer ?: return

        val density = resources.displayMetrics.density
        val defaultLeftMargin = (16 * density).toInt()
        val defaultTopMargin = 0

        val isPortrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

        // 경로안내 중이 아니거나 미리보기 화면이면 기본 위치(최상단 좌측)로 복원
        if (!hasStartedRouteGuidance || !isGuidanceActive || isShowingPreview) {
            val params = quickDestGroup.layoutParams as? android.widget.FrameLayout.LayoutParams
                ?: android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                )
            if (params.leftMargin != defaultLeftMargin || params.topMargin != defaultTopMargin || params.gravity != (android.view.Gravity.TOP or android.view.Gravity.START)) {
                params.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                params.leftMargin = defaultLeftMargin
                params.rightMargin = 0
                params.topMargin = defaultTopMargin
                quickDestGroup.layoutParams = params
            }
            quickDestGroup.translationX = 0f
            quickDestGroup.translationY = 0f

            // OP 연결 상태 뷰 기본 위치 복원
            topUiGroup?.let { topUi ->
                val topParams = topUi.layoutParams as? android.widget.FrameLayout.LayoutParams
                val defaultTop = (4 * density).toInt()
                if (topParams != null && topParams.topMargin != defaultTop) {
                    topParams.topMargin = defaultTop
                    topUi.layoutParams = topParams
                }
            }
            return
        }

        // OP 연결 뷰 기본 위치(최상단 우측) 유지
        topUiGroup?.let { topUi ->
            val topParams = topUi.layoutParams as? android.widget.FrameLayout.LayoutParams
            val defaultTop = (4 * density).toInt()
            if (topParams != null && topParams.topMargin != defaultTop) {
                topParams.topMargin = defaultTop
                topUi.layoutParams = topParams
            }
        }

        // TBT 회전 안내 뷰 탐색 (KNSDK 주행 가이드 뷰)
        val tbtView = findKakaoViewById("component_cur_direction")
            ?: findKakaoViewById("cur_direction_layout")

        if (tbtView != null && (tbtView.width > 0 || tbtView.height > 0 || tbtView.isShown)) {
            val tbtLoc = IntArray(2)
            val containerLoc = IntArray(2)
            tbtView.getLocationOnScreen(tbtLoc)
            mapContainer.getLocationOnScreen(containerLoc)

            val relX = (tbtLoc[0] - containerLoc[0]).coerceAtLeast(0)
            val relY = (tbtLoc[1] - containerLoc[1]).coerceAtLeast(0)
            val tbtWidth = tbtView.width
            val tbtHeight = tbtView.height

            val targetLeft: Int
            val targetTop: Int

            if (isPortrait) {
                // 세로 화면: TBT 패널 밑(아래) 높이이면서 화면 우측(오른쪽)에 배치
                val gap = (8 * density).toInt()
                val targetTop = relY + tbtHeight + gap
                val marginEnd = (8 * density).toInt()

                val params = quickDestGroup.layoutParams as? android.widget.FrameLayout.LayoutParams
                    ?: android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                if (params.gravity != (android.view.Gravity.TOP or android.view.Gravity.END) ||
                    params.rightMargin != marginEnd ||
                    params.topMargin != targetTop ||
                    params.leftMargin != 0
                ) {
                    params.gravity = android.view.Gravity.TOP or android.view.Gravity.END
                    params.leftMargin = 0
                    params.rightMargin = marginEnd
                    params.topMargin = targetTop
                    quickDestGroup.layoutParams = params
                }
                quickDestGroup.translationX = 0f
                quickDestGroup.translationY = 0f
            } else {
                // 가로 화면: TBT 패널 우측 옆으로 배치
                val gap = (12 * density).toInt()
                val targetLeft = relX + tbtWidth + gap
                val targetTop = relY.coerceAtLeast(0)

                val params = quickDestGroup.layoutParams as? android.widget.FrameLayout.LayoutParams
                    ?: android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                if (params.gravity != (android.view.Gravity.TOP or android.view.Gravity.START) ||
                    params.leftMargin != targetLeft ||
                    params.topMargin != targetTop ||
                    params.rightMargin != 0
                ) {
                    params.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    params.leftMargin = targetLeft
                    params.rightMargin = 0
                    params.topMargin = targetTop
                    quickDestGroup.layoutParams = params
                }
                quickDestGroup.translationX = 0f
                quickDestGroup.translationY = 0f
            }
        } else {
            if (isPortrait) {
                // TBT 측정 전 세로 화면 임시 우측 정렬
                val params = quickDestGroup.layoutParams as? android.widget.FrameLayout.LayoutParams
                    ?: android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                val marginEnd = (8 * density).toInt()
                val targetTop = (80 * density).toInt()
                if (params.gravity != (android.view.Gravity.TOP or android.view.Gravity.END) ||
                    params.rightMargin != marginEnd ||
                    params.topMargin != targetTop ||
                    params.leftMargin != 0
                ) {
                    params.gravity = android.view.Gravity.TOP or android.view.Gravity.END
                    params.leftMargin = 0
                    params.rightMargin = marginEnd
                    params.topMargin = targetTop
                    quickDestGroup.layoutParams = params
                }
            }
            tbtView?.post { alignQuickDestGroupWithTbt() }
        }
    }

    private fun alignGpsOverlayWithBottomBar() {
        if (!::hudOverlayManager.isInitialized || !::binding.isInitialized) return
        val statusGroup = hudOverlayManager.binding.llStatusGroup ?: return
        if (isShowingPreview) {
            statusGroup.visibility = android.view.View.GONE
            return
        }
        val mapContainer = binding.mapOverlayContainer ?: return
        val bottomBar = (if (bottomBarId != 0) findViewById<android.view.View?>(bottomBarId) else null)
            ?: findKakaoViewById("component_bottom")
            ?: findKakaoViewById("bottom_drive_constraint_layout")

        if (bottomBar != null && (bottomBar.isShown || bottomBar.visibility == android.view.View.VISIBLE) && bottomBar.width > 0 && bottomBar.height > 0) {
            val barLoc = IntArray(2)
            val containerLoc = IntArray(2)
            bottomBar.getLocationOnScreen(barLoc)
            mapContainer.getLocationOnScreen(containerLoc)

            val relX = (barLoc[0] - containerLoc[0]).coerceAtLeast(0)
            val relY = (barLoc[1] - containerLoc[1]).coerceAtLeast(0)
            val barWidth = bottomBar.width
            val barHeight = bottomBar.height

            if (barWidth > 0 && barHeight > 0) {
                statusGroup.translationX = 0f
                statusGroup.translationY = 0f
                statusGroup.scaleX = 1f
                statusGroup.scaleY = 1f

                val params = statusGroup.layoutParams as? android.widget.FrameLayout.LayoutParams
                    ?: android.widget.FrameLayout.LayoutParams(barWidth, barHeight)
                params.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                params.leftMargin = relX
                params.topMargin = relY
                params.width = barWidth
                params.height = barHeight
                statusGroup.layoutParams = params

                hudOverlayManager.binding.llGpsInfo?.let { gpsInfo ->
                    val infoParams = gpsInfo.layoutParams as? android.widget.LinearLayout.LayoutParams
                        ?: android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                        )
                    infoParams.width = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                    infoParams.height = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                    gpsInfo.layoutParams = infoParams
                    gpsInfo.setBackgroundResource(R.drawable.bg_gps_end_btn)

                    gpsInfo.gravity = android.view.Gravity.CENTER_VERTICAL
                    val padH = (16 * resources.displayMetrics.density).toInt()
                    gpsInfo.setPadding(padH, 0, padH, 0)
                    val hasAddr = lastKnownAddress.isNotEmpty()
                    hudOverlayManager.binding.tvGpsAddress?.visibility = if (hasAddr) android.view.View.VISIBLE else android.view.View.GONE
                    hudOverlayManager.binding.vGpsDivider?.visibility = if (hasAddr) android.view.View.VISIBLE else android.view.View.GONE
                    val shouldShowCancel = hasStartedRouteGuidance && isGuidanceActive && !isShowingPreview
                    hudOverlayManager.binding.btnGpsCancelRoute?.visibility = if (shouldShowCancel) android.view.View.VISIBLE else android.view.View.GONE
                    updateEtaUi()
                }

                statusGroup.elevation = 12f * resources.displayMetrics.density
                statusGroup.visibility = if (hudOverlayManager.isOverlayVisible && !isShowingPreview) android.view.View.VISIBLE else android.view.View.GONE
                statusGroup.isClickable = true
                statusGroup.isFocusable = true
                statusGroup.setOnClickListener { /* Consume touch */ }
                hudOverlayManager.binding.llGpsInfo?.setOnClickListener { /* Consume touch */ }

                // 우측 버튼(llRightBottomGrid)이 GPS 오버레이(하단 바)와 겹치지 않도록 오버레이 바로 위에 배치
                hudOverlayManager.binding.llRightBottomGrid?.let { grid ->
                    val gridParams = grid.layoutParams as? android.widget.FrameLayout.LayoutParams
                        ?: android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                        )
                    val gap = (16 * resources.displayMetrics.density).toInt()
                    val containerHeight = mapContainer.height
                    val bottomMargin = if (containerHeight > relY && relY > 0) {
                        (containerHeight - relY) + gap
                    } else {
                        barHeight + gap
                    }
                    if (gridParams.bottomMargin != bottomMargin) {
                        gridParams.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                        gridParams.bottomMargin = bottomMargin
                        grid.layoutParams = gridParams
                    }

                    val cardOffset = (168 * resources.displayMetrics.density).toInt()
                    (hudOverlayManager.binding.cvMediaOverlayCard.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { cardParams ->
                        val cardBottomMargin = bottomMargin + cardOffset
                        if (cardParams.bottomMargin != cardBottomMargin) {
                            cardParams.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                            cardParams.bottomMargin = cardBottomMargin
                            hudOverlayManager.binding.cvMediaOverlayCard.layoutParams = cardParams
                        }
                    }
                }

                syncGoalTextAddress()
            }
        } else {
            bottomBar?.post { alignGpsOverlayWithBottomBar() }
        }
    }

    private fun cancelRouteAndReturnToTmap() {
        Log.d("KakaoMapActivity", "Cancel Route button clicked, stopping guidance")
        currentRemainDist = 0L
        currentRemainTime = 0L
        updateEtaUi()
        alignQuickDestGroupWithTbt()
        sharedPref.edit().putString("ACTIVE_NAVI", "tmap").apply()
        sharedPref.edit().apply {
            remove("RECENT_DEST_NAME")
            remove("RECENT_DEST_ROAD_ADDRESS")
            remove("RECENT_DEST_ADDRESS")
            remove("RECENT_DEST_X")
            remove("RECENT_DEST_Y")
            remove("RECENT_DEST_TIMESTAMP")
            apply()
        }
        RouteInfoRepository.updateRouteInfo("", 0, 0, "tmap")
        KNSDK.sharedGuidance()?.stop()
        if (!isFinishing) finish()
    }

    private fun updateEtaUi() {
        if (!::hudOverlayManager.isInitialized) return
        val etaGroup = hudOverlayManager.binding.llRouteEtaGroup ?: return
        val shouldShow = hasStartedRouteGuidance && isGuidanceActive && !isShowingPreview && (currentRemainDist > 0 || currentRemainTime > 0)
        if (!shouldShow) {
            etaGroup.visibility = android.view.View.GONE
            return
        }

        etaGroup.visibility = android.view.View.VISIBLE

        // 남은 거리 포맷
        val distStr = if (currentRemainDist >= 1000) {
            String.format(java.util.Locale.US, "%.1f km", currentRemainDist / 1000.0)
        } else {
            "${currentRemainDist} m"
        }
        hudOverlayManager.binding.tvRemainDist?.text = distStr

        // 도착시간 / 남은시간 포맷 (터치 토글)
        if (isShowingRemainingTime) {
            hudOverlayManager.binding.tvEtaLabel?.text = "남음"
            val totalMins = (currentRemainTime / 60).toInt()
            val hours = totalMins / 60
            val mins = totalMins % 60
            val timeStr = if (hours > 0) {
                "${hours}시간 ${mins}분"
            } else {
                "${mins}분"
            }
            hudOverlayManager.binding.tvEtaTime?.text = timeStr
        } else {
            hudOverlayManager.binding.tvEtaLabel?.text = "도착"
            val arrivalCalendar = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.SECOND, currentRemainTime.toInt())
            }
            val sdf = java.text.SimpleDateFormat("a h:mm", java.util.Locale.KOREAN)
            hudOverlayManager.binding.tvEtaTime?.text = sdf.format(arrivalCalendar.time)
        }
    }

    private fun syncGoalTextAddress() {
        if (!::hudOverlayManager.isInitialized) return
        val tvGoal = (if (goalTextId != 0) findViewById<android.widget.TextView?>(goalTextId) else null)
            ?: findKakaoViewById("bottom_drive_goal_text") as? android.widget.TextView
        if (tvGoal != null) {
            if (!isGoalTextWatcherAttached) {
                isGoalTextWatcherAttached = true
                tvGoal.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val txt = s?.toString()?.trim() ?: ""
                        if (txt.isNotEmpty() && txt != lastKnownAddress) {
                            lastKnownAddress = txt
                            runOnUiThread { updateGpsAddressUi(txt) }
                        }
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
            }
            val curText = tvGoal.text?.toString()?.trim() ?: ""
            if (curText.isNotEmpty() && curText != lastKnownAddress) {
                lastKnownAddress = curText
                updateGpsAddressUi(curText)
            }
        }
    }

    private fun updateGpsAddressUi(address: String) {
        if (!::hudOverlayManager.isInitialized) return
        val bottomBar = (if (bottomBarId != 0) findViewById<android.view.View?>(bottomBarId) else null)
            ?: findKakaoViewById("component_bottom")
            ?: findKakaoViewById("bottom_drive_constraint_layout")
        val hasValidBar = bottomBar != null && (bottomBar.width > 0 || bottomBar.isShown)
        hudOverlayManager.binding.tvGpsAddress?.let { tv ->
            tv.text = address
            tv.isSelected = true
            tv.visibility = if (hasValidBar && address.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
        hudOverlayManager.binding.vGpsDivider?.let { divider ->
            divider.visibility = if (hasValidBar && address.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun updateAddressFromCoordinates(lat: Double, lon: Double, fallbackRoad: String? = null) {
        val now = System.currentTimeMillis()
        if (now - lastAddressFetchTime < 5000 && lastKnownAddress.isNotEmpty()) {
            return
        }
        lastAddressFetchTime = now

        Thread {
            try {
                val geocoder = android.location.Geocoder(this, java.util.Locale.KOREAN)
                val list = geocoder.getFromLocation(lat, lon, 1)
                if (!list.isNullOrEmpty()) {
                    val addr = list[0]
                    val fullAddr = addr.getAddressLine(0)?.replace("대한민국", "")?.trim() ?: ""
                    val result = when {
                        !addr.thoroughfare.isNullOrEmpty() -> {
                            val sub = addr.subThoroughfare ?: addr.featureName ?: ""
                            "${addr.locality ?: addr.adminArea ?: ""} ${addr.thoroughfare} $sub".trim()
                        }
                        fullAddr.isNotEmpty() -> fullAddr
                        else -> fallbackRoad ?: ""
                    }
                    if (result.isNotEmpty() && lastKnownAddress.isEmpty()) {
                        lastKnownAddress = result
                        runOnUiThread { updateGpsAddressUi(result) }
                    }
                } else if (!fallbackRoad.isNullOrEmpty() && lastKnownAddress.isEmpty()) {
                    lastKnownAddress = fallbackRoad
                    runOnUiThread { updateGpsAddressUi(fallbackRoad) }
                }
            } catch (e: Exception) {
                if (!fallbackRoad.isNullOrEmpty() && lastKnownAddress.isEmpty()) {
                    lastKnownAddress = fallbackRoad
                    runOnUiThread { updateGpsAddressUi(fallbackRoad) }
                }
            }
        }.start()
    }

    override fun guidanceDidUpdateAroundSafeties(guidance: KNGuidance, safeties: List<KNSafety>?) {
        if(::naviView.isInitialized) naviView.guidanceDidUpdateAroundSafeties(guidance, safeties)
    }

    override fun guidanceGuideStarted(guidance: KNGuidance) {
        if(::naviView.isInitialized) naviView.guidanceGuideStarted(guidance)
        isGuidanceActive = true
        val currentRoute = guidance.routesOnGuide?.firstOrNull()
        val curLoc = guidance.locationGuide?.location
        if (currentRoute != null && curLoc != null) {
            currentRemainDist = currentRoute.remainDistFromLocation(curLoc).toLong()
            currentRemainTime = currentRoute.remainTimeFromLocation(curLoc).toLong()
        }
        updateEtaUi()
        hudOverlayManager.binding.btnGpsCancelRoute?.visibility = if (!isShowingPreview) android.view.View.VISIBLE else android.view.View.GONE
        if (!isShowingPreview) {
            hudOverlayManager.binding.btnSearchAddress.visibility = android.view.View.VISIBLE
            hudOverlayManager.binding.llRightBottomGrid?.visibility = android.view.View.VISIBLE
        }
        lastCameraSignX = -1f
        lastCameraSignY = -1f
        if (::binding.isInitialized) {
            binding.root.post {
                alignSpeedGroupWithCameraSign()
                alignGpsOverlayWithBottomBar()
                alignQuickDestGroupWithTbt()
            }
            binding.root.postDelayed({
                alignSpeedGroupWithCameraSign()
                alignGpsOverlayWithBottomBar()
                alignQuickDestGroupWithTbt()
            }, 500)
            binding.root.postDelayed({
                alignGpsOverlayWithBottomBar()
                alignQuickDestGroupWithTbt()
            }, 1500)
        }
    }

    override fun guidanceGuideEnded(guidance: KNGuidance) {
        if(::naviView.isInitialized) naviView.guidanceGuideEnded(guidance)
        isGuidanceActive = false
        hudOverlayManager.binding.btnGpsCancelRoute?.visibility = android.view.View.GONE
        currentRemainDist = 0L
        currentRemainTime = 0L
        updateEtaUi()
        alignQuickDestGroupWithTbt()
        // 안내 정상 종료 시 복구 정보 삭제
        sharedPref.edit().apply {
            remove("RECENT_DEST_NAME")
            remove("RECENT_DEST_ROAD_ADDRESS")
            remove("RECENT_DEST_ADDRESS")
            remove("RECENT_DEST_X")
            remove("RECENT_DEST_Y")
            remove("RECENT_DEST_TIMESTAMP")
            apply()
        }
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
                currentRemainDist = remainDist.toLong()
                currentRemainTime = remainTime.toLong()
                runOnUiThread { updateEtaUi() }
                
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

        if (lat != 0.0 && lon != 0.0) {
            if (lastKnownAddress.isEmpty() || (roadName.isNotEmpty() && roadName != lastKnownRoadName)) {
                lastKnownRoadName = roadName
                updateAddressFromCoordinates(lat, lon, roadName)
            }
        }
        alignGpsOverlayWithBottomBar()
        alignQuickDestGroupWithTbt()
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
        val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        VoiceDuckingManager.onVoiceStart(audioManager, "kakao")
    }
    override fun didFinishPlayVoiceGuide(guidance: KNGuidance, voiceGuide: com.kakaomobility.knsdk.guidance.knguidance.voiceguide.KNGuide_Voice) {
        if(::naviView.isInitialized) naviView.didFinishPlayVoiceGuide(guidance, voiceGuide)
        VoiceDuckingManager.onVoiceEnd("kakao")
    }
    override fun didUpdateCitsGuide(guidance: KNGuidance, citsGuide: com.kakaomobility.knsdk.guidance.knguidance.citsguide.KNGuide_Cits) {
        if(::naviView.isInitialized) naviView.didUpdateCitsGuide(guidance, citsGuide)
    }

    
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!::binding.isInitialized) return
        
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
        if (isFinishing || isDestroyed) return
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

        Toast.makeText(this@KakaoMapActivity, "경로 탐색 중...", Toast.LENGTH_SHORT).show()

        com.kakaomobility.knsdk.KNSDK.makeTripWithStart(startPoi, goalPoi, null) { error, trip ->
            runOnUiThread {
            if (error != null || trip == null) {
                Toast.makeText(this@KakaoMapActivity, "경로 탐색 실패: ${error?.msg ?: "알 수 없는 오류"}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@KakaoMapActivity, "경로 안내를 시작합니다.", Toast.LENGTH_SHORT).show()
                SearchHistoryManager.addHistory(this@KakaoMapActivity, SearchHistoryItem.fromKakaoDocument(doc))
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
                hudOverlayManager.binding.btnGpsCancelRoute?.visibility = android.view.View.VISIBLE
                hudOverlayManager.binding.btnSearchAddress.visibility = android.view.View.VISIBLE
                hudOverlayManager.binding.llRightBottomGrid?.visibility = android.view.View.VISIBLE
                val destTitle = doc.road_address_name.ifEmpty { doc.address_name.ifEmpty { doc.place_name } }
                if (destTitle.isNotEmpty()) {
                    lastKnownAddress = destTitle
                    updateGpsAddressUi(destTitle)
                }
                lastCameraSignX = -1f
                lastCameraSignY = -1f
                if (::binding.isInitialized) {
                    binding.root.postDelayed({
                        alignSpeedGroupWithCameraSign()
                        alignGpsOverlayWithBottomBar()
                        alignQuickDestGroupWithTbt()
                    }, 300)
                    binding.root.postDelayed({
                        alignSpeedGroupWithCameraSign()
                        alignGpsOverlayWithBottomBar()
                        alignQuickDestGroupWithTbt()
                    }, 1000)
                    binding.root.postDelayed({
                        alignGpsOverlayWithBottomBar()
                        alignQuickDestGroupWithTbt()
                    }, 2500)
                }
                intent.removeExtra("dest_place_name")
                
                // 최근 목적지 정보 저장 (안내 중 비정상 종료 시 복구 목적)
                sharedPref.edit().apply {
                    putString("RECENT_DEST_NAME", doc.place_name)
                    putString("RECENT_DEST_ROAD_ADDRESS", doc.road_address_name)
                    putString("RECENT_DEST_ADDRESS", doc.address_name)
                    putString("RECENT_DEST_X", doc.x)
                    putString("RECENT_DEST_Y", doc.y)
                    putLong("RECENT_DEST_TIMESTAMP", System.currentTimeMillis())
                    apply()
                }
            }
        }
    }
}

    override fun onResume() {
        super.onResume()
        updateRoadSpeedLimitVisibility()
        updateMediaUIFromService()
        if (::binding.isInitialized) {
            binding.root.postDelayed({
                alignGpsOverlayWithBottomBar()
                alignQuickDestGroupWithTbt()
            }, 500)
            binding.root.postDelayed({
                alignGpsOverlayWithBottomBar()
                alignQuickDestGroupWithTbt()
            }, 1500)
        }
        val intent = android.content.Intent(MediaNotificationListenerService.ACTION_MEDIA_CONTROL).apply {
            setPackage(packageName)
            putExtra("command", "refresh")
        }
        sendBroadcast(intent)
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
        if (::locationManager.isInitialized) locationManager.removeUpdates(this)
        
        previewTimer?.cancel()
        previewTimer = null
        mediaProgressHandler.removeCallbacksAndMessages(null)
        
        try {
            presentation?.dismiss()
            streamingManager?.stop()
            v2Client?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 백스택 복귀시 카카오 안내가 종료되게 하되,
        // 종료될 때 onDestroy가 또 불려서 TMAP 복귀를 방해하지 않도록 조건 추가
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
        hudOverlayManager.binding.btnGpsCancelRoute?.visibility = android.view.View.GONE
        hudOverlayManager.binding.llRouteEtaGroup?.visibility = android.view.View.GONE
        hudOverlayManager.binding.llQuickDestGroup.visibility = android.view.View.GONE
        hudOverlayManager.binding.llRightBottomGrid?.visibility = android.view.View.GONE
        hudOverlayManager.binding.cvMediaOverlayCard.visibility = android.view.View.GONE

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
        
        val shouldShowCancel = hasStartedRouteGuidance && isGuidanceActive
        hudOverlayManager.binding.btnGpsCancelRoute?.visibility = if (shouldShowCancel) android.view.View.VISIBLE else android.view.View.GONE
        hudOverlayManager.binding.btnSearchAddress.visibility = android.view.View.VISIBLE
        hudOverlayManager.binding.llRightBottomGrid?.visibility = android.view.View.VISIBLE
        hudOverlayManager.updateOverlayVisibility()
        updateRoadSpeedLimitVisibility()
        updateEtaUi()
        binding.root.postDelayed({
            alignGpsOverlayWithBottomBar()
            alignQuickDestGroupWithTbt()
        }, 300)
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
