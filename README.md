# 찰랑

현대·기아 차량용 앱 공모전 출품 프로젝트 (Pleos Connect / Android Automotive).

화면에 물컵 하나를 띄우고, 물이 넘치지 않게 운전하면 아이가 멀미하지 않는다.
차량 신호(조향각·브레이크·주행 거리)와 내비 안내로 물컵을 출렁이게 하고,
넘친 양을 멀미 누적량으로 쌓아 우리 아이 기준에 닿기 전에 알려 주고, 차가 직접 움직인다.

## 차량과 주고받는 것

| 차량에서 읽기 | 찰랑이 하는 일 |
|---|---|
| 좌석 탑승·안전벨트 | 누가 어느 자리에 탔는지 보고 그 아이의 물 높이(멀미 기준)로 자동 설정. 둘이면 더 예민한 아이 기준, 혼자면 물컵 끔 |
| 조향각·브레이크·주행 거리, 내비 위치·방향 | 앞뒤·좌우 가속도 추정 → 물컵 출렁임 |
| 내비 회전 안내·주의 지점 | 진출로·유턴·방지턱 앞에서 미리 "부드럽게" |
| 와이퍼 | 비가 오면 창문은 열지 않는다 |

| 차량 제어 | 언제 |
|---|---|
| 공조: 외기 유입·에어컨·온도 2℃ 낮춤·풍량 +1 | 물이 자주 넘치거나 누적량이 쌓이면. 회복되면 원래 값으로 |
| 아이 옆 창문 25% | 위와 같을 때 **먼저 물어보고** 연다 (화면 "열기 / 먼지 · 닫아 두기", 20초 답 없으면 안 엶). 시속 80km 이하·비 안 올 때만, 고속·비면 다시 닫음 |
| 내부 순환·공기청정 | 먼지 나는 길이라 "닫아 두기"를 고르면 바깥 공기 대신. 이번 환기 동안 다시 묻지 않음 |
| 휴게소 안내 | 위험 수준이면 "N km 앞 휴게소에서 쉬어 가세요" |

"지금 멀미" 버튼: 이번 주행 기록을 다시 재생해 그 순간 위험 기준에 닿도록 아이의 물 높이를 맞추고, 바로 환기한다.
다음 주행부터는 멀미하기 전에 알려 준다.

## 실행

- 요구: Android Studio, Android SDK 36, Pleos Connect Emulator v2.1.0 (설치는 [docs/PLEOS_SDK.md](docs/PLEOS_SDK.md))
- 빌드: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- 테스트: `./gradlew :app:testDebugUnitTest` (물컵·판단·기준 맞추기·흔들림 추정, 화면·차량 없이)
- 시연: 화면 아래 "시연 거칠게 / 시연 부드럽게" → 주말 나들이 경로(도심 → 고속도로 → 진출로 → 산길 → 휴게소, 13분)를 6배속으로 재생.
  에뮬레이터 좌석이 비어 있으면 처음 등록한 아이가 자기 자리에 탄 것으로 본다. 공조·창문 제어는 실제로 에뮬레이터 차량에 내려간다.
- **웹에서 화면 점검: https://h-y-server.web.app/app/** — 차량 앱과 같은 코드(`shared`)를 웹으로 빌드한 것이라 화면이 늘 같다.
  아래 점검 도구로 좌석 탑승·비를 바꾸고, 공조·창문 조치는 가짜 차량이 받아 같은 문구로 보여 준다.
  로컬: `./gradlew :shared:wasmJsBrowserDevelopmentRun`
- `main` 푸시·PR 마다 GitHub Actions 가 테스트하고 디버그 APK 를 올린다. 웹판은 `main` 에 들어가면 자동 배포, PR 은 미리보기 주소

## 구조

| 경로 | 내용 |
|---|---|
| [core/WaterCup.kt](shared/src/commonMain/kotlin/com/sssoft/chalrang/core/WaterCup.kt) | 물컵 출렁임(0.2Hz 공진, 멀미 주파수 대역) · 넘친 양 · 멀미 누적량 |
| [core/Calibration.kt](shared/src/commonMain/kotlin/com/sssoft/chalrang/core/Calibration.kt) | 주행 기록, "지금 멀미" 로 물 높이 맞추기 |
| [core/Riders.kt](shared/src/commonMain/kotlin/com/sssoft/chalrang/core/Riders.kt) | 좌석 → 아이 → 물 높이·열 창문 |
| [core/CarePolicy.kt](shared/src/commonMain/kotlin/com/sssoft/chalrang/core/CarePolicy.kt) | 소리·음성·공조·창문·휴게소 판단 |
| [core/MotionEstimator.kt](shared/src/commonMain/kotlin/com/sssoft/chalrang/core/MotionEstimator.kt) | 차량 신호 → 가속도 |
| [core/DriveScenario.kt](shared/src/commonMain/kotlin/com/sssoft/chalrang/core/DriveScenario.kt) | 시연·검증용 주행 시나리오 |
| [car/CarLink.kt](app/src/main/java/com/sssoft/chalrang/car/CarLink.kt) | Pleos Vehicle SDK (좌석·벨트·조향·브레이크·주행 거리·와이퍼 읽기, 공조·창문 제어) |
| [car/NaviLink.kt](app/src/main/java/com/sssoft/chalrang/car/NaviLink.kt) | Pleos NaviHelper (회전 안내·주의 지점·위치) |
| [engine/](shared/src/commonMain/kotlin/com/sssoft/chalrang/engine/) | 20Hz 처리 루프, 차량·내비·소리·저장 연결 규격 (앱·웹 공용) |
| [ui/](shared/src/commonMain/kotlin/com/sssoft/chalrang/ui/) | 물컵 화면 (앱·웹 공용, 나눔고딕) |
| [web/](shared/src/wasmJsMain/kotlin/com/sssoft/chalrang/web/) | 웹 점검판: 가짜 차량, 브라우저 음성·찰랑 소리 |

## 문서

- 기획: [docs/찰랑_기획메모.md](docs/찰랑_기획메모.md)
- 공모전·Pleos SDK 정리: [docs/PLEOS_SDK.md](docs/PLEOS_SDK.md)
- 웹 페이지(`server/public`)는 `main` 에 병합되면 https://h-y-server.web.app 에 자동 배포됩니다 (GitHub Actions)

이전 출품 후보 "안기다려"(대기 없는 충전 경로)의 코드는 태그 `anki-darye-final` 에 남아 있습니다.
