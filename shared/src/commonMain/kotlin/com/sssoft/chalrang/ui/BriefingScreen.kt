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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateMapOf
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
import com.sssoft.chalrang.core.TerrainGrid
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 경로 브리핑: 가는 길을 파란 3D 선으로 그리고 멀미 위험 구간은 빨갛게(조심 구간은 주황).
 * 1) 전체 길을 한 바퀴 돌며 보여 주고 요약,
 * 2) 위험 구간마다 차례로 "여기서 여기까지" 짚고 → 그 구간으로 쏙 빨려 들어가 그 구간만 달려 보고 → 다시 위로,
 * 3) 마무리. 안전한 길을 따라 계속 달리지 않아 덜 어지럽다.
 */
@Composable
fun BriefingScreen(
    path: RoutePath,
    speak: (String) -> Unit,
    onClose: () -> Unit,
    /** 위험 구간 둘레의 자세한 지형 (가까이 들어갈 때 쓴다, 없으면 전체 지형) */
    detail: (suspend (RiskSegment) -> TerrainGrid?)? = null,
    /** 전체 지형을 자세히 (GPU 로 그릴 때) */
    fine: (suspend () -> TerrainGrid?)? = null,
) {
    var t by remember { mutableDoubleStateOf(0.0) }
    var caption by remember { mutableStateOf("") }
    val speakNow by rememberUpdatedState(speak)
    val close by rememberUpdatedState(onClose)
    val plan = remember(path) { BriefPlan(path) }
    val details = remember(path) { mutableStateMapOf<Int, TerrainGrid>() }
    // 한 바퀴 도는 동안 위험 구간의 자세한 지형을 미리 받아 둔다 (동시에)
    LaunchedEffect(plan) {
        val load = detail ?: return@LaunchedEffect
        plan.segs.forEachIndexed { i, seg -> launch { load(seg)?.let { details[i] = it } } }
    }
    LaunchedEffect(plan) {
        val load = fine ?: return@LaunchedEffect
        load()?.let { details[FINE] = it }
    }

    LaunchedEffect(plan) {
        var last = 0L
        var told = -1
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.0 else ((now - last) / 1e9).coerceAtMost(0.25)
                last = now
                t += dt
                // 장면이 바뀔 때 한 줄씩 말한다
                val i = plan.stepIndex(t)
                if (i != told) {
                    told = i
                    plan.line(i)?.let { caption = it; speakNow(it) }
                }
            }
            if (t >= plan.total) { close(); break }
        }
    }

    val step = plan.steps[plan.stepIndex(t)]
    val at = plan.pointerAt(t)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF050B18), Color(0xFF0A1A33), Color(0xFF040A16))))) {
        RouteScene(plan, t, details, Modifier.fillMaxSize())
        // 위: 제목·거리·닫기
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Route, null, tint = Neon.Cyan, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("경로 브리핑", color = Neon.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    path.region?.let { "실제 지역 · $it" }
                        ?: if (path.synthetic) "내비 안내 지점과 지형으로 그린 길이에요" else if (path.grid != null) "실제 도로 · 지형" else "시연 경로",
                    color = Neon.Dim, fontSize = 13.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (step.seg >= 0) "${step.seg + 1} / ${plan.segs.size} 구간" else "전체 ${km(path.lengthM)}",
                color = Neon.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(16.dp))
            NeonChip("건너뛰기", false, onClick = onClose, icon = Icons.Filled.Close)
        }
        MiniMap(path, at, Modifier.align(Alignment.TopEnd).padding(top = 84.dp, end = 20.dp).size(190.dp))
        if (step.kind == BriefPlan.Kind.DRIVE) SloshGauge(path, at, Modifier.align(Alignment.CenterStart).padding(start = 20.dp))
        // 아래: 지금 장면 + 말 + 전체 띠
        Column(
            Modifier.align(Alignment.BottomCenter).padding(20.dp).widthIn(max = 980.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val seg = plan.segs.getOrNull(step.seg)
                val (label, c) = when {
                    seg == null -> "전체 경로" to Neon.Cyan
                    seg.level >= 2 -> "멀미 위험 구간 ${RoutePreview.place(seg)}" to Neon.Red
                    else -> "조심할 구간 ${RoutePreview.place(seg)}" to Neon.Amber
                }
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(c.copy(alpha = 0.18f)).padding(horizontal = 12.dp, vertical = 5.dp)) {
                    Text(label, color = c, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Text(caption, color = Neon.Text, fontSize = 18.sp, lineHeight = 25.sp, maxLines = 3)
            }
            ProgressBar(path, at)
        }
    }
}

/**
 * 브리핑 순서와 시간: 한 바퀴([ORBIT_SEC]) → 위험 구간마다 짚기([SHOW_SEC]) · 빨려 들어가기([DIVE_SEC]) ·
 * 그 구간 달리기(실주행의 [RISK_X] 배, 구간당 [DRIVE_MIN]~ 초) · 올라오기([RISE_SEC]) → 마무리([END_SEC]).
 * 전체가 [CAP_SEC] 를 넘지 않게 달리기 시간을 줄인다. 위험 구간이 많으면 심한 [MAX_SEGS] 곳만.
 */
internal class BriefPlan(val path: RoutePath) {
    enum class Kind { ORBIT, SHOW, DIVE, DRIVE, RISE, END }
    class Step(val kind: Kind, val seg: Int, val start: Double, val dur: Double)

    val segs: List<RiskSegment> = path.preview.segments
        .sortedWith(compareByDescending<RiskSegment> { it.level }.thenByDescending { it.lengthM })
        .take(MAX_SEGS).sortedBy { it.startM }
    /** 구간마다 시작부터 누적 실제 시간 (5m 마다): 달리기에서 실제처럼 굽이는 느리게, 곧은 데는 빠르게 */
    private val segReal: List<DoubleArray> = segs.map { s ->
        val a = path.index(s.startM)
        val b = path.index(s.endM)
        val out = DoubleArray(b - a + 1)
        for (i in 1 until out.size) out[i] = out[i - 1] + RoutePath.STEP / realMps(path, (a + i) * RoutePath.STEP)
        out
    }
    val steps: List<Step>
    val total: Double

    init {
        // 구간이 3곳 넘으면 짚기·들어가기·올라오기를 조금 짧게
        val q = if (segs.size >= 3) 0.8 else 1.0
        val fixed = ORBIT_SEC + END_SEC + segs.size * (SHOW_SEC + DIVE_SEC + RISE_SEC) * q
        val ideal = segReal.map { (it.last() / RISK_X).coerceAtLeast(DRIVE_MIN) }
        val budget = max(segs.size * DRIVE_MIN, CAP_SEC - fixed)
        val k = if (ideal.sum() > budget) budget / ideal.sum() else 1.0
        val drive = ideal.map { max(DRIVE_MIN, it * k) }
        val list = ArrayList<Step>()
        var at = 0.0
        fun add(kind: Kind, seg: Int, d: Double) { list += Step(kind, seg, at, d); at += d }
        add(Kind.ORBIT, -1, ORBIT_SEC)
        segs.indices.forEach { i ->
            add(Kind.SHOW, i, SHOW_SEC * q)
            add(Kind.DIVE, i, DIVE_SEC * q)
            add(Kind.DRIVE, i, drive[i])
            add(Kind.RISE, i, RISE_SEC * q)
        }
        add(Kind.END, -1, END_SEC)
        steps = list
        total = at
    }

    fun stepIndex(t: Double): Int {
        for (i in steps.indices.reversed()) if (t >= steps[i].start) return i
        return 0
    }

    /** 장면 안에서 0~1 */
    fun progress(t: Double): Double = steps[stepIndex(t)].let { ((t - it.start) / it.dur).coerceIn(0.0, 1.0) }

    /** 달리는 중이면 그 구간 안의 위치: 실제 걸리는 시간에 비례해서 */
    fun driveAt(seg: Int, u: Double): Double {
        val real = segReal[seg]
        val target = real.last() * u
        var lo = 0
        var hi = real.size - 1
        while (lo < hi) { val mid = (lo + hi) / 2; if (real[mid] < target) lo = mid + 1 else hi = mid }
        return segs[seg].startM + lo * RoutePath.STEP
    }

    /** 포인터(와 작은 지도·띠)의 위치 */
    fun pointerAt(t: Double): Double {
        val st = steps[stepIndex(t)]
        val u = progress(t)
        return when (st.kind) {
            Kind.ORBIT -> 0.0
            Kind.END -> path.lengthM
            Kind.SHOW, Kind.DIVE -> segs[st.seg].startM
            Kind.DRIVE -> driveAt(st.seg, u)
            Kind.RISE -> segs[st.seg].endM
        }
    }

    /** 그 장면을 시작할 때 할 말 */
    fun line(i: Int): String? {
        val st = steps[i]
        return when (st.kind) {
            Kind.ORBIT -> intro(path.preview, segs.size)
            Kind.SHOW -> showLine(st.seg, segs[st.seg])
            Kind.DRIVE -> tipLine(segs[st.seg])
            Kind.END -> "브리핑을 마쳤어요." + (path.preview.restAdvice?.let { " $it." } ?: "") + " 안전하게 다녀오세요."
            else -> null
        }
    }

    companion object {
        const val CAP_SEC = 40.0
        const val ORBIT_SEC = 7.0
        const val SHOW_SEC = 3.2
        const val DIVE_SEC = 1.6
        const val RISE_SEC = 1.4
        const val END_SEC = 4.0
        const val DRIVE_MIN = 2.5
        const val RISK_X = 20.0
        const val MAX_SEGS = 4

        /** 서는 곳·출발 직후에도 멈춰 있지 않게 최소 시속 20km 로 본다 */
        fun realMps(path: RoutePath, m: Double) = max(path.speedKmh[path.index(m)], 20.0) / 3.6
    }
}

private fun intro(p: RoutePreview, shown: Int): String {
    val km = RoutePreview.fmtKm(p.totalM)
    val danger = p.segments.count { it.level >= 2 }
    val caution = p.segments.size - danger
    return when {
        p.segments.isEmpty() -> "가는 길을 한 바퀴 둘러볼게요. $km, 멀미 걱정 구간이 없는 편한 길이에요."
        else -> "가는 길을 한 바퀴 둘러볼게요. $km 가운데 " +
            listOfNotNull(danger.takeIf { it > 0 }?.let { "멀미 위험 구간 ${it}곳" }, caution.takeIf { it > 0 }?.let { "조심할 구간 ${it}곳" }).joinToString(", ") +
            "이 있어요." + if (shown < p.segments.size) " 그중 심한 ${shown}곳을 보여 드릴게요." else ""
    }
}

private val ORDER = listOf("첫 번째", "두 번째", "세 번째", "네 번째", "다섯 번째")

/** 짚기: "여기서 여기까지" */
private fun showLine(i: Int, s: RiskSegment): String {
    val what = if (s.level >= 2) "멀미 위험 구간" else "조심할 구간"
    return "여기서 여기까지, ${ORDER.getOrElse(i) { "${i + 1}번째" }} ${what}이에요. ${RoutePreview.place(s)}, ${s.reasons.take(2).joinToString("·")}."
}

/** 달리기: 운전 팁 */
private fun tipLine(s: RiskSegment): String = when {
    s.reasons.any { "오르내림" in it } -> "오르내림이 이어져요. 일정한 속도로 부드럽게 가 주세요."
    s.reasons.any { "굽은 길" in it || "회전" in it } -> "들어가기 전에 속도를 줄이고, 핸들은 천천히 돌려 주세요."
    s.reasons.any { "방지턱" in it } -> "방지턱은 천천히 넘어 주세요."
    s.reasons.any { "진출로" in it || "진입로" in it } -> "진출입로는 미리 감속해 주세요."
    else -> "천천히 부드럽게 가 주세요."
}

private fun km(m: Double): String = "${(m / 100).roundToInt() / 10.0}km"

private fun riskColor(level: Int): Color = when (level) {
    2 -> Neon.Red
    1 -> Neon.Amber
    else -> Neon.Cyan
}

/**
 * 3D 장면: 색을 입힌 지형(높이별 색 + 비스듬한 빛 그림자 + 옅은 네온 격자), 경로 선(위험 빨강·조심 주황·편한 길 파랑),
 * 위험 구간 번호, 포인터. 카메라는 장면에 따라 한 바퀴 → 짚기 → 빨려 들어가기 → 달리기 → 올라오기.
 */
@Composable
private fun RouteScene(plan: BriefPlan, t: Double, details: Map<Int, TerrainGrid>, modifier: Modifier) {
    val path = plan.path
    val measurer = rememberTextMeasurer()
    val labels = remember(measurer) { HashMap<String, androidx.compose.ui.text.TextLayoutResult>() }
    // 매 프레임 새로 만들지 않는 선·면 (메모리)
    val lines = remember { Array(3) { Path() } }
    val glow = remember { Path() }
    val curtain = remember { Path() }
    val wire = remember { Path() }
    // 지형마다 색·그림자 (한 번만 계산) — GPU 셰이더를 못 쓸 때
    val shades = remember(path) { HashMap<TerrainGrid, TerrainShade>() }
    // GPU 지형: 화소마다 매끄러운 그림자·색·등고선 (물컵과 같은 방식)
    val gpu = remember { TerrainShader() }
    val tu = remember { TerrainUniforms() }
    var loaded by remember { mutableStateOf<TerrainGrid?>(null) }
    var loadedWide by remember { mutableStateOf<TerrainGrid?>(null) }
    DisposableEffect(Unit) { onDispose { gpu.release() } }
    Canvas(modifier) {
        val st = plan.steps[plan.stepIndex(t)]
        val u = plan.progress(t)
        val cam = poseFor(plan, st, u, t).camera(path, size.width, size.height)
        // 위험 구간에 들어가 있으면 그 둘레의 자세한 지형, 아니면 전체 지형
        val close = st.seg >= 0 && (st.kind == BriefPlan.Kind.DIVE || st.kind == BriefPlan.Kind.DRIVE || st.kind == BriefPlan.Kind.RISE)
        // 가장 자세한 것: 전체 30m(받았으면), 위험 구간에 들어가 있으면 그 둘레 것과 견줘 더 촘촘한 쪽
        val fineG = details[FINE]
        val segG = if (close) details[st.seg] else null
        val tg = listOfNotNull(fineG, segG).minByOrNull { it.step } ?: path.grid
        if (tg != null && gpu.supported) {
            // 두 겹: 가까이는 가장 자세한 것, 멀리까지는 넓은 것 (앱에 넣어 둔 경로 둘레 3km, 없으면 같은 것)
            val wide = path.grid ?: tg
            if (loaded !== tg || loadedWide !== wide) {
                val e = EncodedGrid.of(tg)
                val ew = if (wide === tg) e else EncodedGrid.of(wide)
                gpu.setGrids(e, ew)
                tu.set(0, tg, e)
                tu.set(1, wide, ew)
                loaded = tg
                loadedWide = wide
            }
            cam.fill(tu)
            tu.far = (cam.range * 2.2).toFloat()
            gpu.setUniforms(tu)
            gpu.brush()?.let { drawRect(it) }
        } else if (tg != null) {
            shades.getOrPut(tg) { TerrainShade(tg) }.draw(this, cam, wire)
        } else {
            // 지형이 없으면 바닥 격자
            wire.reset()
            val gz = cam.zOf(path.z.min()) - 30
            val gx0 = ((cam.fx / 100).roundToInt() - 15) * 100.0
            val gy0 = ((cam.fy / 100).roundToInt() - 15) * 100.0
            for (k in 0..30) {
                cam.segment(wire, gx0 + k * 100, gy0, gz, gx0 + k * 100, gy0 + 3000, gz)
                cam.segment(wire, gx0, gy0 + k * 100, gz, gx0 + 3000, gy0 + k * 100, gz)
            }
            drawPath(wire, Color(0x1A4FA3E0), style = Stroke(1f))
        }

        // 경로: 달릴 때는 앞 3km·뒤 300m, 내려다볼 때는 전체 (조금 성기게)
        val driving = st.kind == BriefPlan.Kind.DRIVE
        val at = plan.pointerAt(t)
        val i0 = if (driving) path.index(at - 300) else 0
        val i1 = if (driving) path.index(at + 3000) else path.size - 1
        val stride = if (driving) 1 else 2
        lines.forEach { it.reset() }
        curtain.reset()
        glow.reset()
        val hi = plan.segs.getOrNull(st.seg)
        var prev: Offset? = null
        var prevLevel = -1
        var i = i0
        while (i <= i1) {
            val m = i * RoutePath.STEP
            val level = path.riskAt(m)
            val p = cam.project(path.x[i], path.y[i], cam.zOf(path.z[i]) + LIFT)
            if (p == null) { prev = null; i += stride; continue }
            val line = lines[level]
            if (prev == null || level != prevLevel) line.moveTo((prev ?: p).x, (prev ?: p).y)
            line.lineTo(p.x, p.y)
            // 짚는 구간은 한 번 더 빛나게
            if (hi != null && m >= hi.startM && m <= hi.endM) {
                if (glow.isEmpty || prev == null) glow.moveTo(p.x, p.y) else glow.lineTo(p.x, p.y)
            }
            if (driving && i % 10 == 0) {
                val ground = tg?.let { cam.zOf(heightAt(it, path.x[i], path.y[i])) } ?: (cam.zOf(path.z[i]) - 40)
                cam.project(path.x[i], path.y[i], ground)?.let { g -> curtain.moveTo(p.x, p.y); curtain.lineTo(g.x, g.y) }
            }
            prev = p
            prevLevel = level
            i += stride
        }
        drawPath(curtain, Color(0x334FA3E0), style = Stroke(1.2f))
        if (hi != null && st.kind != BriefPlan.Kind.DRIVE) {
            val pulse = 0.5f + 0.5f * kotlin.math.sin((t * 6).toFloat())
            drawPath(glow, Neon.Red.copy(alpha = 0.18f + 0.22f * pulse), style = Stroke(26f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        for (level in 0..2) {
            val c = riskColor(level)
            val w = if (level == 0) 4.5f else 6.5f
            drawPath(lines[level], c.copy(alpha = 0.14f), style = Stroke(w * 5, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(lines[level], c.copy(alpha = 0.32f), style = Stroke(w * 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(lines[level], c, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        if (driving) {
            // 안내 지점 (앞 1.5km). 화면에서 겹치면 이름은 하나만
            var lastLabel = Offset(-1e4f, -1e4f)
            for (mk in path.marks) {
                if (mk.atM < at || mk.atM > at + 1500 || mk.kind == ManeuverKind.OTHER) continue
                val k = path.index(mk.atM)
                val p = cam.project(path.x[k], path.y[k], cam.zOf(path.z[k]) + LIFT) ?: continue
                val top = cam.project(path.x[k], path.y[k], cam.zOf(path.z[k]) + LIFT + 25) ?: continue
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
        } else {
            // 위험 구간 번호 (구간 가운데 위에)
            plan.segs.forEachIndexed { k, seg ->
                val mid = path.index((seg.startM + seg.endM) / 2)
                val base = cam.project(path.x[mid], path.y[mid], cam.zOf(path.z[mid]) + LIFT) ?: return@forEachIndexed
                val top = Offset(base.x, base.y - 42f)
                val c = riskColor(seg.level)
                drawLine(c.copy(alpha = 0.8f), base, top, 2f)
                drawCircle(Color(0xE6081426), 17f, top)
                drawCircle(c, 17f, top, style = Stroke(2.5f))
                val l = labels.getOrPut("#${k + 1}") {
                    measurer.measure("${k + 1}", androidx.compose.ui.text.TextStyle(color = Neon.White, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                }
                drawText(l, topLeft = Offset(top.x - l.size.width / 2f, top.y - l.size.height / 2f))
            }
        }

        // 포인터 (한 바퀴·마무리 때는 숨김)
        if (st.kind != BriefPlan.Kind.ORBIT && st.kind != BriefPlan.Kind.END) {
            val pi = path.index(at)
            cam.project(path.x[pi], path.y[pi], cam.zOf(path.z[pi]) + LIFT)?.let { p ->
                val c = riskColor(path.riskAt(at))
                val ground = tg?.let { cam.zOf(heightAt(it, path.x[pi], path.y[pi])) } ?: (cam.zOf(path.z[pi]) - 40)
                cam.project(path.x[pi], path.y[pi], ground)?.let { g -> drawLine(c.copy(alpha = 0.5f), p, g, 2f) }
                drawCircle(c.copy(alpha = 0.15f), 34f, p)
                drawCircle(c.copy(alpha = 0.35f), 18f, p)
                drawCircle(Neon.White, 8f, p)
            }
        }
    }
}

/** [details] 에서 전체 자세한 지형의 자리 */
private const val FINE = -1

/** 경로 선을 지형 위로 살짝 띄운다 (지형 자료가 성겨서 길이 산에 묻히지 않게, m) */
private const val LIFT = 12.0

/** 카메라 자세: 눈·바라보는 곳, 지형을 진하게 그릴 중심과 범위 */
private class Pose(
    val ex: Double, val ey: Double, val ez: Double,
    val tx: Double, val ty: Double, val tz: Double,
    val fx: Double, val fy: Double, val range: Double,
) {
    fun lerp(o: Pose, k: Double): Pose {
        fun l(a: Double, b: Double) = a + (b - a) * k
        return Pose(l(ex, o.ex), l(ey, o.ey), l(ez, o.ez), l(tx, o.tx), l(ty, o.ty), l(tz, o.tz), l(fx, o.fx), l(fy, o.fy), l(range, o.range))
    }

    fun camera(path: RoutePath, w: Float, h: Float) = ViewCamera(this, path, w, h)
}

private fun ease(u: Double) = u * u * (3 - 2 * u)

/** 장면마다 카메라: 한 바퀴(위에서 빙 돎) ↔ 짚기(천천히 돎) → 빨려 들어가기 → 뒤따라 달리기 → 올라오기 */
private fun poseFor(plan: BriefPlan, st: BriefPlan.Step, u: Double, t: Double): Pose {
    val path = plan.path
    // 한 바퀴는 처음 장면 안에서, 그 뒤로는 아주 천천히 계속 돈다
    val angle = BASE_ANGLE + if (t < BriefPlan.ORBIT_SEC) 2 * PI * ease(t / BriefPlan.ORBIT_SEC) else 2 * PI + DRIFT * (t - BriefPlan.ORBIT_SEC)
    val over = overview(path, angle)
    return when (st.kind) {
        BriefPlan.Kind.ORBIT, BriefPlan.Kind.SHOW, BriefPlan.Kind.END -> over
        BriefPlan.Kind.DIVE -> over.lerp(chase(path, plan.segs[st.seg].startM), ease(u))
        BriefPlan.Kind.DRIVE -> chase(path, plan.driveAt(st.seg, u))
        BriefPlan.Kind.RISE -> chase(path, plan.segs[st.seg].endM).lerp(over, ease(u))
    }
}

private const val BASE_ANGLE = -PI / 2
private const val DRIFT = 0.04

/** 전체 길을 비스듬히 내려다보는 자리 ([angle] 방향에서) */
private fun overview(path: RoutePath, angle: Double): Pose {
    var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
    for (i in 0 until path.size step 8) {
        minX = min(minX, path.x[i]); maxX = max(maxX, path.x[i]); minY = min(minY, path.y[i]); maxY = max(maxY, path.y[i])
    }
    val cx = (minX + maxX) / 2
    val cy = (minY + maxY) / 2
    val r = max(maxX - minX, maxY - minY) * 0.62 + 600
    val zs = zScaleOf(path)
    val cz = ((path.z.max() + path.z.min()) / 2 - path.z[0]) * zs
    return Pose(cx + r * cos(angle), cy + r * sin(angle), cz + r * 0.85, cx, cy, cz, cx, cy, r * 1.6)
}

/** 포인터 뒤 180m·위 90m 에서 앞 260m 를 본다 (앞 150m 평균 방향으로, 굽이에서 덜 흔들리게) */
private fun chase(path: RoutePath, at: Double): Pose {
    val i = path.index(at)
    val px = path.x[i]
    val py = path.y[i]
    val j = path.index(at + 150)
    val k = path.index(at - 60)
    var hx = path.x[j] - path.x[k]
    var hy = path.y[j] - path.y[k]
    val hn = sqrt(hx * hx + hy * hy).coerceAtLeast(1e-6)
    hx /= hn; hy /= hn
    val pz = (path.z[i] - path.z[0]) * zScaleOf(path)
    return Pose(px - hx * 180, py - hy * 180, pz + 90, px + hx * 260, py + hy * 260, pz, px, py, 2_500.0)
}

/** 높이 키우기: 실제 지형 1.6배, 만든 길 3배 (오르내림이 보이게) */
private fun zScaleOf(path: RoutePath) = if (path.grid != null) 1.6 else 3.0

/** 원근 카메라 */
private class ViewCamera(pose: Pose, path: RoutePath, w: Float, h: Float) {
    val fx = pose.fx
    val fy = pose.fy
    val range = pose.range
    private val z0 = path.z[0]
    private val zScale = zScaleOf(path)
    private val cx = w / 2
    private val cy = h * 0.42f
    private val focal = h * 0.95
    private val c = doubleArrayOf(pose.ex, pose.ey, pose.ez)
    private val z0p = path.z[0]
    private val f = doubleArrayOf(pose.tx - pose.ex, pose.ty - pose.ey, pose.tz - pose.ez)
    private val r = DoubleArray(3)
    private val u = DoubleArray(3)

    fun zOf(z: Double) = (z - z0) * zScale

    /** GPU 지형 셰이더에 같은 카메라를 넘긴다 */
    fun fill(t: TerrainUniforms) {
        for (k in 0..2) { t.eye[k] = c[k].toFloat(); t.f[k] = f[k].toFloat(); t.r[k] = r[k].toFloat(); t.u[k] = u[k].toFloat() }
        t.focal = focal.toFloat(); t.cx = cx; t.cy = cy
        t.zScale = zScale.toFloat(); t.z0 = z0p.toFloat()
    }

    init {
        norm(f)
        r[0] = f[1]; r[1] = -f[0]; r[2] = 0.0
        norm(r)
        u[0] = r[1] * f[2] - r[2] * f[1]
        u[1] = r[2] * f[0] - r[0] * f[2]
        u[2] = r[0] * f[1] - r[1] * f[0]
    }

    /** 카메라 앞 깊이 (가까운 평면 뒤면 음수) */
    fun depth(x: Double, y: Double, z: Double) = (x - c[0]) * f[0] + (y - c[1]) * f[1] + (z - c[2]) * f[2]

    fun project(x: Double, y: Double, z: Double): Offset? {
        val dx = x - c[0]; val dy = y - c[1]; val dz = z - c[2]
        val zc = dx * f[0] + dy * f[1] + dz * f[2]
        if (zc < NEAR) return null
        val xc = dx * r[0] + dy * r[1] + dz * r[2]
        val yc = dx * u[0] + dy * u[1] + dz * u[2]
        return Offset((cx + focal * xc / zc).toFloat(), (cy - focal * yc / zc).toFloat())
    }

    /** 객체 없이 화면 좌표만 (지형 점이 많아서) */
    fun projectInto(x: Double, y: Double, z: Double, out: FloatArray, k: Int): Boolean {
        val dx = x - c[0]; val dy = y - c[1]; val dz = z - c[2]
        val zc = dx * f[0] + dy * f[1] + dz * f[2]
        if (zc < NEAR) return false
        out[2 * k] = (cx + focal * (dx * r[0] + dy * r[1] + dz * r[2]) / zc).toFloat()
        out[2 * k + 1] = (cy - focal * (dx * u[0] + dy * u[1] + dz * u[2]) / zc).toFloat()
        return true
    }

    fun segment(p: Path, x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) {
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
        const val NEAR = 5.0
    }
}

/**
 * 색을 입힌 지형: 칸마다 높이별 색(깊은 청록 골짜기 → 푸른 비탈 → 밝은 능선 → 하얀 봉우리)에
 * 북서쪽 빛 그림자를 곱해 [COLORS] 가지로 묶어 두고(한 번만), 매 프레임은 먼 칸부터 깊이 묶음별로 칠한다.
 * 그 위에 옅은 네온 격자. 범위 밖은 그리지 않고, 멀수록 배경으로 흐려진다.
 */
private class TerrainShade(private val g: TerrainGrid) {
    private val cells = (g.nx - 1) * (g.ny - 1)
    private val color = IntArray(cells)
    private val palette: Array<Color>
    private val sx = FloatArray(g.nx * g.ny * 2)
    private val ok = BooleanArray(g.nx * g.ny)
    private val depth = FloatArray(cells)
    private val order = IntArray(cells)
    private val binStart = IntArray(BINS + 1)
    private val paths = Array(COLORS) { Path() }
    private val used = BooleanArray(COLORS)

    init {
        val lo = g.h.min()
        val hi = g.h.max().coerceAtLeast(lo + 1)
        // 높이 단계 × 밝기 단계 색표
        palette = Array(COLORS) { k ->
            val band = k / SHADES
            val light = (k % SHADES + 0.5) / SHADES
            val base = hypso(band.toDouble() / (BANDS - 1))
            val f = (0.45 + 0.75 * light).toFloat()
            Color((base.red * f).coerceAtMost(1f), (base.green * f).coerceAtMost(1f), (base.blue * f).coerceAtMost(1f))
        }
        // 빛: 북서쪽 위에서 (지도처럼)
        val lx = -0.55; val ly = 0.45; val lz = 0.7
        for (j in 0 until g.ny - 1) for (i in 0 until g.nx - 1) {
            val h00 = g.at(i, j); val h10 = g.at(i + 1, j); val h01 = g.at(i, j + 1); val h11 = g.at(i + 1, j + 1)
            val dzx = ((h10 + h11) - (h00 + h01)) / (2 * g.step) * 1.6
            val dzy = ((h01 + h11) - (h00 + h10)) / (2 * g.step) * 1.6
            val nl = sqrt(dzx * dzx + dzy * dzy + 1)
            val lam = ((-dzx * lx - dzy * ly + lz) / nl).coerceIn(0.0, 1.0)
            val band = (((h00 + h10 + h01 + h11) / 4 - lo) / (hi - lo) * (BANDS - 1)).roundToInt().coerceIn(0, BANDS - 1)
            val shade = (lam * SHADES).toInt().coerceIn(0, SHADES - 1)
            color[j * (g.nx - 1) + i] = band * SHADES + shade
        }
    }

    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope, cam: ViewCamera, wire: Path) {
        val nx = g.nx
        for (j in 0 until g.ny) for (i in 0 until nx) {
            val k = j * nx + i
            ok[k] = cam.projectInto(g.x0 + i * g.step, g.y0 + j * g.step, cam.zOf(g.at(i, j)), sx, k)
        }
        // 깊이 묶음 (먼 것부터 칠하려고)
        val far = cam.range * 2.2
        binStart.fill(0)
        for (j in 0 until g.ny - 1) for (i in 0 until nx - 1) {
            val c = j * (nx - 1) + i
            val k = j * nx + i
            val x = g.x0 + (i + 0.5) * g.step
            val y = g.y0 + (j + 0.5) * g.step
            val visible = ok[k] && ok[k + 1] && ok[k + nx] && ok[k + nx + 1] && kotlin.math.hypot(x - cam.fx, y - cam.fy) < far
            depth[c] = if (visible) cam.depth(x, y, cam.zOf(g.at(i, j))).toFloat() else -1f
            if (visible) binStart[binOf(depth[c], far)]++
        }
        // 묶음별 시작 자리 (먼 묶음이 앞)
        var acc = 0
        for (b in BINS - 1 downTo 0) { val n = binStart[b]; binStart[b] = acc; acc += n }
        val fill = binStart.copyOf()
        for (c in 0 until cells) if (depth[c] >= 0) { val b = binOf(depth[c], far); order[fill[b]++] = c }
        // 먼 묶음부터: 묶음 안에서는 색별 경로 하나씩
        var idx = 0
        for (b in BINS - 1 downTo 0) {
            val end = if (b == 0) acc else binStart[b - 1]
            if (idx >= end) continue
            paths.forEach { it.reset() }
            used.fill(false)
            while (idx < end) {
                val c = order[idx++]
                val i = c % (nx - 1)
                val j = c / (nx - 1)
                val k = j * nx + i
                val p = paths[color[c]]
                used[color[c]] = true
                p.moveTo(sx[2 * k], sx[2 * k + 1])
                p.lineTo(sx[2 * (k + 1)], sx[2 * (k + 1) + 1])
                p.lineTo(sx[2 * (k + nx + 1)], sx[2 * (k + nx + 1) + 1])
                p.lineTo(sx[2 * (k + nx)], sx[2 * (k + nx) + 1])
                p.close()
            }
            // 멀수록 흐리게 (배경으로)
            val alpha = (1f - b.toFloat() / BINS).let { 0.25f + 0.75f * it }
            for (q in 0 until COLORS) if (used[q]) {
                scope.drawPath(paths[q], palette[q].copy(alpha = alpha))
                // 칸 사이 틈이 비치지 않게 같은 색 가는 테두리
                scope.drawPath(paths[q], palette[q].copy(alpha = alpha), style = Stroke(1.2f))
            }
        }
        // 옅은 네온 격자 (보이는 칸만)
        wire.reset()
        for (j in 0 until g.ny) for (i in 0 until nx) {
            val k = j * nx + i
            if (!ok[k]) continue
            if (i + 1 < nx && ok[k + 1] && (j % 2 == 0)) { wire.moveTo(sx[2 * k], sx[2 * k + 1]); wire.lineTo(sx[2 * (k + 1)], sx[2 * (k + 1) + 1]) }
            if (j + 1 < g.ny && ok[k + nx] && (i % 2 == 0)) { wire.moveTo(sx[2 * k], sx[2 * k + 1]); wire.lineTo(sx[2 * (k + nx)], sx[2 * (k + nx) + 1]) }
        }
        scope.drawPath(wire, Color(0x2E7FD8F0), style = Stroke(0.8f))
    }

    private fun binOf(d: Float, far: Double): Int = ((d / (far * 1.2)) * BINS).toInt().coerceIn(0, BINS - 1)

    companion object {
        const val BANDS = 8
        const val SHADES = 5
        const val COLORS = BANDS * SHADES
        const val BINS = 40

        /** 높이별 색: 골짜기 깊은 청록 → 푸른 비탈 → 청록빛 능선 → 하얀 봉우리 (네온 화면과 어울리게 차갑게) */
        fun hypso(v: Double): Color {
            val stops = listOf(
                0.0 to Color(0xFF0A2A3C), 0.3 to Color(0xFF0F4A5E), 0.55 to Color(0xFF1C6E7E),
                0.75 to Color(0xFF3C9AA6), 0.9 to Color(0xFF8CCAD4), 1.0 to Color(0xFFE6F6FA),
            )
            for (s in 1 until stops.size) {
                if (v <= stops[s].first) {
                    val (a, ca) = stops[s - 1]
                    val (b, cb) = stops[s]
                    val k = ((v - a) / (b - a)).toFloat()
                    return Color(ca.red + (cb.red - ca.red) * k, ca.green + (cb.green - ca.green) * k, ca.blue + (cb.blue - ca.blue) * k)
                }
            }
            return stops.last().second
        }
    }
}

/** 지형 격자에서 (x, y) 높이 (쌍선형) */
private fun heightAt(g: TerrainGrid, x: Double, y: Double): Double {
    val fx = ((x - g.x0) / g.step).coerceIn(0.0, g.nx - 1.001)
    val fy = ((y - g.y0) / g.step).coerceIn(0.0, g.ny - 1.001)
    val i = fx.toInt()
    val j = fy.toInt()
    val tx = fx - i
    val ty = fy - j
    val a = g.at(i, j) * (1 - tx) + g.at(i + 1, j) * tx
    val b = g.at(i, j + 1) * (1 - tx) + g.at(i + 1, j + 1) * tx
    return a * (1 - ty) + b * ty
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
