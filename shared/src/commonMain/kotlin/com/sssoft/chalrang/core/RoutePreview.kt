package com.sssoft.chalrang.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 경로 위 지점 (출발점부터 거리, m). [dir] 회전 방향 -1 왼쪽, +1 오른쪽, 0 모름 */
data class RoutePoint(val kind: ManeuverKind, val atM: Double, val dir: Int = 0)

/**
 * 가는 길의 지형: 경로를 따라 같은 간격으로 잰 높이 (m).
 * 내비가 경로 선을 주지 않아 지금 위치 → 목적지 직선 위에서 잰 값을 경로 길이에 맞춰 늘린 추정이다.
 */
class Terrain(val spacingM: Double, val heights: DoubleArray)

/** 멀미 위험 구간 (출발점부터 m). [level] 1 = 주의, 2 = 위험 */
data class RiskSegment(val startM: Double, val endM: Double, val level: Int, val reasons: List<String>) {
    val lengthM: Double get() = endM - startM
}

/** 출발 전에 보는 가는 길 요약 */
data class RoutePreview(
    val totalM: Double,
    val etaSec: Double?,
    val segments: List<RiskSegment>,
    /** km 마다 편하게 달릴 때의 Wf 가중 흔들림 세기 a_w (m/s²) */
    val riskPerKm: DoubleArray,
    val restAreaM: List<Double>,
    val hasTerrain: Boolean,
) {
    val dangerCount: Int get() = segments.count { it.level >= 2 }

    /** 출발점이 [d] m 앞으로 옮겨진 평가 (길 안내 도중에 받은 도로 선) */
    fun shifted(d: Double, newTotalM: Double) = if (d == 0.0) copy(totalM = newTotalM) else copy(
        totalM = newTotalM,
        segments = segments.map { it.copy(startM = it.startM + d, endM = it.endM + d) },
        restAreaM = restAreaM.map { it + d },
    )

    /** 화면·음성 한 줄 요약 */
    val summary: String get() {
        val km = fmtKm(totalM)
        val eta = etaSec?.let { " 약 ${(it / 60).roundToInt()}분," } ?: ""
        if (segments.isEmpty()) return "${km} 가는 길,$eta 멀미 걱정 구간이 없어요. 편한 길이에요."
        val first = segments.first()
        val worst = segments.maxBy { it.level * 1e6 + it.lengthM }
        val kind = if (dangerCount > 0) "멀미 위험 구간 ${dangerCount}곳" else "조심할 구간 ${segments.size}곳"
        return "${km} 가는 길,$eta $kind. 가장 힘든 곳은 ${place(worst)} ${worst.reasons.take(2).joinToString("·")}." +
            if (first != worst) " 첫 구간은 ${place(first)}." else ""
    }

    /** 위험 구간 앞 휴게소가 있으면 쉬어 가기 */
    val restAdvice: String? get() {
        val danger = segments.firstOrNull { it.level >= 2 } ?: return null
        val rest = restAreaM.lastOrNull { it < danger.startM && it > 0 } ?: return null
        return "위험 구간 전 ${fmtKm(rest)} 지점 휴게소에서 쉬어 가면 좋아요"
    }

    companion object {
        /** 아직 평가 전 (길 모양을 만들 때 자리만) */
        fun empty(totalM: Double) = RoutePreview(totalM, null, emptyList(), DoubleArray(0), emptyList(), false)

        fun place(s: RiskSegment): String {
            val a = (s.startM / 1000).roundToInt()
            val b = max(a + 1, (s.endM / 1000).roundToInt())
            return "${a}~${b}km"
        }

        fun fmtKm(m: Double): String = if (m < 10_000) "${(m / 100).roundToInt() / 10.0}km" else "${(m / 1000).roundToInt()}km"
    }
}

/**
 * 가는 길에서 멀미 위험 구간 찾기 — 모든 길에 같은 물리 기준.
 *
 * 1. 길 모양(굽은 정도, 높이, 서는 곳, 도로 속도 한도)에서 편하게 달릴 속도를 정한다:
 *    굽이에서 좌우 [RoutePath.A_LAT], 가감속 [RoutePath.A_LON], 방지턱은 시속 [BUMP_KMH] 로.
 * 2. 그 속도로 달릴 때의 앞뒤(dv/dt)·좌우(v²κ)·위아래(v²·d²z/dx²) 가속도를 [DT] 초 간격으로 만든다.
 * 3. 물컵·주행 평가와 같은 ISO 2631-1 Wf 가중을 세 축에 걸어 1km 마다 평균 세기 a_w = √(∫a_w²dt / T).
 * 4. a_w 를 주행 평가 점수식([TripReport]) 에 넣어 그 1km 만 달렸을 때 "찰랑"(70점 미만) 이면 주의, "출렁"(50점 미만) 이면 위험.
 *
 * 내비가 안내를 몇 단계로 나누든, 지점마다 점수를 주지 않으므로 결과가 같다. 안내 지점은 이유 설명에만 쓴다.
 */
object RoutePlanner {
    const val BIN_M = 1000.0
    const val DT = 0.1
    /** 방지턱을 넘는 속도 (km/h) */
    const val BUMP_KMH = 20.0
    /** 도심 교차로에서 서 있는 시간 (초) */
    const val STOP_SEC = 8.0
    /** 위아래 가속을 재는 앞뒤 거리 (m): 지형 자료의 잔 꺾임은 빼고 둔덕만 (시연 재생과 같다) */
    const val CURVE_M = 100.0
    /** 주의·위험 a_w (m/s²) = 주행 평가 점수 70점·50점이 되는 세기 */
    val CAUTION_AW: Double = TripReport.FREE_RMS + TripReport.RMS_SCALE * ln(100.0 / TripGrade.GENTLE.minScore)
    val DANGER_AW: Double = TripReport.FREE_RMS + TripReport.RMS_SCALE * ln(100.0 / TripGrade.SLOSH.minScore)
    /** 미리 보는 최대 거리 (m) */
    const val MAX_M = 300_000.0

    /** 내비 안내 지점만 있을 때: 그 지점으로 길 모양을 다시 그려([RoutePath.synthesize]) 평가 */
    fun plan(points: List<RoutePoint>, totalM: Double, etaSec: Double? = null, terrain: Terrain? = null): RoutePreview {
        val total = min(max(totalM, points.maxOfOrNull { it.atM } ?: 0.0), MAX_M)
        val path = RoutePath.synthesize(points, total, terrain, RoutePreview.empty(total))
        return assess(path, etaSec, hasTerrain = terrain != null)
    }

    /** 길 모양으로 평가 */
    fun assess(path: RoutePath, etaSec: Double? = null, hasTerrain: Boolean = true): RoutePreview {
        val step = RoutePath.STEP
        val n = min(path.size, (MAX_M / step).toInt() + 1)
        val total = (n - 1) * step
        val k = path.curvature
        val z = path.z
        // 1) 편한 속도 (m/s)
        val v = DoubleArray(n) { i -> min(path.speedKmh[i] / 3.6, sqrt(RoutePath.A_LAT / max(abs(k[i]), 1e-6))) }
        for (b in path.marks) if (b.kind == ManeuverKind.SPEED_BUMP) {
            for (i in path.index(b.atM - 10)..path.index(b.atM + 10)) v[i] = min(v[i], BUMP_KMH / 3.6)
        }
        val stopIdx = path.stops.map { path.index(it) }.filter { it < n }.distinct().sorted()
        v[0] = 0.0; v[n - 1] = 0.0
        for (i in stopIdx) v[i] = 0.0
        for (i in 1 until n) v[i] = min(v[i], sqrt(v[i - 1] * v[i - 1] + 2 * RoutePath.A_LON * step))
        for (i in n - 2 downTo 0) v[i] = min(v[i], sqrt(v[i + 1] * v[i + 1] + 2 * RoutePath.A_LON * step))
        // 2) 점마다 앞뒤·좌우·위아래 가속
        val h = (CURVE_M / step).toInt()
        val ax = DoubleArray(n) { i ->
            val a = max(0, i - 1); val b = min(n - 1, i + 1)
            if (b == a) 0.0 else (v[b] * v[b] - v[a] * v[a]) / (2 * (b - a) * step)
        }
        val ay = DoubleArray(n) { i -> v[i] * v[i] * k[i] }
        val az = DoubleArray(n) { i -> v[i] * v[i] * (z[max(0, i - h)] - 2 * z[i] + z[min(n - 1, i + h)]) / (CURVE_M * CURVE_M) }
        // 3) 시간 순서로 달리며 Wf 가중, 1km 칸마다 쌓기
        val bins = max(1, ceil(total / BIN_M).toInt())
        val ex = DoubleArray(bins); val ey = DoubleArray(bins); val ez = DoubleArray(bins); val time = DoubleArray(bins)
        val fx = Wf.Filter(DT); val fy = Wf.Filter(DT); val fz = Wf.Filter(DT)
        fun sample(bin: Int, x: Double, y: Double, zz: Double) {
            val wx = fx.step(x); val wy = fy.step(y); val wz = fz.step(zz)
            ex[bin] += wx * wx * DT; ey[bin] += wy * wy * DT; ez[bin] += wz * wz * DT; time[bin] += DT
        }
        var s = 0.0
        var si = 0
        while (s < total) {
            val f = s / step
            val i = min(n - 2, f.toInt())
            val w = f - i
            fun lerp(a: DoubleArray) = a[i] * (1 - w) + a[i + 1] * w
            val bin = min(bins - 1, (s / BIN_M).toInt())
            sample(bin, lerp(ax), lerp(ay), lerp(az))
            s += max(lerp(v), V_CRAWL) * DT
            // 서는 곳을 지나면 그 자리에서 잠깐 선다 (가속 0)
            while (si < stopIdx.size && s >= stopIdx[si] * step) {
                repeat((STOP_SEC / DT).toInt()) { sample(bin, 0.0, 0.0, 0.0) }
                si++
            }
        }
        val aw = DoubleArray(bins) { b -> if (time[b] > 0) sqrt((ex[b] + ey[b] + ez[b]) / time[b]) else 0.0 }
        // 4) 붙은 칸을 한 구간으로
        val counts = Array(bins) { HashMap<ManeuverKind, Int>() }
        for (p in path.marks) if (p.atM in 0.0..total && p.kind.shaky) {
            val b = min(bins - 1, (p.atM / BIN_M).toInt())
            counts[b][p.kind] = (counts[b][p.kind] ?: 0) + 1
        }
        val segments = ArrayList<RiskSegment>()
        var b = 0
        while (b < bins) {
            if (aw[b] < CAUTION_AW) { b++; continue }
            val start = b
            var level = 1
            var sx = 0.0; var sy = 0.0; var sz = 0.0
            val merged = HashMap<ManeuverKind, Int>()
            while (b < bins && aw[b] >= CAUTION_AW) {
                if (aw[b] >= DANGER_AW) level = 2
                sx += ex[b]; sy += ey[b]; sz += ez[b]
                counts[b].forEach { (kind, c) -> merged[kind] = (merged[kind] ?: 0) + c }
                b++
            }
            segments += RiskSegment(start * BIN_M, min(total, b * BIN_M), level, reasons(sx, sy, sz, merged))
        }
        val rests = path.marks.filter { it.kind == ManeuverKind.REST_AREA }.map { it.atM }.sorted()
        return RoutePreview(total, etaSec, segments, aw, rests, hasTerrain)
    }

    /** 이유: 흔들림이 큰 축 순서로 (좌우 → 굽이 안내, 앞뒤 → 가다 서다, 위아래 → 오르내림) */
    private fun reasons(sx: Double, sy: Double, sz: Double, marks: Map<ManeuverKind, Int>): List<String> {
        val sum = (sx + sy + sz).coerceAtLeast(1e-9)
        val out = ArrayList<Pair<Double, List<String>>>()
        if (sy / sum >= AXIS_SHARE) {
            val bends = marks.entries.filter { it.key in BEND_KINDS }.sortedByDescending { it.value }.map { "${it.key.label} ${it.value}곳" }
            out += sy to bends.ifEmpty { listOf("좌우 흔들림") }
        }
        if (sx / sum >= AXIS_SHARE) out += sx to listOf("가다 서다")
        if (sz / sum >= AXIS_SHARE) out += sz to listOf(if (sz / sum >= 0.5) "심한 오르내림" else "오르내림")
        val other = marks.entries.filter { it.key !in BEND_KINDS }.sortedByDescending { it.value }.map { "${it.key.label} ${it.value}곳" }
        return (out.sortedByDescending { it.first }.flatMap { it.second } + other).take(3)
    }

    private const val AXIS_SHARE = 0.25
    private const val V_CRAWL = 0.5
    private val BEND_KINDS = setOf(ManeuverKind.CURVE, ManeuverKind.SHARP_TURN, ManeuverKind.TURN, ManeuverKind.UTURN, ManeuverKind.ROUNDABOUT, ManeuverKind.IC_EXIT, ManeuverKind.IC_ENTER)
}
