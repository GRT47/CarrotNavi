# CarrotNavi (Tmap & Openpilot Integration)

CarrotNavi는 **Tmap API**를 활용하여 경로 및 안전운전 데이터(단속 카메라, 속도 제한 등)를 백그라운드에서 실시간으로 수집하고, 이를 **UDP 통신**을 통해 오픈파일럿(Openpilot) 장치로 브로드캐스트하는 안드로이드 애플리케이션입니다.

## 🚀 주요 기능

- **백그라운드 통신 (Background Service)**
  - 화면이 꺼져 있거나 다른 앱을 사용하는 중에도 백그라운드에서 지속적으로 Tmap 데이터를 수집하고 UDP 패킷(포트 7706)을 전송합니다.
- **안전운전 데이터 실시간 동기화**
  - Tmap SDK의 `observableEDCData`를 기반으로 `nRoadLimitSpeed`, `nSdiSpeedLimit` 등 카메라와 도로 제한속도 정보를 캐싱하여 연속성 있게 제공합니다.
- **가로 모드 (Landscape) 완벽 지원**
  - 안드로이드 오토 등 차량 내 디스플레이 환경에 최적화되도록 세로/가로 모드 레이아웃을 분리하여 직관적인 UI를 제공합니다.
- **지도 오터치 방지 (Blocking Overlay)**
  - 운전 중 오작동을 방지하기 위해 Tmap 지도 영역의 터치 이벤트를 막아 안전하게 정보만 시각화합니다.
- **간편한 설정**
  - Tmap App Key와 UDP Target IP를 직관적인 설정 화면에서 입력받고, 이후 자동 저장되어 재실행 시 바로 사용할 수 있습니다.
  - 최신 안드로이드 정책을 준수하는 위치 권한 요구 옵션을 지원합니다 ("항상 허용" 옵션 선택 가능).

## 📁 프로젝트 구조

이 저장소는 표준 안드로이드 프로젝트 구조를 따르고 있습니다:
- `app/`: CarrotNavi 메인 애플리케이션 소스 코드 (Kotlin)
- `docs/`: 오픈파일럿 연동 및 Tmap 구현에 관한 마크다운 가이드 문서
- `gradle/`: Gradle 래퍼 및 빌드 설정 파일

## ⚙️ 빌드 및 설치 방법

1. **저장소 클론**
   ```bash
   git clone https://github.com/GRT47/CarrotNavi.git
   ```
2. **Android Studio 열기**
   - Android Studio에서 클론한 디렉토리를 엽니다.
3. **빌드 및 실행**
   - 툴바에서 `Run 'app'` 버튼을 눌러 기기나 에뮬레이터에 설치합니다.
4. **App Key 발급**
   - Tmap API를 사용하기 위해 [SK Open API 포털](https://openapi.sk.com/)에서 Tmap App Key를 발급받아 앱의 초기 화면에 입력해야 합니다.

## 📡 데이터 연동 (UDP 송신)

앱이 실행되면 300ms(약 3.3Hz) 주기로 설정된 Target IP(기본 `255.255.255.255`)의 포트 `7706`으로 JSON 포맷의 주행 정보를 브로드캐스트합니다. 수신 측(오픈파일럿 또는 파이썬 스크립트)에서는 이 JSON을 파싱하여 속도 제한 UI 표시나 제어 로직에 활용할 수 있습니다.

---

*본 프로젝트는 GRT47 및 오픈파일럿 사용자 커뮤니티의 기여를 위해 작성되었습니다.*
