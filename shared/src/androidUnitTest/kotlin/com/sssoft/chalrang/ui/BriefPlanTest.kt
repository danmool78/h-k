package com.sssoft.chalrang.ui

import com.sssoft.chalrang.core.ManeuverKind
import com.sssoft.chalrang.core.RoutePath
import com.sssoft.chalrang.core.RoutePlanner
import com.sssoft.chalrang.core.RoutePoint
import com.sssoft.chalrang.core.RoutePreview
import com.sssoft.chalrang.engine.DemoRoute
import com.sssoft.chalrang.engine.DemoRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BriefPlanTest {

    @Test
    fun `구간마다 둘러보기·들어가기·달리기·올라오기·마무리, 달리기는 그 구간 처음부터 끝까지`() {
        for (r in DemoRoute.entries) {
            if (r == DemoRoute.WEEKEND) continue
            val p = DemoRoutes.parse(File("src/commonMain/composeResources/files/routes/${r.id}.json").readText(), r)
            val plan = BriefPlan(p)
            println("${r.short}: 카드 ${plan.segs.size}곳 — ${plan.headline()}")
            plan.segs.indices.forEach { i ->
                val show = plan.show(i)
                println("  ${i + 1}. ${plan.arriveText(plan.segs[i].startM)} ${"%.1f".format(show.total)}초 " + show.steps.joinToString(" ") { "${it.kind.name.take(2)}${"%.1f".format(it.dur)}" })
                assertEquals(BriefPlan.Kind.SHOW, show.steps.first().kind)
                assertEquals(BriefPlan.Kind.END, show.steps.last().kind)
                assertEquals(plan.segs[i].startM, show.driveAt(0.0), 1.0)
                assertEquals(plan.segs[i].endM, show.driveAt(1.0), 10.0)
                assertTrue(show.total <= BriefPlan.SHOW_SEC + BriefPlan.DIVE_SEC + BriefPlan.DRIVE_MAX + BriefPlan.RISE_SEC + BriefPlan.END_SEC + 0.01)
            }
            // 카드는 위험·조심 구간 모두 (많지 않으면), 위험 구간만 미리 받기
            if (p.preview.segments.size <= BriefPlan.MAX_ALL) assertEquals(p.preview.segments.size, plan.segs.size)
            plan.segs.indices.forEach { assertEquals(plan.segs[it].level >= 2, plan.eager(it)) }
        }
    }

    @Test
    fun `긴 길은 심한 구간만 카드로, 출발부터 순서대로`() {
        // 굽이가 이어지는 길(굽은 길 120m 씩 좌우로) 8곳, 180km
        val pts = (0 until 8).flatMap { k -> List(20) { RoutePoint(ManeuverKind.CURVE, 20_000.0 * k + 5_000 + it * 120) } }
        val long = RoutePath.synthesize(pts, 180_000.0, null, RoutePlanner.plan(pts, 180_000.0))
        val plan = BriefPlan(long)
        println("180km: ${long.preview.segments.size}구간 중 카드 ${plan.segs.size} — ${plan.headline()}")
        assertTrue(plan.segs.isNotEmpty())
        assertTrue(plan.segs.size <= BriefPlan.MAX_ALL)
        assertEquals(plan.segs.sortedBy { it.startM }, plan.segs)
        assertEquals(plan.segs.size, plan.peak.size)
    }

    @Test
    fun `구간은 내비 지명으로 부르고 지명이 없으면 거리로`() {
        val pts = (0 until 8).flatMap { k -> List(20) { RoutePoint(ManeuverKind.CURVE, 20_000.0 * k + 5_000 + it * 120) } }
        val base = RoutePath.synthesize(pts, 180_000.0, null, RoutePlanner.plan(pts, 180_000.0))
        val seg = base.preview.segments.first()
        val named = base.withMarks(
            base.marks +
                RoutePoint(ManeuverKind.TURN, seg.startM - 300, 1, "진부IC") +
                RoutePoint(ManeuverKind.OTHER, seg.endM + 200, 0, "횡계교차로"),
        )
        val plan = BriefPlan(named)
        val s = plan.segs.first { it.startM == seg.startM }
        assertEquals("진부IC부터 횡계교차로까지", plan.placeOf(s))
        val i = plan.segs.indexOf(s)
        val line = plan.show(i).line(0)!!
        println(line)
        assertTrue("진부IC부터 횡계교차로까지" in line)
        // 지명이 없는 구간은 거리로
        val other = plan.segs.last()
        assertEquals(RoutePreview.place(other), plan.placeOf(other))
    }
}
