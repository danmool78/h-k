package com.sssoft.chalrang.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 아이별 민감 주기 (멀미 패턴 학습).
 *
 * 표준 Wf 는 모든 사람에게 같다. 여기에 아이가 멀미한 순간들의 흔들림 주기를 쌓아,
 * 같은 주기대에 몰려 있으면 그 주기를 더 무겁게 보는 강조 필터를 만든다.
 * - [MIN_EVENTS] 번 미만이면 강조 없음 (표준과 같음)
 * - 멀미 주기가 한 옥타브 이상 흩어져 있으면 강조 없음 (특정 주기에 약한 게 아님)
 * - 주기는 수평 멀미 실험들이 다룬 범위 안 ([LEARN_LO]~[LEARN_HI] Hz, 약 1.3~33초).
 *   표준 Wf 의 멀미 대역(2~10초)보다 넓다: 그 바깥은 연구마다 가중이 갈려서 아이 기록으로 정한다
 * - 강조는 최대 [maxGain] 배: [MAX_GAIN] × (연구 범위 위쪽 끝 [Wh.upper] ÷ 표준 Wf).
 *   2~10초에서는 약 1.5 배, Wf 가 크게 깎는 긴 주기(20초: Wf 0.16)에서는 연구 가중까지 올릴 수 있다
 */
data class Sensitivity(
    /** "지금 멀미" 때마다 찾은 멀미 주기 (초), 오래된 것부터 */
    val periods: List<Double> = emptyList(),
) {
    val count: Int get() = periods.size
    val active: Boolean get() = count >= MIN_EVENTS && gain > 1.02

    /** 민감 주파수 (Hz): 최근 것일수록 무겁게 한 로그 평균 */
    val peakHz: Double
        get() {
            if (periods.isEmpty()) return 1 / 6.0
            var sw = 0.0
            var s = 0.0
            periods.forEachIndexed { i, p ->
                val w = RECENT.pow(periods.size - 1 - i)
                sw += w
                s += w * ln(1 / p)
            }
            return exp(s / sw).coerceIn(LEARN_LO, LEARN_HI)
        }

    val peakPeriod: Double get() = 1 / peakHz

    /** 이 민감 주기에서 강조할 수 있는 최대 배율 */
    val maxGain: Double get() = MAX_GAIN * max(1.0, Wh.upper(peakHz) / Wf.gain(peakHz))

    /** 강조 필터의 Q: 배율이 클수록 좁게 해서 이웃 주기(표준이 이미 무겁게 보는 2~10초)까지 키우지 않는다 */
    val q: Double get() = PeakFilter.Q * max(1.0, sqrt(gain / MAX_GAIN))

    /** 강조 배율: 쌓인 횟수와 모인 정도에 따라 1 ~ [maxGain] */
    val gain: Double
        get() {
            if (count < MIN_EVENTS) return 1.0
            val logs = periods.map { ln(it) / ln(2.0) }   // 옥타브 단위
            val mean = logs.average()
            val sd = sqrt(logs.sumOf { (it - mean) * (it - mean) } / logs.size)
            val together = (1 - sd / SPREAD_OCTAVES).coerceIn(0.0, 1.0)
            val ramp = ((count - MIN_EVENTS + 1) / 3.0).coerceIn(0.0, 1.0)
            return 1 + (maxGain - 1) * together * ramp
        }

    fun with(period: Double): Sensitivity = Sensitivity((periods + period).takeLast(MAX_EVENTS))

    /** 주파수 [f] 에서 이 아이의 가중 (표준 Wf × 강조) */
    fun weight(f: Double): Double = Wf.gain(f) * PeakFilter.gainAt(f, peakHz, gain, q)

    companion object {
        const val MIN_EVENTS = 3
        const val MAX_EVENTS = 10
        /** 표준이 멀미 대역으로 보는 주기에서의 최대 강조 */
        const val MAX_GAIN = 1.5
        /** 배울 수 있는 민감 주파수 범위 (Hz): Donohew & Griffin 2004 ~ Griffin & Mills 2002 실험 범위 */
        const val LEARN_LO = 0.03
        const val LEARN_HI = 0.8
        /** 멀미 주기가 이 정도(옥타브) 흩어지면 특정 주기에 약하다고 보지 않는다 */
        const val SPREAD_OCTAVES = 1.0
        /** 한 번 전 기록의 무게 */
        const val RECENT = 0.8
    }
}

/**
 * 한 주파수 근처만 강조하는 2차 필터 (피킹 이퀄라이저, RBJ Audio EQ Cookbook).
 * 중심 [f0] 에서 진폭이 [gain] 배, 멀어질수록 1 배.
 */
class PeakFilter(dt: Double, f0: Double, gain: Double, q: Double = Q) {
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var s1 = 0.0
    private var s2 = 0.0

    init {
        val a = sqrt(gain)
        val w0 = 2 * PI * f0 * dt
        val alpha = sin(w0) / (2 * q)
        val a0 = 1 + alpha / a
        b0 = (1 + alpha * a) / a0
        b1 = -2 * cos(w0) / a0
        b2 = (1 - alpha * a) / a0
        a1 = -2 * cos(w0) / a0
        a2 = (1 - alpha / a) / a0
    }

    fun step(x: Double): Double {
        val y = b0 * x + s1
        s1 = b1 * x - a1 * y + s2
        s2 = b2 * x - a2 * y
        return y
    }

    companion object {
        const val Q = 0.9

        /** 아날로그 원형의 진폭 응답 (그래프·설명용) */
        fun gainAt(f: Double, f0: Double, gain: Double, q: Double = Q): Double {
            if (gain == 1.0) return 1.0
            val a = sqrt(gain)
            val r = f / f0
            // H(s) = (s² + s(A/Q) + 1) / (s² + s/(A Q) + 1), s = j r
            val nr = 1 - r * r
            val ni = r * a / q
            val di = r / (a * q)
            return sqrt((nr * nr + ni * ni) / (nr * nr + di * di))
        }
    }
}

/** "지금 멀미" 순간의 멀미 주기 찾기 */
object SickPeriod {

    /** 멀미 직전 몇 초를 볼지 */
    const val WINDOW = 180.0

    /** 비교할 평소 기록(멀미 직전 구간 앞)이 이보다 짧으면 비교하지 않는다 */
    const val MIN_BASELINE = WINDOW * 1.5

    /**
     * [trace] 의 [at] 직전 [WINDOW] 초 흔들림에서, 그 주행의 평소보다 유독 많았던 주기 중
     * 멀미 가중(Wf²)이 가장 큰 주기 (초). 기록이 모자라면 null.
     * 평소 스펙트럼은 같은 길이 구간들(반씩 겹침)의 평균으로 낸다 — 길이가 다르면 해상도가 달라 비교가 틀어진다 (Welch 방식).
     */
    fun find(trace: DriveTrace, at: Double): Double? {
        val sick = ShakeAnalysis.analyze(trace, at - WINDOW, at) ?: return null
        fun power(r: ShakeReport) = DoubleArray(r.freqs.size) { r.longAmp[it] * r.longAmp[it] + r.latAmp[it] * r.latAmp[it] }
        val ps = smooth(power(sick))
        val baseEnd = at - WINDOW
        val pb: DoubleArray? = if (baseEnd - trace.startTime >= MIN_BASELINE) {
            val acc = DoubleArray(ps.size)
            var n = 0
            var t0 = trace.startTime
            while (t0 + WINDOW <= baseEnd) {
                ShakeAnalysis.analyze(trace, t0, t0 + WINDOW)?.let { r -> power(r).forEachIndexed { k, v -> acc[k] += v }; n++ }
                t0 += WINDOW / 2
            }
            if (n > 0) smooth(DoubleArray(acc.size) { acc[it] / n }) else null
        } else {
            null
        }
        val sumS = max(1e-12, ps.sum())
        val sumB = pb?.let { max(1e-12, it.sum()) }
        var best = -1
        var bestScore = 0.0
        for (k in sick.freqs.indices) {
            val f = sick.freqs[k]
            if (f < Sensitivity.LEARN_LO || f > Sensitivity.LEARN_HI) continue
            // 평소 대비 얼마나 많았나 (평소 기록이 없으면 그대로). 평소에도 거의 없던 주기의 잡음은 바닥값으로 누른다
            val ratio = if (pb != null) (ps[k] / sumS) / max(0.02 / ps.size, pb[k] / sumB!!) else 1.0
            // 멀미 가중은 연구 범위의 위쪽 끝으로: 표준 Wf 로 보면 긴 주기는 처음부터 후보가 못 된다
            val w = Wh.upper(f)
            val score = ps[k] / sumS * ratio * w * w
            if (score > bestScore) { bestScore = score; best = k }
        }
        return if (best >= 0) 1 / sick.freqs[best] else null
    }

    /** 이웃 구간과 섞어(1:2:1) 한 줄짜리 성분이 구간 사이에 걸려도 놓치지 않게 */
    private fun smooth(p: DoubleArray) = DoubleArray(p.size) { k ->
        (p[max(0, k - 1)] + 2 * p[k] + p[minOf(p.size - 1, k + 1)]) / 4
    }
}
