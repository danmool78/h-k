package com.sssoft.chalrang.ui

import androidx.compose.foundation.background
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

    BoxWithConstraints(Modifier.fillMaxSize().background(Bg)) {
        val wide = maxWidth >= WIDE_MIN && maxWidth > maxHeight
        val sideWidth = minOf(380.dp, maxWidth * 0.38f)
        if (wide) {
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
        // 기록 화면은 물컵 화면 위에 덮는다
        if (tracing) TraceScreen(vm, onClose = { tracing = false })
    }
    if (editing) ChildrenDialog(s.children, vm, onDismiss = { editing = false })
}

/** 이보다 넓은 가로 화면이면 좌우 배치 */
private val WIDE_MIN = 900.dp

@Composable
private fun Cup(s: UiState, modifier: Modifier, onTrace: () -> Unit) = Box(modifier) {
    CupView(s.tiltX, s.tiltY, s.level, s.dose, s.spilling, Modifier.fillMaxSize())
    Text("찰랑", color = Dim, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopStart))
    TextButton(onClick = onTrace, modifier = Modifier.align(Alignment.TopEnd)) { Text("흔들림 기록", fontSize = 16.sp) }
}

@Composable
private fun Said(s: UiState) {
    s.lastSaid?.let {
        Text("“$it”", color = Dim, fontSize = 17.sp, lineHeight = 24.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 창문 질문, "지금 멀미", 아이 설정·시연 */
@Composable
private fun Controls(s: UiState, vm: ChalrangEngine, onEdit: () -> Unit) {
    s.askWindows?.let { AskWindowCard(it, vm) }
    Button(
        onClick = vm::sickNow,
        enabled = s.setup.active,
        colors = ButtonDefaults.buttonColors(containerColor = Sick, contentColor = Color.White),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().height(88.dp),
    ) {
        Text("지금 멀미", fontSize = 30.sp, fontWeight = FontWeight.Bold)
    }
    DemoRow(s, vm, onEdit)
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun RidersCard(s: UiState, modifier: Modifier = Modifier) = Card(modifier) {
    Text("탄 사람", color = Dim, fontSize = 15.sp)
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
private fun CarCard(s: UiState, modifier: Modifier = Modifier) = Card(modifier) {
    Text("차량", color = Dim, fontSize = 15.sp)
    val car = s.car
    when {
        car.dustGuard -> Text("먼지 차단 중", color = Color(0xFFFFCC80), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        car.freshAir -> Text("환기 중", color = Color(0xFF81D4FA), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
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
private fun AskWindowCard(windows: List<Window>, vm: ChalrangEngine) = Card {
    Text(windows.joinToString { it.label } + " 창문을 조금 열까요?", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { vm.answerWindow(true) }, modifier = Modifier.weight(1f).height(64.dp)) {
            Text("열기", fontSize = 22.sp)
        }
        OutlinedButton(onClick = { vm.answerWindow(false) }, modifier = Modifier.weight(1f).height(64.dp)) {
            Text("먼지 · 닫기", fontSize = 20.sp, maxLines = 1)
        }
    }
}

@Composable
private fun DemoRow(s: UiState, vm: ChalrangEngine, onEdit: () -> Unit) {
    if (s.demo != null) {
        Text("시연 · ${s.demo}", color = Dim, fontSize = 14.sp)
        LinearProgressIndicator(progress = { s.demoProgress }, modifier = Modifier.fillMaxWidth())
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("아이 설정") }
        if (s.demo == null) {
            OutlinedButton(onClick = { vm.startDemo(DriverStyle.ROUGH) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("시연 거칠게", maxLines = 1)
            }
            OutlinedButton(onClick = { vm.startDemo(DriverStyle.SMOOTH) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("시연 부드럽게", maxLines = 1)
            }
        } else {
            OutlinedButton(onClick = vm::stopDemo, modifier = Modifier.weight(2f)) { Text("시연 끝") }
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
