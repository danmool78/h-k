package com.sssoft.chalrang.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class WaterCupTest {

    private fun drive(scenario: DriveScenario, level: Double = WaterCup.DEFAULT_LEVEL): Pair<WaterCup, Double> {
        val cup = WaterCup(level)
        var maxDose = 0.0
        for (f in scenario.frames(0.1)) {
            if (f.resting) cup.rest(0.1) else cup.step(f.ax, f.ay, 0.1)
            if (cup.dose > maxDose) maxDose = cup.dose
        }
        return cup to maxDose
    }

    @Test
    fun `부드러운 운전은 경고 없이, 거친 운전은 위험까지`() {
        val (smooth, smoothMax) = drive(DriveScenario.weekendTrip(DriverStyle.SMOOTH))
        val (rough, roughMax) = drive(DriveScenario.weekendTrip(DriverStyle.ROUGH))
        println("부드럽게: 최대 누적 %.2f, 넘침 %d번 / 거칠게: 최대 누적 %.2f, 넘침 %d번".format(smoothMax, smooth.spillCount, roughMax, rough.spillCount))
        assertTrue("부드러운 운전은 주의 아래", smoothMax < CarePolicy.CAUTION)
        assertTrue("거친 운전은 위험", roughMax >= CarePolicy.DANGER)
    }

    @Test
    fun `물 높이가 높은 아이가 더 빨리 멀미`() {
        val s = DriveScenario.weekendTrip(DriverStyle.ROUGH)
        val low = drive(s, 0.3).second
        val mid = drive(s, 0.6).second
        val high = drive(s, 0.85).second
        println("물 높이 0.3 → %.2f, 0.6 → %.2f, 0.85 → %.2f".format(low, mid, high))
        assertTrue(low < mid && mid < high)
    }

    @Test
    fun `멀미 대역 0점2Hz 흔들림이 빠른 흔들림보다 많이 출렁인다`() {
        fun peak(freq: Double): Double {
            val cup = WaterCup()
            var peak = 0.0
            var t = 0.0
            while (t < 60) {
                cup.step(1.5 * sin(2 * PI * freq * t), 0.0, 0.02)
                if (t > 30 && cup.tilt > peak) peak = cup.tilt
                t += 0.02
            }
            return peak
        }
        val slow = peak(0.2)
        val fast = peak(1.5)
        val verySlow = peak(0.02)
        println("0.2Hz %.3f g, 1.5Hz %.3f g, 0.02Hz %.3f g".format(slow, fast, verySlow))
        assertTrue(slow > fast * 3)
        assertTrue(slow > verySlow * 3)
    }

    @Test
    fun `쉬면 누적량이 줄어든다`() {
        val cup = WaterCup(0.8)
        repeat(600) { i -> cup.step(3.5 * sin(2 * PI * 0.2 * i * 0.1), 0.0, 0.1) }
        val before = cup.dose
        assertTrue(before > 0)
        repeat(6000) { cup.rest(0.1) }   // 10분
        assertEquals(before / Math.E, cup.dose, before * 0.05)
    }
}
