package com.sssoft.chalrang.ui

import com.sssoft.chalrang.core.ManeuverKind
import com.sssoft.chalrang.core.RoutePath
import com.sssoft.chalrang.core.RoutePlanner
import com.sssoft.chalrang.core.RoutePoint
import com.sssoft.chalrang.engine.DemoRoute
import com.sssoft.chalrang.engine.DemoRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BriefPlanTest {

    @Test
    fun `한 바퀴 → 위험 구간마다 짚기·들어가기·달리기·올라오기 → 마무리, 40초 안에`() {
        for (r in DemoRoute.entries) {
            if (r == DemoRoute.WEEKEND) continue
            val p = DemoRoutes.parse(File("src/commonMain/composeResources/files/routes/${r.id}.json").readText(), r)
            val plan = BriefPlan(p)
            println("${r.short}: ${plan.segs.size}구간 ${"%.1f".format(plan.total)}초 " + plan.steps.joinToString(" ") { "${it.kind.name.take(2)}${"%.1f".format(it.dur)}" })
            assertTrue(plan.total <= BriefPlan.CAP_SEC + 0.01 || plan.segs.size * (BriefPlan.SHOW_SEC + BriefPlan.DIVE_SEC + BriefPlan.RISE_SEC + BriefPlan.DRIVE_MIN) + BriefPlan.ORBIT_SEC + BriefPlan.END_SEC >= plan.total - 0.01)
            assertEquals(BriefPlan.Kind.ORBIT, plan.steps.first().kind)
            assertEquals(BriefPlan.Kind.END, plan.steps.last().kind)
            // 달리기는 그 구간 안에서 처음부터 끝까지
            plan.segs.indices.forEach { i ->
                assertEquals(plan.segs[i].startM, plan.driveAt(i, 0.0), 1.0)
                assertEquals(plan.segs[i].endM, plan.driveAt(i, 1.0), 10.0)
            }
        }
        // 위험 구간이 많으면 심한 4곳만, 그래도 40초 안팎
        val pts = (0 until 8).flatMap { k -> List(8) { RoutePoint(ManeuverKind.SHARP_TURN, 20_000.0 * k + 5_000 + it * 150) } }
        val long = RoutePath.synthesize(pts, 180_000.0, null, RoutePlanner.plan(pts, 180_000.0))
        val plan = BriefPlan(long)
        println("180km: ${long.preview.segments.size}구간 중 ${plan.segs.size} ${"%.1f".format(plan.total)}초")
        assertEquals(BriefPlan.MAX_SEGS, plan.segs.size)
        assertTrue(plan.total <= 41)
    }
}
