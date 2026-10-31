package com.sssoft.chalrang.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class LocalSensorTest {
    private val g = 9.81

    /** 1초 동안 60Hz 로 같은 값 */
    private fun feed(s: LocalSensor, t0: Double, grav: DoubleArray, lin: DoubleArray): Double {
        var t = t0
        repeat(60) { s.onMotion(t, grav[0], grav[1], grav[2], lin[0], lin[1], lin[2]); t += 1 / 60.0 }
        s.received(t)
        return t
    }

    @Test
    fun `세워 두면 뒷면이 앞, 눕히면 위쪽이 앞`() {
        val up = LocalSensor()
        var t = feed(up, 0.0, doubleArrayOf(0.0, g, 0.0), doubleArrayOf(0.0, 0.0, 0.0))
        assertEquals("세워서", up.mount)
        up.take()
        t = feed(up, t, doubleArrayOf(0.0, g, 0.0), doubleArrayOf(0.0, 0.0, -2.0))   // 뒷면 쪽으로 가속
        val a = up.take().last()
        assertEquals(2.0, a.ax, 1e-6); assertEquals(0.0, a.az, 1e-6)
        feed(up, t, doubleArrayOf(0.0, g, 0.0), doubleArrayOf(-1.5, 0.0, 0.0))         // 왼쪽으로
        assertEquals(1.5, up.take().last().ay, 1e-6)

        val flat = LocalSensor()
        t = feed(flat, 0.0, doubleArrayOf(0.0, 0.0, g), doubleArrayOf(0.0, 0.0, 0.0))
        assertEquals("눕혀서", flat.mount)
        t = feed(flat, t, doubleArrayOf(0.0, 0.0, g), doubleArrayOf(0.0, 2.0, 1.0))
        val b = flat.take().last()
        assertEquals(2.0, b.ax, 1e-6); assertEquals(1.0, b.az, 1e-6)
        // 앞을 0.1 rad 들면 오르막
        repeat(3) { t = feed(flat, t, doubleArrayOf(0.0, -g * sin(0.1), g * cos(0.1)), doubleArrayOf(0.0, 0.0, 0.0)) }
        assertEquals(0.1, flat.take().last().gradeX, 0.005)
    }

    @Test
    fun `20Hz 로 묶고, 2초 안 오면 멈춤`() {
        val s = LocalSensor()
        val t = feed(s, 0.0, doubleArrayOf(0.0, 0.0, g), doubleArrayOf(0.0, 0.0, 0.0))
        val n = s.take().size
        assertTrue("1초에 약 20개: $n", n in 18..21)
        assertTrue(s.active(t + 1.0))
        assertFalse(s.active(t + 2.5))
    }

    @Test
    fun `값이 듬성듬성 와도 시간이 줄지 않는다`() {
        val s = LocalSensor()
        var t = 0.0
        repeat(10) { s.onMotion(t, 0.0, 0.0, g, 0.0, 0.0, 0.0); t += 0.2 }   // 5Hz 로 2초
        val n = s.take().size
        assertTrue("2초면 약 40개: $n", n in 34..41)
    }
}
