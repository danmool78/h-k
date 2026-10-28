package com.sssoft.hk.trip

import com.sssoft.hk.navi.LatLng
import com.sssoft.hk.server.StationView
import org.junit.Assert.assertEquals
import org.junit.Test

class TripPlannerTest {

    private fun station(waitMin: Double?, forecastBusy: Double?) = StationView(
        id = "S", name = "A휴게소", location = LatLng(0.0, 0.0), distanceKm = 0.0, maxKw = 200.0,
        total = 4, available = 0, charging = 4, outOfService = 0, waitMin = waitMin, forecastBusy = forecastBusy,
    )

    @Test
    fun `곧 도착하면 현재 대기를 쓴다`() {
        assertEquals(12.0, TripPlanner.expectedWaitMin(station(12.0, 0.9), etaMin = 10.0), 1e-9)
        assertEquals(15.0, TripPlanner.expectedWaitMin(station(null, null), etaMin = 10.0), 1e-9)
    }

    @Test
    fun `멀면 같은 시간대 평균 사용률을 쓴다`() {
        assertEquals(1.0 + 25.0 * 0.64, TripPlanner.expectedWaitMin(station(0.0, 0.8), etaMin = 90.0), 1e-9)
        // 기록이 없으면 현재 값(최소 2분)
        assertEquals(2.0, TripPlanner.expectedWaitMin(station(0.0, null), etaMin = 90.0), 1e-9)
    }

    @Test
    fun `식사 시간대 - 10시 출발 4시간이면 점심, 짧거나 밤이면 없음`() {
        val kst = java.util.TimeZone.getTimeZone("Asia/Seoul")
        fun at(h: Int, m: Int) = java.util.Calendar.getInstance(kst).apply { set(2026, 9, 31, h, m, 0) }.timeInMillis
        val lunch = TripPlanner.mealWindow(at(10, 0), 240.0, kst)!!
        assertEquals("점심", lunch.label)
        assertEquals(90.0, lunch.fromMin, 1.0)
        assertEquals(210.0, lunch.toMin, 1.0)
        assertEquals(null, TripPlanner.mealWindow(at(10, 0), 100.0, kst))
        assertEquals(null, TripPlanner.mealWindow(at(20, 0), 240.0, kst))
        // 12시 출발: 점심 시간대 안이지만 45분 이후부터
        assertEquals(45.0, TripPlanner.mealWindow(at(12, 0), 300.0, kst)!!.fromMin, 1.0)
    }
}
