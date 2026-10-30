package com.sssoft.chalrang.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 브리핑용 3D 경로 선: [STEP] m 마다 평면 위치(x 동, y 북), 높이 z, 진행 방향, 굽은 정도, 그 구간 속도.
 * - 시연: 시나리오 구간(굽은 길 반지름·둔덕)을 그대로 따라 그린 실제 모양
 * - 실제 길 안내: 내비 SDK 가 경로 선을 주지 않아 안내 지점(회전 방향·거리)과 지형 높이로 다시 그린 모양 ([synthetic])
 */
class RoutePath(
    val x: DoubleArray,
    val y: DoubleArray,
    val z: DoubleArray,
    val heading: DoubleArray,
    /** 굽은 정도 (1/m, + 왼쪽) */
    val curvature: DoubleArray,
    val speedKmh: DoubleArray,
    val marks: List<RoutePoint>,
    val preview: RoutePreview,
    val synthetic: Boolean,
) {
    val size: Int get() = x.size
    val lengthM: Double get() = (size - 1) * STEP

    fun index(m: Double): Int = (m / STEP).toInt().coerceIn(0, size - 1)

    /** [m] 지점의 위험 수준 (0 없음, 1 주의, 2 위험) */
    fun riskAt(m: Double): Int = preview.segments.firstOrNull { m >= it.startM && m < it.endM }?.level ?: 0

    /** 그 지점에서 예상되는 좌우 쏠림 (g): v²·κ / g */
    fun lateralG(m: Double): Double {
        val i = index(m)
        val v = speedKmh[i] / 3.6
        return abs(v * v * curvature[i]) / WaterCup.G
    }

    /** 앞뒤 [window] m 의 오르내림 (m) */
    fun climb(m: Double, window: Double = 200.0): Double {
        val a = index(m - window / 2)
        val b = index(m + window / 2)
        var s = 0.0
        for (i in a + 1..b) s += abs(z[i] - z[i - 1])
        return s
    }

    class Builder(private val terrainAt: (Double) -> Double) {
        private val xs = ArrayList<Double>()
        private val ys = ArrayList<Double>()
        private val hs = ArrayList<Double>()
        private val ks = ArrayList<Double>()
        private val vs = ArrayList<Double>()
        private var x = 0.0
        private var y = 0.0
        private var h = PI / 2

        init { push(0.0, 0.0) }

        private fun push(k: Double, v: Double) { xs += x; ys += y; hs += h; ks += k; vs += v }

        /** 마지막 점 뒤로 간 거리 (구간이 [STEP] 으로 나누어떨어지지 않아도 길이가 줄지 않게 이어 간다) */
        private var carry = 0.0

        /** [len] m 를 굽은 정도 [k] (1/m, + 왼쪽) 로 */
        fun go(len: Double, k: Double, kmh: Double) {
            var left = len
            while (left > 1e-9) {
                val d = min(STEP - carry, left)
                h += k * d
                x += cos(h) * d
                y += sin(h) * d
                left -= d
                carry += d
                if (carry >= STEP - 1e-9) { push(k, kmh); carry = 0.0 }
            }
        }

        /** [angle] rad 를 [len] m 동안 돈다 (+ 왼쪽) */
        fun turn(angle: Double, len: Double, kmh: Double) = go(len, angle / len, kmh)

        fun build(marks: List<RoutePoint>, preview: RoutePreview, synthetic: Boolean): RoutePath {
            val n = xs.size
            val z = DoubleArray(n) { terrainAt(it * STEP) }
            return RoutePath(xs.toDoubleArray(), ys.toDoubleArray(), z, hs.toDoubleArray(), ks.toDoubleArray(), vs.toDoubleArray(), marks, preview, synthetic)
        }
    }

    companion object {
        const val STEP = 5.0

        /** 시연 시나리오 그대로 */
        fun of(s: DriveScenario, preview: RoutePreview): RoutePath {
            val t = s.terrain()
            val b = Builder { m -> sample(t, m) }
            for (leg in s.legs) {
                val len = leg.speedKmh / 3.6 * leg.durationSec
                if (len <= 0) continue
                b.go(len, leg.radiusM?.let { 1 / it } ?: 0.0, leg.speedKmh)
            }
            return b.build(s.routePoints(), preview, synthetic = false)
        }

        /**
         * 내비 안내 지점으로 다시 그린 길: 지점 사이는 곧게, 회전은 방향([RoutePoint.dir])대로 짧게 돌고,
         * 진출·진입로는 길게 휘고, 굽은 길은 좌우로 굽힌다. 높이는 지형([terrain]).
         */
        fun synthesize(points: List<RoutePoint>, totalM: Double, terrain: Terrain?, preview: RoutePreview): RoutePath {
            val b = Builder { m -> terrain?.let { sample(it, m) } ?: 0.0 }
            var at = 0.0
            var alt = 1
            for (p in points.filter { it.atM in 0.0..totalM }.sortedBy { it.atM }) {
                val sign = if (p.dir != 0) -p.dir.toDouble() else { alt = -alt; alt.toDouble() }
                val (angle, len, kmh) = when (p.kind) {
                    ManeuverKind.TURN -> Triple(PI / 2, 25.0, 25.0)
                    ManeuverKind.SHARP_TURN -> Triple(PI * 0.75, 30.0, 20.0)
                    ManeuverKind.UTURN -> Triple(PI, 30.0, 15.0)
                    ManeuverKind.IC_EXIT, ManeuverKind.IC_ENTER -> Triple(PI / 2, 180.0, 50.0)
                    ManeuverKind.ROUNDABOUT -> Triple(PI / 4, 40.0, 25.0)
                    ManeuverKind.CURVE -> Triple(PI / 3, 120.0, 50.0)
                    else -> Triple(0.0, 0.0, 0.0)
                }
                val gap = p.atM - at
                if (gap > 0) { b.go(gap, 0.0, 60.0); at = p.atM }
                if (len > 0) { b.turn(angle * sign, len, kmh); at += len }
            }
            if (totalM > at) b.go(totalM - at, 0.0, 60.0)
            return b.build(points, preview, synthetic = true)
        }

        private fun sample(t: Terrain, m: Double): Double {
            if (t.heights.isEmpty()) return 0.0
            val f = (m / t.spacingM).coerceIn(0.0, (t.heights.size - 1).toDouble())
            val i = f.toInt()
            val j = min(i + 1, t.heights.size - 1)
            val w = f - i
            return t.heights[i] * (1 - w) + t.heights[j] * w
        }
    }
}
