package com.sssoft.hk.model

import java.util.Random
import kotlin.math.exp
import kotlin.math.ln

/**
 * 학습 프로필 기반 몬테카를로. prototype/ev_montecarlo.py 에서 출발해 요소별 모델로 확장.
 *
 * 시나리오(샘플)마다 차량 물리값 θ, 이날의 속도·가속 습관, 소모 노이즈, 배터리 용량, 충전 효율,
 * 대기·부대 시간을 한 번 뽑고([Draws]), 모든 후보 충전소를 같은 시나리오로 비교한다(공통 난수).
 */
data class TripEnv(
    val context: TripContext,
    val tempC: Double,
    val occupants: Int = 1,
    val luggageKg: Double = 30.0,
    val hvacOn: Boolean = true,
    val cabinSetC: Double = 22.0,
    val tirePressureKpa: Double? = null,
    /** km당 누적 오르막 (m). 내리막도 비슷하다고 본다 */
    val ascentPerKm: Double = 3.0,
)

data class Charger(
    val name: String,
    val atKm: Double,          // 출발지로부터 거리
    val powerKw: Double,       // 충전기 최대 출력
    val waitMeanMin: Double,   // 도착 시 대기 평균
    /** 경로에서 들렀다 돌아오는 시간 평균 (분). 휴게소 1분, 그 외는 이탈 거리에 따라 */
    val detourMin: Double = 4.0,
    val waitSigma: Double = 0.6,
)

data class ArrivalResult(
    val probability: Double,
    val socP10: Double,
    val socP50: Double,
    val socP90: Double,
    /** 충전 없이 갈 때 주행시간 (분) */
    val minutesP50: Double,
    val minutesP90: Double,
)

sealed interface ChargePlanResult {
    data class NoChargeNeeded(val probability: Double) : ChargePlanResult
    data class Plan(
        val charger: String,
        val chargeToSoc: Double,
        val probability: Double,
        val minutesP50: Double,
        val minutesP90: Double,
        /** 총 시간 중 충전에 쓰는 시간 (중앙값) */
        val chargeMinutesP50: Double = 0.0,
    ) : ChargePlanResult
    data class Infeasible(val probability: Double) : ChargePlanResult
}

class EvMonteCarlo(
    private val profile: VehicleProfile,
    private val samples: Int = 2_000,
    seed: Long = 42,
) {
    private val rng = Random(seed)

    /** 시나리오별로 한 번 뽑는 값들. */
    private inner class Draws(env: TripEnv) {
        val theta = profile.energy.sample(rng, samples)
        val pace: DoubleArray
        val accelMul = DoubleArray(samples) { exp(rng.nextGaussian() * VehicleProfile.ACCEL_NOISE) }
        val noise1 = DoubleArray(samples) { rng.nextGaussian() }
        val noise2 = DoubleArray(samples) { rng.nextGaussian() }
        val capKwh = DoubleArray(samples) { profile.capacityKwh + rng.nextGaussian() * profile.capacityStd }
        val chargeK = DoubleArray(samples) { exp(profile.chargeLogK + rng.nextGaussian() * profile.chargeLogKStd) }
        val waitZ = DoubleArray(samples) { rng.nextGaussian() }
        /** 결제·플러그 연결 등 (분) */
        val overhead = DoubleArray(samples) { 3.0 * exp(rng.nextGaussian() * 0.3) }
        val detourZ = DoubleArray(samples) { rng.nextGaussian() }

        init {
            val (m, s) = profile.paceLog(env.context)
            pace = DoubleArray(samples) { exp(m + rng.nextGaussian() * s) }
        }
    }

    private fun massKg(env: TripEnv) = EnergyModel.totalMassKg(profile.curbMassKg, env.occupants, env.luggageKg)

    /** 시나리오 i 에서 거리 [distKm], 내비 예상 [navMin] 구간의 (소모 kWh, 실제 주행 분). */
    private fun leg(d: Draws, i: Int, env: TripEnv, distKm: Double, navMin: Double, noise: DoubleArray): Pair<Double, Double> {
        if (distKm <= 0) return 0.0 to 0.0
        val minutes = navMin * d.pace[i]
        val mass = massKg(env)
        val input = EnergyInput(
            distKm = distKm, hours = minutes / 60.0, tempC = env.tempC, massKg = mass,
            hvacOn = env.hvacOn, cabinSetC = env.cabinSetC,
            weather = env.context.weather, night = env.context.night,
            tireUnderinflation = profile.tireUnderinflation(env.tirePressureKpa),
            accelKineticWh = EnergyModel.accelWh(profile.accelWhPerKmTon(env.context), distKm, mass) * d.accelMul[i],
            ascentM = env.ascentPerKm * distKm, descentM = env.ascentPerKm * distKm,
        )
        val pred = BayesLinear.dot(d.theta[i], EnergyModel.features(input)) + EnergyModel.climbWh(input)
        // 설명 안 되는 변동은 학습 구간(약 10km)마다 독립이라 긴 주행에서는 √구간수 로만 커진다
        val nSeg = (distKm / SEGMENT_KM).coerceAtLeast(1.0)
        val wh = pred + noise[i] * EnergyModel.noiseStdWh(pred / nSeg) * kotlin.math.sqrt(nSeg)
        return wh.coerceAtLeast(0.0) / 1000.0 to minutes
    }

    fun arrivalProbability(soc0: Double, distKm: Double, navMin: Double, env: TripEnv): ArrivalResult {
        val d = Draws(env)
        val remain = DoubleArray(samples)
        val minutes = DoubleArray(samples)
        for (i in 0 until samples) {
            val (kwh, min) = leg(d, i, env, distKm, navMin, d.noise1)
            remain[i] = soc0 - kwh / d.capKwh[i]
            minutes[i] = min
        }
        val p = remain.count { it >= profile.reserveSoc }.toDouble() / samples
        remain.sort()
        minutes.sort()
        return ArrivalResult(
            p, percentile(remain, 10.0), percentile(remain, 50.0), percentile(remain, 90.0),
            percentile(minutes, 50.0), percentile(minutes, 90.0),
        )
    }

    /** 충전소 하나를 들를 때, 충전 목표 SOC 별 최선안. 목표 확률을 못 넘기면 null. */
    private fun bestForCharger(d: Draws, soc0: Double, totalKm: Double, totalNavMin: Double, env: TripEnv, c: Charger, targetP: Double): ChargePlanResult.Plan? {
        val d1 = c.atKm.coerceIn(0.0, totalKm)
        val nav1 = totalNavMin * d1 / totalKm
        val table = ChargeCurve.cumulativeMinutesPerKwh(c.powerKw, profile.vehicleMaxKw, env.tempC)

        // 목표 SOC와 무관한 부분은 미리 계산
        val socAt = DoubleArray(samples)
        val leg1Min = DoubleArray(samples)
        val leg2Kwh = DoubleArray(samples)
        val leg2Min = DoubleArray(samples)
        val fixedMin = DoubleArray(samples)
        val waitLog = ln(c.waitMeanMin.coerceAtLeast(0.5))
        for (i in 0 until samples) {
            val (k1, m1) = leg(d, i, env, d1, nav1, d.noise1)
            val (k2, m2) = leg(d, i, env, totalKm - d1, totalNavMin - nav1, d.noise2)
            socAt[i] = soc0 - k1 / d.capKwh[i]
            leg1Min[i] = m1
            leg2Kwh[i] = k2
            leg2Min[i] = m2
            val wait = exp(waitLog + d.waitZ[i] * c.waitSigma)
            val detour = c.detourMin * exp(d.detourZ[i] * 0.4)
            fixedMin[i] = wait + d.overhead[i] + detour
        }

        var best: ChargePlanResult.Plan? = null
        val total = DoubleArray(samples)
        val chargeMin = DoubleArray(samples)
        for (step in 0..13) {
            val tgt = 0.30 + step * 0.05
            var ok = 0
            for (i in 0 until samples) {
                val from = socAt[i].coerceAtLeast(0.0)
                val to = maxOf(tgt, from)
                chargeMin[i] = ChargeCurve.lookup(table, from, to) * d.capKwh[i] / d.chargeK[i]
                total[i] = leg1Min[i] + leg2Min[i] + fixedMin[i] + chargeMin[i]
                val reach1 = socAt[i] >= profile.reserveSoc
                val reach2 = to - leg2Kwh[i] / d.capKwh[i] >= profile.reserveSoc
                if (reach1 && reach2) ok++
            }
            val p = ok.toDouble() / samples
            if (p < targetP) continue
            total.sort()
            chargeMin.sort()
            val plan = ChargePlanResult.Plan(c.name, tgt, p, percentile(total, 50.0), percentile(total, 90.0), percentile(chargeMin, 50.0))
            if (best == null || score(plan) < score(best)) best = plan
        }
        return best
    }

    /** 충전소마다 목표 확률을 만족하는 최선의 충전 목표 SOC. 만족 못 하는 충전소는 빠진다. */
    fun bestStopPerCharger(
        soc0: Double, totalKm: Double, totalNavMin: Double, env: TripEnv, chargers: List<Charger>, targetP: Double = 0.95,
    ): List<ChargePlanResult.Plan> {
        val d = Draws(env)
        return chargers.mapNotNull { bestForCharger(d, soc0, totalKm, totalNavMin, env, it, targetP) }
    }

    /** 충전 없이 목표 확률을 못 넘기면, 확률 조건을 만족하는 최소 시간 계획을 탐색. */
    fun planCharging(
        soc0: Double, totalKm: Double, totalNavMin: Double, env: TripEnv, chargers: List<Charger>, targetP: Double = 0.95,
    ): ChargePlanResult {
        val p0 = arrivalProbability(soc0, totalKm, totalNavMin, env).probability
        if (p0 >= targetP) return ChargePlanResult.NoChargeNeeded(p0)
        return bestStopPerCharger(soc0, totalKm, totalNavMin, env, chargers, targetP)
            .minByOrNull(::score) ?: ChargePlanResult.Infeasible(p0)
    }

    companion object {
        /** 학습 구간 길이 (km). 소모 노이즈 모델의 기준 */
        const val SEGMENT_KM = 10.0

        /** 비용: 중앙값 + 지연 리스크(P90) 가중 */
        fun score(p: ChargePlanResult.Plan) = 0.6 * p.minutesP50 + 0.4 * p.minutesP90

        /** numpy.percentile 기본(linear) 방식. [sorted]는 정렬되어 있어야 함. */
        fun percentile(sorted: DoubleArray, q: Double): Double {
            val pos = (sorted.size - 1) * q / 100.0
            val lo = pos.toInt()
            val hi = minOf(lo + 1, sorted.size - 1)
            return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
        }
    }
}
