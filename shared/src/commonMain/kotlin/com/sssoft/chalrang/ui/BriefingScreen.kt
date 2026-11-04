package com.sssoft.chalrang.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
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
import kotlinx.coroutines.delay
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
import com.sssoft.chalrang.core.RoutePlanner
import com.sssoft.chalrang.core.RoutePoint
import com.sssoft.chalrang.core.RoutePreview
import com.sssoft.chalrang.core.TerrainGrid
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 경로 브리핑 (출발 전). 길 전체를 3D 로 보여 주지 않는다 — 수백 km 길은 높이서 내려다보면 선 하나뿐이고 지형도 무겁다.
 * 1) 분석이 끝나면 멀미 위험 구간만 카드로 (어디서·얼마나·왜·언제 닿는지, 그 구간 길 모양),
 * 2) 카드를 누르면 그 구간만 3D 로: 구간 둘레를 돌며 짚고 → 쏙 들어가 달려 보고 → 올라와 → 카드로 돌아온다.
 * 지형은 그 구간 둘레만 받는다 (엔진이 길을 평가하자마자 미리 받아 둔다).
 */
@Composable
fun BriefingScreen(
    path: RoutePath,
    speak: (String) -> Unit,
    onClose: () -> Unit,
    /** 위험 구간 둘레의 자세한 지형 (3D 로 볼 때) */
    detail: (suspend (RiskSegment) -> TerrainGrid?)? = null,
    /** 즐겨찾기인지 (null 이면 저장할 수 없는 길: 시연 등 → 별 버튼 없음) */
    favorite: Boolean? = null,
    /** 저장해 둔 길을 불러왔는지 */
    fromSaved: Boolean = false,
    onFavorite: () -> Unit = {},
) {
    val plan = remember(path) { BriefPlan(path) }
    val details = remember(path) { mutableStateMapOf<Int, TerrainGrid>() }
    /** 구간마다 지형을 받는 중인지 (카드에 "준비 중" 표시, 3D 를 열 때 기다림) */
    val loading = remember(path) { mutableStateMapOf<Int, Boolean>() }
    var selected by remember(plan) { mutableStateOf<Int?>(null) }
    // 위험 구간 지형은 바로(엔진이 미리 받아 둔 것), 조심 구간은 카드를 눌렀을 때
    LaunchedEffect(plan) {
        val load = detail ?: return@LaunchedEffect
        plan.segs.forEachIndexed { i, seg ->
            if (!plan.eager(i)) return@forEachIndexed
            loading[i] = true
            launch { load(seg)?.let { details[i] = it }; loading[i] = false }
        }
    }
    LaunchedEffect(plan, selected) {
        val i = selected ?: return@LaunchedEffect
        val load = detail ?: return@LaunchedEffect
        if (details[i] != null || loading[i] == true) return@LaunchedEffect
        loading[i] = true
        // 다 받기 전에 목록으로 돌아가면 취소된다: 다음에 다시 받게 표시를 지운다
        try { load(plan.segs[i])?.let { details[i] = it } } finally { loading[i] = false }
    }
    val viewed = remember(plan) { mutableStateMapOf<Int, Boolean>() }
    val speakNow by rememberUpdatedState(speak)
    LaunchedEffect(plan) { speakNow(plan.intro()) }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF050B18), Color(0xFF0A1A33), Color(0xFF040A16))))) {
        val sel = selected
        if (sel == null) {
            SegmentCards(plan, loading, viewed, favorite, fromSaved, onFavorite, onPick = { selected = it }, onClose = onClose)
        } else {
            SegmentBriefing(
                plan, sel, details[sel], loading[sel] == true, speak,
                onBack = { viewed[sel] = true; selected = null },
            )
        }
    }
}

/** 카드 목록: 위쪽 요약, 가운데 구간 카드(가로로 넘김), 아래 전체 길 띠 */
@Composable
private fun SegmentCards(
    plan: BriefPlan,
    loading: Map<Int, Boolean>,
    viewed: Map<Int, Boolean>,
    favorite: Boolean?,
    fromSaved: Boolean,
    onFavorite: () -> Unit,
    onPick: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val path = plan.path
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Route, null, tint = Neon.Cyan, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("경로 브리핑", color = Neon.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (fromSaved) "저장해 둔 길 · 인터넷 없이 바로 불러왔어요" else sourceLabel(path),
                        color = if (fromSaved) Neon.Green else Neon.Dim, fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (favorite != null) {
                    // 즐겨찾기: 분석한 길과 지형을 계속 저장해 둔다 (아니면 최근 길 몇 곳만)
                    NeonChip(if (favorite) "즐겨찾기 ★" else "즐겨찾기 ☆", favorite, onClick = onFavorite)
                    Spacer(Modifier.width(10.dp))
                }
                NeonChip("출발하기", true, onClick = onClose, icon = Icons.Filled.Close)
            }
            Spacer(Modifier.height(18.dp))
            Text(plan.headline(), color = Neon.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            path.preview.restAdvice?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = Neon.Green, fontSize = 15.sp)
            }
            Spacer(Modifier.height(18.dp))
            if (plan.segs.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("멀미 걱정 구간이 없는 편한 길이에요", color = Neon.Text, fontSize = 20.sp)
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    plan.segs.indices.forEach { i ->
                        SegmentCard(plan, i, loading[i] == true, viewed[i] == true, onClick = { onPick(i) })
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            RouteStrip(plan)
        }
    }
}

/** 구간 카드: 번호·등급, 그 구간 길 모양(위에서 본), 위치·길이·닿는 시간, 이유, 흔들림 세기 */
@Composable
private fun SegmentCard(plan: BriefPlan, i: Int, loading: Boolean, viewed: Boolean, onClick: () -> Unit) {
    val s = plan.segs[i]
    val c = riskColor(s.level)
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.width(270.dp).fillMaxHeight().clip(shape)
            .background(Brush.verticalGradient(listOf(Neon.CardTop, Neon.CardBottom)))
            .border(1.5.dp, c.copy(alpha = if (viewed) 0.3f else 0.7f), shape)
            .clickable(onClick = onClick).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(15.dp)).background(c.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                Text("${i + 1}", color = c, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Text(if (s.level >= 2) "멀미 위험" else "조심", color = c, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (viewed) Text("봤어요", color = Neon.Dim, fontSize = 13.sp)
        }
        Spacer(Modifier.height(10.dp))
        SegmentShape(plan, i, Modifier.fillMaxWidth().weight(1f))
        Spacer(Modifier.height(10.dp))
        Text(plan.placeOf(s), color = Neon.White, fontSize = if (plan.hasName(s)) 18.sp else 22.sp, fontWeight = FontWeight.Bold, maxLines = 2)
        Text(
            listOfNotNull(if (plan.hasName(s)) RoutePreview.place(s) else km(s.lengthM), plan.arriveText(s.startM)).joinToString(" · "),
            color = Neon.Dim, fontSize = 14.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(s.reasons.take(3).joinToString(" · ").ifEmpty { "흔들림" }, color = Neon.Text, fontSize = 15.sp, maxLines = 2)
        Spacer(Modifier.height(10.dp))
        ShakeMeter(plan.peak[i], c)
        Spacer(Modifier.height(12.dp))
        Text(if (loading) "3D 지형 준비 중…" else "▶ 3D 로 미리 보기", color = if (loading) Neon.Dim else c, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 그 구간 길 모양 (위에서, 앞뒤 조금 포함): 구간은 위험 색, 앞뒤는 옅게 */
@Composable
private fun SegmentShape(plan: BriefPlan, i: Int, modifier: Modifier) {
    val path = plan.path
    val s = plan.segs[i]
    val c = riskColor(s.level)
    val a = path.index(s.startM - SHAPE_LEAD)
    val b = path.index(s.endM + SHAPE_LEAD)
    val sa = path.index(s.startM)
    val sb = path.index(s.endM)
    val stride = max(1, (b - a) / 400)
    Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x66040A16))) {
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (k in a..b step stride) {
            minX = min(minX, path.x[k]); maxX = max(maxX, path.x[k]); minY = min(minY, path.y[k]); maxY = max(maxY, path.y[k])
        }
        val pad = 14f
        val sc = min((size.width - 2 * pad) / max(1.0, maxX - minX), (size.height - 2 * pad) / max(1.0, maxY - minY))
        val ox = (size.width - (maxX - minX) * sc) / 2
        val oy = (size.height - (maxY - minY) * sc) / 2
        fun pt(k: Int) = Offset((ox + (path.x[k] - minX) * sc).toFloat(), (size.height - oy - (path.y[k] - minY) * sc).toFloat())
        val lead = Path()
        val seg = Path()
        var k = a
        var segStarted = false
        while (k <= b) {
            val p = pt(k)
            if (k == a) lead.moveTo(p.x, p.y) else lead.lineTo(p.x, p.y)
            if (k in sa..sb) { if (!segStarted) { seg.moveTo(p.x, p.y); segStarted = true } else seg.lineTo(p.x, p.y) }
            k = if (k < b && k + stride > b) b else k + stride
        }
        drawPath(lead, Neon.Cyan.copy(alpha = 0.35f), style = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(seg, c.copy(alpha = 0.25f), style = Stroke(12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(seg, c, style = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // 가는 방향: 들어가는 곳에 흰 점
        drawCircle(Neon.White, 5f, pt(sa))
    }
}

/** 흔들림 세기 막대 (가장 센 1km 의 a_w, 위험 기준을 4/5 지점에) */
@Composable
private fun ShakeMeter(aw: Double, c: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("흔들림", color = Neon.Dim, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Canvas(Modifier.weight(1f).height(8.dp)) {
            val h = size.height
            drawRoundRect(Neon.Grid, Offset.Zero, size, CornerRadius(h / 2))
            val f = (aw / (RoutePlanner.DANGER_AW * 1.25)).coerceIn(0.05, 1.0).toFloat()
            drawRoundRect(c, Offset.Zero, Size(size.width * f, h), CornerRadius(h / 2))
            val danger = (1 / 1.25f) * size.width
            drawLine(Neon.White.copy(alpha = 0.5f), Offset(danger, -2f), Offset(danger, h + 2f), 1.5f)
        }
    }
}

/** 전체 길 띠: 출발 → 도착, 카드 구간에 번호 */
@Composable
private fun RouteStrip(plan: BriefPlan) {
    val path = plan.path
    val measurer = rememberTextMeasurer()
    Column {
        Canvas(Modifier.fillMaxWidth().height(40.dp)) {
            val y = size.height - 10.dp.toPx()
            val h = 8.dp.toPx()
            val total = path.lengthM.coerceAtLeast(1.0)
            fun x(m: Double) = (m / total * size.width).toFloat().coerceIn(0f, size.width)
            drawRoundRect(Neon.Grid, Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2))
            for (seg in path.preview.segments) {
                drawRoundRect(riskColor(seg.level).copy(alpha = 0.5f), Offset(x(seg.startM), y - h / 2), Size((x(seg.endM) - x(seg.startM)).coerceAtLeast(3f), h), CornerRadius(h / 2))
            }
            plan.segs.forEachIndexed { k, seg ->
                val c = riskColor(seg.level)
                val cx = x((seg.startM + seg.endM) / 2)
                drawRoundRect(c, Offset(x(seg.startM), y - h / 2), Size((x(seg.endM) - x(seg.startM)).coerceAtLeast(4f), h), CornerRadius(h / 2))
                val l = measurer.measure("${k + 1}", androidx.compose.ui.text.TextStyle(color = c, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                drawText(l, topLeft = Offset(cx - l.size.width / 2f, y - h / 2 - l.size.height - 2))
            }
            drawCircle(Neon.White, 5.dp.toPx(), Offset(0f + 5.dp.toPx(), y))
        }
        Row(Modifier.fillMaxWidth()) {
            Text("출발", color = Neon.Dim, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text("도착 ${RoutePreview.fmtKm(path.lengthM)}", color = Neon.Dim, fontSize = 12.sp)
        }
    }
}

/** 한 구간 3D 브리핑: 끝나면(또는 "목록") 카드로 */
@Composable
private fun SegmentBriefing(
    plan: BriefPlan,
    seg: Int,
    grid: TerrainGrid?,
    loading: Boolean,
    speak: (String) -> Unit,
    onBack: () -> Unit,
) {
    val show = remember(plan, seg) { plan.show(seg) }
    var t by remember(show) { mutableDoubleStateOf(0.0) }
    var caption by remember(show) { mutableStateOf("") }
    // 지형을 받는 중이면 조금 기다린다 (인터넷이 느려도 [BriefPlan.LOAD_MAX_MS] 뒤엔 시작)
    var waited by remember(show) { mutableStateOf(false) }
    LaunchedEffect(show) { delay(BriefPlan.LOAD_MAX_MS); waited = true }
    val ready = !loading || waited
    val speakNow by rememberUpdatedState(speak)
    val back by rememberUpdatedState(onBack)
    LaunchedEffect(show, ready) {
        if (!ready) return@LaunchedEffect
        var last = 0L
        var told = -1
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.0 else ((now - last) / 1e9).coerceAtMost(0.25)
                last = now
                t += dt
                val i = show.stepIndex(t)
                if (i != told) {
                    told = i
                    show.line(i)?.let { caption = it; speakNow(it) }
                }
            }
            if (t >= show.total) { back(); break }
        }
    }

    val path = plan.path
    val s = plan.segs[seg]
    val step = show.steps[show.stepIndex(t)]
    val at = show.pointerAt(t)
    Box(Modifier.fillMaxSize()) {
        RouteScene(show, t, grid, Modifier.fillMaxSize())
        if (!ready) LoadingBar(Modifier.align(Alignment.Center))
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            NeonChip("구간 목록", false, onClick = onBack, icon = Icons.Filled.Close)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("${seg + 1}. ${if (s.level >= 2) "멀미 위험 구간" else "조심할 구간"} · ${plan.placeOf(s)}", color = riskColor(s.level), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(sourceLabel(path), color = Neon.Dim, fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))
            Text("${seg + 1} / ${plan.segs.size}", color = Neon.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        MiniMap(plan, at, Modifier.align(Alignment.TopEnd).padding(top = 84.dp, end = 20.dp).size(190.dp))
        if (step.kind == BriefPlan.Kind.DRIVE) SloshGauge(path, at, Modifier.align(Alignment.CenterStart).padding(start = 20.dp))
        Column(
            Modifier.align(Alignment.BottomCenter).padding(20.dp).widthIn(max = 980.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(caption, color = Neon.Text, fontSize = 18.sp, lineHeight = 25.sp, maxLines = 3)
            ProgressBar(path, at)
        }
    }
}

private fun sourceLabel(path: RoutePath) = path.region?.let { "실제 지역 · $it" }
    ?: if (path.synthetic) "내비 안내 지점과 지형으로 그린 길이에요" else if (path.grid != null) "실제 도로 · 지형" else "시연 경로"

/**
 * 브리핑할 구간과 구간별 3D 순서.
 * 카드: 멀미 위험 구간(빨강)과 조심 구간(노랑), 가는 순서대로. 위험 구간 지형은 미리, 조심 구간은 눌렀을 때 받는다.
 * 3D([Show]): 구간 둘레 돌며 짚기([SHOW_SEC]) → 들어가기([DIVE_SEC]) → 달리기(실주행의 1/[RISK_X], [DRIVE_MIN]~[DRIVE_MAX] 초)
 * → 올라오기([RISE_SEC]) → 마무리([END_SEC]).
 */
internal class BriefPlan(val path: RoutePath) {
    enum class Kind { SHOW, DIVE, DRIVE, RISE, END }
    class Step(val kind: Kind, val start: Double, val dur: Double)

    /** 구간의 흔들림 세기: 그 안에서 가장 센 1km 의 a_w (m/s²) */
    private fun peakOf(s: RiskSegment): Double {
        val aw = path.preview.riskPerKm
        if (aw.isEmpty()) return 0.0
        val a = (s.startM / RoutePlanner.BIN_M).toInt().coerceIn(0, aw.size - 1)
        val b = ((s.endM - 1) / RoutePlanner.BIN_M).toInt().coerceIn(a, aw.size - 1)
        var m = 0.0
        for (k in a..b) m = max(m, aw[k])
        return m
    }

    /** 카드: 위험 구간 모두(심한 [MAX_CARDS] 곳까지)와 조심 구간(심한 것부터, 합쳐 [MAX_ALL] 곳까지), 가는 순서대로 */
    val segs: List<RiskSegment> = run {
        val all = path.preview.segments
        val danger = all.filter { it.level >= 2 }.sortedByDescending { peakOf(it) }.take(MAX_CARDS)
        val caution = all.filter { it.level < 2 }.sortedByDescending { peakOf(it) }.take((MAX_ALL - danger.size).coerceAtLeast(0))
        (danger + caution).sortedBy { it.startM }
    }

    /** 내비가 이름을 준 지점 (교차로·나들목·목적지), 가는 순서대로 */
    private val named: List<RoutePoint> = path.marks.filter { !it.name.isNullOrBlank() }.sortedBy { it.atM }

    /** [m] 근처([before] m 앞 ~ [after] m 뒤)에서 가장 가까운 이름 */
    private fun nameNear(m: Double, before: Double, after: Double): String? =
        named.filter { it.atM >= m - before && it.atM <= m + after }.minByOrNull { kotlin.math.abs(it.atM - m) }?.name

    /** 구간 이름: 내비 지명으로 "○○부터 △△까지", 이름이 없으면 거리("12~19km") */
    fun placeOf(s: RiskSegment): String {
        val a = nameNear(s.startM, NAME_BEFORE, NAME_INSIDE)
        val b = nameNear(s.endM, NAME_INSIDE, NAME_BEFORE)
        return when {
            a != null && b != null && a != b -> "${a}부터 ${b}까지"
            a != null -> "${a}부터 ${km(s.lengthM)}"
            b != null -> "${b} 앞 ${km(s.lengthM)}"
            else -> RoutePreview.place(s)
        }
    }

    /** 이름이 있으면 거리도 함께 (카드 아래 줄) */
    fun hasName(s: RiskSegment) = placeOf(s) != RoutePreview.place(s)

    /** 미리 지형을 받아 둘 카드 (위험 구간만, 조심 구간은 눌렀을 때) */
    fun eager(i: Int): Boolean = segs[i].level >= 2
    val peak: List<Double> = segs.map { peakOf(it) }

    /** 점마다 위험 수준 (매 프레임 구간 목록을 훑지 않게 한 번만) */
    val levels: ByteArray = ByteArray(path.size).also { out ->
        for (s in path.preview.segments) {
            val a = path.index(s.startM)
            val b = (path.index(s.endM) - 1).coerceAtLeast(a)
            for (i in a..b) if (s.level > out[i]) out[i] = s.level.toByte()
        }
    }

    /** 경로가 차지하는 범위 (평면) */
    val minX: Double; val maxX: Double; val minY: Double; val maxY: Double

    init {
        var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (i in 0 until path.size) {
            x0 = min(x0, path.x[i]); x1 = max(x1, path.x[i]); y0 = min(y0, path.y[i]); y1 = max(y1, path.y[i])
        }
        minX = x0; maxX = x1; minY = y0; maxY = y1
    }

    /** 출발부터 [m] 까지 걸리는 시간 (초): 내비 예상 시간이 있으면 거리 비율로, 없으면 길 모양의 편한 속도로 */
    fun secondsTo(m: Double): Double {
        path.preview.etaSec?.let { eta -> if (path.lengthM > 0) return eta * (m / path.lengthM).coerceIn(0.0, 1.0) }
        var s = 0.0
        for (i in 1..path.index(m)) s += RoutePath.STEP / realMps(path, i * RoutePath.STEP)
        return s
    }

    fun arriveText(m: Double): String {
        val min = (secondsTo(m) / 60).roundToInt()
        return when {
            min < 1 -> "출발하자마자"
            min < 60 -> "출발 후 약 ${min}분"
            else -> "출발 후 약 ${min / 60}시간" + if (min % 60 >= 5) " ${min % 60}분" else ""
        }
    }

    fun headline(): String {
        val p = path.preview
        val eta = p.etaSec?.let { " · 약 ${fmtDuration(it)}" } ?: ""
        val danger = p.dangerCount
        val caution = p.segments.size - danger
        val what = listOfNotNull(
            danger.takeIf { it > 0 }?.let { "멀미 위험 구간 ${it}곳" },
            caution.takeIf { it > 0 }?.let { "조심할 구간 ${it}곳" },
        ).joinToString(" · ").ifEmpty { "멀미 걱정 없음" } +
            if (segs.size < p.segments.size) " (심한 ${segs.size}곳만 카드로)" else ""
        return "${RoutePreview.fmtKm(p.totalM)}$eta — $what"
    }

    /** 카드 화면을 열 때 할 말 */
    fun intro(): String {
        val km = RoutePreview.fmtKm(path.preview.totalM)
        if (segs.isEmpty()) return "$km 가는 길, 멀미 걱정 구간이 없는 편한 길이에요."
        val danger = segs.filter { it.level >= 2 }
        val caution = segs.size - danger.size
        val what = listOfNotNull(
            danger.size.takeIf { it > 0 }?.let { "멀미 위험 구간 ${it}곳" },
            caution.takeIf { it > 0 }?.let { "조심할 구간 ${it}곳" },
        ).joinToString(", ")
        val first = danger.firstOrNull() ?: segs.first()
        val kind = if (first.level >= 2) "첫 위험 구간" else "첫 구간"
        return "$km 가는 길에 ${what}이 있어요. ${kind}은 ${arriveText(first.startM)}이에요. 카드를 누르면 그 구간을 미리 볼 수 있어요."
    }

    fun show(i: Int) = Show(i)

    /** 한 구간 3D 순서 */
    inner class Show(val seg: Int) {
        val plan: BriefPlan get() = this@BriefPlan
        val s: RiskSegment = segs[seg]
        /** 시작부터 누적 실제 시간 (5m 마다): 달리기에서 실제처럼 굽이는 느리게, 곧은 데는 빠르게 */
        private val real: DoubleArray = run {
            val a = path.index(s.startM)
            val b = path.index(s.endM)
            val out = DoubleArray(b - a + 1)
            for (i in 1 until out.size) out[i] = out[i - 1] + RoutePath.STEP / realMps(path, (a + i) * RoutePath.STEP)
            out
        }
        val steps: List<Step>
        val total: Double

        /** 구간 둘레 범위 (앞뒤 [SHOW_LEAD] 포함) */
        val minX: Double; val maxX: Double; val minY: Double; val maxY: Double

        init {
            val drive = (real.last() / RISK_X).coerceIn(DRIVE_MIN, DRIVE_MAX)
            val list = ArrayList<Step>()
            var at = 0.0
            fun add(kind: Kind, d: Double) { list += Step(kind, at, d); at += d }
            add(Kind.SHOW, SHOW_SEC)
            add(Kind.DIVE, DIVE_SEC)
            add(Kind.DRIVE, drive)
            add(Kind.RISE, RISE_SEC)
            add(Kind.END, END_SEC)
            steps = list
            total = at
            var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
            for (i in path.index(s.startM - SHOW_LEAD)..path.index(s.endM + SHOW_LEAD)) {
                x0 = min(x0, path.x[i]); x1 = max(x1, path.x[i]); y0 = min(y0, path.y[i]); y1 = max(y1, path.y[i])
            }
            minX = x0; maxX = x1; minY = y0; maxY = y1
        }

        fun stepIndex(t: Double): Int {
            for (i in steps.indices.reversed()) if (t >= steps[i].start) return i
            return 0
        }

        /** 장면 안에서 0~1 */
        fun progress(t: Double): Double = steps[stepIndex(t)].let { ((t - it.start) / it.dur).coerceIn(0.0, 1.0) }

        /** 달리는 중이면 구간 안의 위치: 실제 걸리는 시간에 비례해서 */
        fun driveAt(u: Double): Double {
            val target = real.last() * u
            var lo = 0
            var hi = real.size - 1
            while (lo < hi) { val mid = (lo + hi) / 2; if (real[mid] < target) lo = mid + 1 else hi = mid }
            return s.startM + lo * RoutePath.STEP
        }

        /** 포인터(와 작은 지도·띠)의 위치 */
        fun pointerAt(t: Double): Double {
            val st = steps[stepIndex(t)]
            return when (st.kind) {
                Kind.SHOW, Kind.DIVE -> s.startM
                Kind.DRIVE -> driveAt(progress(t))
                Kind.RISE, Kind.END -> s.endM
            }
        }

        /** 그 장면을 시작할 때 할 말 */
        fun line(i: Int): String? = when (steps[i].kind) {
            Kind.SHOW -> showLine(s, plan.arriveText(s.startM), plan.placeOf(s))
            Kind.DRIVE -> tipLine(s)
            Kind.END -> "구간 목록으로 돌아갈게요."
            else -> null
        }
    }

    companion object {
        const val SHOW_SEC = 4.0
        const val DIVE_SEC = 2.2
        const val RISE_SEC = 1.8
        const val END_SEC = 1.6
        const val DRIVE_MIN = 4.0
        const val DRIVE_MAX = 18.0
        const val RISK_X = 20.0
        const val MAX_CARDS = 6
        /** 구간 이름을 찾는 범위 (m): 들어가기 전 [NAME_BEFORE] 까지, 구간 안쪽 [NAME_INSIDE] 까지 */
        const val NAME_BEFORE = 1_500.0
        const val NAME_INSIDE = 500.0
        /** 카드 전체 최대 (긴 길에서 조심 구간이 수십 곳이어도) */
        const val MAX_ALL = 12
        /** 3D 에서 구간 앞뒤로 함께 보여 줄 거리 (m) */
        const val SHOW_LEAD = 400.0
        /** 지형을 기다리는 최대 시간 (ms): 인터넷이 느려도 브리핑은 시작한다 */
        const val LOAD_MAX_MS = 8_000L

        /** 서는 곳·출발 직후에도 멈춰 있지 않게 최소 시속 20km 로 본다 */
        fun realMps(path: RoutePath, m: Double) = max(path.speedKmh[path.index(m)], 20.0) / 3.6
    }
}

/** 카드의 길 모양에 함께 그리는 앞뒤 거리 (m) */
private const val SHAPE_LEAD = 600.0

private fun fmtDuration(sec: Double): String {
    val m = (sec / 60).roundToInt()
    return if (m < 60) "${m}분" else "${m / 60}시간" + if (m % 60 > 0) " ${m % 60}분" else ""
}

/** 짚기: "여기서 여기까지" */
private fun showLine(s: RiskSegment, arrive: String, place: String): String {
    val what = if (s.level >= 2) "멀미 위험 구간" else "조심할 구간"
    val why = s.reasons.take(2).joinToString("·")
    return "$arrive, $place ${what}이에요." + if (why.isNotEmpty()) " $why." else ""
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
 * 한 구간 3D 장면: 그 구간 둘레의 색을 입힌 지형(높이별 색 + 비스듬한 빛 그림자 + 옅은 네온 격자, 가장자리는 흐려져 떠 있는 판처럼),
 * 경로 선(위험 빨강·조심 주황·편한 길 파랑), 포인터. 카메라는 둘레 돌며 짚기 → 들어가기 → 달리기 → 올라오기.
 */
@Composable
private fun RouteScene(show: BriefPlan.Show, t: Double, grid: TerrainGrid?, modifier: Modifier) {
    val plan = show.plan
    val path = plan.path
    val seg = show.s
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
        val st = show.steps[show.stepIndex(t)]
        val u = show.progress(t)
        // 그 구간 둘레의 자세한 지형만 (받았으면). 못 받았으면 앱에 넣어 둔 경로 둘레 지형
        val tg = grid ?: path.grid
        val cam = aboveGround(poseFor(show, st, u, t), path, tg).camera(path, size.width, size.height)
        if (tg != null && gpu.supported) {
            // 자세한 것만 있으면 두 겹 모두 그것으로: 둘레 밖은 흐려져 사라진다 (긴 길의 수 km 격자가 뭉개져 보이지 않게)
            val wide = if (grid != null) grid else tg
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

        // 경로: 달릴 때는 앞 3km·뒤 300m, 둘러볼 때는 구간과 앞뒤 [ROUTE_LEAD].
        // 보는 범위가 넓을수록 성기게, 보는 곳에서 먼 점은 더 성기게
        val driving = st.kind == BriefPlan.Kind.DRIVE
        val at = show.pointerAt(t)
        val i0 = if (driving) path.index(at - 300) else path.index(seg.startM - ROUTE_LEAD)
        val i1 = if (driving) path.index(at + 3000) else path.index(seg.endM + ROUTE_LEAD)
        val nearStride = if (driving) 1 else (cam.range / LINE_DENSITY / RoutePath.STEP).toInt().coerceAtLeast(1)
        val farStride = max(nearStride, (i1 - i0) / MAX_LINE_POINTS)
        val near2 = cam.range * cam.range
        val levels = plan.levels
        lines.forEach { it.reset() }
        curtain.reset()
        glow.reset()
        var prev: Offset? = null
        var prevLevel = -1
        var i = i0
        while (i <= i1) {
            val m = i * RoutePath.STEP
            val level = levels[i].toInt()
            val dfx = path.x[i] - cam.fx
            val dfy = path.y[i] - cam.fy
            val stride = if (dfx * dfx + dfy * dfy < near2) nearStride else farStride
            // 마지막 점은 꼭 (성기게 건너뛰다 끝이 잘리지 않게)
            val next = if (i < i1 && i + stride > i1) i1 else i + stride
            val p = cam.project(path.x[i], path.y[i], cam.zOf(path.z[i]) + LIFT)
            if (p == null) { prev = null; i = next; continue }
            val line = lines[level]
            if (prev == null || level != prevLevel) line.moveTo((prev ?: p).x, (prev ?: p).y)
            line.lineTo(p.x, p.y)
            // 이 구간은 한 번 더 빛나게
            if (m >= seg.startM && m <= seg.endM) {
                if (glow.isEmpty || prev == null) glow.moveTo(p.x, p.y) else glow.lineTo(p.x, p.y)
            }
            if (driving && i % 10 == 0) {
                val ground = tg?.let { cam.zOf(heightAt(it, path.x[i], path.y[i])) } ?: (cam.zOf(path.z[i]) - 40)
                cam.project(path.x[i], path.y[i], ground)?.let { g -> curtain.moveTo(p.x, p.y); curtain.lineTo(g.x, g.y) }
            }
            prev = p
            prevLevel = level
            i = next
        }
        drawPath(curtain, Color(0x334FA3E0), style = Stroke(1.2f))
        if (!driving) {
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
            // 구간 번호 (가운데 위에)
            val mid = path.index((seg.startM + seg.endM) / 2)
            cam.project(path.x[mid], path.y[mid], cam.zOf(path.z[mid]) + LIFT)?.let { base ->
                val top = Offset(base.x, base.y - 42f)
                val c = riskColor(seg.level)
                drawLine(c.copy(alpha = 0.8f), base, top, 2f)
                drawCircle(Color(0xE6081426), 17f, top)
                drawCircle(c, 17f, top, style = Stroke(2.5f))
                val l = labels.getOrPut("#${show.seg + 1}") {
                    measurer.measure("${show.seg + 1}", androidx.compose.ui.text.TextStyle(color = Neon.White, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                }
                drawText(l, topLeft = Offset(top.x - l.size.width / 2f, top.y - l.size.height / 2f))
            }
        }

        // 포인터 (둘러볼 때·마무리 때는 숨김)
        if (st.kind != BriefPlan.Kind.SHOW && st.kind != BriefPlan.Kind.END) {
            val pi = path.index(at)
            cam.project(path.x[pi], path.y[pi], cam.zOf(path.z[pi]) + LIFT)?.let { p ->
                val c = riskColor(levels[pi].toInt())
                val ground = tg?.let { cam.zOf(heightAt(it, path.x[pi], path.y[pi])) } ?: (cam.zOf(path.z[pi]) - 40)
                cam.project(path.x[pi], path.y[pi], ground)?.let { g -> drawLine(c.copy(alpha = 0.5f), p, g, 2f) }
                drawCircle(c.copy(alpha = 0.15f), 34f, p)
                drawCircle(c.copy(alpha = 0.35f), 18f, p)
                drawCircle(Neon.White, 8f, p)
            }
        }
    }
}

/** 둘러볼 때 구간 앞뒤로 함께 그리는 경로 (m) */
private const val ROUTE_LEAD = 1_500.0

/** 눈이 땅에서 적어도 이만큼 위에 (그리는 높이, m) */
private const val EYE_CLEAR = 60.0

/**
 * 눈이 산 속에 들어가지 않게: 뒤따라 달릴 때 눈은 길 높이 + 90m 인데, 자세한 지형에서는 굽이길 뒤 비탈이
 * 그보다 높을 때가 있다. 그러면 모든 시선이 땅 속에서 시작해 지형이 통째로 안 그려졌다(검은 화면).
 */
private fun aboveGround(p: Pose, path: RoutePath, g: TerrainGrid?): Pose {
    if (g == null) return p
    val ground = (heightAt(g, p.ex, p.ey) - path.z[0]) * zScaleOf(path)
    val min = ground + EYE_CLEAR
    return if (p.ez >= min) p else Pose(p.ex, p.ey, min, p.tx, p.ty, p.tz, p.fx, p.fy, p.range)
}

/** 경로 선 점 간격: 보는 범위의 1/[LINE_DENSITY] 쯤 (화면에서 1~2 화소) */
private const val LINE_DENSITY = 700.0

/** 보는 곳에서 먼 경로 선은 이만큼 점으로 */
private const val MAX_LINE_POINTS = 1500

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

    /**
     * 빨려 들어가기·올라오기: 거리는 배율로(같은 시간에 같은 배만큼 가까워진다), 바라보는 곳은 거리가 준 만큼 옮긴다.
     * 그냥 섞으면 긴 길(수백 km 위)에서는 거의 끝까지 멀리 있다가 마지막 몇 프레임에 땅으로 떨어진다.
     */
    fun zoom(o: Pose, k: Double): Pose {
        val ax = ex - tx; val ay = ey - ty; val az = ez - tz
        val bx = o.ex - o.tx; val by = o.ey - o.ty; val bz = o.ez - o.tz
        val d0 = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1.0)
        val d1 = sqrt(bx * bx + by * by + bz * bz).coerceAtLeast(1.0)
        val d = d0 * (d1 / d0).pow(k)
        val kt = if (abs(d0 - d1) < 1.0) k else ((d0 - d) / (d0 - d1)).coerceIn(0.0, 1.0)
        fun l(a: Double, b: Double, w: Double) = a + (b - a) * w
        // 바라보는 방향 (눈 ← 바라보는 곳): 단위 벡터를 섞어 다시 단위로
        var ux = l(ax / d0, bx / d1, k); var uy = l(ay / d0, by / d1, k); var uz = l(az / d0, bz / d1, k)
        val un = sqrt(ux * ux + uy * uy + uz * uz).coerceAtLeast(1e-9)
        ux /= un; uy /= un; uz /= un
        val ntx = l(tx, o.tx, kt); val nty = l(ty, o.ty, kt); val ntz = l(tz, o.tz, kt)
        val r = range * (o.range / range).pow(k)
        return Pose(ntx + ux * d, nty + uy * d, ntz + uz * d, ntx, nty, ntz, l(fx, o.fx, kt), l(fy, o.fy, kt), r)
    }

    fun camera(path: RoutePath, w: Float, h: Float) = ViewCamera(this, path, w, h)
}

private fun ease(u: Double) = u * u * (3 - 2 * u)

/** 장면마다 카메라: 구간 둘레를 천천히 돌며 짚기 ↔ 빨려 들어가기 → 뒤따라 달리기 → 올라오기 */
private fun poseFor(show: BriefPlan.Show, st: BriefPlan.Step, u: Double, t: Double): Pose {
    val path = show.plan.path
    val over = overview(show, BASE_ANGLE + ORBIT_RATE * t)
    return when (st.kind) {
        BriefPlan.Kind.SHOW, BriefPlan.Kind.END -> over
        BriefPlan.Kind.DIVE -> over.zoom(chase(path, show.s.startM), ease(u))
        BriefPlan.Kind.DRIVE -> chase(path, show.driveAt(u))
        BriefPlan.Kind.RISE -> chase(path, show.s.endM).zoom(over, ease(u))
    }
}

private const val BASE_ANGLE = -PI / 2
/** 둘러볼 때 도는 빠르기 (rad/s) */
private const val ORBIT_RATE = 0.12

/** 구간(앞뒤 조금)을 비스듬히 내려다보는 자리 ([angle] 방향에서) */
private fun overview(show: BriefPlan.Show, angle: Double): Pose {
    val path = show.plan.path
    val cx = (show.minX + show.maxX) / 2
    val cy = (show.minY + show.maxY) / 2
    val r = max(show.maxX - show.minX, show.maxY - show.minY) * 0.62 + 600
    val zs = zScaleOf(path)
    var zMin = Double.MAX_VALUE
    var zMax = -Double.MAX_VALUE
    for (i in path.index(show.s.startM - BriefPlan.SHOW_LEAD)..path.index(show.s.endM + BriefPlan.SHOW_LEAD)) {
        zMin = min(zMin, path.z[i]); zMax = max(zMax, path.z[i])
    }
    val cz = ((zMax + zMin) / 2 - path.z[0]) * zs
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
private fun MiniMap(plan: BriefPlan, at: Double, modifier: Modifier) {
    val path = plan.path
    val parts = remember { Array(3) { Path() } }
    Canvas(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xB306111F))) {
        val minX = plan.minX; val maxX = plan.maxX; val minY = plan.minY; val maxY = plan.maxY
        val pad = 14f
        val s = min((size.width - 2 * pad) / max(1.0, maxX - minX), (size.height - 2 * pad) / max(1.0, maxY - minY))
        val ox = pad + ((size.width - 2 * pad) - (maxX - minX) * s) / 2
        val oy = pad + ((size.height - 2 * pad) - (maxY - minY) * s) / 2
        fun pt(i: Int) = Offset((ox + (path.x[i] - minX) * s).toFloat(), (size.height - oy - (path.y[i] - minY) * s).toFloat())
        parts.forEach { it.reset() }
        var lastLevel = -1
        var last: Offset? = null
        // 작은 지도라 600 점이면 충분 (긴 길도 같은 비용)
        for (i in 0 until path.size step max(2, path.size / 600)) {
            val level = plan.levels[i].toInt()
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

/** 3D 를 열 때 그 구간 지형을 아직 받는 중 */
@Composable
private fun LoadingBar(modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xD906111F)).padding(horizontal = 28.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("이 구간의 지형을 불러오고 있어요", color = Neon.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
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
