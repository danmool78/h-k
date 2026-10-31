package com.sssoft.chalrang.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
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
    /** 위아래 성분 (m/s², 사인 진폭) */
    val vertAmp: DoubleArray,
    val wf: DoubleArray,
    /** 차 흔들림이 가장 센 주기 (초) */
    val peakPeriod: Double,
    /** 멀미에 가장 영향을 준 주기 (Wf 가중 후 가장 센, 초) */
    val sicknessPeriod: Double,
    /** 흔들림 에너지 중 멀미 대역(2~10초 주기, 0.1~0.5Hz)의 비율 */
    val bandShare: Double,
    /** ISO 2631-1 멀미 누적량 MSDV (m/s^1.5): 앞뒤·좌우·위아래 Wf 가중 가속도 제곱의 시간 적분의 제곱근 */
    val msdv: Double,
    val durationSec: Double,
) {
    /** 흔들림이 거의 없어 주기가 의미 없다 (가장 센 성분이 [ShakeAnalysis.QUIET_AMP] 미만) */
    val quiet: Boolean get() = longAmp.indices.none { max(max(longAmp[it], latAmp[it]), vertAmp[it]) >= ShakeAnalysis.QUIET_AMP }
}

object ShakeAnalysis {

    /** 분석 주파수 범위 (Hz): 50초 주기 ~ 1초 주기 */
    const val F_MIN = 0.02
    const val F_MAX = 1.0
    const val BINS = 60

    /** 멀미 대역 */
    const val BAND_LO = 0.1
    const val BAND_HI = 0.5

    /**
     * [trace] 의 [from]~[to] 구간을 분석한다. [minSec] (기본 30초) 보다 짧으면 null.
     * 화면은 [SHOW_MIN_SEC] 부터 먼저 그린다 (짧으면 긴 주기는 덜 정확). 학습·주행 평가는 30초 넘게.
     * 해닝 창을 씌운 이산 푸리에 변환을 로그 간격 주파수에서 직접 계산한다 (기록은 0.1초 간격).
     */
    fun analyze(trace: DriveTrace, from: Double, to: Double, minSec: Double = MIN_SEC): ShakeReport? {
        val i0 = trace.indexAt(from).coerceAtLeast(0)
        val i1 = trace.indexAt(to)
        val n = i1 - i0 + 1
        if (n < 2 || trace.timeAt(i1) - trace.timeAt(i0) < minSec) return null
        val dt = trace.interval
        val ax = DoubleArray(n)
        val ay = DoubleArray(n)
        val az = DoubleArray(n)
        var wfEnergy = 0.0
        // MSDV 는 표준 그대로: 기록된 가속도에 Wf 를 다시 건다 (물 기울기에는 아이별 강조가 들어 있을 수 있다)
        val fx = Wf.Filter(dt)
        val fy = Wf.Filter(dt)
        val fz = Wf.Filter(dt)
        for (k in 0 until n) {
            ax[k] = trace.axAt(i0 + k); ay[k] = trace.ayAt(i0 + k); az[k] = trace.azAt(i0 + k)
            val wx = fx.step(ax[k])
            val wy = fy.step(ay[k])
            val wz = fz.step(az[k])
            wfEnergy += (wx * wx + wy * wy + wz * wz) * dt
        }
        listOf(ax, ay, az).forEach { a -> val m = a.average(); for (k in a.indices) a[k] -= m }
        val win = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
        val winSum = win.sum()

        val freqs = DoubleArray(BINS) { F_MIN * exp(ln(F_MAX / F_MIN) * it / (BINS - 1)) }
        val longAmp = DoubleArray(BINS)
        val latAmp = DoubleArray(BINS)
        val vertAmp = DoubleArray(BINS)
        for (b in 0 until BINS) {
            val w = 2 * PI * freqs[b] * dt
            var xr = 0.0; var xi = 0.0; var yr = 0.0; var yi = 0.0; var zr = 0.0; var zi = 0.0
            for (k in 0 until n) {
                val c = cos(w * k) * win[k]
                val s = sin(w * k) * win[k]
                xr += ax[k] * c; xi -= ax[k] * s
                yr += ay[k] * c; yi -= ay[k] * s
                zr += az[k] * c; zi -= az[k] * s
            }
            // 사인파 진폭이 그대로 나오도록 창 합으로 나눈다
            longAmp[b] = 2 * sqrt(xr * xr + xi * xi) / winSum
            latAmp[b] = 2 * sqrt(yr * yr + yi * yi) / winSum
            vertAmp[b] = 2 * sqrt(zr * zr + zi * zi) / winSum
        }
        val wf = DoubleArray(BINS) { Wf.gain(freqs[it]) }
        val power = DoubleArray(BINS) { longAmp[it] * longAmp[it] + latAmp[it] * latAmp[it] + vertAmp[it] * vertAmp[it] }
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
            freqs, longAmp, latAmp, vertAmp, wf,
            peakPeriod = 1 / freqs[peak],
            sicknessPeriod = 1 / freqs[sick],
            bandShare = if (total > 0) band / total else 0.0,
            msdv = sqrt(wfEnergy),
            durationSec = trace.timeAt(i1) - trace.timeAt(i0),
        )
    }

    const val MIN_SEC = 30.0
    /** 화면에 먼저 그리는 길이 (초): 10초면 5초 주기까지 두 번 넘게 본다 */
    const val SHOW_MIN_SEC = 10.0
    /** 이보다 약하면 (m/s², 사인 진폭) 흔들림 없음으로 본다 */
    const val QUIET_AMP = 0.02
}
