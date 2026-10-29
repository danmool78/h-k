package com.sssoft.chalrang.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.sssoft.chalrang.res.Res
import com.sssoft.chalrang.res.nanum_gothic
import com.sssoft.chalrang.res.nanum_gothic_bold
import org.jetbrains.compose.resources.Font

/** 앱과 웹이 같은 글꼴(나눔고딕)로 같은 화면을 그린다 */
@Composable
fun ChalrangTheme(content: @Composable () -> Unit) {
    val family = FontFamily(
        Font(Res.font.nanum_gothic, FontWeight.Normal),
        Font(Res.font.nanum_gothic_bold, FontWeight.Bold),
        Font(Res.font.nanum_gothic_bold, FontWeight.SemiBold),
    )
    val base = Typography()
    val typography = Typography(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family),
    )
    MaterialTheme(colorScheme = darkColorScheme(), typography = typography, content = content)
}
