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
    private lateinit var sharedPref: SharedPreferences
    private lateinit var locationManager: LocationManager
    private lateinit var hudOverlayManager: HudOverlayManager
    
    
    private var lastCameraSpeedLimit = 0
    private var lastRoadType: com.kakaomobility.knsdk.KNRoadType? = null

    
        
    companion object {
        private var knsdkInitialized = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        getSharedPreferences("CarrotNaviPrefs", android.content.Context.MODE_PRIVATE).edit().putBoolean("IS_DEBUG_MODE", false).apply()
        super.onCreate(savedInstanceState)
        
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            100
        )

        sharedPref = getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
        sharedPref.edit().putString("ACTIVE_NAVI", "kakao").apply()

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
                            setupContentAndStart()
                        } else {
                            Log.e("CarrotNavi", "KNSDK Init Failed: ${error.code} / ${error.msg}")
                            Toast.makeText(this@KakaoMapActivity, "지도 초기화 실패: ${error.msg}", Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupContentAndStart() {
        binding = ActivityKakaoMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(binding.root)
        naviView = binding.naviView
        
        val hudBinding = com.example.carrotnavi.databinding.LayoutHudOverlaysBinding.bind(binding.root)
        hudOverlayManager = HudOverlayManager(this@KakaoMapActivity, hudBinding, this@KakaoMapActivity)
        
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

    }

    private fun setupUI() {
        
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (::hudOverlayManager.isInitialized && hudOverlayManager.dispatchTouchEvent(ev)) {
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
        safetyGuide?.let { guide ->
            val limitSpeed = 0
            val safeties = guide.safetiesOnGuide
            if (safeties != null && safeties.isNotEmpty()) {
                val s1 = safeties[0]
                val s1Type = s1.safetyType().value
                val s1Limit = (s1 as? com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety_Camera)?.speedLimit ?: 0
                val s1Dist = s1.location.distFromS
                
                if (s1Limit > 0) {
                    lastCameraSpeedLimit = s1Limit
                }
                
                var s2Type = 0
                var s2Limit = 0
                var s2Dist = 0
                
                if (safeties.size > 1) {
                    val s2 = safeties[1]
                    s2Type = s2.safetyType().value
                    s2Limit = (s2 as? com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety_Camera)?.speedLimit ?: 0
                    s2Dist = s2.location.distFromS
                }
                
                KakaoSdiRepository.updateSafeties(
                    roadLimitSpeed = limitSpeed,
                    sdiType1 = s1Type,
                    speedLimit1 = s1Limit,
                    dist1 = s1Dist,
                    isBlock1 = false,
                    blockAvgSpeed = 0,
                    sdiType2 = s2Type,
                    speedLimit2 = s2Limit,
                    dist2 = s2Dist
                )
                
                runOnUiThread {
                                    }
            } else {
                KakaoSdiRepository.updateSafeties(limitSpeed, 0, 0, 0, false, 0, 0, 0, 0)
                runOnUiThread {
                                    }
            }
        }
    }

    override fun guidanceDidUpdateAroundSafeties(guidance: KNGuidance, safeties: List<KNSafety>?) {
        if(::naviView.isInitialized) naviView.guidanceDidUpdateAroundSafeties(guidance, safeties)
    }

    override fun guidanceGuideStarted(guidance: KNGuidance) {
        if(::naviView.isInitialized) naviView.guidanceGuideStarted(guidance)
    }

    override fun guidanceGuideEnded(guidance: KNGuidance) {
        if(::naviView.isInitialized) naviView.guidanceGuideEnded(guidance)
    }

    override fun guidanceDidUpdateLocation(guidance: KNGuidance, locationGuide: KNGuide_Location) {
        if(::naviView.isInitialized && !isShowingPreview) naviView.guidanceDidUpdateLocation(guidance, locationGuide)
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
    }
    override fun didFinishPlayVoiceGuide(guidance: KNGuidance, voiceGuide: com.kakaomobility.knsdk.guidance.knguidance.voiceguide.KNGuide_Voice) {
        if(::naviView.isInitialized) naviView.didFinishPlayVoiceGuide(guidance, voiceGuide)
    }
    override fun didUpdateCitsGuide(guidance: KNGuidance, citsGuide: com.kakaomobility.knsdk.guidance.knguidance.citsguide.KNGuide_Cits) {
        if(::naviView.isInitialized) naviView.didUpdateCitsGuide(guidance, citsGuide)
    }

    
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        
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
        
        val goalX = doc.x.toDoubleOrNull() ?: 0.0
        val goalY = doc.y.toDoubleOrNull() ?: 0.0
        val katec = com.kakaomobility.knsdk.KNSDK.convertWGS84ToKATEC(goalX, goalY)
        val goalPoi = KNPOI(doc.place_name.ifEmpty { doc.road_address_name }, katec.x.toInt(), katec.y.toInt(), doc.address_name)

        Toast.makeText(this@KakaoMapActivity, "寃쎈줈 ?먯깋 以?..", Toast.LENGTH_SHORT).show()

        com.kakaomobility.knsdk.KNSDK.makeTripWithStart(startPoi, goalPoi, null) { error, trip ->
            runOnUiThread {
            if (error != null || trip == null) {
                Toast.makeText(this@KakaoMapActivity, "경로 탐색 실패: ${error?.msg ?: "?????녿뒗 ?ㅻ쪟"}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@KakaoMapActivity, "경로 안내를 시작합니다.", Toast.LENGTH_SHORT).show()
                val guidance = com.kakaomobility.knsdk.KNSDK.sharedGuidance()!!
                

                guidance.guideStateDelegate = this@KakaoMapActivity
                guidance.routeGuideDelegate = this@KakaoMapActivity
                guidance.safetyGuideDelegate = this@KakaoMapActivity
                guidance.voiceGuideDelegate = this@KakaoMapActivity
                guidance.citsGuideDelegate = this@KakaoMapActivity
                guidance.locationGuideDelegate = this@KakaoMapActivity

                binding.naviView.initWithGuidance(
                    guidance,
                    trip,
                    com.kakaomobility.knsdk.KNRoutePriority.KNRoutePriority_Recommand,
                    com.kakaomobility.knsdk.KNRouteAvoidOption.KNRouteAvoidOption_None.value
                )
            }
        }
    }
}

    override fun onDestroy() {
        super.onDestroy()
        sharedPref.edit().putString("ACTIVE_NAVI", "tmap").apply()
        if (::hudOverlayManager.isInitialized) hudOverlayManager.onDestroy()
        locationManager.removeUpdates(this)
        KNSDK.sharedGuidance()?.stop()
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
        hudOverlayManager.binding.btnToggleVisibility.visibility = android.view.View.GONE
        hudOverlayManager.binding.btnSearchAddress.visibility = android.view.View.GONE
        hudOverlayManager.binding.btnEditMode.visibility = android.view.View.GONE
        hudOverlayManager.binding.btnRestoreDefaults.visibility = android.view.View.GONE

        binding.btnPreviewStart.setOnClickListener {
            hidePreviewOverlay()
            startRouteGuidance(doc)
        }
        
        binding.btnPreviewCancel.setOnClickListener {
            hidePreviewOverlay()
            finish()
        }
    }

    private fun hidePreviewOverlay() {
        isShowingPreview = false
        binding.llPreviewOverlay.visibility = android.view.View.GONE
        binding.naviView.mapComponent?.mapView?.removeMarkersAll()
        
        
        hudOverlayManager.binding.llSpeedGroup.visibility = android.view.View.VISIBLE
        hudOverlayManager.binding.llStatusGroup.visibility = android.view.View.VISIBLE
        hudOverlayManager.binding.btnToggleVisibility.visibility = android.view.View.VISIBLE
        hudOverlayManager.binding.btnSearchAddress.visibility = android.view.View.VISIBLE
        hudOverlayManager.binding.btnEditMode.visibility = android.view.View.VISIBLE
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
            // 주행 종료 버튼을 눌러 일반 지도 상태(NONE)가 되었을 때,
            // 즉시 안전운행 모드로 다시 복귀시킵니다.
            if (!isFinishing) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!isFinishing) {
                        try { KNSDK.sharedGuidance()?.stop() } catch (e: Exception) {}
                        
                        val destPlaceName = intent.getStringExtra("dest_place_name")
                        if (destPlaceName != null) {
                            val destRoadAddressName = intent.getStringExtra("dest_road_address_name") ?: ""
                            val destAddressName = intent.getStringExtra("dest_address_name") ?: ""
                            val destX = intent.getStringExtra("dest_x") ?: ""
                            val destY = intent.getStringExtra("dest_y") ?: ""
                            
                            val doc = KakaoDocument(destPlaceName, destRoadAddressName, destAddressName, destX, destY)
                            // Start guidance right away
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                startRouteGuidance(doc)
                            }, 1000)
                        } else {
                            startSafeDrivingMode() 
                        }

                    }
                }, 500)
            }
        }
    }

}
