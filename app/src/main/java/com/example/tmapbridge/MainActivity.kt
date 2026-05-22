package com.example.tmapbridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.compose.ui.viewinterop.AndroidView
import android.view.View
import android.view.LayoutInflater
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.lazy.*
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

class MainActivity : AppCompatActivity() {

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
                            },
                            onExitClick = {
                                val serviceIntent = Intent(this@MainActivity, TmapService::class.java)
                                stopService(serviceIntent)
                                finish()
                            },
                            appLogs = TmapDataManager.appLogs.collectAsState().value
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

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun DriveScreen(
        authStatus: AuthStatus, 
        driveData: DriveData, 
        satelliteCount: Int,
        onChangeKeyClick: () -> Unit,
        onExitClick: () -> Unit,
        appLogs: List<String>
    ) {
        var showLogs by remember { mutableStateOf(false) }

        if (showLogs) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
            ModalBottomSheet(
                onDismissRequest = { showLogs = false },
                sheetState = sheetState
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("실시간 로그 뷰어 (최근 100개)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(appLogs) { log ->
                            Text(text = log, fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))
                            Divider(color = Color.DarkGray, thickness = 0.5.dp)
                        }
                    }
                }
            }
        }

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

            // Middle Section (TMAP Map)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 16.dp)
            ) {
                val mapLoaded = remember { mutableStateOf(false) }
                
                AndroidView(
                    factory = { ctx ->
                        val view = LayoutInflater.from(ctx).inflate(R.layout.map_layout, null, false)
                        
                        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: android.view.View) {
                                val fragmentManager = (ctx as AppCompatActivity).supportFragmentManager
                                var fragment = fragmentManager.findFragmentById(R.id.map_fragment_container) as? com.tmapmobility.tmap.tmapsdk.ui.fragment.NavigationFragment
                                
                                if (fragment == null) {
                                    fragment = TmapUISDK.getFragment()
                                    fragmentManager.beginTransaction()
                                        .replace(R.id.map_fragment_container, fragment)
                                        .commitNowAllowingStateLoss()
                                }
                                
                                // Fragment View가 완전히 생성된 후 TMAP SDK 초기화를 진행합니다 (가이드 권장 순서)
                                val currentAppKey = ctx.getSharedPreferences("TmapBridgePrefs", Context.MODE_PRIVATE).getString("APP_KEY", "") ?: ""
                                
                                TmapUISDK.Companion.initialize(ctx, "", currentAppKey, "", "", object : TmapUISDK.InitializeListener {
                                    override fun onSuccess() {
                                        Log.d("MainActivity", "TMAP SDK Initialized successfully")
                                        TmapDataManager.authStatus.value = AuthStatus.SUCCESS
                                        
                                        val serviceIntent = Intent(ctx, TmapService::class.java)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            ctx.startForegroundService(serviceIntent)
                                        } else {
                                            ctx.startService(serviceIntent)
                                        }
                                        
                                        // SurfaceView Compose 버그 우회: 지도 UI가 화면에 먼저 배치되도록 레이아웃 강제 갱신
                                        Handler(Looper.getMainLooper()).postDelayed({
                                            mapLoaded.value = true
                                            
                                            // 레이아웃이 완전히 갱신되고 지도 엔진이 준비될 시간을 준 후 여유롭게 안전운행모드 시작
                                            Handler(Looper.getMainLooper()).postDelayed({
                                                fragment?.startSafeDrive()
                                            }, 1500)
                                        }, 500)
                                    }

                                        override fun onFail(errorCode: Int, errorMsg: String?) {
                                            Log.e("MainActivity", "TMAP SDK Init failed: $errorCode - $errorMsg")
                                            TmapDataManager.authStatus.value = AuthStatus.FAILED
                                        }

                                        override fun savedRouteInfoExists(dest: String?) {}
                                    }, null)
                            }

                            override fun onViewDetachedFromWindow(v: android.view.View) {
                                // optional cleanup
                            }
                        })
                        
                        view
                    },
                    update = { view ->
                        // mapLoaded 상태가 변경되면 recomposition이 일어나고, view에 대해 강제 레이아웃을 요청합니다.
                        if (mapLoaded.value) {
                            view.requestLayout()
                            view.invalidate()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                
                // 지도 터치 조작 방지 오버레이
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                )
            }

            // Bottom Section (Buttons)
            Column(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { showLogs = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                ) {
                    Text("로그 보기", fontSize = 16.sp, color = Color.White)
                }
                Spacer(modifier = Modifier.height(16.dp))
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
