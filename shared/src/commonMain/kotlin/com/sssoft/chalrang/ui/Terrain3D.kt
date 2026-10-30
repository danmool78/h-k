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

    /** 지형을 바꿀 때만 (그림을 새로 올림) */
    fun setGrid(argb: IntArray, w: Int, h: Int)

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
    var ox = 0f
    var oy = 0f
    var step = 1f
    var nx = 1f
    var ny = 1f
    var zScale = 1f
    var z0 = 0f
    var hMin = 0f
    var hRange = 1f
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
uniform shader heights;
uniform float3 uEye;
uniform float3 uF;
uniform float3 uR;
uniform float3 uU;
uniform float uFocal;
uniform float2 uC;
uniform float2 uOrigin;
uniform float uStep;
uniform float2 uSize;
uniform float uZScale;
uniform float uZ0;
uniform float uHMin;
uniform float uHRange;
uniform float uFar;

// 실제 높이 (m)
float hRaw(float2 xy) {
    float2 t = (xy - uOrigin) / uStep + 0.5;
    half4 c = heights.eval(clamp(t, float2(0.5), uSize - 0.5));
    return uHMin + (float(c.r) * 255.0 * 256.0 + float(c.g) * 255.0) / 65535.0 * uHRange;
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
    // 지형 상자와 만나는 구간
    float2 lo = uOrigin;
    float2 hi = uOrigin + (uSize - 1.0) * uStep;
    float zLo = (uHMin - uZ0) * uZScale - 1.0;
    float zHi = (uHMin + uHRange - uZ0) * uZScale + 1.0;
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
    for (int i = 0; i < 128; i++) {
        float3 p = uEye + d * t;
        if (p.z < hW(p.xy)) { hit = true; break; }
        prev = t;
        t += max(2.0, t * 0.006);
        if (t > tFar) break;
    }
    if (!hit) return half4(0.0);
    float a = prev;
    float b = t;
    for (int k = 0; k < 6; k++) {
        float m = (a + b) * 0.5;
        float3 q = uEye + d * m;
        if (q.z < hW(q.xy)) b = m; else a = m;
    }
    float3 p = uEye + d * b;

    // 매끄러운 그림자: 높이 기울기로 법선, 북서쪽 위 빛
    float e = uStep * 0.6;
    float hx = hW(p.xy + float2(e, 0.0)) - hW(p.xy - float2(e, 0.0));
    float hy = hW(p.xy + float2(0.0, e)) - hW(p.xy - float2(0.0, e));
    float3 n = normalize(float3(-hx, -hy, 2.0 * e));
    float3 L = normalize(float3(-0.55, 0.45, 0.7));
    float lam = max(dot(n, L), 0.0);
    float h = hRaw(p.xy);
    float v = clamp((h - uHMin) / uHRange, 0.0, 1.0);
    float3 col = hypso(v) * (0.32 + 0.85 * lam);
    // 등고선 50m (지도 느낌의 옅은 네온), 가까울수록 또렷
    float c = fract(h / 50.0);
    float w = 0.035 + b / uFar * 0.05;
    float line = smoothstep(w, 0.0, min(c, 1.0 - c));
    col += float3(0.30, 0.78, 0.96) * line * 0.35;
    // 거리 안개 (배경 남색으로)
    float fog = smoothstep(uFar * 0.35, uFar, b);
    col = mix(col, float3(0.035, 0.075, 0.145), fog);
    return half4(half3(col), 1.0);
}
"""
}
