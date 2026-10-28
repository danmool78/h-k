package com.sssoft.hk.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sssoft.hk.model.ArrivalResult
import com.sssoft.hk.navi.Destination
import com.sssoft.hk.navi.LatLng
import com.sssoft.hk.navi.NaviSource
import com.sssoft.hk.server.ChargerApi
import com.sssoft.hk.trip.ChargingRecommendation
import com.sssoft.hk.trip.TripConditions
import com.sssoft.hk.trip.TripPlanner
import com.sssoft.hk.vehicle.BatteryState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TripInput(
    val tempC: Float = 10f,
    /** 차량 배터리를 못 읽을 때 쓰는 값 */
    val manualSocPercent: Float = 80f,
)

enum class RouteSource { NAVI, DEMO }

data class RangeUiState(
    val input: TripInput = TripInput(),
    val battery: BatteryState = BatteryState(),
    val source: RouteSource? = null,
    val destination: Destination? = null,
    val arrival: ArrivalResult? = null,
    val recommendation: ChargingRecommendation? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val waypointAdded: Boolean = false,
) {
    val socPercent: Int get() = battery.socPercent ?: input.manualSocPercent.toInt()
}

class RangeViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(RangeUiState())
    val ui: StateFlow<RangeUiState> = _ui.asStateFlow()

    private val planner = TripPlanner(ChargerApi(readTimeoutMs = 15_000))
    private val navi = NaviSource(app)
    private var naviConnected: Boolean? = null
    private var job: Job? = null

    init {
        refresh()
    }

    fun onInput(input: TripInput) {
        _ui.update { it.copy(input = input) }
        refresh(debounceMs = 400)
    }

    fun onBattery(battery: BatteryState) {
        val prev = _ui.value.battery
        _ui.update { it.copy(battery = battery) }
        if (prev.socPercent != battery.socPercent || prev.capacityKwh != battery.capacityKwh) refresh(debounceMs = 400)
    }

    fun refresh(debounceMs: Long = 0) {
        job?.cancel()
        job = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            _ui.update { it.copy(loading = true, error = null) }
            try {
                val s = _ui.value
                val cond = TripConditions(s.socPercent, s.battery.capacityKwh, s.input.tempC.toDouble())

                if (naviConnected == null) naviConnected = navi.connect()
                val naviDest = if (naviConnected == true) navi.destination() else null
                val (source, dest, origin) = if (naviDest != null) {
                    Triple(RouteSource.NAVI, naviDest, navi.currentLocation())
                } else {
                    Triple(RouteSource.DEMO, DEMO_DEST, DEMO_ORIGIN)
                }
                val trip = if (source == RouteSource.NAVI) navi.trip(dest) else null

                val arrival = planner.arrival(cond, dest.remainKm)
                val rec = planner.recommend(cond, origin, dest, trip)
                _ui.update {
                    it.copy(
                        source = source, destination = dest, arrival = arrival, recommendation = rec,
                        loading = false, waypointAdded = false,
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _ui.update { it.copy(loading = false, error = "충전소 정보를 가져오지 못했습니다: ${e.message}") }
            }
        }
    }

    fun addWaypoint() {
        val s = _ui.value
        val best = s.recommendation?.best ?: return
        if (s.source != RouteSource.NAVI) return
        navi.addChargingWaypoint(best.station.name, best.station.location)
        _ui.update { it.copy(waypointAdded = true) }
    }

    override fun onCleared() {
        navi.release()
    }

    companion object {
        /** 내비 목적지가 없을 때 쓰는 데모 경로 (충전소 데이터는 실제 서버 값) */
        val DEMO_ORIGIN = LatLng(37.5665, 126.9780)            // 서울시청
        val DEMO_DEST = Destination("부산 (데모 경로)", LatLng(35.1796, 129.0756), remainKm = 400.0, remainMin = 270.0)
    }
}
