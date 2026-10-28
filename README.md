# 갈수있어 (h-k)

현대·기아자동차 앱 공모전 출품 프로젝트.

## 개요

전기차 목적지 **도달 확률** 게이지와 **충전 경유 계획** 앱 (Pleos Connect / Android Automotive).
배터리 상태(Vehicle SDK), 거리·기온·고도, 충전소 대기시간의 불확실성을 몬테카를로로 계산해
"주행가능거리 200km" 대신 "도착 확률 92%"로 보여줍니다.

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
