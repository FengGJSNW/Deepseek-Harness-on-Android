package dev.deepseek.mobile

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** prism_ui/typography.dart's Android type scale; conversation prose keeps its own family. */
internal object PrismTypography {
    private fun style(size: Int, leading: Int, weight: FontWeight = FontWeight.Normal) =
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = size.sp,
            lineHeight = leading.sp,
            fontWeight = weight,
            letterSpacing = 0.sp,
        )

    val pageTitle = style(24, 30, FontWeight.SemiBold)
    val headline = style(17, 22, FontWeight.SemiBold)
    val body = style(17, 22)
    val callout = style(16, 21)
    val subheadline = style(15, 20)
    val footnote = style(13, 18)
    val caption = style(12, 16)
    val material =
        Typography(
            displayLarge = style(40, 44, FontWeight.Bold),
            displayMedium = style(34, 41, FontWeight.Bold),
            displaySmall = style(28, 34, FontWeight.Bold),
            headlineLarge = style(28, 34, FontWeight.Bold),
            headlineMedium = pageTitle,
            headlineSmall = style(22, 28),
            titleLarge = style(20, 25),
            titleMedium = headline,
            titleSmall = subheadline,
            bodyLarge = body,
            bodyMedium = callout,
            bodySmall = footnote,
            labelLarge = headline,
            labelMedium = footnote,
            labelSmall = caption,
        )
}
