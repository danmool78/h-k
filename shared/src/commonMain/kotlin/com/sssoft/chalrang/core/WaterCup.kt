package com.sssoft.chalrang.core

import kotlin.math.exp
import kotlin.math.hypot

/**
 * 물컵 출렁임과 멀미 누적량.
 *
 * 수면 기울기(단위 g) = 차의 앞뒤·좌우 가속도에 ISO 2631-1 멀미 주파수 가중 [Wf] 를 건 값
 * (아이별 민감 주기를 학습했으면 그 주기를 [sensitivity] 만큼 더 강조).
 * Wf 는 0.16Hz(약 6초 주기)에서 가장 크고, 그보다 느리거나 빠른 흔들림은 줄인다. 물은 차 흔들림을 늦게 따라온다.
 * → 물이 많이 출렁이는 운전 = 국제 규격이 멀미를 일으킨다고 보는 흔들림.
 *
 * 물 높이 [level] (0~1) 이 높을수록 여유가 줄어 쉽게 넘친다 (아이별 기준).
 * 넘친 양을 쌓아 멀미 누적량 [dose] 로 쓰고, 넘치지 않는 동안은 천천히 줄어든다 (몸의 회복).
 * [dose] 1.0 = 이 아이가 멀미하는 수준.
 */
class WaterCup(level: Double = DEFAULT_LEVEL) {

    var level: Double = level.coerceIn(MIN_LEVEL, MAX_LEVEL)
        set(value) { field = value.coerceIn(MIN_LEVEL, MAX_LEVEL) }

    /** 수면 기울기 (g). x = 앞뒤(+ 앞으로 쏠림), y = 좌우(+ 왼쪽으로 쏠림) */
    var tiltX = 0.0; private set
    var tiltY = 0.0; private set
    private val wfX = Wf.Filter(STEP)
    private val wfY = Wf.Filter(STEP)

    /** 아이별 민감 주기. 바뀌면 강조 필터를 새로 만든다 (학습 전에는 강조 없음) */
    var sensitivity: Sensitivity = Sensitivity()
        set(value) {
            if (value.peakHz == field.peakHz && value.gain == field.gain) { field = value; return }
            field = value
            peakX = makePeak(value)
            peakY = makePeak(value)
        }
    private var peakX: PeakFilter? = null
    private var peakY: PeakFilter? = null
    private fun makePeak(s: Sensitivity) = if (s.gain > 1.0) PeakFilter(STEP, s.peakHz, s.gain) else null
    /** 아직 필터에 넣지 않은 시간 (Wf 는 [STEP] 간격으로 돈다) */
    private var pending = 0.0

    /** 멀미 누적량. 1.0 = 멀미 */
    var dose = 0.0; private set

    /** 지금 넘치고 있는지 */
    var spilling = false; private set

    /** 넘치기 시작한 횟수 (전체) */
    var spillCount = 0; private set

    val tilt: Double get() = hypot(tiltX, tiltY)

    /** 넘치기까지 남은 여유 (g) */
    val freeboard: Double get() = freeboardOf(level)

    /**
     * [dt] 초 동안 가속도 [ax](앞뒤, + 가속), [ay](좌우, + 왼쪽으로 도는 중) (m/s²) 를 받는다.
     * 반환값은 이번에 넘친 양.
     */
    fun step(ax: Double, ay: Double, dt: Double): Double {
        if (dt <= 0) return 0.0
        // 가속하면 물은 뒤로, 왼쪽으로 돌면 물은 오른쪽으로 쏠린다 → 입력 부호 반대
        val gx = -ax / G
        val gy = -ay / G
        var spilled = 0.0
        pending += dt
        while (pending >= STEP * 0.999) {
            pending -= STEP
            val wx = wfX.step(gx)
            val wy = wfY.step(gy)
            tiltX = peakX?.step(wx) ?: wx
            tiltY = peakY?.step(wy) ?: wy
            spilled += spillRate(tilt, level) * STEP
        }
        val nowSpilling = tilt > freeboard
        if (nowSpilling && !spilling) spillCount++
        spilling = nowSpilling
        dose = recover(dose, dt) + spilled
        return spilled
    }

    /** 쉬는 중 (정차·휴게소). 출렁임은 가라앉고 누적량만 회복된다. */
    fun rest(dt: Double) = step(0.0, 0.0, dt)

    fun reset() {
        tiltX = 0.0; tiltY = 0.0; wfX.reset(); wfY.reset(); pending = 0.0
        dose = 0.0; spilling = false; spillCount = 0
    }

    companion object {
        const val G = 9.81
        const val DEFAULT_LEVEL = 0.6
        const val MIN_LEVEL = 0.05
        const val MAX_LEVEL = 0.95

        /** Wf 필터 간격 (초, 20Hz) */
        const val STEP = 0.05

        /**
         * 물 높이 0 일 때 넘치기까지의 기울기 (Wf 가중 가속도, g).
         * 넘친 양 계수와 함께 시나리오로 맞췄다: 기본 물 높이에서 거친 운전 13분 → 위험, 부드러운 운전 → 거의 0
         */
        const val MAX_FREEBOARD = 0.30

        /** 넘친 양 계수 (1/(g²·s)) */
        const val SPILL_K = 3.0

        /** 회복 시간 상수 (초). 넘치지 않으면 10분마다 1/e 로 줄어든다 */
        const val RECOVERY_TAU = 600.0

        fun freeboardOf(level: Double) = (1 - level) * MAX_FREEBOARD

        fun spillRate(tilt: Double, level: Double): Double {
            val over = tilt - freeboardOf(level)
            return if (over > 0) SPILL_K * over * over else 0.0
        }

        fun recover(dose: Double, dt: Double) = dose * exp(-dt / RECOVERY_TAU)
    }
}
