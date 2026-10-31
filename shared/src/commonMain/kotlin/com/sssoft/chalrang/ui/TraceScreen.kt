package com.sssoft.chalrang.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sssoft.chalrang.core.Child
import com.sssoft.chalrang.core.DriveTrace
import com.sssoft.chalrang.core.Sensitivity
import com.sssoft.chalrang.core.Lag
import com.sssoft.chalrang.core.ShakeAnalysis
import com.sssoft.chalrang.core.ShakeReport
import com.sssoft.chalrang.core.TraceEvent
import com.sssoft.chalrang.core.TraceKind
import com.sssoft.chalrang.core.TraceSample
import com.sssoft.chalrang.core.WaterCup
import com.sssoft.chalrang.engine.ChalrangEngine
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 이 구간의 통계 (0.1초마다) */
private class WindowStats(val accMax: Double, val accMin: Double, val tiltMax: Double, val spills: Int)

/**
 * 흔들림 분석: 차 흔들림(원인)과 물 출렁임(결과)을 같은 시간축에 실시간으로 흘려 보고,
 * 멀미 누적량과 흔들림 주기를 함께 본다. 그래프를 누르거나 끌면 그 순간에서 멈추고 물컵을 다시 그린다.
 * 주행 중에는 열지 않는다(시연은 가능).
 */
@Composable
fun TraceScreen(engine: ChalrangEngine, onClose: () -> Unit) {
    val ui by engine.ui.collectAsState()
    val trace = engine.trace
    // 그래프: 화면 프레임마다 다시 그려 실시간으로 흐른다 (그리기 단계에서만 읽어 화면 재구성은 없다)
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { frame.longValue = it }
    }
    // 수치·물컵·안내 목록: 0.1초마다
    var version by remember { mutableLongStateOf(trace.version) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            version = trace.version
        }
    }
    var windowSec by remember { mutableStateOf<Double?>(60.0) }
    var cursor by remember { mutableStateOf<Double?>(null) }
    /** 순간을 고르면 그때 화면을 멈춘다 (null = 지금을 따라 흐름) */
    var frozenEnd by remember { mutableStateOf<Double?>(null) }
    val pick: (Double) -> Unit = { t -> if (frozenEnd == null) frozenEnd = trace.endTime; cursor = t }
    val range: () -> Pair<Double, Double> = {
        val end = frozenEnd ?: trace.endTime
        (windowSec?.let { max(trace.startTime, end - it) } ?: trace.startTime) to end
    }
    val cursorAt: () -> Double = { val (a, b) = range(); cursor?.coerceIn(a, b) ?: b }
    val following = frozenEnd == null

    NeonBackground(Modifier.pointerInput(Unit) { detectTapGestures { } }) {
        val last = if (trace.size > 0) trace[trace.size - 1] else null
        if (ui.demo == null && !ui.remote && (last?.speedKmh ?: 0.0) > 5) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("주행 중에는 분석을 볼 수 없어요", color = Neon.Text, fontSize = 24.sp)
                Text("차를 세운 뒤 다시 열어 주세요", color = Neon.Dim, fontSize = 17.sp)
                Spacer(Modifier.height(16.dp))
                CloseButton(onClose)
            }
            return@NeonBackground
        }

        val (t0, t1) = remember(version, windowSec, frozenEnd) { range() }
        val lag = remember(trace.size / 10, trace.startTime, windowSec, frozenEnd) { trace.lag(t0, t1) }
        val shake = remember(trace.size / 20, trace.startTime, windowSec, frozenEnd) { ShakeAnalysis.analyze(trace, t0, t1, ShakeAnalysis.SHOW_MIN_SEC) }
        val events = remember(trace.events.size, trace.startTime, t0.toLong(), t1.toLong()) { trace.events.filter { it.t in t0..t1 } }
        val stats = remember(version, windowSec, frozenEnd) { windowStats(trace, t0, t1, events) }
        val at = cursor?.coerceIn(t0, t1) ?: t1
        val sample = remember(version, at) { trace.indexAt(at).takeIf { it >= 0 }?.let { trace[it] } }

        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
            val wide = maxWidth >= 900.dp
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Header(
                    wide, windowSec, following,
                    onWindow = { windowSec = it; cursor = null; frozenEnd = null },
                    onLive = { cursor = null; frozenEnd = null }, onClose = onClose,
                )
                if (trace.size < 2) {
                    Text("아직 기록이 없어요. 주행하거나 시연을 돌려 보세요.", color = Neon.Dim, fontSize = 18.sp)
                    return@Column
                }
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(Modifier.width(340.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CupCard(sample, Modifier.fillMaxWidth().height(232.dp))
                            // 요약·안내는 남는 높이에서 함께 스크롤 (화면이 낮아도 안내가 잘리지 않게)
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                SummaryCard(sample, trace.startTime, if (trace.size > 0) trace[0].level else null, lag, stats, shake, ui.setup.primary)
                                MessagesCard(events, trace.startTime, onPick = pick, modifier = Modifier)
                            }
                        }
                        Charts(trace, frame, range, cursorAt, following, events, shake, ui.setup.primary, sample, stats, pick, Modifier.weight(1f).fillMaxHeight(), null)
                    }
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CupCard(sample, Modifier.fillMaxWidth().height(220.dp))
                        SummaryCard(sample, trace.startTime, if (trace.size > 0) trace[0].level else null, lag, stats, shake, ui.setup.primary)
                        Charts(trace, frame, range, cursorAt, following, events, shake, ui.setup.primary, sample, stats, pick, Modifier.fillMaxWidth(), 200.dp)
                        MessagesCard(events, trace.startTime, onPick = pick, modifier = Modifier)
                    }
                }
            }
        }
    }
}

/** 출렁임 합계 (g): 앞뒤·좌우·위아래 */
private fun total(trace: DriveTrace, i: Int): Double = hypot(hypot(trace.tiltXAt(i), trace.tiltYAt(i)), trace.tiltZAt(i))

private fun windowStats(trace: DriveTrace, t0: Double, t1: Double, events: List<TraceEvent>): WindowStats {
    val i0 = trace.indexAt(t0).coerceAtLeast(0)
    val i1 = trace.indexAt(t1)
    var aMax = 0.0
    var aMin = 0.0
    var tMax = 0.0
    for (i in i0..i1) {
        aMax = max(aMax, max(max(trace.axAt(i), trace.ayAt(i)), trace.azAt(i)))
        aMin = min(aMin, min(min(trace.axAt(i), trace.ayAt(i)), trace.azAt(i)))
        tMax = max(tMax, total(trace, i))
    }
    return WindowStats(aMax, aMin, tMax, events.count { it.kind == TraceKind.SPILL })
}

// ---- 머리 ----

@Composable
private fun Header(
    wide: Boolean, windowSec: Double?, following: Boolean,
    onWindow: (Double?) -> Unit, onLive: () -> Unit, onClose: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(if (wide) 58.dp else 52.dp), verticalAlignment = Alignment.CenterVertically) {
        GlowIcon(Icons.Filled.ShowChart, Neon.Cyan, 32.dp)
        Spacer(Modifier.width(10.dp))
        Text("흔들림 분석", color = Neon.White, fontSize = if (wide) 30.sp else 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        if (wide) {
            Box(Modifier.padding(horizontal = 16.dp).width(1.dp).height(34.dp).background(Neon.Faint))
            Text("차의 움직임이 물의 출렁임과\n멀미에 미치는 영향을 분석합니다", color = Neon.Dim, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.weight(1f))
        Segmented(
            listOf(60.0 to "1분", 120.0 to "2분", 300.0 to "5분", null to "전체"),
            selected = windowSec, following = following, compact = !wide, onPick = onWindow, onLive = onLive,
        )
        if (wide) {
            Canvas(Modifier.padding(horizontal = 14.dp).width(180.dp).height(54.dp)) { carSilhouette(Neon.Cyan) }
        } else {
            Spacer(Modifier.width(8.dp))
        }
        CloseButton(onClose, compact = !wide)
    }
}

@Composable
private fun Segmented(
    items: List<Pair<Double?, String>>, selected: Double?, following: Boolean, compact: Boolean,
    onPick: (Double?) -> Unit, onLive: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.height(44.dp).background(Color(0x99091A30), shape).border(1.dp, Neon.Edge.copy(alpha = 0.45f), shape).padding(3.dp),
    ) {
        val w = if (compact) 44.dp else 64.dp
        items.forEach { (sec, label) -> SegmentItem(label, sec == selected, w) { onPick(sec) } }
        SegmentItem("지금", following, w, onLive)
    }
}

@Composable
private fun SegmentItem(label: String, on: Boolean, width: Dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    val look = if (on) {
        Modifier.background(Brush.verticalGradient(listOf(Color(0xFF1C64C8), Color(0xFF0E3A80))), shape).border(1.5.dp, Neon.Cyan, shape)
    } else {
        Modifier
    }
    Box(
        Modifier.fillMaxHeight().width(width).clip(shape).then(look).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (on) Neon.White else Neon.Dim, fontSize = 16.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun CloseButton(onClose: () -> Unit, compact: Boolean = false) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.height(44.dp).clip(shape).background(Color(0x99091A30), shape).border(1.dp, Neon.Edge.copy(alpha = 0.55f), shape)
            .clickable(onClick = onClose).padding(horizontal = if (compact) 10.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Close, "닫기", tint = Neon.Text, modifier = Modifier.size(22.dp))
        if (!compact) {
            Spacer(Modifier.width(8.dp))
            Text("닫기", color = Neon.Text, fontSize = 17.sp)
        }
    }
}

@Composable
private fun GlowIcon(icon: ImageVector, color: Color, size: Dp) {
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.size(size * 1.5f).background(Brush.radialGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)), CircleShape))
        Icon(icon, null, tint = color, modifier = Modifier.size(size))
    }
}

// ---- 왼쪽: 물컵·요약·안내 ----

/** 고른 순간(또는 지금)의 3D 물컵, 앞뒤·좌우 물 기울기 */
@Composable
private fun CupCard(s: TraceSample?, modifier: Modifier) {
    Box(modifier.neonCard().clip(Neon.CardShape)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.radialGradient(listOf(Color(0x3329C4F6), Color.Transparent), center = Offset(size.width * 0.58f, size.height * 0.6f), radius = size.width * 0.6f))
            // 옅은 격자
            val step = 26.dp.toPx()
            var gx = 0f
            while (gx < size.width) { drawLine(Color(0x0F4FA3E0), Offset(gx, 0f), Offset(gx, size.height)); gx += step }
            var gy = 0f
            while (gy < size.height) { drawLine(Color(0x0F4FA3E0), Offset(0f, gy), Offset(size.width, gy)); gy += step }
        }
        if (s != null) {
            CupView(s.tiltX - s.gradeX, s.tiltY - s.gradeY, s.level, s.dose, s.spilling, heave = s.tiltZ, modifier = Modifier.fillMaxSize().padding(start = 96.dp, end = 8.dp, top = 14.dp, bottom = 4.dp), stage = true)
        }
        LevelGauge(s, Modifier.align(Alignment.CenterStart).padding(start = 10.dp))
        Text("LIQUID\nDYNAMICS", color = Neon.Faint, fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp))
    }
}

/**
 * 수평계: 점 = 물이 몰린 쪽 (위 = 차 앞, 오른쪽 = 차 오른쪽), 바깥 원 = 0.3 g.
 * 빨간 원 = 이 아이의 넘침 기준. 점이 빨간 원 밖으로 나가면 넘친다.
 */
@Composable
private fun LevelGauge(s: TraceSample?, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val labels = remember(measurer) { Labels(measurer, 9.sp, Neon.Dim) }
    val tilt = s?.total ?: 0.0
    Column(modifier.width(84.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(82.dp, 68.dp)) {
            val c = Offset(size.height / 2, size.height / 2)
            val r = size.height / 2 - 10f
            val full = WaterCup.MAX_FREEBOARD
            drawCircle(Color(0x6606111F), r, c)
            drawCircle(Neon.Cyan.copy(alpha = 0.18f), r + 3f, c, style = Stroke(5f))
            drawCircle(Neon.Cyan.copy(alpha = 0.8f), r, c, style = Stroke(1.4f))
            drawLine(Neon.Grid, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
            drawLine(Neon.Grid, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
            for ((t, o) in listOf("앞" to Offset(0f, -1f), "뒤" to Offset(0f, 1f), "좌" to Offset(-1f, 0f), "우" to Offset(1f, 0f))) {
                val l = labels[t]
                drawText(l, topLeft = Offset(c.x + o.x * (r + 6f) - l.size.width / 2f, c.y + o.y * (r + 6f) - l.size.height / 2f))
            }
            if (s != null) {
                // 넘침 기준
                val fr = (s.freeboard / full * r).toFloat().coerceAtMost(r)
                drawCircle(Neon.Red.copy(alpha = 0.85f), fr, c, style = Stroke(1.6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f))))
                // 물이 몰린 쪽: 브레이크(tiltX > 0)면 앞(위), 왼쪽으로 돌면(tiltY < 0) 오른쪽
                val dx = (-s.tiltY / full).toFloat().coerceIn(-1.2f, 1.2f) * r
                val dy = (-s.tiltX / full).toFloat().coerceIn(-1.2f, 1.2f) * r
                val dot = Offset(c.x + dx, c.y + dy)
                val col = if (s.spilling) Neon.Red else Neon.Cyan
                drawLine(col.copy(alpha = 0.5f), c, dot, 1.5f)
                drawCircle(col.copy(alpha = 0.3f), 9f, dot)
                drawCircle(col, 5f, dot)
            }
            // 위아래 막대: 가운데 = 0, 위로 = 물이 눌림(차가 위로 밀림), 아래로 = 물이 뜸
            val bx = size.width - 6f
            drawLine(Neon.Grid, Offset(bx, c.y - r), Offset(bx, c.y + r), 4f)
            drawLine(Neon.Green.copy(alpha = 0.6f), Offset(bx - 5f, c.y), Offset(bx + 5f, c.y), 1f)
            if (s != null) {
                val bz = (s.tiltZ / full).toFloat().coerceIn(-1.1f, 1.1f) * r
                drawLine(Neon.Green.copy(alpha = 0.3f), Offset(bx, c.y), Offset(bx, c.y - bz), 9f)
                drawLine(Neon.Green, Offset(bx, c.y), Offset(bx, c.y - bz), 4f)
            }
            val zl = labels["위아래"]
            drawText(zl, topLeft = Offset(bx - zl.size.width / 2f, size.height - zl.size.height + 2f))
        }
        Text("쏠림", color = Neon.Dim, fontSize = 11.sp)
        Text("${fmt(tilt, 2)} g", color = if (s?.spilling == true) Neon.Red else Neon.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (s != null) Text("넘침 ${fmt(s.freeboard, 2)}", color = Neon.Red.copy(alpha = 0.85f), fontSize = 10.sp)
        // 길 경사 (1도 넘을 때): 물은 수평을 지키므로 컵 안에서 그만큼 기울어 보인다
        if (s != null && hypot(s.gradeX, s.gradeY) > 0.0175) {
            val deg = (kotlin.math.atan(hypot(s.gradeX, s.gradeY)) * 180 / kotlin.math.PI).roundToInt()
            val dir = if (abs(s.gradeX) >= abs(s.gradeY)) (if (s.gradeX > 0) "오르막" else "내리막") else "옆 기울기"
            Text("$dir ${deg}°", color = Neon.Dim, fontSize = 10.sp)
        }
    }
}

@Composable
private fun SummaryCard(s: TraceSample?, start: Double, startWater: Double?, lag: Lag?, stats: WindowStats, shake: ShakeReport?, child: Child?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().neonCard().padding(horizontal = 16.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Speed, null, tint = Neon.Cyan, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("실시간 요약", color = Neon.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (s != null) Text("${clock(s.t - start)}  |  ${s.speedKmh.roundToInt()} km/h", color = Neon.Text, fontSize = 14.sp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(Neon.Edge.copy(alpha = 0.6f), Color.Transparent))))
        if (s == null) return@Column
        val tilt = s.total
        SummaryRow(Icons.Filled.DirectionsCar, "차 흔들림", "${signed(s.ax, 1)} | ${signed(s.ay, 1)} | ${signed(s.az, 1)}", "앞|옆|위 m/s²")
        SummaryRow(Icons.Filled.WaterDrop, "물 출렁임", "${fmt(tilt, 2)} g", "넘침 ${fmt(s.freeboard, 2)} g", if (s.spilling) Neon.Red else Neon.White)
        // 남은 물: 첫 화면 물컵과 같은 값 (평소 운전 습관으로 채우고 넘친 만큼 줄어듦)
        val lost = (startWater ?: s.level) - s.level
        SummaryRow(
            Icons.Filled.LocalDrink, "남은 물", "${(s.level * 100).roundToInt()}%",
            startWater?.let { "출발 ${(it * 100).roundToInt()}%" + if (lost > 0.005) " · 넘쳐서 -${(lost * 100).roundToInt()}%" else "" } ?: "",
            if (lost > 0.03) Neon.Amber else Neon.White,
        )
        SummaryRow(
            Icons.Filled.Warning, "멀미 누적", fmt(s.dose, 2), "주의 0.6 · 위험 1.0",
            when { s.dose >= 1.0 -> Neon.Red; s.dose >= 0.6 -> Neon.Amber; else -> Neon.White },
        )
        SummaryRow(Icons.Filled.Waves, "넘침 횟수", "${stats.spills}회", if (s.spilling) "지금 넘침" else "여유 ${fmt(max(0.0, s.freeboard - tilt), 2)} g")
        SummaryRow(Icons.Filled.Timer, "반응 지연", lag?.let { "약 ${fmt(it.lagSec, 1)}초" } ?: "-", "물이 늦게 따라옴")
        SummaryRow(
            Icons.Filled.Hub, "차량-물 상관", lag?.let { fmt(it.correlation, 2) } ?: "-",
            lag?.let { when { it.correlation >= 0.6 -> "높음"; it.correlation >= 0.3 -> "보통"; else -> "낮음" } } ?: "",
        )
        if (shake != null) SummaryRow(Icons.Filled.GraphicEq, "멀미 주기", if (shake.quiet) "-" else "${fmt(shake.sicknessPeriod, 1)}초", "MSDV ${fmt(shake.msdv, 1)}")
        if (child != null) {
            val sens = child.sensitivity
            SummaryRow(
                Icons.Filled.Face, "${child.name} 민감",
                if (sens.active) "${fmt(sens.peakPeriod, 1)}초" else "학습 중",
                if (sens.active) "×${fmt(sens.gain, 2)} 강조 · ${sens.count}번" else "${sens.count}/${Sensitivity.MIN_EVENTS}번 · 표준 사용",
                if (sens.active) Neon.Amber else Neon.White,
            )
        }
    }
}

@Composable
private fun SummaryRow(icon: ImageVector, label: String, value: String, sub: String, valueColor: Color = Neon.White) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Neon.Cyan, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = Neon.Text, fontSize = 14.sp, modifier = Modifier.width(92.dp), maxLines = 1)
        Text(value, color = valueColor, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Text(sub, color = Neon.Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MessagesCard(events: List<TraceEvent>, start: Double, onPick: (Double) -> Unit, modifier: Modifier) {
    var all by remember { mutableStateOf(false) }
    val shown = events.filter { it.kind != TraceKind.SPILL }.asReversed().let { if (all) it else it.take(5) }
    Column(modifier.fillMaxWidth().neonCard().padding(horizontal = 16.dp, vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Campaign, null, tint = Neon.Cyan, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("안내 · 조치", color = Neon.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(if (all) "접기 ‹" else "더보기 ›", color = Neon.Dim, fontSize = 13.sp, modifier = Modifier.clickable { all = !all }.padding(4.dp))
        }
        Spacer(Modifier.height(6.dp))
        if (shown.isEmpty()) Text("이 구간에는 안내가 없어요", color = Neon.Faint, fontSize = 13.sp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            shown.forEachIndexed { k, e ->
                val c = if (k == 0) Neon.Amber else kindColor(e.kind)
                Row(Modifier.fillMaxWidth().clickable { onPick(e.t) }, verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 5.dp).size(9.dp).border(1.6.dp, c, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(clock(e.t - start), color = Neon.Dim, fontSize = 13.sp, modifier = Modifier.width(40.dp))
                    Text(
                        e.text.ifEmpty { e.kind.label }, color = if (k == 0) Neon.Amber else Neon.Text, fontSize = 13.sp, lineHeight = 18.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ---- 오른쪽: 그래프 ----

@Composable
private fun Charts(
    trace: DriveTrace, frame: State<Long>, range: () -> Pair<Double, Double>, cursorAt: () -> Double, following: Boolean,
    events: List<TraceEvent>, shake: ShakeReport?, child: Child?, s: TraceSample?, stats: WindowStats,
    onCursor: (Double) -> Unit, modifier: Modifier, fixedHeight: Dp?,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        @Composable
        fun ColumnScope.slot(weight: Float): Modifier = if (fixedHeight != null) Modifier.height(fixedHeight) else Modifier.weight(weight)

        ChartCard(
            Icons.Filled.DirectionsCar, "차 흔들림", "(가속도) m/s²",
            listOf("앞뒤" to Neon.Amber, "좌우" to Neon.Cyan, "위아래" to Neon.Green),
            stat(
                "최대" to signed(stats.accMax, 1), "최소" to signed(stats.accMin, 1),
                "현재" to (s?.let { signed(listOf(it.ax, it.ay, it.az).maxBy { v -> abs(v) }, 1) + " m/s²" } ?: "-"),
            ),
            slot(1f),
        ) {
            TimeChart(
                trace, frame, range, cursorAt, following, events, onCursor,
                series = listOf(
                    Series(Neon.Amber, fill = true) { t, i -> t.axAt(i) },
                    Series(Neon.Cyan, fill = true) { t, i -> t.ayAt(i) },
                    Series(Neon.Green, width = 1.5f) { t, i -> t.azAt(i) },
                ),
                yRange = -4.0 to 4.0, yTicks = listOf(3.0, 0.0, -3.0),
            )
        }
        ChartCard(
            Icons.Filled.WaterDrop, "물 출렁임", "(기울기) g",
            listOf("앞뒤" to Neon.Amber, "좌우" to Neon.Cyan, "위아래" to Neon.Green, "합계" to Neon.White),
            stat("최대" to "${fmt(stats.tiltMax, 2)} g", "현재" to (s?.let { "${fmt(it.total, 2)} g" } ?: "-"), "넘침" to "${stats.spills}회"),
            slot(1f),
        ) {
            TimeChart(
                trace, frame, range, cursorAt, following, events, onCursor,
                series = listOf(
                    Series(Neon.Amber) { t, i -> abs(t.tiltXAt(i)) },
                    Series(Neon.Cyan) { t, i -> abs(t.tiltYAt(i)) },
                    Series(Neon.Green, width = 1.5f) { t, i -> abs(t.tiltZAt(i)) },
                    Series(Neon.White, width = 2f, fill = true) { t, i -> total(t, i) },
                ),
                yRange = 0.0 to 0.32, yTicks = listOf(0.3, 0.15, 0.0),
                threshold = Threshold("넘침 기준", Neon.Red) { t, i -> t.freeboardAt(i) }, shadeSpill = true,
            )
        }
        val dose = s?.dose ?: 0.0
        ChartCard(
            Icons.Filled.Warning, "멀미 누적량", "",
            listOf("누적량" to Neon.Purple, "위험 기준" to Neon.Red),
            buildAnnotatedString {
                withStyle(SpanStyle(color = Neon.Dim)) { append("현재 ") }
                val c = when { dose >= 1.0 -> Neon.Red; dose >= 0.6 -> Neon.Amber; else -> Neon.White }
                withStyle(SpanStyle(color = c, fontWeight = FontWeight.Bold)) {
                    append(fmt(dose, 2) + when { dose >= 1.0 -> " (위험)"; dose >= 0.6 -> " (주의)"; else -> "" })
                }
            },
            slot(0.85f),
        ) {
            TimeChart(
                trace, frame, range, cursorAt, following, events, onCursor,
                series = listOf(Series(Neon.Purple, width = 2.5f, fill = true) { t, i -> t.doseAt(i) }),
                yRange = null, yTicks = listOf(2.0, 1.0, 0.0),
                guides = listOf(Guide(1.0, Neon.Red, "위험 1.0"), Guide(0.6, Neon.Amber, "주의 0.6")),
            )
        }
        ChartCard(
            Icons.Filled.GraphicEq, "흔들림 주기", "",
            listOf("앞뒤" to Neon.Amber, "좌우" to Neon.Cyan, "위아래" to Neon.Green, "Wf 가중" to Neon.Dim) +
                (child?.sensitivity?.takeIf { it.active }?.let { listOf("${child.name} 가중" to Neon.Red) } ?: emptyList()),
            if (shake == null || shake.quiet) {
                buildAnnotatedString { withStyle(SpanStyle(color = Neon.Dim)) { append(if (shake == null) "10초 넘게 기록되면 분석해요" else "이 구간은 흔들림이 거의 없어요") } }
            } else {
                val st = stat(
                    "가장 센 주기" to "${fmt(shake.peakPeriod, 1)}초", "멀미 주기" to "${fmt(shake.sicknessPeriod, 1)}초",
                    "2~10초" to "${(shake.bandShare * 100).roundToInt()}%",
                )
                // 30초 전에는 몇 초째인지 보인다 (짧을수록 긴 주기가 덜 정확)
                if (shake.durationSec >= ShakeAnalysis.MIN_SEC) st else buildAnnotatedString {
                    withStyle(SpanStyle(color = Neon.Faint)) { append("${shake.durationSec.roundToInt()}초째 · ") }
                    append(st)
                }
            },
            slot(0.95f),
        ) {
            if (shake != null) SpectrumChart(shake, child?.sensitivity?.takeIf { it.active })
        }
    }
}

/** 오른쪽 위 통계: "최대 +2.6 | 최소 -2.8 | 현재 -1.8 m/s²" */
private fun stat(vararg parts: Pair<String, String>): AnnotatedString = buildAnnotatedString {
    parts.forEachIndexed { k, (label, value) ->
        if (k > 0) withStyle(SpanStyle(color = Neon.Faint)) { append("  |  ") }
        withStyle(SpanStyle(color = Neon.Dim)) { append("$label ") }
        withStyle(SpanStyle(color = Neon.White, fontWeight = FontWeight.SemiBold)) { append(value) }
    }
}

@Composable
private fun ChartCard(
    icon: ImageVector, title: String, unit: String, legend: List<Pair<String, Color>>,
    stats: AnnotatedString, modifier: Modifier, content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth().neonCard().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Neon.Cyan, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, color = Neon.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(unit, color = Neon.Dim, fontSize = 13.sp, maxLines = 1)
            }
            Spacer(Modifier.width(14.dp))
            legend.forEach { (label, c) ->
                Box(Modifier.size(9.dp).border(2.dp, c, CircleShape))
                Spacer(Modifier.width(5.dp))
                Text(label, color = Neon.Text, fontSize = 13.sp, maxLines = 1)
                Spacer(Modifier.width(10.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(stats, fontSize = 14.sp, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)) { content() }
    }
}

private class Series(val color: Color, val width: Float = 1.8f, val fill: Boolean = false, val value: (DriveTrace, Int) -> Double)
private class Threshold(val label: String, val color: Color, val value: (DriveTrace, Int) -> Double)
private class Guide(val value: Double, val color: Color, val label: String)

/** 글자 측정 결과를 기억해 매 프레임 다시 재지 않는다 */
private class Labels(private val measurer: TextMeasurer, private val size: TextUnit, private val color: Color) {
    private val cache = HashMap<String, TextLayoutResult>()
    operator fun get(text: String): TextLayoutResult {
        cache[text]?.let { return it }
        if (cache.size > 200) cache.clear()
        return measurer.measure(text, TextStyle(color = color, fontSize = size)).also { cache[text] = it }
    }
}

/**
 * 시간축 그래프. 화면 프레임마다 지금 구간을 다시 읽어 그린다 → 실시간으로 흐른다.
 * 왼쪽 세로 눈금, 아래 시간 눈금("1분 전 … 지금"), 빛나는 선과 아래 채움, 기준선, 안내 표시, 고른 시각.
 */
@Composable
private fun TimeChart(
    trace: DriveTrace, frame: State<Long>, timeRange: () -> Pair<Double, Double>, cursorAt: () -> Double, following: Boolean,
    events: List<TraceEvent>, onCursor: (Double) -> Unit,
    series: List<Series>, yRange: Pair<Double, Double>?, yTicks: List<Double>,
    threshold: Threshold? = null, shadeSpill: Boolean = false, guides: List<Guide> = emptyList(),
) {
    val measurer = rememberTextMeasurer()
    val labels = remember(measurer) { Labels(measurer, 11.sp, Neon.Dim) }
    val tagLabels = remember(measurer) { HashMap<Color, Labels>() }
    val spillPath = remember { Path() }
    val linePath = remember { Path() }
    val fillPath = remember { Path() }
    val bandPath = remember { Path() }
    var bandLo by remember { mutableStateOf(FloatArray(0)) }
    var bandHi by remember { mutableStateOf(FloatArray(0)) }
    var bandX by remember { mutableStateOf(FloatArray(0)) }
    val left = 34f
    val bottom = 18f
    fun timeAtX(x: Float, width: Int): Double {
        val (t0, t1) = timeRange()
        return t0 + (t1 - t0) * ((x - left) / (width - left)).coerceIn(0f, 1f)
    }
    Canvas(
        Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { o -> onCursor(timeAtX(o.x, size.width)) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> onCursor(timeAtX(change.position.x, size.width)) } },
    ) {
        frame.value   // 프레임마다 다시 그리기
        val (t0, t1) = timeRange()
        val span = (t1 - t0).coerceAtLeast(1e-6)
        val w = size.width - left
        val h = size.height - bottom
        // 기록이 끊긴 구간(앱이 멈췄던 동안)이 있으면 가장 가까운 기록이 창 밖일 수 있다: 창 안의 것만
        var i0 = trace.indexAt(t0).coerceAtLeast(0)
        if (i0 < trace.size - 1 && trace.timeAt(i0) < t0) i0++
        var i1 = trace.indexAt(t1)
        if (i1 > 0 && trace.timeAt(i1) > t1) i1--
        // 한 픽셀에 몇 기록을 묶을지: 창 길이로 정해 프레임마다 바뀌지 않게
        // 픽셀보다 기록이 많으면(2분 이상) 3픽셀마다 묶어 평균 선 + 폭 띠로
        val perPx = span / trace.interval / max(1f, w)
        val step = if (perPx <= 1.0) 1 else max(2, ceil(perPx * 3).toInt())
        // 묶음 경계를 기록의 고정 번호에 맞춘다: 창이 0.1초씩 밀려도 같은 기록끼리 묶여 선이 떨리지 않는다
        val first = i0 + ((step - ((trace.dropped + i0) % step).toInt()) % step)
        val (lo, hi) = yRange ?: run {
            var m = 0.0
            var i = first
            while (i <= i1) { for (sr in series) m = max(m, sr.value(trace, i)); i += step }
            0.0 to max(1.5, m * 1.15)
        }
        fun x(t: Double) = left + ((t - t0) / span * w).toFloat()
        fun y(v: Double) = (h * (1 - (v.coerceIn(lo, hi) - lo) / (hi - lo))).toFloat()

        // 격자와 눈금
        for (v in yTicks) if (v in lo..hi) {
            drawLine(Neon.Grid, Offset(left, y(v)), Offset(size.width, y(v)), 1f, pathEffect = if (v == 0.0) null else PathEffect.dashPathEffect(floatArrayOf(3f, 5f)))
            val l = labels[fmtTick(v)]
            drawText(l, topLeft = Offset(left - l.size.width - 6, (y(v) - l.size.height / 2f).coerceIn(0f, h - l.size.height)))
        }
        for (k in 0..6) {
            val f = k / 6.0
            val px = left + (f * w).toFloat()
            drawLine(Neon.Grid, Offset(px, 0f), Offset(px, h), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f)))
            val ago = span * (1 - f)
            val text = when {
                k == 6 && following -> "지금"
                !following -> clock(t0 + span * f - trace.startTime)
                ago >= 60 && (ago % 60) < 0.5 -> "${(ago / 60).roundToInt()}분 전"
                else -> "${ago.roundToInt()}초"
            }
            val l = labels[text]
            drawText(l, topLeft = Offset((px - l.size.width / 2f).coerceIn(left, size.width - l.size.width), h + 3))
        }
        if (i1 <= i0) return@Canvas

        // 값은 그래프 칸 안에만 (빛 번짐 몇 px 여유): 기록이 끊겼거나 값이 범위를 넘어도 칸 밖으로 그리지 않는다
        clipRect(left - 2f, -4f, size.width + 4f, h + 4f) {
        // 넘친 구간
        if (shadeSpill) {
            spillPath.reset()
            val bw = max(2f, w * step / (i1 - i0 + 1) + 1)
            var i = first
            while (i <= i1) {
                if (trace.spillingAt(i)) spillPath.addRect(Rect(Offset(x(trace.timeAt(i)) - 1, 0f), Size(bw, h)))
                i += step
            }
            drawPath(spillPath, Neon.Red.copy(alpha = 0.14f))
        }
        fun build(value: (DriveTrace, Int) -> Double): Path {
            linePath.reset()
            var prevT = Double.NEGATIVE_INFINITY
            // 그림 간격보다 훨씬 긴 빈틈(앱이 멈췄던 동안)은 선을 잇지 않는다
            val gap = max(1.0, trace.interval * step * 3)
            fun point(i: Int) {
                val t = trace.timeAt(i)
                val px = x(t)
                val py = y(value(trace, i))
                if (t - prevT > gap) linePath.moveTo(px, py) else linePath.lineTo(px, py)
                prevT = t
            }
            if (step == 1) {
                for (i in i0..i1) point(i)
                return linePath
            }
            // 긴 창: 묶음 평균을 잇는다 (흔들림 폭은 아래 [band] 띠로)
            if (first > i0) point(i0)
            var b = first
            while (b <= i1) {
                val e = min(i1, b + step - 1)
                var sum = 0.0
                for (i in b..e) sum += value(trace, i)
                val mid = (b + e) / 2
                val t = trace.timeAt(mid)
                val px = x(t)
                val py = y(sum / (e - b + 1))
                if (t - prevT > gap) linePath.moveTo(px, py) else linePath.lineTo(px, py)
                prevT = t
                b += step
            }
            return linePath
        }
        // 긴 창의 흔들림 폭: 묶음마다 가장 작은 값~큰 값을 옅은 띠로 (선을 빽빽하게 오가지 않고 폭만 보인다)
        fun band(value: (DriveTrace, Int) -> Double): Path {
            bandPath.reset()
            val n = (i1 - first) / step + 1
            if (n < 2) return bandPath
            if (bandLo.size < n) { bandLo = FloatArray(n * 2); bandHi = FloatArray(n * 2); bandX = FloatArray(n * 2) }
            var k = 0
            var b = first
            while (b <= i1 && k < n) {
                val e = min(i1, b + step - 1)
                var vLo = value(trace, b)
                var vHi = vLo
                for (i in b + 1..e) { val v = value(trace, i); if (v < vLo) vLo = v; if (v > vHi) vHi = v }
                bandX[k] = x(trace.timeAt((b + e) / 2)); bandLo[k] = y(vLo); bandHi[k] = y(vHi)
                k++
                b += step
            }
            bandPath.moveTo(bandX[0], bandHi[0])
            for (j in 1 until k) bandPath.lineTo(bandX[j], bandHi[j])
            for (j in k - 1 downTo 0) bandPath.lineTo(bandX[j], bandLo[j])
            bandPath.close()
            return bandPath
        }
        // 기준선
        for (g in guides) if (g.value in lo..hi) {
            drawLine(g.color.copy(alpha = 0.85f), Offset(left, y(g.value)), Offset(size.width, y(g.value)), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 6f)))
            val l = tagLabels.getOrPut(g.color) { Labels(measurer, 11.sp, g.color) }[g.label]
            drawText(l, topLeft = Offset(size.width - l.size.width - 4, y(g.value) - l.size.height - 1))
        }
        if (threshold != null) {
            glowPath(build(threshold.value), threshold.color, 1.5f, dash = true)
            val l = tagLabels.getOrPut(threshold.color) { Labels(measurer, 11.sp, threshold.color) }[threshold.label]
            drawText(l, topLeft = Offset(size.width - l.size.width - 4, (y(threshold.value(trace, i1)) - l.size.height - 1).coerceAtLeast(0f)))
        }
        // 안내 표시
        for (e in events) if (e.kind != TraceKind.SPILL && e.t in t0..t1) {
            drawLine(kindColor(e.kind).copy(alpha = 0.45f), Offset(x(e.t), 0f), Offset(x(e.t), h), 1.2f)
        }
        // 값: 아래 채움 + 빛나는 선
        val base = y(if (0.0 in lo..hi) 0.0 else lo)
        for (sr in series) {
            if (step > 1 && !sr.fill) drawPath(band(sr.value), sr.color.copy(alpha = 0.22f))
            val line = build(sr.value)
            if (sr.fill) {
                fillPath.reset()
                fillPath.addPath(line)
                fillPath.lineTo(x(trace.timeAt(i1)), base)
                fillPath.lineTo(x(trace.timeAt(i0)), base)
                fillPath.close()
                drawPath(fillPath, Brush.verticalGradient(listOf(sr.color.copy(alpha = 0.28f), sr.color.copy(alpha = 0.02f)), startY = 0f, endY = h))
            }
            glowPath(line, sr.color, sr.width)
        }
        // 고른 시각
        val cx = x(cursorAt())
        glowLine(Neon.White, Offset(cx, 0f), Offset(cx, h), 1.5f)
        }
    }
}

/**
 * 흔들림 주기 스펙트럼: 가로 = 주기 (왼쪽 50초 → 오른쪽 1초, 로그), 세로 = 흔들림 세기 (가장 센 것 = 1).
 * 보라 띠 = 멀미 주기(2~10초), 점선 = ISO 2631-1 Wf 가중, 빨간 선 = 멀미에 가장 영향을 준 주기.
 */
@Composable
private fun SpectrumChart(r: ShakeReport, sens: Sensitivity?) {
    val measurer = rememberTextMeasurer()
    val labels = remember(measurer) { Labels(measurer, 11.sp, Neon.Dim) }
    val bandLabel = remember(measurer) { Labels(measurer, 11.sp, Neon.Purple) }
    Canvas(Modifier.fillMaxSize()) {
        val left = 34f
        val bottom = 18f
        val w = size.width - left
        val h = size.height - bottom
        val fLo = ShakeAnalysis.F_MIN
        val fHi = ShakeAnalysis.F_MAX
        fun x(f: Double) = left + (ln(f / fLo) / ln(fHi / fLo) * w).toFloat()
        fun y(v: Double) = (h * (1 - v.coerceIn(0.0, 1.1) / 1.1)).toFloat()
        val bx0 = x(ShakeAnalysis.BAND_LO)
        val bx1 = x(ShakeAnalysis.BAND_HI)
        drawRect(Brush.verticalGradient(listOf(Neon.Purple.copy(alpha = 0.22f), Neon.Purple.copy(alpha = 0.06f))), Offset(bx0, 0f), Size(bx1 - bx0, h))
        val bl = bandLabel["멀미 주기 2~10초"]
        drawText(bl, topLeft = Offset((bx0 + bx1) / 2 - bl.size.width / 2f, 2f))
        for (v in listOf(1.0, 0.5, 0.0)) {
            drawLine(Neon.Grid, Offset(left, y(v)), Offset(size.width, y(v)), 1f, pathEffect = if (v == 0.0) null else PathEffect.dashPathEffect(floatArrayOf(3f, 5f)))
            val l = labels[fmtTick(v)]
            drawText(l, topLeft = Offset(left - l.size.width - 6, (y(v) - l.size.height / 2f).coerceIn(0f, h - l.size.height)))
        }
        for (period in listOf(50.0, 30.0, 20.0, 10.0, 5.0, 2.0, 1.0)) {
            val px = x(1 / period)
            drawLine(Neon.Grid, Offset(px, 0f), Offset(px, h), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f)))
            val l = labels["${period.roundToInt()}초"]
            drawText(l, topLeft = Offset((px - l.size.width / 2f).coerceIn(left, size.width - l.size.width), h + 3))
        }
        val top = max(1e-9, r.freqs.indices.maxOf { max(max(r.longAmp[it], r.latAmp[it]), r.vertAmp[it]) })
        fun path(values: DoubleArray, scale: Double): Path = Path().apply {
            values.forEachIndexed { k, v ->
                val px = x(r.freqs[k])
                val py = y(v / scale)
                if (k == 0) moveTo(px, py) else lineTo(px, py)
            }
        }
        glowPath(path(r.wf, 1.0), Neon.Dim, 1.3f, dash = true)
        // 아이별 가중: 표준 Wf × 민감 주기 강조
        if (sens != null) glowPath(path(DoubleArray(r.freqs.size) { sens.weight(r.freqs[it]) }, sens.gain), Neon.Red.copy(alpha = 0.8f), 1.4f, dash = true)
        for ((amp, c) in listOf(r.longAmp to Neon.Amber, r.latAmp to Neon.Cyan, r.vertAmp to Neon.Green)) {
            val line = path(amp, top)
            val fill = Path().apply { addPath(line); lineTo(x(r.freqs.last()), h); lineTo(x(r.freqs.first()), h); close() }
            drawPath(fill, Brush.verticalGradient(listOf(c.copy(alpha = 0.3f), c.copy(alpha = 0.02f)), startY = 0f, endY = h))
            glowPath(line, c, 1.8f)
        }
        val sx = x(1 / r.sicknessPeriod)
        glowLine(Neon.Red, Offset(sx, 0f), Offset(sx, h), 1.6f)
    }
}

private fun kindColor(k: TraceKind): Color = when (k) {
    TraceKind.SPILL -> Neon.Red
    TraceKind.SAY -> Color(0xFFFFE082)
    TraceKind.CAR -> Neon.Cyan
    TraceKind.SICK -> Neon.Red
}

private fun clock(sec: Double): String {
    val s = sec.coerceAtLeast(0.0).toInt()
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

private fun fmtTick(v: Double): String = if (v == 0.0) "0" else if (abs(v) >= 1 && v % 1.0 == 0.0) fmt(v, 1) else fmt(v, 2)

private fun fmt(v: Double, digits: Int): String {
    var m = 1.0
    repeat(digits) { m *= 10 }
    val r = (abs(v) * m).roundToInt()
    val whole = r / m.toInt()
    val frac = (r % m.toInt()).toString().padStart(digits, '0')
    return (if (v < 0 && r != 0) "-" else "") + if (digits == 0) "$whole" else "$whole.$frac"
}

private fun signed(v: Double, digits: Int): String = (if (v >= 0) "+" else "") + fmt(v, digits)
