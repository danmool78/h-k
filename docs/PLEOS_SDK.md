# 공모전 & Pleos Connect SDK 정리

> 조사일: 2026-10-28. 버전은 Pleos Nexus 저장소의 `maven-metadata.xml`에서 확인한 최신 release 기준.

## 1. 공모전 개요

- 공식 사이트: https://appdevchallenge.auto-hmg.io
- 주제: 차량 데이터·기능·이동 상황을 활용한 차량용 앱 (인카 엔터테인먼트 / 모빌리티 라이프 케어 / 안전 운전 / 패밀리·동승자 케어 등)
- 부문: 대학생 / 일반(스타트업 포함), 개인 또는 최대 5인 팀

| 일정 (KST) | 내용 |
|---|---|
| 10.21 ~ **11.15** | 접수 (이메일) |
| 10.28 14:00 | 온라인 설명회 (YouTube Live) |
| 11.23 | 1차 서류심사 결과 |
| 12.03 ~ 12.04 | 2차 최종 PT 심사 (오프라인, **실제 동작하는 앱 필요**) |
| 12.18 | 최종 결과 |
| 2027.02 | 전시 및 시상식 |

시상: 대상 1,000만 / 최우수 600만 / 우수 400만 / 아이디어 특별상 150만 / 숏폼 챌린지 50만(별도)

### 제출

- 접수 메일: `appdevchallenge@hyundai-partners.com`
- 제목: `[차량용 앱 개발 공모전] 팀명(개인은 성명)_대표자명` — 팀원 전원 CC
- 필수: 접수양식(기획서) — [contest-application-consent.docx](contest-application-consent.docx), 참가자 전원 서명
  - 양식 5번(개발 계획)에 **구현 방법, 개발 범위, SDK/API 사용 계획** 구체적으로 작성
- 선택(단계별 가산점): 시연 영상, GitHub 저장소, APK
  - APK는 저장소 `apk/` 폴더에 `팀명_앱이름_release.apk` (release 빌드), 용량 초과 시 GitHub Releases
  - README에 앱 이름·한 줄 소개, 실행 방법, 테스트 환경/설정, APK 위치 기재
- 수정 제출: 제목 앞에 `[수정제출]`, 마감 전 마지막 제출본이 최종

## 2. 개발 환경

| 항목 | 요구사항 |
|---|---|
| 플랫폼 | Android Automotive OS 기반 Pleos Connect (IVI) |
| minSdk | 28 이상 |
| compile / 에뮬레이터 | Android API 34 |
| Gradle | 8.0 이상 |
| 언어 | Kotlin 또는 Java |
| 에뮬레이터 | Pleos Connect Emulator v2.1.0 (Automotive AVD) |

### 에뮬레이터 설치

1. Android Studio → SDK Manager → **SDK Update Sites** 에 아래 사이트 추가
   - Windows/Linux: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
   - Mac(arm64): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
2. SDK Platforms 탭 → Show Package Details → Android API 34 아래 **Pleos Connect v2.1.0 APIs Intel x86_64 Atom System Image** 설치 (약 4.1GB, 42dot SDK 라이선스 동의 필요)
3. Android Studio 재시작
4. Device Manager → 새 기기 → **Automotive** → Pleos Connect v2.1.0 → API 34 / Pleos Connect v2.1.0 APIs
   - Advanced: Default boot **Cold**, RAM **4GB**

## 3. 계정 / 키 발급 (Pleos Playground)

1. https://pleos.ai/playground 에서 Pleos 계정 가입
2. Workspace → My Project (https://pleos.ai/playground/console/projects) → 프로젝트 생성 (이름, 회사명, 카테고리, 설명)
3. 프로젝트 정보에서 확인: **Project ID(Key)**, **Client ID / Client Secret** (TTS·STT 서버 모드용). CRN은 Emulator 2.1.0 이상에서 불필요
4. SDK 사용 신청
   - 신청 없이 사용: **Vehicle State**, **NaviHelper**
   - 신청 필요: FusedLocation, TextToSpeech, SpeechToText, Vehicle Control → 검토 중 → 사용 가능 / 반려

> Client ID/Secret은 `local.properties` 등 git에 올라가지 않는 곳에 둘 것 (공개 저장소).

## 4. SDK 목록

Maven 저장소 (`settings.gradle.kts`):

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
    }
}
```

의존성 (`app/build.gradle.kts`):

```kotlin
dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
}
```

| SDK | 버전 | 기능 | 진입점 / 주요 API | 신청 |
|---|---|---|---|---|
| Vehicle | 2.3.2 | 차량 상태 조회·제어. 19개 도메인: Brake, CarInfo, Display, Door, DrivingMode, EvBattery, Fuel, HVAC, Light, Odometer, Safety, Seat, SideMirror, Steeringwheel, Tire, Toll, TurnSignal, Window, Wiper | `Vehicle(context)` → `initialize()` → `getDoor()` 등 → `checkXxxCapabilities` / get / set / callback → `release()` | 상태 조회 불필요, 제어는 필요 |
| NaviHelper | 3.0.5 | 목적지 설정·취소, 경유지, 경로 옵션/재탐색, 주행 상태 이벤트, 내비 정보 조회 | `NaviHelper` → `initialize()` → `addListener()` → 요청/조회 → `removeListener()` → `release()` | 불필요 |
| TextToSpeech | 3.3.1 | 음성 합성. `HYBRID`(기본, 서버→온디바이스 폴백) / `SERVER` / `ON_DEVICE` | `TextToSpeech(ctx, Mode.HYBRID)` → `initialize()` → `addEventListener()` → `setLocale()` → (서버 모드: 앱 등록 후 `onConnected`) → `speak()` / `stop()` → `release()` | 필요 |
| SpeechToText | 3.3.1 | 음성 인식. 모드는 TTS와 동일 | `SpeechToText(ctx, Mode.HYBRID)` → `initialize()` → `addListener()` → `setLocale()` → `request()` / `stop()` → `release()` | 필요 |
| FusedLocation | 1.1.3 | 차량 위치, 지오펜스 | `isSupported()`, `getLastKnownLocation()`, `registerFusedLocationCallback()`, `addProximityAlert()` | 필요 |

### 권한

- `AndroidManifest.xml`에 사용하는 기능별 권한 선언. 예: `pleos.car.permission.CAR_DOORS`, `pleos.car.permission.FUSED_LOCATION`
- 사용 가능한 권한은 Pleos App Market 심사에서 승인됨 — 승인되지 않은 권한 선언/호출 시 심사 탈락 가능
- 기능 지원 여부는 각 도메인의 `checkXxxCapabilities`로 확인 후 호출

### 첫 호출 예 (Vehicle)

```kotlin
val vehicle = Vehicle(context)
vehicle.initialize()
val door = vehicle.getDoor()
door.getCurrentDoorPosition(/* onComplete, onFailed */)
// onDestroy()
vehicle.release()   // 대기 중인 비동기 콜백이 끝난 뒤 호출
```

## 5. 참고 링크

- 공모전 개발 환경 안내: https://appdevchallenge.auto-hmg.io/guide/development
- 공모전 FAQ: https://appdevchallenge.auto-hmg.io/faq
- Pleos Playground: https://pleos.ai/playground
- 시작하기: https://document.pleos.ai/docs/pleos-only/getting-started
- 프로젝트 생성 및 SDK 신청: https://document.pleos.ai/docs/pleos-only/create-my-project
- 에뮬레이터 설정: https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator
- SDK 문서: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/
- API 레퍼런스: https://document.pleos.ai/api-reference/connect-sdk-pleos
- Vehicle SDK 튜토리얼: https://document.pleos.ai/docs/pleos-only/tutorial/vehicle-sdk
