package com.sssoft.chalrang.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 흔들림 주기 분석 결과.
 * [freqs] 마다 앞뒤·좌우 흔들림 세기 (진폭 스펙트럼, m/s²), [wf] 는 그 주파수의 ISO 2631-1 Wf 가중값.
 */
class ShakeReport(
    val freqs: DoubleArray,
    val longAmp: DoubleArray,
    val latAmp: DoubleArray,
    val wf: DoubleArray,
    /** 차 흔들림이 가장 센 주기 (초) */
    val peakPeriod: Double,
    /** 멀미에 가장 영향을 준 주기 (Wf 가중 후 가장 센, 초) */
    val sicknessPeriod: Double,
    /** 흔들림 에너지 중 멀미 대역(2~10초 주기, 0.1~0.5Hz)의 비율 */
    val bandShare: Double,
    /** ISO 2631-1 멀미 누적량 MSDV (m/s^1.5): 앞뒤·좌우 Wf 가중 가속도 제곱의 시간 적분의 제곱근 */
    val msdv: Double,
    val durationSec: Double,
)

object ShakeAnalysis {

    /** 분석 주파수 범위 (Hz): 50초 주기 ~ 1초 주기 */
    const val F_MIN = 0.02
    const val F_MAX = 1.0
    const val BINS = 60

    /** 멀미 대역 */
    const val BAND_LO = 0.1
    const val BAND_HI = 0.5

    /**
     * [trace] 의 [from]~[to] 구간을 분석한다. 30초보다 짧으면 null.
     * 해닝 창을 씌운 이산 푸리에 변환을 로그 간격 주파수에서 직접 계산한다 (기록은 0.1초 간격).
     */
    fun analyze(trace: DriveTrace, from: Double, to: Double): ShakeReport? {
        val i0 = trace.indexAt(from).coerceAtLeast(0)
        val i1 = trace.indexAt(to)
        val n = i1 - i0 + 1
        if (n < 2 || trace.timeAt(i1) - trace.timeAt(i0) < MIN_SEC) return null
        val dt = trace.interval
        val ax = DoubleArray(n)
        val ay = DoubleArray(n)
        var wfEnergy = 0.0
        for (k in 0 until n) {
            val s = trace[i0 + k]
            ax[k] = s.ax; ay[k] = s.ay
            // 물 기울기 = Wf 가중 가속도 / g 이므로 기록에서 바로 MSDV 를 얻는다
            wfEnergy += (s.tiltX * s.tiltX + s.tiltY * s.tiltY) * WaterCup.G * WaterCup.G * dt
        }
        listOf(ax, ay).forEach { a -> val m = a.average(); for (k in a.indices) a[k] -= m }
        val win = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
        val winSum = win.sum()

        val freqs = DoubleArray(BINS) { F_MIN * exp(ln(F_MAX / F_MIN) * it / (BINS - 1)) }
        val longAmp = DoubleArray(BINS)
        val latAmp = DoubleArray(BINS)
        for (b in 0 until BINS) {
            val w = 2 * PI * freqs[b] * dt
            var xr = 0.0; var xi = 0.0; var yr = 0.0; var yi = 0.0
            for (k in 0 until n) {
                val c = cos(w * k) * win[k]
                val s = sin(w * k) * win[k]
                xr += ax[k] * c; xi -= ax[k] * s
                yr += ay[k] * c; yi -= ay[k] * s
            }
            // 사인파 진폭이 그대로 나오도록 창 합으로 나눈다
            longAmp[b] = 2 * sqrt(xr * xr + xi * xi) / winSum
            latAmp[b] = 2 * sqrt(yr * yr + yi * yi) / winSum
        }
        val wf = DoubleArray(BINS) { Wf.gain(freqs[it]) }
        val power = DoubleArray(BINS) { longAmp[it] * longAmp[it] + latAmp[it] * latAmp[it] }
        val peak = power.indices.maxBy { power[it] }
        val sick = power.indices.maxBy { power[it] * wf[it] * wf[it] }
        // 로그 간격이라 주파수 폭(∝ f)을 곱해 에너지 비율을 낸다
        var total = 0.0
        var band = 0.0
        for (b in 0 until BINS) {
            val e = power[b] * freqs[b]
            total += e
            if (freqs[b] in BAND_LO..BAND_HI) band += e
        }
        return ShakeReport(
            freqs, longAmp, latAmp, wf,
            peakPeriod = 1 / freqs[peak],
            sicknessPeriod = 1 / freqs[sick],
            bandShare = if (total > 0) band / total else 0.0,
            msdv = sqrt(wfEnergy),
            durationSec = trace.timeAt(i1) - trace.timeAt(i0),
        )
    }

    const val MIN_SEC = 30.0
}
