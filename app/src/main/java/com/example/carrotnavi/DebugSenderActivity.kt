package com.example.carrotnavi

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class DebugSenderActivity : AppCompatActivity() {

    private val fieldsMap = mutableMapOf<String, EditText>()
    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var llFieldsContainer: LinearLayout
    private lateinit var etJsonPreview: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_sender)

        etIp = findViewById(R.id.etIp)
        etPort = findViewById(R.id.etPort)
        llFieldsContainer = findViewById(R.id.llFieldsContainer)
        etJsonPreview = findViewById(R.id.etJsonPreview)

        val btnGenerateJson = findViewById<Button>(R.id.btnGenerateJson)
        val btnSendUdp = findViewById<Button>(R.id.btnSendUdp)
        val btnStopUdp = findViewById<Button>(R.id.btnStopUdp)
        val btnBack = findViewById<ImageButton>(R.id.btnBack)

        setupFields()

        btnBack.setOnClickListener {
            finish()
        }

        btnGenerateJson.setOnClickListener {
            generateJson()
        }

        btnSendUdp.setOnClickListener {
            startUdpLoop()
        }

        btnStopUdp.setOnClickListener {
            stopUdpLoop()
        }

        generateJson()
    }

    override fun onResume() {
        super.onResume()
        val sharedPref = getSharedPreferences("CarrotNaviPrefs", android.content.Context.MODE_PRIVATE)
        sharedPref.edit().putBoolean("IS_DEBUG_MODE", true).apply()
    }

    override fun onPause() {
        super.onPause()
        val sharedPref = getSharedPreferences("CarrotNaviPrefs", android.content.Context.MODE_PRIVATE)
        sharedPref.edit().putBoolean("IS_DEBUG_MODE", false).apply()
        stopUdpLoop()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopUdpLoop()
    }

    private fun addField(category: String, key: String, desc: String, defaultVal: String) {
        if (llFieldsContainer.childCount == 0 || fieldsMap.isEmpty()) {
            // First item or new category
            addCategoryHeader(category)
        } else if (!fieldsMap.containsKey(key)) {
            // If we need a way to group, we could keep track of current category, 
            // but for simplicity we'll just add category header if it's not the same as previous.
        }

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.item_debug_field, llFieldsContainer, false)
        
        val tvKey = view.findViewById<TextView>(R.id.tvKey)
        val tvDesc = view.findViewById<TextView>(R.id.tvDesc)
        val etValue = view.findViewById<EditText>(R.id.etValue)

        tvKey.text = key
        tvDesc.text = desc
        etValue.setText(defaultVal)

        llFieldsContainer.addView(view)
        fieldsMap[key] = etValue
    }

    private var currentCategory = ""
    
    private fun addCategoryHeader(category: String) {
        val tv = TextView(this).apply {
            text = category
            setTextColor(android.graphics.Color.parseColor("#FFC107"))
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 32, 0, 8)
        }
        llFieldsContainer.addView(tv)
    }

    private fun addFieldWithHeader(category: String, key: String, desc: String, defaultVal: String) {
        if (currentCategory != category) {
            addCategoryHeader(category)
            currentCategory = category
        }
        addField(category, key, desc, defaultVal)
    }

    private fun setupFields() {
        // Meta
        addFieldWithHeader("메타데이터 & 기본", "carrotIndex", "패킷 번호 (자동증가)", "0")
        addFieldWithHeader("메타데이터 & 기본", "navitype", "내비게이션 소스", "tmap")
        addFieldWithHeader("메타데이터 & 기본", "carrotCmd", "오픈파일럿 제어 명령", "")
        addFieldWithHeader("메타데이터 & 기본", "carrotArg", "오픈파일럿 제어 인자", "")

        // SDI
        addFieldWithHeader("안전운행 (SDI)", "nRoadLimitSpeed", "현재 도로 제한속도", "100")
        addFieldWithHeader("안전운행 (SDI)", "roadcate", "도로종별 (0:고속,1:도,2:일반)", "0")
        addFieldWithHeader("안전운행 (SDI)", "nSdiType", "1차 이벤트 타입", "0")
        addFieldWithHeader("안전운행 (SDI)", "nSdiSpeedLimit", "1차 이벤트 제한속도", "100")
        addFieldWithHeader("안전운행 (SDI)", "nSdiDist", "1차 이벤트 남은 거리", "500")
        addFieldWithHeader("안전운행 (SDI)", "nSdiSection", "구간단속 여부 (1:예, 0:아니오)", "1")
        addFieldWithHeader("안전운행 (SDI)", "nSdiBlockType", "구간단속 상태 (1:시, 2:진, 3:종)", "2")
        addFieldWithHeader("안전운행 (SDI)", "nSdiBlockSpeed", "구간단속 제한 속도", "100")
        addFieldWithHeader("안전운행 (SDI)", "nSdiBlockAverageSpeed", "현재 평균속도", "95")
        addFieldWithHeader("안전운행 (SDI)", "nSdiBlockDist", "구간단속 남은 거리", "2000")
        addFieldWithHeader("안전운행 (SDI)", "nSdiPlusType", "2차 이벤트 타입", "0")
        addFieldWithHeader("안전운행 (SDI)", "nSdiPlusSpeedLimit", "2차 이벤트 제한속도", "0")
        addFieldWithHeader("안전운행 (SDI)", "nSdiPlusDist", "2차 이벤트 남은 거리", "0")

        // TBT
        addFieldWithHeader("경로안내 (TBT)", "nTBTTurnType", "회전 동작 종류 (12:좌, 13:우)", "0")
        addFieldWithHeader("경로안내 (TBT)", "szTBTMainText", "회전 안내 텍스트", "")
        addFieldWithHeader("경로안내 (TBT)", "nTBTDist", "회전 지점 남은 거리", "0")
        addFieldWithHeader("경로안내 (TBT)", "szNearDirName", "1차 진출 지명", "")
        addFieldWithHeader("경로안내 (TBT)", "szFarDirName", "2차 진출 지명", "")
        addFieldWithHeader("경로안내 (TBT)", "nTBTNextRoadWidth", "진입 도로 너비", "0")
        addFieldWithHeader("경로안내 (TBT)", "nTBTTurnTypeNext", "다음 회전 동작 종류", "0")
        addFieldWithHeader("경로안내 (TBT)", "nTBTDistNext", "다음 회전 지점 거리", "0")

        // Dest
        addFieldWithHeader("목적지 (Dest)", "szGoalName", "목적지 명칭", "집")
        addFieldWithHeader("목적지 (Dest)", "goalPosX", "목적지 경도", "127.1234")
        addFieldWithHeader("목적지 (Dest)", "goalPosY", "목적지 위도", "37.1234")
        addFieldWithHeader("목적지 (Dest)", "nGoPosDist", "목적지 남은 거리(m)", "15000")
        addFieldWithHeader("목적지 (Dest)", "nGoPosTime", "목적지 남은 시간(초)", "1800")

        // Loc
        addFieldWithHeader("위치 (Location)", "nPosSpeed", "차량 현재 속도 (TMap)", "80")
        addFieldWithHeader("위치 (Location)", "gps_speed", "차량 현재 속도 (GPS)", "80.5")
        addFieldWithHeader("위치 (Location)", "szPosRoadName", "현재 주행 중 도로 이름", "경부고속도로")
        addFieldWithHeader("위치 (Location)", "vpPosPointLat", "맵매칭 위도", "37.5665")
        addFieldWithHeader("위치 (Location)", "vpPosPointLon", "맵매칭 경도", "126.9780")
        addFieldWithHeader("위치 (Location)", "nPosAngle", "방위각", "90")
        addFieldWithHeader("위치 (Location)", "accuracy", "GPS 오차", "5")
    }

    private fun generateJson() {
        val json = JSONObject()
        json.put("epochTime", System.currentTimeMillis())

        for ((key, et) in fieldsMap) {
            val value = et.text.toString().trim()
            if (value.isNotEmpty()) {
                // Try to parse as int or float
                try {
                    if (value.contains(".")) {
                        json.put(key, value.toDouble())
                    } else {
                        json.put(key, value.toInt())
                    }
                } catch (e: NumberFormatException) {
                    json.put(key, value)
                }
            }
        }
        etJsonPreview.setText(json.toString(2))
    }

    @Volatile
    private var isSending = false

    private fun startUdpLoop() {
        if (isSending) return
        val ip = etIp.text.toString().trim()
        val portStr = etPort.text.toString().trim()
        if (ip.isEmpty() || portStr.isEmpty()) {
            Toast.makeText(this, "IP와 Port를 확인해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        isSending = true
        findViewById<Button>(R.id.btnSendUdp).isEnabled = false
        findViewById<Button>(R.id.btnStopUdp).isEnabled = true
        Toast.makeText(this, "UDP 전송 시작", Toast.LENGTH_SHORT).show()

        val port = portStr.toIntOrNull() ?: 7706

        thread {
            try {
                val socket = DatagramSocket()
                socket.broadcast = true
                val address = InetAddress.getByName(ip)

                while (isSending) {
                    var currentJsonStr = ""
                    val lock = Object()
                    runOnUiThread {
                        synchronized(lock) {
                            // JSON 생성
                            generateJson()
                            currentJsonStr = etJsonPreview.text.toString().trim()

                            // carrotIndex 증가
                            val idxEt = fieldsMap["carrotIndex"]
                            val currIdx = idxEt?.text?.toString()?.toIntOrNull() ?: 0
                            idxEt?.setText((currIdx + 1).toString())
                            (lock as Object).notify()
                        }
                    }
                    synchronized(lock) {
                        try { (lock as Object).wait(100) } catch (e: Exception) {}
                    }

                    // UI 스레드 작업 후 잠시 대기
                    Thread.sleep(50)

                    if (currentJsonStr.isNotEmpty() && isSending) {
                        try {
                            val bytes = currentJsonStr.toByteArray(Charsets.UTF_8)
                            val packet = DatagramPacket(bytes, bytes.size, address, port)
                            socket.send(packet)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    // UdpSenderService와 비슷하게 약 300ms 주기 (50ms + 250ms)
                    Thread.sleep(250)
                }

                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this@DebugSenderActivity, "오류: ${e.message}", Toast.LENGTH_LONG).show()
                    stopUdpLoop()
                }
            }
        }
    }

    private fun stopUdpLoop() {
        if (!isSending) return
        isSending = false
        runOnUiThread {
            findViewById<Button>(R.id.btnSendUdp).isEnabled = true
            findViewById<Button>(R.id.btnStopUdp).isEnabled = false
            Toast.makeText(this, "UDP 전송 중단", Toast.LENGTH_SHORT).show()
        }
    }
}
