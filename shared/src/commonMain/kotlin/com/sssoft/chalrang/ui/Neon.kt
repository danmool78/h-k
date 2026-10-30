package com.sssoft.chalrang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** 찰랑 분석 화면의 네온 스타일 (차량 화면, 어두운 실내) */
object Neon {
    val Bg0 = Color(0xFF030814)
    val Bg1 = Color(0xFF07132A)
    val CardTop = Color(0xEB0B1D36)
    val CardBottom = Color(0xEB06111F)
    val Edge = Color(0xFF2A8BE0)
    val Cyan = Color(0xFF29C4F6)
    val Blue = Color(0xFF2F7CF6)
    val Amber = Color(0xFFFFB31A)
    val Orange = Color(0xFFFF7A1A)
    val Red = Color(0xFFFF4D5E)
    val Purple = Color(0xFFB07CFF)
    /** 위아래 (z축) */
    val Green = Color(0xFF5BE3A0)
    val White = Color(0xFFF2F7FF)
    val Text = Color(0xFFE3EEFF)
    val Dim = Color(0xFF8AA2C2)
    val Faint = Color(0xFF5A7090)
    val Grid = Color(0x2E4FA3E0)

    val CardShape = RoundedCornerShape(14.dp)
}

/** 분석 화면 바탕: 짙은 남색 + 위쪽 푸른 빛 */
@Composable
fun NeonBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Neon.Bg1, Neon.Bg0)))
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(Color(0x332F7CF6), Color.Transparent), center = Offset(size.width * 0.72f, 0f), radius = size.width * 0.55f))
                drawRect(Brush.radialGradient(listOf(Color(0x2229C4F6), Color.Transparent), center = Offset(0f, size.height), radius = size.width * 0.4f))
            },
        content = content,
    )
}

/** 빛나는 테두리 카드 */
fun Modifier.neonCard(): Modifier = this
    .drawBehind {
        // 바깥으로 번지는 빛
        for ((w, a) in listOf(10f to 0.05f, 5f to 0.08f)) {
            drawRoundRect(
                Neon.Edge.copy(alpha = a), topLeft = Offset(-w / 2, -w / 2),
                size = androidx.compose.ui.geometry.Size(size.width + w, size.height + w),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx() + w / 2), style = Stroke(w),
            )
        }
    }
    .background(Brush.verticalGradient(listOf(Neon.CardTop, Neon.CardBottom)), Neon.CardShape)
    .border(1.dp, Brush.linearGradient(listOf(Neon.Edge.copy(alpha = 0.85f), Neon.Edge.copy(alpha = 0.18f), Neon.Cyan.copy(alpha = 0.5f))), Neon.CardShape)

/** 빛나는 선: 넓고 흐린 선 두 겹 + 선명한 선 */
fun DrawScope.glowPath(path: Path, color: Color, width: Float, dash: Boolean = false) {
    val effect = if (dash) PathEffect.dashPathEffect(floatArrayOf(10f, 7f)) else null
    if (!dash) {
        drawPath(path, color.copy(alpha = 0.10f), style = Stroke(width * 5, cap = StrokeCap.Round, pathEffect = effect))
        drawPath(path, color.copy(alpha = 0.22f), style = Stroke(width * 2.4f, cap = StrokeCap.Round, pathEffect = effect))
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, pathEffect = effect))
}

fun DrawScope.glowLine(color: Color, a: Offset, b: Offset, width: Float) {
    drawLine(color.copy(alpha = 0.15f), a, b, strokeWidth = width * 4, cap = StrokeCap.Round)
    drawLine(color, a, b, strokeWidth = width, cap = StrokeCap.Round)
}

/** 헤더의 차량 실루엣 (선으로 그린 SUV, 빛나는 윤곽과 뒤로 흐르는 빛줄기) */
fun DrawScope.carSilhouette(color: Color) {
    val w = size.width
    val h = size.height
    fun p(x: Float, y: Float) = Offset(w * x, h * y)
    // 뒤로 흐르는 빛줄기
    for ((y, len, a) in listOf(Triple(0.42f, 0.30f, 0.35f), Triple(0.58f, 0.22f, 0.25f), Triple(0.72f, 0.34f, 0.3f))) {
        drawLine(
            Brush.horizontalGradient(listOf(Color.Transparent, color.copy(alpha = a)), startX = 0f, endX = w * len),
            p(0f, y), p(len, y), strokeWidth = 1.5f,
        )
    }
    val body = Path().apply {
        moveTo(w * 0.30f, h * 0.80f)
        lineTo(w * 0.29f, h * 0.60f)
        quadraticTo(w * 0.30f, h * 0.50f, w * 0.38f, h * 0.47f)
        lineTo(w * 0.50f, h * 0.43f)
        quadraticTo(w * 0.58f, h * 0.20f, w * 0.66f, h * 0.17f)
        lineTo(w * 0.86f, h * 0.15f)
        quadraticTo(w * 0.93f, h * 0.16f, w * 0.96f, h * 0.30f)
        lineTo(w * 0.985f, h * 0.48f)
        quadraticTo(w * 1.0f, h * 0.58f, w * 0.99f, h * 0.78f)
        lineTo(w * 0.94f, h * 0.80f)
        moveTo(w * 0.84f, h * 0.80f)
        lineTo(w * 0.48f, h * 0.80f)
        moveTo(w * 0.38f, h * 0.80f)
        lineTo(w * 0.30f, h * 0.80f)
    }
    val window = Path().apply {
        moveTo(w * 0.53f, h * 0.43f)
        quadraticTo(w * 0.60f, h * 0.25f, w * 0.67f, h * 0.23f)
        lineTo(w * 0.85f, h * 0.22f)
        quadraticTo(w * 0.90f, h * 0.24f, w * 0.92f, h * 0.42f)
        close()
        moveTo(w * 0.74f, h * 0.23f)
        lineTo(w * 0.73f, h * 0.43f)
    }
    glowPath(body, color, 2f)
    glowPath(window, color.copy(alpha = 0.7f), 1.3f)
    for (cx in listOf(0.43f, 0.89f)) {
        val r = h * 0.15f
        drawCircle(color.copy(alpha = 0.12f), radius = r * 1.5f, center = p(cx, 0.80f), style = Stroke(5f))
        drawCircle(color, radius = r, center = p(cx, 0.80f), style = Stroke(2f))
        drawCircle(color.copy(alpha = 0.6f), radius = r * 0.45f, center = p(cx, 0.80f), style = Stroke(1.2f))
    }
}

/** 켜고 끄는 알약 버튼 (켜지면 파란 빛) */
@androidx.compose.runtime.Composable
fun NeonChip(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    val look = if (on) {
        Modifier.background(Brush.verticalGradient(listOf(Color(0xFF1C64C8), Color(0xFF0E3A80))), shape)
            .border(1.5.dp, Neon.Cyan, shape)
    } else {
        Modifier.background(Color(0x99091A30), shape).border(1.dp, Neon.Edge.copy(alpha = 0.4f), shape)
    }
    androidx.compose.foundation.layout.Row(
        modifier.height(40.dp).clip(shape).then(look).clickable(onClick = onClick).padding(horizontal = 12.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        if (icon != null) {
            androidx.compose.material3.Icon(icon, null, tint = if (on) Neon.White else Neon.Dim, modifier = Modifier.size(18.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
        }
        androidx.compose.material3.Text(
            label, color = if (on) Neon.White else Neon.Text, fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp),
            fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.Bold else null, maxLines = 1,
        )
    }
}
