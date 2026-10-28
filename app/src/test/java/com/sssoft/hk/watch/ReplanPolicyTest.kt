package com.sssoft.hk.watch

import com.sssoft.hk.model.ArrivalResult
import com.sssoft.hk.model.ChargePlanResult
import com.sssoft.hk.model.TripContext
import com.sssoft.hk.model.TripEnv
import com.sssoft.hk.model.TripType
import com.sssoft.hk.model.Weather
import com.sssoft.hk.navi.LatLng
import com.sssoft.hk.server.StationView
import com.sssoft.hk.trip.ChargingRecommendation
import com.sssoft.hk.trip.StopOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplanPolicyTest {

    private fun station(id: String, available: Int, total: Int = 4, out: Int = 0, waitMin: Double? = 0.0) = StationView(
        id = id, name = id, location = LatLng(0.0, 0.0), distanceKm = 0.0, maxKw = 200.0,
        total = total, available = available, charging = total - available - out, outOfService = out,
        waitMin = waitMin, forecastBusy = null,
    )

    private fun option(id: String, minutes: Double) =
        StopOption(station(id, 1), 100.0, 60.0, 2.0, ChargePlanResult.Plan(id, 0.8, 0.97, minutes, minutes + 10), false)

    private fun rec(vararg o: StopOption) = ChargingRecommendation(
        o.first().plan, o.toList(), null, o.size,
        direct = ArrivalResult(0.2, -0.1, 0.0, 0.1, 270.0, 290.0),
        env = TripEnv(TripContext(TripType.LONG, false, Weather.CLEAR, false), tempC = 10.0),
        conditions = com.sssoft.hk.trip.TripConditions(socPercent = 50, profile = com.sssoft.hk.model.VehicleProfile(), tempC = 10.0),
    )

    @Test
    fun `빈자리가 있으면 유지, 다 차서 대기가 길거나 고장이면 재계획`() {
        assertFalse(ReplanPolicy.targetDegraded(station("A", available = 2), etaMin = 10.0))
        assertTrue(ReplanPolicy.targetDegraded(station("A", available = 0, waitMin = 18.0), etaMin = 10.0))
        assertTrue(ReplanPolicy.targetDegraded(station("A", available = 0, total = 2, out = 2, waitMin = null), etaMin = 10.0))
        assertTrue(ReplanPolicy.targetDegraded(null, etaMin = 10.0))
    }

    @Test
    fun `5분 이상 빨라야 바꾼다`() {
        assertEquals(ReplanPolicy.Decision.Keep, ReplanPolicy.decide("A", rec(option("B", 297.0), option("A", 300.0))))
        val d = ReplanPolicy.decide("A", rec(option("B", 290.0), option("A", 300.0)))
        assertTrue(d is ReplanPolicy.Decision.Switch)
        assertEquals(10.0, (d as ReplanPolicy.Decision.Switch).savedMin!!, 1e-9)
    }

    @Test
    fun `현재 목표가 후보에서 빠지면(안전하게 못 감) 바꾼다`() {
        val d = ReplanPolicy.decide("A", rec(option("B", 300.0)))
        assertTrue(d is ReplanPolicy.Decision.Switch && d.savedMin == null)
    }

    @Test
    fun `현재 목표가 여전히 최선이면 유지`() {
        assertEquals(ReplanPolicy.Decision.Keep, ReplanPolicy.decide("A", rec(option("A", 290.0), option("B", 300.0))))
    }
}
