package com.sssoft.chalrang.core

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max

/**
 * 물컵 출렁임과 멀미 누적량.
 *
 * 수면 기울기(단위 g)는 차의 앞뒤·좌우 가속도에 끌려가는 감쇠 진동자로 계산한다.
 * 고유 진동수 0.2Hz(5초 주기)에 공진하고, 0.08Hz 아래의 오래 지속되는 가속은 서서히 빠지게 해서
 * ISO 2631-1 의 멀미 주파수 가중(Wf, 0.1~0.5Hz 에서 가장 큼)을 흉내 낸다.
 * → 물이 많이 출렁이는 운전 = 멀미를 일으키는 흔들림.
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
    private var velX = 0.0
    private var velY = 0.0
    private var hpX = 0.0
    private var hpY = 0.0

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
        // 짧은 간격으로 나눠 적분 (큰 dt 에서도 안정)
        val n = max(1, (dt / MAX_SUBSTEP).toInt() + 1)
        val h = dt / n
        // 가속하면 물은 뒤로, 왼쪽으로 돌면 물은 오른쪽으로 쏠린다 → 입력 부호 반대
        val gx = -ax / G
        val gy = -ay / G
        var spilled = 0.0
        repeat(n) {
            // 오래 지속되는 가속은 빼기 (1차 고역 통과, 0.08Hz)
            hpX += (gx - hpX) * (1 - exp(-h * HP_W))
            hpY += (gy - hpY) * (1 - exp(-h * HP_W))
            val ux = gx - hpX
            val uy = gy - hpY
            velX += (W0 * W0 * (ux - tiltX) - 2 * ZETA * W0 * velX) * h
            velY += (W0 * W0 * (uy - tiltY) - 2 * ZETA * W0 * velY) * h
            tiltX += velX * h
            tiltY += velY * h
            spilled += spillRate(tilt, level) * h
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
        tiltX = 0.0; tiltY = 0.0; velX = 0.0; velY = 0.0; hpX = 0.0; hpY = 0.0
        dose = 0.0; spilling = false; spillCount = 0
    }

    companion object {
        const val G = 9.81
        const val DEFAULT_LEVEL = 0.6
        const val MIN_LEVEL = 0.05
        const val MAX_LEVEL = 0.95

        /** 고유 진동수 0.2Hz */
        val W0 = 2 * PI * 0.2
        const val ZETA = 0.35
        val HP_W = 2 * PI * 0.08

        /** 물 높이 0 일 때 넘치기까지의 기울기 (g) */
        const val MAX_FREEBOARD = 0.5

        /** 넘친 양 계수 (1/(g²·s)) */
        const val SPILL_K = 2.0

        /** 회복 시간 상수 (초). 넘치지 않으면 10분마다 1/e 로 줄어든다 */
        const val RECOVERY_TAU = 600.0

        private const val MAX_SUBSTEP = 0.05

        fun freeboardOf(level: Double) = (1 - level) * MAX_FREEBOARD

        fun spillRate(tilt: Double, level: Double): Double {
            val over = tilt - freeboardOf(level)
            return if (over > 0) SPILL_K * over * over else 0.0
        }

        fun recover(dose: Double, dt: Double) = dose * exp(-dt / RECOVERY_TAU)
    }
}
