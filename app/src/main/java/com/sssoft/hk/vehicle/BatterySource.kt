package com.sssoft.hk.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.RangeRemainingListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BatteryState(
    val socPercent: Int? = null,
    val capacityKwh: Double? = null,
    val rangeRemaining: Float? = null,   // 단위는 에뮬레이터에서 확인 필요 (km 또는 m)
    val error: String? = null,
) {
    val connected: Boolean get() = socPercent != null
}

/** Pleos Vehicle SDK에서 배터리 상태를 받아 StateFlow로 노출. */
class BatterySource(context: Context) {

    private val vehicle = Vehicle(context.applicationContext)
    private val _state = MutableStateFlow(BatteryState())
    val state: StateFlow<BatteryState> = _state.asStateFlow()

    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
            _state.update { it.copy(socPercent = value, error = null) }
        }

        override fun onFailed(e: Exception) = onError("SOC 수신 실패", e)
    }

    private val rangeListener = object : RangeRemainingListener {
        override fun onRangeRemainingChanged(value: Float?) {
            _state.update { it.copy(rangeRemaining = value) }
        }

        override fun onFailed(e: Exception) = onError("주행가능거리 수신 실패", e)
    }

    fun start() {
        try {
            vehicle.initialize()
            val battery = vehicle.getEvBattery()
            battery.getEvBatteryStateOfCharge(
                { soc -> _state.update { it.copy(socPercent = soc, error = null) } },
                { e -> onError("SOC 조회 실패", e) },
            )
            battery.getEvBatteryCapacity(
                // AAOS 표준 단위는 Wh. kWh로 오는 경우도 대비
                { cap ->
                    if (cap != null) {
                        _state.update { it.copy(capacityKwh = if (cap > 1000f) cap / 1000.0 else cap.toDouble()) }
                    }
                },
                { e -> onError("배터리 용량 조회 실패", e) },
            )
            battery.registerEvBatteryStateOfCharge(socListener)
            vehicle.getOdometer().registerRangeRemainingListener(rangeListener)
        } catch (e: Exception) {
            onError("Vehicle SDK 초기화 실패", e)
        }
    }

    fun stop() {
        try {
            vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(socListener)
            vehicle.getOdometer().unregisterRangeRemainingListener(rangeListener)
            vehicle.release()
        } catch (e: Exception) {
            Log.w(TAG, "release 실패", e)
        }
    }

    private fun onError(msg: String, e: Exception) {
        Log.w(TAG, msg, e)
        _state.update { it.copy(error = "$msg: ${e.message}") }
    }

    private companion object {
        const val TAG = "BatterySource"
    }
}
