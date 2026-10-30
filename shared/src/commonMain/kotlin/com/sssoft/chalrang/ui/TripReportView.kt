package com.sssoft.chalrang.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sssoft.chalrang.core.Jolt
import com.sssoft.chalrang.core.TripGrade
import com.sssoft.chalrang.core.TripReport
import kotlin.math.max
import kotlin.math.roundToInt

/** 주행이 끝나면 덮어 보여 주는 평가 (점수·등급·흔들림 원인·팁·지난번과 비교) */
@Composable
fun TripReportOverlay(r: TripReport, previous: Int?, onClose: () -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color(0xD9020610)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        val wide = maxWidth >= 760.dp && maxWidth > maxHeight
        Column(
            Modifier.padding(20.dp).widthIn(max = 900.dp).fillMaxWidth().neonCard()
                .verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.EmojiEvents, null, tint = Neon.Cyan, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(10.dp))
                Text("이번 주행 평가", color = Neon.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("${minutes(r.durationSec)} · ${fmt(r.distanceKm, 1)} km", color = Neon.Dim, fontSize = 16.sp)
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
                    ScoreRing(r, previous, Modifier.width(230.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) { Details(r) }
                }
            } else {
                ScoreRing(r, previous, Modifier.fillMaxWidth())
                Details(r)
            }
            Tips(r)
            Box(
                Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(14.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF2A86F0), Color(0xFF1250B0))))
                    .border(1.5.dp, Neon.Cyan, RoundedCornerShape(14.dp))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Text("확인", color = Neon.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

private fun gradeColor(g: TripGrade): Color = when (g) {
    TripGrade.CALM -> Neon.Cyan
    TripGrade.GENTLE -> Neon.Green
    TripGrade.SLOSH -> Neon.Amber
    TripGrade.ROUGH -> Neon.Red
}

@Composable
private fun ScoreRing(r: TripReport, previous: Int?, modifier: Modifier) {
    val c = gradeColor(r.grade)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 16f
                val inset = w / 2 + 8f
                val arc = Size(size.width - inset * 2, size.height - inset * 2)
                val tl = Offset(inset, inset)
                drawArc(Neon.Grid, 135f, 270f, false, tl, arc, style = Stroke(w, cap = StrokeCap.Round))
                val sweep = 270f * r.score / 100f
                drawArc(c.copy(alpha = 0.25f), 135f, sweep, false, tl, arc, style = Stroke(w * 2.2f, cap = StrokeCap.Round))
                drawArc(c, 135f, sweep, false, tl, arc, style = Stroke(w, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${r.score}", color = Neon.White, fontSize = 58.sp, fontWeight = FontWeight.Bold, lineHeight = 60.sp)
                Text(r.grade.label, color = c, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(r.grade.line, color = Neon.Text, fontSize = 16.sp)
        if (previous != null) {
            val d = r.score - previous
            val text = when {
                d > 0 -> "지난 주행 ${previous}점보다 +$d"
                d < 0 -> "지난 주행 ${previous}점보다 $d"
                else -> "지난 주행과 같아요"
            }
            Spacer(Modifier.height(4.dp))
            Text(text, color = if (d >= 0) Neon.Cyan else Neon.Amber, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ColumnScope.Details(r: TripReport) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile("넘침", "${r.spills}회", Modifier.weight(1f), if (r.spills > 0) Neon.Amber else Neon.White)
        Tile(
            "최대 누적", fmt(r.peakDose, 2) + when { r.peakDose >= 1.0 -> " 위험"; r.peakDose >= 0.6 -> " 주의"; else -> "" },
            Modifier.weight(1f), when { r.peakDose >= 1.0 -> Neon.Red; r.peakDose >= 0.6 -> Neon.Amber; else -> Neon.White },
        )
        Tile("잔잔했던 시간", "${(r.calmShare * 100).roundToInt()}%", Modifier.weight(1f))
        Tile("멀미 가능성", "약 ${fmt(r.illnessPct, 1)}%", Modifier.weight(1f), sub = "ISO 2631-1 · 성인")
    }
    // 흔들림을 만든 운전
    val maxCount = max(5, r.jolts.values.maxOrNull() ?: 0)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (j in Jolt.entries) {
            val n = r.jolts[j] ?: 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(joltIcon(j), null, tint = if (n > 0) Neon.Amber else Neon.Faint, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(j.label, color = Neon.Text, fontSize = 15.sp, modifier = Modifier.width(96.dp))
                Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Neon.Grid)) {
                    Box(
                        Modifier.fillMaxWidth(n.toFloat() / maxCount).height(10.dp).clip(RoundedCornerShape(5.dp))
                            .background(Brush.horizontalGradient(listOf(Neon.Amber.copy(alpha = 0.6f), Neon.Amber))),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text("${n}번", color = if (n > 0) Neon.White else Neon.Dim, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(44.dp))
            }
        }
    }
    Text(
        "가장 출렁인 순간 ${clockText(r.worstAtSec)} · ${r.worstCause}" + (r.sicknessPeriod?.let { "  |  멀미 주기 ${fmt(it, 1)}초" } ?: ""),
        color = Neon.Dim, fontSize = 14.sp,
    )
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier, valueColor: Color = Neon.White, sub: String? = null) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x6606111F))
            .border(1.dp, Neon.Edge.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, color = Neon.Dim, fontSize = 13.sp, maxLines = 1)
        Text(value, color = valueColor, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        if (sub != null) Text(sub, color = Neon.Faint, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun Tips(r: TripReport) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x4D0B2A4A)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lightbulb, null, tint = Neon.Amber, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("다음엔 이렇게", color = Neon.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        r.tips.forEach { Text("· $it", color = Neon.Text, fontSize = 15.sp, lineHeight = 21.sp) }
    }
}

private fun joltIcon(j: Jolt): ImageVector = when (j) {
    Jolt.BRAKE -> Icons.AutoMirrored.Filled.TrendingDown
    Jolt.ACCEL -> Icons.AutoMirrored.Filled.TrendingUp
    Jolt.TURN -> Icons.Filled.TurnRight
    Jolt.BUMP -> Icons.Filled.Landscape
}

private fun minutes(sec: Double): String {
    val m = (sec / 60).roundToInt()
    return if (m < 60) "${m}분" else "${m / 60}시간 ${m % 60}분"
}

private fun clockText(sec: Double): String {
    val s = sec.toInt()
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

private fun fmt(v: Double, digits: Int): String {
    var p = 1.0
    repeat(digits) { p *= 10 }
    val r = (v * p).roundToInt() / p
    val s = r.toString()
    val dot = s.indexOf('.')
    return when {
        digits == 0 -> r.roundToInt().toString()
        dot < 0 -> s + "." + "0".repeat(digits)
        else -> s + "0".repeat(max(0, digits - (s.length - dot - 1)))
    }
}
