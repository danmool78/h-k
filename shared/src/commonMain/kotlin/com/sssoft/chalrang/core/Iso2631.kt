package com.sssoft.chalrang.core

import kotlin.math.PI
import kotlin.math.sqrt

/**
 * ISO 2631-1 멀미 주파수 가중 Wf.
 *
 * Wf(s) = Hh·Hl·Ht·Hs
 * - 대역 제한: 고역 f1 = 0.08Hz, 저역 f2 = 0.63Hz (Q = 1/√2)
 * - 가속도-속도 전이: f4 = 0.25Hz, Q4 = 0.86 (f3 = ∞)
 * - 상향 계단: f5 = 0.0625Hz, Q5 = 0.80, f6 = 0.1Hz, Q6 = 0.80
 *
 * 매개변수는 ISO 2631-1:1997 표 기준이며, 공개 구현 janiex/SimulinkMatlab (Matlab/ISO2631.m, ISO2631.xlsx) 과 대조했다.
 * 해석적 전달 함수를 쌍선형 변환으로 바꿔 쓴다 (0.63Hz 이하만 다루므로 20Hz 에서 주파수 왜곡은 작다).
 */
object Wf {
    const val F1 = 0.08
    const val F2 = 0.63
    const val F4 = 0.25
    const val Q4 = 0.86
    const val F5 = 0.0625
    const val Q5 = 0.80
    const val F6 = 0.1
    const val Q6 = 0.80

    private val SQRT2 = sqrt(2.0)
    private val W1 = 2 * PI * F1
    private val W2 = 2 * PI * F2
    private val W4 = 2 * PI * F4
    private val W5 = 2 * PI * F5
    private val W6 = 2 * PI * F6

    /** 아날로그 2차 구간 (b2 s² + b1 s + b0) / (a2 s² + a1 s + a0) */
    private class Section(val b2: Double, val b1: Double, val b0: Double, val a2: Double, val a1: Double, val a0: Double)

    private val sections = listOf(
        Section(1.0, 0.0, 0.0, 1.0, SQRT2 * W1, W1 * W1),            // 고역
        Section(0.0, 0.0, W2 * W2, 1.0, SQRT2 * W2, W2 * W2),        // 저역
        Section(0.0, 0.0, W4 * W4, 1.0, W4 / Q4, W4 * W4),           // 가속도-속도 전이
        Section(1.0, W5 / Q5, W5 * W5, 1.0, W6 / Q6, W6 * W6),       // 상향 계단
    )

    /** 주파수 [f] Hz 에서의 가중값 |Wf| */
    fun gain(f: Double): Double {
        val w = 2 * PI * f
        var g = 1.0
        for (s in sections) {
            // s = jw: 분자 (b0 - b2 w²) + j b1 w, 분모 (a0 - a2 w²) + j a1 w
            val nr = s.b0 - s.b2 * w * w
            val ni = s.b1 * w
            val dr = s.a0 - s.a2 * w * w
            val di = s.a1 * w
            g *= sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
        }
        return g
    }

    /** 일정 간격 [dt] 초의 디지털 Wf 필터 (2차 구간 4개) */
    class Filter(dt: Double) {
        private val k = 2 / dt
        private val coef = sections.map { s ->
            val k2 = k * k
            val a0 = s.a2 * k2 + s.a1 * k + s.a0
            doubleArrayOf(
                (s.b2 * k2 + s.b1 * k + s.b0) / a0,
                (2 * s.b0 - 2 * s.b2 * k2) / a0,
                (s.b2 * k2 - s.b1 * k + s.b0) / a0,
                (2 * s.a0 - 2 * s.a2 * k2) / a0,
                (s.a2 * k2 - s.a1 * k + s.a0) / a0,
            )
        }
        // 구간마다 직접형 II 전치 상태 2개
        private val z = Array(sections.size) { DoubleArray(2) }

        fun step(x: Double): Double {
            var v = x
            for (i in coef.indices) {
                val c = coef[i]
                val s = z[i]
                val y = c[0] * v + s[0]
                s[0] = c[1] * v - c[3] * y + s[1]
                s[1] = c[2] * v - c[4] * y
                v = y
            }
            return v
        }

        fun reset() = z.forEach { it.fill(0.0) }
    }
}
