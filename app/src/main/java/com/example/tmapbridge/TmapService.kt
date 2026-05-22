package com.example.tmapbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import androidx.lifecycle.Observer
import com.tmapmobility.tmap.tmapsdk.ui.util.TmapUISDK
import com.tmapmobility.tmap.tmapsdk.ui.data.ObservableRouteData
import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager

class TmapService : Service() {

    private val CHANNEL_ID = "TmapBridgeChannel"
    private val TAG = "TmapService"
    private val OPENPILOT_PORT = 7706
    
    // Broadcast IP
    private val BROADCAST_IP = "255.255.255.255" 

    private var isRunning = AtomicBoolean(false)
    private val gson = Gson()
    private var udpSocket: DatagramSocket? = null
    
    private var locationManager: LocationManager? = null

    // Latest Safe Drive Info (From TMAP EDC)
    private var nRoadLimitSpeed = 0
    private var nSdiType = -1
    private var nSdiSpeedLimit = 0
    private var nSdiDist = -1
    private var nSdiBlockType = -1
    private var nTBTDist = 0
    private var nTBTTurnType = -1
    private var szTBTMainText = ""
    private var nGoPosDist = 0
    
    // Latest Location Info (From Phone GPS)
    private var vpPosPointLat = 0.0
    private var vpPosPointLon = 0.0
    private var nPosAngle = 0f
    private var nPosSpeed = 0f
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createNotification())
        
        try {
            udpSocket = DatagramSocket()
            udpSocket?.broadcast = true
        } catch (e: Exception) {
            Log.e(TAG, "Socket creation failed", e)
        }
        
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        isRunning.set(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startObservingEDC()
        return START_STICKY // Ensures the service stays running
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning.set(false)
        udpSocket?.close()
        
        // Stop Safe Drive Mode and unregister observer
        TmapUISDK.observableEDCData.removeObserver(edcObserver)
        locationManager?.removeUpdates(locationListener)
        TmapUISDK.Companion.finish()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "안전운행모드 서비스",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("안전운행모드")
            .setContentText("오픈파일럿에 주행 정보를 송신 중입니다.")
            .setSmallIcon(R.mipmap.ic_launcher) // Make sure you have this icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private val edcObserver = Observer<Bundle> { bundle ->
        if (bundle != null && isRunning.get()) {
            // Extract necessary values mapped directly to Openpilot expectations
            // Note: Since we don't know the exact keys, we keep the default Tmap keys we guessed.
            nRoadLimitSpeed = bundle.getInt("SPEED_LIMIT", 0) 
            nSdiType = bundle.getInt("SDI_TYPE", -1)
            nSdiSpeedLimit = bundle.getInt("SDI_SPEED_LIMIT", 0)
            nSdiDist = bundle.getInt("SDI_DIST", -1)
            nSdiBlockType = bundle.getInt("SDI_BLOCK_TYPE", -1)
            nTBTDist = bundle.getInt("TBT_DIST", 0)
            nTBTTurnType = bundle.getInt("TBT_TURN_TYPE", -1)
            szTBTMainText = bundle.getString("TBT_MAIN_TEXT", "")
            nGoPosDist = bundle.getInt("GO_POS_DIST", 0)
            
            TmapDataManager.addLog("EDC Event: SDI_TYPE=$nSdiType, SPEED_LIMIT=$nRoadLimitSpeed, TBT=$szTBTMainText")
            
            // Do not override GPS speed/location with EDCData as it's unreliable/missing
            sendCurrentData()
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (isRunning.get()) {
                vpPosPointLat = location.latitude
                vpPosPointLon = location.longitude
                nPosAngle = location.bearing
                
                // location.speed is in m/s. Openpilot/TMAP uses km/h for speed values usually.
                nPosSpeed = location.speed * 3.6f
                
                sendCurrentData()
                
                // Update UI State on Main Thread
                CoroutineScope(Dispatchers.Main).launch {
                    TmapDataManager.driveData.value = DriveData(
                        speed = nPosSpeed.toInt(),
                        speedLimit = nSdiSpeedLimit,
                        sdiType = nSdiType,
                        sdiDist = nSdiDist
                    )
                }
            }
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    private fun startObservingEDC() {
        TmapUISDK.observableEDCData.observeForever(edcObserver)
        
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L, // Request updates every 1 second
                0f,    // regardless of distance
                locationListener
            )
        } catch (e: Exception) {
            Log.e(TAG, "Location request failed", e)
        }
    }

    private fun sendCurrentData() {
        val dataMap = mutableMapOf<String, Any>()
        dataMap["nRoadLimitSpeed"] = nRoadLimitSpeed
        dataMap["nSdiType"] = nSdiType
        dataMap["nSdiSpeedLimit"] = nSdiSpeedLimit
        dataMap["nSdiDist"] = nSdiDist
        dataMap["nSdiBlockType"] = nSdiBlockType
        dataMap["nTBTDist"] = nTBTDist
        dataMap["nTBTTurnType"] = nTBTTurnType
        dataMap["szTBTMainText"] = szTBTMainText
        dataMap["nGoPosDist"] = nGoPosDist
        dataMap["vpPosPointLat"] = vpPosPointLat
        dataMap["vpPosPointLon"] = vpPosPointLon
        dataMap["nPosAngle"] = nPosAngle
        dataMap["nPosSpeed"] = nPosSpeed

        val jsonPayload = gson.toJson(dataMap)
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val address = InetAddress.getByName(BROADCAST_IP)
                val buffer = jsonPayload.toByteArray()
                val packet = DatagramPacket(buffer, buffer.size, address, OPENPILOT_PORT)
                udpSocket?.send(packet)
                Log.d(TAG, "Sent UDP: $jsonPayload")
                TmapDataManager.addLog("UDP: $jsonPayload")
            } catch (e: Exception) {
                Log.e(TAG, "UDP Send failed", e)
                TmapDataManager.addLog("UDP Error: ${e.message}")
            }
        }
    }
}
