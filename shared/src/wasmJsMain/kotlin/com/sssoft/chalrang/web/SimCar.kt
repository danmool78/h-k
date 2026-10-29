package com.sssoft.chalrang.web

import com.sssoft.chalrang.core.Seat
import com.sssoft.chalrang.core.SeatState
import com.sssoft.chalrang.core.Window
import com.sssoft.chalrang.engine.CarPort
import com.sssoft.chalrang.engine.CarStatus
import com.sssoft.chalrang.engine.NaviPort
import com.sssoft.chalrang.engine.RoadAhead
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 웹 점검용 가짜 차량. 실제 앱의 CarLink 와 같은 조치를 받아 실내 값을 바꾸고 같은 문구로 보여 준다.
 * 좌석·비는 화면 아래 점검 도구로 바꾼다.
 */
class SimCar : CarPort {
    private val _seats = MutableStateFlow<Map<Seat, SeatState>>(emptyMap())
    override val seats: StateFlow<Map<Seat, SeatState>> = _seats.asStateFlow()
    private val _raining = MutableStateFlow(false)
    override val raining: StateFlow<Boolean> = _raining.asStateFlow()
    private val _status = MutableStateFlow(CarStatus(connected = true))
    override val status: StateFlow<CarStatus> = _status.asStateFlow()

    /** 가짜 실내 (처음 값) */
    private val baseTempC = 22
    private val baseFan = 3

    fun toggleSeat(seat: Seat) = _seats.update { m ->
        val riding = m[seat]?.riding == true
        if (riding) m - seat else m + (seat to SeatState(occupied = true, belted = true))
    }

    fun setRaining(on: Boolean) {
        _raining.value = on
    }

    override fun freshAir(on: Boolean) {
        if (on == _status.value.freshAir) return
        _status.update {
            if (on) {
                it.copy(freshAir = true, dustGuard = false, lastAction = "외기 유입 · 에어컨 · 풍량 ${baseFan}→${baseFan + 1} · ${baseTempC}→${baseTempC - 2}도")
            } else {
                it.copy(freshAir = false, dustGuard = false, lastAction = "공조 원래대로")
            }
        }
    }

    override fun dustGuard() = _status.update { it.copy(dustGuard = true, lastAction = "내부 순환 · 공기청정") }

    override fun openWindows(windows: List<Window>, percent: Int) = _status.update {
        it.copy(openWindows = it.openWindows + windows.associateWith { percent }, lastAction = windows.joinToString { w -> w.label } + " 창문 $percent%")
    }

    override fun closeWindows() = _status.update { it.copy(openWindows = emptyMap(), lastAction = "창문 원래대로") }
}

/** 웹에는 내비가 없다 (시연 시나리오가 다음 안내·휴게소 거리를 준다) */
class SimNavi : NaviPort {
    override val ahead: StateFlow<RoadAhead> = MutableStateFlow(RoadAhead())
    override fun poll() = Unit
}
