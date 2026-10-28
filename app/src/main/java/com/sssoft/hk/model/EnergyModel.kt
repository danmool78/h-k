package com.sssoft.hk.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/** 소모 계산 입력 (학습 구간과 예측에 공통). */
data class EnergyInput(
    val distKm: Double,
    val hours: Double,
    val tempC: Double,
    val massKg: Double,
    val hvacOn: Boolean,
    val cabinSetC: Double,
    val weather: Weather,
    val night: Boolean,
    /** 타이어 공기압 부족 비율 (권장 대비, 0 = 정상, 0.2 = 20% 부족) */
    val tireUnderinflation: Double,
    /** 가속에 쓴 운동에너지 (Wh) */
    val accelKineticWh: Double,
    val ascentM: Double = 0.0,
    val descentM: Double = 0.0,
)

/**
 * 요소별 소모 모델. 소모(Wh) = θ · features + 오르막.
 * θ 는 차량별로 학습한다 ([VehicleProfile.energy]).
 */
object EnergyModel {

    const val PASSENGER_KG = 70.0
    /** 내리막 회생으로 돌려받는 위치에너지 비율 */
    const val REGEN_RATIO = 0.6

    val NAMES = listOf(
        "공기저항 (Wh/km @100km/h)", "굴러가는 저항 (Wh/km/t)", "공조 (Wh/h/°C)", "공조 기본 (Wh/h)",
        "저온 손실 (Wh/km/°C)", "가속 손실 비율", "젖은 노면 (Wh/km/t)", "눈길 (Wh/km/t)",
        "밤 조명·김서림 (Wh/h)", "타이어 공기압 부족 (Wh/km/t 당 100%)",
    )
    /** 같은 급(중형 전기 SUV) 평균에서 시작. 차종별 평균은 서버에서 받아 교체 예정. */
    val PRIOR_MEAN = doubleArrayOf(95.0, 30.0, 100.0, 300.0, 1.5, 0.35, 4.0, 12.0, 60.0, 15.0)
    val PRIOR_STD = doubleArrayOf(12.0, 5.0, 25.0, 100.0, 0.5, 0.15, 3.0, 8.0, 40.0, 10.0)

    fun totalMassKg(curbKg: Double, occupants: Int, luggageKg: Double) = curbKg + occupants * PASSENGER_KG + luggageKg

    fun features(e: EnergyInput): DoubleArray {
        val speed = if (e.hours > 0) e.distKm / e.hours else 0.0
        val hvacH = if (e.hvacOn) e.hours else 0.0
        val dt = e.distKm * e.massKg / 1000.0
        return doubleArrayOf(
            e.distKm * (speed / 100.0).pow(2),
            dt,
            hvacH * abs(e.cabinSetC - e.tempC),
            hvacH,
            e.distKm * max(0.0, 15.0 - e.tempC),
            e.accelKineticWh,
            if (e.weather == Weather.RAIN) dt else 0.0,
            if (e.weather == Weather.SNOW) dt else 0.0,
            if (e.night) e.hours else 0.0,
            dt * e.tireUnderinflation.coerceIn(0.0, 0.5),
        )
    }

    fun climbWh(e: EnergyInput) = e.massKg * 9.81 * (e.ascentM - REGEN_RATIO * e.descentM) / 3600.0

    /** 구간 소모의 설명 안 되는 변동 (바람·노면·신호 등). */
    fun noiseStdWh(predWh: Double) = 0.05 * max(predWh, 0.0) + 80.0

    /** km·톤당 가속 운동에너지 → 주어진 거리·중량의 가속 에너지 (Wh) */
    fun accelWh(accelWhPerKmTon: Double, distKm: Double, massKg: Double) = accelWhPerKmTon * distKm * massKg / 1000.0
}

/** 충전 곡선: 배터리 %가 높을수록 충전 속도가 떨어진다. */
object ChargeCurve {

    /** (SOC, 최대 출력 대비 비율). 일반적인 전기차 급속충전 곡선의 근사. */
    private val SHAPE = listOf(0.0 to 1.0, 0.5 to 1.0, 0.8 to 0.55, 0.9 to 0.3, 1.0 to 0.12)

    fun shape(soc: Double): Double {
        val s = soc.coerceIn(0.0, 1.0)
        for (i in 1 until SHAPE.size) {
            val (x1, y1) = SHAPE[i]
            if (s <= x1) {
                val (x0, y0) = SHAPE[i - 1]
                return y0 + (y1 - y0) * (s - x0) / (x1 - x0)
            }
        }
        return SHAPE.last().second
    }

    /** 배터리가 차가우면 느려짐. 내비로 충전소를 경유지 지정하면 차가 미리 데우므로 [preconditioned]. */
    fun coldFactor(tempC: Double, preconditioned: Boolean) =
        if (preconditioned || tempC >= 10) 1.0 else max(0.5, 1.0 - 0.025 * (10 - tempC))

    /**
     * SOC 0 → s 까지 용량 1kWh, 효율 k=1 기준 누적 충전 시간(분) 표 (0.5% 간격, 201칸).
     * 실제 시간 = (표[to] − 표[from]) × 용량 / k. 몬테카를로에서 샘플마다 적분하지 않으려고 미리 만든다.
     */
    fun cumulativeMinutesPerKwh(chargerKw: Double, vehicleMaxKw: Double, tempC: Double, preconditioned: Boolean = true): DoubleArray {
        val peak = minOf(chargerKw, vehicleMaxKw) * coldFactor(tempC, preconditioned)
        val t = DoubleArray(201)
        for (i in 1..200) {
            val mid = (i - 0.5) / 200.0
            t[i] = t[i - 1] + 0.005 / max(peak * shape(mid), 1.0) * 60.0
        }
        return t
    }

    fun lookup(table: DoubleArray, from: Double, to: Double): Double {
        fun at(s: Double): Double {
            val x = s.coerceIn(0.0, 1.0) * 200
            val i = x.toInt().coerceAtMost(199)
            return table[i] + (table[i + 1] - table[i]) * (x - i)
        }
        return if (to <= from) 0.0 else at(to) - at(from)
    }

    /** [fromSoc] → [toSoc] 충전 시간(분). 1% 단위로 적분. */
    fun minutes(
        fromSoc: Double, toSoc: Double, chargerKw: Double, capacityKwh: Double, vehicleMaxKw: Double,
        tempC: Double, k: Double, preconditioned: Boolean = true,
    ): Double {
        if (toSoc <= fromSoc) return 0.0
        val peak = minOf(chargerKw, vehicleMaxKw) * k * coldFactor(tempC, preconditioned)
        var minutes = 0.0
        var soc = fromSoc
        while (soc < toSoc - 1e-9) {
            val step = minOf(0.01, toSoc - soc)
            minutes += capacityKwh * step / max(peak * shape(soc + step / 2), 1.0) * 60.0
            soc += step
        }
        return minutes
    }
}
