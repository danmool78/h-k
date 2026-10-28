package com.sssoft.hk.model

import java.util.Random
import kotlin.math.exp
import kotlin.math.ln

/**
 * EV 몬테카를로 모델. prototype/ev_montecarlo.py 의 Kotlin 포팅.
 *   1) 목적지 도달 확률 (주행가능거리 "확률 게이지")
 *   2) 충전 경유 계획 (충전소별 대기시간 반영, 총소요시간 + 안전마진 균형)
 *
 * 모든 기본값은 예시입니다. 실제 차량/주행 로그로 파라미터를 피팅해야 합니다.
 */
data class EvSpec(
    val batteryKwh: Double = 77.0,     // 공칭 용량
    val sohMean: Double = 0.95,        // 배터리 건강도 평균
    val sohStd: Double = 0.015,
    val baseWhPerKm: Double = 150.0,   // 기준 전비 (20°C, 평지, 90km/h)
    val reserveSoc: Double = 0.10,     // 도착 시 최소 허용 SOC
    val massKg: Double = 1900.0,
) {
    val nominalUsableKwh: Double get() = batteryKwh * sohMean
}

data class Charger(
    val name: String,
    val atKm: Double,          // 출발지로부터 거리
    val powerKw: Double,       // 평균 충전 출력
    val waitMeanMin: Double,   // 대기시간 평균
    val waitSigma: Double = 0.6,
)

data class ArrivalResult(
    val probability: Double,
    val socP10: Double,
    val socP50: Double,
    val socP90: Double,
)

sealed interface ChargePlanResult {
    data class NoChargeNeeded(val probability: Double) : ChargePlanResult
    data class Plan(
        val charger: String,
        val chargeToSoc: Double,
        val probability: Double,
        val minutesP50: Double,
        val minutesP90: Double,
    ) : ChargePlanResult
    data class Infeasible(val probability: Double) : ChargePlanResult
}

class EvMonteCarlo(
    private val samples: Int = 2_000,
    seed: Long = 42,
) {
    private val rng = Random(seed)

    /** 구간 에너지 소비(kWh) 분포. */
    fun sampleConsumption(v: EvSpec, distKm: Double, tempC: Double, ascentM: Double): DoubleArray {
        val cold = (20.0 - tempC).coerceAtLeast(0.0)
        val climbJoulePerKwh = 3.6e6
        return DoubleArray(samples) {
            // 전비 변동: 운전 스타일/교통 -> 로그정규
            val style = exp(rng.nextGaussian() * 0.10)
            // 기온 영향: 저온일수록 히터/내부저항으로 증가
            val tempFactor = 1.0 + 0.012 * cold + rng.nextGaussian() * 0.03
            // 경사 영향: 누적 상승고도의 위치에너지 (회생으로 일부 회수)
            val regen = 0.5 + rng.nextDouble() * 0.25
            val climbKwh = (v.massKg * 9.81 * ascentM / climbJoulePerKwh) * (1.0 - regen * 0.5)
            val flatKwh = v.baseWhPerKm * distKm / 1000.0 * style * tempFactor
            flatKwh + climbKwh
        }
    }

    fun sampleUsableKwh(v: EvSpec, soc0: Double): DoubleArray = DoubleArray(samples) {
        val soh = (v.sohMean + rng.nextGaussian() * v.sohStd).coerceIn(0.6, 1.0)
        v.batteryKwh * soh * soc0
    }

    fun arrivalProbability(v: EvSpec, soc0: Double, distKm: Double, tempC: Double, ascentM: Double): ArrivalResult {
        val avail = sampleUsableKwh(v, soc0)
        val need = sampleConsumption(v, distKm, tempC, ascentM)
        val cap = v.nominalUsableKwh
        val remainSoc = DoubleArray(samples) { (avail[it] - need[it]) / cap }
        val p = remainSoc.count { it >= v.reserveSoc }.toDouble() / samples
        remainSoc.sort()
        return ArrivalResult(p, percentile(remainSoc, 10.0), percentile(remainSoc, 50.0), percentile(remainSoc, 90.0))
    }

    /** 한 충전소 경유 시 (도달확률, 총소요시간 샘플). */
    fun tripWithStop(
        v: EvSpec, soc0: Double, totalKm: Double, tempC: Double, ascentPerKm: Double,
        speedKmh: Double, charger: Charger, chargeToSoc: Double,
    ): Pair<Double, DoubleArray> {
        val d1 = charger.atKm
        val d2 = totalKm - charger.atKm
        val cap = v.nominalUsableKwh

        val avail1 = sampleUsableKwh(v, soc0)
        val need1 = sampleConsumption(v, d1, tempC, ascentPerKm * d1)
        val need2 = sampleConsumption(v, d2, tempC, ascentPerKm * d2)

        val targetKwh = cap * chargeToSoc
        val driveMin = totalKm / speedKmh * 60.0
        val logWait = ln(charger.waitMeanMin)

        var ok = 0
        val totalMin = DoubleArray(samples) {
            val atCharger = avail1[it] - need1[it]
            val ok1 = atCharger / cap >= v.reserveSoc

            val addKwh = (targetKwh - atCharger.coerceAtLeast(0.0)).coerceAtLeast(0.0)
            val chargeMin = addKwh / charger.powerKw * 60.0
            val waitMin = exp(logWait + rng.nextGaussian() * charger.waitSigma)

            val start2 = atCharger.coerceAtLeast(0.0) + addKwh
            val ok2 = (start2 - need2[it]) / cap >= v.reserveSoc
            if (ok1 && ok2) ok++

            driveMin + waitMin + chargeMin
        }
        return ok.toDouble() / samples to totalMin
    }

    /** 충전 없이 목표 확률을 못 넘기면, 확률 조건을 만족하는 최소 시간 계획을 탐색. */
    fun planCharging(
        v: EvSpec, soc0: Double, totalKm: Double, tempC: Double, ascentPerKm: Double,
        speedKmh: Double, chargers: List<Charger>, targetP: Double = 0.95,
    ): ChargePlanResult {
        val p0 = arrivalProbability(v, soc0, totalKm, tempC, ascentPerKm * totalKm).probability
        if (p0 >= targetP) return ChargePlanResult.NoChargeNeeded(p0)

        var best: ChargePlanResult.Plan? = null
        var bestScore = Double.MAX_VALUE
        for (c in chargers) {
            for (step in 0..12) {
                val tgt = 0.30 + step * 0.05
                val (p, t) = tripWithStop(v, soc0, totalKm, tempC, ascentPerKm, speedKmh, c, tgt)
                if (p < targetP) continue
                t.sort()
                val t50 = percentile(t, 50.0)
                val t90 = percentile(t, 90.0)
                // 비용: 중앙값 + 지연 리스크(P90) 가중
                val score = 0.6 * t50 + 0.4 * t90
                if (score < bestScore) {
                    bestScore = score
                    best = ChargePlanResult.Plan(c.name, tgt, p, t50, t90)
                }
            }
        }
        return best ?: ChargePlanResult.Infeasible(p0)
    }

    companion object {
        /** numpy.percentile 기본(linear) 방식. [sorted]는 정렬되어 있어야 함. */
        fun percentile(sorted: DoubleArray, q: Double): Double {
            val pos = (sorted.size - 1) * q / 100.0
            val lo = pos.toInt()
            val hi = minOf(lo + 1, sorted.size - 1)
            return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
        }
    }
}
