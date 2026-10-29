package com.sssoft.chalrang.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.FilterChip
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
    PORTRAIT("세로 화면", 720f, 1280f),
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val car = SimCar()
    val voice = BrowserVoice()
    val engine = ChalrangEngine(car, SimNavi(), voice, BrowserChildRepo())
    ComposeViewport("chalrang") {
        ChalrangTheme {
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) { engine.start(scope) }
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

    Column(Modifier.fillMaxSize().background(Color(0xFF05090E))) {
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
        FlowRow(
            Modifier.fillMaxWidth().background(Color(0xFF111A24)).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("점검 · 가짜 차량", color = Color(0xFF9FB0C3), fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterVertically))
            Seat.entries.forEach { s ->
                FilterChip(selected = seats[s]?.riding == true, onClick = { car.toggleSeat(s) }, label = { Text(s.label) })
            }
            Frame.entries.forEach { f ->
                FilterChip(selected = frame == f, onClick = { frame = f }, label = { Text(f.label) })
            }
            FilterChip(selected = raining, onClick = { car.setRaining(!raining) }, label = { Text("비 (와이퍼)") })
            FilterChip(selected = muted, onClick = { muted = !muted; voice.muted = muted }, label = { Text("소리 끔") })
        }
    }
}
