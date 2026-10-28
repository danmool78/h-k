package com.sssoft.hk.learn

import com.sssoft.hk.model.TripContext
import com.sssoft.hk.model.TripType
import com.sssoft.hk.model.Weather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveRecorderTest {

    private val ctx = TripContext(TripType.LONG, false, Weather.CLEAR, false)

    private fun sample(tSec: Long, odo: Double, levelWh: Double, soc: Double) = VehicleSample(
        tSec * 1000, odo, levelWh, soc, occupants = 2, hvacOn = true, cabinSetC = 22.0,
        tireUnderinflation = 0.0, tempC = 10.0, weather = Weather.CLEAR,
    )

    @Test
    fun `100km 정속 주행 → 10km 구간 10개, 정차 10분 후 주행 종료`() {
        val r = DriveRecorder(1950.0) { 74.0 }
        r.plan = DriveRecorder.Plan(ctx, navEtaMin = 60.0)
        val events = mutableListOf<DriveRecorder.Event>()
        var t = 0L
        var odo = 0.0
        var level = 60_000.0
        // 100km/h, 10초마다 → 0.2778km, 150Wh/km
        repeat(360) {
            events += r.onSample(sample(t, odo, level, level / 74_000), ctx)
            t += 10; odo += 100.0 / 360; level -= 150.0 * 100 / 360
        }
        repeat(70) { events += r.onSample(sample(t, odo, level, level / 74_000), ctx); t += 10 }

        val segs = events.filterIsInstance<DriveRecorder.Event.Segment>()
        assertTrue("구간 9~10개: ${segs.size}", segs.size in 9..10)
        val s = segs.first().segment
        assertEquals(150.0, s.energyWh / s.distKm, 3.0)
        assertEquals(100.0, s.energyInput.distKm / s.energyInput.hours, 3.0)
        val end = events.filterIsInstance<DriveRecorder.Event.TripEnd>().single()
        assertEquals(60.0, end.actualDriveMin, 1.0)
    }

    @Test
    fun `정차 중 SOC 상승 → 충전 기록`() {
        val r = DriveRecorder(1950.0) { 74.0 }
        r.chargerKw = 200.0
        val events = mutableListOf<DriveRecorder.Event>()
        var t = 0L
        var soc = 0.20
        // 20분 동안 20% → 70%
        repeat(120) { events += r.onSample(sample(t, 1000.0, soc * 74_000, soc), ctx); t += 10; soc += 0.5 / 120 }
        repeat(40) { events += r.onSample(sample(t, 1000.0, soc * 74_000, soc), ctx); t += 10 }
        val c = events.filterIsInstance<DriveRecorder.Event.Charge>().single().session
        assertEquals(0.20, c.fromSoc, 0.01)
        assertEquals(0.70, c.toSoc, 0.01)
        assertEquals(20.0, c.minutes, 0.5)
        assertEquals(200.0, c.chargerKw, 0.0)
    }
}
