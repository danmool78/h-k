# 안기다려 (h-k)

현대·기아자동차 앱 공모전 출품 프로젝트.

## 개요

**기다리지 않는 충전 경로**를 찾아주는 전기차 앱 (Pleos Connect / Android Automotive).

차량 내비는 도착 시 예상 배터리와 경로상 충전소(대수·출력)까지는 알려주지만,
그 충전소가 **지금 비어 있는지, 도착할 때 붐빌지**는 모릅니다. 안기다려는

- 전국 급속 충전기 5만여 대의 실시간 상태를 5분마다 수집하고 ([server/](server/))
- 요일·시간대별 혼잡도를 학습해 **도착 시점 대기시간**을 예측하고
- 배터리·거리·기온의 불확실성을 몬테카를로로 계산해 **도착 확률을 지키면서 총 소요시간이 가장 짧은 충전소**를 추천합니다.

날씨는 [Open-Meteo](https://open-meteo.com)(전 세계, CC BY 4.0)로 경로 지점별 도착 시각 예보를 씁니다.

Gleo AI 음성으로도 쓸 수 있습니다: "안기다려, 어디서 충전해?"

## 실행

- 요구: Android Studio, Android SDK 36, Pleos Connect Emulator v2.1.0 (설치는 [docs/PLEOS_SDK.md](docs/PLEOS_SDK.md))
- 빌드: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- 테스트: `./gradlew :app:testDebugUnitTest`
- 차량 연결이 안 되면 배터리 값을 화면에서 수동 입력할 수 있습니다.
  에뮬레이터 배터리 값은 Virtual Console(`adb forward tcp:8088 tcp:8088` → http://127.0.0.1:8088)의 Energy에서 변경.

## 구조

| 경로 | 내용 |
|---|---|
| [app/.../model/EvMonteCarlo.kt](app/src/main/java/com/sssoft/hk/model/EvMonteCarlo.kt) | 도달 확률 · 충전 계획 모델 |
| [app/.../vehicle/BatterySource.kt](app/src/main/java/com/sssoft/hk/vehicle/BatterySource.kt) | Vehicle SDK 배터리 연동 |
| [app/.../ui/](app/src/main/java/com/sssoft/hk/ui/) | 게이지 화면, ViewModel |
| [prototype/ev_montecarlo.py](prototype/ev_montecarlo.py) | 원본 파이썬 프로토타입 |

## 문서

- [공모전 요강 · Pleos Connect SDK 정리](docs/PLEOS_SDK.md)
