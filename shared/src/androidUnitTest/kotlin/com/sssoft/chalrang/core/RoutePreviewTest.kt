package com.sssoft.chalrang.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePreviewTest {

    /** 곧은 길 [flatM] m, 좌우로 번갈아 굽는 길(반지름 [radius] m, 60도씩) [windM] m, 다시 곧은 길 */
    private fun road(flatM: Double, windM: Double, radius: Double, marks: List<RoutePoint> = emptyList(), stopsEvery: Double? = null): RoutePath {
        val b = RoutePath.Builder { 0.0 }
        b.go(flatM, 0.0, 80.0)
        var sign = 1.0
        var w = 0.0
        while (w < windM) { b.go(radius * kotlin.math.PI / 3, sign / radius, 60.0); sign = -sign; w += radius * kotlin.math.PI / 3 }
        b.go(flatM, 0.0, 80.0)
        val stops = stopsEvery?.let { e -> (1 until (b.distM / e).toInt()).map { it * e } } ?: emptyList()
        return b.build(marks, RoutePreview.empty(b.distM), synthetic = false, stops = stops)
    }

    @Test
    fun `같은 기준 - 굽이 길은 위험, 곧은 길은 편한 길`() {
        val p = RoutePlanner.assess(road(5_000.0, 4_000.0, 50.0), 900.0)
        println(p.summary + " / " + p.riskPerKm.joinToString(" ") { "%.2f".format(it) })
        assertEquals(1, p.dangerCount)
        val seg = p.segments.first { it.level == 2 }
        assertTrue("굽이 길 자리", seg.startM >= 4_000 && seg.endM <= 10_000)
        assertTrue(p.summary.contains("멀미 위험 구간"))
        val flat = RoutePlanner.assess(road(10_000.0, 0.0, 50.0), 600.0)
        assertTrue(flat.segments.isEmpty())
        assertTrue(flat.summary.contains("편한 길"))
    }

    @Test
    fun `내비가 안내를 몇 번으로 나누든 결과가 같다`() {
        val one = listOf(RoutePoint(ManeuverKind.TURN, 6_000.0))
        val many = List(4) { RoutePoint(ManeuverKind.TURN, 6_000.0 + it * 20) }
        val a = RoutePlanner.assess(road(5_000.0, 2_000.0, 80.0, one))
        val b = RoutePlanner.assess(road(5_000.0, 2_000.0, 80.0, many))
        assertTrue(a.riskPerKm.contentEquals(b.riskPerKm))
        assertEquals(a.segments.map { it.level to it.startM }, b.segments.map { it.level to it.startM })
    }

    @Test
    fun `도심 교차로에서 서고 가는 것은 편한 길`() {
        // 400m 마다 신호에 서는 곧은 길 (도로 속도 50km/h)
        val b = RoutePath.Builder { 0.0 }
        b.go(5_000.0, 0.0, 50.0)
        val p = RoutePlanner.assess(b.build(emptyList(), RoutePreview.empty(5_000.0), false, stops = (1..11).map { it * 400.0 }))
        println("도심 a_w: " + p.riskPerKm.joinToString(" ") { "%.2f".format(it) })
        assertTrue(p.segments.isEmpty())
    }

    @Test
    fun `휴게소가 위험 구간 앞에 있으면 쉬어 가기`() {
        val p = RoutePlanner.assess(road(5_000.0, 4_000.0, 50.0, listOf(RoutePoint(ManeuverKind.REST_AREA, 3_000.0))))
        assertEquals(1, p.dangerCount)
        assertNotNull(p.restAdvice)
    }

    @Test
    fun `주의·위험 문턱은 주행 평가 70점·50점이 되는 세기`() {
        assertEquals(0.628, RoutePlanner.CAUTION_AW, 0.001)
        assertEquals(1.032, RoutePlanner.DANGER_AW, 0.001)
    }
}
