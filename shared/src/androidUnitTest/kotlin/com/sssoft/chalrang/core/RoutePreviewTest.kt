package com.sssoft.chalrang.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePreviewTest {

    @Test
    fun `시연 경로에서 산길을 위험 구간으로, 고속도로는 편한 길로`() {
        val s = DriveScenario.weekendTrip(DriverStyle.SMOOTH)
        val p = RoutePlanner.plan(s.routePoints(), s.totalM, s.durationSec, s.terrain())
        println(p.summary + " / " + p.segments.joinToString { "${RoutePreview.place(it)} L${it.level} ${it.reasons}" } + " / " + p.restAdvice)
        val mountainStart = s.totalM - 14 * 13 * 50 / 3.6 - 20 * 30 / 3.6 - 100
        val danger = p.segments.filter { it.level >= 2 }
        assertTrue("산길이 위험", danger.any { it.endM > mountainStart })
        // 고속도로 (약 2~6km) 는 조용
        val highway = p.segments.filter { it.startM > 2_500 && it.endM < 6_000 }
        assertTrue(highway.isEmpty())
        assertTrue(p.summary.contains("멀미 위험 구간"))
    }

    @Test
    fun `지점이 없고 평지면 편한 길`() {
        val p = RoutePlanner.plan(emptyList(), 20_000.0, 1200.0, Terrain(200.0, DoubleArray(101) { 30.0 + it % 2 }))
        assertTrue(p.segments.isEmpty())
        assertTrue(p.summary.contains("편한 길"))
    }

    @Test
    fun `휴게소가 위험 구간 앞에 있으면 쉬어 가기`() {
        val pts = listOf(RoutePoint(ManeuverKind.REST_AREA, 8_000.0)) + List(6) { RoutePoint(ManeuverKind.SHARP_TURN, 12_000.0 + it * 150) }
        val p = RoutePlanner.plan(pts, 20_000.0)
        assertEquals(1, p.dangerCount)
        assertNotNull(p.restAdvice)
    }

    @Test
    fun `오르내림은 올랐다 내려온 만큼`() {
        val t = Terrain(1_000.0, doubleArrayOf(10.0, 50.0, 10.0))
        assertEquals(40.0, t.climbPerKm(0.0, 2_000.0), 1e-9)
        assertEquals("한쪽으로만 오르면 0", 0.0, Terrain(1_000.0, doubleArrayOf(10.0, 50.0, 90.0)).climbPerKm(0.0, 2_000.0), 1e-9)
    }

}
