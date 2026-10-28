package com.sssoft.hk.model

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * 가상 주행으로 학습 과정을 확인한다 (시연 자료용 표 출력).
 * 참값을 가진 "실제 차량·운전자"로 주행 기록을 만들고, 프로필이 참값에 가까워지며 예측 범위가 좁아지는지 본다.
 */
class LearningSimulationTest {

    /** 실제 차량 (사전 평균과 일부러 다르게) */
    private val trueTheta = doubleArrayOf(110.0, 26.0, 80.0, 250.0, 2.2, 0.45, 5.0, 15.0, 50.0, 15.0)
    /** 실제 운전자 속도 습관 ln(실제/내비): 기본 +5%, 출퇴근 +10%, 밤 +6%, 비 +8% */
    private fun truePace(ctx: TripContext) = ln(1.05) +
        (if (ctx.type == TripType.COMMUTE) ln(1.10) else 0.0) +
        (if (ctx.night) ln(1.06) else 0.0) +
        (if (ctx.weather == Weather.RAIN) ln(1.08) else 0.0)
    /** 실제 가속 습관 (km·톤당 Wh) */
    private fun trueAccel(type: TripType) = when (type) { TripType.COMMUTE -> 40.0; TripType.SHORT -> 30.0; TripType.LONG -> 10.0 }

    private val rng = Random(7)

    private fun randomContext(): TripContext {
        val type = TripType.entries[rng.nextInt(3)]
        val w = rng.nextDouble().let { if (it < 0.15) Weather.RAIN else if (it < 0.2) Weather.SNOW else Weather.CLEAR }
        return TripContext(type, night = rng.nextDouble() < 0.3, weather = w, weekend = rng.nextDouble() < 0.25)
    }

    /** 실제 차량으로 주행 하나를 만들고 학습. 반환: 갱신된 프로필 */
    private fun drive(p0: VehicleProfile): VehicleProfile {
        var p = p0
        val ctx = randomContext()
        val tripKm = when (ctx.type) { TripType.LONG -> 150.0; TripType.COMMUTE -> 25.0; TripType.SHORT -> 12.0 }
        val navSpeed = when (ctx.type) { TripType.LONG -> 95.0; TripType.COMMUTE -> 35.0; TripType.SHORT -> 40.0 }
        val tempC = -5.0 + rng.nextDouble() * 35
        val occupants = 1 + rng.nextInt(4)
        val pace = exp(truePace(ctx) + rng.nextGaussian() * 0.05)
        val mass = EnergyModel.totalMassKg(p.curbMassKg, occupants, 30.0)

        val segKm = 10.0
        var left = tripKm
        var navMin = 0.0
        var actualMin = 0.0
        while (left > 0.1) {
            val d = minOf(segKm, left)
            val hours = d / navSpeed * pace
            val input = EnergyInput(
                distKm = d, hours = hours, tempC = tempC, massKg = mass, hvacOn = true, cabinSetC = 22.0,
                weather = ctx.weather, night = ctx.night, tireUnderinflation = 0.0,
                accelKineticWh = trueAccel(ctx.type) * d * mass / 1000.0 * exp(rng.nextGaussian() * 0.2),
            )
            val trueWh = BayesLinear.dot(trueTheta, EnergyModel.features(input))
            val measured = trueWh + rng.nextGaussian() * EnergyModel.noiseStdWh(trueWh)
            p = p.learnSegment(DriveSegment(input, measured, ctx))
            navMin += d / navSpeed * 60
            actualMin += hours * 60
            left -= d
        }
        return p.learnPace(ctx, navMin, actualMin)
    }

    /** 기준 주행 (200km 장거리, 영상 5도, 밤, 비, 2명) 예측: (도착 SOC 중앙값, P10~P90 폭, 주행시간 중앙값, P10~P90 폭) */
    private val refCtx = TripContext(TripType.LONG, night = true, weather = Weather.RAIN, weekend = false)
    private val refEnv = TripEnv(refCtx, tempC = 5.0, occupants = 2)

    private fun predict(p: VehicleProfile): DoubleArray {
        val a = EvMonteCarlo(p, samples = 4_000).arrivalProbability(0.9, 200.0, 126.0, refEnv)
        // 시간 폭은 P50~P90 로만 나오므로 P90-P50 을 2배해 대략 P10~P90 폭으로 본다
        return doubleArrayOf(a.socP50, a.socP90 - a.socP10, a.minutesP50, 2 * (a.minutesP90 - a.minutesP50))
    }

    /** 참값 기준 도착 SOC·주행시간 */
    private fun truth(p: VehicleProfile): Pair<Double, Double> {
        val mass = EnergyModel.totalMassKg(p.curbMassKg, 2, 30.0)
        val minutes = 126.0 * exp(truePace(refCtx))
        val input = EnergyInput(
            200.0, minutes / 60, 5.0, mass, true, 22.0, Weather.RAIN, true, 0.0,
            trueAccel(TripType.LONG) * 200 * mass / 1000, 600.0, 600.0,
        )
        val wh = BayesLinear.dot(trueTheta, EnergyModel.features(input)) + EnergyModel.climbWh(input)
        return 0.9 - wh / 1000 / p.capacityKwh to minutes
    }

    @Test
    fun `주행할수록 참값에 가까워지고 예측 범위가 좁아진다`() {
        var p = VehicleProfile()
        val checkpoints = setOf(0, 5, 10, 20, 40, 80)
        val rows = mutableListOf<DoubleArray>()
        val (trueSoc, trueMin) = truth(p)
        for (trip in 0..80) {
            if (trip in checkpoints) {
                val r = predict(p)
                rows += doubleArrayOf(trip.toDouble(), abs(r[0] - trueSoc) * 100, r[1] * 100, abs(r[2] - trueMin), r[3])
            }
            p = drive(p)
        }

        println("주행 수 | 도착 배터리 오차(%p) | 배터리 예측 폭(%p) | 주행시간 오차(분) | 시간 예측 폭(분)")
        rows.forEach { println("%6.0f | %18.1f | %18.1f | %16.1f | %15.1f".format(*it.toTypedArray())) }
        println("학습된 차량 값 vs 참값:")
        EnergyModel.NAMES.forEachIndexed { i, n ->
            println("  %-32s %8.2f ± %6.2f  (참값 %6.2f)".format(n, p.energy.mean[i], p.energy.std[i], trueTheta[i]))
        }

        val first = rows.first()
        val last = rows.last()
        assertTrue("배터리 오차 감소", last[1] < first[1])
        assertTrue("배터리 예측 폭 감소", last[2] < first[2] * 0.7)
        assertTrue("주행시간 오차 감소", last[3] < first[3])
        assertTrue("시간 예측 폭 감소", last[4] < first[4])
    }

    @Test
    fun `오래된 기록은 잊는다 - 차량 상태가 바뀌면 따라간다`() {
        var p = VehicleProfile()
        repeat(60) { p = drive(p) }
        val before = p.energy.mean[1]
        // 타이어 교체 등으로 굴러가는 저항 30% 증가
        trueTheta[1] *= 1.3
        repeat(60) { p = drive(p) }
        val after = p.energy.mean[1]
        println("굴러가는 저항 학습값: 변경 전 %.1f → 변경 후 %.1f (새 참값 %.1f)".format(before, after, trueTheta[1]))
        assertTrue(abs(after - trueTheta[1]) < abs(before - trueTheta[1]))
    }

    @Test
    fun `목적지 보정은 최근 30곳만 유지`() {
        var p = VehicleProfile()
        val base = TripContext(TripType.SHORT, false, Weather.CLEAR, false)
        repeat(40) { i -> p = p.learnPace(base.copy(destKey = "dest$i"), 30.0, 33.0) }
        assertTrue(p.destinations.size == VehicleProfile.MAX_DEST)
        assertTrue("가장 오래된 곳은 지워짐", "dest0" !in p.destinations)
    }

    @Test
    fun `학습도는 처음 0% 에서 주행할수록 오른다`() {
        var p = VehicleProfile()
        assertTrue(p.learningProgress.overall < 0.01)
        val seen = mutableListOf<Double>()
        repeat(40) { i ->
            p = drive(p)
            if (i in setOf(4, 19, 39)) seen += p.learningProgress.overall
        }
        println("학습도 5회 %.0f%% → 20회 %.0f%% → 40회 %.0f%%".format(seen[0] * 100, seen[1] * 100, seen[2] * 100))
        assertTrue(seen[0] > 0.1 && seen[1] > seen[0] && seen[2] >= seen[1] - 0.02)
        assertTrue(p.learningProgress.vehicle > 0.5)
    }

    @Test
    fun `프로필 저장·복원`() {
        var p = VehicleProfile()
        repeat(5) { p = drive(p) }
        val q = VehicleProfile.fromJson(p.toJson())
        assertTrue(p.toJson() == q.toJson())
    }
}
