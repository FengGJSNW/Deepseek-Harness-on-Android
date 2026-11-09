package dev.deepseek.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview

/** Debug-only previews: no service, key or account request is started. */
@Preview(name = "推理 · Max", widthDp = 382, showBackground = true, backgroundColor = 0xFF181818)
@Composable
private fun MaxReasoningPreview() {
    MaterialTheme {
        ReasoningCard(remember { Conversation().apply { effort = "max" } }, Modifier.fillMaxWidth())
    }
}

@Preview(name = "推理 · Ultra", widthDp = 382, showBackground = true, backgroundColor = 0xFF181818)
@Composable
private fun UltraReasoningPreview() {
    MaterialTheme {
        ReasoningCard(
            remember {
                Conversation().apply {
                    effort = "ultra"
                    model = "deepseek-v4-pro"
                }
            },
            Modifier.fillMaxWidth(),
        )
    }
}

@Preview(name = "输入框与鲸鲸衔接", widthDp = 392, heightDp = 280, showBackground = true)
@Preview(name = "输入框 · 大字体", widthDp = 320, heightDp = 320, fontScale = 1.3f, showBackground = true)
@Composable
private fun ComposerAndPetPreview() {
    val c = remember {
        Conversation().apply {
            draft = "一起把今天的想法整理一下"
            effort = "high"
        }
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = WhaleBlue, onSurface = WhaleInk)) {
        ConversationLayout(
            modifier = Modifier.fillMaxSize().background(Color.White),
            composer = { Composer(c, onSend = {}) },
            companion = { WhaleDock(allowBubble = false) {} },
            header = {},
        ) {
            Spacer(Modifier.fillMaxSize())
        }
    }
}
