package com.sssoft.chalrang.core

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

class Iso2631Test {

    /** ISO 2631-1 Wf 가중값 표 (×1000). janiex/SimulinkMatlab Matlab/ISO2631.xlsx 와 같은 값 */
    private val table = listOf(
        0.02 to 24.2, 0.025 to 37.7, 0.0315 to 59.7, 0.04 to 97.1, 0.05 to 157.0, 0.063 to 267.0,
        0.08 to 461.0, 0.1 to 695.0, 0.125 to 895.0, 0.16 to 1006.0, 0.2 to 992.0, 0.25 to 854.0,
        0.315 to 619.0, 0.4 to 384.0, 0.5 to 224.0, 0.63 to 116.0, 0.8 to 53.0, 1.0 to 23.5,
        1.25 to 9.98, 1.6 to 3.77, 2.0 to 1.55, 2.5 to 0.64, 3.15 to 0.25, 4.0 to 0.097,
    )

    @Test
    fun `해석적 Wf 가 표준 표와 맞는다`() {
        for ((f, w) in table) {
            val g = Wf.gain(f) * 1000
            println("%.4f Hz: 표 %.1f, 계산 %.1f".format(f, w, g))
            // 표 값은 유효숫자 3자리, 1/3 옥타브 중심 주파수(공칭값)라 2% 이내
            assertEquals("$f Hz", w, g, max(w * 0.02, 0.01))
        }
    }

    @Test
    fun `디지털 필터 20Hz 가 표준 값대로 줄인다`() {
        for (f in listOf(0.1, 0.16, 0.25, 0.5)) {
            val flt = Wf.Filter(0.05)
            var peak = 0.0
            var t = 0.0
            while (t < 300) {
                val y = flt.step(sin(2 * PI * f * t))
                if (t > 200) peak = max(peak, abs(y))
                t += 0.05
            }
            assertEquals("$f Hz", Wf.gain(f), peak, 0.02)
        }
    }
}
