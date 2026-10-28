package com.sssoft.hk.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sssoft.hk.model.ArrivalResult
import com.sssoft.hk.model.ChargePlanResult
import com.sssoft.hk.model.Charger
import com.sssoft.hk.model.EvMonteCarlo
import com.sssoft.hk.model.EvSpec
import com.sssoft.hk.vehicle.BatteryState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TripInput(
    val distanceKm: Float = 400f,
    val tempC: Float = -3f,
    val ascentPerKm: Float = 6f,
    val speedKmh: Float = 95f,
    val manualSocPercent: Float = 90f,
)

data class RangeUiState(
    val input: TripInput = TripInput(),
    val battery: BatteryState = BatteryState(),
    val arrival: ArrivalResult? = null,
    val plan: ChargePlanResult? = null,
    val computing: Boolean = false,
) {
    /** 차량에서 SOC를 받으면 그 값을, 아니면 수동 입력값을 사용. */
    val socPercent: Float get() = battery.socPercent?.toFloat() ?: input.manualSocPercent
}

class RangeViewModel : ViewModel() {

    private val _ui = MutableStateFlow(RangeUiState())
    val ui: StateFlow<RangeUiState> = _ui.asStateFlow()

    private var job: Job? = null

    init {
        recompute()
    }

    fun onInput(input: TripInput) {
        _ui.update { it.copy(input = input) }
        recompute()
    }

    fun onBattery(battery: BatteryState) {
        val prev = _ui.value.battery
        _ui.update { it.copy(battery = battery) }
        if (prev.socPercent != battery.socPercent || prev.capacityKwh != battery.capacityKwh) recompute()
    }

    private fun recompute() {
        job?.cancel()
        val s = _ui.value
        _ui.update { it.copy(computing = true) }
        job = viewModelScope.launch {
            val (arrival, plan) = withContext(Dispatchers.Default) { simulate(s) }
            _ui.update { it.copy(arrival = arrival, plan = plan, computing = false) }
        }
    }

    private fun simulate(s: RangeUiState): Pair<ArrivalResult, ChargePlanResult> {
        val i = s.input
        val spec = s.battery.capacityKwh?.let { EvSpec(batteryKwh = it) } ?: EvSpec()
        val soc = s.socPercent / 100.0
        val dist = i.distanceKm.toDouble()
        val mc = EvMonteCarlo()
        val arrival = mc.arrivalProbability(spec, soc, dist, i.tempC.toDouble(), i.ascentPerKm * dist)
        val plan = mc.planCharging(
            spec, soc, dist, i.tempC.toDouble(), i.ascentPerKm.toDouble(), i.speedKmh.toDouble(),
            sampleChargers(dist),
        )
        return arrival to plan
    }

    /** 실제 충전소 API 연동 전까지 쓰는 경로상 샘플 충전소. */
    private fun sampleChargers(totalKm: Double) = listOf(
        Charger("A휴게소", atKm = totalKm * 0.375, powerKw = 120.0, waitMeanMin = 4.0),
        Charger("B휴게소", atKm = totalKm * 0.55, powerKw = 200.0, waitMeanMin = 12.0),
        Charger("C휴게소", atKm = totalKm * 0.70, powerKw = 100.0, waitMeanMin = 2.0),
    )
}
