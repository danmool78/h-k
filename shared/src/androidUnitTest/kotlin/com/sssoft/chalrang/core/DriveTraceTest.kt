package com.sssoft.chalrang.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveTraceTest {

    private fun recordTrip(style: DriverStyle): DriveTrace {
        val trace = DriveTrace()
        val cup = WaterCup()
        for (f in DriveScenario.weekendTrip(style).frames(0.05)) {
            if (f.resting) cup.rest(0.05) else cup.step(f.ax, f.ay, 0.05)
            trace.record(f.t, f.ax, f.ay, cup.tiltX, cup.tiltY, f.speedKmh, cup.dose, cup.level, cup.spilling)
        }
        return trace
    }

    @Test
    fun `물은 차 흔들림보다 늦게 따라온다`() {
        val lag = recordTrip(DriverStyle.ROUGH).lag()
        assertNotNull(lag)
        println("지연 %.1f초, 상관 %.2f".format(lag!!.lagSec, lag.correlation))
        assertTrue("지연 ${lag.lagSec}", lag.lagSec in 0.3..3.0)
        assertTrue(lag.correlation > 0.3)
    }

    @Test
    fun `0점1초 간격으로 남기고 시각으로 찾는다`() {
        val trace = recordTrip(DriverStyle.SMOOTH)
        assertEquals((trace.endTime - trace.startTime) / 0.1 + 1, trace.size.toDouble(), 1.0)
        val i = trace.indexAt(100.04)
        assertEquals(100.0, trace[i].t, 0.051)
    }

    @Test
    fun `가득 차면 오래된 것부터 버린다`() {
        val trace = DriveTrace(interval = 1.0, capacity = 10)
        repeat(25) { trace.record(it.toDouble(), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.5, false) }
        assertEquals(10, trace.size)
        assertEquals(15.0, trace.startTime, 1e-9)
        assertEquals(24.0, trace.endTime, 1e-9)
        assertEquals(20.0, trace[trace.indexAt(19.6)].t, 1e-9)
    }
}
