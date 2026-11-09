package dev.deepseek.mobile

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.widget.ImageView
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal fun CustomBubbleSticker(id: String) {
    val context = LocalContext.current
    val drawable =
        remember(id) {
            runCatching {
                ImageDecoder.decodeDrawable(
                    ImageDecoder.createSource(AttachmentStore(context).file(id)),
                )
            }
                .getOrNull()
        }
    DisposableEffect(drawable) {
        (drawable as? AnimatedImageDrawable)?.start()
        onDispose { (drawable as? AnimatedImageDrawable)?.stop() }
    }
    if (drawable != null)
        AndroidView(
            factory = {
                ImageView(it).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageDrawable(drawable)
                }
            },
            modifier = Modifier.size(68.dp),
        )
}
