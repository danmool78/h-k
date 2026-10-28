package com.sssoft.hk.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvMonteCarloTest {

    private val profile = VehicleProfile()
    private val ctx = TripContext(TripType.LONG, night = false, weather = Weather.CLEAR, weekend = false)

    private fun env(tempC: Double = 15.0, occupants: Int = 1, weather: Weather = Weather.CLEAR, tire: Double? = null) =
        TripEnv(ctx.copy(weather = weather), tempC, occupants = occupants, tirePressureKpa = tire)

    private fun socP50(e: TripEnv) = EvMonteCarlo(profile, samples = 4_000).arrivalProbability(0.9, 200.0, 130.0, e).socP50

    @Test
    fun `겨울·탑승 인원·비·타이어 공기압 부족이면 소모가 커진다`() {
        val base = socP50(env())
        assertTrue("겨울", socP50(env(tempC = -5.0)) < base - 0.03)
        assertTrue("4명", socP50(env(occupants = 4)) < base - 0.005)
        assertTrue("비", socP50(env(weather = Weather.RAIN)) < base - 0.005)
        assertTrue("공기압 20% 부족", socP50(env(tire = 200.0)) < base - 0.003)
    }

    @Test
    fun `도심 짧은 거리는 거의 확실히 도착, 겨울 400km 는 무충전 불가`() {
        val mc = EvMonteCarlo(profile, samples = 4_000)
        val short = mc.arrivalProbability(0.45, 30.0, 45.0, env(tempC = 28.0).copy(context = ctx.copy(type = TripType.COMMUTE)))
        assertEquals(1.0, short.probability, 0.001)
        assertTrue(mc.arrivalProbability(0.9, 400.0, 270.0, env(tempC = -3.0)).probability < 0.05)
    }

    @Test
    fun `겨울 400km 는 한 번 충전으로 95% 이상, 총 시간에 충전·대기 포함`() {
        val chargers = listOf(
            Charger("A휴게소", 150.0, 120.0, 4.0, detourMin = 1.0),
            Charger("B휴게소", 220.0, 200.0, 12.0, detourMin = 1.0),
            Charger("C휴게소", 280.0, 100.0, 2.0, detourMin = 1.0),
        )
        val plan = EvMonteCarlo(profile).planCharging(0.9, 400.0, 270.0, env(tempC = -3.0), chargers)
        assertTrue(plan is ChargePlanResult.Plan)
        plan as ChargePlanResult.Plan
        assertTrue(plan.probability >= 0.95)
        assertTrue("주행 270분 + 충전·대기", plan.minutesP50 > 280 && plan.minutesP50 < 360)
        assertTrue(plan.chargeMinutesP50 > 5)
    }

    @Test
    fun `충전 곡선 - 80% 넘으면 느려진다`() {
        val to80 = ChargeCurve.minutes(0.1, 0.8, 200.0, 74.0, 200.0, 20.0, k = 1.0)
        val from80 = ChargeCurve.minutes(0.8, 1.0, 200.0, 74.0, 200.0, 20.0, k = 1.0)
        assertTrue("10→80% 약 18분", to80 in 14.0..22.0)
        assertTrue("80→100% 가 10→80% 보다 오래", from80 > to80 * 0.9)
        val table = ChargeCurve.cumulativeMinutesPerKwh(200.0, 200.0, 20.0)
        assertEquals(to80, ChargeCurve.lookup(table, 0.1, 0.8) * 74.0, 0.3)
    }

    @Test
    fun `percentile 은 numpy linear 방식과 같다`() {
        val a = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        assertEquals(1.3, EvMonteCarlo.percentile(a, 10.0), 1e-9)
        assertEquals(2.5, EvMonteCarlo.percentile(a, 50.0), 1e-9)
    }
}
