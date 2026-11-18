package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

@Composable
internal fun BubbleSettings() {
    var editing by remember { mutableStateOf<BubblePhrase?>(null) }
    ExperienceSettings.phrases.forEachIndexed { index, phrase ->
        Row(Modifier.fillMaxWidth()) {
            Text(
                "文案${index + 1}",
                Modifier.padding(vertical = 15.dp),
                fontSize = 12.sp,
                color = WhaleMuted,
            )
            Text(
                phrase.text,
                Modifier.weight(1f).padding(15.dp).clickable { editing = phrase },
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            SmallIcon(AppIcons.ChevronRight, "文案${index + 1}详情") { editing = phrase }
        }
    }
    DshaTextButton(onClick = { editing = BubblePhrase(UUID.randomUUID().toString(), "") }) {
        Text("添加文案")
    }
    val phrase = editing
    if (phrase != null) {
        var text by remember(phrase.id) { mutableStateOf(phrase.text) }
        var condition by remember(phrase.id) { mutableStateOf(phrase.condition) }
        var sticker by remember(phrase.id) { mutableStateOf(phrase.sticker) }
        WhaleSheet("气泡文案", close = { editing = null }) {
            SectionLabel("表情包")
            Box(Modifier.size(76.dp)) {
                Box(
                    Modifier.size(68.dp)
                        .clip(ContinuousShape(12.dp))
                        .background(Color(0xFFF0F0F0))
                        .clickable { MainActivity.current?.pickBubbleSticker { sticker = it } },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        sticker.isBlank() ->
                            Icon(AppIcons.Add, "导入表情包", Modifier.size(22.dp), tint = WhaleMuted)
                        ExperienceSettings.sticker(sticker) != 0 ->
                            Image(
                                painterResource(ExperienceSettings.sticker(sticker)),
                                "表情包预览",
                                Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        else -> CustomBubbleSticker(sticker, Modifier.fillMaxSize(), crop = true)
                    }
                }
                if (sticker.isNotBlank()) {
                    Box(
                        Modifier.align(Alignment.TopEnd)
                            .size(24.dp)
                            .background(Color.White, CircleShape)
                            .clip(CircleShape)
                            .clickable { sticker = "" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "−",
                            color = WhaleInk,
                            fontSize = 17.sp,
                            modifier = Modifier.semantics { contentDescription = "移除表情包" },
                        )
                    }
                }
            }
            OutlinedTextField(
                text,
                { text = it.take(2000) },
                label = { Text("文案") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6,
                shape = ContinuousShape(14.dp),
                visualTransformation = UprightSymbols.transformation,
            )
            OutlinedTextField(
                condition,
                { condition = it.take(300) },
                label = { Text("触发条件（留空表示始终可用）") },
                placeholder = { Text("{cost}>=30") },
                modifier = Modifier.fillMaxWidth(),
                isError = !ExperienceSettings.validCondition(condition),
                shape = ContinuousShape(14.dp),
                visualTransformation = UprightSymbols.transformation,
            )
            Text(
                "可用变量：{limit} {cost} {tokens} {sessions}。每日花费为已观测 CNY 余额降幅估计；跨日首次查询前的消耗无法计入。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = WhaleMuted,
                modifier = Modifier.padding(vertical = 10.dp),
            )
            Text(
                "支持 >= <= > < == !=，用 && 组合。还可用 {busy} {waiting} {heavy} {draft} {ultra}，值为 0 或 1。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = WhaleMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "预览：${ExperienceSettings.render(text)}",
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            Row(Modifier.fillMaxWidth()) {
                DshaTextButton(
                    onClick = {
                        ExperienceSettings.phrases.removeAll { it.id == phrase.id }
                        ExperienceSettings.save()
                        editing = null
                    },
                ) {
                    Text("删除")
                }
                Spacer(Modifier.weight(1f))
                DshaButton(
                    enabled = text.isNotBlank() && ExperienceSettings.validCondition(condition),
                    onClick = {
                        val value =
                            phrase.copy(text = text, condition = condition, sticker = sticker)
                        val i = ExperienceSettings.phrases.indexOfFirst { it.id == phrase.id }
                        if (i < 0) ExperienceSettings.phrases.add(value)
                        else ExperienceSettings.phrases[i] = value
                        ExperienceSettings.save()
                        editing = null
                    },
                ) {
                    Text("保存")
                }
            }
        }
    }
}
