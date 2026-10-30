package com.sssoft.chalrang.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/**
 * 시연·검증용 주행 시나리오. 에뮬레이터에는 실제 움직임이 없으므로
 * 실제 도로 모양(가다 서다·방지턱, 진입·진출로, 굽고 오르내리는 산길)으로 가속도를 만들어 재생한다.
 */
data class Leg(
    val durationSec: Double,
    val speedKmh: Double,
    /** 굽은 길 반지름 (m). + 왼쪽, - 오른쪽, null = 직선 */
    val radiusM: Double? = null,
    /** 이 구간이 시작되는 곳에 있는 안내 (진출로·휴게소 등) */
    val maneuver: ManeuverKind? = null,
    val raining: Boolean = false,
    /** 오르내리는 길: 높이 진폭 (m), 파장 [hillWaveM] (m) */
    val hillM: Double = 0.0,
    val hillWaveM: Double = 80.0,
    /** 출발 직후 과속 방지턱 */
    val bump: Boolean = false,
)

/** 운전 습관. 거친 운전은 세게 밟고, 굽은 길에서 핸들을 자주 고친다 */
/** [bumpHit] = 방지턱을 넘는 세기 (부드러운 운전은 속도를 줄여 살살 넘는다) */
data class DriverStyle(val name: String, val maxAccel: Double, val maxBrake: Double, val steerTau: Double, val weave: Double, val bumpHit: Double = 1.0) {
    companion object {
        val SMOOTH = DriverStyle("부드럽게", maxAccel = 1.0, maxBrake = 1.5, steerTau = 2.5, weave = 0.0, bumpHit = 0.35)
        val ROUGH = DriverStyle("거칠게", maxAccel = 3.0, maxBrake = 4.5, steerTau = 0.6, weave = 0.9, bumpHit = 1.0)
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
    /** 위아래 (m/s², + 위로 밀어 올림) */
    val az: Double = 0.0,
    /** 길 경사 (rad, + 오르막) */
    val grade: Double = 0.0,
    /** 출발점부터 거리 (m) */
    val distM: Double = 0.0,
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
            // 위아래: 길 높이 h(x) = A·sin(2πx/λ) 를 지나면 az = -A·(2πv/λ)²·sin(2πx/λ)
            var az = 0.0
            var grade = 0.0
            if (leg.hillM > 0) {
                val k = 2 * PI / leg.hillWaveM
                az += -leg.hillM * (k * v) * (k * v) * sin(k * dist)
                grade = leg.hillM * k * cos(k * dist)
            }
            // 방지턱: 구간 시작 2초 뒤 0.5초 동안 올라갔다 내려옴
            if (leg.bump && legT in 2.0..2.5) az += 2.8 * style.bumpHit * sin(2 * PI * (legT - 2.0) / 0.5)

            val upcoming = nextManeuver(legIdx, dist)
            val rest = restAreaKm(legIdx, dist)
            yield(
                Frame(
                    t = t, ax = ax, ay = ay + weave, speedKmh = v * 3.6, next = upcoming,
                    restAreaKm = rest, raining = leg.raining,
                    resting = leg.maneuver == ManeuverKind.REST_AREA && leg.speedKmh == 0.0,
                    az = az, grade = grade, distM = dist,
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

    /** 경로 미리보기용: 안내·방지턱·굽은 길 지점 (출발점부터 m) */
    fun routePoints(): List<RoutePoint> = buildList {
        for (i in legs.indices) {
            val leg = legs[i]
            leg.maneuver?.let { add(RoutePoint(it, legStartM[i])) }
            if (leg.bump) add(RoutePoint(ManeuverKind.SPEED_BUMP, legStartM[i] + leg.speedKmh / 3.6 * 2.0))
            if (leg.radiusM != null && abs(leg.radiusM) < 100) add(RoutePoint(ManeuverKind.CURVE, legStartM[i]))
        }
    }

    /** 경로 미리보기용: 100m 마다 길 높이 (둔덕 구간) */
    fun terrain(): Terrain {
        val total = legStartM.last()
        val n = (total / TERRAIN_STEP).toInt() + 1
        val h = DoubleArray(n) { k ->
            val d = k * TERRAIN_STEP
            val i = legs.indices.firstOrNull { d < legStartM[it + 1] } ?: (legs.size - 1)
            val leg = legs[i]
            // 산길은 오르막·내리막이 이어지는 언덕 (큰 높이 변화) 위에 작은 둔덕
            if (leg.hillM > 0) leg.hillM * sin(2 * PI / leg.hillWaveM * d) + 40 * sin(2 * PI * d / 1500) else 0.0
        }
        return Terrain(TERRAIN_STEP, h)
    }

    val totalM: Double get() = legStartM.last()

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
        const val TERRAIN_STEP = 100.0

        /** 가다 서다 [n] 번 */
        fun stopAndGo(n: Int, topKmh: Double = 40.0, bumps: Boolean = false): List<Leg> =
            (0 until n).flatMap { i -> listOf(Leg(12.0, topKmh, bump = bumps && i % 2 == 1), Leg(8.0, 0.0)) }

        /** 굽은 길 [n] 개 (좌우 번갈아) */
        fun curves(n: Int, kmh: Double, radiusM: Double, hillM: Double = 0.0): List<Leg> =
            (0 until n).flatMap { i -> listOf(Leg(9.0, kmh, if (i % 2 == 0) radiusM else -radiusM, hillM = hillM), Leg(4.0, kmh, hillM = hillM)) }

        /** 시연 경로: 주말 나들이 (도심 → 고속도로 → 진출로 → 산길 → 휴게소), 약 13분 */
        fun weekendTrip(style: DriverStyle): DriveScenario = DriveScenario(
            buildList {
                addAll(stopAndGo(8, bumps = true))
                add(Leg(20.0, 60.0, radiusM = 120.0, maneuver = ManeuverKind.IC_ENTER))
                add(Leg(150.0, 100.0))
                add(Leg(18.0, 50.0, radiusM = -90.0, maneuver = ManeuverKind.IC_EXIT))
                addAll(stopAndGo(4, topKmh = 50.0))
                add(Leg(10.0, 50.0, maneuver = ManeuverKind.SHARP_TURN))
                // 산길: 굽고 오르내린다 (파장 80m, 시속 50km 에서 약 6초 주기 — 멀미 주기)
                addAll(curves(14, kmh = 50.0, radiusM = 70.0, hillM = 0.35))
                add(Leg(20.0, 30.0, maneuver = ManeuverKind.REST_AREA))
                add(Leg(60.0, 0.0, maneuver = ManeuverKind.REST_AREA))
            },
            style,
        )
    }
}
