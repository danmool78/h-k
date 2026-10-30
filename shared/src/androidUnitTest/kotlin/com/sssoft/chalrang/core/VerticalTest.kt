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
    private fun sense(f: ImuFrame, t: Double, carAx: Double, carAz: Double, pitch: Double, speed: Double) {
        val g = WaterCup.G
        // 오르막: 위(중력 반작용)가 차 뒤쪽으로 기운다
        val car = doubleArrayOf(carAx - g * sin(pitch), 0.0, carAz + g * cos(pitch))
        val d = toDevice(car)
        f.update(t, d[0], d[1], d[2], carAx, 0.0, speed)
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
