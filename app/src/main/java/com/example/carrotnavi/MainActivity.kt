package com.example.carrotnavi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.carrotnavi.databinding.ActivityMainBinding
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.View

class MainActivity : AppCompatActivity() {

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        dumpTmapAudioSettings()
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        // 자동 업데이트 체크
        AutoUpdater.checkForUpdates(this)

        val sharedPref = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
        val savedAppKey = sharedPref.getString("APP_KEY", "")
        val savedTargetIp = sharedPref.getString("TARGET_IP", "255.255.255.255")
        val savedReqBackground = sharedPref.getBoolean("REQ_BACKGROUND", false)
        val savedBlockSpeedOffset = sharedPref.getInt("BLOCK_SPEED_OFFSET", 0)
        val savedDistanceFormatKm = sharedPref.getBoolean("USE_KM_DISTANCE_FORMAT", true)
        
        binding.etAppKey.setText(savedAppKey)
        binding.etTargetIp.setText(savedTargetIp)
        binding.cbBackgroundLocation.isChecked = savedReqBackground
        binding.etBlockSpeedOffset.setText(savedBlockSpeedOffset.toString())
        binding.cbDistanceFormatKm.isChecked = savedDistanceFormatKm

        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            binding.tvAppVersion.text = "버전: ${pInfo.versionName}"
        } catch (e: Exception) {
            binding.tvAppVersion.text = "버전: -"
        }

        // Start Web Server Service
        val webServerIntent = Intent(this, WebServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(webServerIntent)
        } else {
            startService(webServerIntent)
        }

        // Show IP Address
        updateWebServerInfo(sharedPref)

        // 자동 실행 로직
        val shouldAutoStart = intent.getBooleanExtra("auto_start", true)
        if (!savedAppKey.isNullOrEmpty() && shouldAutoStart) {
            checkPermissionsAndStart()
        }

        binding.btnStartNavi.setOnClickListener {
            val appKey = binding.etAppKey.text.toString().trim()
            val targetIp = binding.etTargetIp.text.toString().trim()
            val reqBackground = binding.cbBackgroundLocation.isChecked
            val distanceFormatKm = binding.cbDistanceFormatKm.isChecked
            val blockSpeedOffset = binding.etBlockSpeedOffset.text.toString().toIntOrNull() ?: 0

            if (appKey.isEmpty()) {
                Toast.makeText(this, "App Key를 입력하세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Save to SharedPreferences
            sharedPref.edit().apply {
                putString("APP_KEY", appKey)
                putString("TARGET_IP", targetIp)
                putBoolean("REQ_BACKGROUND", reqBackground)
                putInt("BLOCK_SPEED_OFFSET", blockSpeedOffset)
                putBoolean("USE_KM_DISTANCE_FORMAT", distanceFormatKm)
                apply()
            }

            checkPermissionsAndStart()
        }

        binding.btnRemoteServer.setOnClickListener {
            val layout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(50, 40, 50, 0)
            }

            val etServerUrl = android.widget.EditText(this).apply {
                hint = "서버 주소 (예: http://192.168.0.10:5000)"
                setText(sharedPref.getString("IP_REPORT_SERVER_URL", ""))
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            }

            val etDeviceId = android.widget.EditText(this).apply {
                hint = "기기 ID (예: carrot)"
                setText(sharedPref.getString("DEVICE_ID", "carrot"))
                inputType = android.text.InputType.TYPE_CLASS_TEXT
            }

            val tvInfo = android.widget.TextView(this).apply {
                text = "Docker 리다이렉트 서버를 구축한 경우에만 사용하세요.\n서버 주소가 비어있으면 이 기능은 비활성화됩니다."
                textSize = 12f
                setTextColor(android.graphics.Color.GRAY)
                setPadding(0, 20, 0, 0)
            }

            layout.addView(etServerUrl)
            layout.addView(etDeviceId)
            layout.addView(tvInfo)

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("외부 리다이렉트 서버 설정")
                .setView(layout)
                .setPositiveButton("저장") { _, _ ->
                    val serverUrl = etServerUrl.text.toString().trim()
                    val deviceId = etDeviceId.text.toString().trim()
                    sharedPref.edit()
                        .putString("IP_REPORT_SERVER_URL", serverUrl)
                        .putString("DEVICE_ID", if (deviceId.isEmpty()) "carrot" else deviceId)
                        .apply()
                    updateWebServerInfo(sharedPref)
                    Toast.makeText(this, "설정이 저장되었습니다.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
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
        val intent = Intent(this, MapActivity::class.java)
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
}
