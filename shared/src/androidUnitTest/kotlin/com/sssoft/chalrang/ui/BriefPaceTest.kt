package com.sssoft.chalrang.ui

import com.sssoft.chalrang.core.ManeuverKind
import com.sssoft.chalrang.core.RoutePath
import com.sssoft.chalrang.core.RoutePlanner
import com.sssoft.chalrang.core.RoutePoint
import com.sssoft.chalrang.engine.DemoRoute
import com.sssoft.chalrang.engine.DemoRoutes
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BriefPaceTest {

    /** (전체 초, 위험 구간에 쓴 초) */
    private fun run(p: RoutePath): Pair<Double, Double> {
        val pace = BriefPace.of(p)
        var at = 0.0
        var t = 0.0
        var risk = 0.0
        while (at < p.lengthM && t < 120) {
            val before = at
            at = pace.advance(p, at, 1 / 60.0)
            t += 1 / 60.0
            if (p.preview.segments.any { before + 150 >= it.startM && before < it.endM }) risk += 1 / 60.0
        }
        return t to risk
    }

    @Test
    fun `30초 안에, 안전한 길은 건너뛰고 위험 구간에 시간을 쓴다`() {
        for (r in DemoRoute.entries) {
            if (r == DemoRoute.WEEKEND) continue
            val p = DemoRoutes.parse(File("src/commonMain/composeResources/files/routes/${r.id}.json").readText(), r)
            val (t, risk) = run(p)
            println("${r.short}: ${(p.lengthM / 1000).toInt()}km -> ${"%.1f".format(t)}s (risk ${"%.1f".format(risk)}s, x${"%.0f".format(BriefPace.of(p).riskX)})")
            assertTrue(t <= 31)
        }
        // 200km: 고속도로 사이에 산길 두 곳
        val pts = List(20) { RoutePoint(ManeuverKind.SHARP_TURN, 60_000.0 + it * 200) } + List(20) { RoutePoint(ManeuverKind.SHARP_TURN, 150_000.0 + it * 200) }
        val plan = RoutePlanner.plan(pts, 200_000.0)
        val long = RoutePath.synthesize(pts, 200_000.0, null, plan)
        val (t, risk) = run(long)
        println("200km -> ${"%.1f".format(t)}s (risk ${"%.1f".format(risk)}s, x${"%.0f".format(BriefPace.of(long).riskX)})")
        assertTrue(t <= 31 && risk > t * 0.7)
    }
}
