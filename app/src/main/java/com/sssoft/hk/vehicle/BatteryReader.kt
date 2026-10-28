package com.sssoft.hk.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.control.DrivingModeType
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import android.content.Context
import com.sssoft.hk.model.DriveMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** 예측·학습에 쓰는 차량 상태. 못 읽은 값은 null 또는 기본값. */
data class VehicleReading(
    val socPercent: Int,
    val capacityKwh: Double?,
    /** 배터리 잔량 (Wh). 학습용 실제 소모 측정 */
    val batteryLevelWh: Double?,
    val occupants: Int,
    val hvacOn: Boolean,
    val cabinSetC: Double,
    val tirePressureKpa: Double?,
    val mode: DriveMode,
    /** 누적 주행거리 (km) */
    val odometerKm: Double?,
)

/** 백그라운드(Gleo 호출, 감시·학습 서비스)에서 차량 상태를 한 번 읽는다. 단위는 에뮬레이터에서 확인 필요. */
object BatteryReader {

    private val SEATS = listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT)
    private val WHEELS = listOf(WheelValue.LEFT_FRONT, WheelValue.RIGHT_FRONT, WheelValue.LEFT_REAR, WheelValue.RIGHT_REAR)

    suspend fun read(context: Context, timeoutMs: Long = 1_500): VehicleReading? {
        val vehicle = Vehicle(context.applicationContext)
        return try {
            vehicle.initialize()
            withTimeoutOrNull(timeoutMs) { readAll(vehicle) }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { vehicle.release() }
        }
    }

    /** 이미 초기화된 [Vehicle] 로 읽기 (학습 서비스처럼 계속 읽는 곳에서 매번 연결하지 않으려고). */
    suspend fun read(vehicle: Vehicle, timeoutMs: Long = 1_500): VehicleReading? =
        runCatching { withTimeoutOrNull(timeoutMs) { readAll(vehicle) } }.getOrNull()

    private suspend fun readAll(v: Vehicle): VehicleReading? = coroutineScope {
        val battery = v.getEvBattery()
        val soc = get<Int> { ok, err -> battery.getEvBatteryStateOfCharge(ok, err) }.await() ?: return@coroutineScope null
        val cap = get<Float> { ok, err -> battery.getEvBatteryCapacity(ok, err) }
        val level = get<Float> { ok, err -> battery.getEvBatteryLevel(ok, err) }
        val seats = SEATS.map { s -> get<Boolean> { ok, err -> v.getSeat().getCurrentSeatOccupancy(SeatArea(s), ok, err) } }
        val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
        val hvacOn = get<Boolean> { ok, err -> v.getHVAC().getHvacPowerState(driverSeat, ok, err) }
        val cabin = get<Float> { ok, err -> v.getHVAC().getHvacTemperature(driverSeat, TemperatureUnit.CELSIUS, ok, err) }
        val tires = WHEELS.map { w -> get<Float> { ok, err -> v.getTire().getTirePressure(WheelArea(w), ok, err) } }
        val mode = get<DrivingModeType> { ok, err -> v.getDrivingMode().getCurrentDrivingModeState(ok, err) }
        val odo = get<Float> { ok, err -> v.getOdometer().getCurrentDistanceDriven(ok, err) }

        val tireValues = tires.mapNotNull { it.awaitShort() }.map { if (it < 100f) it * 6.895 else it.toDouble() } // psi → kPa
        VehicleReading(
            socPercent = soc,
            // AAOS 표준 단위는 Wh. kWh로 오는 경우도 대비
            capacityKwh = cap.awaitShort()?.let { if (it > 1000f) it / 1000.0 else it.toDouble() },
            batteryLevelWh = level.awaitShort()?.let { if (it < 1000f) it * 1000.0 else it.toDouble() },
            occupants = seats.count { it.awaitShort() == true }.coerceAtLeast(1),
            hvacOn = hvacOn.awaitShort() ?: true,
            cabinSetC = cabin.awaitShort()?.toDouble() ?: 22.0,
            tirePressureKpa = tireValues.takeIf { it.isNotEmpty() }?.average(),
            mode = when (mode.awaitShort()) {
                DrivingModeType.ECO -> DriveMode.ECO
                DrivingModeType.SPORT -> DriveMode.SPORT
                else -> DriveMode.NORMAL
            },
            odometerKm = odo.awaitShort()?.toDouble(),
        )
    }

    private fun <T> get(call: (ok: (T?) -> Unit, err: (Exception) -> Unit) -> Unit): CompletableDeferred<T?> {
        val d = CompletableDeferred<T?>()
        runCatching { call({ d.complete(it) }, { d.complete(null) }) }.onFailure { d.complete(null) }
        return d
    }

    private suspend fun <T> CompletableDeferred<T?>.awaitShort(): T? = withTimeoutOrNull(300) { await() }
}
