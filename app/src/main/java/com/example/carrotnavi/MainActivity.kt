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
                binding.tvDiagNetworkDetail.text = "활성 인터넷 연결이 없습니다. Wi-Fi 또는 테더링을 연결해주세요."
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
                    binding.tvDiagNetworkDetail.text = "로컬 네트워크($transport, IP: $localIp)는 연결되었으나 외부 인터넷 응답 실패 (${e.message})"
                    onFinished()
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val rtt = System.currentTimeMillis() - startTime
                response.close()
                runOnUiThread {
                    updateDiagBadge(binding.tvDiagNetworkBadge, DiagStatus.SUCCESS, "정상 연결")
                    binding.tvDiagNetworkDetail.text = "인터넷 정상 통신 ($transport / 지연: ${rtt}ms / 로컬 IP: $localIp)"
                    onFinished()
                }
            }
        })
    }

    private fun testTmapAppKey(appKey: String, onFinished: () -> Unit) {
        if (appKey.isEmpty()) {
            runOnUiThread {
                updateDiagBadge(binding.tvDiagTmapBadge, DiagStatus.WARNING, "미입력")
                binding.tvDiagTmapDetail.text = "Tmap App Key가 입력되지 않았습니다."
                onFinished()
            }
            return
        }

        try {
            initialize(this, "", appKey, "", "", object : TmapUISDK.InitializeListener {
                override fun onSuccess() {
                    runOnUiThread {
                        updateDiagBadge(binding.tvDiagTmapBadge, DiagStatus.SUCCESS, "인증 성공")
                        binding.tvDiagTmapDetail.text = "Tmap SDK 라이선스 인증 정상 (안전운행 사용 가능)"
                        onFinished()
                    }
                }

                override fun onFail(errorCode: Int, errorMsg: String?) {
                    runOnUiThread {
                        updateDiagBadge(binding.tvDiagTmapBadge, DiagStatus.ERROR, "인증 실패 ($errorCode)")
                        val reason = if (!errorMsg.isNullOrEmpty()) errorMsg else "키 유효성 또는 Tmap 서버 확인 필요"
                        binding.tvDiagTmapDetail.text = "Tmap 인증 실패 (코드 $errorCode): $reason"
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
                binding.tvDiagTmapDetail.text = "Tmap SDK 초기화 예외: ${e.message}"
                onFinished()
            }
        }
    }

    private fun testKakaoNativeAppKey(nativeAppKey: String, onFinished: () -> Unit) {
        if (nativeAppKey.isEmpty()) {
            runOnUiThread {
                updateDiagBadge(binding.tvDiagKakaoNativeBadge, DiagStatus.WARNING, "미입력")
                binding.tvDiagKakaoNativeDetail.text = "Kakao Native App Key가 입력되지 않았습니다."
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
                        binding.tvDiagKakaoNativeDetail.text = "Kakao 내비 SDK 인증 정상 (경로안내 사용 가능)"
                    } else {
                        updateDiagBadge(binding.tvDiagKakaoNativeBadge, DiagStatus.ERROR, "인증 실패 (${error.code})")
                        val msg = if (!error.msg.isNullOrEmpty()) error.msg else "오류 코드 ${error.code}"
                        binding.tvDiagKakaoNativeDetail.text = "KNSDK 인증 실패: $msg"
                    }
                    onFinished()
                }
            }
        } catch (e: Throwable) {
            runOnUiThread {
                updateDiagBadge(binding.tvDiagKakaoNativeBadge, DiagStatus.ERROR, "초기화 실패")
                binding.tvDiagKakaoNativeDetail.text = "KNSDK 초기화 예외: ${e.message}"
                onFinished()
            }
        }
    }

    private fun testKakaoRestApiKey(restApiKey: String, onFinished: () -> Unit) {
        if (restApiKey.isEmpty()) {
            runOnUiThread {
                updateDiagBadge(binding.tvDiagKakaoRestBadge, DiagStatus.WARNING, "미입력")
                binding.tvDiagKakaoRestDetail.text = "Kakao REST API Key가 입력되지 않았습니다."
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
                        binding.tvDiagKakaoRestDetail.text = "Kakao Local 검색 API 통신 정상 ('서울역' 검색 결과: ${docCount}건 확인)"
                    } else {
                        val code = response.code()
                        if (code == 401 || code == 403) {
                            updateDiagBadge(binding.tvDiagKakaoRestBadge, DiagStatus.ERROR, "인증 실패 ($code)")
                            binding.tvDiagKakaoRestDetail.text = "Kakao REST API Key 인증 실패: 올바른 REST API Key인지 확인하세요 (HTTP $code)"
                        } else {
                            updateDiagBadge(binding.tvDiagKakaoRestBadge, DiagStatus.ERROR, "오류 ($code)")
                            binding.tvDiagKakaoRestDetail.text = "Kakao 검색 API 오류 응답 (HTTP $code)"
                        }
                    }
                    onFinished()
                }
            }

            override fun onFailure(call: Call<KakaoSearchResponse>, t: Throwable) {
                runOnUiThread {
                    updateDiagBadge(binding.tvDiagKakaoRestBadge, DiagStatus.ERROR, "통신 실패")
                    binding.tvDiagKakaoRestDetail.text = "Kakao 검색 서버 통신 실패 (${t.message})"
                    onFinished()
                }
            }
        })
    }
}
