package dev.deepseek.mobile

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun CustomBubbleSticker(
    id: String,
    modifier: Modifier = Modifier.size(68.dp),
    crop: Boolean = false,
) {
    val context = LocalContext.current
    val target = with(LocalDensity.current) { 80.dp.roundToPx() }.coerceAtLeast(1)
    val drawable by
        produceState<Drawable?>(null, id, target) {
            value = null
            value =
                withContext(Dispatchers.IO) {
                    runCatching {
                        ImageDecoder.decodeDrawable(
                            ImageDecoder.createSource(AttachmentStore(context).file(id)),
                        ) { decoder, info, _ ->
                            decoder.setTargetSampleSize(
                                (minOf(info.size.width, info.size.height) / target).coerceAtLeast(
                                    1,
                                ),
                            )
                        }
                    }
                        .getOrNull()
                }
        }
    DisposableEffect(drawable) {
        (drawable as? AnimatedImageDrawable)?.start()
        onDispose { (drawable as? AnimatedImageDrawable)?.stop() }
    }
    if (drawable != null)
        AndroidView(
            factory = {
                ImageView(it)
            },
            update = {
                it.scaleType =
                    if (crop) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                it.contentDescription = "表情包预览"
                it.setImageDrawable(drawable)
            },
            modifier = modifier,
        )
}
