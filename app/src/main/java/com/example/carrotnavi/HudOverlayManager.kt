package com.example.carrotnavi

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.content.Intent
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import android.app.Dialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.carrotnavi.databinding.LayoutHudOverlaysBinding
import kotlin.math.max

class HudOverlayManager(
    private val activity: Activity,
    val binding: LayoutHudOverlaysBinding,
    private val lifecycleOwner: LifecycleOwner
) {
    private val sharedPref: SharedPreferences = activity.getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
    private var isEditMode = false
    var isOverlayVisible = true

    var onQuickDestinationSelected: ((KakaoDocument) -> Unit)? = null
    var onOverlayVisibilityChanged: (() -> Unit)? = null
    var onMediaOverlayVisibilityChanged: ((Boolean) -> Unit)? = null
    var onSplitContentTypeChanged: ((String) -> Unit)? = null

    val isMediaOverlayActive: Boolean
        get() = binding.cvMediaOverlayCard.visibility == View.VISIBLE

    private var lastMediaTitle: String? = null
    private var lastMediaArtist: String? = null
    private var lastMediaAlbumArt: android.graphics.Bitmap? = null
    private var lastMediaIsPlaying: Boolean = false
    private var lastMediaHasTrack: Boolean = false

    private val mediaProgressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val mediaProgressRunnable = object : Runnable {
        override fun run() {
            updateMediaProgress()
            if (lastMediaIsPlaying && binding.cvMediaOverlayCard.visibility == View.VISIBLE) {
                mediaProgressHandler.postDelayed(this, 1000)
            }
        }
    }

    private var initialX = 0f
    private var initialY = 0f
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    // Pinch-to-zoom
    private var initialDistance = 0f
    private var initialScale = 1f
    private var isScaling = false

    private var activeDialogView: android.view.View? = null



    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
        if (activity.isDestroyed || activity.isFinishing) return@OnSharedPreferenceChangeListener
        
        when (key) {
            "OVERLAY_VISIBLE", "DEBUG_OVERLAY_VISIBLE" -> {
                isOverlayVisible = sp.getBoolean("OVERLAY_VISIBLE", true)
                updateOverlayVisibility()
            }
            "SPLIT_CONTENT_TYPE" -> {
                activeDialogView?.let { view ->
                    val splitType = sp.getString("SPLIT_CONTENT_TYPE", "media")
                    val rbMedia = view.findViewById<android.widget.RadioButton>(R.id.rbSplitContentMedia)
                    val rbOp = view.findViewById<android.widget.RadioButton>(R.id.rbSplitContentOpenpilot)
                    if (splitType == "openpilot") {
                        if (rbOp?.isChecked != true) rbOp?.isChecked = true
                    } else {
                        if (rbMedia?.isChecked != true) rbMedia?.isChecked = true
                    }
                }
            }
            "BLOCK_SPEED_ENABLED", "BLOCK_SPEED_OFFSET", "BLOCK_SPEED_FAKE_DROP", "BLOCK_SPEED_BOOST_MODE", "USE_KM_DISTANCE_FORMAT", "REQ_BACKGROUND", "AUDIO_DUCKING_MODE", "VOICE_VOLUME" -> {
                activeDialogView?.let { view ->
                    val cbDistanceFormatKm = view.findViewById<android.widget.Switch>(R.id.cbDistanceFormatKm)
                    val cbBackgroundLocation = view.findViewById<android.widget.Switch>(R.id.cbBackgroundLocation)
                    val swBoostEnable = view.findViewById<android.widget.Switch>(R.id.swBoostEnable)
                    val llBoostSettingsContainer = view.findViewById<android.widget.LinearLayout>(R.id.llBoostSettingsContainer)
                    val sliderOffset = view.findViewById<com.google.android.material.slider.Slider>(R.id.sliderOffset)
                    val tvOffsetValue = view.findViewById<android.widget.TextView>(R.id.tvOffsetValue)
                    val rbBoostProgressive = view.findViewById<android.widget.RadioButton>(R.id.rbBoostProgressive)
                    val rbBoostFixed = view.findViewById<android.widget.RadioButton>(R.id.rbBoostFixed)
                    val sliderFakeDrop = view.findViewById<com.google.android.material.slider.Slider>(R.id.sliderFakeDrop)
                    val tvFakeDropValue = view.findViewById<android.widget.TextView>(R.id.tvFakeDropValue)

                    val isBoostEnabled = sp.getBoolean("BLOCK_SPEED_ENABLED", false)
                    if (swBoostEnable.isChecked != isBoostEnabled) {
                        swBoostEnable.isChecked = isBoostEnabled
                    }
                    llBoostSettingsContainer.visibility = if (isBoostEnabled) android.view.View.VISIBLE else android.view.View.GONE

                    val useKm = sp.getBoolean("USE_KM_DISTANCE_FORMAT", true)
                    if (cbDistanceFormatKm.isChecked != useKm) cbDistanceFormatKm.isChecked = useKm

                    val reqBg = sp.getBoolean("REQ_BACKGROUND", false)
                    if (cbBackgroundLocation.isChecked != reqBg) cbBackgroundLocation.isChecked = reqBg

                    val offset = sp.getInt("BLOCK_SPEED_OFFSET", 0).toFloat()
                    if (sliderOffset.value != offset) {
                        sliderOffset.value = offset
                        tvOffsetValue.text = "${offset.toInt()} km/h"
                    }

                    val fakeDrop = sp.getInt("BLOCK_SPEED_FAKE_DROP", 10).toFloat()
                    if (sliderFakeDrop.value != fakeDrop) {
                        sliderFakeDrop.value = fakeDrop
                        tvFakeDropValue.text = "${fakeDrop.toInt()}"
                    }

                    val mode = sp.getInt("BLOCK_SPEED_BOOST_MODE", 0)
                    if (mode == 0 && !rbBoostProgressive.isChecked) rbBoostProgressive.isChecked = true
                    if (mode == 1 && !rbBoostFixed.isChecked) rbBoostFixed.isChecked = true
                    
                    val duckingMode = sp.getInt("AUDIO_DUCKING_MODE", 1)
                    val rbAudioDuckingNone = view.findViewById<android.widget.RadioButton>(R.id.rbAudioDuckingNone)
                    val rbAudioDuckingVolume = view.findViewById<android.widget.RadioButton>(R.id.rbAudioDuckingVolume)
                    val rbAudioDuckingPause = view.findViewById<android.widget.RadioButton>(R.id.rbAudioDuckingPause)
                    if (duckingMode == 0 && !rbAudioDuckingNone.isChecked) rbAudioDuckingNone.isChecked = true
                    if (duckingMode == 1 && !rbAudioDuckingVolume.isChecked) rbAudioDuckingVolume.isChecked = true
                    if (duckingMode == 2 && !rbAudioDuckingPause.isChecked) rbAudioDuckingPause.isChecked = true
                    
                    val voiceVol = sp.getFloat("VOICE_VOLUME", 1.0f)
                    val sliderVoiceVolume = view.findViewById<com.google.android.material.slider.Slider>(R.id.sliderVoiceVolume)
                    val tvVoiceVolumeValue = view.findViewById<android.widget.TextView>(R.id.tvVoiceVolumeValue)
                    if (sliderVoiceVolume.value != voiceVol) {
                        sliderVoiceVolume.value = voiceVol
                        tvVoiceVolumeValue.text = "${(voiceVol * 100).toInt()}%"
                    }
                }
            }
        }
    }

    init {
        sharedPref.registerOnSharedPreferenceChangeListener(prefListener)
        setupUI()
        setupObservers()
    }

    fun onDestroy() {
        sharedPref.unregisterOnSharedPreferenceChangeListener(prefListener)
    }

    private fun setupUI() {
        val isLandscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val draggables = listOfNotNull(
            binding.llBottomLeftOverlays
        )

        // llSpeedGroup(파란색 도로제한속도 원)은 사용자가 임의로 이동/확대축소할 수 없도록 고정
        binding.llSpeedGroup.translationX = 0f
        binding.llSpeedGroup.translationY = 0f
        binding.llSpeedGroup.scaleX = 1f
        binding.llSpeedGroup.scaleY = 1f

        // llTopUiGroup(OP 연결 대기 오버레이)은 우측 상단 제일 구석에 항상 고정
        binding.llTopUiGroup?.translationX = 0f
        binding.llTopUiGroup?.translationY = 0f
        binding.llTopUiGroup?.scaleX = 1f
        binding.llTopUiGroup?.scaleY = 1f

        // 티맵 및 카카오 주행 화면에서는 llStatusGroup(GPS 상태)이 하단 바를 가리는 고정 위치이므로 임의 이동/확대축소 방지
        binding.llStatusGroup?.translationX = 0f
        binding.llStatusGroup?.translationY = 0f
        binding.llStatusGroup?.scaleX = 1f
        binding.llStatusGroup?.scaleY = 1f

        // 원터치 목적지 버튼 그룹 (집, 회사, 즐겨찾기) - 티맵 안전운행 화면에서만 표시
        binding.llQuickDestGroup?.translationX = 0f
        binding.llQuickDestGroup?.translationY = 0f
        binding.llQuickDestGroup?.scaleX = 1f
        binding.llQuickDestGroup?.scaleY = 1f
        binding.llQuickDestGroup?.visibility = if (isOverlayVisible && activity is MapActivity) View.VISIBLE else View.GONE

        draggables.forEach { view ->
            view.post {
                val others = draggables.filter { it != view }
                val viewIdName = activity.resources.getResourceEntryName(view.id)
                restorePosition(view, viewIdName, isLandscape, others)
            }
        }

        draggables.forEach { view ->
            val others = draggables.filter { it != view }
            val viewIdName = activity.resources.getResourceEntryName(view.id)
            makeDraggable(view, viewIdName, isLandscape, others)
        }

        isOverlayVisible = true
        val isDebugOverlayVisible = sharedPref.getBoolean("DEBUG_OVERLAY_VISIBLE", false)
        updateOverlayVisibility()

        binding.btnMediaOverlay?.setOnClickListener {
            val isCurrentlyVisible = binding.cvMediaOverlayCard.visibility == View.VISIBLE
            setMediaOverlayVisible(!isCurrentlyVisible, savePref = true)
        }

        binding.cvMediaOverlayCard.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                stopMediaProgressTicker()
            }
        })

        binding.btnMediaOverlay?.setOnLongClickListener {
            val isLandscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val keyPrefix = if (isLandscape) "cvMediaOverlayCard_land" else "cvMediaOverlayCard_port"
            sharedPref.edit()
                .remove("${keyPrefix}_x")
                .remove("${keyPrefix}_y")
                .remove("${keyPrefix}_saved")
                .apply()
            val card = binding.cvMediaOverlayCard
            card.animate().translationX(0f).translationY(0f).setDuration(200).start()
            Toast.makeText(activity, "미디어 오버레이 위치가 기본값으로 초기화되었습니다.", Toast.LENGTH_SHORT).show()
            true
        }

        setupMediaOverlayDrag()
        restoreMediaOverlayPosition(isLandscape)

        val initialShape = sharedPref.getString("MEDIA_OVERLAY_SHAPE", "horizontal") ?: "horizontal"
        applyMediaOverlayShape(initialShape)

        val isMediaOverlaySaved = sharedPref.getBoolean("MEDIA_OVERLAY_VISIBLE", false)
        if (isOverlayVisible && isMediaOverlaySaved) {
            setMediaOverlayVisible(true, savePref = false)
        }

        binding.btnSettings?.setOnClickListener {
            val dialogView = android.view.LayoutInflater.from(activity).inflate(R.layout.dialog_drive_settings, null)
            val dialog = android.app.Dialog(activity, R.style.Theme_CarrotNavi_FullScreenDialog)
            dialog.setContentView(dialogView)
            dialog.window?.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            activeDialogView = dialogView
            dialog.setOnDismissListener {
                activeDialogView = null
            }

            val tabSettings = dialogView.findViewById<com.google.android.material.tabs.TabLayout>(R.id.tabSettings)
            val llTabDriving = dialogView.findViewById<android.view.View>(R.id.llTabDriving)
            val llTabMedia = dialogView.findViewById<android.view.View>(R.id.llTabMedia)
            val llTabSystem = dialogView.findViewById<android.view.View>(R.id.llTabSystem)

            fun switchTab(position: Int) {
                llTabDriving?.visibility = if (position == 0) android.view.View.VISIBLE else android.view.View.GONE
                llTabMedia?.visibility = if (position == 1) android.view.View.VISIBLE else android.view.View.GONE
                llTabSystem?.visibility = if (position == 2) android.view.View.VISIBLE else android.view.View.GONE
            }

            tabSettings?.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                    switchTab(tab?.position ?: 0)
                }
                override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
                override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            })

            val sp = activity.getSharedPreferences("CarrotNaviPrefs", android.content.Context.MODE_PRIVATE)

            val cbDistanceFormatKm = dialogView.findViewById<android.widget.Switch>(R.id.cbDistanceFormatKm)
            val cbBackgroundLocation = dialogView.findViewById<android.widget.Switch>(R.id.cbBackgroundLocation)
            val rgAudioDuckingMode = dialogView.findViewById<android.widget.RadioGroup>(R.id.rgAudioDuckingMode)
            val rbAudioDuckingNone = dialogView.findViewById<android.widget.RadioButton>(R.id.rbAudioDuckingNone)
            val rbAudioDuckingVolume = dialogView.findViewById<android.widget.RadioButton>(R.id.rbAudioDuckingVolume)
            val rbAudioDuckingPause = dialogView.findViewById<android.widget.RadioButton>(R.id.rbAudioDuckingPause)
            val sliderVoiceVolume = dialogView.findViewById<com.google.android.material.slider.Slider>(R.id.sliderVoiceVolume)
            val tvVoiceVolumeValue = dialogView.findViewById<android.widget.TextView>(R.id.tvVoiceVolumeValue)
            val swBoostEnable = dialogView.findViewById<android.widget.Switch>(R.id.swBoostEnable)
            val llBoostSettingsContainer = dialogView.findViewById<android.widget.LinearLayout>(R.id.llBoostSettingsContainer)
            val sliderOffset = dialogView.findViewById<com.google.android.material.slider.Slider>(R.id.sliderOffset)
            val tvOffsetValue = dialogView.findViewById<android.widget.TextView>(R.id.tvOffsetValue)
            val rbBoostProgressive = dialogView.findViewById<android.widget.RadioButton>(R.id.rbBoostProgressive)
            val rbBoostFixed = dialogView.findViewById<android.widget.RadioButton>(R.id.rbBoostFixed)
            val sliderFakeDrop = dialogView.findViewById<com.google.android.material.slider.Slider>(R.id.sliderFakeDrop)
            val tvFakeDropValue = dialogView.findViewById<android.widget.TextView>(R.id.tvFakeDropValue)
            val tvOpStatus = dialogView.findViewById<android.widget.TextView>(R.id.tvOpStatus)
            val tvAppVersion = dialogView.findViewById<android.widget.TextView>(R.id.tvAppVersion)
            val tvWebServerInfo = dialogView.findViewById<android.widget.TextView>(R.id.tvWebServerInfo)
            val btnExitApp = dialogView.findViewById<android.widget.Button>(R.id.btnExitApp)
            val btnEditApiKey = dialogView.findViewById<android.widget.Button>(R.id.btnEditApiKey)
            val btnDebugPage = dialogView.findViewById<android.widget.Button>(R.id.btnDebugPage)
            
            val rgMediaBgStyle = dialogView.findViewById<android.widget.RadioGroup>(R.id.rgMediaBgStyle)
            val rbBgAlbumArt = dialogView.findViewById<android.widget.RadioButton>(R.id.rbBgAlbumArt)
            val rbBgEq = dialogView.findViewById<android.widget.RadioButton>(R.id.rbBgEq)
            val rbBgEqWave = dialogView.findViewById<android.widget.RadioButton>(R.id.rbBgEqWave)
            val rbBgEqCircle = dialogView.findViewById<android.widget.RadioButton>(R.id.rbBgEqCircle)
            val cbShowAlbumArtWithEq = dialogView.findViewById<android.widget.CheckBox>(R.id.cbShowAlbumArtWithEq)
            
            val swMediaOverlayEnable = dialogView.findViewById<android.widget.Switch>(R.id.swMediaOverlayEnable)
            swMediaOverlayEnable?.isChecked = isMediaOverlayActive
            swMediaOverlayEnable?.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked != isMediaOverlayActive) {
                    setMediaOverlayVisible(isChecked, savePref = true)
                }
            }

            val cardMediaSplitRatio = dialogView.findViewById<android.view.View>(R.id.cardMediaSplitRatio)
            cardMediaSplitRatio?.visibility = if (!isMediaOverlayActive) android.view.View.VISIBLE else android.view.View.GONE

            val cardSplitContentType = dialogView.findViewById<android.view.View>(R.id.cardSplitContentType)
            cardSplitContentType?.visibility = if (!isMediaOverlayActive) android.view.View.VISIBLE else android.view.View.GONE

            val rgSplitContentType = dialogView.findViewById<android.widget.RadioGroup>(R.id.rgSplitContentType)
            val rbSplitContentMedia = dialogView.findViewById<android.widget.RadioButton>(R.id.rbSplitContentMedia)
            val rbSplitContentOpenpilot = dialogView.findViewById<android.widget.RadioButton>(R.id.rbSplitContentOpenpilot)

            val initialSplitType = sp.getString("SPLIT_CONTENT_TYPE", "media")
            if (initialSplitType == "openpilot") {
                rbSplitContentOpenpilot?.isChecked = true
            } else {
                rbSplitContentMedia?.isChecked = true
            }

            rgSplitContentType?.setOnCheckedChangeListener { _, checkedId ->
                val newType = if (checkedId == R.id.rbSplitContentOpenpilot) "openpilot" else "media"
                sp.edit().putString("SPLIT_CONTENT_TYPE", newType).apply()
                onSplitContentTypeChanged?.invoke(newType)
            }

            val sliderMediaRatio = dialogView.findViewById<com.google.android.material.slider.Slider>(R.id.sliderMediaRatio)
            val tvMediaRatioValue = dialogView.findViewById<android.widget.TextView>(R.id.tvMediaRatioValue)

            val btnCloseSettings = dialogView.findViewById<android.widget.ImageView>(R.id.btnCloseSettings)
            val btnCheckUpdate = dialogView.findViewById<android.widget.Button>(R.id.btnCheckUpdate)
            val btnCheckUpdateServer = dialogView.findViewById<android.widget.Button>(R.id.btnCheckUpdateServer)
            
            cbDistanceFormatKm.isChecked = sp.getBoolean("USE_KM_DISTANCE_FORMAT", true)
            
            val currentStyle = sp.getString("MEDIA_BG_STYLE", "album")
            when (currentStyle) {
                "eq", "eq_bar" -> rbBgEq.isChecked = true
                "eq_wave" -> rbBgEqWave.isChecked = true
                "eq_circle" -> rbBgEqCircle.isChecked = true
                else -> rbBgAlbumArt.isChecked = true
            }
            cbShowAlbumArtWithEq.isChecked = sp.getBoolean("SHOW_ALBUM_ART_WITH_EQ", false)
            
            val isPortrait = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
            val ratioKey = if (isPortrait) "MEDIA_SPLIT_RATIO_PORTRAIT_F" else "MEDIA_SPLIT_RATIO_LANDSCAPE_F"
            
            val currentRatio = if (sp.contains(ratioKey)) {
                sp.getFloat(ratioKey, 3.5f)
            } else {
                sp.getFloat("MEDIA_SPLIT_RATIO_F", 3.5f)
            }
            sliderMediaRatio.value = currentRatio
            fun fmt(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
            tvMediaRatioValue.text = "${fmt(currentRatio)} : ${fmt(5f - currentRatio)}"
            
            cbBackgroundLocation.isChecked = sp.getBoolean("REQ_BACKGROUND", false)

            // 0: 사용 안함, 1: 오디오 포커스(볼륨 깎기), 2: 미디어 일시정지
            val duckingMode = sp.getInt("AUDIO_DUCKING_MODE", 1)
            when (duckingMode) {
                0 -> rbAudioDuckingNone?.isChecked = true
                2 -> rbAudioDuckingPause?.isChecked = true
                else -> rbAudioDuckingVolume?.isChecked = true
            }
            
            rgAudioDuckingMode?.setOnCheckedChangeListener { _, checkedId ->
                val mode = when (checkedId) {
                    R.id.rbAudioDuckingNone -> 0
                    R.id.rbAudioDuckingPause -> 2
                    else -> 1
                }
                sp.edit().putInt("AUDIO_DUCKING_MODE", mode).apply()
            }

            val isBoostEnabled = sp.getBoolean("BLOCK_SPEED_ENABLED", false)
            swBoostEnable.isChecked = isBoostEnabled
            llBoostSettingsContainer.visibility = if (isBoostEnabled) android.view.View.VISIBLE else android.view.View.GONE

            swBoostEnable.setOnCheckedChangeListener { _, isChecked ->
                sp.edit().putBoolean("BLOCK_SPEED_ENABLED", isChecked).apply()
                llBoostSettingsContainer.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
            }


            cbBackgroundLocation.setOnCheckedChangeListener { _, isChecked ->
                sp.edit().putBoolean("REQ_BACKGROUND", isChecked).apply()
            }

            val offset = sp.getInt("BLOCK_SPEED_OFFSET", 0)
            sliderOffset.value = offset.toFloat()
            tvOffsetValue.text = "${offset} km/h"
            sliderOffset.addOnChangeListener { _, value, _ ->
                tvOffsetValue.text = "${value.toInt()} km/h"
                sp.edit().putInt("BLOCK_SPEED_OFFSET", value.toInt()).apply()
            }
            
            val voiceVol = sp.getFloat("VOICE_VOLUME", 1.0f)
            sliderVoiceVolume.value = voiceVol
            tvVoiceVolumeValue.text = "${(voiceVol * 100).toInt()}%"
            sliderVoiceVolume.addOnChangeListener { _, value, _ ->
                tvVoiceVolumeValue.text = "${(value * 100).toInt()}%"
                sp.edit().putFloat("VOICE_VOLUME", value).apply()
            }


            val mode = sp.getInt("BLOCK_SPEED_BOOST_MODE", 0)
            if (mode == 0) rbBoostProgressive.isChecked = true else rbBoostFixed.isChecked = true
            rbBoostProgressive.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) sp.edit().putInt("BLOCK_SPEED_BOOST_MODE", 0).apply()
            }
            rbBoostFixed.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) sp.edit().putInt("BLOCK_SPEED_BOOST_MODE", 1).apply()
            }

            val fakeDrop = sp.getInt("BLOCK_SPEED_FAKE_DROP", 10)
            sliderFakeDrop.value = fakeDrop.toFloat()
            tvFakeDropValue.text = "${fakeDrop}"
            sliderFakeDrop.addOnChangeListener { _, value, _ ->
                tvFakeDropValue.text = "${value.toInt()}"
                sp.edit().putInt("BLOCK_SPEED_FAKE_DROP", value.toInt()).apply()
            }

            // 하단 바 높이 조절 슬라이더 바인딩 (세로 / 가로 모드 통합)
            val sliderPortraitBottomBarHeight = dialogView.findViewById<com.google.android.material.slider.Slider>(R.id.sliderPortraitBottomBarHeight)
            val tvPortraitBottomBarHeightValue = dialogView.findViewById<android.widget.TextView>(R.id.tvPortraitBottomBarHeightValue)
            val sliderLandscapeBottomBarHeight = dialogView.findViewById<com.google.android.material.slider.Slider>(R.id.sliderLandscapeBottomBarHeight)
            val tvLandscapeBottomBarHeightValue = dialogView.findViewById<android.widget.TextView>(R.id.tvLandscapeBottomBarHeightValue)

            fun formatBottomBarOffset(v: Int): String = if (v > 0) "+$v dp" else "$v dp"

            val portraitOffset = sp.getInt("BOTTOM_BAR_PORTRAIT_HEIGHT_OFFSET", sp.getInt("TMAP_PORTRAIT_BOTTOM_BAR_HEIGHT_OFFSET", 0))
            sliderPortraitBottomBarHeight?.value = portraitOffset.toFloat().coerceIn(-20f, 60f)
            tvPortraitBottomBarHeightValue?.text = formatBottomBarOffset(portraitOffset)
            sliderPortraitBottomBarHeight?.addOnChangeListener { _, value, _ ->
                val v = value.toInt()
                tvPortraitBottomBarHeightValue?.text = formatBottomBarOffset(v)
                sp.edit()
                    .putInt("BOTTOM_BAR_PORTRAIT_HEIGHT_OFFSET", v)
                    .putInt("TMAP_PORTRAIT_BOTTOM_BAR_HEIGHT_OFFSET", v)
                    .putInt("KAKAO_PORTRAIT_BOTTOM_BAR_HEIGHT_OFFSET", 0)
                    .apply()
                val isLandscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                if (!isLandscape) {
                    (activity as? MapActivity)?.alignGpsOverlayWithEndButton()
                    (activity as? KakaoMapActivity)?.alignGpsOverlayWithBottomBar()
                }
            }

            val landscapeOffset = sp.getInt("BOTTOM_BAR_LANDSCAPE_HEIGHT_OFFSET", sp.getInt("TMAP_LANDSCAPE_BOTTOM_BAR_HEIGHT_OFFSET", 0))
            sliderLandscapeBottomBarHeight?.value = landscapeOffset.toFloat().coerceIn(-20f, 60f)
            tvLandscapeBottomBarHeightValue?.text = formatBottomBarOffset(landscapeOffset)
            sliderLandscapeBottomBarHeight?.addOnChangeListener { _, value, _ ->
                val v = value.toInt()
                tvLandscapeBottomBarHeightValue?.text = formatBottomBarOffset(v)
                sp.edit()
                    .putInt("BOTTOM_BAR_LANDSCAPE_HEIGHT_OFFSET", v)
                    .putInt("TMAP_LANDSCAPE_BOTTOM_BAR_HEIGHT_OFFSET", v)
                    .putInt("KAKAO_LANDSCAPE_BOTTOM_BAR_HEIGHT_OFFSET", 0)
                    .apply()
                val isLandscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                if (isLandscape) {
                    (activity as? MapActivity)?.alignGpsOverlayWithEndButton()
                    (activity as? KakaoMapActivity)?.alignGpsOverlayWithBottomBar()
                }
            }

            val btnMediaPermission = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMediaPermission)

            btnMediaPermission?.setOnClickListener {
                dialog.dismiss()
                try {
                    val intent = android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(activity, "설정 화면을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
            }


            
            try {
                val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
                val deviceId = sp.getString("DEVICE_ID", "알 수 없음")
                tvAppVersion.text = "버전 ${pInfo.versionName} / 기기ID: $deviceId"
                val toolbar = dialogView.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbarSettings)
                val titleStr = "상세 설정  v${pInfo.versionName} / 기기ID: $deviceId"
                val spannable = android.text.SpannableString(titleStr)
                spannable.setSpan(android.text.style.RelativeSizeSpan(0.7f), 6, titleStr.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#AAAAAA")), 6, titleStr.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                toolbar?.title = spannable
                toolbar?.subtitle = null
            } catch (e: Exception) {}
            
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                var ipAddress: String? = null
                while (interfaces.hasMoreElements() && ipAddress == null) {
                    val networkInterface = interfaces.nextElement()
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                            ipAddress = address.hostAddress
                            break
                        }
                    }
                }
                
                val serverUrl = sp.getString("IP_REPORT_SERVER_URL", "")?.trim()
                val deviceId = sp.getString("DEVICE_ID", "carrot")?.trim()

                if (ipAddress != null) {
                    var infoText = "내부 IP: http://$ipAddress:8080/\nmDNS: http://carrotnavi.local:8080/"
                    if (!serverUrl.isNullOrEmpty()) {
                        val connectUrl = if (serverUrl.endsWith("/")) "${serverUrl}connect/$deviceId" else "$serverUrl/connect/$deviceId"
                        infoText += "\n원격 접속: $connectUrl"
                    }
                    tvWebServerInfo?.text = infoText
                    tvWebServerInfo?.visibility = android.view.View.VISIBLE
                } else {
                    tvWebServerInfo?.text = "원격 설정: Wi-Fi 연결 확인 필요"
                }
            } catch (e: Exception) {}
            
            btnCheckUpdate?.setOnClickListener {
                AutoUpdater.checkForUpdates(activity, isManual = true, useServer = false)
            }
            btnCheckUpdateServer?.setOnClickListener {
                AutoUpdater.checkForUpdates(activity, isManual = true, useServer = true)
            }
            
            btnDebugPage.visibility = android.view.View.GONE
            
            btnExitApp.setOnClickListener {
                dialog.dismiss()
                activity.stopService(android.content.Intent(activity, UdpSenderService::class.java))
                activity.stopService(android.content.Intent(activity, WebServerService::class.java))
                activity.finishAffinity()
                System.exit(0)
            }

            btnCloseSettings.setOnClickListener {
                dialog.dismiss()
            }

            cbDistanceFormatKm.setOnCheckedChangeListener { _, isChecked ->
                sp.edit().putBoolean("USE_KM_DISTANCE_FORMAT", isChecked).apply()
            }

            rgMediaBgStyle.setOnCheckedChangeListener { _, checkedId ->
                val style = when (checkedId) {
                    R.id.rbBgEq -> "eq_bar"
                    R.id.rbBgEqWave -> "eq_wave"
                    R.id.rbBgEqCircle -> "eq_circle"
                    else -> "album"
                }
                sp.edit().putString("MEDIA_BG_STYLE", style).apply()
            }

            cbShowAlbumArtWithEq.setOnCheckedChangeListener { _, isChecked ->
                sp.edit().putBoolean("SHOW_ALBUM_ART_WITH_EQ", isChecked).apply()
            }

            sliderMediaRatio.addOnChangeListener { _, value, _ ->
                fun fmt(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
                tvMediaRatioValue.text = "${fmt(value)} : ${fmt(5f - value)}"
                
                val isPortrait = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
                val ratioKey = if (isPortrait) "MEDIA_SPLIT_RATIO_PORTRAIT_F" else "MEDIA_SPLIT_RATIO_LANDSCAPE_F"
                
                sp.edit()
                    .putFloat(ratioKey, value)
                    .putFloat("MEDIA_SPLIT_RATIO_F", value) // fallback for compatibility
                    .apply()
            }

            btnEditApiKey.setOnClickListener {
                dialog.dismiss()
                val intent = android.content.Intent(activity, MainActivity::class.java)
                intent.putExtra("auto_start", false)
                activity.startActivity(intent)
                activity.finish()
            }
            
            com.example.carrotnavi.OpenpilotStateRepository.state.observe(activity as androidx.lifecycle.LifecycleOwner, androidx.lifecycle.Observer { state ->
                if (state == null) {
                    tvOpStatus.text = "대기중"
                    tvOpStatus.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                } else {
                    tvOpStatus.text = "연결됨"
                    tvOpStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                }
            })
            
            dialog.show()
        }

        // 퀵 목적지 버튼 (집, 사무실, 즐겨찾기)
        binding.btnQuickHome.setOnClickListener {
            Log.d("HudOverlayManager", "btnQuickHome clicked")
            val home = DestinationBookmarkManager.getHome(activity)
            if (home != null) {
                onQuickDestinationSelected?.invoke(home.toKakaoDocument())
            } else {
                AlertDialog.Builder(activity)
                    .setTitle("🏠 집 주소 등록")
                    .setMessage("등록된 집 주소가 없습니다.\n검색 화면으로 이동하여 집 주소를 등록하시겠습니까?")
                    .setPositiveButton("검색하기") { _, _ ->
                        val intent = Intent(activity, SearchActivity::class.java).apply {
                            putExtra("register_target", BookmarkItem.TYPE_HOME)
                        }
                        activity.startActivity(intent)
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
        }
        binding.btnQuickHome.setOnLongClickListener {
            val home = DestinationBookmarkManager.getHome(activity)
            if (home != null) {
                showQuickDestManageDialog(BookmarkItem.TYPE_HOME, home) {}
                true
            } else {
                false
            }
        }

        binding.btnQuickOffice.setOnClickListener {
            Log.d("HudOverlayManager", "btnQuickOffice clicked")
            val office = DestinationBookmarkManager.getOffice(activity)
            if (office != null) {
                onQuickDestinationSelected?.invoke(office.toKakaoDocument())
            } else {
                AlertDialog.Builder(activity)
                    .setTitle("🏢 사무실 주소 등록")
                    .setMessage("등록된 사무실 주소가 없습니다.\n검색 화면으로 이동하여 사무실 주소를 등록하시겠습니까?")
                    .setPositiveButton("검색하기") { _, _ ->
                        val intent = Intent(activity, SearchActivity::class.java).apply {
                            putExtra("register_target", BookmarkItem.TYPE_OFFICE)
                        }
                        activity.startActivity(intent)
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
        }
        binding.btnQuickOffice.setOnLongClickListener {
            val office = DestinationBookmarkManager.getOffice(activity)
            if (office != null) {
                showQuickDestManageDialog(BookmarkItem.TYPE_OFFICE, office) {}
                true
            } else {
                false
            }
        }

        binding.btnQuickFavorites.setOnClickListener {
            Log.d("HudOverlayManager", "btnQuickFavorites clicked")
            showFavoritesDialog()
        }

        // 집, 사무실 버튼 폭을 즐겨찾기 버튼 폭과 동일하게 동기화
        binding.btnQuickFavorites.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val favWidth = right - left
            if (favWidth > 0) {
                var changed = false
                if (binding.btnQuickHome.layoutParams.width != favWidth) {
                    binding.btnQuickHome.layoutParams.width = favWidth
                    changed = true
                }
                if (binding.btnQuickOffice.layoutParams.width != favWidth) {
                    binding.btnQuickOffice.layoutParams.width = favWidth
                    changed = true
                }
                if (changed) {
                    binding.btnQuickHome.requestLayout()
                    binding.btnQuickOffice.requestLayout()
                    binding.llQuickDestToolbar.requestLayout()
                }
            }
        }
        binding.btnQuickFavorites.post { syncQuickDestButtonWidths() }
    }

    private fun showQuickDestManageDialog(targetType: String, item: BookmarkItem, onUpdated: () -> Unit) {
        val title = if (targetType == BookmarkItem.TYPE_HOME) "🏠 집 설정" else "🏢 사무실 설정"
        val address = if (item.road_address_name.isNotEmpty()) item.road_address_name else item.address_name
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage("현재 등록: ${item.place_name}\n($address)")
            .setPositiveButton("안내 시작") { _, _ ->
                onQuickDestinationSelected?.invoke(item.toKakaoDocument())
            }
            .setNeutralButton("변경(검색)") { _, _ ->
                val intent = Intent(activity, SearchActivity::class.java).apply {
                    putExtra("register_target", targetType)
                }
                activity.startActivity(intent)
            }
            .setNegativeButton("삭제") { _, _ ->
                if (targetType == BookmarkItem.TYPE_HOME) {
                    DestinationBookmarkManager.clearHome(activity)
                    Toast.makeText(activity, "집 주소가 삭제되었습니다.", Toast.LENGTH_SHORT).show()
                } else {
                    DestinationBookmarkManager.clearOffice(activity)
                    Toast.makeText(activity, "사무실 주소가 삭제되었습니다.", Toast.LENGTH_SHORT).show()
                }
                onUpdated()
            }
            .show()
    }

    private fun showFavoritesDialog() {
        val dialog = Dialog(activity, R.style.Theme_CarrotNavi_FullScreenDialog)
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_favorites_list, null)
        dialog.setContentView(dialogView)

        dialog.window?.let { window ->
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }

        val toolbar = dialogView.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.tbFavorites)
        val btnHeaderAdd = dialogView.findViewById<View>(R.id.btnHeaderAddFavorite)

        val cvQuickHome = dialogView.findViewById<View>(R.id.cvQuickHome)
        val tvHomeTitle = dialogView.findViewById<TextView>(R.id.tvQuickHomeTitle)
        val tvHomeBadge = dialogView.findViewById<TextView>(R.id.tvQuickHomeBadge)
        val tvHomeSub = dialogView.findViewById<TextView>(R.id.tvQuickHomeSub)

        val cvQuickOffice = dialogView.findViewById<View>(R.id.cvQuickOffice)
        val tvOfficeTitle = dialogView.findViewById<TextView>(R.id.tvQuickOfficeTitle)
        val tvOfficeBadge = dialogView.findViewById<TextView>(R.id.tvQuickOfficeBadge)
        val tvOfficeSub = dialogView.findViewById<TextView>(R.id.tvQuickOfficeSub)

        val tvFavoritesCount = dialogView.findViewById<TextView>(R.id.tvFavoritesCount)
        val llEmpty = dialogView.findViewById<View>(R.id.llEmptyFavorites)
        val btnAddEmpty = dialogView.findViewById<View>(R.id.btnAddFavoriteEmpty)
        val rvFavorites = dialogView.findViewById<RecyclerView>(R.id.rvFavorites)

        toolbar.setNavigationOnClickListener {
            dialog.dismiss()
        }

        val openSearchForFavorite = {
            dialog.dismiss()
            val intent = Intent(activity, SearchActivity::class.java).apply {
                putExtra("register_target", BookmarkItem.TYPE_FAVORITE)
            }
            activity.startActivity(intent)
        }
        btnHeaderAdd.setOnClickListener { openSearchForFavorite() }
        btnAddEmpty.setOnClickListener { openSearchForFavorite() }

        fun updateHomeCard() {
            val home = DestinationBookmarkManager.getHome(activity)
            if (home != null) {
                tvHomeSub.text = home.place_name
                tvHomeSub.setTextColor(activity.getColor(R.color.text_primary))
                tvHomeBadge.text = "안내"
                tvHomeBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.accent_green))
                val startHome = {
                    dialog.dismiss()
                    onQuickDestinationSelected?.invoke(home.toKakaoDocument())
                }
                cvQuickHome.setOnClickListener { startHome() }
                cvQuickHome.setOnLongClickListener {
                    showQuickDestManageDialog(BookmarkItem.TYPE_HOME, home) {
                        updateHomeCard()
                    }
                    true
                }
            } else {
                tvHomeSub.text = "미등록 (터치하여 등록)"
                tvHomeSub.setTextColor(activity.getColor(R.color.text_muted))
                tvHomeBadge.text = "등록"
                tvHomeBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.accent_blue))
                val registerHome = {
                    dialog.dismiss()
                    val intent = Intent(activity, SearchActivity::class.java).apply {
                        putExtra("register_target", BookmarkItem.TYPE_HOME)
                    }
                    activity.startActivity(intent)
                }
                cvQuickHome.setOnClickListener { registerHome() }
                cvQuickHome.setOnLongClickListener(null)
            }
        }

        fun updateOfficeCard() {
            val office = DestinationBookmarkManager.getOffice(activity)
            if (office != null) {
                tvOfficeSub.text = office.place_name
                tvOfficeSub.setTextColor(activity.getColor(R.color.text_primary))
                tvOfficeBadge.text = "안내"
                tvOfficeBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.accent_green))
                val startOffice = {
                    dialog.dismiss()
                    onQuickDestinationSelected?.invoke(office.toKakaoDocument())
                }
                cvQuickOffice.setOnClickListener { startOffice() }
                cvQuickOffice.setOnLongClickListener {
                    showQuickDestManageDialog(BookmarkItem.TYPE_OFFICE, office) {
                        updateOfficeCard()
                    }
                    true
                }
            } else {
                tvOfficeSub.text = "미등록 (터치하여 등록)"
                tvOfficeSub.setTextColor(activity.getColor(R.color.text_muted))
                tvOfficeBadge.text = "등록"
                tvOfficeBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.accent_blue))
                val registerOffice = {
                    dialog.dismiss()
                    val intent = Intent(activity, SearchActivity::class.java).apply {
                        putExtra("register_target", BookmarkItem.TYPE_OFFICE)
                    }
                    activity.startActivity(intent)
                }
                cvQuickOffice.setOnClickListener { registerOffice() }
                cvQuickOffice.setOnLongClickListener(null)
            }
        }

        updateHomeCard()
        updateOfficeCard()

        var adapter: FavoritesAdapter? = null

        fun refreshFavoritesList() {
            val favorites = DestinationBookmarkManager.getFavorites(activity)
            tvFavoritesCount.text = "${favorites.size}개"
            if (favorites.isEmpty()) {
                llEmpty.visibility = View.VISIBLE
                rvFavorites.visibility = View.GONE
            } else {
                llEmpty.visibility = View.GONE
                rvFavorites.visibility = View.VISIBLE
                adapter?.submitList(favorites)
            }
        }

        adapter = FavoritesAdapter(
            onItemClick = { item ->
                dialog.dismiss()
                onQuickDestinationSelected?.invoke(item.toKakaoDocument())
            },
            onDeleteClick = { item ->
                AlertDialog.Builder(activity)
                    .setTitle("즐겨찾기 삭제")
                    .setMessage("'${item.place_name}'을(를) 즐겨찾기에서 삭제하시겠습니까?")
                    .setPositiveButton("삭제") { _, _ ->
                        DestinationBookmarkManager.removeFavorite(activity, item)
                        refreshFavoritesList()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
        )

        val isLandscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        rvFavorites.layoutManager = if (isLandscape) {
            GridLayoutManager(activity, 2)
        } else {
            LinearLayoutManager(activity)
        }
        rvFavorites.adapter = adapter
        refreshFavoritesList()

        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun setAutoRepeatButton(button: Button, action: () -> Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var runnable: Runnable? = null

        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (isEditMode) return@setOnTouchListener false
                    action()
                    runnable = object : Runnable {
                        override fun run() {
                            action()
                            handler.postDelayed(this, 100)
                        }
                    }
                    handler.postDelayed(runnable!!, 500)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    runnable?.let { handler.removeCallbacks(it) }
                    true
                }
                else -> false
            }
        }
    }

    fun updateOverlayVisibility() {
        val visibility = if (isOverlayVisible) View.VISIBLE else View.GONE
        binding.llSpeedGroup.visibility = visibility
        binding.llBottomLeftOverlays.visibility = visibility
        binding.llTopUiGroup?.visibility = visibility
        // 티맵 주행화면의 GPS 오버레이 내부에서만 표시, 카카오 경로안내 중에는 숨김
        binding.llQuickDestGroup?.visibility = if (isOverlayVisible && activity is MapActivity) View.VISIBLE else View.GONE
        if (isOverlayVisible && activity is MapActivity) {
            binding.btnQuickFavorites.post { syncQuickDestButtonWidths() }
        }
        
        // 티맵 및 카카오 주행 화면에서는 하단 바를 가리기 위해 항상 표시
        binding.llStatusGroup?.visibility = View.VISIBLE
        
        binding.btnSettings?.alpha = 1.0f
        binding.btnMediaOverlay?.alpha = if (isOverlayVisible) 1.0f else 0.5f
        binding.btnSearchAddress.alpha = if (isOverlayVisible) 1.0f else 0.5f
        val isMediaOverlaySaved = sharedPref.getBoolean("MEDIA_OVERLAY_VISIBLE", false)
        if (isOverlayVisible && isMediaOverlaySaved) {
            setMediaOverlayVisible(true, savePref = false)
        } else {
            if (binding.cvMediaOverlayCard.visibility == View.VISIBLE) {
                binding.cvMediaOverlayCard.visibility = View.GONE
                stopMediaProgressTicker()
                notifyMediaOverlayVisibility(false)
            }
        }
        onOverlayVisibilityChanged?.invoke()
    }

    private fun syncQuickDestButtonWidths() {
        val favWidth = binding.btnQuickFavorites.width
        if (favWidth > 0) {
            var changed = false
            if (binding.btnQuickHome.layoutParams.width != favWidth) {
                binding.btnQuickHome.layoutParams.width = favWidth
                changed = true
            }
            if (binding.btnQuickOffice.layoutParams.width != favWidth) {
                binding.btnQuickOffice.layoutParams.width = favWidth
                changed = true
            }
            if (changed) {
                binding.btnQuickHome.requestLayout()
                binding.btnQuickOffice.requestLayout()
                binding.llQuickDestToolbar.requestLayout()
            }
        }
    }

    fun applyBottomBarOrientation(isLandscape: Boolean, hasAddr: Boolean) {
        val density = activity.resources.displayMetrics.density
        val gpsInfo = binding.llGpsInfo ?: return
        val topRow = binding.llGpsTopRow ?: return
        val bottomRow = binding.llGpsBottomRow ?: return
        val divider = binding.vGpsDivider ?: return
        val addressTv = binding.tvGpsAddress ?: return
        val cancelBtn = binding.btnGpsCancelRoute

        if (isLandscape) {
            // [가로 모드] 1줄 배치 (GPS 상태 | 집,사무실,즐겨찾기 | 구분선 | 주소 | 경로취소)
            gpsInfo.orientation = LinearLayout.HORIZONTAL
            gpsInfo.gravity = android.view.Gravity.CENTER_VERTICAL
            val padH = (12 * density).toInt()
            val padV = (2 * density).toInt()
            gpsInfo.setPadding(padH, padV, padH, padV)

            val topParams = topRow.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            topParams.width = LinearLayout.LayoutParams.WRAP_CONTENT
            topParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
            topRow.layoutParams = topParams

            divider.visibility = if (hasAddr) View.VISIBLE else View.GONE

            val bottomParams = bottomRow.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            bottomParams.width = 0
            bottomParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
            bottomParams.weight = 1f
            bottomParams.topMargin = 0
            bottomRow.layoutParams = bottomParams

            val addrParams = addressTv.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(0, (34 * density).toInt(), 1f)
            addrParams.width = 0
            addrParams.weight = 1f
            addressTv.layoutParams = addrParams
            addressTv.gravity = android.view.Gravity.CENTER

            cancelBtn?.let { btn ->
                if (btn.parent != bottomRow) {
                    (btn.parent as? ViewGroup)?.removeView(btn)
                    bottomRow.addView(btn)
                }
                val btnParams = btn.layoutParams as? LinearLayout.LayoutParams
                    ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (34 * density).toInt())
                btnParams.marginStart = (6 * density).toInt()
                btnParams.gravity = android.view.Gravity.CENTER_VERTICAL
                btn.layoutParams = btnParams
            }
        } else {
            // [세로 모드] 2줄 배치 (Row 1: GPS 상태 | 집,사무실,즐겨찾기, Row 2: 현위치 주소)
            gpsInfo.orientation = LinearLayout.VERTICAL
            gpsInfo.gravity = android.view.Gravity.CENTER_VERTICAL
            val padH = (10 * density).toInt()
            val padV = (2 * density).toInt()
            gpsInfo.setPadding(padH, padV, padH, padV)

            val topParams = topRow.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            topParams.width = LinearLayout.LayoutParams.MATCH_PARENT
            topParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
            topRow.layoutParams = topParams

            // 세로 모드에서는 줄바꿈되므로 세로 구분선 숨김
            divider.visibility = View.GONE

            val bottomParams = bottomRow.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            bottomParams.width = LinearLayout.LayoutParams.MATCH_PARENT
            bottomParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
            bottomParams.weight = 0f
            bottomParams.topMargin = (4 * density).toInt()
            bottomRow.layoutParams = bottomParams

            val addrParams = addressTv.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (34 * density).toInt())
            addrParams.width = LinearLayout.LayoutParams.MATCH_PARENT
            addrParams.weight = 0f
            addressTv.layoutParams = addrParams
            addressTv.gravity = android.view.Gravity.CENTER

            cancelBtn?.let { btn ->
                if (btn.parent != topRow) {
                    (btn.parent as? ViewGroup)?.removeView(btn)
                    topRow.addView(btn)
                }
                val btnParams = btn.layoutParams as? LinearLayout.LayoutParams
                    ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (34 * density).toInt())
                btnParams.marginStart = (6 * density).toInt()
                btnParams.gravity = android.view.Gravity.CENTER_VERTICAL
                btn.layoutParams = btnParams
            }
            binding.btnQuickFavorites.post { syncQuickDestButtonWidths() }
        }
    }

    fun formatAddressWithPin(context: Context, address: String): CharSequence {
        if (address.isEmpty()) return ""
        val drawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_location_pin_small)?.mutate()
            ?: return address
        val size = (15 * context.resources.displayMetrics.density).toInt()
        drawable.setBounds(0, 0, size, size)
        val align = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            android.text.style.ImageSpan.ALIGN_CENTER
        } else {
            android.text.style.DynamicDrawableSpan.ALIGN_BOTTOM
        }
        val span = android.text.style.ImageSpan(drawable, align)
        val ssb = android.text.SpannableStringBuilder("  $address")
        ssb.setSpan(span, 0, 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return ssb
    }

    private fun updateEditModeForegrounds() {
        if (isEditMode) {
            binding.llBottomLeftOverlays.foreground = HatchedDrawable(binding.llBottomLeftOverlays)
            binding.llTopUiGroup?.let { it.foreground = HatchedDrawable(it) }
            if (activity !is MapActivity) {
                binding.llStatusGroup?.let { it.foreground = HatchedDrawable(it) }
            }
        } else {
            binding.llBottomLeftOverlays.foreground = null
            binding.llTopUiGroup?.foreground = null
            binding.llStatusGroup?.foreground = null
        }
    }

    private fun setupObservers() {
        OpenpilotStateRepository.state.observe(lifecycleOwner, Observer { state ->
            binding.tvCarrotVersion?.text = "Ver: ${state.carrot2}"
            binding.tvCarrotIp?.text = "IP: ${state.ip}"
            
            if (state.ip != "-" && state.ip.isNotEmpty()) {
                binding.vConnectionDot?.setBackgroundResource(R.drawable.shape_circle_green)
                binding.tvConnectionStatus?.text = "OP 연결됨"
                binding.tvConnectionStatus?.setTextColor(Color.parseColor("#4CAF50"))
            } else {
                binding.vConnectionDot?.setBackgroundResource(R.drawable.shape_circle_gray)
                binding.tvConnectionStatus?.text = "OP 연결 대기"
                binding.tvConnectionStatus?.setTextColor(Color.parseColor("#AAAAAA"))
            }


            // they are in the Top Bar which is still in Activity's XML!
            // Wait, I should not update TopBar views here if they are not in binding.
        })
    }

    private fun makeDraggable(view: View, viewIdName: String, isLandscape: Boolean, otherViews: List<View>) {
        view.setOnTouchListener { v, event ->
            if (!isEditMode) return@setOnTouchListener false

            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = view.translationX
                    initialY = view.translationY
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isScaling = false
                    view.bringToFront()
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2) {
                        isScaling = true
                        initialDistance = getDistance(event)
                        initialScale = view.scaleX
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isScaling && event.pointerCount == 2) {
                        val currentDistance = getDistance(event)
                        if (initialDistance > 0) {
                            val scaleFactor = currentDistance / initialDistance
                            val newScale = max(0.5f, initialScale * scaleFactor)
                            view.scaleX = newScale
                            view.scaleY = newScale
                        }
                    } else if (!isScaling) {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY

                        var newTranslationX = initialX + dx
                        var newTranslationY = initialY + dy

                        val parent = view.parent as? ViewGroup
                        if (parent != null) {
                            val scaledWidth = view.width * view.scaleX
                            val scaledHeight = view.height * view.scaleY
                            val widthDiff = (scaledWidth - view.width) / 2
                            val heightDiff = (scaledHeight - view.height) / 2

                            val minX = -view.left.toFloat() + widthDiff
                            val maxX = parent.width - view.right.toFloat() - widthDiff
                            val minY = -view.top.toFloat() + heightDiff
                            val maxY = parent.height - view.bottom.toFloat() - heightDiff

                            newTranslationX = newTranslationX.coerceIn(minX, maxX)
                            newTranslationY = newTranslationY.coerceIn(minY, maxY)

                            // Snapping logic
                            val snapDistance = 40f
                            var snappedX = false
                            var snappedY = false

                            val viewCenterX = view.left + newTranslationX + view.width / 2
                            val viewCenterY = view.top + newTranslationY + view.height / 2

                            for (other in otherViews) {
                                val otherCenterX = other.left + other.translationX + other.width / 2
                                val otherCenterY = other.top + other.translationY + other.height / 2

                                if (Math.abs(viewCenterX - otherCenterX) < snapDistance) {
                                    newTranslationX = otherCenterX - view.width / 2 - view.left
                                    snappedX = true
                                }
                                if (Math.abs(viewCenterY - otherCenterY) < snapDistance) {
                                    newTranslationY = otherCenterY - view.height / 2 - view.top
                                    snappedY = true
                                }
                            }

                            val parentCenterX = parent.width / 2f
                            val parentCenterY = parent.height / 2f
                            if (!snappedX && Math.abs(viewCenterX - parentCenterX) < snapDistance) {
                                newTranslationX = parentCenterX - view.width / 2 - view.left
                            }
                            if (!snappedY && Math.abs(viewCenterY - parentCenterY) < snapDistance) {
                                newTranslationY = parentCenterY - view.height / 2 - view.top
                            }
                        }

                        view.translationX = newTranslationX
                        view.translationY = newTranslationY
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    if (event.pointerCount <= 2) {
                        isScaling = false
                        val keyPrefix = if (isLandscape) "${viewIdName}_land" else "${viewIdName}_port"
                        sharedPref.edit()
                            .putFloat("${keyPrefix}_x", view.translationX)
                            .putFloat("${keyPrefix}_y", view.translationY)
                            .putFloat("${keyPrefix}_scale", view.scaleX)
                            .apply()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun restorePosition(view: View, viewIdName: String, isLandscape: Boolean, otherViews: List<View>) {
        val keyPrefix = if (isLandscape) "${viewIdName}_land" else "${viewIdName}_port"
        val savedX = sharedPref.getFloat("${keyPrefix}_x", 0f)
        val savedY = sharedPref.getFloat("${keyPrefix}_y", 0f)
        val savedScale = sharedPref.getFloat("${keyPrefix}_scale", 1f)

        view.scaleX = savedScale
        view.scaleY = savedScale
        view.translationX = savedX
        view.translationY = savedY

        // Boundaries check
        val parent = view.parent as? ViewGroup
        if (parent != null && parent.width > 0 && parent.height > 0) {
            val scaledWidth = view.width * view.scaleX
            val scaledHeight = view.height * view.scaleY
            val widthDiff = (scaledWidth - view.width) / 2
            val heightDiff = (scaledHeight - view.height) / 2

            val minX = -view.left.toFloat() + widthDiff
            val maxX = parent.width - view.right.toFloat() - widthDiff
            val minY = -view.top.toFloat() + heightDiff
            val maxY = parent.height - view.bottom.toFloat() - heightDiff

            view.translationX = view.translationX.coerceIn(minX, maxX)
            view.translationY = view.translationY.coerceIn(minY, maxY)
        }
    }

    private fun getDistance(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    fun shouldBlockTouch(ev: MotionEvent): Boolean {
        if (isEditMode) {
            val touchedOverlay = findTouchedOverlay(ev)
            if (touchedOverlay != null) {
                return false // Let Android view hierarchy handle it normally to preserve coordinates
            }
            return true // Block map interactions in edit mode
        }
        return false 
    }

    private fun findTouchedOverlay(ev: MotionEvent): View? {
        val x = ev.rawX.toInt()
        val y = ev.rawY.toInt()
        val overlays = listOfNotNull(
            binding.llSpeedGroup,
            binding.llBottomLeftOverlays,
            binding.llTopUiGroup,

            binding.llStatusGroup,
            binding.llRightBottomGrid
        )

        val location = IntArray(2)
        for (overlay in overlays) {
            if (overlay.visibility == View.VISIBLE) {
                overlay.getLocationOnScreen(location)
                val left = location[0]
                val top = location[1]
                val right = left + (overlay.width * overlay.scaleX).toInt()
                val bottom = top + (overlay.height * overlay.scaleY).toInt()

                if (x in left..right && y in top..bottom) {
                    return overlay
                }
            }
        }
        return null
    }

    private class HatchedDrawable(private val targetView: View, baseColor: Int = Color.parseColor("#33FFC107")) : android.graphics.drawable.Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#80FFC107")
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        private val bgPaint = Paint().apply {
            color = baseColor
            style = Paint.Style.FILL
        }

        override fun draw(canvas: android.graphics.Canvas) {
            val bounds = bounds
            canvas.drawRect(bounds, bgPaint)
            val size = max(bounds.width(), bounds.height()) * 2
            val spacing = 20
            for (i in -size..size step spacing) {
                canvas.drawLine(
                    bounds.left.toFloat() + i, bounds.top.toFloat(),
                    bounds.left.toFloat() + i + size, bounds.top.toFloat() + size,
                    paint
                )
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    fun showMediaSettingsDialog() {
        val dialogView = android.view.LayoutInflater.from(activity).inflate(R.layout.dialog_media_split_settings, null)
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(activity)
        dialog.setContentView(dialogView)

        val sp = activity.getSharedPreferences("CarrotNaviPrefs", android.content.Context.MODE_PRIVATE)

        val btnClose = dialogView.findViewById<android.widget.ImageView>(R.id.btnCloseMediaSettings)
        val btnConfirm = dialogView.findViewById<android.widget.Button>(R.id.btnConfirmMediaSettings)
        val btnRatioHalf = dialogView.findViewById<android.widget.Button>(R.id.btnRatioHalf)
        val btnRatioDefault = dialogView.findViewById<android.widget.Button>(R.id.btnRatioDefault)
        val btnRatioMini = dialogView.findViewById<android.widget.Button>(R.id.btnRatioMini)
        val btnRatioFullScreen = dialogView.findViewById<android.widget.Button>(R.id.btnRatioFullScreen)

        val sliderMediaRatio = dialogView.findViewById<com.google.android.material.slider.Slider>(R.id.sliderMediaRatio)
        val tvMediaRatioValue = dialogView.findViewById<android.widget.TextView>(R.id.tvMediaRatioValue)

        val rgMediaBgStyle = dialogView.findViewById<android.widget.RadioGroup>(R.id.rgMediaBgStyle)
        val rbBgAlbumArt = dialogView.findViewById<android.widget.RadioButton>(R.id.rbBgAlbumArt)
        val rbBgEq = dialogView.findViewById<android.widget.RadioButton>(R.id.rbBgEq)
        val rbBgEqWave = dialogView.findViewById<android.widget.RadioButton>(R.id.rbBgEqWave)
        val rbBgEqCircle = dialogView.findViewById<android.widget.RadioButton>(R.id.rbBgEqCircle)
        val cbShowAlbumArtWithEq = dialogView.findViewById<android.widget.CheckBox>(R.id.cbShowAlbumArtWithEq)

        // 분할 화면 콘텐츠 선택 설정
        val rgSplitContentType = dialogView.findViewById<android.widget.RadioGroup>(R.id.rgSplitContentType)
        val rbSplitContentMedia = dialogView.findViewById<android.widget.RadioButton>(R.id.rbSplitContentMedia)
        val rbSplitContentOpenpilot = dialogView.findViewById<android.widget.RadioButton>(R.id.rbSplitContentOpenpilot)

        val initialSplitType = sp.getString("SPLIT_CONTENT_TYPE", "media")
        if (initialSplitType == "openpilot") {
            rbSplitContentOpenpilot?.isChecked = true
        } else {
            rbSplitContentMedia?.isChecked = true
        }

        rgSplitContentType?.setOnCheckedChangeListener { _, checkedId ->
            val newType = if (checkedId == R.id.rbSplitContentOpenpilot) "openpilot" else "media"
            sp.edit().putString("SPLIT_CONTENT_TYPE", newType).apply()
            onSplitContentTypeChanged?.invoke(newType)
        }

        // 초기 배경 스타일 설정
        val currentStyle = sp.getString("MEDIA_BG_STYLE", "album")
        when (currentStyle) {
            "eq", "eq_bar" -> rbBgEq.isChecked = true
            "eq_wave" -> rbBgEqWave.isChecked = true
            "eq_circle" -> rbBgEqCircle.isChecked = true
            else -> rbBgAlbumArt.isChecked = true
        }
        cbShowAlbumArtWithEq.isChecked = sp.getBoolean("SHOW_ALBUM_ART_WITH_EQ", false)

        rgMediaBgStyle.setOnCheckedChangeListener { _, checkedId ->
            val style = when (checkedId) {
                R.id.rbBgEq -> "eq"
                R.id.rbBgEqWave -> "eq_wave"
                R.id.rbBgEqCircle -> "eq_circle"
                else -> "album"
            }
            sp.edit().putString("MEDIA_BG_STYLE", style).apply()
        }

        cbShowAlbumArtWithEq.setOnCheckedChangeListener { _, isChecked ->
            sp.edit().putBoolean("SHOW_ALBUM_ART_WITH_EQ", isChecked).apply()
        }

        // 초기 분할 비율 설정
        val isPortrait = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val ratioKey = if (isPortrait) "MEDIA_SPLIT_RATIO_PORTRAIT_F" else "MEDIA_SPLIT_RATIO_LANDSCAPE_F"

        val currentRatio = if (sp.contains(ratioKey)) {
            sp.getFloat(ratioKey, 3.5f)
        } else {
            sp.getFloat("MEDIA_SPLIT_RATIO_F", 3.5f)
        }
        sliderMediaRatio.value = currentRatio.coerceIn(0.5f, 5.0f)
        fun fmt(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
        tvMediaRatioValue.text = "${fmt(sliderMediaRatio.value)} : ${fmt(5f - sliderMediaRatio.value)}"

        fun applyRatio(v: Float) {
            sliderMediaRatio.value = v
            tvMediaRatioValue.text = "${fmt(v)} : ${fmt(5f - v)}"
            sp.edit()
                .putFloat(ratioKey, v)
                .putFloat("MEDIA_SPLIT_RATIO_F", v)
                .apply()
        }

        sliderMediaRatio.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                applyRatio(value)
            }
        }

        btnRatioHalf.setOnClickListener { applyRatio(2.5f) }
        btnRatioDefault.setOnClickListener { applyRatio(3.5f) }
        btnRatioMini.setOnClickListener { applyRatio(4.0f) }
        btnRatioFullScreen.setOnClickListener { applyRatio(5.0f) }

        btnClose.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            hideMediaOverlay()
            dialog.dismiss()
        }

        dialog.show()
        val bottomSheet = dialog.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
        if (bottomSheet != null) {
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet)
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun sendMediaCommand(command: String) {
        try {
            val intent = Intent(MediaNotificationListenerService.ACTION_MEDIA_CONTROL).apply {
                setPackage(activity.packageName)
                putExtra("command", command)
            }
            activity.sendBroadcast(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateMediaOverlayUi(title: String?, artist: String?, albumArt: android.graphics.Bitmap?, isPlaying: Boolean) {
        lastMediaTitle = title
        lastMediaArtist = artist
        lastMediaAlbumArt = albumArt
        lastMediaIsPlaying = isPlaying

        val hasTrack = !title.isNullOrEmpty() && title != "재생중인 곡 없음" && title != "음악을 재생해 주세요"
        lastMediaHasTrack = hasTrack
        val displayTitle = if (hasTrack) title else "재생 중인 음악 없음"
        val displayArtist = if (hasTrack && !artist.isNullOrEmpty()) artist else "-"

        // 가로 형태 UI 갱신
        binding.tvMediaOverlayTitle?.text = displayTitle
        binding.tvMediaOverlayTitle?.isSelected = true
        binding.tvMediaOverlayArtist?.text = displayArtist
        binding.tvMediaOverlayArtist?.isSelected = true

        // 세로 형태 UI 갱신
        binding.tvMediaOverlayTitleVert?.text = displayTitle
        binding.tvMediaOverlayTitleVert?.isSelected = true
        binding.tvMediaOverlayArtistVert?.text = displayArtist
        binding.tvMediaOverlayArtistVert?.isSelected = true

        if (albumArt != null) {
            binding.ivMediaOverlayThumb?.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            binding.ivMediaOverlayThumb?.setImageBitmap(albumArt)
            binding.ivMediaOverlayThumbVert?.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            binding.ivMediaOverlayThumbVert?.setImageBitmap(albumArt)
        } else {
            binding.ivMediaOverlayThumb?.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            binding.ivMediaOverlayThumb?.setImageResource(R.drawable.ic_music_note)
            binding.ivMediaOverlayThumbVert?.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            binding.ivMediaOverlayThumbVert?.setImageResource(R.drawable.ic_music_note)
        }

        if (hasTrack) {
            val statusIconRes = if (isPlaying) R.drawable.ic_round_play_arrow_24 else R.drawable.ic_round_pause_24
            val statusTint = if (isPlaying) Color.parseColor("#FFEB3B") else Color.parseColor("#AAAAAA")

            binding.ivMediaOverlayPlayStatus?.setImageResource(statusIconRes)
            binding.ivMediaOverlayPlayStatus?.setColorFilter(statusTint)
            binding.ivMediaOverlayPlayStatus?.visibility = View.VISIBLE

            binding.ivMediaOverlayPlayStatusVert?.setImageResource(statusIconRes)
            binding.ivMediaOverlayPlayStatusVert?.setColorFilter(statusTint)
            binding.ivMediaOverlayPlayStatusVert?.visibility = View.VISIBLE
        } else {
            binding.ivMediaOverlayPlayStatus?.visibility = View.GONE
            binding.ivMediaOverlayPlayStatusVert?.visibility = View.GONE
        }

        updateMediaProgress()
        if (isPlaying && binding.cvMediaOverlayCard.visibility == View.VISIBLE) {
            startMediaProgressTicker()
        } else {
            stopMediaProgressTicker()
        }
    }

    fun applyMediaOverlayShape(shape: String) {
        val card = binding.cvMediaOverlayCard ?: return
        val isVert = shape == "vertical"
        val density = activity.resources.displayMetrics.density
        val targetWidth = if (isVert) (136 * density).toInt() else (230 * density).toInt()
        val targetHeight = if (isVert) (188 * density).toInt() else (66 * density).toInt()

        val params = card.layoutParams
        params.width = targetWidth
        params.height = targetHeight
        card.layoutParams = params
        card.requestLayout()

        binding.llMediaOverlayHorizontal?.visibility = if (isVert) View.GONE else View.VISIBLE
        binding.llMediaOverlayVertical?.visibility = if (isVert) View.VISIBLE else View.GONE

        updateMediaOverlayUi(lastMediaTitle, lastMediaArtist, lastMediaAlbumArt, lastMediaIsPlaying)
    }

    fun showMediaOverlayShapeMenu(anchor: View) {
        val popup = PopupMenu(activity, anchor)
        val currentShape = sharedPref.getString("MEDIA_OVERLAY_SHAPE", "horizontal") ?: "horizontal"
        popup.menu.add(0, 1, 0, if (currentShape == "horizontal") "✔ 가로 형태 (수평 바)" else "   가로 형태 (수평 바)")
        popup.menu.add(0, 2, 1, if (currentShape == "vertical") "✔ 세로 형태 (콤팩트 카드)" else "   세로 형태 (콤팩트 카드)")
        popup.setOnMenuItemClickListener { item ->
            val newShape = if (item.itemId == 2) "vertical" else "horizontal"
            sharedPref.edit().putString("MEDIA_OVERLAY_SHAPE", newShape).apply()
            applyMediaOverlayShape(newShape)
            Toast.makeText(
                activity,
                if (newShape == "vertical") "미디어 오버레이: 세로 형태로 변경되었습니다." else "미디어 오버레이: 가로 형태로 변경되었습니다.",
                Toast.LENGTH_SHORT
            ).show()
            true
        }
        popup.show()
    }

    private fun setupMediaOverlayDrag() {
        val card = binding.cvMediaOverlayCard ?: return
        val touchSlop = android.view.ViewConfiguration.get(activity).scaledTouchSlop

        var downX = 0f
        var downY = 0f
        var startTransX = 0f
        var startTransY = 0f
        var isDragging = false

        card.setOnTouchListener { _, event ->
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startTransX = card.translationX
                    startTransY = card.translationY
                    isDragging = false
                    card.bringToFront()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!isDragging && Math.hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        var newX = startTransX + dx
                        var newY = startTransY + dy

                        val parent = card.parent as? ViewGroup
                        if (parent != null && parent.width > 0 && parent.height > 0) {
                            val minX = -card.left.toFloat()
                            val maxX = (parent.width - card.right).toFloat()
                            val minY = -card.top.toFloat()
                            val maxY = (parent.height - card.bottom).toFloat()
                            newX = newX.coerceIn(minX, maxX)
                            newY = newY.coerceIn(minY, maxY)
                        }

                        card.translationX = newX
                        card.translationY = newY
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        val isLandscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                        val keyPrefix = if (isLandscape) "cvMediaOverlayCard_land" else "cvMediaOverlayCard_port"
                        sharedPref.edit()
                            .putFloat("${keyPrefix}_x", card.translationX)
                            .putFloat("${keyPrefix}_y", card.translationY)
                            .putBoolean("${keyPrefix}_saved", true)
                            .apply()
                    } else {
                        val currentShape = sharedPref.getString("MEDIA_OVERLAY_SHAPE", "horizontal") ?: "horizontal"
                        val activeThumb = if (currentShape == "vertical") binding.cvMediaOverlayThumbVert else binding.cvMediaOverlayThumb
                        val thumbRect = android.graphics.Rect()
                        activeThumb?.getGlobalVisibleRect(thumbRect)
                        val touchX = event.rawX.toInt()
                        val touchY = event.rawY.toInt()

                        if (thumbRect.contains(touchX, touchY)) {
                            showMediaOverlayShapeMenu(activeThumb ?: card)
                        } else {
                            showMediaSettingsDialog()
                        }
                    }
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    fun restoreMediaOverlayPosition(isLandscape: Boolean) {
        val card = binding.cvMediaOverlayCard ?: return
        val keyPrefix = if (isLandscape) "cvMediaOverlayCard_land" else "cvMediaOverlayCard_port"
        val isSaved = sharedPref.getBoolean("${keyPrefix}_saved", false)
        if (isSaved) {
            val savedX = sharedPref.getFloat("${keyPrefix}_x", 0f)
            val savedY = sharedPref.getFloat("${keyPrefix}_y", 0f)
            card.translationX = savedX
            card.translationY = savedY

            card.post {
                val parent = card.parent as? ViewGroup
                if (parent != null && parent.width > 0 && parent.height > 0) {
                    val minX = -card.left.toFloat()
                    val maxX = (parent.width - card.right).toFloat()
                    val minY = -card.top.toFloat()
                    val maxY = (parent.height - card.bottom).toFloat()
                    card.translationX = card.translationX.coerceIn(minX, maxX)
                    card.translationY = card.translationY.coerceIn(minY, maxY)
                }
            }
        } else {
            card.translationX = 0f
            card.translationY = 0f
        }
    }

    fun notifyMediaOverlayVisibility(isVisible: Boolean) {
        onMediaOverlayVisibilityChanged?.invoke(isVisible)
        activeDialogView?.let { view ->
            view.findViewById<android.view.View>(R.id.cardMediaSplitRatio)?.visibility =
                if (!isVisible) View.VISIBLE else View.GONE
            view.findViewById<android.view.View>(R.id.cardSplitContentType)?.visibility =
                if (!isVisible) View.VISIBLE else View.GONE
            val sw = view.findViewById<android.widget.Switch>(R.id.swMediaOverlayEnable)
            if (sw != null && sw.isChecked != isVisible) {
                sw.isChecked = isVisible
            }
        }
    }

    fun setMediaOverlayVisible(visible: Boolean, savePref: Boolean = true) {
        if (savePref) {
            sharedPref.edit().putBoolean("MEDIA_OVERLAY_VISIBLE", visible).apply()
        }
        val card = binding.cvMediaOverlayCard ?: return
        val effectiveVisible = visible && isOverlayVisible
        card.visibility = if (effectiveVisible) View.VISIBLE else View.GONE
        notifyMediaOverlayVisibility(effectiveVisible)
        if (effectiveVisible) {
            restoreMediaOverlayPosition(activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            updateMediaOverlayUi(
                MediaNotificationListenerService.currentTitle,
                MediaNotificationListenerService.currentArtist,
                MediaNotificationListenerService.currentAlbumArt ?: MediaNotificationListenerService.fetchedAlbumArt,
                MediaNotificationListenerService.isPlaying
            )
        } else {
            stopMediaProgressTicker()
        }
    }

    fun hideMediaOverlay(updatePref: Boolean = true) {
        if (updatePref) {
            sharedPref.edit().putBoolean("MEDIA_OVERLAY_VISIBLE", false).apply()
        }
        if (binding.cvMediaOverlayCard.visibility == View.VISIBLE) {
            binding.cvMediaOverlayCard.visibility = View.GONE
            stopMediaProgressTicker()
            notifyMediaOverlayVisibility(false)
        }
    }

    fun startMediaProgressTicker() {
        mediaProgressHandler.removeCallbacks(mediaProgressRunnable)
        if (lastMediaIsPlaying && binding.cvMediaOverlayCard.visibility == View.VISIBLE) {
            mediaProgressHandler.post(mediaProgressRunnable)
        }
    }

    fun stopMediaProgressTicker() {
        mediaProgressHandler.removeCallbacks(mediaProgressRunnable)
    }

    fun updateMediaProgress() {
        val pb = binding.pbMediaOverlayProgress ?: return
        val duration = MediaNotificationListenerService.duration
        val hasTrack = lastMediaHasTrack && !lastMediaTitle.isNullOrBlank()

        if (hasTrack && duration > 0L) {
            val lastUpdate = MediaNotificationListenerService.lastUpdateTime
            val elapsed = if (lastMediaIsPlaying && lastUpdate > 0L) {
                (android.os.SystemClock.elapsedRealtime() - lastUpdate).coerceAtLeast(0L)
            } else {
                0L
            }
            val currentPos = (MediaNotificationListenerService.position + elapsed).coerceIn(0L, duration)
            val progress = ((currentPos * 1000L) / duration).toInt()
            pb.progress = progress
            pb.visibility = View.VISIBLE
        } else {
            pb.progress = 0
            pb.visibility = View.GONE
        }
    }
}
