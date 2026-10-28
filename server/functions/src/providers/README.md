# 충전소 데이터 출처 (Provider)

H-Y Server는 나라·업체마다 다른 충전소 데이터를 **하나의 공통 형식**([types.ts](types.ts), OCPI 2.2 Location/EVSE 단순화)으로 모아
앱과 조회 API에 제공한다. 앱은 데이터가 어느 나라·출처에서 왔는지 몰라도 된다.

## 새 출처 붙이기

1. `providers/<이름>.ts` 에 `ChargerProvider` 구현
   - `fetchStations(previous)`: 급속 충전소 전체 목록 (하루 1회). 일부 지역 실패 시 `previous` 에서 그 지역을 유지
   - `fetchStatusChanges()`: 최근 상태 변경분 (5분마다). 실시간 상태가 없는 출처는 `[]`
   - 충전소 ID는 `"국가코드:원본ID"` (예: `KR:ME174050`, `DE:...`) — 전 세계에서 유일해야 함
   - 상태는 `AVAILABLE / CHARGING / OUTOFORDER / INOPERATIVE / UNKNOWN` 로 변환
2. [index.ts](index.ts) 의 `PROVIDERS` 에 추가
3. 원본 → 공통 형식 변환 함수에 단위 테스트 추가 (`krKeco.convert` 참고)
4. API 키가 필요하면 관리자 페이지의 키 관리에 항목 추가 (현재는 공공데이터포털 키 하나)

## 현재

| 출처 | 국가 | 목록 | 실시간 상태 |
|---|---|---|---|
| `kr-keco` 한국환경공단 (data.go.kr 15076352) | KR | 매일 04:00 | 5분마다 |

## 확장 후보

| 후보 | 범위 | 실시간 | 비고 |
|---|---|---|---|
| EU 국가별 데이터 창구 (AFIR) | EU 각국 | 의무화 진행 | 나라마다 창구·형식이 다름 (독일 Mobilithek, 네덜란드 NDW 등). OCPI 기반이 많아 변환이 쉬움 |
| 영국 공공 충전기 개방 데이터 | UK | 의무 | 2023년 공공 충전기 규정 |
| 미국 NREL AFDC | US | 없음 | 위치·출력 정적 정보만 (무료 키) |
| Open Charge Map | 전 세계 | 약함 | 커뮤니티 데이터, 무료 키 |
| 로밍 플랫폼 (Hubject, Gireve 등) / 데이터 업체 (Eco-Movement 등) | 다국가 | 있음 | 유료, OCPI 형식 |
| **현대·기아 충전 서비스 데이터** (Charge myHyundai, Kia Charge 등) | 유럽·북미 등 | 있음 | 상용화 시 가장 현실적. 주최 측 자산과 연결 |

각 후보의 의무 시행 시점·접근 조건은 실제 연동 전에 다시 확인할 것.
