package com.sssoft.chalrang.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/**
 * 시연·검증용 주행 시나리오. 에뮬레이터에는 실제 움직임이 없으므로
 * 실제 도로 모양(가다 서다, 진입·진출로, 굽은 길)으로 가속도를 만들어 재생한다.
 */
data class Leg(
    val durationSec: Double,
    val speedKmh: Double,
    /** 굽은 길 반지름 (m). + 왼쪽, - 오른쪽, null = 직선 */
    val radiusM: Double? = null,
    /** 이 구간이 시작되는 곳에 있는 안내 (진출로·휴게소 등) */
    val maneuver: ManeuverKind? = null,
    val raining: Boolean = false,
)

/** 운전 습관. 거친 운전은 세게 밟고, 굽은 길에서 핸들을 자주 고친다 */
data class DriverStyle(val name: String, val maxAccel: Double, val maxBrake: Double, val steerTau: Double, val weave: Double) {
    companion object {
        val SMOOTH = DriverStyle("부드럽게", maxAccel = 1.0, maxBrake = 1.5, steerTau = 2.5, weave = 0.0)
        val ROUGH = DriverStyle("거칠게", maxAccel = 3.0, maxBrake = 4.5, steerTau = 0.6, weave = 0.9)
    }
}

data class Frame(
    val t: Double,
    val ax: Double,
    val ay: Double,
    val speedKmh: Double,
    val next: Upcoming?,
    val restAreaKm: Double?,
    val raining: Boolean,
    /** 휴게소에서 쉬는 중 */
    val resting: Boolean,
)

class DriveScenario(val legs: List<Leg>, val style: DriverStyle) {

    val durationSec: Double = legs.sumOf { it.durationSec }

    /** 각 구간 시작까지의 거리 (계획 속도 기준, m) */
    private val legStartM: DoubleArray = DoubleArray(legs.size + 1).also { a ->
        for (i in legs.indices) a[i + 1] = a[i] + legs[i].speedKmh / 3.6 * legs[i].durationSec
    }

    fun frames(dt: Double = 0.1): Sequence<Frame> = sequence {
        var t = 0.0
        var v = 0.0
        var ay = 0.0
        var dist = 0.0
        var legIdx = 0
        var legT = 0.0
        while (legIdx < legs.size) {
            val leg = legs[legIdx]
            val target = leg.speedKmh / 3.6
            // 거친 운전은 목표 속도까지 세게, 부드러운 운전은 천천히
            val dv = target - v
            val a = if (dv >= 0) min(dv / dt, style.maxAccel * smoothStart(dv)) else -min(-dv / dt, style.maxBrake * smoothStart(-dv))
            val ax = if (abs(dv) < 0.01) 0.0 else a
            v = (v + ax * dt).coerceAtLeast(0.0)
            // 좌우: v²/R 로 다가가되 운전자의 핸들 반응 속도만큼 늦게, 거친 운전은 핸들을 자꾸 고친다
            val wantAy = leg.radiusM?.let { v * v / it } ?: 0.0
            ay += (wantAy - ay) * (dt / style.steerTau).coerceAtMost(1.0)
            val weave = if (leg.radiusM != null) style.weave * sin(2 * PI * 0.22 * t) * sign(wantAy) else 0.0
            dist += v * dt

            val upcoming = nextManeuver(legIdx, dist)
            val rest = restAreaKm(legIdx, dist)
            yield(
                Frame(
                    t = t, ax = ax, ay = ay + weave, speedKmh = v * 3.6, next = upcoming,
                    restAreaKm = rest, raining = leg.raining,
                    resting = leg.maneuver == ManeuverKind.REST_AREA && leg.speedKmh == 0.0,
                ),
            )
            t += dt
            legT += dt
            if (legT >= leg.durationSec) {
                legT = 0.0
                // 실제 거리를 계획 거리에 맞춰 다음 안내까지 거리가 어긋나지 않게
                dist = legStartM[legIdx + 1]
                legIdx++
            }
        }
    }

    private fun smoothStart(dv: Double) = (dv / 2.0).coerceIn(0.3, 1.0)

    private fun nextManeuver(legIdx: Int, dist: Double): Upcoming? {
        for (i in legIdx + 1 until legs.size) {
            val m = legs[i].maneuver ?: continue
            return Upcoming(m, (legStartM[i] - dist).coerceAtLeast(0.0))
        }
        return null
    }

    private fun restAreaKm(legIdx: Int, dist: Double): Double? {
        for (i in legIdx + 1 until legs.size) {
            if (legs[i].maneuver == ManeuverKind.REST_AREA) return (legStartM[i] - dist).coerceAtLeast(0.0) / 1000
        }
        return null
    }

    companion object {
        /** 가다 서다 [n] 번 */
        fun stopAndGo(n: Int, topKmh: Double = 40.0): List<Leg> =
            (0 until n).flatMap { listOf(Leg(12.0, topKmh), Leg(8.0, 0.0)) }

        /** 굽은 길 [n] 개 (좌우 번갈아) */
        fun curves(n: Int, kmh: Double, radiusM: Double): List<Leg> =
            (0 until n).flatMap { i -> listOf(Leg(9.0, kmh, if (i % 2 == 0) radiusM else -radiusM), Leg(4.0, kmh)) }

        /** 시연 경로: 주말 나들이 (도심 → 고속도로 → 진출로 → 산길 → 휴게소), 약 13분 */
        fun weekendTrip(style: DriverStyle): DriveScenario = DriveScenario(
            buildList {
                addAll(stopAndGo(8))
                add(Leg(20.0, 60.0, radiusM = 120.0, maneuver = ManeuverKind.IC_ENTER))
                add(Leg(150.0, 100.0))
                add(Leg(18.0, 50.0, radiusM = -90.0, maneuver = ManeuverKind.IC_EXIT))
                addAll(stopAndGo(4, topKmh = 50.0))
                add(Leg(10.0, 50.0, maneuver = ManeuverKind.SHARP_TURN))
                addAll(curves(14, kmh = 50.0, radiusM = 70.0))
                add(Leg(20.0, 30.0, maneuver = ManeuverKind.REST_AREA))
                add(Leg(60.0, 0.0, maneuver = ManeuverKind.REST_AREA))
            },
            style,
        )
    }
}
