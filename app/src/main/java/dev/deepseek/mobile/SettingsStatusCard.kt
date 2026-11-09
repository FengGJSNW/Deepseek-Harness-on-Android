package dev.deepseek.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SettingsStatusCard(
    icon: ImageVector,
    title: String,
    detail: String = "",
    tint: Color = WhaleMuted,
    announce: Boolean = false,
) {
    Surface(
        Modifier.fillMaxWidth()
            .then(
                if (announce) Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                else Modifier,
            ),
        shape = ContinuousShape(16.dp),
        color = Color(0xFFF8F8F7),
        border = BorderStroke(1.dp, WhaleLine),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = tint)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(title, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
                if (detail.isNotBlank())
                    Text(
                        detail,
                        Modifier.padding(top = 3.dp),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = WhaleMuted,
                    )
            }
        }
    }
}
