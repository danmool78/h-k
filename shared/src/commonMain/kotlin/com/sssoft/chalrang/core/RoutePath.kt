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
    /** 실제 지역이면 이름 (예: "설악로 한계령") */
    val region: String? = null,
    /** 주변 지형 격자 (출발점 기준 m) */
    val grid: TerrainGrid? = null,
    /** 서는 곳 (출발점부터 m): 도심 교차로 회전 */
    val stops: List<Double> = emptyList(),
) {
    /** 새 위험 구간으로 (내비 지점이 늘어날 때) */
    fun withPreview(p: RoutePreview) = RoutePath(x, y, z, heading, curvature, speedKmh, marks, p, synthetic, region, grid, stops)

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

        /**
         * 실제 도로 선(점 사이 거리가 제각각)으로: [STEP] m 마다 다시 나누고 앞뒤 [SMOOTH_M] m 로 고르게 해
         * 꺾인 데 없이 부드럽게. 굽은 정도에서 편안한 속도(좌우 2.5 m/s², 최고 70km/h, 가감속 1.5 m/s²)를 정하고,
         * 굽이를 찾아 위험 점수 지점으로 ([bends]).
         */
        fun fromPolyline(
            px: DoubleArray, py: DoubleArray, pz: DoubleArray,
            extra: List<RoutePoint> = emptyList(), etaSec: Double? = null,
            region: String? = null, grid: TerrainGrid? = null,
            /** 점마다 도로 속도 한도 (m/s, 없으면 [V_MAX]) */
            vLimit: DoubleArray? = null,
            stops: List<Double> = emptyList(),
        ): RoutePath {
            // 1) 거리로 다시 나누기
            val cum = DoubleArray(px.size)
            for (i in 1 until px.size) cum[i] = cum[i - 1] + kotlin.math.hypot(px[i] - px[i - 1], py[i] - py[i - 1])
            val total = cum.last()
            val n = (total / STEP).toInt() + 1
            val rx = DoubleArray(n); val ry = DoubleArray(n); val rz = DoubleArray(n); val rv = DoubleArray(n) { V_MAX }
            var j = 0
            for (k in 0 until n) {
                val d = k * STEP
                while (j < px.size - 2 && cum[j + 1] < d) j++
                val span = cum[j + 1] - cum[j]
                val t = if (span > 0) ((d - cum[j]) / span).coerceIn(0.0, 1.0) else 0.0
                rx[k] = px[j] + (px[j + 1] - px[j]) * t
                ry[k] = py[j] + (py[j + 1] - py[j]) * t
                rz[k] = pz[j] + (pz[j + 1] - pz[j]) * t
                if (vLimit != null) rv[k] = vLimit[j].coerceIn(3.0, 35.0)
            }
            // 2) 고르기 (두 번: 삼각 창과 비슷)
            val w = (SMOOTH_M / STEP).toInt()
            val sx = smooth(smooth(rx, w), w)
            val sy = smooth(smooth(ry, w), w)
            val sz = smooth(smooth(rz, w * 2), w * 2)
            // 3) 방향·굽은 정도
            val hd = DoubleArray(n) { i ->
                val a = (i - 1).coerceAtLeast(0)
                val b = (i + 1).coerceAtMost(n - 1)
                kotlin.math.atan2(sy[b] - sy[a], sx[b] - sx[a])
            }
            val kappa = DoubleArray(n) { i ->
                val a = (i - 2).coerceAtLeast(0)
                val b = (i + 2).coerceAtMost(n - 1)
                if (b == a) 0.0 else wrap(hd[b] - hd[a]) / ((b - a) * STEP)
            }
            // 4) 편안한 속도: 굽이에서 좌우 2.5 m/s², 앞뒤로 가감속 1.5 m/s² 넘지 않게
            val v = DoubleArray(n) { i -> min(rv[i], kotlin.math.sqrt(A_LAT / kotlin.math.max(abs(kappa[i]), 1e-6))) }
            v[0] = 0.0; v[n - 1] = 0.0
            for (st in stops) v[(st / STEP).toInt().coerceIn(0, n - 1)] = 0.0
            for (i in 1 until n) v[i] = min(v[i], kotlin.math.sqrt(v[i - 1] * v[i - 1] + 2 * A_LON * STEP))
            for (i in n - 2 downTo 0) v[i] = min(v[i], kotlin.math.sqrt(v[i + 1] * v[i + 1] + 2 * A_LON * STEP))
            val kmh = DoubleArray(n) { v[it] * 3.6 }
            // 내비(또는 길찾기) 회전 안내 근처의 굽이는 같은 것이라 뺀다
            val marks = bends(kappa).filter { b -> extra.none { abs(it.atM - b.atM) < 60 } } + extra
            // 오르내림 점수는 150m 로 더 고른 높이로 (지형 자료 90m 간격의 잔 오차가 쌓이지 않게)
            val hw = (HILL_SMOOTH_M / STEP).toInt()
            val terrain = Terrain(STEP, smooth(smooth(sz, hw), hw))
            val preview = RoutePlanner.plan(marks, (n - 1) * STEP, etaSec, terrain)
            return RoutePath(sx, sy, sz, hd, kappa, kmh, marks, preview, synthetic = false, region = region, grid = grid, stops = stops)
        }

        /** 굽이 찾기: 굽은 정도가 1/150m 넘는 곳이 이어지면 한 굽이, 반지름 40m 미만이면 급한 회전 */
        fun bends(kappa: DoubleArray): List<RoutePoint> {
            val out = ArrayList<RoutePoint>()
            var i = 0
            while (i < kappa.size) {
                if (abs(kappa[i]) < BEND_K) { i++; continue }
                val start = i
                val sign = kappa[i] > 0
                var peak = 0.0
                var turn = 0.0
                while (i < kappa.size && abs(kappa[i]) >= BEND_K * 0.7 && (kappa[i] > 0) == sign) {
                    peak = kotlin.math.max(peak, abs(kappa[i]))
                    turn += abs(kappa[i]) * STEP
                    i++
                }
                // 45도 넘게 도는 굽이만 (살짝 휘는 길은 빼고)
                if (turn >= PI / 4) {
                    val kind = if (peak >= 1 / 40.0) ManeuverKind.SHARP_TURN else ManeuverKind.CURVE
                    out += RoutePoint(kind, start * STEP, if (sign) -1 else 1)
                }
            }
            return out
        }

        private fun smooth(a: DoubleArray, w: Int): DoubleArray {
            if (w <= 0) return a
            val out = DoubleArray(a.size)
            var sum = 0.0
            var cnt = 0
            // 끝은 줄어든 창으로 (처음·끝 점은 그대로 남게)
            for (i in a.indices) {
                val lo = (i - w).coerceAtLeast(0)
                val hi = (i + w).coerceAtMost(a.size - 1)
                val r = kotlin.math.min(i - lo, hi - i)
                sum = 0.0; cnt = 0
                for (k in i - r..i + r) { sum += a[k]; cnt++ }
                out[i] = sum / cnt
            }
            return out
        }

        private fun wrap(a: Double): Double {
            var d = a
            while (d > PI) d -= 2 * PI
            while (d < -PI) d += 2 * PI
            return d
        }

        const val SMOOTH_M = 20.0
        const val HILL_SMOOTH_M = 150.0
        const val BEND_K = 1 / 150.0
        const val A_LAT = 2.5
        const val A_LON = 1.5
        const val V_MAX = 70 / 3.6

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

/** 주변 지형: 출발점 기준 (x0, y0) 부터 [step] m 간격 [nx]×[ny] 높이 (m) */
class TerrainGrid(val x0: Double, val y0: Double, val step: Double, val nx: Int, val ny: Int, val h: DoubleArray) {
    fun at(i: Int, j: Int): Double = h[j * nx + i]
}
