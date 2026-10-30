package com.sssoft.chalrang.core

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 기기 가속도 센서(중력 포함, 기기 좌표) → 차 기준 위아래 가속도와 길 경사.
 *
 * 기기가 차에 어떤 방향으로 붙어 있는지 모르므로 스스로 알아낸다.
 * - 위: 센서 값을 느리게 평균한 것이 중력 반대 방향 (차가 내는 가속은 알게 된 만큼 빼고 평균)
 * - 위아래 가속 = (센서 값 - 중력) · 위 → 기기 방향과 상관없다
 * - 기준 "위": 처음 2초 넘게 멈춰 있을 때의 위 (앞 방향을 찾는 데만 쓴다 — 비탈일 수 있다)
 * - 앞: 곧게 가속·제동할 때(차량 신호로 아는 앞뒤 가속) 센서의 수평 가속 방향을 모은다
 * - 길 경사: 지금 "위" 가 기준에서 앞뒤·좌우로 얼마나 기울었나 (오르막이면 위가 차 뒤쪽으로 기운다)에서
 *   기준 자리의 경사(= 기기가 붙은 각도 오차)를 계속 학습해 뺀다:
 *   · 지도 경사([update] 의 mapGrade, 길 안내 중 도로 높이에서)를 알면: 곧게 일정하게 달릴 때 (센서 경사 − 지도 경사) 를 약 [MAP_TAU] 초로 평균
 *   · 모르면: 길은 길게 보면 오르내림이 상쇄되므로 달리는 동안의 평균을 [DRIFT_TAU] 초에 걸쳐 0 으로 (옆 기울기는 늘 이 방식)
 *   그래서 비탈에서 출발해도 달리기 시작하면 맞춰진다
 *
 * 값마다 새 객체를 만들지 않는다 (초당 50번 불린다).
 */
class ImuFrame {
    /** 위아래 가속 (m/s², + 위로) */
    var vertical = 0.0; private set
    /** 길 경사 (rad): 앞이 올라감, 왼쪽이 올라감. [oriented] 일 때만 의미 있음 */
    var pitch = 0.0; private set
    var roll = 0.0; private set
    /** 학습한 기준 자리 경사 (rad): 센서 경사에서 뺀다 */
    var pitchOffset = 0.0; private set
    var rollOffset = 0.0; private set
    /** 지도 경사로 맞춘 적이 있는지 (없으면 평균 0 가정만) */
    var mapAnchored = false; private set
    /** 기준 수평과 앞 방향을 알아냈는지 */
    val oriented: Boolean get() = hasRef && fwdCount >= FWD_SAMPLES

    // 중력 추정 (기기 좌표, m/s²)
    private var gx = 0.0; private var gy = 0.0; private var gz = 0.0
    private var hasG = false
    private var lastT = Double.NaN
    // 기준 위 (단위)
    private var rx = 0.0; private var ry = 0.0; private var rz = 0.0
    private var hasRef = false
    private var stillSince = Double.NaN
    // 앞 방향 모음
    private var fx = 0.0; private var fy = 0.0; private var fz = 0.0
    private var fwdCount = 0
    // 앞·왼쪽 단위 (기준 위에 수직)
    private var ux = 0.0; private var uy = 0.0; private var uz = 0.0
    private var lx = 0.0; private var ly = 0.0; private var lz = 0.0

    /**
     * [ax], [ay], [az] = 센서 값 (기기 좌표, 중력 반작용 포함, m/s²).
     * [carAx], [carAy] = 차량 신호로 추정한 앞뒤·좌우 가속 (m/s²), [speed] = m/s.
     */
    fun update(t: Double, ax: Double, ay: Double, az: Double, carAx: Double, carAy: Double, speed: Double, mapGrade: Double? = null) {
        val dt = if (lastT.isNaN()) 0.0 else (t - lastT).coerceIn(0.0, 0.2)
        lastT = t
        // 차가 내는 가속(앞뒤·좌우)은 알게 된 만큼 빼고 중력을 평균한다 (가속 중에 오르막으로 착각하지 않게)
        var sx = ax; var sy = ay; var sz = az
        if (oriented) {
            sx -= carAx * ux + carAy * lx
            sy -= carAx * uy + carAy * ly
            sz -= carAx * uz + carAy * lz
        }
        if (!hasG) { gx = sx; gy = sy; gz = sz; hasG = true }
        val k = min(1.0, dt / GRAVITY_TAU)
        gx += (sx - gx) * k; gy += (sy - gy) * k; gz += (sz - gz) * k
        val gn = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(1e-6)
        val upx = gx / gn; val upy = gy / gn; val upz = gz / gn
        // 위아래 = 중력을 뺀 가속의 위 방향 성분
        val lx0 = ax - gx; val ly0 = ay - gy; val lz0 = az - gz
        vertical = lx0 * upx + ly0 * upy + lz0 * upz

        // 기준 수평: 멈춰 있는 2초
        if (!hasRef) {
            if (speed < 0.3) {
                if (stillSince.isNaN()) stillSince = t
                if (t - stillSince >= STILL_SEC) { rx = upx; ry = upy; rz = upz; hasRef = true }
            } else stillSince = Double.NaN
        }
        // 앞: 곧게 가속·제동하는 동안 수평 가속 방향을 모은다
        if (hasRef && fwdCount < FWD_SAMPLES && speed > 2.0 && abs(carAx) > 1.2 && abs(carAy) < 0.5) {
            val along = lx0 * rx + ly0 * ry + lz0 * rz
            val s = if (carAx > 0) 1.0 else -1.0
            fx += (lx0 - along * rx) * s; fy += (ly0 - along * ry) * s; fz += (lz0 - along * rz) * s
            fwdCount++
            if (fwdCount >= FWD_SAMPLES) {
                val fn = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-6)
                ux = fx / fn; uy = fy / fn; uz = fz / fn
                // 왼쪽 = 위 × 앞
                lx = ry * uz - rz * uy; ly = rz * ux - rx * uz; lz = rx * uy - ry * ux
            }
        }
        if (oriented) {
            val dx = upx - rx; val dy = upy - ry; val dz = upz - rz
            val rawPitch = -(dx * ux + dy * uy + dz * uz)
            val rawRoll = -(dx * lx + dy * ly + dz * lz)
            // 기준 자리 경사(기기 각도 오차) 학습: 곧게 일정하게 달릴 때만 (가속·회전 중엔 중력 추정이 흔들린다)
            val steady = speed > 4.0 && abs(carAx) < 0.6 && abs(carAy) < 0.8
            if (steady && dt > 0) {
                if (mapGrade != null) {
                    pitchOffset += (rawPitch - mapGrade - pitchOffset) * min(1.0, dt / MAP_TAU)
                    mapAnchored = true
                } else if (!mapAnchored) {
                    pitchOffset += (rawPitch - pitchOffset) * min(1.0, dt / DRIFT_TAU)
                }
                rollOffset += (rawRoll - rollOffset) * min(1.0, dt / DRIFT_TAU)
            }
            pitch = rawPitch - pitchOffset
            roll = rawRoll - rollOffset
        }
    }

    companion object {
        /** 중력 평균 시간 (초): 길 경사는 천천히 바뀌고, 방지턱 같은 위아래 흔들림은 남긴다 */
        const val GRAVITY_TAU = 1.0
        const val STILL_SEC = 2.0
        /** 지도 경사로 기기 각도 오차를 맞추는 시간 (초) */
        const val MAP_TAU = 20.0
        /** 지도가 없을 때 평균을 0 으로 맞추는 시간 (초): 길은 길게 보면 오르내림이 상쇄된다 */
        const val DRIFT_TAU = 600.0
        /** 앞 방향을 정하는 데 모으는 시료 수 (50Hz 로 약 2초의 곧은 가속·제동) */
        const val FWD_SAMPLES = 100
    }
}
