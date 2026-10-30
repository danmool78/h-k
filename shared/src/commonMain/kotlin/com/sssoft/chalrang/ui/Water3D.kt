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
    var agitation = 0f
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
    private val cell = (2 * extent / N).toFloat()
    val argb = IntArray(N * N)

    private var p1x = 0.0
    private var p1y = 0.0
    private var acc = 0.0
    private val rnd = Random(3)

    init {
        for (i in 0 until N) xs[i] = ((i + 0.5) / N * 2 - 1).toFloat() * extent.toFloat()
        for (j in 0 until N) for (i in 0 until N) {
            inside[j * N + i] = xs[i] * xs[i] + xs[j] * xs[j] <= (extent * extent).toFloat()
        }
    }

    /** 흔들림 정도 (0~1): 기울기가 빨리 바뀔수록 커진다. 셰이더 잔물결에 쓴다 */
    var agitation = 0.0; private set

    /**
     * [dt] 초 진행. [px], [py] = 지금 물이 기울어야 하는 경사 (차 가속 ÷ 중력, 물컵 모델의 기울기),
     * [radius] = 지금 수면 높이의 컵 안쪽 반지름, [spilling] 이면 가장자리에서 물이 튄다.
     *
     * 가속은 벽에서만 들어간다: 벽에서의 수면 경사 = 가속/중력 (얕은 물 방정식의 경계 조건).
     * 그래서 가만히 있으면 수면이 그 경사의 평면으로 멈추고, 가속이 바뀌면 벽에서 물결이 생겨
     * 반대편으로 건너가고, 벽에서 되돌아오고, 서로 부딪혀 간섭 무늬를 만든다.
     */
    fun step(dt: Double, px: Double, py: Double, radius: Double, spilling: Boolean) {
        val rate = kotlin.math.hypot(px - p1x, py - p1y) / max(dt, 1e-3)
        agitation += ((rate * 2.5).coerceIn(0.0, 1.0) - agitation) * min(1.0, dt * 3)
        p1x = px; p1y = py
        sx = px.toFloat()
        sy = py.toFloat()
        val r2 = (radius * radius).toFloat()
        lastR2 = r2
        // 넘치면 가장자리에서 물이 튄다, 평소에도 가끔 작은 물방울
        if (rnd.nextDouble() < dt * (if (spilling) 6.0 else 0.15)) {
            val a = kotlin.math.atan2(py, px) + (rnd.nextDouble() - 0.5) * 1.6
            val rr = radius * (if (spilling) 0.85 else rnd.nextDouble() * 0.7)
            bump(rr * kotlin.math.cos(a), rr * kotlin.math.sin(a), if (spilling) 0.025 else 0.003, wide = 0.02)
        }
        acc += dt
        var n = 0
        while (acc >= SUB && n < 8) {
            acc -= SUB
            n++
            wave(r2)
        }
        if (n > 0) breakSteep(r2)
        if (acc > SUB) acc = 0.0
    }

    private var sx = 0f
    private var sy = 0f
    private var lastR2 = 0f

    /** 지금 경사의 평면 (비선형 항은 이것에 대한 높이로 본다) */
    private fun planeAt(i: Int, j: Int): Float = sx * xs[i] + sy * xs[j]

    /**
     * 부서짐: 물결이 너무 가팔라진 곳 몇 군데를 찾아 그 자리에 잔물결을 일으키고 그만큼 낮춘다.
     * 파도가 벽에 부딪혀 솟거나 물결끼리 겹쳐 높아지면 새 물결이 퍼진다.
     */
    private fun breakSteep(r2: Float) {
        repeat(BREAK_SAMPLES) {
            val i = 1 + rnd.nextInt(N - 2)
            val j = 1 + rnd.nextInt(N - 2)
            val k = j * N + i
            if (!inside[k] || xs[i] * xs[i] + xs[j] * xs[j] > r2) return@repeat
            val gx = (u[k + 1] - u[k - 1]) / (2 * cell) - sx
            val gy = (u[k + N] - u[k - N]) / (2 * cell) - sy
            val steep = kotlin.math.sqrt(gx * gx + gy * gy)
            if (steep > BREAK_STEEP) {
                val amp = (steep - BREAK_STEEP) * 0.008
                bump(xs[i].toDouble(), xs[j].toDouble(), amp.toDouble(), wide = 0.008)
                u[k] -= amp.toFloat() * 0.5f
            }
        }
    }

    private fun bump(x: Double, y: Double, amp: Double, wide: Double = 0.012) {
        for (j in 0 until N) for (i in 0 until N) {
            val k = j * N + i
            if (!inside[k]) continue
            val d2 = (xs[i] - x) * (xs[i] - x) + (xs[j] - y) * (xs[j] - y)
            u[k] += (amp * exp(-d2 / wide)).toFloat()
        }
    }

    private fun wave(r2: Float) {
        var mean = 0f
        var cnt = 0
        for (j in 0 until N) for (i in 0 until N) {
            val k = j * N + i
            if (!inside[k] || xs[i] * xs[i] + xs[j] * xs[j] > r2) { next[k] = 0f; continue }
            val c = u[k]
            // 벽 밖 이웃 = 자기 값 + 벽에서의 경사 × 간격 (가속이 벽에서 물을 민다)
            fun at(ii: Int, jj: Int): Float {
                val ghost = c + sx * (ii - i) * cell + sy * (jj - j) * cell
                if (ii < 0 || jj < 0 || ii >= N || jj >= N) return ghost
                val kk = jj * N + ii
                return if (inside[kk] && xs[ii] * xs[ii] + xs[jj] * xs[jj] <= r2) u[kk] else ghost
            }
            val lap = at(i - 1, j) + at(i + 1, j) + at(i, j - 1) + at(i, j + 1) - 4 * c
            // 비선형: 물이 높은 곳에서 물결이 더 빨리 달린다 (얕은 물 c² ∝ g·깊이) → 앞쪽이 가팔라지며 잔물결이 생긴다
            val local = C2 * (1f + NONLINEAR * (c - planeAt(i, j))).coerceIn(0.5f, 1.6f)
            // 점성: 아주 잔 굴곡은 금방 매끄러워진다 (큰 물결은 오래 간다)
            next[k] = (2 * c - prev[k] + local * lap) * DAMP + VISC * lap
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

    /** 셰이더용 그림: R·G = 16비트 높이 (가운데 0.5 = 0). 컵 밖 칸은 수면 경사를 이어 채워 벽에서 높이가 뚝 끊기지 않게 */
    fun encode(): IntArray {
        for (k in 0 until N * N) {
            val i = k % N
            val j = k / N
            val h = if (inside[k] && xs[i] * xs[i] + xs[j] * xs[j] <= lastR2) u[k] else sx * xs[i] + sy * xs[j]
            val v = ((h / SCALE + 0.5f).coerceIn(0f, 1f) * 65535f).toInt()
            argb[k] = (0xFF shl 24) or ((v shr 8) shl 16) or ((v and 0xFF) shl 8)
        }
        return argb
    }

    companion object {
        const val N = 48
        /** 담을 수 있는 수면 높이 ±SCALE/2 (기울기 포함) */
        const val SCALE = 1.4f
        private const val SUB = 1.0 / 120
        /** (c·dt/h)²: 물결 속도 약 1.9 단위/초 (컵 지름을 1초 남짓에 건넌다) */
        private const val C2 = 0.15f
        private const val DAMP = 0.997f
        /** 비선형 세기 (물결 높이에 따른 속도 변화) */
        private const val NONLINEAR = 0.6f
        /** 이보다 가파르면 부서진다 (평면 대비 경사) */
        private const val BREAK_STEEP = 3.0f
        private const val VISC = 0.06f
        private const val BREAK_SAMPLES = 24

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
uniform float uAgit;
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

float innerR(float z) { return RBI + CONE * z; }

// 잔물결: 방향이 다른 짧은 물결 몇 개 (흔들릴수록 크다) — 빛이 반짝이는 수면
float ripple(float2 xy) {
    float a = 0.0012 + 0.003 * uAgit;
    return a * (sin(dot(xy, float2(23.0, 9.0)) + uTime * 5.1)
              + 0.8 * sin(dot(xy, float2(-11.0, 21.0)) + uTime * 4.3)
              + 0.6 * sin(dot(xy, float2(17.0, -19.0)) - uTime * 6.2)
              + 0.4 * sin(dot(xy, float2(-29.0, -7.0)) + uTime * 7.7));
}

// 잔물결을 뺀 매끄러운 수면 (벽 쪽 판정·밝은 띠용)
float surfSmooth(float2 xy) { return uH0 + wave(xy); }

// 벽에 닿은 물 높이: 둘레 방향으로 고르게 (유리에 붙은 물 가장자리는 매끄럽다) + 메니스커스
float wallLevel(float2 xy) {
    float2 t = float2(-xy.y, xy.x) * 0.09;
    float2 inw = xy * 0.93;
    return (surfSmooth(inw) * 2.0 + surfSmooth(inw + t) + surfSmooth(inw - t)
          + surfSmooth(inw + 2.0 * t) + surfSmooth(inw - 2.0 * t)) / 6.0 + 0.02;
}

// 수면: 시뮬레이션(기울기·출렁임·간섭) + 잔물결 + 벽에서 말려 올라간 메니스커스
float surf(float2 xy) {
    float r = length(xy);
    float men = 0.022 * exp(-max(innerR(uH0) - r, 0.0) / 0.022);
    return uH0 + wave(xy) + ripple(xy) + men;
}

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

// 광선이 수면과 만나는 t: 평면 교점 근처를 짧게 훑어 위→아래로 바뀌는 곳을 찾고 이분법으로 좁힌다
// (잔물결이 촘촘해도 빗나가지 않는다)
float surfaceHit(float3 ro, float3 rd) {
    float den = rd.z - uPx * rd.x - uPy * rd.y;
    if (abs(den) < 1e-5) return -1.0;
    float t0 = (uH0 + uPx * ro.x + uPy * ro.y - ro.z) / den;
    // 수면 높이 차 ±0.35 만큼 광선을 앞뒤로
    float span = 0.35 / max(abs(rd.z), 0.2);
    float a = t0 - span;
    float prevT = a;
    float3 p = ro + rd * a;
    bool prevAbove = p.z > surf(p.xy);
    for (int k = 1; k <= 32; k++) {
        float t = a + 2.0 * span * float(k) / 32.0;
        p = ro + rd * t;
        bool above = p.z > surf(p.xy);
        if (prevAbove && !above) {
            float lo = prevT;
            float hi = t;
            for (int m = 0; m < 6; m++) {
                float mid = (lo + hi) * 0.5;
                float3 q = ro + rd * mid;
                if (q.z > surf(q.xy)) lo = mid; else hi = mid;
            }
            return hi;
        }
        prevAbove = above;
        prevT = t;
    }
    return -1.0;
}

// 법선: 시뮬레이션 물결은 격자 간격(0.05)으로 매끄럽게, 잔물결·메니스커스는 촘촘하게 (격자 모서리가 비치지 않게)
float3 surfNormal(float2 xy) {
    float e1 = 0.05;
    float e2 = 0.008;
    float wx = (wave(xy + float2(e1, 0.0)) - wave(xy - float2(e1, 0.0))) / (2.0 * e1);
    float wy = (wave(xy + float2(0.0, e1)) - wave(xy - float2(0.0, e1))) / (2.0 * e1);
    float rest0x = surf(xy + float2(e2, 0.0)) - wave(xy + float2(e2, 0.0));
    float rest1x = surf(xy - float2(e2, 0.0)) - wave(xy - float2(e2, 0.0));
    float rest0y = surf(xy + float2(0.0, e2)) - wave(xy + float2(0.0, e2));
    float rest1y = surf(xy - float2(0.0, e2)) - wave(xy - float2(0.0, e2));
    float rx = (rest0x - rest1x) / (2.0 * e2);
    float ry = (rest0y - rest1y) / (2.0 * e2);
    return normalize(float3(-(wx + rx), -(wy + ry), 1.0));
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

    // 컵 안(안쪽 원뿔)으로 들어오는 곳부터 따라간다: 들어오자마자 물속이면 옆에서 본 물,
    // 가다가 수면을 지나면 위에서 본 수면 (두 판정이 한 번에 정해져 틈이 생기지 않는다)
    float2 gi = coneHits(ro, rd, RBI);
    float tIn = -1.0;
    float zf = ro.z + rd.z * gi.x;
    if (gi.x > 0.0 && zf >= BASE && zf <= H) tIn = gi.x;
    else if (tTop > 0.0 && length(pTop.xy) <= innerR(H)) tIn = tTop;
    float tOut = gi.y;
    if (rd.z < 0.0) tOut = min(tOut, (BASE - ro.z) / rd.z);
    bool surfHit = false;
    bool wallHit = false;
    float3 pS = float3(0.0);
    float3 pW = float3(0.0);
    if (tIn > 0.0 && tOut > tIn) {
        float3 p0 = ro + rd * tIn;
        // 유리에 닿은 물 높이는 잔물결 없이 (가장자리가 매끈하게)
        if (p0.z <= surfSmooth(p0.xy) + 0.022) {
            wallHit = true;
            pW = p0;
        } else {
            float dt = (tOut - tIn) / 40.0;
            float prev = tIn;
            for (int k = 1; k <= 40; k++) {
                float t = tIn + dt * float(k);
                float3 p = ro + rd * t;
                if (p.z <= surf(p.xy)) {
                    float lo = prev;
                    float hi = t;
                    for (int m = 0; m < 7; m++) {
                        float mid = (lo + hi) * 0.5;
                        float3 q = ro + rd * mid;
                        if (q.z > surf(q.xy)) lo = mid; else hi = mid;
                    }
                    pS = ro + rd * hi;
                    surfHit = true;
                    break;
                }
                prev = t;
            }
        }
    }

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
        float3 body = mix(floorCol * mix(float3(1.0), uWater, 0.6), uWater, 0.55 + thick * 0.35);
        col = mix(body, refl, F) + spec;
        alpha = 0.97;
    } else if (wallHit) {
        // 옆에서 본 물: 물속 거리만큼 물 색, 위쪽이 밝다 (빛이 위에서)
        float d = exitDist(pW, rd);
        float thick = 1.0 - exp(-d * 1.4);
        float lift = smoothstep(BASE, uH0 + 0.2, pW.z);
        // 위쪽(빛이 드는 쪽)이 밝고 두꺼울수록 물 색이 진하다
        float3 body = uWater * (0.62 + 0.38 * lift) * (0.85 + 0.2 * thick) + float3(0.04, 0.05, 0.06);
        // 수면 바로 아래의 밝은 띠 (전반사)
        float meniscus = smoothstep(0.05, 0.0, surfSmooth(pW.xy) + 0.022 - pW.z);
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
