package com.sssoft.chalrang.engine

import com.sssoft.chalrang.core.DriveScenario
import com.sssoft.chalrang.core.DriveTrace
import com.sssoft.chalrang.core.DriverStyle
import com.sssoft.chalrang.core.RoutePath
import com.sssoft.chalrang.core.TripReport
import com.sssoft.chalrang.core.WaterCup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DemoRoutesTest {

    private fun path(r: DemoRoute): RoutePath =
        DemoRoutes.parse(File("src/commonMain/composeResources/files/routes/${r.id}.json").readText(), r)

    private fun drive(p: RoutePath, style: DriverStyle): TripReport {
        val cup = WaterCup()
        val trace = DriveTrace()
        for (f in DriveScenario.fromRoute(p, style).frames(0.1)) {
            cup.gradeX = f.grade
            cup.step(f.ax, f.ay, 0.1, f.az)
            trace.record(f.t, f.ax, f.ay, cup.tiltX, cup.tiltY, f.speedKmh, cup.dose, cup.level, cup.spilling, f.az, cup.tiltZ, cup.gradeX)
        }
        return TripReport.evaluate(trace)!!
    }

    @Test
    fun `실제 지역 시연 길을 읽고 달려 본다`() {
        val out = HashMap<DemoRoute, Pair<TripReport, TripReport>>()
        for (r in DemoRoute.entries) {
            if (r == DemoRoute.WEEKEND) continue
            val p = path(r)
            val smooth = drive(p, DriverStyle.SMOOTH)
            val rough = drive(p, DriverStyle.ROUGH)
            out[r] = smooth to rough
            println("${r.short} km 마다 a_w: " + p.preview.riskPerKm.joinToString(" ") { "%.2f".format(it) })
            println(p.preview.segments.joinToString { "${com.sssoft.chalrang.core.RoutePreview.place(it)} L${it.level} ${it.reasons}" })
            println(
                "${r.short}: ${(p.lengthM / 100).toInt() / 10.0}km 높이 ${p.z.min().toInt()}~${p.z.max().toInt()}m 지형 ${p.grid?.nx}x${p.grid?.ny} " +
                    "위험 ${p.preview.segments.count { it.level >= 2 }}곳 조심 ${p.preview.segments.count { it.level == 1 }}곳 / " +
                    "부드럽게 ${smooth.score}점 ${smooth.durationSec.toInt()}초 / 거칠게 ${rough.score}점 넘침 ${rough.spills} ${rough.jolts}",
            )
            assertEquals(p.lengthM, rough.distanceKm * 1000, p.lengthM * 0.1)
            assertTrue("${r.short} 부드러운 쪽이 점수가 높다", smooth.score >= rough.score)
        }
        // 산길은 고속도로보다 위험 구간이 많다
        fun dangerM(r: DemoRoute) = path(r).preview.segments.filter { it.level >= 2 }.sumOf { it.lengthM }
        println("위험 구간 길이: 대관령 ${dangerM(DemoRoute.DAEGWALLYEONG)} 한계령 ${dangerM(DemoRoute.HANGYERYEONG)} 고속도로 ${dangerM(DemoRoute.HIGHWAY)}")
        assertTrue(dangerM(DemoRoute.DAEGWALLYEONG) > dangerM(DemoRoute.HIGHWAY))
        // 도심은 교차로에서 선다
        assertTrue(path(DemoRoute.SEOUL).stops.isNotEmpty())
    }
}
