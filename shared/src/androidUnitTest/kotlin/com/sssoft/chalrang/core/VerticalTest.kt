package com.sssoft.chalrang.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class VerticalTest {

    /** 차 기준 (앞, 왼쪽, 위) → 아무렇게나 붙은 기기 기준 (Z축 35도, X축 70도 돌림) */
    private fun toDevice(v: DoubleArray): DoubleArray {
        val a = 35 * PI / 180
        val b = 70 * PI / 180
        val x1 = v[0] * cos(a) - v[1] * sin(a)
        val y1 = v[0] * sin(a) + v[1] * cos(a)
        val z1 = v[2]
        return doubleArrayOf(x1, y1 * cos(b) - z1 * sin(b), y1 * sin(b) + z1 * cos(b))
    }

    /** 차 기준 가속 + 경사(앞 올라감 [pitch]) 에서 센서 값 */
    private fun sense(f: ImuFrame, t: Double, carAx: Double, carAz: Double, pitch: Double, speed: Double, mapGrade: Double? = null) {
        val g = WaterCup.G
        // 오르막: 위(중력 반작용)가 차 뒤쪽으로 기운다
        val car = doubleArrayOf(carAx - g * sin(pitch), 0.0, carAz + g * cos(pitch))
        val d = toDevice(car)
        f.update(t, d[0], d[1], d[2], carAx, 0.0, speed, mapGrade)
    }

    @Test
    fun `비탈에서 출발해도 지도 경사로 기준을 바로잡는다`() {
        val f = ImuFrame()
        var t = 0.0
        var v = 0.0
        // 지금 자리의 실제 도로 경사 (처음 300m 는 8% 오르막, 그 뒤 평지)
        fun road(d: Double) = if (d < 300) 0.08 else 0.0
        var dist = 0.0
        fun run(sec: Double, ax: Double, map: Boolean) {
            repeat((sec / 0.02).toInt()) {
                v = (v + ax * 0.02).coerceAtLeast(0.0)
                dist += v * 0.02
                val g = road(dist)
                sense(f, t, ax, 0.0, g, v, if (map) g else null)
                t += 0.02
            }
        }
        run(3.0, 0.0, map = true)        // 8% 비탈에서 멈춰 출발 → 기준 "위" 가 비탈
        run(4.0, 2.0, map = true)        // 곧게 가속 → 앞 방향
        run(3.0, -1.0, map = true)
        run(120.0, 0.0, map = true)      // 일정하게 약 5m/s (오르막 끝나고 평지까지)
        assertTrue("평지까지 왔다", dist > 400)
        assertTrue(f.mapAnchored)
        assertEquals("평지에서 0 으로 바로잡힘", 0.0, f.pitch, 0.015)
        assertEquals("기준 자리 경사 = 출발 비탈 (기준이 그만큼 기울어 있었다)", -0.08, f.pitchOffset, 0.015)
    }

    @Test
    fun `지도가 없으면 오래 달리며 평균을 0 으로`() {
        val f = ImuFrame()
        var t = 0.0
        var v = 0.0
        fun run(sec: Double, ax: Double, g: Double) {
            repeat((sec / 0.02).toInt()) {
                v = (v + ax * 0.02).coerceAtLeast(0.0)
                sense(f, t, ax, 0.0, g, v)
                t += 0.02
            }
        }
        run(3.0, 0.0, 0.08)              // 8% 비탈에서 출발
        run(4.0, 2.0, 0.08)
        run(3.0, -1.0, 0.08)
        run(10.0, 0.0, 0.0)              // 평지로
        val before = f.pitch
        run(1_790.0, 0.0, 0.0)           // 30분 평지
        println("지도 없이 평지 오차: 처음 ${"%.3f".format(before)} → 30분 뒤 ${"%.3f".format(f.pitch)}")
        assertTrue("처음엔 출발 비탈만큼 어긋남", before < -0.06)
        assertEquals("30분이면 거의 0", 0.0, f.pitch, 0.01)
    }

    @Test
    fun `기기 방향을 몰라도 위아래와 오르막을 찾는다`() {
        val f = ImuFrame()
        var t = 0.0
        var v = 0.0
        fun run(sec: Double, ax: Double, az: Double = 0.0, pitch: Double = 0.0) {
            repeat((sec / 0.02).toInt()) {
                v = (v + ax * 0.02).coerceAtLeast(0.0)
                sense(f, t, ax, az, pitch, v)
                t += 0.02
            }
        }
        run(3.0, 0.0)                       // 멈춤 → 기준 수평
        assertFalse(f.oriented)
        run(4.0, 2.0)                       // 곧게 가속 → 앞 방향
        run(3.0, -2.0)
        assertTrue("앞 방향을 알아냄", f.oriented)
        run(5.0, 0.0)
        assertEquals("평지", 0.0, f.pitch, 0.02)
        run(6.0, 0.0, pitch = 0.1)          // 오르막 10%
        assertEquals("오르막", 0.1, f.pitch, 0.02)
        assertEquals("옆 기울기 없음", 0.0, f.roll, 0.02)
        // 위로 1.5 m/s² 짧게 (방지턱)
        sense(f, t, 0.0, 1.5, 0.1, v)
        assertEquals("위아래", 1.5, f.vertical, 0.3)
    }

    @Test
    fun `위아래 흔들림도 물컵을 넘치게 하고, 경사는 넘침에 넣지 않는다`() {
        val flat = WaterCup(level = 0.8)
        val bumpy = WaterCup(level = 0.8)
        for (k in 0 until 2400) {                     // 2분, 6초 주기 위아래 1.5 m/s²
            val t = k * 0.05
            flat.step(0.0, 0.0, 0.05)
            bumpy.step(0.0, 0.0, 0.05, az = 1.5 * sin(2 * PI * t / 6))
        }
        assertEquals(0.0, flat.dose, 1e-9)
        assertTrue("위아래로 멀미 누적", bumpy.dose > 0.05)
        assertTrue(bumpy.tiltZ != 0.0)

        val hill = WaterCup(level = 0.8)
        hill.gradeX = 0.15
        repeat(400) { hill.step(0.0, 0.0, 0.05) }
        assertEquals("경사만으로는 안 넘침", 0.0, hill.dose, 1e-9)
        assertEquals("화면 수면은 경사만큼 기운다", -0.15, hill.surfaceX, 1e-9)
    }

    @Test
    fun `둔덕을 넘을 때(물이 뜰 때) 같은 쏠림에도 더 기운다`() {
        val a = WaterCup()
        val b = WaterCup()
        repeat(200) { a.step(2.0, 0.0, 0.05); b.step(2.0, 0.0, 0.05, az = -3.0) }
        assertTrue(b.tiltH > a.tiltH * 1.3)
    }
}
