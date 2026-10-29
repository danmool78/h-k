package com.sssoft.chalrang.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import com.sssoft.chalrang.core.WaterCup
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private val Water = Color(0xFF4FC3F7)
private val WaterWarn = Color(0xFFFFB74D)
private val WaterDanger = Color(0xFFFF7043)
private val GlassEdge = Color(0xE6FFFFFF)

/**
 * 3D 물컵 (차 뒤에서 앞을 보며 위에서 약간 내려다본 모습).
 *
 * 컵은 원뿔대(아래 반지름 [RB], 위 [RT], 높이 [H]), 물은 기울어진 평면 수면으로 자른 부피다.
 * 수면 기울기 = 물컵 모델의 기울기(g) × [k], k 는 모델에서 넘치는 순간 수면이 정확히 컵 가장자리에 닿도록 정한다
 * (WaterCup: 여유 = (1-물 높이)×0.5g, 여기: 가장자리 높이 차 = 반지름×기울기×k → k = 2H/RT).
 * 수면과 컵 벽이 만나는 선은 원뿔 벽 위에서 정확히 풀고, 넘친 물방울은 포물선으로 떨어진다.
 *
 * 앞뒤 기울기: 브레이크를 밟으면(+) 물이 앞(화면 안쪽)으로, 좌우 기울기: 왼쪽으로 돌면(-) 물이 오른쪽으로 쏠린다.
 */
@Composable
fun CupView(
    tiltX: Double,
    tiltY: Double,
    level: Double,
    dose: Double,
    spilling: Boolean,
    modifier: Modifier = Modifier,
) {
    val puddle by animateFloatAsState((dose / 1.4).coerceIn(0.0, 1.0).toFloat(), label = "puddle")
    val shownLevel by animateFloatAsState(level.toFloat(), label = "level")
    val state by rememberUpdatedState(Triple(tiltX, tiltY, spilling))
    val drops = remember { Drops() }
    var time by remember { mutableFloatStateOf(0f) }

    // 프레임마다 물방울을 움직이고, 넘치는 중이면 가장자리에서 새 물방울을 낸다
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                time += dt
                val (tx, ty, sp) = state
                drops.step(dt, if (sp) slope(tx, ty) else null, shownLevel)
            }
        }
    }

    Canvas(modifier.clipToBounds()) {
        val cam = Camera(size.width, size.height)
        val c = waterColor(dose)
        val (px, py) = slope(tiltX, tiltY)
        val h0 = shownLevel * H
        val ripple = 0.012f * sin(time * 2.4f)

        drawPuddle(cam, c, puddle)
        drawGlassBack(cam)

        // 수면이 벽과 만나는 높이 (원뿔 벽 위에서 풀기)
        val n = 96
        val surf = Array(n + 1) { i ->
            val th = 2 * PI * i / n
            val g = px * cos(th) + py * sin(th)
            val z = ((h0 + RB * g) / (1 - CONE * g)).coerceIn(0.0, H.toDouble()) + ripple * sin(th * 3 + time * 2)
            val r = radiusAt(z)
            P3(r * cos(th), r * sin(th), z)
        }
        // 뒤쪽 물 (벽 안쪽에서 보이는 부분)
        drawWaterSide(cam, surf, back = true, color = c)
        // 수면
        val top = Path().apply {
            surf.forEachIndexed { i, p -> val o = cam.project(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
            close()
        }
        val hiA = cam.project(P3(-0.6 * RT, 0.4 * RT, h0.toDouble()))
        val hiB = cam.project(P3(0.6 * RT, -0.4 * RT, h0.toDouble()))
        drawPath(top, Brush.linearGradient(listOf(lerp(c, Color.White, 0.55f), lerp(c, Color.White, 0.15f)), hiA, hiB), alpha = 0.95f)
        drawPath(top, Color.White.copy(alpha = 0.35f), style = Stroke(width = cam.unit * 0.012f))
        // 앞쪽 물 (유리 너머로 보이는 몸통)
        drawWaterSide(cam, surf, back = false, color = c)

        drawGlassFront(cam)
        drops.draw(this, cam, c)
    }
}

/** 물 색: 주의(누적 0.6) 전까지 파랑, 주의부터 주황 → 위험(1.0) 빨강. 파랑과 주황을 섞으면 탁해져 섞지 않는다 */
private fun waterColor(dose: Double): Color = when {
    dose >= 1.0 -> WaterDanger
    dose >= 0.6 -> lerp(WaterWarn, WaterDanger, ((dose - 0.6) / 0.4).toFloat())
    else -> Water
}

// ---- 기하 ----

private const val RB = 0.74f
private const val RT = 1.0f
private const val H = 2.1f
private const val CONE = (RT - RB) / H

/** 모델 기울기(g) → 수면 경사 (dz/dx, dz/dy). x 오른쪽, y 앞(화면 안쪽) */
private fun slope(tiltX: Double, tiltY: Double): Pair<Double, Double> {
    val k = 2.0 * H / RT * (WaterCup.MAX_FREEBOARD / 0.5)
    return (-tiltY * k) to (tiltX * k)
}

private fun radiusAt(z: Double): Double = RB + CONE * z

private data class P3(val x: Double, val y: Double, val z: Double)

/** 뒤에서 앞을 보며 [PITCH] 만큼 내려다보는 원근 카메라 */
private class Camera(w: Float, h: Float) {
    val unit = min(w / 3.2f, h / 3.3f)
    private val cx = w / 2
    private val cy = h * 0.53f
    fun project(p: P3): Offset {
        val up = p.z * cos(PITCH) + p.y * sin(PITCH)
        val depth = p.y * cos(PITCH) - p.z * sin(PITCH) + DIST
        val s = DIST / depth
        return Offset(cx + (p.x * s * unit).toFloat(), cy - ((up - H * 0.45) * s * unit).toFloat())
    }

    companion object {
        val PITCH = 24.0 * PI / 180
        const val DIST = 8.0
    }
}

private fun ring(cam: Camera, r: Double, z: Double, from: Double = 0.0, to: Double = 2 * PI, n: Int = 72): List<Offset> =
    (0..n).map { i -> val th = from + (to - from) * i / n; cam.project(P3(r * cos(th), r * sin(th), z)) }

private fun Path.addPoly(points: List<Offset>, close: Boolean = true) {
    points.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
    if (close) close()
}

private fun DrawScope.drawPuddle(cam: Camera, c: Color, puddle: Float) {
    // 컵 그림자
    drawPath(Path().apply { addPoly(ring(cam, RB * 1.15, 0.0)) }, Color.Black.copy(alpha = 0.35f))
    if (puddle > 0.005f) {
        val r = RB * (1.2 + 0.9 * puddle)
        drawPath(Path().apply { addPoly(ring(cam, r, 0.0)) }, c.copy(alpha = 0.35f))
    }
}

private fun DrawScope.drawGlassBack(cam: Camera) {
    val back = Path().apply {
        addPoly(ring(cam, RB.toDouble(), 0.0, 0.0, PI, 36) + ring(cam, RT.toDouble(), H.toDouble(), PI, 0.0, 36))
    }
    drawPath(back, Color.White.copy(alpha = 0.05f))
    drawPath(Path().apply { addPoly(ring(cam, RT.toDouble(), H.toDouble(), 0.0, PI, 36), close = false) }, GlassEdge.copy(alpha = 0.35f), style = Stroke(cam.unit * 0.012f))
}

/** 물 몸통: 바닥 원호와 수면선 사이 (뒤쪽 반 또는 앞쪽 반) */
private fun DrawScope.drawWaterSide(cam: Camera, surf: Array<P3>, back: Boolean, color: Color) {
    val n = surf.size - 1
    val idx = if (back) (0..n / 2) else (n / 2..n)
    val bottom = idx.map { i -> val th = 2 * PI * i / n; cam.project(P3(RB * cos(th), RB * sin(th), 0.0)) }
    val top = idx.reversed().map { cam.project(surf[it]) }
    val path = Path().apply { addPoly(bottom + top) }
    val yTop = top.minOf { it.y }
    val yBot = bottom.maxOf { it.y }
    if (back) {
        drawPath(path, lerp(color, Color.Black, 0.45f), alpha = 0.9f)
    } else {
        drawPath(path, Brush.verticalGradient(listOf(color.copy(alpha = 0.78f), lerp(color, Color.Black, 0.35f).copy(alpha = 0.9f)), yTop, yBot))
    }
}

private fun DrawScope.drawGlassFront(cam: Camera) {
    val w = cam.unit * 0.022f
    // 바닥 두께
    drawPath(Path().apply { addPoly(ring(cam, RB.toDouble(), 0.0, PI, 2 * PI, 36) + ring(cam, RB * 0.97, 0.08, 2 * PI, PI, 36)) }, Color.White.copy(alpha = 0.18f))
    drawPath(Path().apply { addPoly(ring(cam, RB.toDouble(), 0.0, PI, 2 * PI, 36), close = false) }, GlassEdge, style = Stroke(w))
    // 옆 윤곽 (보이는 가장자리: 좌우 끝)
    for (th in listOf(0.0, PI)) {
        drawLine(GlassEdge, cam.project(P3(RB * cos(th), 0.0, 0.0)), cam.project(P3(RT * cos(th), 0.0, H.toDouble())), strokeWidth = w)
    }
    // 반사광: 앞쪽 왼편 세로 띠
    val a0 = PI * 1.18
    val a1 = PI * 1.26
    val band = ring(cam, RB * 1.001, 0.15, a0, a1, 6) + ring(cam, RT * 1.001, H - 0.12, a1, a0, 6)
    drawPath(Path().apply { addPoly(band) }, Color.White.copy(alpha = 0.22f))
    // 가장자리 (앞쪽은 두껍게)
    drawPath(Path().apply { addPoly(ring(cam, RT.toDouble(), H.toDouble(), PI, 2 * PI, 36), close = false) }, GlassEdge, style = Stroke(w * 1.3f))
}

// ---- 넘친 물방울 ----

private class Drop(var x: Double, var y: Double, var z: Double, var vx: Double, var vy: Double, var vz: Double, var life: Double)

private class Drops {
    private val list = ArrayList<Drop>()
    private var spawnAcc = 0.0

    /** [slope] 가 있으면 넘치는 중: 수면이 가장 높은 쪽 가장자리에서 물방울을 낸다 */
    fun step(dt: Float, slope: Pair<Double, Double>?, level: Float) {
        if (slope != null) {
            spawnAcc += dt * 40
            val (px, py) = slope
            val m = hypot(px, py)
            if (m > 1e-6) {
                val dirX = px / m
                val dirY = py / m
                while (spawnAcc >= 1) {
                    spawnAcc -= 1
                    // 가장 높은 쪽 ± 40도 안에서
                    val th = kotlin.math.atan2(dirY, dirX) + (Random.nextDouble() - 0.5) * 1.4
                    val out = 0.5 + Random.nextDouble() * 0.6
                    list += Drop(
                        RT * cos(th), RT * sin(th), H.toDouble(),
                        cos(th) * out, sin(th) * out, 0.4 + Random.nextDouble() * 0.5, 1.4,
                    )
                }
            }
        } else {
            spawnAcc = 0.0
        }
        val it = list.iterator()
        while (it.hasNext()) {
            val d = it.next()
            d.vz -= 6.0 * dt
            d.x += d.vx * dt; d.y += d.vy * dt; d.z += d.vz * dt
            d.life -= dt
            if (d.z <= 0 || d.life <= 0) it.remove()
        }
        if (list.size > 300) list.subList(0, list.size - 300).clear()
    }

    fun draw(scope: DrawScope, cam: Camera, c: Color) {
        for (d in list) {
            val o = cam.project(P3(d.x, d.y, max(d.z, 0.0)))
            scope.drawCircle(c, radius = cam.unit * (0.018f + 0.012f * (d.life / 1.4).toFloat()), center = o, alpha = (d.life / 1.4).toFloat().coerceIn(0.2f, 1f))
        }
    }
}
