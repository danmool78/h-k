package com.sssoft.chalrang.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RidersTest {

    private val minji = Child("1", "민지", Seat.REAR_LEFT, level = 0.8)
    private val junho = Child("2", "준호", Seat.REAR_RIGHT, level = 0.5)

    @Test
    fun `혼자 타면 물컵 끔`() {
        val s = CupSetup.choose(emptyMap(), listOf(minji, junho))
        assertNull(s.level)
        assertFalse(s.active)
    }

    @Test
    fun `등록한 아이가 자기 자리에 타면 그 아이 기준, 둘이면 예민한 아이`() {
        val one = CupSetup.choose(mapOf(Seat.REAR_RIGHT to SeatState(true, true)), listOf(minji, junho))
        assertEquals(0.5, one.level!!, 1e-9)
        assertEquals(listOf(Window.REAR_RIGHT), one.windows)

        val both = CupSetup.choose(
            mapOf(Seat.REAR_LEFT to SeatState(true, true), Seat.REAR_RIGHT to SeatState(true, true)),
            listOf(minji, junho),
        )
        assertEquals("민지", both.primary!!.name)
        assertEquals(0.8, both.level!!, 1e-9)
        assertEquals(setOf(Window.REAR_LEFT, Window.REAR_RIGHT), both.windows.toSet())
    }

    @Test
    fun `벨트를 안 맨 좌석은 짐으로 본다, 모르는 동승자는 보통 기준`() {
        val bag = CupSetup.choose(mapOf(Seat.REAR_LEFT to SeatState(true, belted = false)), listOf(minji))
        assertFalse(bag.active)
        val guest = CupSetup.choose(mapOf(Seat.FRONT_RIGHT to SeatState(true, belted = null)), listOf(minji))
        assertEquals(CupSetup.GUEST_LEVEL, guest.level!!, 1e-9)
        assertEquals(listOf(Window.FRONT_RIGHT), guest.windows)
    }
}

class CarePolicyTest {

    private val minji = Child("1", "민지", Seat.REAR_LEFT, level = 0.7)
    private val setup = CupSetup.choose(mapOf(Seat.REAR_LEFT to SeatState(true, true)), listOf(minji))

    private fun input(t: Double, dose: Double, spill: Boolean = false, kmh: Double? = 50.0, raining: Boolean = false, rest: Double? = null, next: Upcoming? = null) =
        CareInput(t, setup, dose, spill, kmh, raining, next, rest)

    @Test
    fun `자주 넘치면 외기 유입과 아이 옆 창문, 회복되면 원래대로`() {
        val p = CarePolicy()
        var t = 0.0
        val acts = mutableListOf<CareAction>()
        repeat(4) { acts += p.decide(input(t, 0.3, spill = true)); t += 20 }
        assertTrue(acts.contains(CareAction.Chime))
        assertTrue(acts.contains(CareAction.FreshAir(true)))
        assertTrue(acts.contains(CareAction.OpenWindows(listOf(Window.REAR_LEFT), CarePolicy.WINDOW_PERCENT)))
        assertTrue(p.ventilating && p.windowsOpen)

        // 3분 넘게 안 넘치고 누적이 내려가면 되돌림
        val back = p.decide(input(t + 200, 0.1))
        assertTrue(back.contains(CareAction.CloseWindows))
        assertTrue(back.contains(CareAction.FreshAir(false)))
        assertFalse(p.ventilating)
    }

    @Test
    fun `비 오거나 고속이면 창문은 열지 않고 공조만`() {
        val rain = CarePolicy().decide(input(0.0, 0.55, raining = true))
        assertTrue(rain.contains(CareAction.FreshAir(true)))
        assertTrue(rain.none { it is CareAction.OpenWindows })

        val p = CarePolicy()
        assertTrue(p.decide(input(0.0, 0.55, kmh = 110.0)).none { it is CareAction.OpenWindows })
        // 속도가 내려가면 그때 연다
        assertTrue(p.decide(input(10.0, 0.55, kmh = 60.0)).any { it is CareAction.OpenWindows })
        // 다시 고속이면 닫는다
        assertTrue(p.decide(input(20.0, 0.55, kmh = 100.0)).contains(CareAction.CloseWindows))
    }

    @Test
    fun `위험이면 휴게소 안내, 10분에 한 번만`() {
        val p = CarePolicy()
        val a = p.decide(input(0.0, 1.1, rest = 12.4))
        assertTrue(a.contains(CareAction.Say("민지 멀미 위험이 높아요. 12km 앞 휴게소에서 쉬어 가세요")))
        assertTrue(p.decide(input(60.0, 1.2, rest = 11.0)).none { it is CareAction.Say && "휴게소" in it.text })
    }

    @Test
    fun `흔들리는 곳 앞에서 미리 예고`() {
        val p = CarePolicy()
        val a = p.decide(input(0.0, 0.35, next = Upcoming(ManeuverKind.IC_EXIT, 300.0)))
        assertTrue(a.contains(CareAction.Say("곧 진출로예요. 천천히 부드럽게")))
        val b = CarePolicy().decide(input(0.0, 0.35, next = Upcoming(ManeuverKind.UTURN, 300.0)))
        assertTrue(b.contains(CareAction.Say("곧 유턴이에요. 천천히 부드럽게")))
        // 누적이 낮으면 예고하지 않는다 (잔소리 줄이기)
        assertTrue(CarePolicy().decide(input(0.0, 0.1, next = Upcoming(ManeuverKind.IC_EXIT, 300.0))).isEmpty())
    }

    @Test
    fun `혼자가 되면 하던 조치를 되돌린다`() {
        val p = CarePolicy()
        p.decide(input(0.0, 0.6))
        val alone = CupSetup.choose(emptyMap(), emptyList())
        val a = p.decide(CareInput(5.0, alone, 0.6, false, 50.0))
        assertTrue(a.contains(CareAction.CloseWindows) && a.contains(CareAction.FreshAir(false)))
    }

    @Test
    fun `시나리오 전체 - 거친 운전은 환기·위험 안내, 부드러운 운전은 조용`() {
        fun run(style: DriverStyle): List<CareAction> {
            val cup = WaterCup(minji.level)
            val p = CarePolicy()
            val out = mutableListOf<CareAction>()
            for (f in DriveScenario.weekendTrip(style).frames(0.1)) {
                val before = cup.spillCount
                if (f.resting) cup.rest(0.1) else cup.step(f.ax, f.ay, 0.1)
                out += p.decide(CareInput(f.t, setup, cup.dose, cup.spillCount > before, f.speedKmh, f.raining, f.next, f.restAreaKm))
            }
            return out
        }
        val rough = run(DriverStyle.ROUGH)
        val smooth = run(DriverStyle.SMOOTH)
        println("거칠게: " + rough.filterIsInstance<CareAction.Say>().joinToString(" / ") { it.text })
        println("부드럽게: " + smooth.filterIsInstance<CareAction.Say>().joinToString(" / ") { it.text })
        assertTrue(rough.contains(CareAction.FreshAir(true)))
        assertTrue(rough.any { it is CareAction.Say && "휴게소" in it.text })
        assertTrue(smooth.none { it is CareAction.FreshAir || it is CareAction.OpenWindows })
    }
}

class CalibrationTest {

    @Test
    fun `지금 멀미를 누르면 그 시점 누적이 목표가 되는 물 높이를 찾는다`() {
        val log = TiltLog()
        val cup = WaterCup()
        for (f in DriveScenario.weekendTrip(DriverStyle.ROUGH).frames(0.1).take(4000)) {
            cup.step(f.ax, f.ay, 0.1)
            log.add(cup.tilt, 0.1)
        }
        val level = Calibration.levelForSickness(log)
        println("찾은 물 높이 %.3f (기록 %d개)".format(level, log.size))
        assertEquals(Calibration.TARGET_DOSE, log.replayDose(level), 0.01)

        // 그 물 높이로 같은 길을 다시 달리면, 멀미한 시점보다 먼저 위험 경고(1.0)에 닿는다
        val again = WaterCup(level)
        var dangerAt = -1
        DriveScenario.weekendTrip(DriverStyle.ROUGH).frames(0.1).take(4000).forEachIndexed { i, f ->
            again.step(f.ax, f.ay, 0.1)
            if (dangerAt < 0 && again.dose >= CarePolicy.DANGER) dangerAt = i
        }
        assertTrue("위험 경고 $dangerAt", dangerAt in 1 until 4000)
    }

    @Test
    fun `부드럽게 달렸는데도 멀미하면 가장 예민하게, 이전 기준과 섞는다`() {
        val log = TiltLog()
        repeat(1000) { log.add(0.01, 0.1) }
        assertEquals(WaterCup.MAX_LEVEL, Calibration.levelForSickness(log), 1e-9)
        assertEquals(0.5 + 0.3 * Calibration.LEARN_RATE, Calibration.blend(0.5, 0.8), 1e-9)
        assertEquals(0.8, Calibration.blend(null, 0.8), 1e-9)
    }
}

class MotionEstimatorTest {

    @Test
    fun `거리 변화로 속도·가속도, 조향각으로 좌우 가속도`() {
        val m = MotionEstimator(wheelbaseM = 2.9)
        var d = 0.0
        var v = 0.0
        var t = 0.0
        // 2 m/s² 로 10초 가속 (거리는 1m 단위로 거칠게)
        while (t < 10) {
            v += 2 * 0.1; d += v * 0.1; t += 0.1
            m.onDistance(t, kotlin.math.floor(d))
            m.update(t)
        }
        assertEquals(20.0, m.speed, 2.0)
        assertEquals(2.0, m.ax, 0.6)

        // 20 m/s 로 3초 유지
        val end = t + 3
        while (t < end) {
            d += v * 0.1; t += 0.1
            m.onDistance(t, kotlin.math.floor(d))
            m.update(t)
        }
        assertEquals(20.0, m.speed, 0.6)
        assertEquals(0.0, m.ax, 0.6)

        // 바퀴 각도 3도 왼쪽(음수) → v²·tan(3°)/L ≈ 400·0.0524/2.9 ≈ 7.2 m/s² 왼쪽(+)
        m.onSteering(t, -3.0)
        m.update(t)
        assertEquals(7.2, m.ay, 0.5)

        // 운전대 각도면 비율로 나눈다
        m.steeringRatio = MotionEstimator.ratioForMaxAngle(540.0)
        m.onSteering(t, -42.0)
        m.update(t)
        assertEquals(7.2, m.ay, 0.5)
    }

    @Test
    fun `조향각이 없으면 진행 방향 변화로`() {
        val m = MotionEstimator()
        // 반지름 100m 원을 15 m/s 로 오른쪽으로 돈다 → 2.25 m/s² 오른쪽(-)
        val r = 100.0
        var t = 0.0
        val lat0 = 37.5
        val mPerDegLat = 111_195.0
        val mPerDegLng = mPerDegLat * kotlin.math.cos(Math.toRadians(lat0))
        while (t < 20) {
            val th = 15 * t / r
            // 북쪽에서 시작해 시계 방향
            val x = r * (1 - kotlin.math.cos(th)); val y = r * kotlin.math.sin(th)
            m.onPosition(t, lat0 + y / mPerDegLat, 127.0 + x / mPerDegLng, Math.toDegrees(th) % 360)
            m.update(t)
            t += 1.0
        }
        assertEquals(15.0, m.speed, 1.0)
        assertEquals(-2.25, m.ay, 0.3)
    }
}

class SickNowTest {
    @Test
    fun `지금 멀미를 누르면 방금 위험 안내를 했어도 바로 환기·휴게소 안내`() {
        val setup = CupSetup.choose(mapOf(Seat.REAR_LEFT to SeatState(true, true)), listOf(Child("1", "민지", Seat.REAR_LEFT)))
        val p = CarePolicy()
        p.decide(CareInput(0.0, setup, 1.1, false, 50.0, restAreaKm = 5.0))
        val a = p.decide(CareInput(30.0, setup, 1.1, false, 50.0, restAreaKm = 4.6, sickNow = true))
        assertTrue(a.contains(CareAction.Say("민지 멀미 위험이 높아요. 4km 앞 휴게소에서 쉬어 가세요")))
    }
}
