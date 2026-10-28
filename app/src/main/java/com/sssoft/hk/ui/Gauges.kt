package com.sssoft.hk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sssoft.hk.model.LearningProgress
import kotlin.math.roundToInt

// 웹 데모(server/public/demo/journey.js)와 같은 디자인

private val Line = Color(0xFF4F8CFF)
private val Band = Color(0x2E4F8CFF)
private val ChargeGreen = Color(0xFF4CAF50)
private val ReserveRed = Color(0xFFE53935)
private val Grid = Color(0xFF2A2F3A)

fun levelColor(soc: Double): Color = when {
    soc >= 0.5 -> Color(0xFF4CAF50)
    soc >= 0.2 -> Color(0xFFFFB300)
    else -> Color(0xFFE53935)
}

private fun pct(x: Double) = "${(x.coerceAtLeast(0.0) * 100).roundToInt()}%"

/** 배터리 여정 데이터: 충전소 없이 가면 [stop] = null. 배터리 값은 0~1. */
data class Journey(
    val totalKm: Double,
    val soc0: Double,
    val reserve: Double,
    val destName: String,
    val dest: Triple<Double, Double, Double>,          // 적게 / 보통 / 많이
    val stop: Stop? = null,
) {
    data class Stop(
        val atKm: Double, val name: String, val chargeTo: Double, val chargeMin: Double,
        val arrive: Triple<Double, Double, Double>,
    )
}

/** 배터리 모양 게이지 하나 */
@Composable
private fun Battery(label: String, soc: Double, sub: String?, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = muted)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .height(30.dp)
                    .border(2.dp, Color(0xFF3A4150), RoundedCornerShape(7.dp))
                    .padding(2.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(soc.coerceIn(0.0, 1.0).toFloat())
                        .background(levelColor(soc), RoundedCornerShape(5.dp)),
                )
                Text(pct(soc), Modifier.align(Alignment.Center), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
            }
            Box(Modifier.size(width = 4.dp, height = 10.dp).background(Color(0xFF3A4150)))
        }
        Text(sub ?: " ", fontSize = 11.sp, color = muted)
    }
}

/** 출발 / 충전소 도착 / 충전 후 / 목적지 도착 */
@Composable
fun BatteryRow(j: Journey) {
    val muted = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Battery("출발", j.soc0, null, Modifier.weight(1f))
        j.stop?.let { s ->
            Text("→", color = muted)
            Battery("충전소 도착", s.arrive.second, "적으면 ${pct(s.arrive.first)}", Modifier.weight(1f))
            Text("→", color = muted)
            Battery("충전 후", s.chargeTo, "${s.chargeMin.roundToInt()}분 충전", Modifier.weight(1f))
        }
        Text("→", color = muted)
        Battery("목적지 도착", j.dest.second, "적으면 ${pct(j.dest.first)}", Modifier.weight(1f))
    }
}

/** 경로 거리에 따른 배터리: 예상 선 + 예측 범위 띠 + 충전 막대 + 도착 최소 기준선 */
@Composable
fun BatteryJourney(j: Journey, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(170.dp)) {
            val l = 8.dp.toPx(); val r = 8.dp.toPx(); val t = 8.dp.toPx(); val b = 8.dp.toPx()
            fun x(km: Double) = l + (size.width - l - r) * (km / j.totalKm).toFloat()
            fun y(soc: Double) = t + (size.height - t - b) * (1 - soc.coerceIn(0.0, 1.0)).toFloat()

            for (g in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) drawLine(Grid, Offset(l, y(g)), Offset(size.width - r, y(g)), 1f)
            drawLine(
                ReserveRed, Offset(l, y(j.reserve)), Offset(size.width - r, y(j.reserve)), 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f)),
            )
            fun band(x0: Double, s0: Double, x1: Double, lo: Double, hi: Double) {
                drawPath(Path().apply { moveTo(x(x0), y(s0)); lineTo(x(x1), y(hi)); lineTo(x(x1), y(lo)); close() }, Band)
            }
            val s = j.stop
            if (s != null) {
                band(0.0, j.soc0, s.atKm, s.arrive.first, s.arrive.third)
                band(s.atKm, s.chargeTo, j.totalKm, j.dest.first, j.dest.third)
                val bw = 14.dp.toPx()
                drawRoundRect(
                    ChargeGreen, Offset(x(s.atKm) - bw / 2, y(s.chargeTo)), Size(bw, y(s.arrive.second) - y(s.chargeTo)),
                    CornerRadius(4.dp.toPx()),
                )
                drawLine(Line, Offset(x(0.0), y(j.soc0)), Offset(x(s.atKm), y(s.arrive.second)), 8f, StrokeCap.Round)
                drawLine(Line, Offset(x(s.atKm), y(s.chargeTo)), Offset(x(j.totalKm), y(j.dest.second)), 8f, StrokeCap.Round)
                drawCircle(levelColor(s.arrive.second), 10f, Offset(x(s.atKm), y(s.arrive.second)))
                drawCircle(levelColor(s.chargeTo), 10f, Offset(x(s.atKm), y(s.chargeTo)))
            } else {
                band(0.0, j.soc0, j.totalKm, j.dest.first, j.dest.third)
                drawLine(Line, Offset(x(0.0), y(j.soc0)), Offset(x(j.totalKm), y(j.dest.second)), 8f, StrokeCap.Round)
            }
            drawCircle(levelColor(j.soc0), 10f, Offset(x(0.0), y(j.soc0)))
            drawCircle(levelColor(j.dest.second), 10f, Offset(x(j.totalKm), y(j.dest.second)))
        }
        Row(Modifier.fillMaxWidth()) {
            Text("출발", fontSize = 12.sp, color = muted)
            Spacer(Modifier.weight(1f))
            j.stop?.let {
                Text("${it.name.take(12)} · ${it.atKm.roundToInt()}km · +${((it.chargeTo - it.arrive.second) * 100).roundToInt()}%",
                    fontSize = 12.sp, color = ChargeGreen)
                Spacer(Modifier.weight(1f))
            }
            Text("${j.destName} · ${j.totalKm.roundToInt()}km", fontSize = 12.sp, color = muted)
        }
        Text("선: 예상 · 띠: 예측 범위 · 빨간 점선: 도착 최소 ${pct(j.reserve)}", fontSize = 11.sp, color = muted)
    }
}

/** 개인 학습도: 종합 링 + 항목별 막대 */
@Composable
fun LearningGauge(p: LearningProgress, modifier: Modifier = Modifier) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val muted = onBg.copy(alpha = 0.6f)
    val ringColor = when {
        p.overall >= 0.7 -> ChargeGreen
        p.overall >= 0.35 -> Color(0xFFFFB300)
        else -> muted
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 10.dp.toPx()
                val inset = stroke / 2
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(Color(0xFF11141A), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                drawArc(ringColor, -90f, 360f * p.overall.toFloat(), false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${(p.overall * 100).roundToInt()}%", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = onBg)
                Text("학습도", fontSize = 11.sp, color = muted)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((name, v) in listOf(
                "차량 특성" to p.vehicle, "운전 습관" to p.habit, "충전 특성" to p.charging, "자주 가는 곳" to p.places,
            )) {
                Row {
                    Text(name, fontSize = 12.sp, color = muted, modifier = Modifier.weight(1f))
                    Text("${(v * 100).roundToInt()}%", fontSize = 12.sp, color = muted)
                }
                Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0xFF11141A), RoundedCornerShape(3.dp))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(v.toFloat()).background(Line, RoundedCornerShape(3.dp)))
                }
            }
            Text(
                when {
                    p.overall < 0.05 -> "아직 이 차를 모릅니다. 차종 평균으로 넓게 예측합니다."
                    p.overall < 0.7 -> "학습 중입니다. 주행할수록 예측 범위가 좁아집니다."
                    else -> "이 차와 운전 습관을 잘 알고 있습니다."
                },
                fontSize = 11.sp, color = muted,
            )
        }
    }
}
