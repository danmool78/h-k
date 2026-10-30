package com.sssoft.chalrang.ui

import androidx.compose.ui.graphics.Brush
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * GPU 셰이더로 그리는 3D 물컵 (앱: Android 13+ RuntimeShader, 웹: Skiko RuntimeEffect, 같은 SkSL 코드).
 * 지원하지 않으면 [brush] 가 null 이고, CupView 가 2D 그림으로 그린다.
 */
expect class WaterShader() {
    /** 이 기기에서 셰이더를 쓸 수 있는지 */
    val supported: Boolean

    fun setUniforms(u: WaterUniforms)

    /** 수면 물결 높이 (ARGB, R·G 에 16비트로 담음) */
    fun setHeights(argb: IntArray, size: Int)

    /** 이번 프레임에 쓸 붓. 실패하면 null */
    fun brush(): Brush?

    /** 붙잡은 GPU·네이티브 자원 반납 */
    fun release()
}

/** 셰이더에 넘기는 값 (화면 좌표는 px) */
class WaterUniforms {
    var width = 0f
    var height = 0f
    var unit = 1f
    var cx = 0f
    var cy = 0f
    var h0 = 1f
    var slopeX = 0f
    var slopeY = 0f
    var time = 0f
    var r = 0.3f
    var g = 0.76f
    var b = 0.97f
}

/**
 * 수면 물결 시뮬레이션: 원 안의 [N]×[N] 격자에서 파동 방정식을 푼다.
 * 수면 = 평면(물컵 모델의 기울기) + 물결 u. 평면이 바뀌는 만큼(2계 차분) 물결에 힘을 줘서
 * 차가 흔들리면 물이 출렁이고, 벽(원 경계)에서 되돌아온다.
 */
class WaterSim(private val extent: Double) {
    private val u = FloatArray(N * N)
    private val prev = FloatArray(N * N)
    private val next = FloatArray(N * N)
    private val inside = BooleanArray(N * N)
    private val xs = FloatArray(N)
    val argb = IntArray(N * N)

    private var p1x = 0.0
    private var p1y = 0.0
    private var p2x = 0.0
    private var p2y = 0.0
    private var acc = 0.0
    private val rnd = Random(3)

    init {
        for (i in 0 until N) xs[i] = ((i + 0.5) / N * 2 - 1).toFloat() * extent.toFloat()
        for (j in 0 until N) for (i in 0 until N) {
            inside[j * N + i] = xs[i] * xs[i] + xs[j] * xs[j] <= (extent * extent).toFloat()
        }
    }

    /**
     * [dt] 초 진행. [px], [py] = 지금 수면 평면의 기울기, [radius] = 지금 수면 높이의 컵 안쪽 반지름,
     * [spilling] 이면 가장자리에 잔물결을 더 일으킨다.
     */
    fun step(dt: Double, px: Double, py: Double, radius: Double, spilling: Boolean) {
        // 평면 기울기의 가속(2계 차분)만큼 물을 반대로 민다 → 출렁임
        val ddx = (px - 2 * p1x + p2x).coerceIn(-0.3, 0.3)
        val ddy = (py - 2 * p1y + p2y).coerceIn(-0.3, 0.3)
        p2x = p1x; p2y = p1y; p1x = px; p1y = py
        val r2 = (radius * radius).toFloat()
        for (j in 0 until N) for (i in 0 until N) {
            val k = j * N + i
            if (!inside[k] || xs[i] * xs[i] + xs[j] * xs[j] > r2) continue
            val f = (ddx * xs[i] + ddy * xs[j]).toFloat() * FORCE
            u[k] -= f
            prev[k] -= f * 0.5f
        }
        // 살아 있는 잔물결: 가끔 작은 물방울이 떨어진 듯
        if (rnd.nextDouble() < dt * (if (spilling) 6.0 else 0.6)) {
            val a = rnd.nextDouble() * 6.283
            val rr = radius * (if (spilling) 0.8 else rnd.nextDouble() * 0.7)
            bump(rr * kotlin.math.cos(a), rr * kotlin.math.sin(a), if (spilling) 0.03 else 0.008)
        }
        acc += dt
        var n = 0
        while (acc >= SUB && n < 4) {
            acc -= SUB
            n++
            wave(r2)
        }
        if (acc > SUB) acc = 0.0
    }

    private fun bump(x: Double, y: Double, amp: Double) {
        for (j in 0 until N) for (i in 0 until N) {
            val k = j * N + i
            if (!inside[k]) continue
            val d2 = (xs[i] - x) * (xs[i] - x) + (xs[j] - y) * (xs[j] - y)
            u[k] += (amp * exp(-d2 / 0.012)).toFloat()
        }
    }

    private fun wave(r2: Float) {
        var mean = 0f
        var cnt = 0
        for (j in 0 until N) for (i in 0 until N) {
            val k = j * N + i
            if (!inside[k] || xs[i] * xs[i] + xs[j] * xs[j] > r2) { next[k] = 0f; continue }
            val c = u[k]
            // 벽 밖 이웃은 자기 값 (반사)
            fun at(ii: Int, jj: Int): Float {
                if (ii < 0 || jj < 0 || ii >= N || jj >= N) return c
                val kk = jj * N + ii
                return if (inside[kk] && xs[ii] * xs[ii] + xs[jj] * xs[jj] <= r2) u[kk] else c
            }
            val lap = at(i - 1, j) + at(i + 1, j) + at(i, j - 1) + at(i, j + 1) - 4 * c
            next[k] = (2 * c - prev[k] + C2 * lap) * DAMP
            mean += next[k]
            cnt++
        }
        // 부피 보존: 물결의 평균은 0
        val m = if (cnt > 0) mean / cnt else 0f
        for (k in 0 until N * N) {
            prev[k] = u[k]
            u[k] = if (next[k] != 0f) next[k] - m else 0f
        }
    }

    /** 셰이더용 그림: R·G = 16비트 높이 (가운데 0.5 = 0) */
    fun encode(): IntArray {
        for (k in 0 until N * N) {
            val v = ((u[k] / SCALE + 0.5f).coerceIn(0f, 1f) * 65535f).toInt()
            argb[k] = (0xFF shl 24) or ((v shr 8) shl 16) or ((v and 0xFF) shl 8)
        }
        return argb
    }

    companion object {
        const val N = 48
        /** 담을 수 있는 물결 높이 ±SCALE/2 */
        const val SCALE = 0.5f
        private const val SUB = 1.0 / 60
        private const val C2 = 0.22f
        private const val DAMP = 0.994f
        private const val FORCE = 2.2f

        fun clampSize(v: Int) = max(1, min(v, N))
    }
}

/** 화면에 맞는 3D 물컵 장면 (SkSL / AGSL 공통). 기하 값은 CupView 와 같다 */
internal val WATER_SKSL: String by lazy {
    """
uniform float2 uRes;
uniform float uUnit;
uniform float uCx;
uniform float uCy;
uniform float uH0;
uniform float uPx;
uniform float uPy;
uniform float uTime;
uniform float3 uWater;
uniform shader heights;

const float RB = ${RB_};
const float RT = ${RT_};
const float H = ${H_};
const float BASE = ${BASE_};
const float WALL = ${WALL_};
const float CONE = (RT - RB) / H;
const float RBI = RB - WALL;
const float RTI = RT - WALL;
const float N = ${WaterSim.N}.0;
const float SCALE = ${WaterSim.SCALE};
const float COSP = ${COS_P};
const float SINP = ${SIN_P};
const float DIST = ${DIST_};

float wave(float2 xy) {
    float2 t = (xy / RTI * 0.5 + 0.5) * N;
    half4 c = heights.eval(clamp(t, float2(0.5), float2(N - 0.5)));
    return ((float(c.r) * 255.0 * 256.0 + float(c.g) * 255.0) / 65535.0 - 0.5) * SCALE;
}

float surf(float2 xy) { return uH0 + uPx * xy.x + uPy * xy.y + wave(xy); }

float innerR(float z) { return RBI + CONE * z; }

bool inWater(float3 p) {
    return p.z >= BASE && length(p.xy) <= innerR(p.z) && p.z <= surf(p.xy);
}

bool inCup(float3 p) {
    return p.z >= BASE && p.z <= H + 0.2 && length(p.xy) <= innerR(min(p.z, H));
}

float3 env(float3 d) {
    // 어두운 실내 + 위쪽의 넓은 조명 + 왼쪽 위 창
    float3 sky = mix(float3(0.02, 0.05, 0.10), float3(0.30, 0.55, 0.80), smoothstep(-0.3, 0.9, d.z));
    float box = smoothstep(0.85, 0.97, dot(d, normalize(float3(-0.45, -0.35, 0.82))));
    float strip = smoothstep(0.02, 0.0, abs(d.x + 0.55)) * smoothstep(0.1, 0.5, d.z);
    return sky + float3(1.0) * box * 1.4 + float3(0.8, 0.9, 1.0) * strip * 0.6;
}

float2 coneHits(float3 ro, float3 rd, float r0) {
    // |p.xy| = r0 + CONE * p.z 의 두 근
    float k = r0 + CONE * ro.z;
    float a = dot(rd.xy, rd.xy) - CONE * CONE * rd.z * rd.z;
    float b = 2.0 * (dot(ro.xy, rd.xy) - CONE * rd.z * k);
    float c = dot(ro.xy, ro.xy) - k * k;
    float disc = b * b - 4.0 * a * c;
    if (disc < 0.0 || abs(a) < 1e-6) return float2(-1.0);
    float s = sqrt(disc);
    return float2((-b - s) / (2.0 * a), (-b + s) / (2.0 * a));
}

// 광선이 수면(평면 + 물결)과 만나는 t: 평면과의 교점에서 시작해 뉴턴법으로 물결을 맞춘다
float surfaceHit(float3 ro, float3 rd) {
    float den = rd.z - uPx * rd.x - uPy * rd.y;
    if (abs(den) < 1e-5) return -1.0;
    float t = (uH0 + uPx * ro.x + uPy * ro.y - ro.z) / den;
    for (int k = 0; k < 4; k++) {
        float3 p = ro + rd * t;
        float e = 0.02;
        float wx = (wave(p.xy + float2(e, 0.0)) - wave(p.xy - float2(e, 0.0))) / (2.0 * e);
        float wy = (wave(p.xy + float2(0.0, e)) - wave(p.xy - float2(0.0, e))) / (2.0 * e);
        float f = p.z - surf(p.xy);
        float d = rd.z - (uPx + wx) * rd.x - (uPy + wy) * rd.y;
        if (abs(d) < 1e-5) break;
        t -= f / d;
    }
    return t;
}

float3 surfNormal(float2 xy) {
    float e = 0.06;
    float hx = surf(xy + float2(e, 0.0)) - surf(xy - float2(e, 0.0));
    float hy = surf(xy + float2(0.0, e)) - surf(xy - float2(0.0, e));
    return normalize(float3(-hx / (2.0 * e), -hy / (2.0 * e), 1.0));
}

// 물속에서 [dir] 로 나아가 벽·바닥에 닿기까지의 거리
float exitDist(float3 q, float3 dir) {
    float t = 9.0;
    if (dir.z < 0.0) t = min(t, (BASE - q.z) / dir.z);
    float2 c = coneHits(q, dir, RBI);
    if (c.y > 1e-4) t = min(t, c.y);
    return max(t, 0.0);
}

half4 main(float2 fc) {
    float xs = (fc.x - uCx) / uUnit;
    float ys = (uCy - fc.y) / uUnit;
    float3 fw = float3(0.0, COSP, -SINP);
    float3 up = float3(0.0, SINP, COSP);
    float3 ro = -DIST * fw;
    float3 rd = normalize(float3(xs, 0.0, 0.0) + ys * up + DIST * fw);
    float3 v = -rd;
    float3 L = normalize(float3(-0.45, -0.35, 0.82));

    // 바깥 유리(원뿔대) 앞·뒤
    float2 g = coneHits(ro, rd, RB);
    float tFront = -1.0;
    for (int i = 0; i < 2; i++) {
        float t = i == 0 ? g.x : g.y;
        float z = ro.z + rd.z * t;
        if (t > 0.0 && z >= 0.0 && z <= H && tFront < 0.0) tFront = t;
    }
    float tTop = (H - ro.z) / rd.z;
    float3 pTop = ro + rd * tTop;
    bool throughTop = tTop > 0.0 && length(pTop.xy) <= RT + 0.03;
    float tBottom = -ro.z / rd.z;
    float3 pBot = ro + rd * tBottom;
    bool hitsBase = tBottom > 0.0 && length(pBot.xy) <= RB;
    if (tFront < 0.0 && !throughTop && !hitsBase) return half4(0.0);

    float3 col = float3(0.0);
    float alpha = 0.0;

    // 1) 위에서 수면에 닿는가
    float tS = surfaceHit(ro, rd);
    float3 pS = ro + rd * tS;
    bool surfHit = tS > 0.0 && pS.z >= BASE && pS.z <= H && length(pS.xy) <= innerR(pS.z);
    // 2) 옆 유리를 지나 물에 들어가는가 (안쪽 벽 앞면이 수면 아래)
    float2 gi = coneHits(ro, rd, RBI);
    float3 pW = ro + rd * gi.x;
    bool wallHit = gi.x > 0.0 && pW.z >= BASE && pW.z <= H && pW.z <= surf(pW.xy);
    if (surfHit && wallHit) { if (gi.x < tS) surfHit = false; else wallHit = false; }

    if (surfHit) {
        float3 n = surfNormal(pS.xy);
        float F = 0.02 + 0.98 * pow(1.0 - max(dot(n, v), 0.0), 5.0);
        float3 refl = env(reflect(rd, n));
        float3 spec = float3(1.0) * pow(max(dot(n, normalize(L + v)), 0.0), 220.0) * 3.0;
        float3 dir = refract(rd, n, 0.75);
        float d = exitDist(pS, dir);
        float3 q = pS + dir * d;
        // 바닥 빛 무늬: 물결이 오목하면 빛이 모인다
        float lap = wave(q.xy + float2(0.05, 0.0)) + wave(q.xy - float2(0.05, 0.0)) + wave(q.xy + float2(0.0, 0.05)) + wave(q.xy - float2(0.0, 0.05)) - 4.0 * wave(q.xy);
        float caustic = clamp(1.0 - lap * 160.0, 0.5, 2.4);
        float onFloor = q.z < BASE + 0.03 ? 1.0 : 0.55;
        float3 floorCol = float3(0.80, 0.92, 1.0) * (0.45 + 0.35 * caustic) * onFloor;
        float thick = 1.0 - exp(-d * 1.4);
        float3 body = mix(floorCol * mix(float3(1.0), uWater, 0.6), uWater * 0.9, thick * 0.7);
        col = mix(body, refl, F) + spec;
        alpha = 0.97;
    } else if (wallHit) {
        // 옆에서 본 물: 물속 거리만큼 물 색, 위쪽이 밝다 (빛이 위에서)
        float d = exitDist(pW, rd);
        float thick = 1.0 - exp(-d * 1.4);
        float lift = smoothstep(BASE, uH0 + 0.2, pW.z);
        // 위쪽(빛이 드는 쪽)이 밝고 두꺼울수록 물 색이 진하다
        float3 body = uWater * (0.30 + 0.55 * lift) * (0.65 + 0.45 * thick) + float3(0.06, 0.09, 0.12);
        // 수면 바로 아래의 밝은 띠 (전반사)
        float meniscus = smoothstep(0.06, 0.0, surf(pW.xy) - pW.z);
        col = body + float3(0.85, 0.95, 1.0) * meniscus * 0.5;
        alpha = 0.95;
    }

    // 유리 앞면: 반사·가장자리 빛
    if (tFront > 0.0) {
        float3 p = ro + rd * tFront;
        float r = length(p.xy);
        float3 ng = normalize(float3(p.xy / max(r, 1e-4), -CONE));
        float cosv = abs(dot(ng, v));
        float Fg = 0.04 + 0.96 * pow(1.0 - cosv, 5.0);
        float edge = smoothstep(0.32, 0.04, cosv);
        float3 gl = env(reflect(rd, ng)) * Fg + float3(0.9, 0.96, 1.0) * edge * 0.6;
        float ga = clamp(0.05 + Fg * 0.6 + edge * 0.55, 0.0, 1.0);
        col = col * (1.0 - ga * 0.5) + gl;
        alpha = max(alpha, ga);
        if (p.z < BASE) {
            col = mix(col, float3(0.75, 0.88, 1.0) * 0.55, 0.55);
            alpha = max(alpha, 0.6);
        }
    } else if (hitsBase && !surfHit && !wallHit) {
        col = float3(0.6, 0.75, 0.9) * 0.4;
        alpha = 0.5;
    }
    // 가장자리 테두리
    if (throughTop) {
        float d = abs(length(pTop.xy) - (RT - WALL * 0.5));
        float rim = smoothstep(0.03, 0.0, d);
        col += float3(0.95, 0.98, 1.0) * rim;
        alpha = max(alpha, rim);
    }
    return half4(half3(col * alpha), half(alpha));
}
"""
}

// 셰이더 문자열에 넣는 기하·카메라 값 (CupView 와 같게)
private val RB_ = fmtF(CupGeometry.RB)
private val RT_ = fmtF(CupGeometry.RT)
private val H_ = fmtF(CupGeometry.H)
private val BASE_ = fmtF(CupGeometry.BASE)
private val WALL_ = fmtF(CupGeometry.WALL)
private val COS_P = fmtF(kotlin.math.cos(CupGeometry.PITCH))
private val SIN_P = fmtF(kotlin.math.sin(CupGeometry.PITCH))
private val DIST_ = fmtF(CupGeometry.DIST)

private fun fmtF(v: Double): String {
    val s = ((v * 1_000_000).toLong() / 1_000_000.0).toString()
    return if ('.' in s || 'e' in s || 'E' in s) s else "$s.0"
}
private fun fmtF(v: Float) = fmtF(v.toDouble())
