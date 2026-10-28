package com.sssoft.hk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sssoft.hk.model.ChargePlanResult
import kotlin.math.roundToInt

private val Safe = Color(0xFF4CAF50)
private val Caution = Color(0xFFFFB300)
private val Danger = Color(0xFFE53935)

private fun gaugeColor(p: Double) = when {
    p >= 0.95 -> Safe
    p >= 0.70 -> Caution
    else -> Danger
}

@Composable
fun RangeScreen(viewModel: RangeViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Row(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ProbabilityGauge(ui.arrival?.probability)
            Spacer(Modifier.height(16.dp))
            ui.arrival?.let { a ->
                Text(
                    "도착 시 배터리  P10 ${pct(a.socP10)} · P50 ${pct(a.socP50)} · P90 ${pct(a.socP90)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Spacer(Modifier.height(24.dp))
            PlanCard(ui.plan)
        }
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            BatteryInfo(ui)
            val i = ui.input
            LabeledSlider("목적지까지", "${i.distanceKm.roundToInt()} km", i.distanceKm, 10f..600f) {
                viewModel.onInput(i.copy(distanceKm = it))
            }
            LabeledSlider("외부 기온", "${i.tempC.roundToInt()} °C", i.tempC, -20f..40f) {
                viewModel.onInput(i.copy(tempC = it))
            }
            LabeledSlider("오르막 (누적)", "${i.ascentPerKm.roundToInt()} m/km", i.ascentPerKm, 0f..30f) {
                viewModel.onInput(i.copy(ascentPerKm = it))
            }
            if (ui.battery.socPercent == null) {
                LabeledSlider("배터리 (수동)", "${i.manualSocPercent.roundToInt()} %", i.manualSocPercent, 5f..100f) {
                    viewModel.onInput(i.copy(manualSocPercent = it))
                }
            }
        }
    }
}

@Composable
private fun ProbabilityGauge(probability: Double?) {
    val color = probability?.let(::gaugeColor) ?: Color.Gray
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(280.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 28.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (probability != null) {
                drawArc(color, 135f, 270f * probability.toFloat(), false, Offset(inset, inset), arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                probability?.let { "${(it * 100).roundToInt()}%" } ?: "—",
                fontSize = 72.sp, fontWeight = FontWeight.Bold, color = color,
            )
            Text("도착 확률", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        }
    }
}

@Composable
private fun PlanCard(plan: ChargePlanResult?) {
    val text = when (plan) {
        null -> "계산 중…"
        is ChargePlanResult.NoChargeNeeded -> "충전 없이 도착할 수 있습니다."
        is ChargePlanResult.Plan ->
            "${plan.charger}에서 ${pct(plan.chargeToSoc)}까지 충전\n" +
                "도착 확률 ${pct(plan.probability)} · 총 ${plan.minutesP50.roundToInt()}분 (늦으면 ${plan.minutesP90.roundToInt()}분)"
        is ChargePlanResult.Infeasible -> "한 번 충전으로는 안전하게 도착하기 어렵습니다. 충전소 후보를 늘려야 합니다."
    }
    Card(Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun BatteryInfo(ui: RangeUiState) {
    val b = ui.battery
    val line = if (b.connected) {
        "차량 배터리 ${b.socPercent}%" +
            (b.capacityKwh?.let { " · 용량 ${it.roundToInt()} kWh" } ?: "") +
            (b.rangeRemaining?.let { " · 계기판 주행가능 ${it.roundToInt()}" } ?: "")
    } else {
        "차량 연결 안 됨 — 수동 입력 사용" + (b.error?.let { "\n$it" } ?: "")
    }
    Text(line, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
}

@Composable
private fun LabeledSlider(
    label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Text(valueText, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

private fun pct(x: Double) = "${(x * 100).roundToInt()}%"
