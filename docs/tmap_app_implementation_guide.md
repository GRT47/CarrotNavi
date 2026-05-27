# Tmap SDK 활용 단방향 연동 앱 구현 가이드 (안전운행 모드 전용)

이 문서는 **Tmap API 공식 문서(TmapUISDK)**를 기반으로 팩트체크된 내용을 바탕으로, 오픈파일럿(Carrot 모듈)으로 **안전운전 정보(SDI)**를 단방향으로 쏴주는 안드로이드 앱 구현 가이드입니다. 

사용자가 목적지를 설정하지 않고 주행하는 **"안전운행 모드(Safe Drive Mode)"**만을 100% 활용하는 방식에 초점이 맞춰져 있습니다.

---

## 1. Tmap SDK 초기화 및 안전운행 모드 진입

목적지 안내(Routing) 없이 전방 단속 카메라와 방지턱 정보만 받기 위해서는, `requestRoute()` 대신 **`requestSafeDrive()`**를 호출하여 안전운행 모드로 진입해야 합니다.

### 핵심 구현 코드 (초기화 및 안전운행 실행)
```java
// 1. TmapUISDK 초기화
TmapUISDK.Companion.initialize(this, CLIENT_ID, API_KEY, USER_KEY, DEVICE_KEY, new TmapUISDK.InitializeListener() {
    @Override
    public void onSuccess() {
        Log.e(TAG, "Tmap SDK 초기화 성공");
        
        // 2. 초기화 성공 후, 목적지 없이 '안전운행 모드' 강제 실행
        startSafeDriveMode();
    }

    @Override
    public void onFail(int i, @Nullable String s) {
        Log.e(TAG, "Tmap SDK 초기화 실패: " + s);
    }
    
    @Override
    public void savedRouteInfoExists(@Nullable String dest) { }
});

// 안전운행 모드 실행 함수
private void startSafeDriveMode() {
    // NavigationFragment 객체 가져오기
    NavigationFragment navigationFragment = TmapUISDK.Companion.getFragment();
    
    // UI에 Fragment 부착 (화면에 Tmap을 띄워야 백그라운드 GPS 획득이 원활함)
    getSupportFragmentManager().beginTransaction()
        .add(R.id.tmapUILayout, navigationFragment)
        .commitAllowingStateLoss();

    // 목적지 경로 탐색이 아닌 "안전운행 모드" 요청 API 호출
    navigationFragment.requestSafeDrive(); 
}
```

---

## 2. 안전운행 정보(SDI) 추출 리스너 등록

안전운행 모드가 실행된 후, 실시간으로 변하는 전방 카메라, 방지턱 거리 및 속도 제한 정보를 가로채기 위해 주행 상태 리스너(Drive Status Listener)를 등록합니다.

```kotlin
// 주행 상태 콜백 리스너 등록 (Kotlin 예시)
navigationFragment.addDriveStatusListener(object : DriveStatusListener {
    override fun onSdiStateUpdated(sdiInfo: TmapSdiInfo) {
        // 1. 현재 주행 중인 도로의 기본 제한 속도
        val roadLimitSpeed = sdiInfo.currentRoadLimitSpeed 

        // 2. 전방 이벤트(카메라/방지턱) 정보
        val sdiType = sdiInfo.sdiType 
        val sdiSpeedLimit = sdiInfo.targetSpeedLimit
        val sdiDistance = sdiInfo.distanceToSdi

        // 3. Tmap의 sdiType 코드를 오픈파일럿 규격 코드로 변환 매핑
        val opSdiType = convertTmapToOpenpilotSdiType(sdiType)

        // 4. 구간 단속(Block) 정보 추출 (예시)
        // Tmap API에서 제공하는 구간단속 관련 속성을 기반으로 매핑합니다.
        val isBlockSpeedZone = sdiInfo.isBlockSpeedZone 
        val sdiBlockType = if (isBlockSpeedZone) 2 else 0 // 1:시작, 2:진행중, 3:종료
        val sdiBlockSpeed = sdiInfo.blockSpeedLimit
        val sdiBlockDist = sdiInfo.distanceToBlockEnd

        // 최신 상태를 전역 변수나 Flow에 저장하여 UDP Sender가 가져가게 함
        updateCurrentSdiState(
            roadLimitSpeed, opSdiType, sdiSpeedLimit, sdiDistance,
            sdiBlockType, sdiBlockSpeed, sdiBlockDist
        )
    }
})

// 타입 변환 함수
fun convertTmapToOpenpilotSdiType(tmapType: Int): Int {
    return when(tmapType) {
        TMAP_CAMERA_FIXED -> 1       // 고정식 과속 카메라
        TMAP_CAMERA_MOBILE -> 7      // 이동식 단속 카메라
        TMAP_SPEED_BUMP -> 22        // 과속 방지턱
        else -> 0                    // 이벤트 없음
    }
}
```
*※ 참고: Tmap SDK의 버전에 따라 리스너 인터페이스 명칭(`OnSdiListener`, `DriveStatusListener` 등)이 상이할 수 있으나, 제공되는 핵심 파라미터(제한속도, 카메라 타입, 거리) 구조는 동일합니다.*

---

## 3. JSON 페이로드 생성 및 UDP 발송 (Heartbeat)

오픈파일럿 규격(JSON)으로 변환한 뒤, 백그라운드 스레드에서 초당 2~5회(2~5Hz) 빈도로 오픈파일럿 기기의 IP(포트 `7706`)를 향해 발송합니다. **이 과정이 있어야 오픈파일럿의 'APN'이 켜집니다.**

```kotlin
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.timer

class OpenpilotUdpSender(private val targetIp: String, private val port: Int = 7706) {
    
    private val udpSocket = DatagramSocket()
    private var packetIndex = 0

    // 안전운행 진입 시 호출
    fun startSendingLoop() {
        // 300ms 주기 (약 3.3Hz)로 전송 루프 실행
        timer(period = 300) {
            sendSdiData()
        }
    }

    private fun sendSdiData() {
        try {
            val currentState = getCurrentSdiState()

            val json = JSONObject().apply {
                put("carrotIndex", packetIndex++)
                put("nRoadLimitSpeed", currentState.roadLimitSpeed)
                put("nSdiType", currentState.opSdiType)
                put("nSdiSpeedLimit", currentState.sdiSpeedLimit)
                put("nSdiDist", currentState.sdiDistance)

                // 구간 단속 정보 추가
                if (currentState.sdiBlockType > 0) {
                    put("nSdiBlockType", currentState.sdiBlockType)
                    put("nSdiBlockSpeed", currentState.sdiBlockSpeed)
                    put("nSdiBlockDist", currentState.sdiBlockDist)
                }
            }

            val jsonDataString = json.toString()
            val buffer = jsonDataString.toByteArray(Charsets.UTF_8)
            
            val address = InetAddress.getByName(targetIp)
            val packet = DatagramPacket(buffer, buffer.size, address, port)
            
            udpSocket.send(packet)
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
```

---

## 4. 앱 구현 시 주요 고려사항 (팩트체크 완료)

1. **포어그라운드 서비스 (Foreground Service)**
   - Tmap SDK의 안전운행(Safe Drive) 모드는 화면을 끄거나 앱을 내리면 안드로이드 OS 정책에 의해 GPS 수신 및 콜백이 중단될 수 있습니다. 반드시 앱을 **포어그라운드 서비스**로 띄워서 백그라운드에서도 GPS 수신과 UDP 전송이 멈추지 않도록 조치해야 합니다.

2. **초기화 순서 필수 엄수**
   - Tmap 공식 문서에 따르면, `TmapUISDK.Companion.initialize()` 콜백에서 `onSuccess()`를 받기 전에 내비게이션 기능(requestRoute, requestSafeDrive)을 호출하면 크래시가 발생하거나 무시됩니다. 반드시 초기화 완료 후 안전운행 모드를 호출해야 합니다.

3. **방지턱 vs 카메라 중복 수신 처리**
   - 안전운행 모드에서 여러 이벤트가 겹칠 경우 Tmap은 배열이나 다중 콜백으로 데이터를 줍니다. 오픈파일럿 JSON에는 하나의 이벤트(`nSdiType`)만 담기므로, **안전 우선순위(카메라 > 방지턱) 또는 거리 우선순위**에 따라 앱 단에서 필터링하여 단 1개의 이벤트만 JSON에 담아 전송하도록 로직을 구현해야 합니다.
