package com.sssoft.chalrang.ui

import androidx.compose.ui.graphics.Brush
import com.sssoft.chalrang.core.TerrainGrid

/**
 * GPU 로 그리는 지형 (앱: Android 13+ RuntimeShader, 웹: Skiko RuntimeEffect, 같은 SkSL).
 * 높이를 그림(16비트를 R·G 에)으로 올리고 화소마다 시선을 쏴 산에 닿는 곳을 찾아,
 * 매끄러운 그림자·높이별 색·50m 등고선·거리 안개로 칠한다. 칸 모양이 없다.
 * 지원하지 않으면 [supported] = false → 칸으로 칠하는 [TerrainShade] 로.
 */
expect class TerrainShader() {
    val supported: Boolean

    /** 지형을 바꿀 때만 (그림을 새로 올림): 가까이 볼 자세한 것과 멀리까지 덮는 넓은 것 */
    fun setGrids(fine: EncodedGrid, coarse: EncodedGrid)

    fun setUniforms(u: TerrainUniforms)

    fun brush(): Brush?

    fun release()
}

/** 카메라·지형 값 (ViewCamera 와 같은 원근) */
class TerrainUniforms {
    val eye = FloatArray(3)
    val f = FloatArray(3)
    val r = FloatArray(3)
    val u = FloatArray(3)
    var focal = 1f
    var cx = 0f
    var cy = 0f
    /** 자세한 지형 [0] 과 넓은 지형 [1] 의 시작점·간격·칸 수·높이 범위 */
    val ox = FloatArray(2)
    val oy = FloatArray(2)
    val step = FloatArray(2)
    val nx = FloatArray(2)
    val ny = FloatArray(2)
    val hMin = FloatArray(2)
    val hRange = FloatArray(2)
    var zScale = 1f
    var z0 = 0f

    fun set(k: Int, g: com.sssoft.chalrang.core.TerrainGrid, e: EncodedGrid) {
        ox[k] = g.x0.toFloat(); oy[k] = g.y0.toFloat(); step[k] = g.step.toFloat()
        nx[k] = g.nx.toFloat(); ny[k] = g.ny.toFloat()
        hMin[k] = e.hMin.toFloat(); hRange[k] = e.hRange.toFloat()
    }
    /** 이 거리(m) 쯤부터 안개로 흐려진다 */
    var far = 10_000f
}

/** 격자 높이 → 그림 (R·G = 16비트, 가장 낮은 곳 0 ~ 가장 높은 곳 65535) 과 그 범위 */
class EncodedGrid(val argb: IntArray, val w: Int, val h: Int, val hMin: Double, val hRange: Double) {
    companion object {
        fun of(g: TerrainGrid): EncodedGrid {
            val lo = g.h.min()
            val range = (g.h.max() - lo).coerceAtLeast(1.0)
            val out = IntArray(g.nx * g.ny)
            // 그림은 위쪽이 첫 줄: 격자 j 를 그대로 줄로 (셰이더도 같은 방향으로 읽는다)
            for (k in out.indices) {
                val v = ((g.h[k] - lo) / range * 65535).toInt().coerceIn(0, 65535)
                out[k] = (0xFF shl 24) or ((v shr 8) shl 16) or ((v and 0xFF) shl 8)
            }
            return EncodedGrid(out, g.nx, g.ny, lo, range)
        }
    }
}

internal val TERRAIN_SKSL: String by lazy {
    """
uniform shader fine;
uniform shader coarse;
uniform float3 uEye;
uniform float3 uF;
uniform float3 uR;
uniform float3 uU;
uniform float uFocal;
uniform float2 uC;
uniform float2 uOrigin;
uniform float uStep;
uniform float2 uSize;
uniform float uHMin;
uniform float uHRange;
uniform float2 uOrigin2;
uniform float uStep2;
uniform float2 uSize2;
uniform float uHMin2;
uniform float uHRange2;
uniform float uZScale;
uniform float uZ0;
uniform float uFar;

float dec(half4 c) { return (float(c.r) * 255.0 * 256.0 + float(c.g) * 255.0) / 65535.0; }

float hFine(float2 xy) {
    float2 t = (xy - uOrigin) / uStep + 0.5;
    return uHMin + dec(fine.eval(clamp(t, float2(0.5), uSize - 0.5))) * uHRange;
}

float hCoarse(float2 xy) {
    float2 t = (xy - uOrigin2) / uStep2 + 0.5;
    return uHMin2 + dec(coarse.eval(clamp(t, float2(0.5), uSize2 - 0.5))) * uHRange2;
}

// 자세한 지형이 덮는 정도 (안쪽 1, 가장자리 10칸에 걸쳐 넓은 지형으로 섞임)
float fineW(float2 xy) {
    float2 d = min(xy - uOrigin, uOrigin + (uSize - 1.0) * uStep - xy);
    return smoothstep(0.0, uStep * 10.0, min(d.x, d.y));
}

// 실제 높이 (m): 가까이는 자세한 것, 바깥은 넓은 것
float hRaw(float2 xy) {
    float c = hCoarse(xy);
    float w = fineW(xy);
    if (w <= 0.0) return c;
    return mix(c, hFine(xy), w);
}

// 그리는 높이 (출발점 0, 키운 만큼)
float hW(float2 xy) { return (hRaw(xy) - uZ0) * uZScale; }

// 높이별 색: 깊은 청록 골짜기 → 푸른 비탈 → 청록빛 능선 → 하얀 봉우리
float3 hypso(float v) {
    float3 c0 = float3(0.039, 0.165, 0.235);
    float3 c1 = float3(0.059, 0.290, 0.369);
    float3 c2 = float3(0.110, 0.431, 0.494);
    float3 c3 = float3(0.235, 0.604, 0.651);
    float3 c4 = float3(0.549, 0.792, 0.831);
    float3 c5 = float3(0.902, 0.965, 0.980);
    if (v < 0.3) return mix(c0, c1, v / 0.3);
    if (v < 0.55) return mix(c1, c2, (v - 0.3) / 0.25);
    if (v < 0.75) return mix(c2, c3, (v - 0.55) / 0.2);
    if (v < 0.9) return mix(c3, c4, (v - 0.75) / 0.15);
    return mix(c4, c5, (v - 0.9) / 0.1);
}

half4 main(float2 fc) {
    float3 d = normalize(uF * uFocal + uR * (fc.x - uC.x) - uU * (fc.y - uC.y));
    // 넓은 지형 상자와 만나는 구간
    float2 lo = uOrigin2;
    float2 hi = uOrigin2 + (uSize2 - 1.0) * uStep2;
    float zLo = (min(uHMin, uHMin2) - uZ0) * uZScale - 1.0;
    float zHi = (max(uHMin + uHRange, uHMin2 + uHRange2) - uZ0) * uZScale + 1.0;
    float3 bmin = float3(lo, zLo);
    float3 bmax = float3(hi, zHi);
    float3 inv = 1.0 / (abs(d) + 1e-6) * sign(d + 1e-9);
    float3 t0 = (bmin - uEye) * inv;
    float3 t1 = (bmax - uEye) * inv;
    float3 tn = min(t0, t1);
    float3 tf = max(t0, t1);
    float tNear = max(max(tn.x, tn.y), max(tn.z, 1.0));
    float tFar = min(min(tf.x, tf.y), tf.z);
    if (tFar <= tNear) return half4(0.0);

    // 앞으로 나아가며 (멀수록 크게) 땅 아래로 들어가는 곳을 찾고 반으로 좁힌다
    float t = tNear;
    float prev = t;
    bool hit = false;
    bool side = false;
    // 걸음: 가까이는 1.5m, 멀수록 거리의 3.5% (192걸음이면 10km 넘게 간다 — 1인칭에서도 먼 땅까지)
    for (int i = 0; i < 192; i++) {
        float3 p = uEye + d * t;
        if (p.z < hW(p.xy)) { hit = true; side = (i == 0); break; }
        prev = t;
        t += max(1.5, t * 0.035);
        if (t > tFar) break;
    }
    // 상자 옆면으로 바로 들어온 것은 땅이 아니라 벽: 그리지 않는다
    if (!hit || side) return half4(0.0);
    float a = prev;
    float b = t;
    for (int k = 0; k < 8; k++) {
        float m = (a + b) * 0.5;
        float3 q = uEye + d * m;
        if (q.z < hW(q.xy)) b = m; else a = m;
    }
    float3 p = uEye + d * b;

    // 매끄러운 그림자: 높이 기울기로 법선, 북서쪽 위 빛
    float e = mix(uStep2, uStep, fineW(p.xy)) * 0.6;
    float hx = hW(p.xy + float2(e, 0.0)) - hW(p.xy - float2(e, 0.0));
    float hy = hW(p.xy + float2(0.0, e)) - hW(p.xy - float2(0.0, e));
    float3 n = normalize(float3(-hx, -hy, 2.0 * e));
    float3 L = normalize(float3(-0.55, 0.45, 0.7));
    float lam = max(dot(n, L), 0.0);
    float h = hRaw(p.xy);
    float v = clamp((h - uHMin2) / uHRange2, 0.0, 1.0);
    float3 col = hypso(v) * (0.32 + 0.85 * lam);
    // 등고선 50m (지도 느낌의 옅은 네온), 가까울수록 또렷
    float c = fract(h / 50.0);
    float w = 0.035 + b / uFar * 0.05;
    float line = smoothstep(w, 0.0, min(c, 1.0 - c));
    col += float3(0.30, 0.78, 0.96) * line * 0.35;
    // 가까이 볼 때: 10m 잔 등고선과 100m 네온 격자 (땅이 밋밋해 보이지 않고 달리는 느낌이 나게)
    float near = 1.0 - smoothstep(600.0, 2200.0, b);
    float c10 = fract(h / 10.0);
    // 완만한 비탈에서는 선이 넓게 번지므로 기울기만큼 가늘게
    float slope = length(float2(hx, hy)) / (2.0 * e * uZScale) + 0.02;
    float line10 = smoothstep(clamp(0.02 / slope, 0.01, 0.08), 0.0, min(c10, 1.0 - c10));
    float2 g = fract(p.xy / 100.0);
    float gridL = smoothstep(0.006, 0.0, min(min(g.x, 1.0 - g.x), min(g.y, 1.0 - g.y)));
    col += float3(0.30, 0.78, 0.96) * near * (line10 * 0.08 + gridL * 0.05);
    // 거리 안개 (배경 남색으로)
    float fog = smoothstep(uFar * 0.35, uFar, b);
    col = mix(col, float3(0.035, 0.075, 0.145), fog);
    // 가장자리는 부드럽게 사라지게 (지형 상자의 끝이 보이지 않게)
    float2 edge = min(p.xy - lo, hi - p.xy);
    float alpha = smoothstep(0.0, uStep2 * 8.0, min(edge.x, edge.y)) * (1.0 - fog * 0.6);
    return half4(half3(col) * half(alpha), half(alpha));
}
"""
}
