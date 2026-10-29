package com.sssoft.chalrang.core

import kotlin.math.sqrt

/** 기록에 남기는 일 */
enum class TraceKind(val label: String) {
    SPILL("넘침"),
    SAY("안내"),
    CAR("차량"),
    SICK("지금 멀미"),
}

data class TraceEvent(val t: Double, val kind: TraceKind, val text: String)

/** 한 시점의 기록 */
data class TraceSample(
    val t: Double,
    /** 차 흔들림 (원인): 앞뒤 + 가속, 좌우 + 왼쪽으로 도는 중 (m/s²) */
    val ax: Double,
    val ay: Double,
    /** 물 출렁임 (결과): 수면 기울기 (g) */
    val tiltX: Double,
    val tiltY: Double,
    val speedKmh: Double,
    val dose: Double,
    val level: Double,
    val spilling: Boolean,
) {
    /** 넘치기 시작하는 기울기 (g) */
    val freeboard: Double get() = WaterCup.freeboardOf(level)
}

/** 지연 분석 결과: 물이 차 흔들림보다 [lagSec] 초 늦게 따라온다 (상관 [correlation]) */
data class Lag(val lagSec: Double, val correlation: Double)

/**
 * 상세 흔들림 기록.
 *
 * 차의 흔들림(원인)과 물의 출렁임(결과)을 함께 [interval] 초마다 남긴다.
 * 물은 차 흔들림을 바로 따라가지 않고 늦게, 공진하며 출렁이므로 두 값을 같은 시간축에 겹치면 지연이 보인다.
 * 원인만 있어도 물컵 모델로 결과를 다시 계산할 수 있지만, 그때의 물 높이·누적량까지 남겨 그대로 재생한다.
 */
class DriveTrace(val interval: Double = 0.1, private val capacity: Int = 72_000) {

    private val t = DoubleArray(capacity)
    private val ax = FloatArray(capacity)
    private val ay = FloatArray(capacity)
    private val tx = FloatArray(capacity)
    private val ty = FloatArray(capacity)
    private val speed = FloatArray(capacity)
    private val dose = FloatArray(capacity)
    private val level = FloatArray(capacity)
    private val spill = BooleanArray(capacity)
    private var start = 0
    var size = 0; private set
    private var lastT = Double.NEGATIVE_INFINITY

    private val _events = ArrayDeque<TraceEvent>()
    val events: List<TraceEvent> get() = _events.toList()

    /** 기록이 바뀔 때마다 늘어난다 (화면 갱신용) */
    var version = 0L; private set

    fun record(
        time: Double, ax: Double, ay: Double, tiltX: Double, tiltY: Double,
        speedKmh: Double, dose: Double, level: Double, spilling: Boolean,
    ) {
        if (time - lastT < interval * 0.999) return
        lastT = time
        val i = if (size < capacity) (start + size++) % capacity else start.also { start = (start + 1) % capacity }
        this.t[i] = time
        this.ax[i] = ax.toFloat(); this.ay[i] = ay.toFloat()
        this.tx[i] = tiltX.toFloat(); this.ty[i] = tiltY.toFloat()
        this.speed[i] = speedKmh.toFloat(); this.dose[i] = dose.toFloat(); this.level[i] = level.toFloat()
        this.spill[i] = spilling
        version++
    }

    fun event(time: Double, kind: TraceKind, text: String = "") {
        _events.addLast(TraceEvent(time, kind, text))
        while (_events.size > MAX_EVENTS) _events.removeFirst()
        version++
    }

    operator fun get(i: Int): TraceSample {
        val k = (start + i) % capacity
        return TraceSample(
            t[k], ax[k].toDouble(), ay[k].toDouble(), tx[k].toDouble(), ty[k].toDouble(),
            speed[k].toDouble(), dose[k].toDouble(), level[k].toDouble(), spill[k],
        )
    }

    fun timeAt(i: Int): Double = t[(start + i) % capacity]

    val startTime: Double get() = if (size == 0) 0.0 else timeAt(0)
    val endTime: Double get() = if (size == 0) 0.0 else timeAt(size - 1)

    /** [time] 에 가장 가까운 기록 번호 */
    fun indexAt(time: Double): Int {
        if (size == 0) return -1
        var lo = 0
        var hi = size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (timeAt(mid) < time) lo = mid + 1 else hi = mid
        }
        return if (lo > 0 && time - timeAt(lo - 1) < timeAt(lo) - time) lo - 1 else lo
    }

    /**
     * [from]~[to] 구간에서 물 출렁임이 차 흔들림보다 얼마나 늦게 따라오는지.
     * 차 흔들림이 물을 미는 방향(-a/g)과 물 기울기의 상관이 가장 큰 시간차를 찾는다 (0~[maxLagSec]초).
     */
    fun lag(from: Double = startTime, to: Double = endTime, maxLagSec: Double = 5.0): Lag? {
        val i0 = indexAt(from).coerceAtLeast(0)
        val i1 = indexAt(to)
        val n = i1 - i0 + 1
        val maxLag = (maxLagSec / interval).toInt()
        if (n < maxLag * 3) return null
        val inX = DoubleArray(n) { -ax[(start + i0 + it) % capacity] / WaterCup.G }
        val inY = DoubleArray(n) { -ay[(start + i0 + it) % capacity] / WaterCup.G }
        val outX = DoubleArray(n) { tx[(start + i0 + it) % capacity].toDouble() }
        val outY = DoubleArray(n) { ty[(start + i0 + it) % capacity].toDouble() }
        listOf(inX, inY, outX, outY).forEach { a -> val m = a.average(); for (k in a.indices) a[k] -= m }
        var best = Lag(0.0, -1.0)
        for (l in 0..maxLag) {
            var c = 0.0; var ei = 0.0; var eo = 0.0
            for (k in 0 until n - l) {
                c += inX[k] * outX[k + l] + inY[k] * outY[k + l]
                ei += inX[k] * inX[k] + inY[k] * inY[k]
                eo += outX[k + l] * outX[k + l] + outY[k + l] * outY[k + l]
            }
            if (ei <= 0 || eo <= 0) continue
            val r = c / sqrt(ei * eo)
            if (r > best.correlation) best = Lag(l * interval, r)
        }
        return best.takeIf { it.correlation > 0 }
    }

    fun clear() {
        start = 0; size = 0; lastT = Double.NEGATIVE_INFINITY
        _events.clear()
        version++
    }

    companion object {
        const val MAX_EVENTS = 3000
    }
}
