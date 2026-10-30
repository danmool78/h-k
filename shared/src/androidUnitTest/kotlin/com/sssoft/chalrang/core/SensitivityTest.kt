package com.sssoft.chalrang.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

class SensitivityTest {

    @Test
    fun `3번 전에는 표준 그대로`() {
        val s = Sensitivity(listOf(5.0, 5.2))
        assertEquals(1.0, s.gain, 1e-9)
        assertFalse(s.active)
    }

    @Test
    fun `같은 주기대에 모이면 그 주기를 강조, 쌓일수록 세진다`() {
        val three = Sensitivity(listOf(4.8, 5.0, 5.3))
        println("3번: 주기 %.2f초, 강조 %.2f".format(three.peakPeriod, three.gain))
        assertTrue(three.active)
        assertEquals(5.0, three.peakPeriod, 0.3)
        assertTrue(three.gain in 1.1..1.5)
        val five = three.with(5.1).with(4.9)
        println("5번: 주기 %.2f초, 강조 %.2f".format(five.peakPeriod, five.gain))
        assertTrue(five.gain > three.gain)
        assertTrue(five.gain <= five.maxGain)
        assertEquals(Sensitivity.MAX_GAIN, five.maxGain, 0.05)
    }

    @Test
    fun `멀미 주기가 흩어져 있으면 강조하지 않는다`() {
        val s = Sensitivity(listOf(2.2, 9.5, 4.5))
        println("흩어짐: 강조 %.3f".format(s.gain))
        assertTrue(s.gain < 1.05)
    }

    @Test
    fun `최근 10번만 기억`() {
        var s = Sensitivity()
        repeat(15) { s = s.with(3.0 + it * 0.1) }
        assertEquals(10, s.count)
        assertEquals(3.5, s.periods.first(), 1e-9)
    }

    private fun peak(f: Double, step: (Double) -> Double): Double {
        var m = 0.0
        var t = 0.0
        while (t < 200) {
            val y = step(sin(2 * PI * f * t))
            if (t > 120) m = max(m, abs(y))
            t += 0.05
        }
        return m
    }

    @Test
    fun `강조 필터는 중심 주기에서 배율만큼, 멀리서는 그대로`() {
        val atCenter = PeakFilter(0.05, 0.25, 1.4)
        assertEquals(1.4, peak(0.25, atCenter::step), 0.03)
        val far = PeakFilter(0.05, 0.25, 1.4)
        assertEquals(1.0, peak(0.02, far::step), 0.05)
        assertEquals(1.4, PeakFilter.gainAt(0.25, 0.25, 1.4), 1e-9)
    }

    @Test
    fun `민감 주기를 배운 물컵은 그 주기에서 더 출렁인다`() {
        fun tiltPeak(f: Double, sens: Sensitivity): Double {
            val cup = WaterCup()
            cup.sensitivity = sens
            return peak(f) { x -> cup.step(1.5 * x, 0.0, 0.05); cup.tilt }
        }
        val sens = Sensitivity(listOf(4.0, 4.1, 3.9, 4.0, 4.0))
        val std = Sensitivity()
        val near = tiltPeak(0.25, sens) / tiltPeak(0.25, std)
        val farR = tiltPeak(0.05, sens) / tiltPeak(0.05, std)
        println("4초 주기 %.2f배, 20초 주기 %.2f배 (강조 %.2f)".format(near, farR, sens.gain))
        assertEquals(sens.gain, near, 0.08)
        assertEquals(1.0, farR, 0.08)
    }

    @Test
    fun `멀미 직전에 평소보다 유독 많았던 주기를 찾는다`() {
        val trace = DriveTrace()
        val cup = WaterCup()
        var t = 0.0
        // 8분: 20초 주기 가다 서다만, 그다음 3분: 가다 서다 + 4초 주기 좌우 흔들림
        while (t < 660) {
            val ax = if ((t % 20) < 10) 1.5 else -1.5
            val ay = if (t > 480) 1.2 * sin(2 * PI * t / 4.0) else 0.0
            cup.step(ax, ay, 0.05)
            trace.record(t, ax, ay, cup.tiltX, cup.tiltY, 40.0, cup.dose, cup.level, cup.spilling)
            t += 0.05
        }
        val p = SickPeriod.find(trace, trace.endTime)
        assertNotNull(p)
        println("찾은 멀미 주기 %.2f초".format(p))
        assertEquals(4.0, p!!, 0.5)
    }

    @Test
    fun `수평 연구 가중은 0_25Hz 까지 평평, 그 위는 Wf 와 같은 기울기`() {
        for (f in listOf(0.0315, 0.05, 0.1, 0.16, 0.2)) assertEquals("$f Hz", 1.0, Wh.gain(f), 0.08)
        assertEquals(0.39, Wh.gain(0.4), 0.03)
        assertEquals(0.10, Wh.gain(0.8), 0.02)
        // 2~10초에서는 표준과 거의 같고, 긴 주기에서만 크게 다르다
        assertEquals(Wf.gain(0.2), Wh.upper(0.2), 0.02)
        assertTrue(Wh.upper(0.05) > 5 * Wf.gain(0.05))
    }

    @Test
    fun `긴 주기(20초)에 약한 아이도 배운다`() {
        val s = Sensitivity(listOf(20.0, 19.0, 21.0, 20.0, 20.0))
        println("20초: 주기 %.1f초, 강조 %.2f (최대 %.2f), Q %.2f".format(s.peakPeriod, s.gain, s.maxGain, s.q))
        assertEquals(20.0, s.peakPeriod, 1.0)
        assertTrue(s.maxGain > 5)
        assertTrue(s.gain > 3)
        // 표준 Wf × 강조가 연구 범위 위쪽 끝을 크게 넘지 않는다
        assertTrue(s.weight(0.05) <= Wh.upper(0.05) * Sensitivity.MAX_GAIN * 1.05)
        // 이웃(표준이 이미 무겁게 보는 5초)은 크게 키우지 않는다
        assertTrue(PeakFilter.gainAt(0.2, s.peakHz, s.gain, s.q) < 1.3)
    }

    @Test
    fun `긴 주기를 배운 물컵은 20초 흔들림에 더 출렁이고 5초는 거의 그대로`() {
        fun tiltPeak(f: Double, sens: Sensitivity): Double {
            val cup = WaterCup()
            cup.sensitivity = sens
            var m = 0.0
            var t = 0.0
            while (t < 400) {
                cup.step(1.5 * sin(2 * PI * f * t), 0.0, 0.05)
                if (t > 250) m = max(m, cup.tilt)
                t += 0.05
            }
            return m
        }
        val sens = Sensitivity(listOf(20.0, 19.0, 21.0, 20.0, 20.0))
        val long = tiltPeak(0.05, sens) / tiltPeak(0.05, Sensitivity())
        val mid = tiltPeak(0.2, sens) / tiltPeak(0.2, Sensitivity())
        println("20초 주기 %.2f배, 5초 주기 %.2f배 (강조 %.2f)".format(long, mid, sens.gain))
        assertEquals(sens.gain, long, sens.gain * 0.1)
        assertTrue(mid < 1.3)
    }

    @Test
    fun `멀미 직전 긴 굽은 길(20초 주기)도 멀미 주기로 찾는다`() {
        val trace = DriveTrace()
        val cup = WaterCup()
        var t = 0.0
        // 8분: 4초 주기 약한 흔들림, 그다음 3분: 20초 주기 큰 좌우 흔들림 (긴 굽은 길 연속)
        while (t < 660) {
            val ax = 0.4 * sin(2 * PI * t / 4.0)
            val ay = if (t > 480) 2.0 * sin(2 * PI * t / 20.0) else 0.0
            cup.step(ax, ay, 0.05)
            trace.record(t, ax, ay, cup.tiltX, cup.tiltY, 60.0, cup.dose, cup.level, cup.spilling)
            t += 0.05
        }
        val p = SickPeriod.find(trace, trace.endTime)
        assertNotNull(p)
        println("찾은 멀미 주기 %.2f초".format(p))
        assertEquals(20.0, p!!, 4.0)
    }

    @Test
    fun `멀미 없이 끝난 주행 - 멀미 수준을 넘었으면 기준을 조금 낮추고, 아니면 그대로`() {
        val rough = TiltLog()
        repeat(20 * 60 * 5) { rough.add(0.25, 0.2) }   // 20분 동안 꽤 큰 출렁임
        val level = 0.8
        assertTrue(rough.replayPeakDose(level) >= Calibration.TARGET_DOSE)
        val lowered = Calibration.levelAfterCalmTrip(rough, level)
        assertNotNull(lowered)
        println("멀미 없이 도착: 물 높이 %.3f → %.3f".format(level, lowered))
        assertTrue(lowered!! < level)
        assertTrue(level - lowered <= (level - WaterCup.MIN_LEVEL) * Calibration.CALM_RATE + 1e-9)

        val calm = TiltLog()
        repeat(20 * 60 * 5) { calm.add(0.02, 0.2) }
        assertEquals(null, Calibration.levelAfterCalmTrip(calm, level))
    }
}
