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

    // 단속 이벤트 및 도로 기본 제한속도 제어용 상태
    private var currentRoadLimitSpeed = 0
    private var isCameraEventActive = false
    private var lastCameraSignX = -1f
    private var lastCameraSignY = -1f
    private var splitHandleManager: SplitHandleManager? = null
    
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
                
                if (::hudOverlayManager.isInitialized) {
                    hudOverlayManager.updateMediaProgress()
                }

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

        if (::hudOverlayManager.isInitialized) {
            hudOverlayManager.updateMediaOverlayUi(
                title,
                artist,
                MediaNotificationListenerService.currentAlbumArt ?: MediaNotificationListenerService.fetchedAlbumArt,
                isPlaying
            )
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
            "MEDIA_SPLIT_RATIO", "MEDIA_SPLIT_RATIO_F", "MEDIA_SPLIT_RATIO_PORTRAIT_F", "MEDIA_SPLIT_RATIO_LANDSCAPE_F" -> {
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
        lastCameraSignX = -1f
        lastCameraSignY = -1f
        if (::binding.isInitialized) {
            updateMediaLayout(newConfig.orientation)
            updateRoadSpeedLimitVisibility()
            if (::hudOverlayManager.isInitialized) {
                hudOverlayManager.restoreMediaOverlayPosition(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
            }
            binding.root.postDelayed({ alignGpsOverlayWithEndButton() }, 300)
            binding.root.postDelayed({ alignGpsOverlayWithEndButton() }, 800)
            binding.root.postDelayed({ alignSpeedGroupWithCameraSign() }, 300)
            binding.root.postDelayed({ alignSpeedGroupWithCameraSign() }, 800)
        }
        if (!isResumedState) {
            needFragmentRecreate = true
            Log.d("MapActivity", "Orientation changed in background, will recreate fragment view on resume")
        }
    }

    private fun updateMediaLayout(orientation: Int) {
        val sharedPref = getSharedPreferences("CarrotNaviPrefs", android.content.Context.MODE_PRIVATE)
        val ratioKey = if (orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) "MEDIA_SPLIT_RATIO_PORTRAIT_F" else "MEDIA_SPLIT_RATIO_LANDSCAPE_F"
        val configuredRatio = if (sharedPref.contains(ratioKey)) {
            sharedPref.getFloat(ratioKey, 3.5f)
        } else if (sharedPref.contains("MEDIA_SPLIT_RATIO_F")) {
            sharedPref.getFloat("MEDIA_SPLIT_RATIO_F", 3.5f)
        } else {
            val oldRatio = sharedPref.getInt("MEDIA_SPLIT_RATIO", 4).toFloat()
            if (oldRatio >= 5f) 5f else oldRatio
        }

        // 미디어 오버레이 활성화 시 분할모드 비활성화 및 주행화면 전체 표출 (5.0f)
        val isMediaOverlayActive = ::hudOverlayManager.isInitialized && hudOverlayManager.isMediaOverlayActive
        val ratio = if (isMediaOverlayActive) 5.0f else configuredRatio
        splitHandleManager?.setHandleVisible(!isMediaOverlayActive)
        
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
        
        val sharedPref = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
        sharedPref.registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        // 현재 앱이 Tmap 모드임을 명시적으로 설정하여 JSON 로그 송신 오류 수정
        sharedPref.edit().putString("ACTIVE_NAVI", "tmap").apply()
        VoiceDuckingManager.init(this)
        startUdpSenderService()

        hudBinding = com.example.carrotnavi.databinding.LayoutHudOverlaysBinding.bind(binding.root)
        hudOverlayManager = HudOverlayManager(this, hudBinding, this)
        hudOverlayManager.onMediaOverlayVisibilityChanged = { isVisible ->
            splitHandleManager?.setHandleVisible(!isVisible)
            updateMediaLayout(resources.configuration.orientation)
        }
        if (hudOverlayManager.isMediaOverlayActive) {
            splitHandleManager?.setHandleVisible(false)
            updateMediaLayout(resources.configuration.orientation)
        }
        hudOverlayManager.binding.btnSearchAddress.setOnClickListener {
            showSearchDialog()
        }

        // 앨범아트 클릭 시 미디어 화면 분할 설정 메뉴 표시
        binding.root.findViewById<android.view.View>(R.id.cvAlbumArtContainer)?.setOnClickListener {
            hudOverlayManager.showMediaSettingsDialog()
        }
        binding.root.findViewById<android.view.View>(R.id.ivAlbumArtThumbnail)?.setOnClickListener {
            hudOverlayManager.showMediaSettingsDialog()
        }
        hudOverlayManager.onQuickDestinationSelected = { doc ->
            val naviIntent = Intent(this@MapActivity, KakaoMapActivity::class.java).apply {
                putExtra("dest_place_name", doc.place_name)
                putExtra("dest_road_address_name", doc.road_address_name)
                putExtra("dest_address_name", doc.address_name)
                putExtra("dest_x", doc.x)
                putExtra("dest_y", doc.y)
            }
            startActivity(naviIntent)
        }
        hudOverlayManager.onOverlayVisibilityChanged = {
            updateRoadSpeedLimitVisibility()
            alignGpsOverlayWithEndButton()
        }

        binding.root.findViewById<android.view.View>(R.id.mapOverlayContainer)?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            alignGpsOverlayWithEndButton()
            alignSpeedGroupWithCameraSign()
        }

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

        if (::hudBinding.isInitialized && hudBinding.cvMediaOverlayCard.visibility == View.VISIBLE) {
            val cardRect = android.graphics.Rect()
            hudBinding.cvMediaOverlayCard.getGlobalVisibleRect(cardRect)
            if (cardRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                return super.dispatchTouchEvent(ev)
            }
        }

        if (::hudBinding.isInitialized && hudBinding.llQuickDestGroup?.visibility == View.VISIBLE) {
            val quickRect = android.graphics.Rect()
            hudBinding.llQuickDestGroup?.getGlobalVisibleRect(quickRect)
            if (quickRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                return super.dispatchTouchEvent(ev)
            }
        }

        // TMAP 안심주행 하단 바 영역(navigation_eta: 주행종료, 현위치 주소, 메뉴 버튼 등) 터치 제한
        val etaView = if (etaViewId != 0) findViewById<View?>(etaViewId) else null
        if (etaView != null && etaView.isShown) {
            val rect = android.graphics.Rect()
            etaView.getGlobalVisibleRect(rect)
            if (rect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                // 하단 바 영역의 터치 이벤트는 가로채서 무시 (지도 및 다른 영역은 정상 터치됨)
                return true
            }
        } else {
            val endBtn = if (endBtnId != 0) findViewById<View?>(endBtnId) else null
            if (endBtn != null && endBtn.isShown) {
                val rect = android.graphics.Rect()
                endBtn.getGlobalVisibleRect(rect)
                if (rect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    return true
                }
            }
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
                binding.root.postDelayed({ alignGpsOverlayWithEndButton() }, 500)
                binding.root.postDelayed({ alignGpsOverlayWithEndButton() }, 1500)
                binding.root.postDelayed({ alignGpsOverlayWithEndButton() }, 3000)
                binding.root.postDelayed({ alignSpeedGroupWithCameraSign() }, 500)
                binding.root.postDelayed({ alignSpeedGroupWithCameraSign() }, 1500)
                binding.root.postDelayed({ alignSpeedGroupWithCameraSign() }, 3000)
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
                } else {
                    val sharedPref = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
                    val recentDestName = sharedPref.getString("RECENT_DEST_NAME", null)
                    val recentTimestamp = sharedPref.getLong("RECENT_DEST_TIMESTAMP", 0L)
                    
                    if (recentDestName != null && recentTimestamp > 0L) {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - recentTimestamp < 3600_000L) { // 1 hour
                            val dialog = android.app.AlertDialog.Builder(this@MapActivity)
                                .setTitle("경로 안내 복구")
                                .setMessage("최근 안내 중이던 목적지('$recentDestName')로 안내를 다시 시작하시겠습니까?")
                                .setCancelable(false)
                                .setPositiveButton("확인(5)") { _, _ ->
                                    val destRoadAddress = sharedPref.getString("RECENT_DEST_ROAD_ADDRESS", "")
                                    val destAddress = sharedPref.getString("RECENT_DEST_ADDRESS", "")
                                    val destX = sharedPref.getString("RECENT_DEST_X", "")
                                    val destY = sharedPref.getString("RECENT_DEST_Y", "")
                                    
                                    val naviIntent = Intent(this@MapActivity, KakaoMapActivity::class.java).apply {
                                        putExtra("dest_place_name", recentDestName)
                                        putExtra("dest_road_address_name", destRoadAddress)
                                        putExtra("dest_address_name", destAddress)
                                        putExtra("dest_x", destX)
                                        putExtra("dest_y", destY)
                                    }
                                    startActivity(naviIntent)
                                }
                                .setNegativeButton("취소") { _, _ ->
                                    sharedPref.edit().apply {
                                        remove("RECENT_DEST_NAME")
                                        remove("RECENT_DEST_ROAD_ADDRESS")
                                        remove("RECENT_DEST_ADDRESS")
                                        remove("RECENT_DEST_X")
                                        remove("RECENT_DEST_Y")
                                        remove("RECENT_DEST_TIMESTAMP")
                                        apply()
                                    }
                                }
                                .create()

                            dialog.setOnShowListener {
                                val positiveButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                                val timer = object : android.os.CountDownTimer(5000, 1000) {
                                    override fun onTick(millisUntilFinished: Long) {
                                        val seconds = (millisUntilFinished / 1000) + 1
                                        positiveButton.text = "확인($seconds)"
                                    }

                                    override fun onFinish() {
                                        if (dialog.isShowing) {
                                            positiveButton.performClick()
                                        }
                                    }
                                }
                                dialog.setOnDismissListener { timer.cancel() }
                                timer.start()
                            }
                            dialog.show()
                        } else {
                            sharedPref.edit().apply {
                                remove("RECENT_DEST_NAME")
                                remove("RECENT_DEST_ROAD_ADDRESS")
                                remove("RECENT_DEST_ADDRESS")
                                remove("RECENT_DEST_X")
                                remove("RECENT_DEST_Y")
                                remove("RECENT_DEST_TIMESTAMP")
                                apply()
                            }
                        }
                    }
                }
            }, 1000)

            frag.setNavigationScreenStateListener(object : com.tmapmobility.tmap.tmapsdk.ui.data.NavigationScreenStateListener {
                override fun onChanged(state: com.tmapmobility.tmap.tmapsdk.ui.data.NavigationScreenState) {
                    val stateName = state.javaClass.simpleName
                    Log.d("MapActivity", "NavigationScreenState changed: $stateName")
                    binding.root.post { alignGpsOverlayWithEndButton() }
                    binding.root.postDelayed({ alignGpsOverlayWithEndButton() }, 800)
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
                    currentRoadLimitSpeed = realRoadLimit
                    updateRoadSpeedLimitVisibility()

                    if (it is android.os.Bundle) {
                        extractAndDisplaySdiInfo(it)
                    }
                    syncCurrentAddress()
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
        updateRoadSpeedLimitVisibility()
        if (::hudOverlayManager.isInitialized) {
            hudOverlayManager.updateOverlayVisibility()
        }
        binding.root.postDelayed({ alignGpsOverlayWithEndButton() }, 500)
        binding.root.postDelayed({ alignGpsOverlayWithEndButton() }, 1500)
        binding.root.postDelayed({ alignSpeedGroupWithCameraSign() }, 500)
        binding.root.postDelayed({ alignSpeedGroupWithCameraSign() }, 1500)
        
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
            var hasCamera = false
            if (sdiObj != null) {
                val sdiJsonStr = if (sdiObj is String) sdiObj else com.google.gson.Gson().toJson(sdiObj)
                val json = org.json.JSONObject(sdiJsonStr)
                
                val sdiType = json.optInt("nSdiType", 0)
                var sdiSpeedLimit = json.optInt("nSdiSpeedLimit", 0)
                val sdiDist = json.optInt("nSdiDist", 0)
                
                // 단속 카메라 또는 주의구간 이벤트 발생 여부:
                // sdiDist > 0이고 단속 유형이나 제한속도가 유효할 때
                if ((sdiType > 0 || sdiSpeedLimit > 0) && sdiDist > 0) {
                    hasCamera = true
                }
                
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
            }
            
            // TMAP 네이티브 뷰 상에서 단속 카메라 표지판이 떠 있는지 추가 확인 및 좌표 캡처
            val limitSign = findTmapViewById("LimitSpeedSign")
            if (limitSign != null && limitSign.visibility == android.view.View.VISIBLE && limitSign.width > 0) {
                hasCamera = true
                val signLoc = IntArray(2)
                val containerLoc = IntArray(2)
                val mapContainer = findViewById<android.view.View>(R.id.mapOverlayContainer)
                if (mapContainer != null) {
                    limitSign.getLocationOnScreen(signLoc)
                    mapContainer.getLocationOnScreen(containerLoc)
                    val relX = (signLoc[0] - containerLoc[0]).toFloat()
                    val relY = (signLoc[1] - containerLoc[1]).toFloat()
                    if (relX >= 0 && relY >= 0) {
                        lastCameraSignX = relX
                        lastCameraSignY = relY
                    }
                }
            }

            isCameraEventActive = hasCamera
            updateRoadSpeedLimitVisibility()
        } catch (e: Exception) {
            Log.e("MapActivity", "Error extracting SDI Info: ${e.message}")
        }
    }

    private fun findTmapViewById(name: String): android.view.View? {
        val id = resources.getIdentifier(name, "id", packageName)
        return if (id != 0) findViewById(id) else null
    }

    private fun updateRoadSpeedLimitVisibility() {
        if (!::hudOverlayManager.isInitialized || !::hudBinding.isInitialized) return
        runOnUiThread {
            if (!::hudOverlayManager.isInitialized || !::hudBinding.isInitialized) return@runOnUiThread
            // 단속 이벤트가 없을 때만 도로 기본 제한속도(30 이상) 파란색 원을 표시
            // 단속 이벤트 발생 시 파란색 원을 숨겨서 해당 위치에 TMAP 단속 카메라가 대체 표시되도록 함
            val shouldShow = !isCameraEventActive && currentRoadLimitSpeed >= 30 && hudOverlayManager.isOverlayVisible
            if (shouldShow) {
                hudBinding.llSpeedGroup.visibility = android.view.View.VISIBLE
                hudBinding.llRoadSpeedLimit.visibility = android.view.View.VISIBLE
                hudBinding.tvRoadSpeedLimit.text = currentRoadLimitSpeed.toString()
                alignSpeedGroupWithCameraSign()
            } else {
                hudBinding.llSpeedGroup.visibility = android.view.View.GONE
                hudBinding.llRoadSpeedLimit.visibility = android.view.View.GONE
            }
        }
    }

    private var isSpeedLayoutListenerAttached = false

    private fun alignSpeedGroupWithCameraSign() {
        if (!::hudOverlayManager.isInitialized || !::hudBinding.isInitialized) return
        val speedGroup = hudBinding.llSpeedGroup ?: return
        val mapContainer = findViewById<android.view.View>(R.id.mapOverlayContainer) ?: return

        // 1. 실시간: TMAP 현재속도계(speedNumberArea / current_speed_text / sdi_speed_view)의 바로 위에 동적 배치
        val speedView = findTmapViewById("speedNumberArea")
            ?: findTmapViewById("current_speed_text")
            ?: findTmapViewById("sdi_speed_view")

        if (speedView != null && (speedView.isShown || speedView.visibility == android.view.View.VISIBLE) && speedView.width > 0 && speedView.height > 0) {
            if (!isSpeedLayoutListenerAttached) {
                isSpeedLayoutListenerAttached = true
                speedView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    alignSpeedGroupWithCameraSign()
                }
            }

            val speedLoc = IntArray(2)
            val containerLoc = IntArray(2)
            speedView.getLocationOnScreen(speedLoc)
            mapContainer.getLocationOnScreen(containerLoc)
            val density = resources.displayMetrics.density
            val groupWidth = if (speedGroup.width > 0) speedGroup.width else (72 * density).toInt()
            val groupHeight = if (speedGroup.height > 0) speedGroup.height else (72 * density).toInt()

            val relX = speedLoc[0] - containerLoc[0]
            val relY = speedLoc[1] - containerLoc[1]

            val speedCenterX = relX + (speedView.width / 2)
            val speedTopY = relY

            val targetX = (speedCenterX - (groupWidth / 2)).coerceAtLeast(0)
            val targetY = (speedTopY - groupHeight - (4 * density).toInt()).coerceAtLeast(0)

            lastCameraSignX = targetX.toFloat()
            lastCameraSignY = targetY.toFloat()
            applySpeedGroupMargin(speedGroup, targetX, targetY)
            return
        }

        // 2. 실시간 단속 카메라 표지판(LimitSpeedSign)이 레이아웃 상에 실제로 표시 중이면 그 위치 사용
        val limitSign = findTmapViewById("LimitSpeedSign")
        if (limitSign != null && limitSign.isShown && limitSign.width > 0 && limitSign.height > 0) {
            val signLoc = IntArray(2)
            val containerLoc = IntArray(2)
            limitSign.getLocationOnScreen(signLoc)
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

        // 3. Fallback: 이전에 캡처된 유효 좌표가 있으면 사용
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

    fun alignGpsOverlayWithEndButton() {
        if (!::hudOverlayManager.isInitialized || !::hudBinding.isInitialized) return
        val statusGroup = hudBinding.llStatusGroup ?: return
        val mapContainer = findViewById<android.view.View>(R.id.mapOverlayContainer) ?: return
        val endBtn = if (endBtnId != 0) findViewById<android.view.View?>(endBtnId) else null
        val etaView = if (etaViewId != 0) findViewById<android.view.View?>(etaViewId) else null

        if (endBtn != null && (endBtn.isShown || endBtn.visibility == android.view.View.VISIBLE) && endBtn.width > 0 && endBtn.height > 0) {
            val btnLoc = IntArray(2)
            val containerLoc = IntArray(2)
            endBtn.getLocationOnScreen(btnLoc)
            mapContainer.getLocationOnScreen(containerLoc)

            val relX = btnLoc[0] - containerLoc[0]
            val relY = btnLoc[1] - containerLoc[1]
            val btnWidth = endBtn.width
            val btnHeight = endBtn.height

            if (relX >= 0 && relY >= 0 && btnWidth > 0 && btnHeight > 0) {
                // 하단 바(CardView 또는 navigation_eta) 찾기
                val barView = (etaView as? android.view.ViewGroup)?.let { vg ->
                    if (vg.childCount > 0 && vg.getChildAt(0).width > 0 && vg.getChildAt(0).height > 0) {
                        vg.getChildAt(0)
                    } else {
                        vg
                    }
                } ?: etaView
                val hasValidBar = barView != null && barView.height > 0 && barView.width > 0

                val (targetLeft, targetWidth, targetTop, targetHeight) = if (hasValidBar) {
                    // 가로/세로 공통: 하단 바(CardView)의 좌우 끝자락 및 상하 높이와 정확히 동일하게 맞춤
                    val barLoc = IntArray(2)
                    barView!!.getLocationOnScreen(barLoc)
                    val barRelX = (barLoc[0] - containerLoc[0]).coerceAtLeast(0)
                    val barRelY = (barLoc[1] - containerLoc[1]).coerceAtLeast(0)
                    val barWidth = barView.width
                    val barHeight = barView.height
                    arrayOf(barRelX, barWidth, barRelY, barHeight)
                } else {
                    // 예외 fallback: 주행종료 버튼 위치 및 크기에 맞춤
                    arrayOf(relX, btnWidth, relY, btnHeight)
                }

                val sp = getSharedPreferences("CarrotNaviPrefs", android.content.Context.MODE_PRIVATE)
                val heightOffsetDp = sp.getInt("TMAP_BOTTOM_BAR_HEIGHT_OFFSET", 0)
                val heightOffsetPx = (heightOffsetDp * resources.displayMetrics.density).toInt()
                val finalHeight = (targetHeight + heightOffsetPx).coerceAtLeast((24 * resources.displayMetrics.density).toInt())
                val finalTop = (targetTop - heightOffsetPx).coerceAtLeast(0)

                statusGroup.translationX = 0f
                statusGroup.translationY = 0f
                statusGroup.scaleX = 1f
                statusGroup.scaleY = 1f

                val params = statusGroup.layoutParams as? android.widget.FrameLayout.LayoutParams
                    ?: android.widget.FrameLayout.LayoutParams(targetWidth, finalHeight)
                params.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                params.leftMargin = targetLeft
                params.topMargin = finalTop
                params.width = targetWidth
                params.height = finalHeight
                statusGroup.layoutParams = params

                hudBinding.llGpsInfo?.let { gpsInfo ->
                    val infoParams = gpsInfo.layoutParams as? android.widget.LinearLayout.LayoutParams
                        ?: android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                        )
                    infoParams.width = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                    infoParams.height = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                    gpsInfo.layoutParams = infoParams
                    gpsInfo.setBackgroundResource(R.drawable.bg_gps_end_btn)

                    if (hasValidBar) {
                        // 가로/세로 공통: 좌측에 GPS 상태, 중앙에 현재 주소 표시, 우측에 원터치 목적지 버튼
                        gpsInfo.gravity = android.view.Gravity.CENTER_VERTICAL
                        val padH = (12 * resources.displayMetrics.density).toInt()
                        gpsInfo.setPadding(padH, 0, padH, 0)
                        val hasAddr = lastKnownAddress.isNotEmpty()
                        hudBinding.tvGpsAddress?.visibility = if (hasAddr) android.view.View.VISIBLE else android.view.View.INVISIBLE
                        hudBinding.vGpsDivider?.visibility = if (hasAddr) android.view.View.VISIBLE else android.view.View.GONE
                        hudBinding.llQuickDestGroup?.visibility = if (hudOverlayManager.isOverlayVisible) android.view.View.VISIBLE else android.view.View.GONE
                    } else {
                        // 예외 fallback: 주행종료 버튼 크기이므로 GPS 상태만 중앙 정렬
                        gpsInfo.gravity = android.view.Gravity.CENTER
                        gpsInfo.setPadding(0, 0, 0, 0)
                        hudBinding.tvGpsAddress?.visibility = android.view.View.GONE
                        hudBinding.vGpsDivider?.visibility = android.view.View.GONE
                        hudBinding.llQuickDestGroup?.visibility = android.view.View.GONE
                    }
                }

                statusGroup.elevation = 12f * resources.displayMetrics.density
                statusGroup.visibility = if (hudOverlayManager.isOverlayVisible) android.view.View.VISIBLE else android.view.View.GONE
                statusGroup.isClickable = true
                statusGroup.isFocusable = true
                statusGroup.setOnClickListener { /* Consume touch to prevent safe drive exit */ }
                hudBinding.llGpsInfo?.setOnClickListener { /* Consume touch */ }

                // 우측 4개 버튼(llRightBottomGrid)이 GPS 오버레이(하단 바)와 겹치지 않도록 오버레이 바로 위에 배치
                hudBinding.llRightBottomGrid?.let { grid ->
                    val gridParams = grid.layoutParams as? android.widget.FrameLayout.LayoutParams
                        ?: android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                        )
                    val gap = (16 * resources.displayMetrics.density).toInt()
                    val containerHeight = mapContainer.height
                    val bottomMargin = if (containerHeight > targetTop && targetTop > 0) {
                        (containerHeight - targetTop) + gap
                    } else {
                        targetHeight + gap
                    }
                    if (gridParams.bottomMargin != bottomMargin) {
                        gridParams.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                        gridParams.bottomMargin = bottomMargin
                        grid.layoutParams = gridParams
                    }

                    val cardOffset = (168 * resources.displayMetrics.density).toInt()
                    (hudBinding.cvMediaOverlayCard.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { cardParams ->
                        val cardBottomMargin = bottomMargin + cardOffset
                        if (cardParams.bottomMargin != cardBottomMargin) {
                            cardParams.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                            cardParams.bottomMargin = cardBottomMargin
                            hudBinding.cvMediaOverlayCard.layoutParams = cardParams
                        }
                    }
                }

                syncCurrentAddress()
            }
        }
    }

    private fun syncCurrentAddress() {
        if (!::hudBinding.isInitialized) return
        val tvAddress = if (currentAddressId != 0) findViewById<android.widget.TextView?>(currentAddressId) else null
        if (tvAddress != null) {
            if (!isAddressWatcherAttached) {
                isAddressWatcherAttached = true
                tvAddress.addTextChangedListener(object : android.text.TextWatcher {
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
            val curText = tvAddress.text?.toString()?.trim() ?: ""
            if (curText.isNotEmpty() && curText != lastKnownAddress) {
                lastKnownAddress = curText
                updateGpsAddressUi(curText)
            }
        }
    }

    private fun updateGpsAddressUi(address: String) {
        if (!::hudBinding.isInitialized) return
        val etaView = if (etaViewId != 0) findViewById<android.view.View?>(etaViewId) else null
        val hasValidBar = etaView != null && (etaView.width > 0 || etaView.isShown)
        hudBinding.tvGpsAddress?.let { tv ->
            tv.text = address
            tv.isSelected = true
            tv.visibility = if (hasValidBar && address.isNotEmpty()) android.view.View.VISIBLE else android.view.View.INVISIBLE
        }
        hudBinding.vGpsDivider?.let { divider ->
            divider.visibility = if (hasValidBar && address.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
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

    private val etaViewId by lazy { resources.getIdentifier("navigation_eta", "id", packageName) }
    private val endBtnId by lazy { resources.getIdentifier("btn_end_safe_drive", "id", packageName) }
    private val currentAddressId by lazy { resources.getIdentifier("tv_current_address", "id", packageName) }
    private var isAddressWatcherAttached = false
    private var lastKnownAddress: String = ""
}
