package dev.deepseek.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Shared contents for the sidebar search and browser address, including IME behavior. */
@Composable
internal fun PrismSearchField(
    value: String,
    change: (String) -> Unit,
    placeholder: String,
    actionLabel: String,
    action: () -> Unit,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentAlpha: Float = 1f,
    surface: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    Box(modifier.clip(ContinuousShape(26.dp))) {
        if (surface) LiquidGlass(Modifier.matchParentSize(), corner = 26f)
        Row(
            Modifier.fillMaxSize().graphicsLayer { alpha = contentAlpha },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DshaIconButton(actionLabel, action, enabled = enabled, size = 48.dp) {
                Icon(AppIcons.Search, null, Modifier.size(22.dp), tint = WhaleInk)
            }
            BasicTextField(
                value,
                change,
                Modifier.weight(1f).padding(end = 16.dp).then(fieldModifier),
                enabled = enabled,
                singleLine = true,
                textStyle = PrismTypography.body.copy(color = WhaleInk),
                cursorBrush = SolidColor(WhaleBlue),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                decorationBox = { field ->
                    Box {
                        if (value.isEmpty())
                            Text(
                                placeholder,
                                color = WhaleMuted,
                                style = PrismTypography.body,
                                maxLines = 1,
                            )
                        field()
                    }
                },
            )
        }
    }
}
