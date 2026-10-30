package com.sssoft.chalrang.engine

import com.sssoft.chalrang.core.Child
import com.sssoft.chalrang.core.TripSummary
import com.sssoft.chalrang.core.Seat
import com.sssoft.chalrang.core.SeatState
import com.sssoft.chalrang.core.Upcoming
import com.sssoft.chalrang.core.Window
import kotlinx.coroutines.flow.StateFlow

/** 차량 조치 상태 (화면 표시용) */
data class CarStatus(
    val connected: Boolean = false,
    val freshAir: Boolean = false,
    /** 먼지 나는 길: 내부 순환 + 공기청정 */
    val dustGuard: Boolean = false,
    /** 열어 둔 창문과 열린 정도 (%) */
    val openWindows: Map<Window, Int> = emptyMap(),
    /** 마지막으로 한 조치 (예: "외기 유입 · 에어컨 · 22→20도") */
    val lastAction: String? = null,
    val error: String? = null,
)

/**
 * 차량. 앱은 Pleos Vehicle SDK(CarLink), 웹은 점검용 가짜 차량(SimCar).
 * 바꾸기 전 값을 저장해 두었다가 [freshAir] (false) 에서 함께 되돌린다.
 */
interface CarPort {
    val seats: StateFlow<Map<Seat, SeatState>>
    /** 와이퍼가 움직이는 중 */
    val raining: StateFlow<Boolean>
    val status: StateFlow<CarStatus>

    /** 외기 유입 + 에어컨 + 온도 2℃ 낮춤 + 풍량 +1 (on), 원래대로 (off) */
    fun freshAir(on: Boolean)
    /** 내부 순환 + 공기청정 */
    fun dustGuard()
    fun openWindows(windows: List<Window>, percent: Int)
    fun closeWindows()
}

/** 내비에서 받은 앞으로 올 길 */
data class RoadAhead(
    /** 가장 가까운 안내 (회전·진출로·방지턱 등) */
    val next: Upcoming? = null,
    /** 경로상 다음 휴게소까지 (km) */
    val restAreaKm: Double? = null,
    /** 알고 있는 앞쪽 안내·주의 지점 전부 (지금 위치부터 m). 경로 미리보기에 쓴다 */
    val points: List<Upcoming> = emptyList(),
    /** 목적지까지 남은 거리 (m)·시간 (초). 길 안내 중이 아니면 null */
    val remainM: Double? = null,
    val remainSec: Double? = null,
    /** 경로가 바뀌면 달라진다 */
    val routeId: String? = null,
    /** 지금 위치·목적지 (지형 미리보기용) */
    val hereLat: Double? = null,
    val hereLng: Double? = null,
    val destLat: Double? = null,
    val destLng: Double? = null,
)

interface NaviPort {
    val ahead: StateFlow<RoadAhead>
    /** 1초마다 불린다 */
    fun poll()
}

/** 소리: "찰랑" 과 음성 안내 */
interface VoicePort {
    fun chime()
    fun say(text: String)
}

/** 아이 프로필 저장 (기기 안에만) */
interface ChildRepo {
    fun load(): List<Child>
    fun save(children: List<Child>)
    /** 지난 주행 평가 (최근 [TripSummary.KEEP] 개, 오래된 것부터) */
    fun loadTrips(): List<TripSummary> = emptyList()
    fun saveTrips(trips: List<TripSummary>) {}
}
