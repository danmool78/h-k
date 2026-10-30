package com.sssoft.chalrang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sssoft.chalrang.core.AppSettings
import com.sssoft.chalrang.core.Sensitivity
import com.sssoft.chalrang.engine.ChalrangEngine
import kotlin.math.roundToInt

/** 여러 설정을 모아 둔 화면: 아이·물컵·소리·차량·가는 길·주행 평가·시연·정보 */
@Composable
fun SettingsScreen(vm: ChalrangEngine, onClose: () -> Unit) {
    val s by vm.ui.collectAsState()
    val set = s.settings
    var editing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    fun update(f: (AppSettings) -> AppSettings) = vm.updateSettings(f(set))

    NeonBackground {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 900.dp
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Settings, null, tint = Neon.Cyan, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("설정", color = Neon.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    NeonChip("닫기", false, onClick = onClose, icon = Icons.Filled.Close)
                }
                val left: @Composable () -> Unit = {
                    Section(Icons.Filled.Face, "아이") {
                        if (s.children.isEmpty()) Note("등록한 아이가 없어요")
                        s.children.forEach { c ->
                            val sens = c.sensitivity
                            Line(
                                "${c.name} · ${c.seat.label}",
                                "물 높이 기준 ${(c.level * 100).roundToInt()}% · " +
                                    if (sens.active) "${(sens.peakPeriod * 10).roundToInt() / 10.0}초 흔들림에 예민 (×${(sens.gain * 100).roundToInt() / 100.0})"
                                    else "멀미 패턴 학습 중 ${sens.count}/${Sensitivity.MIN_EVENTS}",
                            )
                        }
                        ActionButton("아이 추가 · 관리") { editing = true }
                    }
                    Section(Icons.Filled.WaterDrop, "물컵") {
                        Toggle("평소 운전 습관만큼 물 채우기", "최근 5번 주행 점수로 처음 물 양을 정해요. 젠틀할수록 가득. 끄면 늘 60%", set.habitFill) { v -> update { it.copy(habitFill = v) } }
                        Line(
                            "지금 평소 물 ${(s.habitWater * 100).roundToInt()}%",
                            s.habitScore?.let { "최근 주행 평균 ${it}점 · 출발 전에 물컵 옆 +/- 버튼으로 더 채워 도전하거나 덜어 낼 수 있어요" } ?: "아직 주행 기록이 없어요",
                        )
                        if (confirmClear) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ActionButton("정말 지우기", danger = true, modifier = Modifier.weight(1f)) { vm.clearTrips(); confirmClear = false }
                                ActionButton("그만두기", modifier = Modifier.weight(1f)) { confirmClear = false }
                            }
                        } else {
                            ActionButton("주행 기록 지우기 (평소 습관 처음부터)") { confirmClear = true }
                        }
                    }
                    Section(Icons.Filled.VolumeUp, "소리") {
                        Toggle("음성 안내", "넘침 주의·환기·가는 길·주행 평가를 말로", set.voice) { v -> update { it.copy(voice = v) } }
                        Toggle("\"찰랑\" 소리", "물이 넘칠 때", set.chime) { v -> update { it.copy(chime = v) } }
                        Toggle("안내 적게", "\"부드럽게\" 주의와 내비 예고는 말하지 않아요 (위험·휴게소 안내는 해요)", set.fewerAlerts) { v -> update { it.copy(fewerAlerts = v) } }
                    }
                }
                val right: @Composable () -> Unit = {
                    Section(Icons.Filled.DirectionsCar, "차량") {
                        Toggle("자동 환기", "물이 자주 넘치면 외기 유입·에어컨·온도 2도 낮춤·풍량 +1, 회복되면 원래대로", set.autoVent) { v -> update { it.copy(autoVent = v) } }
                        Toggle("창문 열기 묻기", "환기할 때 아이 옆 창문을 열지 먼저 물어요 (시속 80km 이하·비 안 올 때만)", set.askWindow) { v -> update { it.copy(askWindow = v) } }
                        Text("창문 여는 정도", color = Neon.Text, fontSize = 16.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppSettings.WINDOW_CHOICES.forEach { p ->
                                NeonChip("$p%", set.windowPercent == p, onClick = { update { it.copy(windowPercent = p) } })
                            }
                        }
                    }
                    Section(Icons.Filled.Route, "가는 길") {
                        Toggle("멀미 위험 구간 미리보기", "길 안내가 시작되면 요약하고, 구간 1.2km 앞에서 알려요", set.routePreview) { v -> update { it.copy(routePreview = v) } }
                        Toggle("3D 경로 브리핑", "길 안내·시연이 시작될 때(정차 중) 파란 3D 선을 따라 가는 길을 훑고, 멀미 위험 구간을 빨갛게 보여 주며 말로 설명해요", set.briefing) { v -> update { it.copy(briefing = v) } }
                        Toggle("지형도 보기 (인터넷)", "고도 정보(Open-Meteo)로 오르내림이 심한 곳도 찾아요", set.terrain) { v -> update { it.copy(terrain = v) } }
                    }
                    Section(Icons.Filled.EmojiEvents, "주행 평가") {
                        Toggle("주행이 끝나면 평가 보기", "점수·등급·흔들림 원인·다음엔 이렇게", set.tripReport) { v -> update { it.copy(tripReport = v) } }
                    }
                    Section(Icons.Filled.PhoneAndroid, "시연") {
                        Toggle("휴대폰 시연 받기", "관리자가 휴대폰(/phone/)을 흔들면 그 흔들림으로 물컵을 움직여요", set.phoneDemo) { v -> update { it.copy(phoneDemo = v) } }
                    }
                    Section(Icons.Filled.Info, "정보") {
                        Line("계산 근거", "h-y-server.web.app/admin — 수식·매개변수·검증·출처")
                        Note("아이 정보·주행 기록·설정은 이 기기 안에만 저장해요")
                    }
                }
                if (wide) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) { left() }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) { right() }
                    }
                } else {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) { left(); right() }
                }
            }
        }
    }
    if (editing) ChildrenDialog(s.children, vm, onDismiss = { editing = false })
}

@Composable
private fun Section(icon: ImageVector, title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().neonCard().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Neon.Cyan, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, color = Neon.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

/** 큰 스위치 (차 화면에서 누르기 쉽게 줄 전체가 눌린다) */
@Composable
private fun Toggle(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Neon.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Neon.Dim, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        val track = RoundedCornerShape(16.dp)
        Box(
            Modifier.width(58.dp).height(32.dp).clip(track)
                .background(if (on) Brush.horizontalGradient(listOf(Color(0xFF1A6FD0), Neon.Cyan)) else Brush.horizontalGradient(listOf(Color(0xFF16233A), Color(0xFF16233A))))
                .border(1.dp, if (on) Neon.Cyan else Neon.Edge.copy(alpha = 0.4f), track),
        ) {
            Box(
                Modifier.align(Alignment.CenterStart).offset(x = if (on) 29.dp else 3.dp)
                    .size(26.dp).clip(CircleShape).background(if (on) Neon.White else Neon.Dim),
            )
        }
    }
}

@Composable
private fun Line(title: String, sub: String) {
    Column {
        Text(title, color = Neon.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(sub, color = Neon.Dim, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun Note(text: String) = Text(text, color = Neon.Faint, fontSize = 13.sp)

@Composable
private fun ActionButton(label: String, danger: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.height(48.dp).clip(shape).background(Color(0x660B1D36))
            .border(1.2.dp, if (danger) Neon.Red else Neon.Edge.copy(alpha = 0.7f), shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (danger) Neon.Red else Neon.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}
