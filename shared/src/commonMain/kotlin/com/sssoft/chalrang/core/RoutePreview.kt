package com.sssoft.chalrang.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 경로 위 지점 (출발점부터 거리, m). [dir] 회전 방향 -1 왼쪽, +1 오른쪽, 0 모름 */
data class RoutePoint(val kind: ManeuverKind, val atM: Double, val dir: Int = 0)

/**
 * 가는 길의 지형: 경로를 따라 같은 간격으로 잰 높이 (m).
 * 내비가 경로 선을 주지 않아 지금 위치 → 목적지 직선 위에서 잰 값을 경로 길이에 맞춰 늘린 추정이다.
 */
class Terrain(val spacingM: Double, val heights: DoubleArray) {
    /** [from]~[to] (m) 구간에서 1km 당 오르내린 높이 합 (m/km) */
    fun climbPerKm(from: Double, to: Double): Double {
        if (heights.size < 2 || spacingM <= 0) return 0.0
        val a = (from / spacingM).toInt().coerceIn(0, heights.size - 1)
        val b = ceil(to / spacingM).toInt().coerceIn(0, heights.size - 1)
        if (b <= a) return 0.0
        var sum = 0.0
        for (i in a + 1..b) sum += abs(heights[i] - heights[i - 1])
        return sum / ((b - a) * spacingM / 1000)
    }
}

/** 멀미 위험 구간 (출발점부터 m). [level] 1 = 주의, 2 = 위험 */
data class RiskSegment(val startM: Double, val endM: Double, val level: Int, val reasons: List<String>) {
    val lengthM: Double get() = endM - startM
}

/** 출발 전에 보는 가는 길 요약 */
data class RoutePreview(
    val totalM: Double,
    val etaSec: Double?,
    val segments: List<RiskSegment>,
    /** km 마다 멀미 점수 (그림용) */
    val riskPerKm: DoubleArray,
    val restAreaM: List<Double>,
    val hasTerrain: Boolean,
) {
    val dangerCount: Int get() = segments.count { it.level >= 2 }

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
        fun place(s: RiskSegment): String {
            val a = (s.startM / 1000).roundToInt()
            val b = max(a + 1, (s.endM / 1000).roundToInt())
            return "${a}~${b}km"
        }

        fun fmtKm(m: Double): String = if (m < 10_000) "${(m / 100).roundToInt() / 10.0}km" else "${(m / 1000).roundToInt()}km"
    }
}

/**
 * 가는 길에서 멀미 위험 구간 찾기.
 * 1km 칸마다 점수 = 그 칸의 안내·주의 지점 점수([ManeuverKind.risk]) + 지형 오르내림 점수,
 * 이웃 칸과 1:2:1 로 고른 뒤 1.5 넘으면 주의, 3 넘으면 위험. 붙어 있는 칸은 한 구간으로 묶는다.
 */
object RoutePlanner {
    const val BIN_M = 1000.0
    /** 오르내림 (m/km) → 점수: 15 까지는 평지, 20 마다 1점, 최대 3점 */
    const val FLAT_CLIMB = 15.0
    const val CLIMB_PER_POINT = 20.0
    const val CAUTION = 1.5
    const val DANGER = 3.0
    /** 미리 보는 최대 거리 (m) */
    const val MAX_M = 300_000.0

    fun plan(points: List<RoutePoint>, totalM: Double, etaSec: Double? = null, terrain: Terrain? = null): RoutePreview {
        val total = min(max(totalM, points.maxOfOrNull { it.atM } ?: 0.0), MAX_M)
        val n = max(1, ceil(total / BIN_M).toInt())
        val raw = DoubleArray(n)
        val counts = Array(n) { HashMap<ManeuverKind, Int>() }
        val hill = DoubleArray(n)
        for (p in points) {
            if (p.atM < 0 || p.atM > total || p.kind.risk <= 0) continue
            val i = min(n - 1, (p.atM / BIN_M).toInt())
            raw[i] += p.kind.risk
            counts[i][p.kind] = (counts[i][p.kind] ?: 0) + 1
        }
        if (terrain != null) for (i in 0 until n) {
            val climb = terrain.climbPerKm(i * BIN_M, (i + 1) * BIN_M)
            hill[i] = ((climb - FLAT_CLIMB) / CLIMB_PER_POINT).coerceIn(0.0, 3.0)
            raw[i] += hill[i]
        }
        val risk = DoubleArray(n) { i ->
            val l = if (i > 0) raw[i - 1] else raw[i]
            val r = if (i < n - 1) raw[i + 1] else raw[i]
            0.25 * l + 0.5 * raw[i] + 0.25 * r
        }
        val segments = ArrayList<RiskSegment>()
        var i = 0
        while (i < n) {
            if (risk[i] < CAUTION) { i++; continue }
            val start = i
            var level = 1
            while (i < n && risk[i] >= CAUTION) { if (risk[i] >= DANGER) level = 2; i++ }
            val merged = HashMap<ManeuverKind, Int>()
            var hilly = 0.0
            // 이유는 구간 앞뒤 한 칸까지 (고르기로 번진 만큼)
            for (k in max(0, start - 1) until min(n, i + 1)) {
                counts[k].forEach { (kind, c) -> merged[kind] = (merged[kind] ?: 0) + c }
                hilly = max(hilly, hill[k])
            }
            val reasons = merged.entries.sortedByDescending { it.key.risk * it.value }.map { "${it.key.label} ${it.value}곳" }.toMutableList()
            if (hilly >= 1.0) reasons.add(0, if (hilly >= 2.0) "심한 오르내림" else "오르내림")
            segments += RiskSegment(start * BIN_M, min(total, i * BIN_M), level, reasons.take(3))
        }
        val rests = points.filter { it.kind == ManeuverKind.REST_AREA }.map { it.atM }.sorted()
        return RoutePreview(total, etaSec, segments, risk, rests, terrain != null)
    }
}
