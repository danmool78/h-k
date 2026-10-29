package com.sssoft.chalrang.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import com.sssoft.chalrang.core.WaterCup
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

private val Water = Color(0xFF4FC3F7)
private val WaterWarn = Color(0xFFFFB74D)
private val WaterDanger = Color(0xFFFF7043)
private val Glass = Color(0xCCFFFFFF)

/**
 * 물컵 (옆에서 본 모습). 숫자·글자 없이 한눈에:
 * - 수면 기울기 = 좌우 쏠림, 수면 타원의 두께 = 앞뒤 출렁임
 * - 넘치면 가장자리에서 물방울
 * - 컵 아래 물웅덩이 = 멀미 누적량, 색이 주황·빨강으로 변한다
 */
@Composable
fun CupView(
    tiltX: Double,
    tiltY: Double,
    level: Double,
    dose: Double,
    spilling: Boolean,
    modifier: Modifier = Modifier,
) {
    val time by rememberInfiniteTransition(label = "t").animateFloat(
        0f, 1000f, infiniteRepeatable(tween(1_000_000, easing = LinearEasing)), label = "t",
    )
    val puddle by animateFloatAsState((dose / 1.4).coerceIn(0.0, 1.0).toFloat(), label = "puddle")
    val shownLevel by animateFloatAsState(level.toFloat(), label = "level")

    Canvas(modifier) {
        val w = min(size.width * 0.5f, size.height * 0.62f)
        val h = w * 1.15f
        val cx = size.width / 2
        val top = (size.height - h) / 2 - h * 0.08f
        val bottom = top + h
        val topHalf = w / 2
        val bottomHalf = w * 0.36f

        fun halfAt(y: Float) = bottomHalf + (topHalf - bottomHalf) * ((bottom - y) / h)

        val waterColor = when {
            dose >= 1.0 -> WaterDanger
            dose >= 0.6 -> lerp(WaterWarn, WaterDanger, ((dose - 0.6) / 0.4).toFloat())
            else -> lerp(Water, WaterWarn, (dose / 0.6).toFloat().coerceIn(0f, 1f) * 0.5f)
        }

        // 물웅덩이 (누적량)
        if (puddle > 0.005f) {
            val pw = w * (0.5f + 1.3f * puddle)
            val ph = w * (0.05f + 0.07f * puddle)
            drawOval(
                waterColor.copy(alpha = 0.55f),
                topLeft = Offset(cx - pw / 2, bottom + h * 0.04f),
                size = Size(pw, ph),
            )
        }

        val cupPath = Path().apply {
            moveTo(cx - topHalf, top)
            lineTo(cx + topHalf, top)
            lineTo(cx + bottomHalf, bottom)
            lineTo(cx - bottomHalf, bottom)
            close()
        }

        // 수면: 좌우 기울기 → 가장자리 높이 차, 컵 높이 기준 (넘치는 기울기 = 가장자리)
        val surfaceY = bottom - h * shownLevel
        val rise = (tiltY / WaterCup.MAX_FREEBOARD).toFloat() * h
        val wave = (abs(tiltX) / WaterCup.MAX_FREEBOARD).toFloat() * h
        val ripple = h * 0.012f * sin(time * 2.2f)
        val leftY = surfaceY - rise + ripple
        val rightY = surfaceY + rise - ripple

        clipPath(cupPath) {
            val water = Path().apply {
                moveTo(cx - topHalf * 1.2f, leftY)
                // 수면은 살짝 물결치게
                val steps = 24
                for (i in 0..steps) {
                    val f = i / steps.toFloat()
                    val x = cx - topHalf * 1.2f + topHalf * 2.4f * f
                    val y = leftY + (rightY - leftY) * f + h * 0.01f * sin(f * 9f + time * 3f)
                    lineTo(x, y)
                }
                lineTo(cx + topHalf * 1.2f, bottom + 2)
                lineTo(cx - topHalf * 1.2f, bottom + 2)
                close()
            }
            drawPath(
                water,
                Brush.verticalGradient(
                    listOf(waterColor.copy(alpha = 0.9f), waterColor.copy(alpha = 0.6f)),
                    startY = min(leftY, rightY), endY = bottom,
                ),
            )
            // 수면 타원: 앞뒤로 출렁일수록 두꺼워진다
            val midY = (leftY + rightY) / 2
            val half = halfAt(midY)
            val eh = w * 0.04f + wave * 0.6f
            drawOval(
                Color.White.copy(alpha = 0.22f),
                topLeft = Offset(cx - half, midY - eh / 2),
                size = Size(half * 2, eh),
            )
        }

        // 컵
        drawPath(cupPath, Glass, style = Stroke(width = w * 0.025f))
        drawLine(Glass, Offset(cx - topHalf - w * 0.03f, top), Offset(cx + topHalf + w * 0.03f, top), strokeWidth = w * 0.03f)

        // 넘치는 물방울: 수면이 높은 쪽 가장자리에서 (앞뒤로 넘치면 양쪽)
        if (spilling) {
            val sides = when {
                abs(tiltY) > abs(tiltX) * 0.7 -> listOf(if (tiltY > 0) -1f else 1f)
                else -> listOf(-1f, 1f)
            }
            for (side in sides) {
                for (i in 0 until 6) {
                    val phase = ((time * 1.6f + i / 6f) % 1f)
                    val x = cx + side * (topHalf + w * 0.04f + phase * w * 0.18f)
                    val y = top + phase * phase * h * 0.9f
                    drawCircle(waterColor, radius = w * (0.022f - 0.01f * phase), center = Offset(x, y))
                }
            }
        }
    }
}
