package com.sssoft.chalrang.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sssoft.chalrang.core.DriveTrace
import com.sssoft.chalrang.core.TraceEvent
import com.sssoft.chalrang.core.TraceKind
import com.sssoft.chalrang.core.TraceSample
import com.sssoft.chalrang.core.WaterCup
import com.sssoft.chalrang.engine.ChalrangEngine
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

private val Bg = Color(0xFF0E1621)
private val Panel = Color(0xFF17222F)
private val Dim = Color(0xFF9FB0C3)
private val Grid = Color(0x33FFFFFF)
private val LongColor = Color(0xFFFFB74D)   // 앞뒤
private val LatColor = Color(0xFF4DD0E1)    // 좌우
private val SpillColor = Color(0xFFFF7043)
private val DoseColor = Color(0xFFE1BEE7)

/**
 * 흔들림 기록: 차 흔들림(원인)과 물 출렁임(결과)을 같은 시간축에 겹쳐 지연을 보고,
 * 그래프를 누르거나 끌면 그 순간의 물컵을 다시 그린다. 주행 중에는 열지 않는다(시연은 가능).
 */
@Composable
fun TraceScreen(engine: ChalrangEngine, onClose: () -> Unit) {
    val ui by engine.ui.collectAsState()
    val trace = engine.trace
    var version by remember { mutableLongStateOf(trace.version) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            version = trace.version
        }
    }
    var windowSec by remember { mutableStateOf<Double?>(120.0) }
    var cursor by remember { mutableStateOf<Double?>(null) }

    // 아래 물컵 화면으로 누름이 새지 않게 막는다
    Box(Modifier.fillMaxSize().background(Bg).pointerInput(Unit) { detectTapGestures { } }.padding(20.dp)) {
        val last = if (trace.size > 0) trace[trace.size - 1] else null
        val driving = ui.demo == null && (last?.speedKmh ?: 0.0) > 5
        if (driving) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("주행 중에는 기록을 볼 수 없어요", color = Color.White, fontSize = 24.sp)
                Text("차를 세운 뒤 다시 열어 주세요", color = Dim, fontSize = 17.sp)
                OutlinedButton(onClick = onClose, modifier = Modifier.padding(top = 16.dp)) { Text("닫기") }
            }
            return@Box
        }

        // version 이 바뀔 때마다 다시 계산
        val t1 = remember(version) { trace.endTime }
        val t0 = remember(version, windowSec) { windowSec?.let { max(trace.startTime, t1 - it) } ?: trace.startTime }
        val lag = remember(version, windowSec) { trace.lag(t0, t1) }
        val events = remember(version, windowSec) { trace.events.filter { it.t in t0..t1 } }
        val at = cursor?.coerceIn(t0, t1) ?: t1
        val sample = remember(version, at) { trace.indexAt(at).takeIf { it >= 0 }?.let { trace[it] } }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 900.dp
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Header(windowSec, onWindow = { windowSec = it; cursor = null }, following = cursor == null, onLive = { cursor = null }, onClose = onClose)
                if (trace.size < 2) {
                    Text("아직 기록이 없어요. 주행하거나 시연을 돌려 보세요.", color = Dim, fontSize = 18.sp)
                    return@Column
                }
                val charts: @Composable (Modifier) -> Unit = { m ->
                    Charts(trace, version, t0, t1, at, events, onCursor = { cursor = it }, modifier = m)
                }
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Replay(sample, Modifier.fillMaxWidth().height(230.dp))
                            Values(sample, trace.startTime)
                            LagText(lag)
                            EventList(events, trace.startTime, onPick = { cursor = it }, modifier = Modifier.weight(1f))
                        }
                        charts(Modifier.weight(1f))
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Replay(sample, Modifier.width(150.dp).height(150.dp))
                        Column(Modifier.weight(1f)) {
                            Values(sample, trace.startTime)
                            LagText(lag)
                        }
                    }
                    charts(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Header(windowSec: Double?, onWindow: (Double?) -> Unit, following: Boolean, onLive: () -> Unit, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("흔들림 기록", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
        listOf(60.0 to "1분", 120.0 to "2분", 300.0 to "5분", null to "전체").forEach { (sec, label) ->
            FilterChip(selected = windowSec == sec, onClick = { onWindow(sec) }, label = { Text(label) })
        }
        FilterChip(selected = following, onClick = onLive, label = { Text("지금") })
        Box(Modifier.weight(1f))
        OutlinedButton(onClick = onClose) { Text("닫기") }
    }
}

/** 고른 순간의 물컵 (3D) */
@Composable
private fun Replay(s: TraceSample?, modifier: Modifier) {
    Box(modifier.background(Panel, RoundedCornerShape(14.dp))) {
        if (s != null) CupView(s.tiltX, s.tiltY, s.level, s.dose, s.spilling, Modifier.fillMaxSize().padding(8.dp))
    }
}

@Composable
private fun Values(s: TraceSample?, start: Double) {
    if (s == null) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("${clock(s.t - start)} · ${s.speedKmh.roundToInt()}km/h", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("차 흔들림  앞뒤 ${signed(s.ax)} · 좌우 ${signed(s.ay)} m/s²", color = Dim, fontSize = 14.sp)
        val tilt = hypot(s.tiltX, s.tiltY)
        Text(
            "물 기울기 ${fmt(tilt, 2)}g / 넘침 ${fmt(s.freeboard, 2)}g" + if (s.spilling) "  넘침!" else "",
            color = if (s.spilling) SpillColor else Dim, fontSize = 14.sp,
        )
        Text("멀미 누적 ${fmt(s.dose, 2)}  (주의 0.6 · 위험 1.0)", color = Dim, fontSize = 14.sp)
    }
}

@Composable
private fun LagText(lag: com.sssoft.chalrang.core.Lag?) {
    val text = if (lag == null) {
        "기록이 더 쌓이면 지연을 계산해요"
    } else {
        "물은 차 흔들림보다 약 ${fmt(lag.lagSec, 1)}초 늦게 따라와요 (상관 ${fmt(lag.correlation, 2)})"
    }
    Text(text, color = Color.White, fontSize = 15.sp, lineHeight = 21.sp)
    Text(
        "물컵은 2~10초 주기 흔들림에 가장 크게 출렁여요. 멀미를 가장 잘 일으키는 흔들림이에요 (ISO 2631-1).",
        color = Dim, fontSize = 12.sp, lineHeight = 17.sp,
    )
}

@Composable
private fun EventList(events: List<TraceEvent>, start: Double, onPick: (Double) -> Unit, modifier: Modifier) {
    val shown = events.filter { it.kind != TraceKind.SPILL }.asReversed()
    LazyColumn(modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(8.dp)) {
        items(shown) { e ->
            Row(Modifier.fillMaxWidth().clickable { onPick(e.t) }.padding(vertical = 4.dp)) {
                Text(clock(e.t - start), color = Dim, fontSize = 13.sp, modifier = Modifier.width(48.dp))
                Text("${e.kind.label} · ${e.text}", color = kindColor(e.kind), fontSize = 13.sp, lineHeight = 17.sp)
            }
        }
    }
}

/** 세 그래프: 차 흔들림(원인), 물 출렁임(결과), 멀미 누적량 */
@Composable
private fun Charts(
    trace: DriveTrace, version: Long, t0: Double, t1: Double, at: Double,
    events: List<TraceEvent>, onCursor: (Double) -> Unit, modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Legend("차 흔들림 (원인) m/s²", listOf("앞뒤" to LongColor, "좌우" to LatColor))
        Chart(
            trace, version, t0, t1, at, events, onCursor, Modifier.weight(1f),
            series = listOf(Series(LongColor) { it.ax }, Series(LatColor) { it.ay }),
            range = 5.0, guides = listOf(0.0),
        )
        Legend("물 출렁임 (결과) g · 차 흔들림과 같은 방향 · 빨간 띠 = 넘침", listOf("앞뒤" to LongColor, "좌우" to LatColor, "크기" to Color.White))
        Chart(
            trace, version, t0, t1, at, events, onCursor, Modifier.weight(1f),
            series = listOf(
                Series(LongColor) { -it.tiltX },
                Series(LatColor) { -it.tiltY },
                Series(Color.White, width = 1.2f) { hypot(it.tiltX, it.tiltY) },
            ),
            range = WaterCup.MAX_FREEBOARD * 0.9, guides = listOf(0.0), threshold = { it.freeboard }, shadeSpill = true,
        )
        Legend("멀미 누적량", listOf("누적" to DoseColor))
        Chart(
            trace, version, t0, t1, at, events, onCursor, Modifier.weight(0.7f),
            series = listOf(Series(DoseColor, width = 2.5f) { it.dose }),
            range = null, guides = listOf(0.6, 1.0),
        )
    }
}

@Composable
private fun Legend(title: String, items: List<Pair<String, Color>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Dim, fontSize = 13.sp)
        items.forEach { (label, c) ->
            Box(Modifier.width(14.dp).height(3.dp).background(c))
            Text(label, color = Dim, fontSize = 12.sp)
        }
    }
}

private class Series(val color: Color, val width: Float = 1.8f, val value: (TraceSample) -> Double)

/**
 * 시간축 그래프. [range] 가 있으면 ±range, 없으면 0 ~ max(1.2, 최대값).
 * 누르거나 가로로 끌면 [onCursor] 로 그 시각을 알린다.
 */
@Composable
private fun Chart(
    trace: DriveTrace, version: Long, t0: Double, t1: Double, at: Double,
    events: List<TraceEvent>, onCursor: (Double) -> Unit, modifier: Modifier,
    series: List<Series>, range: Double?, guides: List<Double>,
    threshold: ((TraceSample) -> Double)? = null, shadeSpill: Boolean = false,
) {
    val span = (t1 - t0).coerceAtLeast(1e-6)
    Canvas(
        modifier
            .fillMaxWidth()
            .background(Panel, RoundedCornerShape(10.dp))
            .pointerInput(t0, t1) { detectTapGestures { o -> onCursor(t0 + span * (o.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(t0, t1) {
                detectHorizontalDragGestures { change, _ -> onCursor(t0 + span * (change.position.x / size.width).coerceIn(0f, 1f)) }
            },
    ) {
        val i0 = trace.indexAt(t0).coerceAtLeast(0)
        val i1 = trace.indexAt(t1)
        if (i1 <= i0) return@Canvas
        val step = max(1, (i1 - i0) / max(1, size.width.toInt()))
        val samples = (i0..i1 step step).map { trace[it] }
        val (lo, hi) = if (range != null) -range to range else 0.0 to max(1.2, samples.maxOf { s -> series.maxOf { it.value(s) } } * 1.1)
        fun x(t: Double) = ((t - t0) / span * size.width).toFloat()
        fun y(v: Double) = (size.height * (1 - (v.coerceIn(lo, hi) - lo) / (hi - lo))).toFloat()

        // 넘친 구간
        if (shadeSpill) {
            for (s in samples) if (s.spilling) {
                drawRect(SpillColor.copy(alpha = 0.22f), topLeft = Offset(x(s.t) - 1, 0f), size = androidx.compose.ui.geometry.Size(max(2f, size.width / samples.size + 1), size.height))
            }
        }
        // 기준선
        for (g in guides) drawLine(Grid, Offset(0f, y(g)), Offset(size.width, y(g)), strokeWidth = 1f, pathEffect = if (g == 0.0) null else PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        if (threshold != null) {
            val p = Path()
            samples.forEachIndexed { k, s -> val o = Offset(x(s.t), y(threshold(s))); if (k == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
            drawPath(p, SpillColor, style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))
        }
        // 사건
        for (e in events) if (e.kind != TraceKind.SPILL) {
            drawLine(kindColor(e.kind).copy(alpha = 0.6f), Offset(x(e.t), 0f), Offset(x(e.t), size.height), strokeWidth = 1.5f)
        }
        // 값
        for (sr in series) {
            val p = Path()
            samples.forEachIndexed { k, s -> val o = Offset(x(s.t), y(sr.value(s))); if (k == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
            drawPath(p, sr.color, style = Stroke(sr.width))
        }
        // 고른 시각
        drawCursor(x(at))
    }
}

private fun DrawScope.drawCursor(x: Float) {
    drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
}

private fun kindColor(k: TraceKind): Color = when (k) {
    TraceKind.SPILL -> SpillColor
    TraceKind.SAY -> Color(0xFFFFF59D)
    TraceKind.CAR -> Color(0xFF81D4FA)
    TraceKind.SICK -> Color(0xFFEF5350)
}

private fun clock(sec: Double): String {
    val s = sec.coerceAtLeast(0.0).toInt()
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

private fun fmt(v: Double, digits: Int): String {
    var m = 1.0
    repeat(digits) { m *= 10 }
    val r = (abs(v) * m).roundToInt()
    val whole = r / m.toInt()
    val frac = (r % m.toInt()).toString().padStart(digits, '0')
    return (if (v < 0 && r != 0) "-" else "") + if (digits == 0) "$whole" else "$whole.$frac"
}

private fun signed(v: Double): String = (if (v >= 0) "+" else "") + fmt(v, 1)
