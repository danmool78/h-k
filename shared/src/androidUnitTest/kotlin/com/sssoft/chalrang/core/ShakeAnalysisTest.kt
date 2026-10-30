package com.sssoft.chalrang.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class ShakeAnalysisTest {

    private fun record(seconds: Double, accel: (Double) -> Pair<Double, Double>): DriveTrace {
        val trace = DriveTrace()
        val cup = WaterCup()
        var t = 0.0
        while (t < seconds) {
            val (ax, ay) = accel(t)
            cup.step(ax, ay, 0.05)
            trace.record(t, ax, ay, cup.tiltX, cup.tiltY, 40.0, cup.dose, cup.level, cup.spilling)
            t += 0.05
        }
        return trace
    }

    @Test
    fun `6초 주기 흔들림이면 주된 주기 6초, 대부분 멀미 대역`() {
        val trace = record(240.0) { t -> 1.5 * sin(2 * PI * t / 6.0) to 0.0 }
        val r = ShakeAnalysis.analyze(trace, 0.0, 240.0)
        assertNotNull(r)
        println("주기 %.1f초, 멀미 주기 %.1f초, 대역 %.0f%%, MSDV %.1f".format(r!!.peakPeriod, r.sicknessPeriod, r.bandShare * 100, r.msdv))
        assertEquals(6.0, r.peakPeriod, 0.4)
        assertEquals(6.0, r.sicknessPeriod, 0.4)
        assertTrue(r.bandShare > 0.9)
        // 진폭 스펙트럼은 사인파 진폭을 그대로 보여 준다
        assertEquals(1.5, r.longAmp.max(), 0.15)
        // MSDV ≈ Wf(1/6Hz)·진폭/√2·√시간 (필터가 자리 잡는 처음 몇 초는 덜 쌓인다)
        val expect = Wf.gain(1 / 6.0) * 1.5 / sqrt(2.0) * sqrt(240.0)
        assertEquals(expect, r.msdv, expect * 0.05)
    }

    @Test
    fun `빠른 진동은 세게 흔들려도 멀미 대역 밖, 멀미 주기는 느린 쪽`() {
        // 1초 주기 진동(세다) + 8초 주기 흔들림(약하다)
        val trace = record(240.0) { t -> 2.0 * sin(2 * PI * t / 1.0) + 0.5 * sin(2 * PI * t / 8.0) to 0.0 }
        val r = ShakeAnalysis.analyze(trace, 0.0, 240.0)!!
        println("주기 %.1f초, 멀미 주기 %.1f초, 대역 %.0f%%".format(r.peakPeriod, r.sicknessPeriod, r.bandShare * 100))
        assertEquals(1.0, r.peakPeriod, 0.1)
        assertEquals(8.0, r.sicknessPeriod, 0.8)
    }

    @Test
    fun `시나리오 - 거친 운전이 부드러운 운전보다 MSDV 가 크다`() {
        fun msdv(style: DriverStyle): ShakeReport {
            val trace = DriveTrace()
            val cup = WaterCup()
            for (f in DriveScenario.weekendTrip(style).frames(0.05)) {
                cup.step(f.ax, f.ay, 0.05, f.az)
                trace.record(f.t, f.ax, f.ay, cup.tiltX, cup.tiltY, f.speedKmh, cup.dose, cup.level, cup.spilling, f.az, cup.tiltZ)
            }
            return ShakeAnalysis.analyze(trace, trace.startTime, trace.endTime)!!
        }
        val rough = msdv(DriverStyle.ROUGH)
        val smooth = msdv(DriverStyle.SMOOTH)
        println("거칠게 MSDV %.1f 주기 %.1f초 멀미 주기 %.1f초 대역 %.0f%% / 부드럽게 MSDV %.1f 주기 %.1f초".format(
            rough.msdv, rough.peakPeriod, rough.sicknessPeriod, rough.bandShare * 100, smooth.msdv, smooth.peakPeriod))
        assertTrue(rough.msdv > smooth.msdv * 2)
    }
}
