package com.example.tmapbridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.location.GnssStatus
import android.location.LocationManager
import android.util.Log
import com.tmapmobility.tmap.tmapsdk.ui.util.TmapUISDK

class MainActivity : ComponentActivity() {

    private var locationManager: LocationManager? = null
    private var gnssStatusCallback: GnssStatus.Callback? = null

    private fun registerGnssCallback() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            gnssStatusCallback = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    var inUseCount = 0
                    for (i in 0 until status.satelliteCount) {
                        if (status.usedInFix(i)) {
                            inUseCount++
                        }
                    }
                    TmapDataManager.satelliteCount.value = inUseCount
                }
            }
            gnssStatusCallback?.let {
                locationManager?.registerGnssStatusCallback(it, null)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        gnssStatusCallback?.let {
            locationManager?.unregisterGnssStatusCallback(it)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            // startTmapService() -> now handled in onStart callback
        } else {
            Toast.makeText(this, "권한이 거부되어 안전운행모드를 실행할 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AutoUpdater.checkForUpdates(this)
        
        val prefs = getSharedPreferences("TmapBridgePrefs", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("APP_KEY", "") ?: ""

        setContent {
            var appKey by remember { mutableStateOf(savedKey) }
            val isDriving by TmapDataManager.isDriving.collectAsState()
            val authStatus by TmapDataManager.authStatus.collectAsState()
            val driveData by TmapDataManager.driveData.collectAsState()
            val satelliteCount by TmapDataManager.satelliteCount.collectAsState()

            LaunchedEffect(Unit) {
                registerGnssCallback()
                if (savedKey.isNotBlank()) {
                    val hasPerm = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    if (hasPerm) {
                        appKey = savedKey
                        TmapDataManager.isDriving.value = true
                        TmapDataManager.authStatus.value = AuthStatus.LOADING
                        
                        TmapUISDK.Companion.initialize(this@MainActivity, "", appKey, "", "", object : TmapUISDK.InitializeListener {
                            override fun onSuccess() {
                                TmapDataManager.authStatus.value = AuthStatus.SUCCESS
                                val serviceIntent = Intent(this@MainActivity, TmapService::class.java)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    startForegroundService(serviceIntent)
                                } else {
                                    startService(serviceIntent)
                                }
                            }
                            override fun onFail(errorCode: Int, errorMsg: String?) {
                                TmapDataManager.authStatus.value = AuthStatus.FAILED
                            }
                            override fun savedRouteInfoExists(dest: String?) {}
                        }, null)
                    }
                }
            }

            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize().systemBarsPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isDriving) {
                        DriveScreen(
                            authStatus = authStatus, 
                            driveData = driveData,
                            satelliteCount = satelliteCount,
                            onChangeKeyClick = {
                                val serviceIntent = Intent(this@MainActivity, TmapService::class.java)
                                stopService(serviceIntent)
                                TmapDataManager.isDriving.value = false
                                appKey = ""
                            },
                            onExitClick = {
                                val serviceIntent = Intent(this@MainActivity, TmapService::class.java)
                                stopService(serviceIntent)
                                finish()
                            }
                        )
                    } else {
                        SetupScreen(
                            appKey = appKey,
                            onAppKeyChange = { appKey = it },
                            onStartClick = {
                                // Save Key
                                prefs.edit().putString("APP_KEY", appKey).apply()
                                
                                TmapDataManager.isDriving.value = true
                                TmapDataManager.authStatus.value = AuthStatus.LOADING
                                
                                TmapUISDK.Companion.initialize(this@MainActivity, "", appKey, "", "", object : TmapUISDK.InitializeListener {
                                    override fun onSuccess() {
                                        Log.d("MainActivity", "TMAP SDK Initialized successfully")
                                        TmapDataManager.authStatus.value = AuthStatus.SUCCESS
                                        
                                        // Start Background Service
                                        val serviceIntent = Intent(this@MainActivity, TmapService::class.java)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            startForegroundService(serviceIntent)
                                        } else {
                                            startService(serviceIntent)
                                        }
                                    }

                                    override fun onFail(errorCode: Int, errorMsg: String?) {
                                        Log.e("MainActivity", "TMAP SDK Init failed: $errorCode - $errorMsg")
                                        TmapDataManager.authStatus.value = AuthStatus.FAILED
                                    }

                                    override fun savedRouteInfoExists(dest: String?) {
                                    }
                                }, null)
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun SetupScreen(appKey: String, onAppKeyChange: (String) -> Unit, onStartClick: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "APN 브릿지", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(24.dp))
            
            TextField(
                value = appKey,
                onValueChange = onAppKeyChange,
                label = { Text("TMAP App Key 입력") },
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = onStartClick,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("안전운행모드 시작", fontSize = 18.sp)
            }
        }
    }

    @Composable
    fun DriveScreen(
        authStatus: AuthStatus, 
        driveData: DriveData, 
        satelliteCount: Int,
        onChangeKeyClick: () -> Unit,
        onExitClick: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Section (GPS and Auth Status)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // GPS Indicator
                val (gpsIcon, gpsColor, gpsText) = when {
                    satelliteCount >= 8 -> Triple("📶", Color(0xFF4CAF50), "GPS 매우좋음 ($satelliteCount)")
                    satelliteCount >= 4 -> Triple("📶", Color(0xFFFFEB3B), "GPS 양호 ($satelliteCount)")
                    satelliteCount > 0 -> Triple("📶", Color(0xFFFF9800), "GPS 약함 ($satelliteCount)")
                    else -> Triple("📵", Color(0xFFE53935), "GPS 수신불가 (0)")
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = gpsIcon, fontSize = 20.sp, color = gpsColor)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = gpsText, color = gpsColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                
                Spacer(modifier = Modifier.height(16.dp))

                // Connection Status
                val (statusText, statusColor) = when(authStatus) {
                    AuthStatus.LOADING -> Pair("TMAP 연결 중...", Color.Gray)
                    AuthStatus.SUCCESS -> Pair("TMAP 연동됨", Color(0xFF4CAF50)) // Green
                    AuthStatus.FAILED -> Pair("TMAP 연결 실패", Color(0xFFE53935)) // Red
                }
                
                Text(
                    text = "● $statusText",
                    color = statusColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Middle Section (Speed & Warning)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "현재 속도",
                    fontSize = 20.sp,
                    color = Color.LightGray
                )
                
                Text(
                    text = "${driveData.speed}",
                    fontSize = 120.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
                
                Text(
                    text = "km/h",
                    fontSize = 24.sp,
                    color = Color.Gray
                )
                
                Spacer(modifier = Modifier.height(48.dp))
                
                // Warning Card
                if (driveData.sdiType >= 0) {
                    val (warningText, warningColor) = when(driveData.sdiType) {
                        22 -> Pair("과속 방지턱", Color(0xFFFFA000)) // Amber
                        else -> Pair("단속 카메라 (${driveData.speedLimit}km/h)", Color(0xFFE53935)) // Red
                    }
                    
                    Card(
                        colors = CardDefaults.cardColors(containerColor = warningColor),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = warningText,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "${driveData.sdiDist}m 전방",
                                fontSize = 20.sp,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // Bottom Section (Buttons)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                OutlinedButton(
                    onClick = onChangeKeyClick,
                    modifier = Modifier.weight(1f).height(56.dp)
                ) {
                    Text("App Key 변경", fontSize = 16.sp)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Button(
                    onClick = onExitClick,
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                ) {
                    Text("앱 종료", fontSize = 16.sp, color = Color.White)
                }
            }
        }
    }

    private fun checkPermissionsAndStartService() {
        // 백그라운드에서 Github 업데이트 확인 및 다운로드
        AutoUpdater.checkForUpdates(this)

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
            startTmapService()
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startTmapService() {
        val serviceIntent = Intent(this, TmapService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}
