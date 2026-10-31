package com.sssoft.chalrang.ui

import com.sssoft.chalrang.engine.DemoRoute
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Masks
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.sssoft.chalrang.core.Window as CarWindow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Route
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sssoft.chalrang.core.Child
import com.sssoft.chalrang.core.DriverStyle
import com.sssoft.chalrang.core.Seat
import com.sssoft.chalrang.core.Window
import com.sssoft.chalrang.engine.ChalrangEngine
import com.sssoft.chalrang.engine.UiState

private val Bg = Color(0xFF0E1621)
private val Panel = Color(0xFF17222F)
private val Dim = Color(0xFF9FB0C3)
private val Sick = Color(0xFFE5533D)

/**
 * 찰랑 화면. 차량 화면에는 앱이 하나(가로로 넓음) 또는 둘(나란히, 세로로 김)까지 뜨므로
 * 넓으면 물컵 | 정보 좌우 배치, 좁거나 세로로 길면 물컵 위·정보 아래로 바꾼다.
 */
@Composable
fun MainScreen(vm: ChalrangEngine) {
    val s by vm.ui.collectAsState()
    var tracing by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }

    NeonBackground {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_MIN && maxWidth > maxHeight
        val sideWidth = minOf(400.dp, maxWidth * 0.38f)
        val brief = s.briefing
        if (brief != null) {
            // 3D 경로 브리핑 (설정에서 켜면 길 안내·시연이 시작될 때)
            BriefingScreen(brief, speak = vm::briefSay, onClose = vm::endBriefing, detail = { seg -> vm.briefDetail(brief, seg) }, fine = { vm.briefFine(brief) })
        } else if (settingsOpen) {
            SettingsScreen(vm, onClose = { settingsOpen = false })
        } else if (tracing) {
            // 기록 화면이 덮는 동안 아래 물컵 화면은 그리지 않는다 (차량 화면 성능)
            TraceScreen(vm, onClose = { tracing = false })
        } else if (wide) {
            Row(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Cup(s, Modifier.weight(1f).fillMaxHeight(), onTrace = { tracing = true }, onAdjust = vm::adjustWater)
                Column(
                    Modifier.width(sideWidth).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RidersCard(s)
                    CarCard(s)
                    RouteCard(s, onBrief = vm::startBriefing)
                    Said(s)
                    Spacer(Modifier.weight(1f))
                    Controls(s, vm, onEdit = { settingsOpen = true })
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Cup(s, Modifier.weight(1f).fillMaxWidth(), onTrace = { tracing = true }, onAdjust = vm::adjustWater)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                    RidersCard(s, Modifier.weight(1f).fillMaxHeight())
                    CarCard(s, Modifier.weight(1f).fillMaxHeight())
                }
                RouteCard(s, onBrief = vm::startBriefing)
                Said(s)
                Controls(s, vm, onEdit = { settingsOpen = true })
            }
        }
        // 주행이 끝나면 평가를 덮어 보여 준다
        if (!settingsOpen && brief == null) s.report?.let { TripReportOverlay(it, s.previousScore, onClose = vm::dismissReport) }
    }
    }
}

/** 이보다 넓은 가로 화면이면 좌우 배치 */
private val WIDE_MIN = 900.dp

@Composable
private fun Cup(s: UiState, modifier: Modifier, onTrace: () -> Unit, onAdjust: (Double) -> Unit) = BoxWithConstraints(modifier.neonCard().clip(Neon.CardShape)) {
    // 은은한 빛과 옅은 HUD 격자 (고리·그림자는 CupView 가 같은 3D 바닥에 그린다).
    // 따로 층(graphicsLayer)에 그려 두어 물이 움직일 때 다시 그리지 않는다
    Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawHud() }
    CupView(s.tiltX - s.gradeX, s.tiltY - s.gradeY, s.level, s.dose, s.spilling, Modifier.fillMaxSize().padding(top = 40.dp, bottom = 8.dp), stage = true, heave = s.tiltZ, freeboard = s.freeboard)
    // 컵 옆에 자리가 있을 때만 (세로로 긴 카드는 컵이 커져 눈금자와 겹친다)
    if (maxWidth >= 440.dp && maxWidth >= maxHeight * 0.95f) MlRuler(s.level, Modifier.align(Alignment.CenterStart).padding(start = 18.dp).fillMaxHeight(0.56f))
    WaterButtons(s, onAdjust, Modifier.align(Alignment.CenterEnd).padding(end = 16.dp))
    Row(Modifier.align(Alignment.TopStart).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.WaterDrop, null, tint = Neon.Cyan, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(8.dp))
        Text("찰랑", color = Neon.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    }
    NeonChip("흔들림 분석", false, onClick = onTrace, icon = Icons.Filled.ShowChart, chevron = true, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp))
}

/** 물컵 카드 바탕: 가운데 푸른 빛, 옅은 격자와 교차점의 점, 컵을 감싸는 큰 호 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHud() {
    val w = size.width
    val h = size.height
    drawRect(Brush.radialGradient(listOf(Color(0x3329C4F6), Color.Transparent), center = Offset(w * 0.5f, h * 0.62f), radius = size.minDimension * 0.7f))
    val step = 44.dp.toPx()
    val line = Color(0x0F4FA3E0)
    var x = (w / 2) % step
    while (x < w) { drawLine(line, Offset(x, 0f), Offset(x, h), 1f); x += step }
    var y = (h * 0.62f) % step
    while (y < h) { drawLine(line, Offset(0f, y), Offset(w, y), 1f); y += step }
    // 교차점마다 작은 점 (네 칸에 하나는 조금 밝게)
    x = (w / 2) % step
    var i = 0
    while (x < w) {
        y = (h * 0.62f) % step
        var j = 0
        while (y < h) {
            drawCircle(if ((i + j) % 4 == 0) Color(0x5529C4F6) else Color(0x224FA3E0), if ((i + j) % 4 == 0) 1.6f else 1.1f, Offset(x, y))
            y += step; j++
        }
        x += step; i++
    }
    // 컵을 감싸는 큰 호 (HUD 테두리)
    val r = size.minDimension * 0.47f
    val c = Offset(w / 2, h * 0.52f)
    drawArc(Color(0x2229C4F6), 200f, 140f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(1.2f))
    drawArc(Color(0x1429C4F6), 20f, 140f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(1.2f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 8f))))
}

/** 눈금자 숫자 한 칸 높이 (13sp 글자가 잘리지 않게) */
private val LABEL_H = 22.dp

/** 왼쪽 ml 눈금자 (0~250ml, 50 마다 숫자). 화살표 = 지금 물 높이 */
@Composable
private fun MlRuler(level: Double, modifier: Modifier) {
    val arrow = remember { Path() }
    Row(modifier.width(72.dp)) {
        // 숫자 칸(높이 LABEL_H) 가운데에 눈금이 오도록 위아래를 반 칸씩 띄운다
        Canvas(Modifier.width(30.dp).fillMaxHeight().padding(vertical = LABEL_H / 2)) {
            val x0 = size.width - 2f
            drawLine(Color(0x664FA3E0), Offset(x0, 0f), Offset(x0, size.height), 1.5f)
            for (k in 0..25) {
                val y = size.height * (1 - k / 25f)
                val major = k % 5 == 0
                val len = if (major) 16.dp.toPx() else 7.dp.toPx()
                drawLine(if (major) Color(0xB34FB8F0) else Color(0x554FA3E0), Offset(x0 - len, y), Offset(x0, y), if (major) 1.6f else 1f)
            }
            // 지금 물 높이: 빛나는 삼각형
            val ly = size.height * (1 - level.toFloat().coerceIn(0f, 1f))
            val t = 6.dp.toPx()
            arrow.reset()
            arrow.moveTo(0f, ly - t)
            arrow.lineTo(t * 1.3f, ly)
            arrow.lineTo(0f, ly + t)
            arrow.close()
            drawCircle(Neon.Cyan.copy(alpha = 0.18f), t * 1.6f, Offset(t * 0.5f, ly))
            drawPath(arrow, Neon.Cyan)
            drawLine(Neon.Cyan.copy(alpha = 0.6f), Offset(t * 1.6f, ly), Offset(x0, ly), 1.5f, cap = StrokeCap.Round)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            for (label in listOf("250", "200", "150", "100", "50", "ml")) {
                Box(Modifier.height(LABEL_H), contentAlignment = Alignment.CenterStart) {
                    Text(label, color = Neon.Dim, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun Said(s: UiState) {
    s.lastSaid?.let {
        Row(
            Modifier.fillMaxWidth().neonCard().padding(start = 14.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = Neon.Cyan, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text(it, color = Neon.Text, fontSize = 16.sp, lineHeight = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Chevron()
        }
    }
}

@Composable
private fun Chevron(tint: Color = Neon.Dim) = Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = tint, modifier = Modifier.size(26.dp))

/** 창문 질문, "지금 멀미", 아이 설정·시연 */
@Composable
private fun Controls(s: UiState, vm: ChalrangEngine, onEdit: () -> Unit) {
    s.askWindows?.let { AskWindowCard(it, vm) }
    // "지금 멀미": 빛나는 빨간 버튼 (혼자 주행이면 꺼짐)
    val on = s.setup.active
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.fillMaxWidth().height(72.dp)
            .drawBehind {
                if (on) {
                    for ((w, a) in listOf(22f to 0.06f, 12f to 0.12f, 5f to 0.22f)) {
                        drawRoundRect(Neon.Red.copy(alpha = a), Offset(-w / 2, -w / 2), Size(size.width + w, size.height + w), CornerRadius(20.dp.toPx() + w / 2), style = Stroke(w))
                    }
                }
            }
            .clip(shape)
            .background(if (on) SickFill else Brush.verticalGradient(listOf(Color(0xFF243246), Color(0xFF1A2536))), shape)
            .border(1.5.dp, if (on) Color(0xFFFFA19A) else Neon.Edge.copy(alpha = 0.3f), shape)
            .clickable(enabled = on, onClick = vm::sickNow),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = if (on) Color.White else Neon.Faint, modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(14.dp))
            Text("지금 멀미", color = if (on) Color.White else Neon.Faint, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 14.dp)) { Chevron(if (on) Color.White else Neon.Faint) }
    }
    DemoRow(s, vm, onEdit)
}

private val SickFill = Brush.verticalGradient(listOf(Color(0xFFFF5A55), Color(0xFFE0283A), Color(0xFFB8142C)))

/** 카드: 아이콘·제목(옅게) / 본문(크게), 오른쪽 끝 ">" 와 그림 자리 [side] */
@Composable
private fun Card(
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    title: String? = null,
    chevron: Boolean = true,
    side: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Row(modifier.fillMaxWidth().neonCard().padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Icon(icon, null, tint = Neon.Cyan, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(title, color = Neon.Dim, fontSize = 15.sp)
                }
            }
            content()
        }
        if (side != null) {
            Spacer(Modifier.width(8.dp))
            side()
        }
        if (chevron) Chevron()
    }
}

@Composable
private fun RidersCard(s: UiState, modifier: Modifier = Modifier) = Card(
    modifier, Icons.Filled.Person, "탄 사람",
    side = if (s.setup.active) ({ SeatMap(s.setup.children.map { it.seat }, s.setup.guests, s.car.openWindows) }) else null,
) {
    val setup = s.setup
    when {
        !setup.active -> Text("혼자 주행 · 물컵은 쉬는 중", color = Color.White, fontSize = 20.sp)
        else -> {
            setup.children.forEach { c ->
                Text("${c.name} · ${c.seat.label}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
            setup.guests.forEach { g -> Text("동승자 · ${g.label}", color = Color.White, fontSize = 20.sp) }
        }
    }
    if (s.assumedSeat) Text("시연: 좌석이 비어 있어 첫 아이가 탄 것으로 봐요", color = Dim, fontSize = 13.sp)
}

/**
 * 위에서 내려다본 좌석 배치: 아이 자리는 밝은 하늘색, 동승자는 옅게, 운전석은 회색.
 * 열어 둔 창문은 차 옆에 하늘색 막대 (길이 = 열린 정도).
 */
@Composable
private fun SeatMap(kids: List<Seat>, guests: List<Seat>, open: Map<CarWindow, Int>, modifier: Modifier = Modifier) {
    Canvas(modifier.size(42.dp, 62.dp)) {
        val w = size.width
        val h = size.height
        val body = Size(w * 0.78f, h)
        val bx = (w - body.width) / 2
        drawRoundRect(Color(0x33102A4A), Offset(bx, 0f), body, CornerRadius(w * 0.26f))
        drawRoundRect(Neon.Edge.copy(alpha = 0.7f), Offset(bx, 0f), body, CornerRadius(w * 0.26f), style = Stroke(1.5f))
        // 앞유리
        drawLine(Neon.Edge.copy(alpha = 0.5f), Offset(bx + body.width * 0.18f, h * 0.2f), Offset(bx + body.width * 0.82f, h * 0.2f), 1.5f, cap = StrokeCap.Round)
        val sw = body.width * 0.25f
        val sh = h * 0.17f
        fun seat(cx: Float, cy: Float, c: Color, glow: Boolean) {
            if (glow) drawCircle(c.copy(alpha = 0.25f), sw * 1.1f, Offset(cx, cy))
            drawRoundRect(c, Offset(cx - sw / 2, cy - sh / 2), Size(sw, sh), CornerRadius(sw * 0.3f))
        }
        val left = bx + body.width * 0.28f
        val mid = bx + body.width * 0.5f
        val right = bx + body.width * 0.72f
        val front = h * 0.36f
        val rear = h * 0.68f
        fun color(seat: Seat?) = when {
            seat != null && seat in kids -> Neon.Cyan
            seat != null && seat in guests -> Neon.Cyan.copy(alpha = 0.45f)
            else -> Color(0x553D5677)
        }
        seat(left, front, Color(0x887A8CA8), false)
        seat(right, front, color(Seat.FRONT_RIGHT), Seat.FRONT_RIGHT in kids)
        seat(left, rear, color(Seat.REAR_LEFT), Seat.REAR_LEFT in kids)
        if (Seat.REAR_CENTER in kids || Seat.REAR_CENTER in guests) seat(mid, rear, color(Seat.REAR_CENTER), Seat.REAR_CENTER in kids)
        seat(right, rear, color(Seat.REAR_RIGHT), Seat.REAR_RIGHT in kids)
        // 열린 창문
        for ((win, p) in open) {
            val x = if (win == CarWindow.REAR_LEFT) bx - 3f else bx + body.width + 3f
            val cy = if (win == CarWindow.FRONT_RIGHT) front else rear
            val len = h * 0.2f * (p / 100f).coerceIn(0.15f, 1f)
            drawLine(Neon.Cyan.copy(alpha = 0.3f), Offset(x, cy - len / 2), Offset(x, cy + len / 2), 6f, cap = StrokeCap.Round)
            drawLine(Neon.Cyan, Offset(x, cy - len / 2), Offset(x, cy + len / 2), 2.5f, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun CarCard(s: UiState, modifier: Modifier = Modifier) = Card(modifier, Icons.Filled.DirectionsCar, "차량 공조") {
    val car = s.car
    if (car.connected) {
        // 찰랑이 차에 하는 조치: 평소(손대지 않음) / 환기 / 먼지 차단 중 켜진 것이 빛난다
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AirMode(Icons.Filled.CheckCircle, "평소대로", !car.dustGuard && !car.freshAir, Neon.Cyan)
            AirMode(Icons.Filled.Air, "환기 중", car.freshAir && !car.dustGuard, Neon.Cyan)
            AirMode(Icons.Filled.Masks, "먼지 차단", car.dustGuard, Neon.Amber)
        }
    } else {
        Text("차량 연결 안 됨", color = Color.White, fontSize = 20.sp)
    }
    if (car.openWindows.isNotEmpty()) {
        Text(car.openWindows.entries.joinToString { (w, p) -> "${w.label} 창문 $p%" }, color = Color.White, fontSize = 17.sp)
    }
    if (s.raining) Text("비 · 창문은 닫아 둬요", color = Dim, fontSize = 15.sp)
    car.lastAction?.let { Text(it, color = Dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** 공조 상태 하나: 동그란 아이콘 + 이름. 켜진 것만 빛나고 나머지는 흐리게 */
@Composable
private fun AirMode(icon: ImageVector, label: String, on: Boolean, color: Color) {
    Column(Modifier.width(62.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(34.dp)
                .drawBehind { if (on) drawCircle(color.copy(alpha = 0.22f), size.minDimension * 0.62f) }
                .clip(CircleShape)
                .background(if (on) color.copy(alpha = 0.2f) else Color(0x33102A4A))
                .border(1.5.dp, if (on) color else Neon.Edge.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (on) color else Neon.Faint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(label, color = if (on) Neon.White else Neon.Faint, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else null, maxLines = 1)
    }
}

/**
 * 물 양: 평소 운전 습관만큼 채워져 있고, 출발 전에 더 채우거나(도전) 덜어 낼 수 있다.
 * 물이 많을수록 작은 쏠림에도 넘친다 — 가득 채우고도 지키면 젠틀 운전.
 */
@Composable
private fun WaterButtons(s: UiState, onAdjust: (Double) -> Unit, modifier: Modifier) {
    val diff = s.level - s.habitWater
    val pill = RoundedCornerShape(44.dp)
    Column(
        modifier.width(88.dp)
            .background(Brush.verticalGradient(listOf(Color(0xB30A1B33), Color(0xB3050D1A))), pill)
            .border(1.dp, Brush.verticalGradient(listOf(Neon.Edge.copy(alpha = 0.6f), Neon.Edge.copy(alpha = 0.15f), Neon.Edge.copy(alpha = 0.6f))), pill)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RoundButton(Icons.Filled.Add, s.canAdjust) { onAdjust(ChalrangEngine.WATER_STEP) }
        Spacer(Modifier.height(4.dp))
        Text(
            buildAnnotatedString {
                append("${(s.level * 100).roundToInt()}")
                withStyle(SpanStyle(fontSize = 17.sp)) { append("%") }
            },
            color = Neon.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        )
        Text(
            when {
                !s.canAdjust && s.level < s.startWater - 0.005 -> "넘쳐서 -${((s.startWater - s.level) * 100).roundToInt()}%"
                diff > 0.005 -> "도전 +${(diff * 100).roundToInt()}%"
                diff < -0.005 -> "덜어 냄"
                s.habitScore != null -> "평소 ${s.habitScore}점"
                else -> "기본"
            },
            color = when { diff > 0.005 -> Neon.Amber; !s.canAdjust && s.level < s.startWater - 0.005 -> Neon.Red; else -> Neon.Dim },
            fontSize = 13.sp, maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        RoundButton(Icons.Filled.Remove, s.canAdjust) { onAdjust(-ChalrangEngine.WATER_STEP) }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp)
            .drawBehind { if (enabled) drawCircle(Neon.Cyan.copy(alpha = 0.10f), size.minDimension * 0.58f) }
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color(0xFF0E2340), Color(0xFF06111F))))
            .border(1.5.dp, if (enabled) Neon.Cyan.copy(alpha = 0.85f) else Neon.Edge.copy(alpha = 0.3f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) Neon.White else Neon.Faint, modifier = Modifier.size(28.dp))
    }
}

private val CautionBar = Brush.horizontalGradient(listOf(Color(0xFFFF9A3C), Color(0xFFFF5A4A)))
private val DangerBar = Brush.horizontalGradient(listOf(Color(0xFFFF4D5E), Color(0xFFFF4FA0)))

/** 가는 길: 멀미 위험 구간 요약과 경로 띠 (주황 = 조심, 빨강·분홍 = 위험, 빛나는 점 = 지금) */
@Composable
private fun RouteCard(s: UiState, onBrief: () -> Unit, modifier: Modifier = Modifier) {
    val r = s.route ?: return
    Card(modifier, Icons.Filled.Route, "가는 길", chevron = false) {
        Text(r.summary, color = Color.White, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Canvas(Modifier.fillMaxWidth().height(26.dp)) {
            val y = size.height / 2
            val h = 7.dp.toPx()
            val total = r.totalM.coerceAtLeast(1.0)
            fun x(m: Double) = (m / total * size.width).toFloat().coerceIn(0f, size.width)
            drawRoundRect(Color(0x33233A5C), Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2))
            val cx = x(s.routeAtM)
            // 지나온 길: 옅은 파랑
            if (cx > 0f) drawRoundRect(Neon.Blue.copy(alpha = 0.35f), Offset(0f, y - h / 2), Size(cx, h), CornerRadius(h / 2))
            // 멀미 위험 구간: 조심 = 주황 → 빨강, 위험 = 빨강 → 분홍 (구간마다 그라디언트)
            for (seg in r.segments) {
                drawRoundRect(if (seg.level >= 2) DangerBar else CautionBar, Offset(x(seg.startM), y - h / 2), Size((x(seg.endM) - x(seg.startM)).coerceAtLeast(3f), h), CornerRadius(h / 2))
            }
            for (rest in r.restAreaM) drawCircle(Neon.Green, 3.dp.toPx(), Offset(x(rest), y))
            // 지금 위치: 빛나는 둥근 손잡이
            drawCircle(Neon.Cyan.copy(alpha = 0.18f), 13.dp.toPx(), Offset(cx, y))
            drawCircle(Neon.Cyan.copy(alpha = 0.35f), 9.5.dp.toPx(), Offset(cx, y))
            drawCircle(Neon.Blue, 8.dp.toPx(), Offset(cx, y))
            drawCircle(Neon.White, 5.dp.toPx(), Offset(cx, y))
        }
        r.restAdvice?.let { Text(it, color = Neon.Green, fontSize = 13.sp) }
        NeonChip("3D 경로 브리핑", false, onClick = onBrief, icon = Icons.Filled.Route, chevron = true)
    }
}

/** 창문을 열지 묻기. 운전 중에도 누르기 쉽게 크게, 동승자가 눌러도 된다 */
@Composable
private fun AskWindowCard(windows: List<Window>, vm: ChalrangEngine) = Card(icon = Icons.Filled.Air, title = "창문", chevron = false) {
    Text(windows.joinToString { it.label } + " 창문을 조금 열까요?", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BigChoice("열기", primary = true, modifier = Modifier.weight(1f)) { vm.answerWindow(true) }
        BigChoice("먼지 · 닫기", primary = false, modifier = Modifier.weight(1f)) { vm.answerWindow(false) }
    }
}

@Composable
private fun BigChoice(label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.height(60.dp).clip(shape)
            .background(if (primary) Brush.verticalGradient(listOf(Color(0xFF2A86F0), Color(0xFF1250B0))) else Brush.verticalGradient(listOf(Color(0xCC0B1D36), Color(0xCC06111F))), shape)
            .border(1.5.dp, if (primary) Neon.Cyan else Neon.Edge.copy(alpha = 0.5f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Neon.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun DemoRow(s: UiState, vm: ChalrangEngine, onEdit: () -> Unit) {
    if (s.remote) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PhoneAndroid, null, tint = Neon.Cyan, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            val text = s.localMount?.let { m -> "이 기기 센서로 흔들림 받는 중" + (if (m.isNotEmpty()) " ($m)" else "") + " · 2~10초에 한 번씩 앞뒤·좌우로 움직여 보세요" }
                ?: "휴대폰 시연 중 · 휴대폰을 2~10초에 한 번씩 앞뒤·좌우로 움직여 보세요"
            Text(text, color = Neon.Cyan, fontSize = 14.sp, lineHeight = 19.sp)
        }
    }
    if (s.demo != null) {
        Text("시연 · ${s.demo}", color = Neon.Dim, fontSize = 14.sp)
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x332A8BE0))) {
            Box(Modifier.fillMaxWidth(s.demoProgress.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Brush.horizontalGradient(listOf(Neon.Blue, Neon.Cyan))))
        }
    }
    // 시연 길 고르기 (실제 지역: 대관령·한계령·서울 도심·고속도로, 또는 만든 길)
    var choosing by remember { mutableStateOf(false) }
    if (s.demo == null) {
        NeonChip("시연 길 · ${DemoRoute.of(s.settings.demoRoute).short}", false, onClick = { choosing = true }, icon = Icons.Filled.Route, modifier = Modifier.fillMaxWidth())
    }
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("시연 길") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DemoRoute.entries.forEach { r ->
                        NeonChip(r.label, r.id == s.settings.demoRoute, onClick = { vm.chooseDemoRoute(r); choosing = false }, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text("닫기") } },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        NeonChip("설정", false, onClick = onEdit, icon = Icons.Filled.Settings, modifier = Modifier.weight(1f).height(44.dp))
        if (s.demo == null) {
            NeonChip("시연 거칠게", false, onClick = { vm.startDemo(DriverStyle.ROUGH) }, modifier = Modifier.weight(1f).height(44.dp))
            NeonChip("시연 부드럽게", false, onClick = { vm.startDemo(DriverStyle.SMOOTH) }, modifier = Modifier.weight(1f).height(44.dp))
        } else {
            NeonChip("시연 끝", true, onClick = vm::stopDemo, icon = Icons.Filled.Stop, modifier = Modifier.weight(1.6f).height(44.dp))
        }
    }
}

@Composable
internal fun ChildrenDialog(children: List<Child>, vm: ChalrangEngine, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var seat by remember { mutableStateOf(Seat.REAR_LEFT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("아이 설정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                children.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${c.name} · ${c.seat.label}", fontSize = 18.sp)
                            Text(
                                when {
                                    c.calibrations == 0 -> "처음 기준 · 멀미하면 '지금 멀미'를 눌러 주세요"
                                    c.sensitivity.active -> "'지금 멀미'로 ${c.calibrations}번 맞춤 · ${(c.sensitivity.peakPeriod * 10).toInt() / 10.0}초 흔들림에 약함"
                                    else -> "'지금 멀미'로 ${c.calibrations}번 맞춤 · 멀미 패턴 학습 ${c.sensitivity.count}/${com.sssoft.chalrang.core.Sensitivity.MIN_EVENTS}"
                                },
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { vm.removeChild(c) }) { Text("삭제") }
                    }
                }
                Text("아이 추가 · 자리를 정하면 그 좌석에 탈 때 자동으로 맞춰요", fontSize = 14.sp)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("이름") }, singleLine = true)
                SeatPicker(seat) { seat = it }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { vm.addChild(name, seat); name = "" }) { Text("추가") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SeatPicker(selected: Seat, onSelect: (Seat) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Seat.entries.forEach { s ->
            FilterChip(selected = s == selected, onClick = { onSelect(s) }, label = { Text(s.label) })
        }
    }
}
