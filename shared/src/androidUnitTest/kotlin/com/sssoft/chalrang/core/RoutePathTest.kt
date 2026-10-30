package com.sssoft.chalrang.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class RoutePathTest {

    @Test
    fun `시연 경로 선은 시나리오 길이·산길 굽이·높이를 그대로`() {
        val s = DriveScenario.weekendTrip(DriverStyle.SMOOTH)
        val p = RoutePath.of(s)
        assertEquals(s.totalM, p.lengthM, 10.0)
        // 산길: 굽은 정도 1/70 m, 시속 50km → 좌우 약 0.28 g
        val mountain = p.lengthM - 1500
        val maxG = (0 until 60).maxOf { p.lateralG(mountain + it * 5.0) }
        assertTrue("$maxG", maxG in 0.2..0.35)
        assertTrue(p.climb(mountain, 400.0) > 5)
        assertEquals("고속도로는 편한 길", 0, p.riskAt(4_000.0))
    }

    @Test
    fun `내비 지점으로 다시 그린 길은 회전 방향대로 돈다`() {
        val pts = listOf(RoutePoint(ManeuverKind.TURN, 500.0, dir = 1), RoutePoint(ManeuverKind.TURN, 1500.0, dir = -1))
        val plan = RoutePlanner.plan(pts, 3_000.0)
        val p = RoutePath.synthesize(pts, 3_000.0, null, plan)
        assertTrue(p.synthetic)
        val h0 = p.heading[p.index(400.0)]
        val h1 = p.heading[p.index(1000.0)]
        val h2 = p.heading[p.index(2000.0)]
        assertEquals("오른쪽으로 90도", -PI / 2, h1 - h0, 0.05)
        assertTrue("왼쪽으로 돌아 원래 방향", abs(h2 - h0) < 0.05)
    }
}
