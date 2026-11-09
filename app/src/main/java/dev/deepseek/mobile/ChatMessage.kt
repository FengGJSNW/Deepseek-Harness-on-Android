package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*

/** 聊天消息：用户/助手气泡、Markdown文字与本地图片。 */
@Composable
internal fun ChatMessage(entry: ChatEntry, showHeader: Boolean = true) {
    if (entry.type == "terminal") {
        TerminalCard(entry.text)
        return
    }
    val clipboard = LocalClipboardManager.current
    if (entry.type == "system") {
        val failed = entry.text.contains("失败") || entry.text.startsWith("Error", ignoreCase = true)
        Surface(
            color = if (failed) Color(0xFFFFF5F3) else Color(0xFFF6F5F2),
            shape = ContinuousShape(10.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    if (failed) AppIcons.ShieldOff else AppIcons.Description,
                    null,
                    Modifier.padding(top = 2.dp).size(15.dp),
                    tint = if (failed) Color(0xFFB14940) else ReadingStyle.secondary,
                )
                Text(
                    entry.text,
                    Modifier.padding(start = 8.dp),
                    color = if (failed) Color(0xFF96564D) else ReadingStyle.secondary,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                )
            }
        }
        return
    }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (entry.type == "user") Alignment.End else Alignment.Start,
    ) {
        if (entry.type == "assistant" && showHeader)
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentAvatar(30.dp)
                Text(
                    AgentIdentity.name,
                    fontSize = 12.sp,
                    color = WhaleMuted,
                    modifier = Modifier.padding(start = 9.dp),
                )
            }
        if (entry.images.isNotEmpty())
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                entry.images.forEach { AttachmentImage(it, 66) }
            }
        entry.files.forEach { AttachmentCard(it, upload = entry.type == "user") }
        if (entry.text.isNotBlank()) {
            if (entry.type == "user") {
                Surface(
                    color = Color(0xFFF1F1F1),
                    shape = ContinuousShape(18.dp),
                    modifier = Modifier.widthIn(max = 310.dp),
                ) {
                    Text(
                        entry.text,
                        Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        fontFamily = DshaFonts.family(FontRole.UI),
                    )
                }
            } else {
                MarkdownText(entry.text, Modifier.widthIn(max = 700.dp).padding(vertical = 3.dp))
            }
        }
        if (entry.type == "assistant")
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(entry.text))
                    AgentService.current?.toast("已复制")
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(AppIcons.ContentCopy, "复制回复", Modifier.size(16.dp), tint = WhaleMuted)
            }
    }
}

@Composable
internal fun AttachmentImage(id: String, size: Int) {
    val ctx = LocalContext.current
    val jobs = rememberCoroutineScope()
    val bitmap by
        produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, id) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val options =
                            android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                        android.graphics.BitmapFactory.decodeFile(
                                java.io.File(ctx.filesDir, "screens/$id.jpg").path,
                                options,
                            )
                            ?.asImageBitmap()
                    }
                        .getOrNull()
                }
        }
    if (bitmap != null)
        Image(
            bitmap!!,
            "预览图片",
            Modifier.size(size.dp).clip(ContinuousShape(10.dp)).clickable {
                jobs.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            AttachmentStore(ctx).legacyImage(id, AssistantState.current.id)
                        }
                    }
                        .onSuccess { FilePreviewState.open(it.id) }
                        .onFailure { AgentService.current?.toast("图片已不可用") }
                }
            },
        )
}
