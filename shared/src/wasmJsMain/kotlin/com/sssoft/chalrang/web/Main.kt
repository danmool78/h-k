package com.sssoft.chalrang.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import com.sssoft.chalrang.ui.Neon
import com.sssoft.chalrang.ui.NeonChip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ComposeViewport
import com.sssoft.chalrang.core.Seat
import com.sssoft.chalrang.engine.ChalrangEngine
import com.sssoft.chalrang.ui.ChalrangTheme
import com.sssoft.chalrang.ui.MainScreen
import kotlin.math.min

/**
 * 점검할 앱 영역 (dp). 차량 화면 2560×1440(QHD), 배율 2 기준.
 * 브라우저에서는 이 영역을 통째로 줄이거나 키워 보여 준다.
 */
private enum class Frame(val label: String, val w: Float, val h: Float) {
    FULL("전체 화면", 1280f, 720f),
    SPLIT("화면 분할 (앱 두 개)", 640f, 720f),
    PORTRAIT("세로 화면", 720f, 1280f);

    val short: String get() = when (this) { FULL -> "전체 화면"; SPLIT -> "분할 (앱 두 개)"; PORTRAIT -> "세로 화면" }
}

@Composable
private fun BarLabel(icon: ImageVector, text: String) {
    Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Neon.Cyan, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Neon.Dim, fontSize = 14.sp)
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun BarDivider() {
    Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(40.dp).background(Neon.Edge.copy(alpha = 0.35f)))
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val car = SimCar()
    val voice = BrowserVoice()
    val engine = ChalrangEngine(car, SimNavi(), voice, BrowserChildRepo())
    ComposeViewport("chalrang") {
        ChalrangTheme {
            // 화면이 떠 있는 동안만 돈다
            LaunchedEffect(Unit) { engine.run() }
            WebApp(engine, car, voice)
        }
    }
}

/** 위: 앱과 같은 화면 (같은 코드), 아래: 점검 도구 (좌석·비·소리) */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WebApp(engine: ChalrangEngine, car: SimCar, voice: BrowserVoice) {
    val seats by car.seats.collectAsState()
    val raining by car.raining.collectAsState()
    var muted by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf(Frame.FULL) }

    Column(Modifier.fillMaxSize().background(Neon.Bg0)) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val scale = min(maxWidth.value / frame.w, maxHeight.value / frame.h)
            Box(
                Modifier
                    .requiredSize(frame.w.dp, frame.h.dp)
                    .graphicsLayer(scaleX = scale, scaleY = scale),
            ) {
                MainScreen(engine)
            }
        }
        // 점검 도구 (웹에만): 좌석·화면 크기·비·소리
        FlowRow(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xFF0A1830), Color(0xFF050C18))))
                .drawBehind { drawLine(Brush.horizontalGradient(listOf(Color.Transparent, Neon.Edge, Color.Transparent)), Offset(0f, 0f), Offset(size.width, 0f), 2f) }
                .padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BarLabel(Icons.Filled.EventSeat, "점검 · 탄 좌석")
            Seat.entries.forEach { s -> NeonChip(s.label, seats[s]?.riding == true, onClick = { car.toggleSeat(s) }) }
            BarDivider()
            BarLabel(Icons.Filled.DesktopWindows, "화면")
            Frame.entries.forEach { f -> NeonChip(f.short, frame == f, onClick = { frame = f }) }
            BarDivider()
            NeonChip("비 (와이퍼)", raining, onClick = { car.setRaining(!raining) }, icon = Icons.Filled.Umbrella)
            NeonChip(if (muted) "소리 끔" else "소리 켬", muted, onClick = { muted = !muted; voice.muted = muted }, icon = if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp)
        }
    }
}
