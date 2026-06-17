# TMAP SDK 도로 제한 속도(Speed Limit) 추출 가이드

본 문서는 TMAP SDK를 사용하여 커스텀 내비게이션 앱을 구현할 때, 공식적으로 노출되지 않는 **현재 도로의 제한 속도(Road Limit Speed)**를 내부 코어 엔진에서 직접 추출하는 과정과 그 디코딩 규칙을 상세히 기록한 문서입니다.

## 1. 문제 배경
기본적으로 TMAP UI SDK(`TmapUISDK`)의 데이터 옵저버(`observableEDCData`, `observableRouteData`)가 브로드캐스트하는 `Bundle` 객체 안에는 명시적으로 제한 속도 값을 나타내는 필드가 존재하지 않거나 알기 어려운 형태로 숨겨져 있습니다. 
따라서 단순히 `Bundle`에서 키값을 파싱하는 것만으로는 제한 속도를 화면에 표시할 수 없었습니다.

## 2. SDK 내부 구조 분석 (역공학)
이 문제를 해결하기 위해 Gradle 캐시에 다운로드된 TMAP SDK의 `.aar` 파일들을 직접 추출하고 분석(Decompile)을 진행했습니다.

1. **AAR 아카이브 분석**
   - `tmap-ui-sdk` 내부의 `NavigationFragment` 클래스를 `javap`로 디컴파일 해본 결과, 해당 프래그먼트는 길안내 데이터를 가져오기 위해 내부 코어 엔진인 `SDKManager`를 참조하고 있음을 확인했습니다.
2. **엔진 코어 데이터(`RGData`) 발견**
   - 내비게이션 엔진 코어 파일인 `TmapEngineCommonData`와 `TmapNavigationEngine`을 찾아 분석했습니다.
   - `com.skt.tmap.engine.navigation.data.RGData` 클래스 내부에 `public int nRoadLimitSpeed;` 변수가 존재한다는 사실을 확인했습니다.
   - 또한, `com.skt.tmap.engine.navigation.SDKManager` 객체를 통해 언제든지 `getRecentRGData()` 메서드를 호출하여 최신 `RGData` 객체를 가져올 수 있음을 발견했습니다.

## 3. 해결 방법: Reflection을 통한 엔진 데이터 직접 접근
`TmapNavigationEngine` 패키지는 컴파일 타임에 직접 참조할 경우 접근 제어나 의존성 꼬임 등으로 빌드 에러가 발생할 수 있습니다. 이를 우회하고 안전하게 데이터를 빼내기 위해 **Java Reflection(리플렉션)** 기법을 사용하였습니다.

**Kotlin 코드 적용 예시 (`MainActivity.kt`):**
```kotlin
try {
    // 1. SDKManager 클래스 강제 로드
    val sdkManagerClass = Class.forName("com.skt.tmap.engine.navigation.SDKManager")
    val companionField = sdkManagerClass.getField("Companion")
    val companionObj = companionField.get(null)
    
    // 2. SDKManager 싱글톤 인스턴스 획득
    val getInstanceMethod = companionObj.javaClass.getMethod("getInstance")
    val sdkManager = getInstanceMethod.invoke(companionObj)

    if (sdkManager != null) {
        // 3. 최신 길안내 데이터(RGData) 획득
        val getRecentRGDataMethod = sdkManager.javaClass.getMethod("getRecentRGData")
        val rgData = getRecentRGDataMethod.invoke(sdkManager)
        
        if (rgData != null) {
            // 4. 제한 속도 필드(nRoadLimitSpeed) 강제 추출
            val nRoadLimitSpeedField = rgData.javaClass.getField("nRoadLimitSpeed")
            val rawLimitSpeed = nRoadLimitSpeedField.getInt(rgData)
            
            // ... 데이터 디코딩 처리 ...
        }
    }
} catch (e: Exception) {
    Log.e("TmapSpeedLimit", "Reflection error: ${e.message}")
}
```

## 4. TMAP 고유의 데이터 디코딩 (Decoding Rule)
TMAP SDK는 `nRoadLimitSpeed`에 값을 저장할 때 실제 `km/h` 단위를 그대로 저장하지 않고 특별한 인코딩을 거칩니다.

**수신된 Raw 데이터 관찰 결과:**
- 30km/h 구역 ➜ `320`
- 40km/h 구역 ➜ `420`
- 60km/h 구역 ➜ `620`
- 100km/h 구역 ➜ `1020`

**디코딩 공식:**
끝자리 `20`은 내부적인 특정 플래그나 단위(km/h) 식별자로 추정되며, 실제 제한 속도는 다음 공식으로 얻을 수 있습니다.
```text
실제 제한 속도 = (수신된 Raw Data - 20) / 10
```

**디코딩 코드 적용:**
```kotlin
var limitSpeed = 0
if (rawLimitSpeed > 0) {
    limitSpeed = (rawLimitSpeed - 20) / 10
    
    // UI 업데이트 로직 (예: limitSpeed == 50)
    currentLimitSpeed = limitSpeed
}
```

## 5. 결론 및 요약
1. TMAP UI SDK 겉단의 이벤트 데이터에는 제한 속도가 없습니다.
2. 엔진 코어 내부 클래스인 `SDKManager`의 `RGData` 속 `nRoadLimitSpeed` 변수를 타겟으로 삼았습니다.
3. 빌드 충돌을 피하기 위해 **Reflection**으로 강제 접근하여 값을 추출했습니다.
4. 추출된 값에 `(Value - 20) / 10` 공식을 적용하여 실제 도로의 정확한 제한 속도를 화면과 백그라운드(UDP) 시스템에 표시/전송할 수 있게 되었습니다.
