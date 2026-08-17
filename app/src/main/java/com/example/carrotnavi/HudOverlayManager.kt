package com.example.carrotnavi

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
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
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
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
            binding.llSpeedGroup,
            binding.llBottomLeftOverlays,
            binding.llTopUiGroup,

            binding.llStatusGroup
        )

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

        isOverlayVisible = sharedPref.getBoolean("OVERLAY_VISIBLE", true)
        val isDebugOverlayVisible = sharedPref.getBoolean("DEBUG_OVERLAY_VISIBLE", false)
        updateOverlayVisibility()

        
        
        binding.btnToggleVisibility.setOnClickListener {
            isOverlayVisible = !isOverlayVisible
            sharedPref.edit().putBoolean("OVERLAY_VISIBLE", isOverlayVisible).apply()
            updateOverlayVisibility()
        }

        binding.btnSettings?.setOnClickListener {
            val dialogView = android.view.LayoutInflater.from(activity).inflate(R.layout.dialog_drive_settings, null)
            val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(activity)
            dialog.setContentView(dialogView)
            activeDialogView = dialogView
            dialog.setOnDismissListener {
                activeDialogView = null
            }

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
            
            val sliderMediaRatio = dialogView.findViewById<com.google.android.material.slider.Slider>(R.id.sliderMediaRatio)
            val tvMediaRatioValue = dialogView.findViewById<android.widget.TextView>(R.id.tvMediaRatioValue)

            val btnCloseSettings = dialogView.findViewById<android.widget.ImageView>(R.id.btnCloseSettings)
            val btnCheckUpdate = dialogView.findViewById<android.widget.Button>(R.id.btnCheckUpdate)
            
            cbDistanceFormatKm.isChecked = sp.getBoolean("USE_KM_DISTANCE_FORMAT", true)
            
            val currentStyle = sp.getString("MEDIA_BG_STYLE", "album")
            when (currentStyle) {
                "eq", "eq_bar" -> rbBgEq.isChecked = true
                "eq_wave" -> rbBgEqWave.isChecked = true
                "eq_circle" -> rbBgEqCircle.isChecked = true
                else -> rbBgAlbumArt.isChecked = true
            }
            cbShowAlbumArtWithEq.isChecked = sp.getBoolean("SHOW_ALBUM_ART_WITH_EQ", false)
            
            val currentRatio = sp.getFloat("MEDIA_SPLIT_RATIO_F", 3.5f)
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
                AutoUpdater.checkForUpdates(activity, isManual = true)
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
                sp.edit().putFloat("MEDIA_SPLIT_RATIO_F", value).apply()
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
            
            // Force the bottom sheet to be fully expanded
            val bottomSheet = dialog.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet)
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            }
        }

        binding.btnEditMode.setOnClickListener {
            isEditMode = !isEditMode
            updateEditModeForegrounds()
            if (isEditMode) {
                binding.btnEditMode.setBackgroundResource(R.drawable.shape_circle_green)
                binding.btnRestoreDefaults.visibility = View.VISIBLE
                Toast.makeText(activity, "오버레이 편집 모드 켜짐", Toast.LENGTH_SHORT).show()
            } else {
                binding.btnEditMode.setBackgroundResource(R.drawable.shape_circle_gray)
                binding.btnRestoreDefaults.visibility = View.GONE
                Toast.makeText(activity, "오버레이 편집 모드 꺼짐", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRestoreDefaults.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle("오버레이 배치 초기화")
                .setMessage("모든 위젯의 배치를 기본값으로 복원하시겠습니까?")
                .setPositiveButton("복원") { _, _ ->
                    sharedPref.edit()
                        .remove("llSpeedGroup_x_port")
                        .remove("llSpeedGroup_y_port")
                        .remove("llSpeedGroup_x_land")
                        .remove("llSpeedGroup_y_land")
                        .remove("llBottomLeftOverlays_x_port")
                        .remove("llBottomLeftOverlays_y_port")
                        .remove("llBottomLeftOverlays_x_land")
                        .remove("llBottomLeftOverlays_y_land")
                        .remove("llSpeedGroup_scale_port")
                        .remove("llSpeedGroup_scale_land")
                        .remove("llBottomLeftOverlays_scale_port")
                        .remove("llBottomLeftOverlays_scale_land")
                        .remove("llStatusGroup_x_port")
                        .remove("llStatusGroup_y_port")
                        .remove("llStatusGroup_x_land")
                        .remove("llStatusGroup_y_land")
                        .remove("llStatusGroup_scale_port")
                        .remove("llStatusGroup_scale_land")
                        .apply()

                    // Reset views to layout defaults immediately
                    binding.llSpeedGroup.translationX = 0f
                    binding.llSpeedGroup.translationY = 0f
                    binding.llBottomLeftOverlays.translationX = 0f
                    binding.llBottomLeftOverlays.translationY = 0f
                    binding.llStatusGroup?.translationX = 0f
                    binding.llStatusGroup?.translationY = 0f

                    binding.llSpeedGroup.scaleX = 1f
                    binding.llSpeedGroup.scaleY = 1f
                    binding.llBottomLeftOverlays.scaleX = 1f
                    binding.llBottomLeftOverlays.scaleY = 1f
                    binding.llStatusGroup?.scaleX = 1f
                    binding.llStatusGroup?.scaleY = 1f

                    Toast.makeText(activity, "기본값으로 복원되었습니다.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null)
                .show()
        }
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

        
        binding.btnSearchAddress.visibility = visibility
        binding.btnSettings?.visibility = visibility
        binding.btnEditMode.visibility = visibility
        
        val isDebugOverlayVisible = sharedPref.getBoolean("DEBUG_OVERLAY_VISIBLE", false)
        binding.llStatusGroup?.visibility = if (isOverlayVisible && isDebugOverlayVisible) View.VISIBLE else View.GONE
        
        binding.btnToggleVisibility.alpha = if (isOverlayVisible) 1.0f else 0.5f
    }

    private fun updateEditModeForegrounds() {
        if (isEditMode) {
            binding.llSpeedGroup.foreground = HatchedDrawable(binding.llSpeedGroup)
            binding.llBottomLeftOverlays.foreground = HatchedDrawable(binding.llBottomLeftOverlays)
            binding.llTopUiGroup?.let { it.foreground = HatchedDrawable(it) }

            binding.llStatusGroup?.let { it.foreground = HatchedDrawable(it) }
        } else {
            binding.llSpeedGroup.foreground = null
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
            binding.btnRestoreDefaults,
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
}
