package dev.deepseek.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    TextButton(onClick = { editing = BubblePhrase(UUID.randomUUID().toString(), "") }) {
        Text("添加文案")
    }
    val phrase = editing
    if (phrase != null) {
        var text by remember(phrase.id) { mutableStateOf(phrase.text) }
        var condition by remember(phrase.id) { mutableStateOf(phrase.condition) }
        var sticker by remember(phrase.id) { mutableStateOf(phrase.sticker) }
        WhaleSheet("气泡文案", close = { editing = null }) {
            SectionLabel("表情包 · 显示在文字上方")
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf(
                        "" to "无",
                        "cute" to "摸摸",
                        "ohjing" to "哦鲸鲸",
                        "rice" to "干饭",
                        "sleep" to "睡觉",
                    )
                    .forEach { (id, label) ->
                        TextButton(onClick = { sticker = id }) {
                            Text(if (sticker == id) "✓ $label" else label, fontSize = 12.sp)
                        }
                    }
            }
            Row {
                TextButton(onClick = { MainActivity.current?.pickBubbleSticker { sticker = it } }) {
                    Text("上传表情包")
                }
                if (sticker.isNotBlank()) TextButton(onClick = { sticker = "" }) { Text("移除表情包") }
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
            Text(
                "可用变量：{limit} {cost} {tokens} {sessions}。每日花费为已观测 CNY 余额降幅估计；跨日首次查询前的消耗无法计入。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = WhaleMuted,
                modifier = Modifier.padding(vertical = 10.dp),
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
                TextButton(
                    onClick = {
                        ExperienceSettings.phrases.removeAll { it.id == phrase.id }
                        ExperienceSettings.save()
                        editing = null
                    },
                ) {
                    Text("删除")
                }
                Spacer(Modifier.weight(1f))
                Button(
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
