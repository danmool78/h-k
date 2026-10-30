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

    /** 물 높이 [level] 로 이 기록을 다시 재생했을 때 가장 컸던 멀미 누적량 */
    fun replayPeakDose(level: Double): Double {
        var dose = 0.0
        var peak = 0.0
        for (i in 0 until size) {
            dose = WaterCup.recover(dose, intervalSec) + WaterCup.spillRate(get(i), level) * intervalSec
            if (dose > peak) peak = dose
        }
        return peak
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

    /**
     * 멀미 없이 끝난 주행으로 기준을 낮출 때 새 기준 쪽으로 옮기는 비율.
     * 멀미를 누르지 않았거나 환기·휴식 덕에 넘긴 경우도 섞여 있으므로 멀미 때([LEARN_RATE])보다 작게.
     */
    const val CALM_RATE = 0.25

    /**
     * 멀미 없이 끝난 주행: 물 높이 [level] 로 다시 재생한 최대 누적량이 [target] 을 넘었는데도 괜찮았다면,
     * 이 아이는 생각보다 덜 예민하다 → 이 주행이 겨우 [target] 에 닿는 물 높이 쪽으로 조금 낮춘 값.
     * 넘지 않았으면 (알 수 있는 것이 없으므로) null.
     */
    fun levelAfterCalmTrip(log: TiltLog, level: Double, target: Double = TARGET_DOSE): Double? {
        if (log.replayPeakDose(level) < target) return null
        var lo = WaterCup.MIN_LEVEL
        var hi = level
        if (log.replayPeakDose(lo) >= target) return level + (lo - level) * CALM_RATE
        repeat(40) {
            val mid = (lo + hi) / 2
            if (log.replayPeakDose(mid) >= target) hi = mid else lo = mid
        }
        return level + ((lo + hi) / 2 - level) * CALM_RATE
    }

    /** 이전 기준 [old] (처음이면 null) 과 이번 결과 [found] 를 합친다. */
    fun blend(old: Double?, found: Double): Double =
        if (old == null) found else old + (found - old) * LEARN_RATE
}
