package com.sssoft.chalrang.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 차량 SDK 에 가속도 값이 없어서, 들어오는 신호로 앞뒤·좌우 가속도를 추정한다.
 *
 * - 속도: 주행 거리(Odometer) 또는 내비 현재 위치의 변화 ÷ 시간
 * - 앞뒤: 속도의 변화 (브레이크를 밟는 중이면 가속으로 보지 않는다)
 * - 좌우: 속도² × tan(바퀴 각도) ÷ 축간거리. 조향각이 없으면 속도 × 진행 방향 변화율
 *
 * 시간은 모두 초.
 */
class MotionEstimator(
    private val wheelbaseM: Double = 2.9,
) {
    /** 조향각을 바퀴 각도로 바꾸는 비율. 운전대 각도면 약 14, 바퀴 각도면 1 */
    var steeringRatio: Double = 1.0

    var speed = 0.0; private set          // m/s
    var ax = 0.0; private set             // m/s², + 가속
    var ay = 0.0; private set             // m/s², + 왼쪽으로 도는 중
    var hasSpeed = false; private set

    /** 최근 (시각, 누적 거리), (시각, 속도). 거칠게 들어오는 값을 2초 남짓 구간의 기울기로 읽는다 */
    private val distHist = ArrayDeque<Pair<Double, Double>>()
    private val speedHist = ArrayDeque<Pair<Double, Double>>()
    private var steeringDeg: Double? = null
    private var steeringT = Double.NaN
    private var yawRate: Double? = null    // rad/s, + 오른쪽 (나침반 방향 증가)
    private var lastHeading = Double.NaN
    private var lastHeadingT = Double.NaN
    private var lastPos: Pair<Double, Double>? = null
    private var posDistance = 0.0
    private var braking = false

    /** 누적 주행 거리 (m). 차량 Odometer 또는 위치에서 */
    fun onDistance(t: Double, meters: Double) {
        val last = distHist.lastOrNull()
        if (last != null) {
            val dt = t - last.first
            // 거리 값이 거칠게(예: 10m 단위) 오면 바뀔 때까지 기다린다. 너무 오래 안 바뀌면 정차
            if (dt <= 0 || (meters <= last.second && dt < STOP_AFTER)) return
        }
        distHist.addLast(t to meters)
        trim(distHist, t, DIST_WINDOW)
        if (distHist.size < 2) return
        val (t0, d0) = distHist.first()
        onSpeed(t, ((meters - d0) / (t - t0)).coerceIn(0.0, 70.0))
    }

    /** 내비 현재 위치. 방향 [headingDeg] 는 북쪽 0, 시계 방향 */
    fun onPosition(t: Double, lat: Double, lng: Double, headingDeg: Double?) {
        lastPos?.let { (pLat, pLng) -> posDistance += haversine(pLat, pLng, lat, lng) }
        lastPos = lat to lng
        onDistance(t, posDistance)
        if (headingDeg != null) {
            if (!lastHeadingT.isNaN() && t > lastHeadingT) {
                var d = headingDeg - lastHeading
                if (d > 180) d -= 360
                if (d < -180) d += 360
                yawRate = d * PI / 180 / (t - lastHeadingT)
            }
            lastHeading = headingDeg; lastHeadingT = t
        }
    }

    /** 조향각 (도). 왼쪽이 음수 (AAOS 기준) */
    fun onSteering(t: Double, deg: Double) {
        steeringDeg = deg; steeringT = t
    }

    fun onBrake(pressed: Boolean) {
        braking = pressed
    }

    /** 현재 시각 [t] 의 가속도를 갱신한다 */
    fun update(t: Double) {
        val lastT = distHist.lastOrNull()?.first
        if (lastT != null && t - lastT > STOP_AFTER * 2) {
            // 거리 신호가 끊김: 정지로 본다
            distHist.clear()
            distHist.addLast(t to 0.0)
            onSpeed(t, 0.0)
        } else if (speedHist.isNotEmpty() && t - speedHist.last().first > 1.5) {
            ax = 0.0
        }
        val steer = steeringDeg?.takeIf { t - steeringT < 5 }
        ay = when {
            steer != null -> -speed * speed * tan(steer / steeringRatio * PI / 180) / wheelbaseM
            yawRate != null -> -speed * yawRate!!
            else -> 0.0
        }.coerceIn(-MAX_A, MAX_A)
    }

    private fun onSpeed(t: Double, v: Double) {
        speed = v
        hasSpeed = true
        speedHist.addLast(t to v)
        trim(speedHist, t, ACCEL_WINDOW)
        if (speedHist.size < 2) return
        val (t0, v0) = speedHist.first()
        var a = (v - v0) / (t - t0)
        if (braking && a > 0) a = 0.0
        ax = a.coerceIn(-MAX_A, MAX_A)
    }

    /** 가장 오래된 값이 [window] 초 전쯤이 되도록 앞을 버린다 */
    private fun trim(hist: ArrayDeque<Pair<Double, Double>>, now: Double, window: Double) {
        while (hist.size > 2 && now - hist[1].first >= window) hist.removeFirst()
    }

    val speedKmh: Double? get() = if (hasSpeed) speed * 3.6 else null

    companion object {
        const val MAX_A = 0.8 * WaterCup.G
        /** 속도·가속도를 읽는 구간 (초). 멀미 대역(2~10초 주기)보다 짧아 흔들림은 살아남는다 */
        const val DIST_WINDOW = 2.0
        const val ACCEL_WINDOW = 2.0
        const val STOP_AFTER = 3.0

        /** 조향각 최대값이 이보다 크면 운전대 각도로 본다 */
        const val WHEEL_ANGLE_MAX = 60.0
        const val DEFAULT_STEERING_RATIO = 14.0

        fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val r = 6_371_000.0
            val p1 = lat1 * PI / 180
            val p2 = lat2 * PI / 180
            val dp = p2 - p1
            val dl = (lng2 - lng1) * PI / 180
            val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }

        fun ratioForMaxAngle(maxDeg: Double?): Double =
            if (maxDeg != null && abs(maxDeg) > WHEEL_ANGLE_MAX) DEFAULT_STEERING_RATIO else 1.0
    }
}
