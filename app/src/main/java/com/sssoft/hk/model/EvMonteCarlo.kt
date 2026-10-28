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
    /**
     * 소모 배율. 차량 내비의 도착 예상 배터리에 우리 예측의 중심을 맞출 때 쓴다 (1 = 보정 없음).
     * [energyScaleLogStd] 는 그 배율의 불확실성 (내비 대비 실제 차이를 학습할수록 줄어듦).
     */
    val energyScale: Double = 1.0,
    val energyScaleLogStd: Double = 0.0,
)

data class Charger(
    val name: String,
    val atKm: Double,          // 출발지로부터 거리
    val powerKw: Double,       // 충전기 최대 출력
    val waitMeanMin: Double,   // 도착 시 대기 평균
    /** 경로에서 들렀다 돌아오는 시간 평균 (분). 휴게소 1분, 그 외는 실제 도로 우회 */
    val detourMin: Double = 4.0,
    /** 우회 시간의 흔들림 (로그 표준편차). 휴게소 진출입은 일정(0.2), 시내는 신호·정체로 들쭉날쭉(0.6) */
    val detourSigma: Double = 0.4,
    val waitSigma: Double = 0.6,
    /** 식당이 있는 휴게소 (졸음쉼터·시내 충전소는 아님) */
    val mealOk: Boolean = false,
)

/**
 * 가는 길에 밥을 먹어야 하는 때: 출발 후 [fromMin]~[toMin] 분에 도착하는 식당 있는 충전소에서 먹으면
 * 충전이 식사 시간과 겹쳐 사실상 공짜가 된다. 그런 곳에 안 들르면 따로 [minutes]+5분을 쓴다.
 */
data class MealWindow(val fromMin: Double, val toMin: Double, val label: String = "식사", val minutes: Double = MEAL_MIN) {
    companion object {
        const val MEAL_MIN = 35.0
        /** 식사만 하려고 따로 들를 때 진출입·주차 */
        const val SEPARATE_EXTRA_MIN = 5.0
    }
}

/** 계획에 들어간 충전소 하나. */
data class PlannedStop(
    val charger: String,
    val atKm: Double,
    val chargeToSoc: Double,
    val socAtP10: Double,
    val socAtP50: Double,
    val socAtP90: Double,
    val chargeMinutesP50: Double,
    /** 우회·대기·충전(식사)까지 이 충전소에서 쓰는 시간 (중앙값) */
    val dwellMinutesP50: Double,
    /** 여기서 밥도 먹음 */
    val meal: Boolean = false,
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
        /** 충전소 도착 시 배터리 (적게 남는 경우 / 보통 / 많이 남는 경우) */
        val socAtChargerP10: Double = 0.0,
        val socAtChargerP50: Double = 0.0,
        val socAtChargerP90: Double = 0.0,
        /** 목적지 도착 시 배터리 */
        val destSocP10: Double = 0.0,
        val destSocP50: Double = 0.0,
        val destSocP90: Double = 0.0,
        /** 들르는 충전소 전체 (첫 번째 = [charger]). 비어 있으면 한 곳 */
        val stops: List<PlannedStop> = emptyList(),
        /** 식사할 때인데 충전소에서 못 먹어 따로 쓰는 시간 (분, 총 소요에 포함) */
        val mealSeparateMin: Double = 0.0,
        val mealLabel: String? = null,
    ) : ChargePlanResult {
        val stopCount: Int get() = stops.size.coerceAtLeast(1)
    }
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
        val scaleZ = DoubleArray(samples) { rng.nextGaussian() }

        init {
            val (m, s) = profile.paceLog(env.context)
            pace = DoubleArray(samples) { exp(m + rng.nextGaussian() * s) }
        }
    }

    private fun massKg(env: TripEnv) = EnergyModel.totalMassKg(profile.curbMassKg, env.occupants, env.luggageKg)

    /** 시나리오 i 에서 거리 [distKm], 내비 예상 [navMin] 구간의 (소모 kWh, 실제 주행 분). */
    private fun leg(
        d: Draws, i: Int, env: TripEnv, distKm: Double, navMin: Double, noise: DoubleArray, minSeg: Double = 1.0,
    ): Pair<Double, Double> {
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
        // 경로를 잘게 나눠 더할 때는 [minSeg] 를 작게 줘서 구간별 노이즈가 무작위 걸음처럼 쌓이게 한다
        val nSeg = (distKm / SEGMENT_KM).coerceAtLeast(minSeg)
        val wh = (pred + noise[i] * EnergyModel.noiseStdWh(pred / nSeg) * kotlin.math.sqrt(nSeg)) *
            env.energyScale * exp(d.scaleZ[i] * env.energyScaleLogStd)
        return wh.coerceAtLeast(0.0) / 1000.0 to minutes
    }

    /** 충전 없이 갈 때. probability = 도착 배터리가 [arrivalReserve] 이상일 확률 */
    fun arrivalProbability(
        soc0: Double, distKm: Double, navMin: Double, env: TripEnv, arrivalReserve: Double = profile.reserveSoc,
    ): ArrivalResult {
        val d = Draws(env)
        val remain = DoubleArray(samples)
        val minutes = DoubleArray(samples)
        for (i in 0 until samples) {
            val (kwh, min) = leg(d, i, env, distKm, navMin, d.noise1)
            remain[i] = soc0 - kwh / d.capKwh[i]
            minutes[i] = min
        }
        val p = remain.count { it >= arrivalReserve }.toDouble() / samples
        remain.sort()
        minutes.sort()
        return ArrivalResult(
            p, percentile(remain, 10.0), percentile(remain, 50.0), percentile(remain, 90.0),
            percentile(minutes, 50.0), percentile(minutes, 90.0),
        )
    }

    /**
     * 경로 위 모든 지점(출발, 충전소들, 도착)까지의 누적 소모·주행시간을 시나리오마다 한 번 계산해 둔다.
     * 구간별 노이즈는 독립이라 어느 두 지점 사이의 소모도 차이로 바로 나온다.
     */
    private inner class RouteSamples(
        val d: Draws, val env: TripEnv, val soc0: Double, totalKm: Double, totalNavMin: Double, chargers: List<Charger>,
    ) {
        val cs = chargers.filter { it.atKm > 0 && it.atKm < totalKm }.sortedBy { it.atKm }
        val n = cs.size
        val dest = n + 1
        /** 지점 p 까지 누적 (p = 0 출발, 1..n 충전소, n+1 도착) */
        val km = DoubleArray(n + 2) { p -> if (p == 0) 0.0 else if (p == dest) totalKm else cs[p - 1].atKm }
        val nav = DoubleArray(n + 2) { p -> totalNavMin * km[p] / totalKm }
        val kwh = Array(n + 2) { DoubleArray(samples) }
        val minutes = Array(n + 2) { DoubleArray(samples) }
        /** 충전소별 (대기 + 결제·연결), 우회 — 충전소마다 독립 */
        val fixed = Array(n) { DoubleArray(samples) }
        val detour = Array(n) { DoubleArray(samples) }
        val tables = cs.map { ChargeCurve.cumulativeMinutesPerKwh(it.powerKw, profile.vehicleMaxKw, env.tempC) }
        val paceMean = d.pace.average()
        val capMean = d.capKwh.average()
        val kMean = d.chargeK.average()
        private val useMean = arrayOfNulls<DoubleArray>(n + 2)
        private val useStd = arrayOfNulls<DoubleArray>(n + 2)

        init {
            for (p in 1..dest) {
                val noise = DoubleArray(samples) { rng.nextGaussian() }
                for (i in 0 until samples) {
                    val (k, m) = leg(d, i, env, km[p] - km[p - 1], nav[p] - nav[p - 1], noise, minSeg = 0.01)
                    kwh[p][i] = kwh[p - 1][i] + k
                    minutes[p][i] = minutes[p - 1][i] + m
                }
            }
            for ((c, ch) in cs.withIndex()) {
                val waitLog = ln(ch.waitMeanMin.coerceAtLeast(0.5))
                for (i in 0 until samples) {
                    fixed[c][i] = exp(waitLog + rng.nextGaussian() * ch.waitSigma) + 3.0 * exp(rng.nextGaussian() * 0.3)
                    detour[c][i] = ch.detourMin * exp(rng.nextGaussian() * ch.detourSigma)
                }
            }
        }

        /** 지점 p → 이후 각 지점 배터리 소모(비율)의 평균과 표준편차 */
        private fun stats(p: Int): Pair<DoubleArray, DoubleArray> {
            useMean[p]?.let { return it to useStd[p]!! }
            val m = DoubleArray(n + 2)
            val s = DoubleArray(n + 2)
            for (q in p + 1..dest) {
                var sum = 0.0
                var sq = 0.0
                for (i in 0 until samples) {
                    val u = (kwh[q][i] - kwh[p][i]) / d.capKwh[i]
                    sum += u; sq += u * u
                }
                m[q] = sum / samples
                s[q] = kotlin.math.sqrt((sq / samples - m[q] * m[q]).coerceAtLeast(0.0))
            }
            useMean[p] = m; useStd[p] = s
            return m to s
        }

        fun meanUse(p: Int, q: Int) = stats(p).first[q]
        /** 운 나쁜 쪽 소모 (거르기용, 조합 전체 확률은 몬테카를로로 다시 확인) */
        fun needUse(p: Int, q: Int) = stats(p).let { (m, s) -> m[q] + SCREEN_Z * s[q] }

        /** 평균값으로 본 충전소 c 체류 시간 */
        fun meanDwell(c: Int, from: Double, to: Double, meal: MealWindow?): Double {
            val ch = cs[c]
            val active = ch.waitMeanMin * exp(ch.waitSigma * ch.waitSigma / 2) + 3.0 +
                ChargeCurve.lookup(tables[c], from.coerceAtLeast(0.0), to) * capMean / kMean
            return ch.detourMin + if (meal != null) maxOf(active, meal.minutes) else active
        }
    }

    /** 거르기 단계의 후보 계획: 지점 번호(1..n), 목표 SOC, 밥 먹는 정차 순번(-1 = 없음) */
    private class Draft(val points: IntArray, val targets: DoubleArray, val mealAt: Int, val estMin: Double)

    /**
     * 정차 최대 [maxStops] 곳까지의 조합을 평균·운 나쁜 쪽 소모로 빠르게 훑어, 첫 충전소별로 유망한 계획 몇 개를 남긴다.
     * 마지막 정차의 목표는 "목적지에 기준 이상 남길 만큼"이고, 그 전 정차는 목표를 0.05 단위로 바꿔 본다
     * (출력이 큰 곳·밥 먹는 곳에서 더 채우고 다음 정차를 줄이는 게 빠를 수 있으므로).
     */
    private fun screen(rs: RouteSamples, arrivalReserve: Double, meal: MealWindow?, maxStops: Int): Map<Int, List<Draft>> {
        val keep = HashMap<Int, MutableList<Draft>>()
        val drive = rs.nav[rs.dest] * rs.paceMean
        val pts = IntArray(maxStops)
        val tgts = DoubleArray(maxStops)

        fun worst(first: Int): Double = keep[first]?.takeIf { it.size >= KEEP_PER_FIRST }?.maxOf { it.estMin } ?: Double.MAX_VALUE

        fun record(depth: Int, mealAt: Int, dwell: Double) {
            val est = drive + dwell + if (meal != null && mealAt < 0) meal.minutes + MealWindow.SEPARATE_EXTRA_MIN else 0.0
            val list = keep.getOrPut(pts[0]) { mutableListOf() }
            if (list.size >= KEEP_PER_FIRST) {
                val w = list.maxBy { it.estMin }
                if (w.estMin <= est) return
                list.remove(w)
            }
            list += Draft(pts.copyOf(depth), tgts.copyOf(depth), mealAt, est)
        }

        fun extend(prev: Int, soc: Double, depth: Int, dwell: Double, mealAt: Int) {
            if (depth > 0 && drive + dwell >= worst(pts[0])) return
            for (q in prev + 1..rs.n) {
                if (soc - rs.needUse(prev, q) < profile.reserveSoc) break
                val arr = soc - rs.meanUse(prev, q)
                val c = q - 1
                val eta = rs.nav[q] * rs.paceMean + dwell
                val mealHere = meal != null && mealAt < 0 && rs.cs[c].mealOk && eta in meal.fromMin..meal.toMin
                val toDest = ceil05(rs.needUse(q, rs.dest) + arrivalReserve).coerceAtLeast(MIN_TARGET)
                val m = if (mealHere) depth else mealAt
                pts[depth] = q
                var t = ceil05(maxOf(arr + 0.05, MIN_TARGET))
                while (t <= MAX_TARGET + 1e-9) {
                    val last = t >= toDest - 1e-9
                    // 목적지까지 충분하면 그 이상은 시간만 든다 → 마지막 정차는 딱 필요한 만큼
                    val tgt = if (last) maxOf(toDest, ceil05(arr + 0.05)) else t
                    if (tgt > MAX_TARGET + 1e-9) break
                    tgts[depth] = tgt
                    val dw = dwell + rs.meanDwell(c, arr, tgt, if (mealHere) meal else null)
                    if (last) {
                        record(depth + 1, m, dw)
                        break
                    }
                    if (depth + 1 < maxStops) extend(q, tgt, depth + 1, dw, m)
                    t += 0.05
                }
            }
        }
        extend(0, rs.soc0, 0, 0.0, -1)
        return keep
    }

    /** 후보 계획을 모든 시나리오로 정밀 계산. 확률이 모자라면 가장 자주 실패하는 구간 앞 정차의 목표를 올려 본다. */
    private fun evaluate(rs: RouteSamples, draft: Draft, arrivalReserve: Double, meal: MealWindow?, targetP: Double): ChargePlanResult.Plan? {
        val k = draft.points.size
        val targets = draft.targets.copyOf()
        val socAt = Array(k) { DoubleArray(samples) }
        val charge = Array(k) { DoubleArray(samples) }
        val dwell = Array(k) { DoubleArray(samples) }
        val total = DoubleArray(samples)
        val destSoc = DoubleArray(samples)
        val separate = if (meal != null && draft.mealAt < 0) meal.minutes + MealWindow.SEPARATE_EXTRA_MIN else 0.0
        repeat(MAX_BUMPS + 1) {
            val fails = IntArray(k + 1)
            var ok = 0
            for (i in 0 until samples) {
                val cap = rs.d.capKwh[i]
                var soc = rs.soc0
                var prev = 0
                var t = rs.minutes[rs.dest][i] + separate
                var failed = -1
                for (s in 0 until k) {
                    val q = draft.points[s]
                    val c = q - 1
                    val arr = soc - (rs.kwh[q][i] - rs.kwh[prev][i]) / cap
                    socAt[s][i] = arr
                    if (arr < profile.reserveSoc && failed < 0) failed = s
                    val from = arr.coerceAtLeast(0.0)
                    val to = maxOf(targets[s], from)
                    charge[s][i] = ChargeCurve.lookup(rs.tables[c], from, to) * cap / rs.d.chargeK[i]
                    val active = rs.fixed[c][i] + charge[s][i]
                    dwell[s][i] = rs.detour[c][i] + if (s == draft.mealAt) maxOf(active, meal!!.minutes) else active
                    t += dwell[s][i]
                    soc = to
                    prev = q
                }
                destSoc[i] = soc - (rs.kwh[rs.dest][i] - rs.kwh[prev][i]) / cap
                if (destSoc[i] < arrivalReserve && failed < 0) failed = k
                total[i] = t
                if (failed < 0) ok++ else fails[failed]++
            }
            val p = ok.toDouble() / samples
            if (p >= targetP) return summarize(rs, draft, targets, p, socAt, charge, dwell, total, destSoc, separate, meal)
            // 실패가 가장 많은 구간 바로 앞 정차에서 더 채운다. 첫 충전소까지 못 가는 게 문제면 방법 없음
            val worst = fails.indices.maxBy { fails[it] }
            if (worst == 0) return null
            targets[worst - 1] += 0.05
            if (targets[worst - 1] > MAX_TARGET + 1e-9) return null
        }
        return null
    }

    private fun summarize(
        rs: RouteSamples, draft: Draft, targets: DoubleArray, p: Double,
        socAt: Array<DoubleArray>, charge: Array<DoubleArray>, dwell: Array<DoubleArray>,
        total: DoubleArray, destSoc: DoubleArray, separate: Double, meal: MealWindow?,
    ): ChargePlanResult.Plan {
        fun sorted(a: DoubleArray) = a.copyOf().also { it.sort() }
        val stops = draft.points.indices.map { s ->
            val sa = sorted(socAt[s])
            PlannedStop(
                rs.cs[draft.points[s] - 1].name, rs.km[draft.points[s]], Math.round(targets[s] * 100) / 100.0,
                percentile(sa, 10.0), percentile(sa, 50.0), percentile(sa, 90.0),
                percentile(sorted(charge[s]), 50.0), percentile(sorted(dwell[s]), 50.0), s == draft.mealAt,
            )
        }
        val ts = sorted(total)
        val ds = sorted(destSoc)
        val first = stops.first()
        return ChargePlanResult.Plan(
            first.charger, first.chargeToSoc, p, percentile(ts, 50.0), percentile(ts, 90.0), first.chargeMinutesP50,
            first.socAtP10, first.socAtP50, first.socAtP90,
            percentile(ds, 10.0), percentile(ds, 50.0), percentile(ds, 90.0),
            stops = stops, mealSeparateMin = separate, mealLabel = meal?.label,
        )
    }

    /**
     * 첫 충전소마다, 목표 확률을 지키는 가장 빠른 계획 (그 뒤 충전소 [maxStops]−1 곳까지 포함).
     * 각 충전소에는 [VehicleProfile.reserveSoc], 목적지에는 [arrivalReserve] 이상 남아야 성공으로 본다.
     * [meal] 이 있으면 그 시간대에 식당 있는 휴게소에서 밥 먹으며 충전하는 계획도 비교한다.
     */
    fun bestPlanPerFirstStop(
        soc0: Double, totalKm: Double, totalNavMin: Double, env: TripEnv, chargers: List<Charger>,
        arrivalReserve: Double = profile.reserveSoc, targetP: Double = 0.95,
        meal: MealWindow? = null, maxStops: Int = MAX_STOPS,
    ): List<ChargePlanResult.Plan> {
        val rs = RouteSamples(Draws(env), env, soc0, totalKm, totalNavMin, chargers)
        if (rs.n == 0) return emptyList()
        return screen(rs, arrivalReserve, meal, maxStops).values.mapNotNull { drafts ->
            drafts.mapNotNull { evaluate(rs, it, arrivalReserve, meal, targetP) }.minByOrNull(::score)
        }
    }

    /**
     * (한 곳만 들르는 이전 방식, 비교용) 충전 목표 SOC 별 최선안. 목표 확률을 못 넘기면 null.
     * 충전소까지는 [VehicleProfile.reserveSoc], 목적지에는 [arrivalReserve] 이상 남아야 성공으로 본다.
     */
    private fun bestForCharger(
        d: Draws, soc0: Double, totalKm: Double, totalNavMin: Double, env: TripEnv, c: Charger,
        arrivalReserve: Double, targetP: Double,
    ): ChargePlanResult.Plan? {
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
            val detour = c.detourMin * exp(d.detourZ[i] * c.detourSigma)
            fixedMin[i] = wait + d.overhead[i] + detour
        }

        var best: ChargePlanResult.Plan? = null
        val total = DoubleArray(samples)
        val chargeMin = DoubleArray(samples)
        val destSoc = DoubleArray(samples)
        val socAtSorted = socAt.copyOf().also { it.sort() }
        for (step in 0..13) {
            val tgt = 0.30 + step * 0.05
            var ok = 0
            for (i in 0 until samples) {
                val from = socAt[i].coerceAtLeast(0.0)
                val to = maxOf(tgt, from)
                chargeMin[i] = ChargeCurve.lookup(table, from, to) * d.capKwh[i] / d.chargeK[i]
                total[i] = leg1Min[i] + leg2Min[i] + fixedMin[i] + chargeMin[i]
                destSoc[i] = to - leg2Kwh[i] / d.capKwh[i]
                if (socAt[i] >= profile.reserveSoc && destSoc[i] >= arrivalReserve) ok++
            }
            val p = ok.toDouble() / samples
            if (p < targetP) continue
            val ts = total.copyOf().also { it.sort() }
            val cs = chargeMin.copyOf().also { it.sort() }
            val ds = destSoc.copyOf().also { it.sort() }
            val plan = ChargePlanResult.Plan(
                c.name, tgt, p, percentile(ts, 50.0), percentile(ts, 90.0), percentile(cs, 50.0),
                percentile(socAtSorted, 10.0), percentile(socAtSorted, 50.0), percentile(socAtSorted, 90.0),
                percentile(ds, 10.0), percentile(ds, 50.0), percentile(ds, 90.0),
            )
            if (best == null || score(plan) < score(best)) best = plan
        }
        return best
    }

    /** 충전소 한 곳만 들를 때, 충전소마다 목표 확률을 만족하는 최선의 충전 목표 SOC. 만족 못 하는 충전소는 빠진다. */
    fun bestStopPerCharger(
        soc0: Double, totalKm: Double, totalNavMin: Double, env: TripEnv, chargers: List<Charger>,
        arrivalReserve: Double = profile.reserveSoc, targetP: Double = 0.95,
    ): List<ChargePlanResult.Plan> {
        val d = Draws(env)
        return chargers.mapNotNull { bestForCharger(d, soc0, totalKm, totalNavMin, env, it, arrivalReserve, targetP) }
    }

    /** 충전 없이 목표 확률을 못 넘기면, 확률 조건을 만족하는 최소 시간 계획을 탐색. */
    fun planCharging(
        soc0: Double, totalKm: Double, totalNavMin: Double, env: TripEnv, chargers: List<Charger>,
        arrivalReserve: Double = profile.reserveSoc, targetP: Double = 0.95,
        meal: MealWindow? = null, maxStops: Int = MAX_STOPS,
    ): ChargePlanResult {
        val p0 = arrivalProbability(soc0, totalKm, totalNavMin, env, arrivalReserve).probability
        if (p0 >= targetP) return ChargePlanResult.NoChargeNeeded(p0)
        return bestPlanPerFirstStop(soc0, totalKm, totalNavMin, env, chargers, arrivalReserve, targetP, meal, maxStops)
            .minByOrNull(::score) ?: ChargePlanResult.Infeasible(p0)
    }

    companion object {
        /** 학습 구간 길이 (km). 소모 노이즈 모델의 기준 */
        const val SEGMENT_KM = 10.0
        /** 한 번 여행에 들르는 충전소 최대 수 */
        const val MAX_STOPS = 3
        const val MIN_TARGET = 0.20
        const val MAX_TARGET = 0.95
        /** 거르기 단계에서 구간별 "운 나쁜 쪽" 소모 기준 (표준편차 배수) */
        private const val SCREEN_Z = 1.75
        private const val KEEP_PER_FIRST = 3
        private const val MAX_BUMPS = 8

        fun ceil05(x: Double) = kotlin.math.ceil(x / 0.05 - 1e-6) * 0.05

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
