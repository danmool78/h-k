package com.sssoft.hk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sssoft.hk.model.ChargePlanResult
import com.sssoft.hk.model.VehicleProfile
import com.sssoft.hk.model.Weather
import com.sssoft.hk.trip.ChargingRecommendation
import com.sssoft.hk.trip.StopOption
import kotlin.math.roundToInt

private val Good = Color(0xFF4CAF50)
private val Caution = Color(0xFFFFB300)
private val Bad = Color(0xFFE53935)

private fun waitColor(min: Double) = when {
    min < 5 -> Good
    min < 15 -> Caution
    else -> Bad
}

@Composable
fun RangeScreen(viewModel: RangeViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val onBg = MaterialTheme.colorScheme.onBackground
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Header(ui, onRefresh = viewModel::refresh)
        if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        else Spacer(Modifier.height(12.dp))
        ui.error?.let { Text(it, color = Bad, modifier = Modifier.padding(vertical = 8.dp)) }

        Row(Modifier.fillMaxSize().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1.2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                RecommendationCard(ui, onAddWaypoint = viewModel::addWaypoint)
                ArrivalLine(ui)
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text("후보 충전소 비교", style = MaterialTheme.typography.titleMedium, color = onBg)
                Spacer(Modifier.height(8.dp))
                val options = ui.recommendation?.options.orEmpty()
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(options, key = { it.station.id }) { o ->
                        OptionRow(o, isBest = o == options.first(), isBaseline = o == ui.recommendation?.baseline)
                    }
                }
                if (ui.source == RouteSource.DEMO) DemoInputs(ui, viewModel::onInput)
            }
        }
    }
}

@Composable
private fun Header(ui: RangeUiState, onRefresh: () -> Unit) {
    val onBg = MaterialTheme.colorScheme.onBackground
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                ui.destination?.let { "${it.name} · ${it.remainKm.roundToInt()}km" } ?: "목적지 확인 중…",
                fontSize = 26.sp, fontWeight = FontWeight.Bold, color = onBg,
            )
            val battery = if (ui.battery.connected) "차량 배터리 ${ui.socPercent}%" else "배터리 ${ui.socPercent}% (수동)"
            val src = if (ui.source == RouteSource.DEMO) " · 내비 목적지 없음, 데모 경로" else ""
            val c = ui.conditions
            val weather = if (c != null) {
                "경로 기온 ${c.tempC.roundToInt()}°C · ${c.weather.label}" + if (c.weatherFromForecast) " (예보)" else " (수동)"
            } else "기온 ${ui.input.tempC.roundToInt()}°C · ${ui.input.weather.label}"
            Text("$battery · $weather$src", color = onBg.copy(alpha = 0.7f))
        }
        ui.recommendation?.totalMinutes?.let { (p50, p90) ->
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 16.dp)) {
                Text("도착 예정 ${clock(p50)}", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = onBg)
                Text("늦어도 ${clock(p90)} · 충전·대기 포함", color = onBg.copy(alpha = 0.7f))
            }
        }
        OutlinedButton(onClick = onRefresh, enabled = !ui.loading) { Text("새로고침") }
    }
}

@Composable
private fun RecommendationCard(ui: RangeUiState, onAddWaypoint: () -> Unit) {
    val rec = ui.recommendation
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(28.dp)) {
            when (val plan = rec?.plan) {
                null -> Text("계산 중…", fontSize = 24.sp)
                is ChargePlanResult.NoChargeNeeded -> {
                    Text("충전 없이 도착할 수 있어요", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Good)
                    Text("도착 확률 ${pct(plan.probability)}", fontSize = 20.sp)
                }
                is ChargePlanResult.Infeasible -> {
                    Text("한 번 충전으로는 안전하게 가기 어려워요", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Bad)
                    Text("확인한 충전소 ${rec.checkedStations}곳", fontSize = 18.sp)
                }
                is ChargePlanResult.Plan -> PlanBody(rec, plan, ui, onAddWaypoint)
            }
        }
    }
}

@Composable
private fun PlanBody(rec: ChargingRecommendation, plan: ChargePlanResult.Plan, ui: RangeUiState, onAddWaypoint: () -> Unit) {
    val best = rec.best ?: return
    val s = best.station
    Text("추천 충전소", style = MaterialTheme.typography.labelLarge)
    Text(s.name, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Text("도착 시 대기 ", fontSize = 20.sp)
        Text("약 ${best.waitMin.roundToInt()}분", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = waitColor(best.waitMin))
    }
    Text(
        "지금 빈 충전기 ${s.available}/${s.inService} · 최대 ${s.maxKw.roundToInt()}kW · ${pct(plan.chargeToSoc)}까지 충전",
        fontSize = 18.sp,
    )
    Spacer(Modifier.height(16.dp))

    val saved = rec.savedMin?.roundToInt()
    val baseName = rec.baseline?.let { if (it.naviPick) "내비 추천" else "가장 빠른 충전기" } ?: "내비 추천"
    val (badge, color) = when {
        saved != null && saved > 0 -> "$baseName(${rec.baseline!!.station.name})보다 ${saved}분 빨라요" to Good
        else -> "$baseName 충전소가 지금 가장 빨라요" to MaterialTheme.colorScheme.primary
    }
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f))) {
        Text(badge, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
    Spacer(Modifier.height(16.dp))
    Text(
        "총 소요 약 ${plan.minutesP50.roundToInt()}분 (늦어지면 ${plan.minutesP90.roundToInt()}분) · 도착 확률 ${pct(plan.probability)}",
        fontSize = 18.sp,
    )
    if (ui.source == RouteSource.NAVI) {
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAddWaypoint, enabled = !ui.waypointAdded) {
            Text(if (ui.waypointAdded) "경유지에 추가됨" else "경유지로 추가", fontSize = 18.sp)
        }
    }
}

@Composable
private fun ArrivalLine(ui: RangeUiState) {
    val a = ui.arrival ?: return
    val muted = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
    Text("충전 없이 갈 확률 ${pct(a.probability)} · 도착 시 배터리 약 ${pct(a.socP50.coerceAtLeast(0.0))}", color = muted, fontSize = 16.sp)

    val c = ui.conditions ?: return
    val ctx = ui.recommendation?.env?.context
    val hvac = if (c.hvacOn) "공조 ${c.cabinSetC.roundToInt()}°C" else "공조 꺼짐"
    val tire = c.tirePressureKpa?.let { " · 타이어 ${it.roundToInt()}kPa" } ?: ""
    val when_ = ctx?.let { listOfNotNull(it.type.label, if (it.night) "밤" else "낮", if (it.weekend) "주말" else null).joinToString("·") } ?: ""
    Text("예측 조건: 탑승 ${c.occupants}명 · $hvac · ${c.mode.label} 모드$tire · $when_", color = muted, fontSize = 14.sp)

    val p = ui.profile ?: return
    val (paceMean, _) = ctx?.let(p::paceLog) ?: (0.0 to 0.0)
    val pace = (kotlin.math.exp(paceMean) - 1) * 100
    val style = ctx?.let { p.accelWhPerKmTon(it) / VehicleProfile.DEFAULT_ACCEL_WH_PER_KM_TON } ?: 1.0
    Text(
        "학습: 주행 ${p.segments}구간 · ${p.trips}회 · 충전 ${p.sessions}회 · " +
            "운전 습관 내비 대비 ${"%+.0f".format(pace)}% · 가속 지수 ${"%.2f".format(style)}",
        color = muted, fontSize = 14.sp,
    )
    if (c.weatherFromForecast) Text("Weather data by Open-Meteo.com", color = muted, fontSize = 12.sp)
}

private fun clock(minutesFromNow: Double): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA)
        .format(java.util.Date(System.currentTimeMillis() + (minutesFromNow * 60_000).toLong()))

@Composable
private fun OptionRow(o: StopOption, isBest: Boolean, isBaseline: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.station.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (isBest) Tag("추천", Good)
                if (isBaseline) Tag(if (o.naviPick) "내비 추천" else "최대 출력", MaterialTheme.colorScheme.primary)
            }
            Text(
                "${o.atKm.roundToInt()}km 지점 · 대기 ${o.waitMin.roundToInt()}분 · 총 ${o.plan.minutesP50.roundToInt()}분 · " +
                    "빈 ${o.station.available}/${o.station.inService} · ${o.station.maxKw.roundToInt()}kW",
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text,
        Modifier
            .padding(start = 6.dp)
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun DemoInputs(ui: RangeUiState, onInput: (TripInput) -> Unit) {
    val i = ui.input
    val onBg = MaterialTheme.colorScheme.onBackground
    Column(Modifier.padding(top = 12.dp)) {
        Text("데모 조건", style = MaterialTheme.typography.labelLarge, color = onBg)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("날씨", Modifier.weight(0.3f), color = onBg)
            Row(Modifier.weight(0.7f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (i.useForecast) Button(onClick = {}) { Text("실제 예보") }
                else OutlinedButton(onClick = { onInput(i.copy(useForecast = true)) }) { Text("실제 예보") }
                if (!i.useForecast) Button(onClick = {}) { Text("직접 입력") }
                else OutlinedButton(onClick = { onInput(i.copy(useForecast = false)) }) { Text("직접 입력") }
            }
        }
        if (!i.useForecast) {
            LabeledSlider("외부 기온", "${i.tempC.roundToInt()} °C", i.tempC, -20f..40f) { onInput(i.copy(tempC = it)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("", Modifier.weight(0.3f))
                Row(Modifier.weight(0.7f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Weather.entries.forEach { w ->
                        if (w == i.weather) Button(onClick = {}) { Text(w.label) }
                        else OutlinedButton(onClick = { onInput(i.copy(weather = w)) }) { Text(w.label) }
                    }
                }
            }
        }
        if (!ui.battery.connected) {
            LabeledSlider("배터리", "${i.manualSocPercent.roundToInt()} %", i.manualSocPercent, 10f..100f) {
                onInput(i.copy(manualSocPercent = it))
            }
            LabeledSlider("탑승 인원", "${i.manualOccupants}명", i.manualOccupants.toFloat(), 1f..5f) {
                onInput(i.copy(manualOccupants = it.roundToInt()))
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(0.3f), color = onBg)
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(0.5f))
        Text(valueText, Modifier.weight(0.2f), color = onBg)
    }
}

private fun pct(x: Double) = "${(x * 100).roundToInt()}%"
