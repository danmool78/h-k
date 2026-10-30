package com.sssoft.chalrang.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 주행 등급: 점수 구간마다 물컵 모습으로 */
enum class TripGrade(val label: String, val line: String, val minScore: Int) {
    CALM("잔잔", "물이 거의 흔들리지 않았어요", 85),
    GENTLE("살랑", "조금 흔들렸지만 편안했어요", 70),
    SLOSH("찰랑", "물이 자주 출렁였어요", 50),
    ROUGH("출렁", "물이 많이 넘쳤어요", 0);

    companion object {
        fun of(score: Int): TripGrade = entries.first { score >= it.minScore }
    }
}

/** 흔들림을 만든 운전 (횟수를 센다) */
enum class Jolt(val label: String, val tip: String) {
    BRAKE("급제동", "미리 천천히 서면 물이 덜 출렁여요"),
    ACCEL("급출발", "출발할 때 천천히 밟아 주세요"),
    TURN("급회전", "굽은 길에 들어가기 전에 속도를 줄여 주세요"),
    BUMP("방지턱·둔덕", "방지턱과 둔덕은 천천히 넘어 주세요"),
}

/**
 * 한 주행의 평가 (주행이 끝날 때 기록 [DriveTrace] 에서 계산).
 *
 * - 점수(0~100): 거리에 상관없이 "어떻게 몰았나". Wf 가중 가속도의 평균 세기 a_w = MSDV/√T 와
 *   10분당 넘친 횟수로 깎는다 (부드러운 시연 약 90점, 거친 시연 약 35점이 되도록 맞춤)
 * - 멀미할 수 있는 비율: ISO 2631-1 부록 D, 성인 기준 약 MSDV/3 % (아이는 더 예민할 수 있다)
 */
data class TripReport(
    val durationSec: Double,
    val distanceKm: Double,
    val score: Int,
    val grade: TripGrade,
    val spills: Int,
    val peakDose: Double,
    val msdv: Double,
    /** Wf 가중 가속도 평균 세기 (m/s²) */
    val weightedRms: Double,
    /** ISO 2631-1 부록 D: 멀미(구토)할 수 있는 성인 비율 (%) */
    val illnessPct: Double,
    /** 달린 시간 중 물이 넘침 기준의 절반 아래였던 비율 */
    val calmShare: Double,
    val jolts: Map<Jolt, Int>,
    /** 가장 출렁인 순간 (주행 시작부터 초) 과 그때 원인 */
    val worstAtSec: Double,
    val worstCause: String,
    /** 가장 영향을 준 흔들림 주기 (초), 없으면 null */
    val sicknessPeriod: Double?,
    val tips: List<String>,
) {
    /** 음성 한 줄 */
    fun spoken(previous: Int?): String {
        val base = "이번 주행은 ${score}점, ${grade.label}이에요."
        val top = jolts.entries.filter { it.value > 0 }.maxByOrNull { it.value }
        val cause = top?.let { " ${it.key.label}이 ${it.value}번 있었어요." } ?: ""
        val diff = previous?.let { p -> val d = score - p; if (d >= 3) " 지난번보다 ${d}점 올랐어요." else if (d <= -3) " 지난번보다 ${-d}점 낮아요." else "" } ?: ""
        return base + cause + diff
    }

    companion object {
        /** 이보다 짧게 달렸으면 평가하지 않는다 (초) */
        const val MIN_MOVING_SEC = 60.0
        const val MOVING_KMH = 3.0

        /** 점수: a_w 가 이만큼(m/s²)까지는 깎지 않는다 (가다 서다만 해도 나오는 흔들림) */
        const val FREE_RMS = 0.2
        const val RMS_SCALE = 1.2
        /** 10분당 넘침 1번에 깎는 점수, 최대 */
        const val SPILL_POINT = 0.5
        const val SPILL_MAX = 25.0

        /** ISO 2631-1 부록 D: 구토 비율 % = Km · MSDV, 성인 남녀 섞인 경우 Km ≈ 1/3 */
        const val KM = 1.0 / 3.0

        // 흔들림 운전 판정 (m/s²): 넘으면 한 번, 아래로 내려와야 다시 센다, 같은 종류는 3초 안에 한 번
        const val BRAKE_ON = 3.0
        const val ACCEL_ON = 2.5
        const val TURN_ON = 3.0
        const val BUMP_ON = 2.0
        const val JOLT_GAP = 3.0

        fun evaluate(trace: DriveTrace, spills: Int = -1): TripReport? {
            val n = trace.size
            if (n < 2) return null
            val dt = trace.interval
            val fx = Wf.Filter(dt)
            val fy = Wf.Filter(dt)
            val fz = Wf.Filter(dt)
            var energy = 0.0
            var moving = 0.0
            var calm = 0.0
            var dist = 0.0
            var peakDose = 0.0
            var spillCount = 0
            var wasSpilling = false
            var worst = -1.0
            var worstI = 0
            val kinds = Jolt.entries.toTypedArray()
            val counts = IntArray(kinds.size)
            val armed = BooleanArray(Jolt.entries.size) { true }
            val lastAt = DoubleArray(Jolt.entries.size) { Double.NEGATIVE_INFINITY }
            for (i in 0 until n) {
                val ax = trace.axAt(i)
                val ay = trace.ayAt(i)
                val az = trace.azAt(i)
                val wx = fx.step(ax)
                val wy = fy.step(ay)
                val wz = fz.step(az)
                val v = trace.speedAt(i)
                val step = if (i > 0) (trace.timeAt(i) - trace.timeAt(i - 1)).coerceIn(0.0, 1.0) else 0.0
                energy += (wx * wx + wy * wy + wz * wz) * step
                dist += v / 3.6 * step
                val tilt = hypot(hypot(trace.tiltXAt(i), trace.tiltYAt(i)), trace.tiltZAt(i))
                if (v >= MOVING_KMH) {
                    moving += step
                    if (tilt < trace.freeboardAt(i) / 2) calm += step
                }
                peakDose = max(peakDose, trace.doseAt(i))
                val sp = trace.spillingAt(i)
                if (sp && !wasSpilling) spillCount++
                wasSpilling = sp
                if (tilt > worst) { worst = tilt; worstI = i }
                val t = trace.timeAt(i)
                for (k in kinds.indices) {
                    val j = kinds[k]
                    val value = when (j) { Jolt.BRAKE -> -ax; Jolt.ACCEL -> ax; Jolt.TURN -> abs(ay); Jolt.BUMP -> abs(az) }
                    val on = when (j) { Jolt.BRAKE -> BRAKE_ON; Jolt.ACCEL -> ACCEL_ON; Jolt.TURN -> TURN_ON; Jolt.BUMP -> BUMP_ON }
                    if (armed[k] && value >= on) {
                        armed[k] = false
                        if (t - lastAt[k] >= JOLT_GAP) { counts[k]++; lastAt[k] = t }
                    } else if (!armed[k] && value < on * 0.66) armed[k] = true
                }
            }
            if (moving < MIN_MOVING_SEC) return null
            val total = (trace.timeAt(n - 1) - trace.timeAt(0)).coerceAtLeast(1e-6)
            val msdv = sqrt(energy)
            val rms = msdv / sqrt(total)
            val spillsUsed = if (spills >= 0) spills else spillCount
            val perTen = spillsUsed / (moving / 600.0)
            val score = (100 * exp(-max(0.0, rms - FREE_RMS) / RMS_SCALE) - min(SPILL_MAX, SPILL_POINT * perTen))
                .roundToInt().coerceIn(0, 100)
            val jolts = Jolt.entries.associateWith { counts[it.ordinal] }
            val shake = ShakeAnalysis.analyze(trace, trace.timeAt(0), trace.timeAt(n - 1))?.takeIf { !it.quiet }
            return TripReport(
                durationSec = total, distanceKm = dist / 1000, score = score, grade = TripGrade.of(score),
                spills = spillsUsed, peakDose = peakDose, msdv = msdv, weightedRms = rms, illnessPct = KM * msdv,
                calmShare = if (moving > 0) calm / moving else 1.0, jolts = jolts,
                worstAtSec = trace.timeAt(worstI) - trace.timeAt(0), worstCause = cause(trace, worstI),
                sicknessPeriod = shake?.sicknessPeriod, tips = tips(jolts, shake, score),
            )
        }

        /** 그 순간 앞 몇 초 동안 가장 센 흔들림이 무엇이었나 (물은 늦게 따라오므로) */
        private fun cause(trace: DriveTrace, at: Int): String {
            var bx = 0.0; var by = 0.0; var bz = 0.0
            val from = max(0, at - (4.0 / trace.interval).toInt())
            var brake = false
            for (i in from..at) {
                if (abs(trace.axAt(i)) > bx) { bx = abs(trace.axAt(i)); brake = trace.axAt(i) < 0 }
                by = max(by, abs(trace.ayAt(i)))
                bz = max(bz, abs(trace.azAt(i)))
            }
            return when {
                bz >= bx && bz >= by -> "위아래 (방지턱·둔덕)"
                by >= bx -> "좌우 (회전)"
                brake -> "앞뒤 (제동)"
                else -> "앞뒤 (가속)"
            }
        }

        private fun tips(jolts: Map<Jolt, Int>, shake: ShakeReport?, score: Int): List<String> {
            val out = jolts.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(2)
                .map { "${it.key.label} ${it.value}번 — ${it.key.tip}" }.toMutableList()
            if (shake != null && shake.bandShare >= 0.3) {
                out += "${(shake.sicknessPeriod * 10).roundToInt() / 10.0}초 주기의 느린 출렁임이 많았어요 — 멀미를 가장 잘 일으키는 흔들림이에요"
            }
            if (out.isEmpty()) out += if (score >= TripGrade.CALM.minScore) "아주 부드러웠어요. 이대로면 아이가 편해요" else "속도를 조금 더 고르게 유지해 보세요"
            return out.take(3)
        }
    }
}

/** 지난 주행 기록 (저장용 요약) */
data class TripSummary(val score: Int, val durationSec: Double, val distanceKm: Double, val spills: Int, val peakDose: Double) {
    companion object {
        const val KEEP = 20
        fun of(r: TripReport) = TripSummary(r.score, r.durationSec, r.distanceKm, r.spills, r.peakDose)

        /** 저장 글: 한 줄에 하나, 쉼표로 */
        fun encode(list: List<TripSummary>): String =
            list.joinToString("\n") { "${it.score},${it.durationSec.roundToInt()},${(it.distanceKm * 10).roundToInt() / 10.0},${it.spills},${(it.peakDose * 100).roundToInt() / 100.0}" }

        fun decode(text: String?): List<TripSummary> = text.orEmpty().lines().mapNotNull { line ->
            val p = line.split(',')
            runCatching { TripSummary(p[0].toInt(), p[1].toDouble(), p[2].toDouble(), p[3].toInt(), p[4].toDouble()) }.getOrNull()
        }
    }
}
