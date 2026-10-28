package com.sssoft.hk.learn

import com.sssoft.hk.model.ChargeSession
import com.sssoft.hk.model.DriveSegment
import com.sssoft.hk.model.EnergyInput
import com.sssoft.hk.model.EnergyModel
import com.sssoft.hk.model.TripContext
import com.sssoft.hk.model.Weather
import kotlin.math.max

/** 주기적으로 읽은 차량 값 하나. */
data class VehicleSample(
    val timeMs: Long,
    val odometerKm: Double,
    /** 배터리 잔량 (Wh). 못 읽으면 SOC×용량으로 대신 */
    val batteryLevelWh: Double?,
    val soc: Double,
    val occupants: Int,
    val hvacOn: Boolean,
    val cabinSetC: Double,
    val tireUnderinflation: Double,
    val tempC: Double,
    val weather: Weather,
)

/**
 * 차량 값 흐름을 학습 단위로 바꾼다 (Android 의존 없음 → 단위 테스트 가능).
 *  - 주행 구간: [SEGMENT_KM] 또는 [SEGMENT_MIN] 마다 실제 소모·가속 에너지·조건
 *  - 주행 종료: [TRIP_END_IDLE_MIN] 동안 안 움직이면. 내비 예상 대비 실제 주행시간
 *  - 충전: 정차 중 SOC가 오르다 멈추면
 */
class DriveRecorder(
    private val curbMassKg: Double,
    private val capacityKwh: () -> Double,
) {
    sealed interface Event {
        data class Segment(val segment: DriveSegment, val deltaWh: Double, val deltaSoc: Double) : Event
        data class TripEnd(
            val context: TripContext, val navEtaMin: Double, val actualDriveMin: Double,
            val plan: Plan, val socEnd: Double, val chargedDuringTrip: Boolean,
        ) : Event
        data class Charge(val session: ChargeSession) : Event
    }

    /** 이번 주행의 내비 계획. 주행 시작 시 서비스가 넣어준다. */
    /**
     * 이번 주행의 계획과 출발 시점 예측. [navSocAtDest] 내비의 도착 예상 배터리(0~1),
     * [oursSoc]·[oursMin] 은 안기다려 예측 (정확도 비교용).
     */
    data class Plan(
        val context: TripContext, val navEtaMin: Double,
        val socStart: Double = 0.0, val navSocAtDest: Double? = null,
        val oursSoc: Double? = null, val oursMin: Double? = null,
    )

    var plan: Plan? = null
    /** 충전 중일 때 충전기 출력 (감시 대상 충전소에서). 모르면 50kW 로 본다 */
    var chargerKw: Double = 50.0

    private var last: VehicleSample? = null
    private var lastMoveMs: Long = 0
    private var tripDriveMs: Long = 0
    private var tripActive = false
    private var chargedDuringTrip = false

    // 진행 중인 구간
    private var segStart: VehicleSample? = null
    private var segDriveMs: Long = 0
    private var segAccelWh = 0.0
    private var prevSpeedMs = 0.0

    // 진행 중인 충전
    private var chargeStart: VehicleSample? = null
    private var chargeLastRise: VehicleSample? = null

    fun onSample(s: VehicleSample, context: TripContext): List<Event> {
        val prev = last
        last = s
        if (prev == null) return emptyList()
        val events = mutableListOf<Event>()
        val dtMs = s.timeMs - prev.timeMs
        if (dtMs <= 0) return events
        val dKm = s.odometerKm - prev.odometerKm
        val moving = dKm > 0.0005

        if (moving) {
            closeCharge(events)
            if (!tripActive) { tripActive = true; tripDriveMs = 0 }
            if (segStart == null) { segStart = prev; segDriveMs = 0; segAccelWh = 0.0; prevSpeedMs = 0.0 }
            lastMoveMs = s.timeMs
            tripDriveMs += dtMs
            segDriveMs += dtMs

            val v = dKm * 1000 / (dtMs / 1000.0)
            val mass = EnergyModel.totalMassKg(curbMassKg, s.occupants, LUGGAGE_KG)
            segAccelWh += 0.5 * mass * max(0.0, v * v - prevSpeedMs * prevSpeedMs) / 3600.0
            prevSpeedMs = v

            val start = segStart!!
            if (s.odometerKm - start.odometerKm >= SEGMENT_KM || segDriveMs >= SEGMENT_MIN * 60_000) {
                closeSegment(s, context, events)
            }
        } else {
            prevSpeedMs = 0.0
            // 정차 중 SOC 상승 → 충전
            if (s.soc > prev.soc + 0.001) {
                if (segStart != null) closeSegment(prev, context, events)
                if (chargeStart == null) chargeStart = prev
                if (tripActive) chargedDuringTrip = true
                chargeLastRise = s
            } else if (chargeStart != null && s.timeMs - (chargeLastRise?.timeMs ?: s.timeMs) > CHARGE_END_IDLE_MIN * 60_000) {
                closeCharge(events)
            }
            // 오래 서 있으면 주행 종료
            if (tripActive && s.timeMs - lastMoveMs > TRIP_END_IDLE_MIN * 60_000) {
                if (segStart != null) closeSegment(prev, context, events)
                plan?.let { p -> events += Event.TripEnd(p.context, p.navEtaMin, tripDriveMs / 60_000.0, p, prev.soc, chargedDuringTrip) }
                chargedDuringTrip = false
                plan = null
                tripActive = false
            }
        }
        return events
    }

    private fun closeSegment(end: VehicleSample, context: TripContext, out: MutableList<Event>) {
        val start = segStart ?: return
        segStart = null
        val dist = end.odometerKm - start.odometerKm
        val hours = segDriveMs / 3_600_000.0
        val deltaSoc = start.soc - end.soc
        val wh = if (start.batteryLevelWh != null && end.batteryLevelWh != null) start.batteryLevelWh - end.batteryLevelWh
        else deltaSoc * capacityKwh() * 1000
        if (dist < MIN_SEGMENT_KM || hours <= 0 || wh <= 0) return
        val input = EnergyInput(
            distKm = dist, hours = hours, tempC = end.tempC,
            massKg = EnergyModel.totalMassKg(curbMassKg, end.occupants, LUGGAGE_KG),
            hvacOn = end.hvacOn, cabinSetC = end.cabinSetC, weather = end.weather, night = context.night,
            tireUnderinflation = end.tireUnderinflation, accelKineticWh = segAccelWh,
        )
        out += Event.Segment(DriveSegment(input, wh, context), wh, deltaSoc)
    }

    private fun closeCharge(out: MutableList<Event>) {
        val start = chargeStart ?: return
        val end = chargeLastRise ?: return
        chargeStart = null
        chargeLastRise = null
        val minutes = (end.timeMs - start.timeMs) / 60_000.0
        if (end.soc - start.soc < MIN_CHARGE_SOC || minutes <= 0) return
        out += Event.Charge(ChargeSession(start.soc, end.soc, minutes, chargerKw, end.tempC))
    }

    companion object {
        const val SEGMENT_KM = 10.0
        const val SEGMENT_MIN = 15
        const val MIN_SEGMENT_KM = 1.0
        const val TRIP_END_IDLE_MIN = 10
        const val CHARGE_END_IDLE_MIN = 5
        const val MIN_CHARGE_SOC = 0.05
        /** 짐 무게는 차가 모르므로 보통 수준으로 가정 */
        const val LUGGAGE_KG = 30.0
    }
}
