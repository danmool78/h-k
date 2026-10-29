package com.sssoft.chalrang.core

/**
 * 주행 기록: 물컵 기울기(물 높이와 무관)를 일정 간격으로 저장한다.
 * "지금 멀미" 를 누르면 이 기록을 다른 물 높이로 다시 재생해 이 아이의 기준을 찾는다.
 */
class TiltLog(val intervalSec: Double = 0.2, private val maxSamples: Int = 5 * 3600 * 4) {
    private val samples = FloatArray(maxSamples)
    private var start = 0
    var size = 0; private set
    private var acc = 0.0
    private var accMax = 0.0

    /** [dt] 초 동안의 기울기 [tilt] (g). 간격 안에서는 가장 큰 값을 남긴다 (넘침을 놓치지 않게). */
    fun add(tilt: Double, dt: Double) {
        acc += dt
        if (tilt > accMax) accMax = tilt
        while (acc >= intervalSec) {
            push(accMax.toFloat())
            acc -= intervalSec
            accMax = tilt
        }
    }

    private fun push(v: Float) {
        if (size < maxSamples) {
            samples[(start + size) % maxSamples] = v
            size++
        } else {
            samples[start] = v
            start = (start + 1) % maxSamples
        }
    }

    operator fun get(i: Int): Double = samples[(start + i) % maxSamples].toDouble()

    fun clear() { start = 0; size = 0; acc = 0.0; accMax = 0.0 }

    /** 물 높이 [level] 로 이 기록을 다시 재생했을 때의 마지막 멀미 누적량 */
    fun replayDose(level: Double): Double {
        var dose = 0.0
        for (i in 0 until size) {
            dose = WaterCup.recover(dose, intervalSec) + WaterCup.spillRate(get(i), level) * intervalSec
        }
        return dose
    }
}

object Calibration {

    /**
     * "지금 멀미" 시점에 누적량이 이 값이 되도록 물 높이를 맞춘다.
     * 1.0(위험 경고) 보다 조금 높게 잡아, 다음에는 멀미하기 전에 위험 경고가 먼저 나오게 한다.
     */
    const val TARGET_DOSE = 1.25

    /** 아이가 쉽게 멀미할수록 높은 물 높이. 새 기준과 이전 기준을 섞는 비율 */
    const val LEARN_RATE = 0.6

    /**
     * 기록 [log] 끝에서 멀미했을 때 맞는 물 높이.
     * 누적량은 물 높이가 높을수록 커지므로 이분 탐색.
     */
    fun levelForSickness(log: TiltLog, target: Double = TARGET_DOSE): Double {
        var lo = WaterCup.MIN_LEVEL
        var hi = WaterCup.MAX_LEVEL
        if (log.replayDose(hi) < target) return hi   // 아주 부드럽게 달렸는데도 멀미 → 가장 예민하게
        if (log.replayDose(lo) >= target) return lo
        repeat(40) {
            val mid = (lo + hi) / 2
            if (log.replayDose(mid) >= target) hi = mid else lo = mid
        }
        return (lo + hi) / 2
    }

    /** 이전 기준 [old] (처음이면 null) 과 이번 결과 [found] 를 합친다. */
    fun blend(old: Double?, found: Double): Double =
        if (old == null) found else old + (found - old) * LEARN_RATE
}
