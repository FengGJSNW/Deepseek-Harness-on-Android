package dev.deepseek.mobile

import androidx.compose.runtime.*

/** Local, contextual banter; account numbers always come from the native balance cache. */
object WhaleCompanion {
    var mood by mutableStateOf("idle")
    var text by mutableStateOf("")
    var styledText by mutableStateOf(androidx.compose.ui.text.AnnotatedString(""))
        private set

    var sticker by mutableIntStateOf(0)
    var customSticker by mutableStateOf("")
    var revision by mutableIntStateOf(0)
    var touches by mutableIntStateOf(0)
    var resting by mutableStateOf(false)
    var showBalance by mutableStateOf(false)
    private var flashOffer by mutableStateOf(false)
    private var interactionSession by mutableStateOf("")
    private val conversation
        get() = AssistantState.sessions.firstOrNull { it.id == AssistantState.selectedId }

    val canUseFlash
        get() =
            flashOffer &&
                interactionSession == AssistantState.selectedId &&
                conversation?.model == "deepseek-v4-pro"

    val balanceText
        get() =
            when {
                AssistantState.balance.isNotBlank() && AssistantState.balance != "未查询" ->
                    AssistantState.balance
                !AssistantState.configured -> "尚未连接 API"
                AssistantState.balanceRefreshing -> "正在读取…"
                else -> "暂未查询，可在连接设置刷新"
            }

    fun react(next: String, words: String, picture: Int = 0) {
        mood = next
        text = words
        styledText = androidx.compose.ui.text.AnnotatedString(words)
        sticker = picture
        customSticker = ""
        showBalance = false
        flashOffer = false
        revision++
    }

    fun touch() {
        resting = false
        touches++
        interactionSession = AssistantState.selectedId
        val candidates =
            ExperienceSettings.phrases.filter { ExperienceSettings.matches(it.condition) }
        val phrase = candidates.getOrNull((touches - 1).mod(candidates.size.coerceAtLeast(1)))
        if (phrase != null) {
            reactTemplate(
                if (AssistantState.busy) "think" else "happy",
                phrase.text,
                ExperienceSettings.sticker(phrase.sticker),
            )
            customSticker = phrase.sticker.takeIf { AttachmentStore.valid(it) }.orEmpty()
        } else react("wave", "我在呢。你慢慢说。")
        AgentService.current?.refreshPetBalance()
    }

    internal fun reactTemplate(next: String, template: String, picture: Int = 0) {
        val rendered = styledWhaleBubble(template, ExperienceSettings::render)
        react(next, rendered.text, picture)
        styledText = rendered
    }

    fun useFlash() {
        if (!canUseFlash) return
        conversation?.model = "deepseek-flash"
        AssistantState.persist()
        react("wave", "换好 Flash 啦，思考档位替你留着。")
        showBalance = false
    }

    fun feed() {
        resting = false
        react("happy", "就一碗！吃饱了再陪你干活。", R.drawable.meme_rice)
    }

    fun rest() {
        if (AssistantState.busy) {
            react("think", "等这轮忙完，再一起摸鱼。")
            return
        }
        resting = true
        react("sleep", "我眯一会儿，有任务再叫我。", R.drawable.meme_sleep)
    }

    fun animation(): Int =
        when (if (resting && AssistantState.running == null) "sleep" else mood) {
            "think",
            "waiting" -> R.drawable.pet_think
            "happy" -> R.drawable.pet_happy
            "wave" -> R.drawable.pet_wave
            "done" -> R.drawable.pet_done
            "error",
            "cancelled" -> R.drawable.pet_error
            "sleep" -> R.drawable.pet_sleep
            else -> R.drawable.pet_idle
        }

    fun settle() {
        mood = if (resting) "sleep" else if (AssistantState.running != null) "think" else "idle"
        text = ""
        styledText = androidx.compose.ui.text.AnnotatedString("")
        sticker = 0
        showBalance = false
        flashOffer = false
    }
}

internal val whalePhrases = listOf("哦鲸鲸…", "我在呢，慢慢说。", "摸摸收到！", "今天也陪你一起。")
