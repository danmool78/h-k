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

    /** 50km 마다 휴게소 (100kW, 대기 3분). [mealFrom] 이후 휴게소에는 식당이 있다 */
    private fun everyFifty(totalKm: Double) = (1 until (totalKm / 50).toInt()).map {
        Charger("R${it * 50}", it * 50.0, 100.0, 3.0, detourMin = 1.0, detourSigma = 0.2, mealOk = true)
    }

    @Test
    fun `배터리 작은 차의 장거리는 한 번으로 안 되고 여러 번 나눠 충전한다`() {
        val small = profile.copy(capacityKwh = 40.0)
        val chargers = everyFifty(350.0)
        val mc = EvMonteCarlo(small)
        val one = mc.planCharging(0.9, 350.0, 230.0, env(tempC = 15.0), chargers, arrivalReserve = 0.3, maxStops = 1)
        assertTrue("한 곳만으로는 불가", one is ChargePlanResult.Infeasible)
        val multi = EvMonteCarlo(small).planCharging(0.9, 350.0, 230.0, env(tempC = 15.0), chargers, arrivalReserve = 0.3)
        assertTrue(multi is ChargePlanResult.Plan)
        multi as ChargePlanResult.Plan
        assertTrue("2곳 이상", multi.stops.size >= 2)
        assertTrue(multi.probability >= 0.95)
        assertTrue("순서대로", multi.stops.zipWithNext().all { (a, b) -> a.atKm < b.atKm })
        assertTrue("운 나빠도 각 충전소에 10% 근처 이상", multi.stops.all { it.socAtP10 > 0.08 })
        assertTrue("도착 30% 이상", multi.destSocP10 >= 0.28)
    }

    @Test
    fun `여러 번 나눠 짧게 충전하는 게 빠르면 그렇게 고른다 (80% 이후 느린 충전 회피)`() {
        val chargers = everyFifty(450.0)
        val mc = EvMonteCarlo(profile)
        val one = mc.bestStopPerCharger(0.8, 450.0, 300.0, env(tempC = 0.0), chargers, arrivalReserve = 0.3)
            .minByOrNull(EvMonteCarlo::score)
        val best = EvMonteCarlo(profile).planCharging(0.8, 450.0, 300.0, env(tempC = 0.0), chargers, arrivalReserve = 0.3)
        best as ChargePlanResult.Plan
        if (one != null) assertTrue("한 번 충전보다 느리지 않다", EvMonteCarlo.score(best) <= EvMonteCarlo.score(one) + 3)
    }

    @Test
    fun `점심때 식당 있는 휴게소에서 밥 먹으며 더 채운다`() {
        val chargers = everyFifty(450.0)
        val lunch = MealWindow(110.0, 190.0, "점심")
        val plan = EvMonteCarlo(profile).planCharging(0.7, 450.0, 300.0, env(tempC = 0.0), chargers, arrivalReserve = 0.3, meal = lunch)
        plan as ChargePlanResult.Plan
        val m = plan.stops.firstOrNull { it.meal }
        assertTrue("점심 정차가 있다: ${plan.stops}", m != null)
        assertEquals(0.0, plan.mealSeparateMin, 1e-9)
        assertTrue("식사 시간만큼 머문다", m!!.dwellMinutesP50 >= MealWindow.MEAL_MIN)
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
