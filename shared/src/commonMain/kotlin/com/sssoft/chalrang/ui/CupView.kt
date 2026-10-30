package com.sssoft.chalrang.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import com.sssoft.chalrang.core.WaterCup
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
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
 * (WaterCup: 넘치는 기울기 = (1-물 높이)×MAX_FREEBOARD, 여기: 가장자리 높이 차 = 반지름×기울기×k → k = H/(RT×MAX_FREEBOARD)).
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
    /** 컵이 놓인 바닥(빛나는 받침 고리)도 같은 3D 공간에 그린다 */
    stage: Boolean = false,
    /** 위아래 출렁임 (g, + 위로): 3D 물이 눌리고 튄다 */
    heave: Double = 0.0,
    /** 넘치기 시작하는 기울기 (g). 기본은 물 높이에서 */
    freeboard: Double = WaterCup.freeboardOf(level),
) {
    val puddle by animateFloatAsState((dose / 1.4).coerceIn(0.0, 1.0).toFloat(), label = "puddle")
    val shownLevel by animateFloatAsState(level.toFloat(), label = "level")
    val state by rememberUpdatedState(Triple(tiltX, tiltY, spilling))
    val heaveNow by rememberUpdatedState(heave)
    val fbNow by rememberUpdatedState(freeboard)
    val drops = remember { Drops() }
    var time by remember { mutableFloatStateOf(0f) }
    // 3D 물: GPU 셰이더 + 물결 시뮬레이션 (안 되면 2D 그림)
    val water = remember { WaterShader() }
    val sim = remember { WaterSim(RTI.toDouble()) }
    val uniforms = remember { WaterUniforms() }
    DisposableEffect(Unit) { onDispose { water.release() } }

    // 프레임마다 물방울을 움직이고, 넘치는 중이면 가장자리에서 새 물방울을 낸다
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                time += dt
                val (tx, ty, sp) = state
                drops.step(dt, if (sp) slope(tx, ty, BASE + shownLevel * (H - BASE), fbNow) else null, shownLevel)
                if (water.supported && dt > 0) {
                    val h0 = BASE + shownLevel * (H - BASE)
                    val (px, py) = realSlope(tx, ty)
                    sim.step(dt.toDouble(), px, py, innerRadiusAt(h0) * 0.97, sp, heaveNow)
                }
            }
        }
    }

    // 매 프레임 새로 만들지 않고 재사용하는 경로·좌표 (메모리)
    val pen = remember { Pen() }

    Canvas(modifier.clipToBounds()) {
        val cam = Camera(size.width, size.height)
        val c = waterColor(dose)
        val h0 = BASE + shownLevel * (H - BASE)
        val (px, py) = limitedSlope(tiltX, tiltY, h0, freeboard)
        val ripple = 0.012f * sin(time * 2.4f)

        drawFloor(cam, pen, c, puddle, stage)
        // GPU 3D 물: 유리·물·빛을 화소마다 광선으로 계산
        if (water.supported) {
            uniforms.width = size.width; uniforms.height = size.height
            uniforms.unit = cam.unit; uniforms.cx = cam.cx; uniforms.cy = cam.cy
            val (rx, ry) = realSlope(tiltX, tiltY)
            uniforms.h0 = h0.toFloat(); uniforms.slopeX = rx.toFloat(); uniforms.slopeY = ry.toFloat()
            uniforms.time = time
            uniforms.agitation = sim.agitation.toFloat()
            uniforms.r = c.red; uniforms.g = c.green; uniforms.b = c.blue
            water.setUniforms(uniforms)
            water.setHeights(sim.encode(), WaterSim.N)
            val brush = water.brush()
            if (brush != null) {
                // 셰이더는 컵이 있는 영역만 (나머지 화소는 계산하지 않는다)
                val left = cam.project(-RT * 1.06, 0.0, H.toDouble()).x
                val right = cam.project(RT * 1.06, 0.0, H.toDouble()).x
                val top = cam.project(0.0, RT.toDouble(), H.toDouble()).y - cam.unit * 0.06f
                val bottom = cam.project(0.0, -RB.toDouble(), 0.0).y + cam.unit * 0.03f
                drawRect(brush, topLeft = Offset(left, top), size = Size(right - left, bottom - top))
                drops.draw(this, cam, c)
                return@Canvas
            }
        }
        drawGlassBack(cam, pen)

        // 수면이 벽과 만나는 높이 (원뿔 벽 위에서 풀기)
        var zMin = Double.MAX_VALUE
        var zMax = -Double.MAX_VALUE
        for (i in 0..Pen.N) {
            val th = 2 * PI * i / Pen.N
            val g = px * cos(th) + py * sin(th)
            val z = ((h0 + RBI * g) / (1 - CONE * g)).coerceIn(BASE, H.toDouble()) + ripple * sin(th * 3 + time * 2)
            val r = innerRadiusAt(z)
            pen.sx[i] = r * cos(th); pen.sy[i] = r * sin(th); pen.sz[i] = z
            if (z < zMin) zMin = z
            if (z > zMax) zMax = z
        }
        // 뒤쪽 물 (벽 안쪽에서 보이는 부분)
        drawWaterSide(cam, pen, back = true, color = c)
        // 수면
        val top = pen.a.apply {
            reset()
            for (i in 0..Pen.N) {
                val o = cam.project(pen.sx[i], pen.sy[i], pen.sz[i])
                if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
            }
            close()
        }
        val hiA = cam.project(-0.6 * RT, 0.4 * RT, h0)
        val hiB = cam.project(0.6 * RT, -0.4 * RT, h0)
        // 잔잔하면 위에서 빛을 받아 밝고, 가파르게 서면 몸통 색에 가까운 반투명 (빛나는 가장자리만)
        val steep = (hypot(px, py) / 1.2).coerceIn(0.0, 1.0).toFloat()
        val light = 0.5f - 0.42f * steep
        drawPath(top, Brush.linearGradient(listOf(lerp(c, Color.White, light), lerp(c, Color.White, light * 0.3f)), hiA, hiB), alpha = 0.92f - 0.35f * steep)
        drawPath(top, Color.White.copy(alpha = 0.6f * (1 - 0.7f * steep)), style = Stroke(width = cam.unit * 0.012f))
        // 같은 높이를 잇는 물결선: 수면이 어느 쪽으로 얼마나 기울었는지 보인다
        val m = hypot(px, py)
        if (m > 0.05) {
            val dx = px / m
            val dy = py / m
            for (k in 1..5) {
                val zk = zMin + (zMax - zMin) * k / 6
                val d = (zk - h0) / m
                val r = innerRadiusAt(zk) * 0.98
                if (r <= abs(d)) continue
                val half = sqrt(r * r - d * d)
                val a = cam.project(dx * d - dy * half, dy * d + dx * half, zk)
                val b = cam.project(dx * d + dy * half, dy * d - dx * half, zk)
                drawLine(Color.White.copy(alpha = 0.10f + 0.12f * steep), a, b, strokeWidth = cam.unit * 0.008f)
            }
        }
        // 앞쪽 물 (유리 너머로 보이는 몸통)
        drawWaterSide(cam, pen, back = false, color = c)

        drawGlassFront(cam, pen)
        drops.draw(this, cam, c)
    }
}

/** 그리기용 재사용 객체: 경로 2개와 수면선 좌표 */
private class Pen {
    val a = Path()
    val b = Path()
    val sx = DoubleArray(N + 1)
    val sy = DoubleArray(N + 1)
    val sz = DoubleArray(N + 1)

    companion object {
        const val N = 96
    }
}

/** 물 색: 주의(누적 0.6) 전까지 파랑, 주의부터 주황 → 위험(1.0) 빨강. 파랑과 주황을 섞으면 탁해져 섞지 않는다 */
private fun waterColor(dose: Double): Color = when {
    dose >= 1.0 -> WaterDanger
    dose >= 0.6 -> lerp(WaterWarn, WaterDanger, ((dose - 0.6) / 0.4).toFloat())
    else -> Water
}

// ---- 기하 ----

/** 컵 치수·카메라 (3D 셰이더 Water3D 와 같게 쓴다) */
internal object CupGeometry {
    const val RB = 0.74f
    const val RT = 1.0f
    const val H = 2.1f
    /** 유리 바닥 두께, 벽 두께 */
    const val BASE = 0.16
    const val WALL = 0.035
    val PITCH = 24.0 * PI / 180
    const val DIST = 8.0
}

private const val RB = CupGeometry.RB
private const val RT = CupGeometry.RT
private const val H = CupGeometry.H
private const val CONE = (RT - RB) / H
private const val BASE = CupGeometry.BASE
private const val WALL = CupGeometry.WALL
/** 안쪽(물이 닿는) 반지름: 바닥·가장자리 */
private const val RBI = RB - WALL
private const val RTI = RT - WALL

/**
 * 3D 물의 수면 경사: 실제 컵의 물처럼 덜 기울인다 (2D 그림은 넘침을 보이려고 가장자리까지 키운다).
 * 넘치기 시작하는 기울기에서 약 15도, 아무리 세도 약 27도까지. 넘침은 물방울·물결로 보여 준다.
 */
private fun realSlope(tiltX: Double, tiltY: Double): Pair<Double, Double> {
    val sx = -tiltY / WaterCup.MAX_FREEBOARD * 0.27
    val sy = tiltX / WaterCup.MAX_FREEBOARD * 0.27
    val m = hypot(sx, sy)
    val cap = 0.5 * kotlin.math.tanh(m / 0.5)
    return if (m > 1e-9) (sx * cap / m) to (sy * cap / m) else 0.0 to 0.0
}

/** 넘친 뒤에는 수면이 가장자리 가까이에서 멈춘다 (그 이상은 물방울로 보여 준다) */
private fun limitedSlope(tiltX: Double, tiltY: Double, h0: Double, freeboard: Double): Pair<Double, Double> {
    val (sx, sy) = slope(tiltX, tiltY, h0, freeboard)
    val m = hypot(sx, sy)
    val limit = (H - h0) / RTI * 0.85
    return if (m > limit) (sx * limit / m) to (sy * limit / m) else sx to sy
}

/** 모델 기울기(g) → 수면 경사 (dz/dx, dz/dy). x 오른쪽, y 앞(화면 안쪽) */
private fun slope(tiltX: Double, tiltY: Double, h0: Double, freeboard: Double): Pair<Double, Double> {
    // 넘치는 기울기 [freeboard] 에서 수면이 가장자리(남은 높이 H - h0)에 닿도록
    val k = (H - h0) / (RTI * freeboard.coerceAtLeast(0.01))
    return (-tiltY * k) to (tiltX * k)
}

private fun radiusAt(z: Double): Double = RB + CONE * z
private fun innerRadiusAt(z: Double): Double = RBI + CONE * z

private data class P3(val x: Double, val y: Double, val z: Double)

/** 뒤에서 앞을 보며 [PITCH] 만큼 내려다보는 원근 카메라 */
private class Camera(w: Float, h: Float) {
    /** 컵 가장자리부터 바닥 고리·물웅덩이 끝까지가 화면 안에 들어오도록 크기와 중심을 맞춘다 */
    val unit: Float
    val cx: Float
    val cy: Float

    init {
        val (minX, maxX, minY, maxY) = BOUNDS
        unit = min(w * FILL / (maxX - minX), h * FILL / (maxY - minY)).toFloat()
        cx = (w / 2 - unit * (minX + maxX) / 2).toFloat()
        cy = (h / 2 - unit * (minY + maxY) / 2).toFloat()
    }

    /** 3D 점 → 화면 (객체를 만들지 않는다) */
    fun project(x: Double, y: Double, z: Double): Offset {
        val up = z * cos(PITCH) + y * sin(PITCH)
        val depth = y * cos(PITCH) - z * sin(PITCH) + DIST
        val s = DIST / depth
        return Offset(cx + (x * s * unit).toFloat(), cy - (up * s * unit).toFloat())
    }

    companion object {
        val PITCH = CupGeometry.PITCH
        const val DIST = CupGeometry.DIST
        const val FILL = 0.94

        /** 크기 1, 중심 0 일 때의 화면 좌표 (아래가 +) */
        fun raw(p: P3): Pair<Double, Double> {
            val up = p.z * cos(PITCH) + p.y * sin(PITCH)
            val depth = p.y * cos(PITCH) - p.z * sin(PITCH) + DIST
            val s = DIST / depth
            return p.x * s to -up * s
        }

        /** 가장자리(z = H)와 바닥에 그리는 것 중 가장 큰 원(z = 0)의 화면 범위 */
        val BOUNDS: List<Double> by lazy {
            val pts = (0 until 72).flatMap { i ->
                val th = 2 * PI * i / 72
                listOf(
                    raw(P3(RT * cos(th), RT * sin(th), H + 0.15)),
                    raw(P3(FLOOR_R * cos(th), FLOOR_R * sin(th), 0.0)),
                )
            }
            listOf(pts.minOf { it.first }, pts.maxOf { it.first }, pts.minOf { it.second }, pts.maxOf { it.second })
        }
    }
}

// 바닥 그리기 값 (반지름 배율 to 진하기)
private val FLOOR_GLOW = listOf(1.78 to 0.035f, 1.45 to 0.05f, 1.2 to 0.08f, 1.06 to 0.10f)
private val FLOOR_RINGS = listOf(1.1 to 0.75f, 1.2 to 0.45f, 1.34 to 0.3f, 1.45 to 0.5f, 1.72 to 0.22f)
/** 눈금 고리 (안쪽·바깥 반지름 배율), 눈금 수 */
private const val TICK_IN = 1.52
private const val TICK_OUT = 1.6
private const val TICKS = 90
private val StageCyan = Color(0xFF29C4F6)
private val CONTACT_SHADOW = listOf(1.12 to 0.12f, 1.05 to 0.18f, 1.0 to 0.25f)
private val PUDDLE_LAYERS = listOf(1.0 to 0.22f, 0.82 to 0.14f, 0.64 to 0.14f)

/** 바닥에 그리는 것(받침 고리·물웅덩이)의 가장 큰 반지름 */
private const val FLOOR_R = RB * 1.8

/** 높이 [z], 반지름 [r] 원의 [from]~[to] 호를 경로에 이어 그린다 ([start] 면 새로 시작) */
private fun Path.arc(cam: Camera, r: Double, z: Double, from: Double = 0.0, to: Double = 2 * PI, n: Int = 72, start: Boolean = true) {
    for (i in 0..n) {
        val th = from + (to - from) * i / n
        val o = cam.project(r * cos(th), r * sin(th), z)
        if (start && i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
    }
}

/** 재사용 경로에 원 하나 */
private fun Path.ellipse(cam: Camera, r: Double, z: Double = 0.0): Path {
    reset()
    arc(cam, r, z)
    close()
    return this
}

/**
 * 넘친 물웅덩이: 가장자리가 고르지 않은 얇은 물 막. 안쪽이 진하고, 가장자리에 빛이 맺히고, 뒤쪽에 반사광.
 */
private fun DrawScope.drawPuddle(cam: Camera, pen: Pen, c: Color, radius: Double) {
    fun blob(scale: Double) = pen.a.apply {
        reset()
        val n = 96
        for (i in 0..n) {
            val th = 2 * PI * i / n
            val r = radius * scale * (1 + 0.07 * sin(3 * th + 1.1) + 0.05 * sin(5 * th + 2.3) + 0.03 * sin(8 * th + 0.4))
            val o = cam.project(r * cos(th), r * sin(th), 0.0)
            if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
        }
        close()
    }
    for ((k, a) in PUDDLE_LAYERS) drawPath(blob(k), c.copy(alpha = a))
    drawPath(blob(1.0), Color.White.copy(alpha = 0.28f), style = Stroke(cam.unit * 0.008f))
    // 뒤쪽 반사광 (위에서 오는 빛)
    pen.b.reset()
    pen.b.arc(cam, radius * 0.86, 0.0, PI * 0.58, PI * 0.82, 12)
    drawPath(pen.b, Color.White.copy(alpha = 0.35f), style = Stroke(cam.unit * 0.014f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
}

/**
 * 컵이 놓인 바닥 (z = 0 평면): 빛나는 받침 고리, 접촉 그림자, 넘친 물웅덩이.
 * 컵·물과 같은 카메라로 그려 원근이 맞고 컵 바닥이 바닥에 붙는다.
 */
private fun DrawScope.drawFloor(cam: Camera, pen: Pen, c: Color, puddle: Float, stage: Boolean) {
    fun ellipse(r: Double) = pen.a.ellipse(cam, r)
    if (stage) {
        // 아래로 퍼지는 빛줄기 (받침 앞쪽에서 화면 아래로, 아래로 갈수록 옅다)
        val rayLen = cam.unit * 0.9f
        for (i in 0..8) {
            val th = PI * (1.18 + 0.08 * i)
            val o = cam.project(RB * 1.2 * cos(th), RB * 1.2 * sin(th), 0.0)
            val spread = (o.x - cam.cx) * 0.35f
            for (j in 0 until 4) {
                val t0 = j / 4f
                val t1 = (j + 1) / 4f
                drawLine(
                    StageCyan.copy(alpha = 0.16f * (1 - t0) * (if (i % 2 == 0) 1f else 0.55f)),
                    Offset(o.x + spread * t0, o.y + rayLen * t0), Offset(o.x + spread * t1, o.y + rayLen * t1),
                    strokeWidth = cam.unit * (0.03f - 0.006f * j),
                )
            }
        }
        // 바닥 빛 (안쪽이 밝다)
        for ((k, a) in FLOOR_GLOW) drawPath(ellipse(RB * k), StageCyan.copy(alpha = a))
        for ((k, a) in FLOOR_RINGS) {
            val e = ellipse(RB * k)
            drawPath(e, StageCyan.copy(alpha = a * 0.25f), style = Stroke(cam.unit * 0.05f))
            drawPath(e, StageCyan.copy(alpha = a), style = Stroke(cam.unit * 0.010f))
        }
        // 새겨진 방사형 눈금 (10칸마다 길고 밝게)
        for (i in 0 until TICKS) {
            val th = 2 * PI * i / TICKS
            val major = i % 10 == 0
            val r1 = RB * (if (major) TICK_OUT + 0.04 else TICK_OUT)
            val a = cam.project(RB * TICK_IN * cos(th), RB * TICK_IN * sin(th), 0.0)
            val b = cam.project(r1 * cos(th), r1 * sin(th), 0.0)
            drawLine(StageCyan.copy(alpha = if (major) 0.7f else 0.32f), a, b, strokeWidth = cam.unit * (if (major) 0.012f else 0.007f))
        }
        // 바깥 점선 고리: 짧은 호를 띄엄띄엄
        pen.b.reset()
        for (i in 0 until 48) {
            val from = 2 * PI * i / 48
            pen.b.arc(cam, RB * 1.79, 0.0, from, from + PI / 72, 3)
        }
        drawPath(pen.b, StageCyan.copy(alpha = 0.3f), style = Stroke(cam.unit * 0.008f))
    }
    if (puddle > 0.005f) drawPuddle(cam, pen, c, RB * (1.12 + 0.48 * puddle))
    // 접촉 그림자: 바닥에 가까울수록 진하다
    for ((k, a) in CONTACT_SHADOW) drawPath(ellipse(RB * k), Color.Black.copy(alpha = a))
}

private fun DrawScope.drawGlassBack(cam: Camera, pen: Pen) {
    val back = pen.a.apply {
        reset()
        arc(cam, RB.toDouble(), 0.0, 0.0, PI, 36)
        arc(cam, RT.toDouble(), H.toDouble(), PI, 0.0, 36, start = false)
        close()
    }
    drawPath(back, Color.White.copy(alpha = 0.05f))
    pen.b.reset()
    pen.b.arc(cam, RT.toDouble(), H.toDouble(), 0.0, PI, 36)
    drawPath(pen.b, GlassEdge.copy(alpha = 0.35f), style = Stroke(cam.unit * 0.012f))
}

/** 물 몸통: 유리 바닥 위 원호와 수면선 사이 (뒤쪽 반 또는 앞쪽 반) */
private fun DrawScope.drawWaterSide(cam: Camera, pen: Pen, back: Boolean, color: Color) {
    val n = Pen.N
    val from = if (back) 0 else n / 2
    val to = if (back) n / 2 else n
    var yTop = Float.MAX_VALUE
    val path = pen.b.apply {
        reset()
        arc(cam, RBI, BASE, 2 * PI * from / n, 2 * PI * to / n, to - from)
        for (i in to downTo from) {
            val o = cam.project(pen.sx[i], pen.sy[i], pen.sz[i])
            lineTo(o.x, o.y)
            if (o.y < yTop) yTop = o.y
        }
        close()
    }
    if (back) {
        drawPath(path, lerp(color, Color.Black, 0.45f), alpha = 0.9f)
    } else {
        val yBot = cam.project(0.0, -RBI, BASE).y
        drawPath(path, Brush.verticalGradient(listOf(color.copy(alpha = 0.78f), lerp(color, Color.Black, 0.35f).copy(alpha = 0.9f)), yTop, yBot))
    }
}

private fun DrawScope.drawGlassFront(cam: Camera, pen: Pen) {
    val w = cam.unit * 0.022f
    // 두꺼운 유리 바닥: 바닥 원(z=0)과 안쪽 바닥(z=BASE) 사이의 앞쪽 띠, 아래 가장자리는 빛을 모은다
    val slab = pen.a.apply {
        reset()
        arc(cam, RB.toDouble(), 0.0, PI, 2 * PI, 48)
        arc(cam, radiusAt(BASE), BASE, 2 * PI, PI, 48, start = false)
        close()
    }
    val slabTop = cam.project(-RB.toDouble(), 0.0, BASE).y
    val slabBottom = cam.project(0.0, -RB.toDouble(), 0.0).y
    drawPath(slab, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.30f)), slabTop, slabBottom))
    pen.b.reset()
    pen.b.arc(cam, RBI, BASE, PI, 2 * PI, 48)
    drawPath(pen.b, Color.White.copy(alpha = 0.35f), style = Stroke(w * 0.6f))
    pen.b.reset()
    pen.b.arc(cam, RB.toDouble(), 0.0, PI, 2 * PI, 48)
    drawPath(pen.b, GlassEdge, style = Stroke(w))
    // 옆 윤곽 (보이는 가장자리: 좌우 끝)
    for (side in intArrayOf(1, -1)) {
        drawLine(GlassEdge, cam.project(RB * side.toDouble(), 0.0, 0.0), cam.project(RT * side.toDouble(), 0.0, H.toDouble()), strokeWidth = w)
    }
    // 반사광: 앞쪽 왼편 세로 띠
    val a0 = PI * 1.18
    val a1 = PI * 1.26
    pen.a.reset()
    pen.a.arc(cam, RB * 1.001, BASE + 0.05, a0, a1, 6)
    pen.a.arc(cam, RT * 1.001, H - 0.12, a1, a0, 6, start = false)
    pen.a.close()
    drawPath(pen.a, Color.White.copy(alpha = 0.22f))
    // 가장자리 (앞쪽은 두껍게)
    pen.b.reset()
    pen.b.arc(cam, RT.toDouble(), H.toDouble(), PI, 2 * PI, 36)
    drawPath(pen.b, GlassEdge, style = Stroke(w * 1.3f))
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
            val o = cam.project(d.x, d.y, max(d.z, 0.0))
            scope.drawCircle(c, radius = cam.unit * (0.018f + 0.012f * (d.life / 1.4).toFloat()), center = o, alpha = (d.life / 1.4).toFloat().coerceIn(0.2f, 1f))
        }
    }
}
