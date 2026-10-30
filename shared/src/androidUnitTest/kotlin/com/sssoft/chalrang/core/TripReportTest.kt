package com.sssoft.chalrang.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripReportTest {

    private fun drive(style: DriverStyle): TripReport {
        val cup = WaterCup()
        val trace = DriveTrace()
        for (f in DriveScenario.weekendTrip(style).frames(0.1)) {
            cup.gradeX = f.grade
            if (f.resting) cup.rest(0.1) else cup.step(f.ax, f.ay, 0.1, f.az)
            trace.record(f.t, f.ax, f.ay, cup.tiltX, cup.tiltY, f.speedKmh, cup.dose, cup.level, cup.spilling, f.az, cup.tiltZ, cup.gradeX)
        }
        return TripReport.evaluate(trace)!!
    }

    @Test
    fun `부드러운 운전은 높은 점수, 거친 운전은 낮은 점수와 원인`() {
        val smooth = drive(DriverStyle.SMOOTH)
        val rough = drive(DriverStyle.ROUGH)
        println("부드럽게 ${smooth.score}점 ${smooth.grade} rms %.2f 넘침 ${smooth.spills} ${smooth.jolts} 멀미 %.1f%% / 거칠게 ${rough.score}점 ${rough.grade} rms %.2f 넘침 ${rough.spills} ${rough.jolts} 멀미 %.1f%% 최악 ${rough.worstCause}"
            .format(smooth.weightedRms, smooth.illnessPct, rough.weightedRms, rough.illnessPct))
        println(rough.tips + " / " + rough.spoken(80))
        assertTrue(smooth.score >= 85)
        assertEquals(TripGrade.CALM, smooth.grade)
        assertTrue(rough.score <= 50)
        assertEquals(0, smooth.jolts[Jolt.BRAKE])
        assertTrue(rough.jolts[Jolt.BRAKE]!! >= 5)
        assertTrue(rough.jolts[Jolt.TURN]!! >= 5)
        assertTrue(rough.jolts[Jolt.BUMP]!! >= 2)
        assertTrue(rough.distanceKm in 8.0..14.0)
        assertTrue(rough.calmShare < smooth.calmShare)
    }

    @Test
    fun `짧게 달리면 평가하지 않는다`() {
        val trace = DriveTrace()
        for (k in 0 until 300) trace.record(k * 0.1, 0.0, 0.0, 0.0, 0.0, 30.0, 0.0, 0.6, false)
        assertNull(TripReport.evaluate(trace))
    }
}
