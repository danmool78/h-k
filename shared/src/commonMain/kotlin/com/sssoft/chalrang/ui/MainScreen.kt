package com.sssoft.chalrang.ui

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
    var editing by remember { mutableStateOf(false) }
    var tracing by remember { mutableStateOf(false) }

    NeonBackground {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_MIN && maxWidth > maxHeight
        val sideWidth = minOf(380.dp, maxWidth * 0.38f)
        if (tracing) {
            // 기록 화면이 덮는 동안 아래 물컵 화면은 그리지 않는다 (차량 화면 성능)
            TraceScreen(vm, onClose = { tracing = false })
        } else if (wide) {
            Row(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Cup(s, Modifier.weight(1f).fillMaxHeight(), onTrace = { tracing = true })
                Column(
                    Modifier.width(sideWidth).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    RidersCard(s)
                    CarCard(s)
                    Said(s)
                    Spacer(Modifier.weight(1f))
                    Controls(s, vm, onEdit = { editing = true })
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Cup(s, Modifier.weight(1f).fillMaxWidth(), onTrace = { tracing = true })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                    RidersCard(s, Modifier.weight(1f).fillMaxHeight())
                    CarCard(s, Modifier.weight(1f).fillMaxHeight())
                }
                Said(s)
                Controls(s, vm, onEdit = { editing = true })
            }
        }
    }
    }
    if (editing) ChildrenDialog(s.children, vm, onDismiss = { editing = false })
}

/** 이보다 넓은 가로 화면이면 좌우 배치 */
private val WIDE_MIN = 900.dp

@Composable
private fun Cup(s: UiState, modifier: Modifier, onTrace: () -> Unit) = Box(modifier.neonCard().clip(Neon.CardShape)) {
    // 은은한 빛 (고리·그림자는 CupView 가 같은 3D 바닥에 그린다)
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.radialGradient(listOf(Color(0x3329C4F6), Color.Transparent), center = Offset(size.width * 0.5f, size.height * 0.62f), radius = size.minDimension * 0.7f))
    }
    CupView(s.tiltX, s.tiltY, s.level, s.dose, s.spilling, Modifier.fillMaxSize().padding(top = 40.dp, bottom = 8.dp), stage = true)
    Row(Modifier.align(Alignment.TopStart).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.WaterDrop, null, tint = Neon.Cyan, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(6.dp))
        Text("찰랑", color = Neon.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
    NeonChip("흔들림 분석", false, onClick = onTrace, icon = Icons.Filled.ShowChart, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp))
}

@Composable
private fun Said(s: UiState) {
    s.lastSaid?.let {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Campaign, null, tint = Neon.Cyan, modifier = Modifier.padding(top = 2.dp).size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(it, color = Neon.Text, fontSize = 17.sp, lineHeight = 24.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 창문 질문, "지금 멀미", 아이 설정·시연 */
@Composable
private fun Controls(s: UiState, vm: ChalrangEngine, onEdit: () -> Unit) {
    s.askWindows?.let { AskWindowCard(it, vm) }
    // "지금 멀미": 빛나는 빨간 버튼 (혼자 주행이면 꺼짐)
    val on = s.setup.active
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.fillMaxWidth().height(88.dp)
            .drawBehind {
                if (on) {
                    for ((w, a) in listOf(16f to 0.08f, 8f to 0.16f)) {
                        drawRoundRect(Neon.Red.copy(alpha = a), Offset(-w / 2, -w / 2), Size(size.width + w, size.height + w), CornerRadius(20.dp.toPx() + w / 2), style = Stroke(w))
                    }
                }
            }
            .clip(shape)
            .background(if (on) Brush.verticalGradient(listOf(Color(0xFFFF6A5A), Color(0xFFD9283C))) else Brush.verticalGradient(listOf(Color(0xFF243246), Color(0xFF1A2536))), shape)
            .border(1.5.dp, if (on) Color(0xFFFFA19A) else Neon.Edge.copy(alpha = 0.3f), shape)
            .clickable(enabled = on, onClick = vm::sickNow),
        contentAlignment = Alignment.Center,
    ) {
        Text("지금 멀미", color = if (on) Color.White else Neon.Faint, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    }
    DemoRow(s, vm, onEdit)
}

@Composable
private fun Card(modifier: Modifier = Modifier, icon: ImageVector? = null, title: String? = null, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().neonCard().padding(horizontal = 16.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, tint = Neon.Cyan, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(title, color = Neon.Dim, fontSize = 15.sp)
            }
        }
        content()
    }
}

@Composable
private fun RidersCard(s: UiState, modifier: Modifier = Modifier) = Card(modifier, Icons.Filled.EventSeat, "탄 사람") {
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

@Composable
private fun CarCard(s: UiState, modifier: Modifier = Modifier) = Card(modifier, Icons.Filled.DirectionsCar, "차량") {
    val car = s.car
    when {
        car.dustGuard -> Text("먼지 차단 중", color = Neon.Amber, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        car.freshAir -> Text("환기 중", color = Neon.Cyan, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        else -> Text(if (car.connected) "평소대로" else "차량 연결 안 됨", color = Color.White, fontSize = 20.sp)
    }
    if (car.openWindows.isNotEmpty()) {
        Text(car.openWindows.entries.joinToString { (w, p) -> "${w.label} 창문 $p%" }, color = Color.White, fontSize = 17.sp)
    }
    if (s.raining) Text("비 · 창문은 닫아 둬요", color = Dim, fontSize = 15.sp)
    car.lastAction?.let { Text(it, color = Dim, fontSize = 15.sp) }
}

/** 창문을 열지 묻기. 운전 중에도 누르기 쉽게 크게, 동승자가 눌러도 된다 */
@Composable
private fun AskWindowCard(windows: List<Window>, vm: ChalrangEngine) = Card(icon = Icons.Filled.Air, title = "창문") {
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
    if (s.demo != null) {
        Text("시연 · ${s.demo}", color = Neon.Dim, fontSize = 14.sp)
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x332A8BE0))) {
            Box(Modifier.fillMaxWidth(s.demoProgress.coerceIn(0f, 1f)).height(6.dp).background(Brush.horizontalGradient(listOf(Neon.Blue, Neon.Cyan))))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        NeonChip("아이 설정", false, onClick = onEdit, icon = Icons.Filled.Face, modifier = Modifier.weight(1f))
        if (s.demo == null) {
            NeonChip("시연 거칠게", false, onClick = { vm.startDemo(DriverStyle.ROUGH) }, modifier = Modifier.weight(1f))
            NeonChip("시연 부드럽게", false, onClick = { vm.startDemo(DriverStyle.SMOOTH) }, modifier = Modifier.weight(1f))
        } else {
            NeonChip("시연 끝", true, onClick = vm::stopDemo, icon = Icons.Filled.Stop, modifier = Modifier.weight(2f))
        }
    }
}

@Composable
private fun ChildrenDialog(children: List<Child>, vm: ChalrangEngine, onDismiss: () -> Unit) {
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
                                if (c.calibrations == 0) "처음 기준 · 멀미하면 '지금 멀미'를 눌러 주세요"
                                else "'지금 멀미'로 ${c.calibrations}번 맞춤",
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
