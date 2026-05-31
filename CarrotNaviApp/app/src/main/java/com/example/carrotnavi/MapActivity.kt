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

import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.view.WindowManager
import android.view.GestureDetector
import android.view.View
import android.app.PictureInPictureParams
import android.util.Rational
import android.content.res.Configuration
import android.view.MotionEvent
import androidx.activity.OnBackPressedCallback

class MapActivity : AppCompatActivity() {

    companion object {
        const val ACTION_TOGGLE_POWER_SAVING = "com.example.carrotnavi.ACTION_TOGGLE_POWER_SAVING"
    }

    private lateinit var binding: ActivityMapBinding
    private var navigationFragment: NavigationFragment? = null
    
    private var isPowerSavingMode = false

    private val powerSavingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TOGGLE_POWER_SAVING) {
                togglePowerSavingMode()
            }
        }
    }

    private fun togglePowerSavingMode() {
        isPowerSavingMode = !isPowerSavingMode
        val attrs = window.attributes
        if (isPowerSavingMode) {
            binding.vBlackOverlay.visibility = View.VISIBLE
            attrs.screenBrightness = 0.0f
        } else {
            binding.vBlackOverlay.visibility = View.GONE
            attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = attrs
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isPowerSavingMode) {
                    togglePowerSavingMode()
                    return true
                }
                return super.onDoubleTap(e)
            }
        })
        binding.vBlackOverlay.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        val sharedPref = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
        val appKey = sharedPref.getString("APP_KEY", "") ?: ""
        
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

        binding.btnPipMode.setOnClickListener {
            enterPipModeManually()
        }

        binding.btnScreenOff.setOnClickListener {
            togglePowerSavingMode()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                enterPipModeManually()
            }
        })

        binding.btnExitApp.setOnClickListener {
            finishAffinity()
        }

        // 자동 업데이트 체크 (MainActivity가 바로 종료되므로 MapActivity에서도 체크)
        AutoUpdater.checkForUpdates(this)

        initTmapSdk(appKey)
    }

    override fun getSystemService(name: String): Any? {
        if (Context.AUDIO_SERVICE == name) {
            // 강제로 Application의 가짜 AudioManager 반환
            return applicationContext.getSystemService(name)
        }
        return super.getSystemService(name)
    }

    private fun initTmapSdk(appKey: String) {
        // TmapUISDK 초기화
        initialize(this, "", appKey, "", "", object : TmapUISDK.InitializeListener {
            override fun onSuccess() {
                runOnUiThread {
                    binding.tvStatus.visibility = View.GONE
                    
                    muteTmapAudio()
                    startSafeDriveMode()
                    startUdpSenderService()
                }
            }

            override fun onFail(errorCode: Int, errorMsg: String?) {
                runOnUiThread {
                    binding.tvStatus.text = "초기화 실패: $errorMsg"
                    Toast.makeText(this@MapActivity, "Tmap SDK 초기화 실패", Toast.LENGTH_LONG).show()
                }
            }

            override fun savedRouteInfoExists(dest: String?) {
                // 경로 안내 중이 아니므로 무시
            }
        })
    }

    private fun muteTmapAudio() {
        try {
            // 안드로이드 기본 설정 레벨에서 Tmap SDK의 자동 볼륨 조절 기능 강제 끄기 시도
            val prefs = getSharedPreferences("user.settings.info", Context.MODE_PRIVATE)
            prefs.edit()
                .putInt("feature.naviVolume", 0)
                .putInt("feature.musicVolumeAutoControlOnDriving", 0)
                .putBoolean("feature.musicVolumeAutoControlOnDriving", false)
                .putInt("feature_musicVolumeAutoControlOnDriving", 0)
                .putBoolean("feature_musicVolumeAutoControlOnDriving", false)
                .apply()

            val navClass = Class.forName("com.skt.tmap.engine.navigation.TmapNavigation")
            val getInstanceMethod = navClass.getMethod("getInstance")
            val navInstance = getInstanceMethod.invoke(null)
            
            val getAudioMethod = navClass.getMethod("getAudioInterface")
            val audioInterface = getAudioMethod.invoke(navInstance)
            
            if (audioInterface != null) {
                val audioClass = Class.forName("com.skt.tmap.engine.navigation.TmapNavigationAudio")
                
                // 1. 기존처럼 Mute 처리
                try {
                    val setMuteMethod = audioClass.getMethod("setMuteState", Byte::class.javaPrimitiveType)
                    setMuteMethod.invoke(audioInterface, 1.toByte())
                } catch (e: Exception) {
                    // 무시
                }

                // 2. Volume을 0으로 명시적 설정 시도
                try {
                    val setVolumeMethod = audioClass.getMethod("setVolume", Int::class.javaPrimitiveType)
                    setVolumeMethod.invoke(audioInterface, 0)
                } catch (e: Exception) {
                    // 무시
                }

                // 3. AudioPlayCallback 가로채기
                val audioCallbackClass = Class.forName("com.skt.tmap.engine.navigation.TmapNavigationAudio\$AudioPlayCallback")
                val handler = java.lang.reflect.InvocationHandler { _, _, _ ->
                    null
                }
                val proxy = java.lang.reflect.Proxy.newProxyInstance(
                    classLoader,
                    arrayOf(audioCallbackClass),
                    handler
                )
                
                val setAudioPlayCallbackMethod = audioClass.getMethod("setAudioPlayCallback", audioCallbackClass)
                setAudioPlayCallbackMethod.invoke(audioInterface, proxy)
                
                Log.d("MapActivity", "Successfully intercepted Tmap AudioPlayCallback and injected preferences.")
            }
        } catch (e: Exception) {
            Log.e("MapActivity", "Failed to mute Tmap audio", e)
        }
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
                }
            })

            // 안드로이드 기본 GPS 상태 리스너 등록
            try {
                val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                locationManager.registerGnssStatusCallback(object : android.location.GnssStatus.Callback() {
                    override fun onStarted() {
                        binding.tvGpsStatus.text = "GPS 상태: 탐색 중"
                        binding.tvGpsStatus.setTextColor(android.graphics.Color.YELLOW)
                    }
                    override fun onStopped() {
                        binding.tvGpsStatus.text = "GPS 상태: 끊김 (NO_SIGNAL)"
                        binding.tvGpsStatus.setTextColor(android.graphics.Color.RED)
                    }
                    override fun onFirstFix(ttffMillis: Int) {
                        binding.tvGpsStatus.text = "GPS 상태: 수신 양호"
                        binding.tvGpsStatus.setTextColor(android.graphics.Color.GREEN)
                    }
                    override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                        var usedInFix = 0
                        for (i in 0 until status.satelliteCount) {
                            if (status.usedInFix(i)) usedInFix++
                        }
                        if (usedInFix >= 4) {
                            binding.tvGpsStatus.text = "GPS 상태: GOOD (위성 $usedInFix 개)"
                            binding.tvGpsStatus.setTextColor(android.graphics.Color.GREEN)
                        } else {
                            binding.tvGpsStatus.text = "GPS 상태: BAD (위성 $usedInFix 개)"
                            binding.tvGpsStatus.setTextColor(android.graphics.Color.RED)
                        }
                    }
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            } catch (e: SecurityException) {
                Log.e("MapActivity", "GPS Permission Error")
            }
        }
    }

    /*
    private fun processSdiInfo(sdiInfo: TmapSdiInfo) {
        // Dummy
    }
    */

    private fun startUdpSenderService() {
        val intent = Intent(this, UdpSenderService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun enterPipModeManually() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                Log.e("MapActivity", "Manual PiP Error", e)
            }
        } else {
            Toast.makeText(this, "이 기기에서는 창모드를 지원하지 않습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val intent = Intent(this, UdpSenderService::class.java)
        stopService(intent)
    }

    override fun onResume() {
        super.onResume()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(powerSavingReceiver, IntentFilter(ACTION_TOGGLE_POWER_SAVING), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(powerSavingReceiver, IntentFilter(ACTION_TOGGLE_POWER_SAVING))
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .setAutoEnterEnabled(true)
                    .build()
                setPictureInPictureParams(params)
            } catch (e: Exception) {
                Log.e("MapActivity", "Auto PiP Error", e)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(powerSavingReceiver)
        } catch (e: Exception) {}

        // Fallback for Recents button app switching
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (!isInPictureInPictureMode && !isFinishing) {
                try {
                    val params = PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build()
                    enterPictureInPictureMode(params)
                } catch (e: Exception) {
                    Log.e("MapActivity", "PiP onPause Error", e)
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                Log.e("MapActivity", "PiP Error", e)
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            binding.vPipOverlay.visibility = View.VISIBLE
        } else {
            binding.vPipOverlay.visibility = View.GONE
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        val llMainContainer = findViewById<android.widget.LinearLayout>(R.id.llMainContainer)
        val llTopUI = findViewById<android.widget.LinearLayout>(R.id.llTopUI)
        val llButtons = findViewById<android.widget.LinearLayout>(R.id.llButtons)
        val flMapContainer = findViewById<android.widget.FrameLayout>(R.id.flMapContainer)

        if (llMainContainer == null || llTopUI == null || llButtons == null || flMapContainer == null) return

        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            llMainContainer.orientation = android.widget.LinearLayout.HORIZONTAL
            llTopUI.orientation = android.widget.LinearLayout.VERTICAL
            llButtons.orientation = android.widget.LinearLayout.VERTICAL
            
            llMainContainer.removeView(llTopUI)
            llMainContainer.addView(llTopUI)

            val layoutParams = llTopUI.layoutParams as android.widget.LinearLayout.LayoutParams
            layoutParams.width = android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            layoutParams.height = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
            layoutParams.gravity = android.view.Gravity.CENTER
            llTopUI.layoutParams = layoutParams

            val mapParams = flMapContainer.layoutParams as android.widget.LinearLayout.LayoutParams
            mapParams.width = 0
            mapParams.height = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
            mapParams.weight = 1f
            flMapContainer.layoutParams = mapParams

            for (i in 0 until llButtons.childCount) {
                val btn = llButtons.getChildAt(i)
                val btnParams = btn.layoutParams as android.widget.LinearLayout.LayoutParams
                btnParams.width = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                btnParams.height = android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                btnParams.weight = 0f
                btnParams.setMargins(8, 8, 8, 8)
                btn.layoutParams = btnParams
            }
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            llMainContainer.orientation = android.widget.LinearLayout.VERTICAL
            llTopUI.orientation = android.widget.LinearLayout.VERTICAL
            llButtons.orientation = android.widget.LinearLayout.HORIZONTAL
            
            llMainContainer.removeView(llTopUI)
            llMainContainer.addView(llTopUI, 0)

            val layoutParams = llTopUI.layoutParams as android.widget.LinearLayout.LayoutParams
            layoutParams.width = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
            layoutParams.height = android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            layoutParams.gravity = android.view.Gravity.CENTER_HORIZONTAL
            llTopUI.layoutParams = layoutParams

            val mapParams = flMapContainer.layoutParams as android.widget.LinearLayout.LayoutParams
            mapParams.width = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
            mapParams.height = 0
            mapParams.weight = 1f
            flMapContainer.layoutParams = mapParams

            for (i in 0 until llButtons.childCount) {
                val btn = llButtons.getChildAt(i)
                val btnParams = btn.layoutParams as android.widget.LinearLayout.LayoutParams
                btnParams.width = 0
                btnParams.height = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                btnParams.weight = 1f
                btnParams.setMargins(8, 8, 8, 8)
                btn.layoutParams = btnParams
            }
        }
    }
}
