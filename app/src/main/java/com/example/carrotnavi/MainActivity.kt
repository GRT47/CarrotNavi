package com.example.carrotnavi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.Observer
import android.graphics.Color
import android.app.AlertDialog
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.carrotnavi.databinding.ActivityMainBinding
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.View
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.TextView
import com.tmapmobility.tmap.tmapsdk.ui.util.TmapUISDK
import com.tmapmobility.tmap.tmapsdk.ui.util.TmapUISDK.Companion.initialize
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.KNLanguageType
import android.content.ClipboardManager
import android.content.ClipData
import android.graphics.drawable.ColorDrawable
import android.util.Base64
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {

    private val searchRetrofit by lazy {
        retrofit2.Retrofit.Builder()
            .baseUrl("https://dapi.kakao.com/")
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
    }
    private val kakaoSearchApi: KakaoSearchApi by lazy {
        searchRetrofit.create(KakaoSearchApi::class.java)
    }


    private lateinit var binding: ActivityMainBinding

    private val backgroundPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "항상 허용 권한이 거부되었습니다. (일부 기능 제한될 수 있음)", Toast.LENGTH_SHORT).show()
        }
        startMapActivity()
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            checkBackgroundPermissionAndStart()
        } else {
            Toast.makeText(this, "권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkAndHandleSharedIntent()
        if (intent.getBooleanExtra("run_diag", false)) {
            runConnectionDiagnostics()
        }
    }

    override fun onResume() {
        super.onResume()
        val sharedPref = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
        val currentThemeMode = sharedPref.getString("MAP_THEME_MODE", "auto") ?: "auto"
        when (currentThemeMode) {
            "day" -> binding.rbMainThemeDay.isChecked = true
            "night" -> binding.rbMainThemeNight.isChecked = true
            else -> binding.rbMainThemeAuto.isChecked = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        RemoteLogManager.init(this)
        
        getSharedPreferences("CarrotNaviPrefs", android.content.Context.MODE_PRIVATE).edit().putBoolean("IS_DEBUG_MODE", false).apply()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        var backPressedTime = 0L
        var exitToast: android.widget.Toast? = null
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (System.currentTimeMillis() - backPressedTime < 2000) {
                    exitToast?.cancel()
                    stopService(android.content.Intent(this@MainActivity, UdpSenderService::class.java))
                    stopService(android.content.Intent(this@MainActivity, WebServerService::class.java))
                    finishAffinity()
                    System.exit(0)
                } else {
                    backPressedTime = System.currentTimeMillis()
                    exitToast?.cancel()
                    exitToast = android.widget.Toast.makeText(
                        this@MainActivity,
                        "앱을 종료하시겠습니까? '뒤로' 버튼을 한번 더 누르면 종료됩니다.",
                        android.widget.Toast.LENGTH_SHORT
                    )
                    exitToast?.show()
                }
            }
        })
        
        // 앱 버전 표시
        val sp = getSharedPreferences("CarrotNaviPrefs", android.content.Context.MODE_PRIVATE)
        if (!sp.getBoolean("KAKAO_OFFSET_CUSTOMIZED_BY_USER", false)) {
            sp.edit().putInt("KAKAO_LANDSCAPE_BOTTOM_BAR_HEIGHT_OFFSET", 0)
                .putInt("KAKAO_PORTRAIT_BOTTOM_BAR_HEIGHT_OFFSET", 0).apply()
        }
        val deviceId = sp.getString("DEVICE_ID", "알 수 없음")
        binding.tvAppVersion.text = "버전 ${BuildConfig.VERSION_NAME} / 기기ID: $deviceId"
        
        binding.btnCheckUpdate?.setOnClickListener {
            AutoUpdater.checkForUpdates(this, isManual = true, useServer = false)
        }
        binding.btnCheckUpdateServer?.setOnClickListener {
            AutoUpdater.checkForUpdates(this, isManual = true, useServer = true)
        }
        
        binding.btnExitApp?.setOnClickListener {
            stopService(android.content.Intent(this, UdpSenderService::class.java))
            stopService(android.content.Intent(this, WebServerService::class.java))
            finishAffinity()
            System.exit(0)
        }

        val sharedPref = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)

        // Load saved values
        val savedAppKey = sharedPref.getString("APP_KEY", "")
        binding.etAppKey.setText(savedAppKey)
        binding.etKakaoNativeAppKey.setText(sharedPref.getString("KAKAO_NATIVE_APP_KEY", ""))
        binding.etKakaoRestApiKey.setText(sharedPref.getString("KAKAO_REST_API_KEY", ""))
        binding.cbBackgroundLocation.isChecked = sharedPref.getBoolean("REQ_BACKGROUND", false)
        binding.cbDistanceFormatKm.isChecked = sharedPref.getBoolean("USE_KM_DISTANCE_FORMAT", true)
        
        
        // Load and setup Offset Slider
        val currentOffset = sharedPref.getInt("BLOCK_SPEED_OFFSET", 0)
        binding.sliderOffset.value = currentOffset.toFloat()
        binding.tvOffsetValue.text = if (currentOffset > 0) "+$currentOffset km/h" else "$currentOffset km/h"
        
        binding.sliderOffset.addOnChangeListener { _, value, _ ->
            val intValue = value.toInt()
            binding.tvOffsetValue.text = if (intValue > 0) "+$intValue km/h" else "$intValue km/h"
            sharedPref.edit().putInt("BLOCK_SPEED_OFFSET", intValue).apply()
        }

        // Load and setup Boost Mode
        val currentBoostMode = sharedPref.getInt("BLOCK_SPEED_BOOST_MODE", 0)
        if (currentBoostMode == 0) {
            binding.rbBoostProgressive.isChecked = true
        } else {
            binding.rbBoostFixed.isChecked = true
        }
        binding.rgBoostMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == binding.rbBoostProgressive.id) 0 else 1
            sharedPref.edit().putInt("BLOCK_SPEED_BOOST_MODE", mode).apply()
        }

        // Load and setup Fake Drop Slider
        val currentFakeDrop = sharedPref.getInt("BLOCK_SPEED_FAKE_DROP", 10)
        binding.sliderFakeDrop.value = currentFakeDrop.toFloat()
        binding.tvFakeDropValue.text = "$currentFakeDrop km/h"
        
        binding.sliderFakeDrop.addOnChangeListener { _, value, _ ->
            val intValue = value.toInt()
            binding.tvFakeDropValue.text = "$intValue km/h"
            sharedPref.edit().putInt("BLOCK_SPEED_FAKE_DROP", intValue).apply()
        }

        // Load and setup Map Theme Mode (주간 / 야간 / 자동)
        val currentThemeMode = sharedPref.getString("MAP_THEME_MODE", "auto") ?: "auto"
        when (currentThemeMode) {
            "day" -> binding.rbMainThemeDay.isChecked = true
            "night" -> binding.rbMainThemeNight.isChecked = true
            else -> binding.rbMainThemeAuto.isChecked = true
        }
        binding.rgMainMapThemeMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                binding.rbMainThemeDay.id -> "day"
                binding.rbMainThemeNight.id -> "night"
                else -> "auto"
            }
            sharedPref.edit().putString("MAP_THEME_MODE", mode).apply()
            SdiDataRepository.applyThemeMode(this)
        }

        // Start WebServer Service for IP reporting
        val webServerIntent = Intent(this, WebServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(webServerIntent)
        } else {
            startService(webServerIntent)
        }

        // Show IP Address
        updateWebServerInfo(sharedPref)
        
        // OP Connection State Observer
        OpenpilotStateRepository.state.observe(this, Observer { state ->
            if (state.ip.isNotEmpty() && state.carrot2.isNotEmpty()) {
                binding.tvOpStatus.text = "연결됨 (${state.ip})"
                binding.tvOpStatus.setTextColor(Color.parseColor("#4CAF50"))
            } else {
                binding.tvOpStatus.text = "연결 안됨"
                binding.tvOpStatus.setTextColor(Color.parseColor("#FF5252"))
            }
        })

        // 자동 실행 로직
        if (intent?.action == Intent.ACTION_SEND) {
            checkAndHandleSharedIntent()   // 공유로 진입: 자동실행 금지
        } else {
            val shouldAutoStart = intent.getBooleanExtra("auto_start", true)
            if (!savedAppKey.isNullOrEmpty() && shouldAutoStart) {
                checkPermissionsAndStart()
            }
        }

        binding.btnStartNavi.setOnClickListener {
            val appKey = binding.etAppKey.text.toString().trim()
            val reqBackground = binding.cbBackgroundLocation.isChecked
            val distanceFormatKm = binding.cbDistanceFormatKm.isChecked

            if (appKey.isEmpty()) {
                Toast.makeText(this, "App Key를 입력하세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Save to SharedPreferences
            sharedPref.edit().apply {
                putString("APP_KEY", appKey)
                putString("KAKAO_NATIVE_APP_KEY", binding.etKakaoNativeAppKey.text.toString().trim())
                putString("KAKAO_REST_API_KEY", binding.etKakaoRestApiKey.text.toString().trim())
                putBoolean("REQ_BACKGROUND", reqBackground)
                putBoolean("USE_KM_DISTANCE_FORMAT", distanceFormatKm)
                apply()
            }

            checkPermissionsAndStart()
        }

        checkNotificationPermissionAndPrompt()
        setupConnectionDiagnostics()

    }

    private fun isNotificationPermissionGranted(): Boolean {
        val enabledListeners = android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabledListeners != null && enabledListeners.contains(packageName)
    }

    private fun checkNotificationPermissionAndPrompt() {
        if (!isNotificationPermissionGranted()) {
            android.widget.Toast.makeText(this, "미디어 정보를 위해 알림 접근 권한을 허용해주세요.", android.widget.Toast.LENGTH_LONG).show()
            val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }
    }

    
        private fun checkAndHandleSharedIntent() {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            
            // Extract keyword
            val cleanText = sharedText.replace(Regex("\\[.*?\\]"), "")
                .replace(Regex("http[s]?://\\S+"), "")
                .trim()
                
            val lines = cleanText.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return
            
            // Search only the address (usually the last line before URL)
            val keyword = lines.last()
            
            val sharedPref = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
            val restApiKey = sharedPref.getString("KAKAO_REST_API_KEY", "") ?: ""
            if (restApiKey.isEmpty()) {
                Toast.makeText(this, "Kakao REST API 키가 설정되지 않았습니다.", Toast.LENGTH_SHORT).show()
                return
            }

            binding.tvOpStatus.text = "공유된 주소 검색중..."
            val authorizationHeader = "KakaoAK $restApiKey"
            
            kakaoSearchApi.searchKeyword(authorizationHeader, keyword).enqueue(object : retrofit2.Callback<KakaoSearchResponse> {
                override fun onResponse(call: retrofit2.Call<KakaoSearchResponse>, response: retrofit2.Response<KakaoSearchResponse>) {
                    val docs = response.body()?.documents
                    if (!docs.isNullOrEmpty()) {
                        val doc = docs[0]
                        Toast.makeText(this@MainActivity, "'${doc.place_name.ifEmpty { doc.road_address_name }}' (으)로 바로 안내를 시작합니다.", Toast.LENGTH_SHORT).show()
                        val newIntent = Intent(this@MainActivity, MapActivity::class.java).apply {
                            putExtra("dest_place_name", doc.place_name)
                            putExtra("dest_road_address_name", doc.road_address_name)
                            putExtra("dest_address_name", doc.address_name)
                            putExtra("dest_x", doc.x)
                            putExtra("dest_y", doc.y)
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        startActivity(newIntent)
                    } else if (lines.size > 1) {
                        // Retry with the first line if address search failed
                        kakaoSearchApi.searchKeyword(authorizationHeader, lines[0]).enqueue(object : retrofit2.Callback<KakaoSearchResponse> {
                            override fun onResponse(call: retrofit2.Call<KakaoSearchResponse>, response: retrofit2.Response<KakaoSearchResponse>) {
                                val retryDocs = response.body()?.documents
                                if (!retryDocs.isNullOrEmpty()) {
                                    val doc = retryDocs[0]
                                    Toast.makeText(this@MainActivity, "'${doc.place_name.ifEmpty { doc.road_address_name }}' (으)로 설정합니다.", Toast.LENGTH_SHORT).show()
                                    val newIntent = Intent(this@MainActivity, MapActivity::class.java).apply {
                                        putExtra("dest_place_name", doc.place_name)
                                        putExtra("dest_road_address_name", doc.road_address_name)
                                        putExtra("dest_address_name", doc.address_name)
                                        putExtra("dest_x", doc.x)
                                        putExtra("dest_y", doc.y)
                                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    }
                                    startActivity(newIntent)
                                } else {
                                    handleSearchFailure(keyword)
                                }
                            }
                            override fun onFailure(call: retrofit2.Call<KakaoSearchResponse>, t: Throwable) {
                                handleSearchFailure(keyword)
                            }
                        })
                    } else {
                        handleSearchFailure(keyword)
                    }
                }
                override fun onFailure(call: retrofit2.Call<KakaoSearchResponse>, t: Throwable) {
                    handleSearchFailure(keyword)
                }
            })
            
            // clear intent action so it doesn't trigger again on rotation
            intent.action = Intent.ACTION_MAIN
        }
    }

    private fun handleSearchFailure(failedKeyword: String) {
        Toast.makeText(this, "주소를 찾을 수 없어 안심 주행 모드를 시작합니다.", Toast.LENGTH_SHORT).show()
        binding.tvOpStatus.text = "안심 주행 모드 전환"
        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            checkBackgroundPermissionAndStart()
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun checkBackgroundPermissionAndStart() {
        if (binding.cbBackgroundLocation.isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // 백그라운드 권한 요청 (설정 화면으로 이동됨)
                Toast.makeText(this, "설정에서 '항상 허용'을 선택해 주세요.", Toast.LENGTH_LONG).show()
                backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                startMapActivity()
            }
        } else {
            startMapActivity()
        }
    }

    private fun startMapActivity() {
        val intent = Intent(this, MapActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }


    private fun dumpTmapAudioSettings() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        android.util.Log.d("CarrotNaviAudio", "Music stream volume: ${audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)}")
        android.util.Log.d("CarrotNaviAudio", "Navi stream volume: ${audioManager.getStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION)}")
        
        android.util.Log.e("TmapVolume", "dumpTmapAudioSettings started")
        val kw = listOf("mute", "volume", "sound", "audio", "tts", "speech", "voice", "guide", "guidance", "announce", "alert")
        val sb = java.lang.StringBuilder()
        sb.append("dumpTmapAudioSettings started\n")
        try {
            val classesToInspect = listOf(
                "com.skt.tmap.engine.navigation.TmapNavigation",
                "com.tmapmobility.tmap.tmapsdk.ui.util.TmapUISDK",
                "com.skt.tmap.engine.navigation.TmapNavigationAudio",
                "com.skt.tmap.engine.navigation.TTSHelper",
                "com.tmapmobility.tmap.tmapsdk.ui.fragment.NavigationFragment"
            )

            for (className in classesToInspect) {
                try {
                    val cls = Class.forName(className)
                    for (m in cls.methods) {
                        if (kw.any { m.name.contains(it, ignoreCase = true) }) {
                            val line = "$className method: ${m.name}(${m.parameterTypes.joinToString { it.name }}) -> ${m.returnType.name}\n"
                            sb.append(line)
                            android.util.Log.e("TmapVolume", sb.toString())
                        }
                    }
                } catch (e: Throwable) {
                    sb.append("Failed to inspect $className\n")
                    android.util.Log.e("TmapVolume", "Failed to inspect $className")
                }
            }
        } catch (e: Throwable) {
            sb.append("Error in dumpTmapAudioSettings: ${e.message}\n")
            android.util.Log.e("TmapVolume", "Error in dumpTmapAudioSettings", e)
        }
        
        try {
            val file = java.io.File(getFilesDir(), "tmap_dump.txt")
            file.writeText(sb.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun updateWebServerInfo(sharedPref: android.content.SharedPreferences) {
        val ipAddress = getLocalIpAddress()
        val serverUrl = sharedPref.getString("IP_REPORT_SERVER_URL", "")?.trim()
        val deviceId = sharedPref.getString("DEVICE_ID", "carrot")?.trim()

        if (ipAddress != null) {
            var infoText = "내부 IP: http://$ipAddress:8080/\nmDNS: http://carrotnavi.local:8080/"
            if (!serverUrl.isNullOrEmpty()) {
                val connectUrl = if (serverUrl.endsWith("/")) "${serverUrl}connect/$deviceId" else "$serverUrl/connect/$deviceId"
                infoText += "\n원격 접속: $connectUrl"
            }
            binding.tvWebServerInfo.text = infoText
            binding.tvWebServerInfo.visibility = View.VISIBLE
        } else {
            binding.tvWebServerInfo.text = "원격 설정: Wi-Fi 연결 확인 필요"
        }
    }

    private enum class DiagStatus {
        WAITING, PROGRESS, SUCCESS, WARNING, ERROR
    }

    private enum class DiagItemType {
        NETWORK, TMAP, KAKAO_NATIVE, KAKAO_REST
    }

    private class DiagResultInfo(
        var status: DiagStatus = DiagStatus.WAITING,
        var badgeText: String = "대기중",
        var detailText: String = "확인 대기",
        var rawErrorCode: String? = null,
        var rawErrorMessage: String? = null
    )

    private val diagResults = mutableMapOf(
        DiagItemType.NETWORK to DiagResultInfo(),
        DiagItemType.TMAP to DiagResultInfo(),
        DiagItemType.KAKAO_NATIVE to DiagResultInfo(),
        DiagItemType.KAKAO_REST to DiagResultInfo()
    )

    private val diagOkHttpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun updateDiagBadge(badgeView: TextView, status: DiagStatus, text: String) {
        badgeView.text = text
        when (status) {
            DiagStatus.WAITING -> {
                badgeView.setTextColor(Color.parseColor("#9E9E9E"))
                badgeView.setBackgroundColor(Color.TRANSPARENT)
            }
            DiagStatus.PROGRESS -> {
                badgeView.setTextColor(Color.parseColor("#00E5FF"))
                badgeView.setBackgroundColor(Color.parseColor("#2200E5FF"))
            }
            DiagStatus.SUCCESS -> {
                badgeView.setTextColor(Color.parseColor("#4CAF50"))
                badgeView.setBackgroundColor(Color.parseColor("#224CAF50"))
            }
            DiagStatus.WARNING -> {
                badgeView.setTextColor(Color.parseColor("#FFA000"))
                badgeView.setBackgroundColor(Color.parseColor("#22FFA000"))
            }
            DiagStatus.ERROR -> {
                badgeView.setTextColor(Color.parseColor("#FF5252"))
                badgeView.setBackgroundColor(Color.parseColor("#22FF5252"))
            }
        }
        badgeView.setPadding(16, 6, 16, 6)
    }

    private fun setupConnectionDiagnostics() {
        binding.btnTestConnection.setOnClickListener {
            android.util.Log.d("CarrotNavi", "btnTestConnection clicked")
            runConnectionDiagnostics()
        }

        binding.layoutDiagNetwork.setOnClickListener {
            showDiagGuideDialog(DiagItemType.NETWORK)
        }
        binding.layoutDiagTmap.setOnClickListener {
            showDiagGuideDialog(DiagItemType.TMAP)
        }
        binding.layoutDiagKakaoNative.setOnClickListener {
            showDiagGuideDialog(DiagItemType.KAKAO_NATIVE)
        }
        binding.layoutDiagKakaoRest.setOnClickListener {
            showDiagGuideDialog(DiagItemType.KAKAO_REST)
        }

        if (intent.getBooleanExtra("run_diag", false)) {
            binding.root.postDelayed({
                android.util.Log.d("CarrotNavi", "Auto-triggering runConnectionDiagnostics via intent extra")
                runConnectionDiagnostics()
            }, 600)
        }
    }

    private fun runConnectionDiagnostics() {
        val sharedPref = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)

        val tmapAppKey = binding.etAppKey.text.toString().trim()
        val kakaoNativeAppKey = binding.etKakaoNativeAppKey.text.toString().trim()
        val kakaoRestApiKey = binding.etKakaoRestApiKey.text.toString().trim()

        // 현재 입력된 키를 SharedPreferences에 즉시 저장
        sharedPref.edit().apply {
            putString("APP_KEY", tmapAppKey)
            putString("KAKAO_NATIVE_APP_KEY", kakaoNativeAppKey)
            putString("KAKAO_REST_API_KEY", kakaoRestApiKey)
            apply()
        }

        binding.btnTestConnection.isEnabled = false
        binding.btnTestConnection.text = "진단 테스트 진행 중..."
        binding.llTestProgress.visibility = View.VISIBLE
        binding.tvTestProgress.text = "네트워크 및 API 키 진단 중..."
        binding.llTestResults.visibility = View.VISIBLE
        binding.scrollView.post {
            binding.scrollView.fullScroll(View.FOCUS_DOWN)
        }

        updateDiagBadge(binding.tvDiagNetworkBadge, DiagStatus.PROGRESS, "확인 중...")
        binding.tvDiagNetworkDetail.text = "네트워크 연결 상태 및 외부 통신 점검 중..."

        updateDiagBadge(binding.tvDiagTmapBadge, DiagStatus.PROGRESS, "인증 중...")
        binding.tvDiagTmapDetail.text = "Tmap SDK 라이선스 서버 인증 확인 중..."

        updateDiagBadge(binding.tvDiagKakaoNativeBadge, DiagStatus.PROGRESS, "인증 중...")
        binding.tvDiagKakaoNativeDetail.text = "Kakao KNSDK 서버 라이선스 인증 확인 중..."

        updateDiagBadge(binding.tvDiagKakaoRestBadge, DiagStatus.PROGRESS, "검색 확인 중...")
        binding.tvDiagKakaoRestDetail.text = "Kakao Local REST API 통신 점검 중..."

        var completedCount = 0
        val totalTests = 4

        fun onSingleTestFinished() {
            completedCount++
            if (completedCount >= totalTests) {
                binding.btnTestConnection.isEnabled = true
                binding.btnTestConnection.text = "네트워크 및 API 키 재테스트"
                binding.llTestProgress.visibility = View.GONE
                Toast.makeText(this@MainActivity, "네트워크 및 API 키 진단이 완료되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        // 1. 네트워크 통신 점검
        testNetworkConnectivity {
            runOnUiThread { onSingleTestFinished() }
        }

        // 2. Tmap App Key 점검
        testTmapAppKey(tmapAppKey) {
            runOnUiThread { onSingleTestFinished() }
        }

        // 3. Kakao Native App Key 점검
        testKakaoNativeAppKey(kakaoNativeAppKey) {
            runOnUiThread { onSingleTestFinished() }
        }

        // 4. Kakao REST API Key 점검
        testKakaoRestApiKey(kakaoRestApiKey) {
            runOnUiThread { onSingleTestFinished() }
        }
    }

    private fun testNetworkConnectivity(onFinished: () -> Unit) {
        val isAvailable = NetworkUtil.isNetworkAvailable(this)
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }

        val transport = when {
            caps == null -> "네트워크 없음"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "모바일 데이터 (LTE/5G)"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "이더넷"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && caps.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> "USB 테더링"
            else -> "기타 네트워크"
        }
        val localIp = getLocalIpAddress() ?: "IP 미할당"

        if (!isAvailable) {
            runOnUiThread {
                updateDiagBadge(binding.tvDiagNetworkBadge, DiagStatus.ERROR, "연결 없음")
                val msg = "활성 인터넷 연결이 없습니다. Wi-Fi 또는 테더링을 연결해주세요."
                binding.tvDiagNetworkDetail.text = msg
                diagResults[DiagItemType.NETWORK]?.apply {
                    status = DiagStatus.ERROR
                    badgeText = "연결 없음"
                    detailText = msg
                }
                onFinished()
            }
            return
        }

        val startTime = System.currentTimeMillis()
        val request = okhttp3.Request.Builder()
            .url("https://dapi.kakao.com")
            .head()
            .build()

        diagOkHttpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                runOnUiThread {
                    updateDiagBadge(binding.tvDiagNetworkBadge, DiagStatus.WARNING, "외부망 통신 불가")
                    val msg = "로컬 네트워크($transport, IP: $localIp)는 연결되었으나 외부 인터넷 응답 실패 (${e.message})"
                    binding.tvDiagNetworkDetail.text = msg
                    diagResults[DiagItemType.NETWORK]?.apply {
                        status = DiagStatus.WARNING
                        badgeText = "외부망 통신 불가"
                        detailText = msg
                        rawErrorMessage = e.message
                    }
                    onFinished()
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val rtt = System.currentTimeMillis() - startTime
                response.close()
                runOnUiThread {
                    updateDiagBadge(binding.tvDiagNetworkBadge, DiagStatus.SUCCESS, "정상 연결")
                    val msg = "인터넷 정상 통신 ($transport / 지연: ${rtt}ms / 로컬 IP: $localIp)"
                    binding.tvDiagNetworkDetail.text = msg
                    diagResults[DiagItemType.NETWORK]?.apply {
                        status = DiagStatus.SUCCESS
                        badgeText = "정상 연결"
                        detailText = msg
                    }
                    onFinished()
                }
            }
        })
    }

    private fun testTmapAppKey(appKey: String, onFinished: () -> Unit) {
        if (appKey.isEmpty()) {
            runOnUiThread {
                updateDiagBadge(binding.tvDiagTmapBadge, DiagStatus.WARNING, "미입력")
                val msg = "Tmap App Key가 입력되지 않았습니다."
                binding.tvDiagTmapDetail.text = msg
                diagResults[DiagItemType.TMAP]?.apply {
                    status = DiagStatus.WARNING
                    badgeText = "미입력"
                    detailText = msg
                }
                onFinished()
            }
            return
        }

        try {
            initialize(this, "", appKey, "", "", object : TmapUISDK.InitializeListener {
                override fun onSuccess() {
                    runOnUiThread {
                        updateDiagBadge(binding.tvDiagTmapBadge, DiagStatus.SUCCESS, "인증 성공")
                        val msg = "Tmap SDK 라이선스 인증 정상 (안전운행 사용 가능)"
                        binding.tvDiagTmapDetail.text = msg
                        diagResults[DiagItemType.TMAP]?.apply {
                            status = DiagStatus.SUCCESS
                            badgeText = "인증 성공"
                            detailText = msg
                        }
                        onFinished()
                    }
                }

                override fun onFail(errorCode: Int, errorMsg: String?) {
                    runOnUiThread {
                        updateDiagBadge(binding.tvDiagTmapBadge, DiagStatus.ERROR, "인증 실패 ($errorCode)")
                        val reason = if (!errorMsg.isNullOrEmpty()) errorMsg else "키 유효성 또는 Tmap 서버 확인 필요"
                        val msg = "Tmap 인증 실패 (코드 $errorCode): $reason"
                        binding.tvDiagTmapDetail.text = msg
                        diagResults[DiagItemType.TMAP]?.apply {
                            status = DiagStatus.ERROR
                            badgeText = "인증 실패 ($errorCode)"
                            detailText = msg
                            rawErrorCode = errorCode.toString()
                            rawErrorMessage = errorMsg
                        }
                        onFinished()
                    }
                }

                override fun savedRouteInfoExists(destinationName: String?) {
                    // 무시
                }
            })
        } catch (e: Throwable) {
            runOnUiThread {
                updateDiagBadge(binding.tvDiagTmapBadge, DiagStatus.ERROR, "초기화 실패")
                val msg = "Tmap SDK 초기화 예외: ${e.message}"
                binding.tvDiagTmapDetail.text = msg
                diagResults[DiagItemType.TMAP]?.apply {
                    status = DiagStatus.ERROR
                    badgeText = "초기화 실패"
                    detailText = msg
                    rawErrorMessage = e.message
                }
                onFinished()
            }
        }
    }

    private fun testKakaoNativeAppKey(nativeAppKey: String, onFinished: () -> Unit) {
        if (nativeAppKey.isEmpty()) {
            runOnUiThread {
                updateDiagBadge(binding.tvDiagKakaoNativeBadge, DiagStatus.WARNING, "미입력")
                val msg = "Kakao Native App Key가 입력되지 않았습니다."
                binding.tvDiagKakaoNativeDetail.text = msg
                diagResults[DiagItemType.KAKAO_NATIVE]?.apply {
                    status = DiagStatus.WARNING
                    badgeText = "미입력"
                    detailText = msg
                }
                onFinished()
            }
            return
        }

        try {
            val dbPath = filesDir.absolutePath + "/knsdk"
            KNSDK.install(application, dbPath)

            KNSDK.initializeWithAppKey(
                nativeAppKey,
                "1.0",
                "diag_user",
                "ko",
                KNLanguageType.KNLanguageType_KOREAN
            ) { error ->
                runOnUiThread {
                    if (error == null) {
                        updateDiagBadge(binding.tvDiagKakaoNativeBadge, DiagStatus.SUCCESS, "인증 성공")
                        val msg = "Kakao 내비 SDK 인증 정상 (경로안내 사용 가능)"
                        binding.tvDiagKakaoNativeDetail.text = msg
                        diagResults[DiagItemType.KAKAO_NATIVE]?.apply {
                            status = DiagStatus.SUCCESS
                            badgeText = "인증 성공"
                            detailText = msg
                            rawErrorCode = null
                            rawErrorMessage = null
                        }
                    } else {
                        updateDiagBadge(binding.tvDiagKakaoNativeBadge, DiagStatus.ERROR, "인증 실패 (${error.code})")
                        val msg = if (!error.msg.isNullOrEmpty()) error.msg else "오류 코드 ${error.code}"
                        val detail = "KNSDK 인증 실패: $msg"
                        binding.tvDiagKakaoNativeDetail.text = detail
                        diagResults[DiagItemType.KAKAO_NATIVE]?.apply {
                            status = DiagStatus.ERROR
                            badgeText = "인증 실패 (${error.code})"
                            detailText = detail
                            rawErrorCode = error.code
                            rawErrorMessage = error.msg
                        }
                    }
                    onFinished()
                }
            }
        } catch (e: Throwable) {
            runOnUiThread {
                updateDiagBadge(binding.tvDiagKakaoNativeBadge, DiagStatus.ERROR, "초기화 실패")
                val msg = "KNSDK 초기화 예외: ${e.message}"
                binding.tvDiagKakaoNativeDetail.text = msg
                diagResults[DiagItemType.KAKAO_NATIVE]?.apply {
                    status = DiagStatus.ERROR
                    badgeText = "초기화 실패"
                    detailText = msg
                    rawErrorMessage = e.message
                }
                onFinished()
            }
        }
    }

    private fun testKakaoRestApiKey(restApiKey: String, onFinished: () -> Unit) {
        if (restApiKey.isEmpty()) {
            runOnUiThread {
                updateDiagBadge(binding.tvDiagKakaoRestBadge, DiagStatus.WARNING, "미입력")
                val msg = "Kakao REST API Key가 입력되지 않았습니다."
                binding.tvDiagKakaoRestDetail.text = msg
                diagResults[DiagItemType.KAKAO_REST]?.apply {
                    status = DiagStatus.WARNING
                    badgeText = "미입력"
                    detailText = msg
                }
                onFinished()
            }
            return
        }

        kakaoSearchApi.searchKeyword("KakaoAK $restApiKey", "서울역").enqueue(object : Callback<KakaoSearchResponse> {
            override fun onResponse(call: Call<KakaoSearchResponse>, response: Response<KakaoSearchResponse>) {
                runOnUiThread {
                    if (response.isSuccessful) {
                        val docCount = response.body()?.documents?.size ?: 0
                        updateDiagBadge(binding.tvDiagKakaoRestBadge, DiagStatus.SUCCESS, "인증 성공")
                        val msg = "Kakao Local 검색 API 통신 정상 ('서울역' 검색 결과: ${docCount}건 확인)"
                        binding.tvDiagKakaoRestDetail.text = msg
                        diagResults[DiagItemType.KAKAO_REST]?.apply {
                            status = DiagStatus.SUCCESS
                            badgeText = "인증 성공"
                            detailText = msg
                        }
                    } else {
                        val code = response.code()
                        if (code == 401 || code == 403) {
                            updateDiagBadge(binding.tvDiagKakaoRestBadge, DiagStatus.ERROR, "인증 실패 ($code)")
                            val msg = "Kakao REST API Key 인증 실패: 올바른 REST API Key인지 확인하세요 (HTTP $code)"
                            binding.tvDiagKakaoRestDetail.text = msg
                            diagResults[DiagItemType.KAKAO_REST]?.apply {
                                status = DiagStatus.ERROR
                                badgeText = "인증 실패 ($code)"
                                detailText = msg
                                rawErrorCode = code.toString()
                            }
                        } else {
                            updateDiagBadge(binding.tvDiagKakaoRestBadge, DiagStatus.ERROR, "오류 ($code)")
                            val msg = "Kakao 검색 API 오류 응답 (HTTP $code)"
                            binding.tvDiagKakaoRestDetail.text = msg
                            diagResults[DiagItemType.KAKAO_REST]?.apply {
                                status = DiagStatus.ERROR
                                badgeText = "오류 ($code)"
                                detailText = msg
                                rawErrorCode = code.toString()
                            }
                        }
                    }
                    onFinished()
                }
            }

            override fun onFailure(call: Call<KakaoSearchResponse>, t: Throwable) {
                runOnUiThread {
                    updateDiagBadge(binding.tvDiagKakaoRestBadge, DiagStatus.ERROR, "통신 실패")
                    val msg = "Kakao 검색 서버 통신 실패 (${t.message})"
                    binding.tvDiagKakaoRestDetail.text = msg
                    diagResults[DiagItemType.KAKAO_REST]?.apply {
                        status = DiagStatus.ERROR
                        badgeText = "통신 실패"
                        detailText = msg
                        rawErrorMessage = t.message
                    }
                    onFinished()
                }
            }
        })
    }

    private fun getAppKeyHash(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                val signingInfo = packageInfo.signingInfo
                val signatures = if (signingInfo != null) {
                    if (signingInfo.hasMultipleSigners()) {
                        signingInfo.apkContentsSigners
                    } else {
                        signingInfo.signingCertificateHistory
                    }
                } else null

                if (!signatures.isNullOrEmpty()) {
                    val md = MessageDigest.getInstance("SHA-1")
                    md.update(signatures[0].toByteArray())
                    Base64.encodeToString(md.digest(), Base64.NO_WRAP)
                } else {
                    "서명 정보를 찾을 수 없습니다."
                }
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNATURES
                )
                val signatures = packageInfo.signatures
                if (!signatures.isNullOrEmpty()) {
                    val md = MessageDigest.getInstance("SHA-1")
                    md.update(signatures[0].toByteArray())
                    Base64.encodeToString(md.digest(), Base64.NO_WRAP)
                } else {
                    "서명 정보를 찾을 수 없습니다."
                }
            }
        } catch (e: Exception) {
            "키 해시 추출 실패: ${e.message}"
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(this, "${label}이(가) 클립보드에 복사되었습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun showDiagGuideDialog(type: DiagItemType) {
        val info = diagResults[type] ?: DiagResultInfo()
        val dialogView = layoutInflater.inflate(R.layout.dialog_diag_guide, null)

        val tvTitle = dialogView.findViewById<TextView>(R.id.tvDiagGuideTitle)
        val tvBadge = dialogView.findViewById<TextView>(R.id.tvDiagGuideBadge)
        val tvErrorMsg = dialogView.findViewById<TextView>(R.id.tvDiagGuideErrorMsg)
        val tvCause = dialogView.findViewById<TextView>(R.id.tvDiagGuideCause)
        val llKakaoInfo = dialogView.findViewById<View>(R.id.llKakaoAppInfoCard)
        val tvPkgName = dialogView.findViewById<TextView>(R.id.tvDiagPkgName)
        val tvKeyHash = dialogView.findViewById<TextView>(R.id.tvDiagKeyHash)
        val btnCopyPkg = dialogView.findViewById<View>(R.id.btnCopyPkgName)
        val btnCopyHash = dialogView.findViewById<View>(R.id.btnCopyKeyHash)
        val tvGuideSteps = dialogView.findViewById<TextView>(R.id.tvDiagGuideSteps)
        val btnClose = dialogView.findViewById<View>(R.id.btnDiagGuideClose)

        updateDiagBadge(tvBadge, info.status, info.badgeText)

        when (type) {
            DiagItemType.KAKAO_NATIVE -> {
                tvTitle.text = "🚗 Kakao 내비 SDK (경로안내)"
                llKakaoInfo.visibility = View.VISIBLE
                tvPkgName.text = packageName
                val keyHash = getAppKeyHash()
                tvKeyHash.text = keyHash

                btnCopyPkg.setOnClickListener {
                    copyToClipboard("패키지명", packageName)
                }
                btnCopyHash.setOnClickListener {
                    copyToClipboard("키 해시", keyHash)
                }

                if (info.status == DiagStatus.SUCCESS) {
                    tvErrorMsg.text = "Kakao 내비 SDK 인증이 정상 완료되었습니다."
                    tvErrorMsg.setTextColor(Color.parseColor("#4CAF50"))
                    tvCause.text = "카카오 라이선스 서버와 정상 인증되어 경로 안내(TBT) 기능을 바로 사용할 수 있습니다."
                    tvGuideSteps.text = "• 추가 설정 없이 카카오 경로 안내를 정상 이용할 수 있습니다."
                } else {
                    tvErrorMsg.text = if (info.detailText.isNotEmpty() && info.status != DiagStatus.WAITING) {
                        info.detailText
                    } else {
                        "진단 테스트를 실행하지 않았거나 인증에 실패했습니다."
                    }
                    tvErrorMsg.setTextColor(Color.parseColor("#FF5252"))

                    val isC103 = info.rawErrorCode == "C103" || info.detailText.contains("C103") || info.detailText.contains("INVALID_TOKEN")
                    if (isC103) {
                        tvCause.text = "카카오 인증 서버에 등록된 [패키지명] 또는 [키 해시]가 일치하지 않거나, 입력된 키가 '네이티브 앱 키'가 아닙니다.\n(또는 해당 카카오 개발자 앱에 카카오내비 SDK 사용 권한이 비활성화된 상태일 수 있습니다.)"
                    } else {
                        tvCause.text = "카카오 내비 SDK 인증 서버 응답 실패 또는 초기화 오류입니다. 앱 키 및 네트워크 상태를 확인하세요."
                    }

                    tvGuideSteps.text = """
1. https://developers.kakao.com 접속 후 로그인합니다.
2. [내 애플리케이션] > [앱 설정] > [플랫폼] 메뉴로 이동합니다.
3. [Android 플랫폼 등록/수정]에서 위의 [패키지명]과 [키 해시]를 각각 [복사]하여 붙여넣고 저장합니다.
4. [앱 키] 메뉴에서 반드시 '네이티브 앱 키'를 복사하여 본 앱의 Kakao Native App Key에 입력하세요 (REST API 키와 혼동 주의).
5. 저장 후 1~2분 뒤 본 앱에서 [재테스트]를 눌러 인증 성공 여부를 확인하세요.
""".trimIndent()
                }
            }

            DiagItemType.TMAP -> {
                tvTitle.text = "🗺️ Tmap SDK (안전운행)"
                llKakaoInfo.visibility = View.GONE

                if (info.status == DiagStatus.SUCCESS) {
                    tvErrorMsg.text = "Tmap SDK 라이선스 인증이 정상 완료되었습니다."
                    tvErrorMsg.setTextColor(Color.parseColor("#4CAF50"))
                    tvCause.text = "Tmap 안전운행 모드(SDI/단속카메라/제한속도 경고)를 정상 사용할 수 있습니다."
                    tvGuideSteps.text = "• 정상 동작 중입니다. 별도 조치가 필요하지 않습니다."
                } else {
                    tvErrorMsg.text = if (info.detailText.isNotEmpty() && info.status != DiagStatus.WAITING) {
                        info.detailText
                    } else {
                        "Tmap App Key가 입력되지 않았거나 인증에 실패했습니다."
                    }
                    tvErrorMsg.setTextColor(Color.parseColor("#FF5252"))
                    tvCause.text = "SK OpenAPI 포털 또는 Tmap 서버에서 App Key 검증에 실패했습니다.\n키 오타, 유효기간 만료, 무료 트래픽 초과 여부를 확인하세요."
                    tvGuideSteps.text = """
1. SK OpenAPI 포털(https://openapi.sk.com) 또는 TMAP Developer에 로그인합니다.
2. 발급받은 App Key의 유효기간 및 Tmap API 권한 활성화 여부를 확인합니다.
3. 입력된 App Key 문자열에 앞뒤 공백이나 오타가 없는지 점검하세요.
""".trimIndent()
                }
            }

            DiagItemType.KAKAO_REST -> {
                tvTitle.text = "🔍 Kakao REST API (목적지 검색)"
                llKakaoInfo.visibility = View.GONE

                if (info.status == DiagStatus.SUCCESS) {
                    tvErrorMsg.text = info.detailText
                    tvErrorMsg.setTextColor(Color.parseColor("#4CAF50"))
                    tvCause.text = "카카오 Local 장소/주소 검색 API 서버와 정상 통신 중입니다."
                    tvGuideSteps.text = "• 목적지 검색 및 외부 지도 공유 연동이 정상 작동합니다."
                } else {
                    tvErrorMsg.text = if (info.detailText.isNotEmpty() && info.status != DiagStatus.WAITING) {
                        info.detailText
                    } else {
                        "Kakao REST API Key가 입력되지 않았거나 통신에 실패했습니다."
                    }
                    tvErrorMsg.setTextColor(Color.parseColor("#FF5252"))
                    tvCause.text = "카카오 Local 검색 API 호출 시 HTTP 401(인증 실패) 또는 403 오류가 발생했습니다.\nREST API 키 자리에 네이티브 앱 키를 잘못 넣었거나 키가 유효하지 않습니다."
                    tvGuideSteps.text = """
1. 카카오 디벨로퍼스(https://developers.kakao.com)의 [앱 키] 메뉴로 이동합니다.
2. 4가지 키 중 반드시 'REST API 키'를 복사하여 세 번째 입력란에 붙여넣으세요.
3. 네이티브 앱 키와 REST API 키는 서로 다른 문자열이므로 혼동에 주의하세요.
""".trimIndent()
                }
            }

            DiagItemType.NETWORK -> {
                tvTitle.text = "🌐 인터넷 네트워크"
                llKakaoInfo.visibility = View.GONE

                if (info.status == DiagStatus.SUCCESS) {
                    tvErrorMsg.text = info.detailText
                    tvErrorMsg.setTextColor(Color.parseColor("#4CAF50"))
                    tvCause.text = "기기가 외부 인터넷(카카오/SK 서버)과 원활히 통신하고 있습니다."
                    tvGuideSteps.text = "• 지도 데이터 다운로드 및 API 인증을 위한 네트워크가 준비되어 있습니다."
                } else {
                    tvErrorMsg.text = if (info.detailText.isNotEmpty() && info.status != DiagStatus.WAITING) {
                        info.detailText
                    } else {
                        "활성 네트워크가 없거나 외부 통신이 원활하지 않습니다."
                    }
                    tvErrorMsg.setTextColor(Color.parseColor("#FF5252"))
                    tvCause.text = "Wi-Fi 또는 모바일 데이터 연결이 끊겼거나, 로컬 공유기에는 연결되었으나 외부 인터넷 회선이 차단된 상태입니다."
                    tvGuideSteps.text = """
1. 기기의 Wi-Fi 또는 모바일 데이터(LTE/5G) 연결을 확인하세요.
2. CarlinKit/안드로이드 오토 환경인 경우 스마트폰의 핫스팟 또는 USB 테더링이 정상 작동하는지 확인하세요.
3. 사설 VPN, 프록시, 또는 사내 방화벽이 외부 서버 접속을 차단하고 있지 않은지 확인하세요.
""".trimIndent()
                }
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}
