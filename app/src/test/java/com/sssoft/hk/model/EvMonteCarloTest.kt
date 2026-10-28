package com.sssoft.hk.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** prototype/ev_montecarlo.py 데모 시나리오와 같은 결과가 나오는지 확인. */
class EvMonteCarloTest {

    private val v = EvSpec()

    @Test
    fun `서울-부산 겨울 400km 는 무충전 도달 불가`() {
        val r = EvMonteCarlo(samples = 20_000).arrivalProbability(v, 0.90, 400.0, -3.0, 6.0 * 400)
        assertEquals(0.0, r.probability, 0.01)
        assertEquals(-0.26, r.socP50, 0.03)   // 파이썬: P50 = -26%
    }

    @Test
    fun `서울-부산 겨울 400km 는 한 번 충전으로 95% 이상`() {
        val chargers = listOf(
            Charger("A휴게소", 150.0, 120.0, 4.0),
            Charger("B휴게소", 220.0, 200.0, 12.0),
            Charger("C휴게소", 280.0, 100.0, 2.0),
        )
        val plan = EvMonteCarlo(samples = 20_000).planCharging(v, 0.90, 400.0, -3.0, 6.0, 95.0, chargers)
        assertTrue(plan is ChargePlanResult.Plan)
        plan as ChargePlanResult.Plan
        assertTrue(plan.probability >= 0.95)
        assertEquals(275.0, plan.minutesP50, 15.0)  // 파이썬: 중앙값 275분
    }

    @Test
    fun `도심 출퇴근 30km 여름은 거의 확실히 도착`() {
        val r = EvMonteCarlo(samples = 20_000).arrivalProbability(v, 0.45, 30.0, 28.0, 150.0)
        assertEquals(1.0, r.probability, 0.001)
        assertEquals(0.38, r.socP50, 0.02)    // 파이썬: P50 = 38%
    }

    @Test
    fun `percentile 은 numpy linear 방식과 같다`() {
        val a = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        assertEquals(1.3, EvMonteCarlo.percentile(a, 10.0), 1e-9)
        assertEquals(2.5, EvMonteCarlo.percentile(a, 50.0), 1e-9)
    }
}
