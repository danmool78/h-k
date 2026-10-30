package com.sssoft.chalrang.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sssoft.chalrang.core.ManeuverKind
import com.sssoft.chalrang.core.RiskSegment
import com.sssoft.chalrang.core.RoutePath
import com.sssoft.chalrang.core.RoutePreview
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 경로 브리핑: 가는 길을 파란 3D 선으로 그리고, 빛나는 포인터가 그 선을 빠르게 따라가며(카메라가 뒤에서 따라감)
 * 멀미 위험 구간은 빨갛게(조심 구간은 주황) 보여 주고 말로 설명한다. 위험 구간에서는 천천히 지나간다.
 */
@Composable
fun BriefingScreen(path: RoutePath, speak: (String) -> Unit, onClose: () -> Unit) {
    var at by remember { mutableDoubleStateOf(0.0) }
    var caption by remember { mutableStateOf("") }
    val speakNow by rememberUpdatedState(speak)
    val close by rememberUpdatedState(onClose)
    val pv = path.preview
    // 기본 속도: 전체를 약 24초에 (위험 구간은 0.5배로 천천히)
    val base = (path.lengthM / 24.0).coerceIn(150.0, 4_000.0)

    LaunchedEffect(path) {
        val intro = intro(pv)
        caption = intro
        speakNow(intro)
        // 첫 설명을 들을 시간
        var wait = 2.5
        var last = 0L
        val told = HashSet<Int>()
        var ended = false
        var endHold = 0.0
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.0 else ((now - last) / 1e9).coerceAtMost(0.2)
                last = now
                if (wait > 0) { wait -= dt; return@withFrameNanos }
                if (!ended) {
                    // 위험 구간 300m 앞에서 그 구간을 설명
                    pv.segments.forEachIndexed { i, seg ->
                        if (i !in told && at >= seg.startM - 300) {
                            told += i
                            val line = segmentLine(i, seg)
                            caption = line
                            speakNow(line)
                        }
                    }
                    val slow = if (path.riskAt(at + 100) > 0) 0.5 else 1.0
                    at = min(path.lengthM, at + base * slow * dt)
                    if (at >= path.lengthM) {
                        ended = true
                        val outro = "브리핑을 마쳤어요." + (pv.restAdvice?.let { " $it." } ?: "") + " 안전하게 다녀오세요."
                        caption = outro
                        speakNow(outro)
                    }
                } else {
                    endHold += dt
                }
            }
            if (ended && endHold > 4.0) { close(); break }
        }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF050B18), Color(0xFF0A1A33), Color(0xFF040A16))))) {
        RouteScene(path, at, Modifier.fillMaxSize())
        // 위: 제목·거리·닫기
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Route, null, tint = Neon.Cyan, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("경로 브리핑", color = Neon.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (path.synthetic) "내비 안내 지점과 지형으로 그린 길이에요" else "시연 경로",
                    color = Neon.Dim, fontSize = 13.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            Text("${km(at)} / ${km(path.lengthM)}", color = Neon.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(16.dp))
            NeonChip("건너뛰기", false, onClick = onClose, icon = Icons.Filled.Close)
        }
        MiniMap(path, at, Modifier.align(Alignment.TopEnd).padding(top = 84.dp, end = 20.dp).size(190.dp))
        SloshGauge(path, at, Modifier.align(Alignment.CenterStart).padding(start = 20.dp))
        // 아래: 지금 구간 + 말 + 전체 띠
        Column(
            Modifier.align(Alignment.BottomCenter).padding(20.dp).widthIn(max = 980.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val risk = path.riskAt(at)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (label, c) = when (risk) {
                    2 -> "멀미 위험 구간" to Neon.Red
                    1 -> "조심할 구간" to Neon.Amber
                    else -> "편한 길" to Neon.Cyan
                }
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(c.copy(alpha = 0.18f)).padding(horizontal = 12.dp, vertical = 5.dp)) {
                    Text(label, color = c, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                // 위험 구간을 지나 편한 길이면 다음 구간까지 거리
                val next = pv.segments.firstOrNull { it.startM - 300 > at }
                val passed = pv.segments.any { it.endM <= at }
                val text = if (risk == 0 && passed && !caption.startsWith("브리핑을 마쳤어요")) {
                    next?.let { "편한 길이에요. 다음 구간 ${RoutePreview.place(it)}, ${km(it.startM - at)} 앞" } ?: "편한 길이에요. 남은 위험 구간은 없어요"
                } else caption
                Text(text, color = Neon.Text, fontSize = 18.sp, lineHeight = 25.sp, maxLines = 3)
            }
            ProgressBar(path, at)
        }
    }
}

private fun intro(p: RoutePreview): String {
    val km = RoutePreview.fmtKm(p.totalM)
    val danger = p.segments.count { it.level >= 2 }
    val caution = p.segments.size - danger
    return when {
        p.segments.isEmpty() -> "가는 길을 미리 볼게요. $km, 멀미 걱정 구간이 없는 편한 길이에요."
        else -> "가는 길을 미리 볼게요. $km 가운데 " +
            listOfNotNull(danger.takeIf { it > 0 }?.let { "멀미 위험 구간 ${it}곳" }, caution.takeIf { it > 0 }?.let { "조심할 구간 ${it}곳" }).joinToString(", ") + "이 있어요."
    }
}

private fun segmentLine(i: Int, s: RiskSegment): String {
    val order = listOf("첫 번째", "두 번째", "세 번째", "네 번째", "다섯 번째").getOrElse(i) { "${i + 1}번째" }
    val what = if (s.level >= 2) "멀미 위험 구간" else "조심할 구간"
    val why = s.reasons.take(2).joinToString("·")
    val tip = when {
        s.reasons.any { "오르내림" in it } -> "오르내림이 이어져요. 일정한 속도로 부드럽게."
        s.reasons.any { "굽은 길" in it || "회전" in it } -> "들어가기 전에 속도를 줄이고 핸들은 천천히."
        s.reasons.any { "방지턱" in it } -> "방지턱은 천천히 넘어 주세요."
        s.reasons.any { "진출로" in it || "진입로" in it } -> "진출입로는 미리 감속해 주세요."
        else -> "천천히 부드럽게 가 주세요."
    }
    return "$order $what, ${RoutePreview.place(s)}. $why. $tip"
}

private fun km(m: Double): String = "${(m / 100).roundToInt() / 10.0}km"

private fun riskColor(level: Int): Color = when (level) {
    2 -> Neon.Red
    1 -> Neon.Amber
    else -> Neon.Cyan
}

/** 3D 장면: 바닥 격자, 경로 선(높이 기둥 포함), 위험 구간 빨강, 안내 지점, 포인터 */
@Composable
private fun RouteScene(path: RoutePath, at: Double, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val labels = remember(measurer) { HashMap<String, androidx.compose.ui.text.TextLayoutResult>() }
    // 매 프레임 새로 만들지 않는 선 (색마다 하나)
    val lines = remember { Array(3) { Path() } }
    val curtain = remember { Path() }
    val grid = remember { Path() }
    Canvas(modifier) {
        val cam = ChaseCamera(path, at, size.width, size.height)
        // 바닥 격자 (포인터 높이 아래)
        grid.reset()
        val gz = cam.groundZ
        val gx0 = ((cam.px / 100).roundToInt() - 15) * 100.0
        val gy0 = ((cam.py / 100).roundToInt() - 15) * 100.0
        for (k in 0..30) {
            cam.segment(grid, gx0 + k * 100, gy0, gz, gx0 + k * 100, gy0 + 3000, gz)
            cam.segment(grid, gx0, gy0 + k * 100, gz, gx0 + 3000, gy0 + k * 100, gz)
        }
        drawPath(grid, Color(0x1A4FA3E0), style = Stroke(1f))

        // 경로: 앞 3km·뒤 300m
        val i0 = path.index(at - 300)
        val i1 = path.index(at + 3000)
        lines.forEach { it.reset() }
        curtain.reset()
        var prev: Offset? = null
        var prevLevel = -1
        for (i in i0..i1) {
            val m = i * RoutePath.STEP
            val level = path.riskAt(m)
            val p = cam.project(path.x[i], path.y[i], cam.zOf(path.z[i]))
            if (p == null) { prev = null; continue }
            val line = lines[level]
            if (prev == null || level != prevLevel) {
                if (prev != null) line.moveTo(prev.x, prev.y) else line.moveTo(p.x, p.y)
            }
            line.lineTo(p.x, p.y)
            // 높이 기둥 (50m 마다): 3D 로 떠 있는 느낌
            if (i % 10 == 0) cam.project(path.x[i], path.y[i], gz)?.let { g -> curtain.moveTo(p.x, p.y); curtain.lineTo(g.x, g.y) }
            prev = p
            prevLevel = level
        }
        drawPath(curtain, Color(0x334FA3E0), style = Stroke(1.2f))
        for (level in 0..2) {
            val c = riskColor(level)
            val w = if (level == 0) 5f else 7f
            drawPath(lines[level], c.copy(alpha = 0.12f), style = Stroke(w * 5, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(lines[level], c.copy(alpha = 0.3f), style = Stroke(w * 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(lines[level], c, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        // 안내 지점 (앞 1.5km). 화면에서 겹치면 이름은 하나만
        var lastLabel = Offset(-1e4f, -1e4f)
        for (mk in path.marks) {
            if (mk.atM < at || mk.atM > at + 1500 || mk.kind == ManeuverKind.OTHER) continue
            val i = path.index(mk.atM)
            val p = cam.project(path.x[i], path.y[i], cam.zOf(path.z[i])) ?: continue
            val top = cam.project(path.x[i], path.y[i], cam.zOf(path.z[i]) + 25) ?: continue
            val c = if (mk.kind.risk >= 1.0) Neon.Amber else Neon.Dim
            drawLine(c.copy(alpha = 0.7f), p, top, 1.5f)
            drawCircle(c, 4f, top)
            val l = labels.getOrPut(mk.kind.label) {
                measurer.measure(mk.kind.label, androidx.compose.ui.text.TextStyle(color = Neon.Text, fontSize = 12.sp))
            }
            if ((top - lastLabel).getDistance() > 40f) {
                drawText(l, topLeft = Offset(top.x - l.size.width / 2f, top.y - l.size.height - 4))
                lastLabel = top
            }
        }

        // 포인터
        val pi = path.index(at)
        cam.project(path.x[pi], path.y[pi], cam.zOf(path.z[pi]))?.let { p ->
            val c = riskColor(path.riskAt(at))
            cam.project(path.x[pi], path.y[pi], gz)?.let { g -> drawLine(c.copy(alpha = 0.5f), p, g, 2f) }
            drawCircle(c.copy(alpha = 0.15f), 34f, p)
            drawCircle(c.copy(alpha = 0.35f), 18f, p)
            drawCircle(Neon.White, 8f, p)
        }
    }
}

/** 포인터 뒤 위에서 앞을 보는 카메라. 높이는 3배 키운다 (오르내림이 보이게) */
private class ChaseCamera(path: RoutePath, at: Double, w: Float, h: Float) {
    val px: Double
    val py: Double
    private val z0 = path.z[0]
    val groundZ: Double
    private val cx = w / 2
    private val cy = h * 0.42f
    private val focal = h * 0.95
    private val c = DoubleArray(3)
    private val r = DoubleArray(3)
    private val u = DoubleArray(3)
    private val f = DoubleArray(3)

    fun zOf(z: Double) = (z - z0) * Z_SCALE

    init {
        val i = path.index(at)
        px = path.x[i]; py = path.y[i]
        // 앞 150m 의 평균 방향으로 (굽이에서 카메라가 덜 흔들리게)
        val j = path.index(at + 150)
        val k = path.index(at - 60)
        var hx = path.x[j] - path.x[k]
        var hy = path.y[j] - path.y[k]
        val hn = sqrt(hx * hx + hy * hy).coerceAtLeast(1e-6)
        hx /= hn; hy /= hn
        val pz = zOf(path.z[i])
        var minZ = pz
        for (q in path.index(at - 300)..path.index(at + 3000) step 20) minZ = min(minZ, zOf(path.z[q]))
        groundZ = minZ - 30
        c[0] = px - hx * BACK; c[1] = py - hy * BACK; c[2] = pz + UP
        val tx = px + hx * AHEAD
        val ty = py + hy * AHEAD
        val tz = pz
        f[0] = tx - c[0]; f[1] = ty - c[1]; f[2] = tz - c[2]
        norm(f)
        // r = f × (0,0,1)
        r[0] = f[1]; r[1] = -f[0]; r[2] = 0.0
        norm(r)
        // u = r × f
        u[0] = r[1] * f[2] - r[2] * f[1]
        u[1] = r[2] * f[0] - r[0] * f[2]
        u[2] = r[0] * f[1] - r[1] * f[0]
    }

    fun project(x: Double, y: Double, z: Double): Offset? {
        val dx = x - c[0]; val dy = y - c[1]; val dz = z - c[2]
        val zc = dx * f[0] + dy * f[1] + dz * f[2]
        if (zc < NEAR) return null
        val xc = dx * r[0] + dy * r[1] + dz * r[2]
        val yc = dx * u[0] + dy * u[1] + dz * u[2]
        return Offset((cx + focal * xc / zc).toFloat(), (cy - focal * yc / zc).toFloat())
    }

    /** 두 점을 잇는 선 (가까운 평면 뒤면 그리지 않음) */
    fun segment(p: Path, x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) {
        // 격자선은 길어서 조각내 그린다
        var last: Offset? = null
        for (s in 0..12) {
            val t = s / 12.0
            val q = project(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, z0 + (z1 - z0) * t)
            if (q != null && last != null) { p.moveTo(last.x, last.y); p.lineTo(q.x, q.y) }
            last = q
        }
    }

    private fun norm(v: DoubleArray) {
        val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(1e-9)
        v[0] /= n; v[1] /= n; v[2] /= n
    }

    companion object {
        const val Z_SCALE = 3.0
        const val BACK = 180.0
        const val UP = 90.0
        const val AHEAD = 260.0
        const val NEAR = 5.0
    }
}

/** 위에서 본 전체 경로 (빨강 = 위험, 주황 = 조심, 흰 점 = 지금) */
@Composable
private fun MiniMap(path: RoutePath, at: Double, modifier: Modifier) {
    val parts = remember { Array(3) { Path() } }
    Canvas(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xB306111F))) {
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (i in 0 until path.size step 4) {
            minX = min(minX, path.x[i]); maxX = max(maxX, path.x[i]); minY = min(minY, path.y[i]); maxY = max(maxY, path.y[i])
        }
        val pad = 14f
        val s = min((size.width - 2 * pad) / max(1.0, maxX - minX), (size.height - 2 * pad) / max(1.0, maxY - minY))
        val ox = pad + ((size.width - 2 * pad) - (maxX - minX) * s) / 2
        val oy = pad + ((size.height - 2 * pad) - (maxY - minY) * s) / 2
        fun pt(i: Int) = Offset((ox + (path.x[i] - minX) * s).toFloat(), (size.height - oy - (path.y[i] - minY) * s).toFloat())
        parts.forEach { it.reset() }
        var lastLevel = -1
        var last: Offset? = null
        for (i in 0 until path.size step 2) {
            val level = path.riskAt(i * RoutePath.STEP)
            val p = pt(i)
            val line = parts[level]
            if (level != lastLevel) line.moveTo((last ?: p).x, (last ?: p).y)
            line.lineTo(p.x, p.y)
            last = p; lastLevel = level
        }
        for (level in 0..2) drawPath(parts[level], riskColor(level), style = Stroke(if (level == 0) 2.5f else 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val p = pt(path.index(at))
        drawCircle(Neon.White.copy(alpha = 0.3f), 9f, p)
        drawCircle(Neon.White, 4.5f, p)
    }
}

/** 이 길의 예상 출렁임: 굽은 정도(v²κ)와 오르내림 */
@Composable
private fun SloshGauge(path: RoutePath, at: Double, modifier: Modifier) {
    val g = path.lateralG(at + 50)
    val climb = path.climb(at)
    Column(modifier.width(120.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xB306111F)).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("예상 출렁임", color = Neon.Dim, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.width(26.dp).height(130.dp)) {
            val v = (g / 0.35).coerceIn(0.0, 1.0).toFloat()
            drawRoundRect(Neon.Grid, Offset.Zero, size, CornerRadius(8f))
            val c = when { g >= 0.25 -> Neon.Red; g >= 0.15 -> Neon.Amber; else -> Neon.Cyan }
            drawRoundRect(c, Offset(0f, size.height * (1 - v)), Size(size.width, size.height * v), CornerRadius(8f))
        }
        Spacer(Modifier.height(6.dp))
        Text("좌우 ${(g * 100).roundToInt() / 100.0} g", color = Neon.White, fontSize = 13.sp)
        Text("오르내림 ${climb.roundToInt()} m", color = Neon.Dim, fontSize = 11.sp)
    }
}

@Composable
private fun ProgressBar(path: RoutePath, at: Double) {
    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
        val y = size.height / 2
        val h = 8.dp.toPx()
        val total = path.lengthM.coerceAtLeast(1.0)
        fun x(m: Double) = (m / total * size.width).toFloat().coerceIn(0f, size.width)
        drawRoundRect(Neon.Grid, Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2))
        drawRoundRect(Neon.Cyan.copy(alpha = 0.5f), Offset(0f, y - h / 2), Size(x(at), h), CornerRadius(h / 2))
        for (seg in path.preview.segments) {
            drawRoundRect(riskColor(seg.level), Offset(x(seg.startM), y - h / 2), Size((x(seg.endM) - x(seg.startM)).coerceAtLeast(3f), h), CornerRadius(h / 2))
        }
        drawCircle(Neon.White, 6.dp.toPx(), Offset(x(at), y))
    }
}
