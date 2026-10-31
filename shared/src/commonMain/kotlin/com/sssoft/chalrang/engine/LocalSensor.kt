package com.sssoft.chalrang.engine

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 이 화면 기기의 움직임 센서 (웹: 휴대폰·태블릿 브라우저의 devicemotion). 시연을 켜지 않아도
 * 기기를 흔들면 그 흔들림이 곧바로 물컵·그래프로 간다.
 *
 * 놓인 자세와 상관없이 차 기준 (앞, 왼쪽, 위) 로 바꾼다 (휴대폰 시연 페이지와 같은 방법):
 * - 위 = 중력 반대 방향 (기기가 자이로로 구한 중력, 0.5초쯤 고름)
 * - 앞 = 처음 자세에서 기기 위쪽(눕혔을 때)과 뒷면(세웠을 때, 화면이 사람 쪽) 중 더 수평인 쪽
 * - 왼쪽 = 위 × 앞
 * - 경사 = 처음 자세를 평지로 보고 위가 기운 만큼
 * [IDLE_SEC] 동안 값이 안 오면 끝난 것으로 보고, 다음에 오면 자세를 새로 찾는다.
 *
 * 기기는 초당 60번쯤 값을 주므로 [DT] 마다 평균해 한 시료로 낸다 (듬성듬성 오면 지난 시간만큼 같은 시료를 채움).
 */
class LocalSensor {
    private val queue = ArrayDeque<RemoteSample>()
    private var lastT = Double.NaN
    // 고른 중력 (기기 좌표)
    private var ux = 0.0; private var uy = 0.0; private var uz = 0.0
    private var hasUp = false
    // 처음 자세: 위, 앞, 왼쪽
    private var u0 = DoubleArray(3); private var f = DoubleArray(3); private var l = DoubleArray(3)
    private var framed = false
    // [DT] 동안의 합
    private var sx = 0.0; private var sy = 0.0; private var sz = 0.0; private var n = 0; private var binT = 0.0

    /** 놓인 자세 이름 (화면에 보여 줄 때): 아직 모르면 null */
    var mount: String? = null; private set
    /** 최근 1초에 받은 값 수 (넘겨주는 쪽이 채움, 화면에 보여 줄 때) */
    var rateHz = 0
    /** 기기가 순수 가속을 주는지 (아니면 느린 평균으로 중력을 뺀 값) */
    var linear = true

    /** 화면 한 줄: "세워서 · 60Hz" */
    val status: String get() = listOfNotNull(mount, if (rateHz > 0) "${rateHz}Hz" else null, if (!linear) "자이로 없음" else null).joinToString(" · ")

    /** 엔진 시계로 마지막에 값을 받은 때 ([received]) */
    var receivedAt = Double.NaN; private set

    /** 최근 [IDLE_SEC] 안에 값이 왔는지 ([now] = 엔진 시계) */
    fun active(now: Double): Boolean = !receivedAt.isNaN() && now - receivedAt < IDLE_SEC

    /** 값이 왔었고 [GRACE_SEC] 안에 끊긴 것 (그동안은 같은 기록을 이어 간다) */
    fun recent(now: Double): Boolean = !receivedAt.isNaN() && now - receivedAt < GRACE_SEC

    /** 값을 넘겨준 쪽이 엔진 시계([now])로 받은 때를 알린다 (기기 값의 [onMotion] 시각은 기기 시계) */
    fun received(now: Double) { receivedAt = now }

    /**
     * 기기 값 하나: [t] 초 (기기 시계), 중력 반작용 (gx, gy, gz: 가만히 있으면 위로 9.8), 순수 가속 (lx, ly, lz), 기기 좌표 m/s².
     */
    fun onMotion(t: Double, gx: Double, gy: Double, gz: Double, lx: Double, ly: Double, lz: Double) {
        if (!lastT.isNaN() && t - lastT > IDLE_SEC) reset()
        val dt = if (lastT.isNaN()) 0.0 else (t - lastT).coerceIn(0.0, 0.2)
        lastT = t
        if (!hasUp) { ux = gx; uy = gy; uz = gz; hasUp = true }
        val k = (dt / UP_TAU).coerceAtMost(1.0)
        ux += (gx - ux) * k; uy += (gy - uy) * k; uz += (gz - uz) * k
        val un = sqrt(ux * ux + uy * uy + uz * uz)
        if (un < 1.0) return
        val up0 = ux / un; val up1 = uy / un; val up2 = uz / un
        if (!framed) {
            // 기기 위쪽 (0,1,0) 이 더 수평이면 눕힌 것, 아니면 세운 것 → 뒷면 (0,0,-1) 이 앞
            val flat = abs(up1) < abs(up2)
            val a = if (flat) doubleArrayOf(0.0, 1.0, 0.0) else doubleArrayOf(0.0, 0.0, -1.0)
            val d = a[0] * up0 + a[1] * up1 + a[2] * up2
            val fx = a[0] - d * up0; val fy = a[1] - d * up1; val fz = a[2] - d * up2
            val fn = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-6)
            u0 = doubleArrayOf(up0, up1, up2)
            f = doubleArrayOf(fx / fn, fy / fn, fz / fn)
            l = doubleArrayOf(up1 * f[2] - up2 * f[1], up2 * f[0] - up0 * f[2], up0 * f[1] - up1 * f[0])
            framed = true
            mount = if (flat) "눕혀서" else "세워서"
            binT = t
        }
        sx += lx * f[0] + ly * f[1] + lz * f[2]
        sy += lx * l[0] + ly * l[1] + lz * l[2]
        sz += lx * up0 + ly * up1 + lz * up2
        n++
        if (t - binT >= DT) {
            // 실제 시간대로: 값이 듬성듬성 와도 지난 시간만큼 시료를 채운다 (시간이 줄어들면 흔들림 주기가 짧아져 물이 덜 반응한다)
            val k = ((t - binT) / DT).toInt().coerceIn(1, MAX_FILL)
            binT = if (t - binT > MAX_FILL * DT) t else binT + k * DT
            val du0 = up0 - u0[0]; val du1 = up1 - u0[1]; val du2 = up2 - u0[2]
            val px = -(du0 * f[0] + du1 * f[1] + du2 * f[2])
            val py = -(du0 * l[0] + du1 * l[1] + du2 * l[2])
            val sample = (
                RemoteSample(
                    (sx / n).coerceIn(-RemoteFeed.MAX_ACC, RemoteFeed.MAX_ACC),
                    (sy / n).coerceIn(-RemoteFeed.MAX_ACC, RemoteFeed.MAX_ACC),
                    (sz / n).coerceIn(-RemoteFeed.MAX_ACC, RemoteFeed.MAX_ACC),
                    px.coerceIn(-RemoteFeed.MAX_GRADE, RemoteFeed.MAX_GRADE),
                    py.coerceIn(-RemoteFeed.MAX_GRADE, RemoteFeed.MAX_GRADE),
                )
            )
            repeat(k) { queue.addLast(sample) }
            while (queue.size > MAX_QUEUE) queue.removeFirst()
            sx = 0.0; sy = 0.0; sz = 0.0; n = 0
        }
    }

    /** 쌓인 시료를 모두 꺼낸다 (실시간: 기다리지 않음) */
    fun take(): List<RemoteSample> {
        if (queue.isEmpty()) return emptyList()
        val out = queue.toList()
        queue.clear()
        return out
    }

    private fun reset() {
        hasUp = false; framed = false; mount = null
        sx = 0.0; sy = 0.0; sz = 0.0; n = 0
        queue.clear()
    }

    companion object {
        /** 시료 간격 (초) = 20Hz, 휴대폰 시연과 같다 */
        const val DT = 0.05
        const val UP_TAU = 0.5
        const val IDLE_SEC = 2.0
        /** 이만큼 끊겨도 기록을 끝내지 않는다 (초): 탭을 잠깐 바꾸거나 손을 뗐을 때 */
        const val GRACE_SEC = 30.0
        const val MAX_QUEUE = 100
        /** 한 번에 채우는 시료 한도 (0.5초): 그보다 오래 끊기면 채우지 않고 이어 간다 */
        const val MAX_FILL = 10
    }
}
