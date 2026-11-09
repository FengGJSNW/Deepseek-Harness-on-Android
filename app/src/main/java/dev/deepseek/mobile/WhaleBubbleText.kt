package dev.deepseek.mobile

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** Widget main 5247dd6: speech blue, 800-weight amounts, coral expenses, green off-peak. */
internal val WhaleBubbleBlue = Color(0xFF536BA9)
internal val WhaleBubbleValue = Color(0xFF2FA24C)
internal val WhaleBubbleCost = Color(0xFFE0433F)

internal fun styledWhaleBubble(template: String, resolve: (String) -> String): AnnotatedString =
    buildAnnotatedString {
        var cursor = 0
        for (match in Regex("\\{([a-zA-Z]+)\\}").findAll(template)) {
            append(template.substring(cursor, match.range.first))
            val key = match.groupValues[1]
            pushStringAnnotation("placeholder", key)
            withStyle(
                SpanStyle(
                    color = if (key == "cost") WhaleBubbleCost else WhaleBubbleValue,
                    fontWeight = FontWeight.ExtraBold,
                ),
            ) {
                append(resolve(match.value))
            }
            pop()
            cursor = match.range.last + 1
        }
        append(template.substring(cursor))
    }
