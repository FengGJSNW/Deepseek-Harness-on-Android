package dev.deepseek.mobile

import android.content.Context
import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject

internal data class BubblePhrase(
    val id: String,
    val text: String,
    val condition: String = "",
    val sticker: String = "",
) {
    fun json() =
        JSONObject()
            .put("id", id)
            .put("text", text)
            .put("condition", condition)
            .put("sticker", sticker)
}

internal object ExperienceSettings {
    var reasoningMode by mutableStateOf("island")
    var browserMode by mutableStateOf(BrowserProvider.LOCAL)
    var browserEnabled: Boolean
        get() = browserMode != BrowserProvider.BLOCKED
        set(value) {
            if (!value) browserMode = BrowserProvider.BLOCKED
            else if (!browserEnabled) browserMode = BrowserProvider.LOCAL
        }

    var maxAgents by mutableIntStateOf(4)
    var textScale by mutableFloatStateOf(1f)
    var lineSpacing by mutableFloatStateOf(1.4f)
    var taskNavigation by mutableStateOf(true)
    var petMotion by mutableStateOf(PetMotionSettings())
    var keepTasksAwake by mutableStateOf(true)
    var privilegedKeepAlive by mutableStateOf(false)
    val phrases = mutableStateListOf<BubblePhrase>()
    private var context: Context? = null

    fun initialize(ctx: Context) {
        context = ctx.applicationContext
        val p = ctx.getSharedPreferences("experience-v1", Context.MODE_PRIVATE)
        reasoningMode = p.getString("reasoning", "island") ?: "island"
        browserMode =
            BrowserProvider.parse(
                p.getString(
                        "browserMode",
                        if (p.getBoolean("browser", true)) "local" else "blocked",
                    )
                    .orEmpty(),
            )
        maxAgents = p.getInt("agents", 4).coerceIn(1, 8)
        textScale = p.getFloat("textScale", 1f).coerceIn(.85f, 1.3f)
        lineSpacing = p.getFloat("lineSpacing", 1.4f).coerceIn(1.2f, 1.9f)
        taskNavigation = p.getBoolean("taskNavigation", true)
        keepTasksAwake = p.getBoolean("keepTasksAwake", true)
        privilegedKeepAlive = p.getBoolean("privilegedKeepAlive", false)
        petMotion =
            PetMotionSettings(
                p.getFloat("petMovement", 24f).coerceIn(8f, 64f),
                p.getFloat("petDamping", .42f).coerceIn(.25f, 1f),
                p.getInt("petInterval", 600).coerceIn(400, 1800),
                p.getFloat("petRate", 1f).coerceIn(.5f, 2f),
                p.getInt("petPressUnits", 10).coerceIn(0, 30),
            )
        phrases.clear()
        val raw = p.getString("phrases", null)
        if (raw != null)
            runCatching {
                val values = JSONArray(raw)
                for (i in 0 until values.length()) values.getJSONObject(i).let {
                    phrases.add(
                        BubblePhrase(
                            it.getString("id"),
                            it.getString("text"),
                            it.optString("condition"),
                            it.optString("sticker"),
                        ),
                    )
                }
            }
                .onFailure { AssistantState.error = "气泡配置读取失败，原配置已保留" }
        else
            phrases.addAll(
                listOf(
                    BubblePhrase("touch", "哦鲸鲸… 再摸一下？", sticker = "cute"),
                    BubblePhrase("wave", "收到摸摸！今天也会认真干活。"),
                    BubblePhrase("hair", "呆毛要被你摸翘啦。", sticker = "ohjing"),
                    BubblePhrase("here", "我在呢。你慢慢说。"),
                    BubblePhrase("heavy", "Pro 还开高推理？穷就别用…逗你的，本鲸认真想。", "{heavy}==1"),
                    BubblePhrase("waiting", "先帮鲸鲸点个头，这一步在等你确认。", "{waiting}==1"),
                    BubblePhrase("working", "本鲸在认真干活，摸摸也不会停工。", "{busy}==1"),
                    BubblePhrase("queue", "还有{queued}个任务排队，鲸鲸一个一个来。", "{queued}>0"),
                    BubblePhrase("thinking", "脑袋在转了，呆毛也在转。", "{busy}==1"),
                    BubblePhrase("draft", "草稿都备好啦，就等你发车。", "{draft}==1"),
                    BubblePhrase("ultra", "Ultra 都安排上了？本鲸的呆毛要冒星星啦。", "{ultra}==1"),
                    BubblePhrase("serious", "这题值得认真想，给本鲸一点时间。", "{heavy}==1"),
                    BubblePhrase("tokens", "累计陪你想了{tokens} tokens，呆毛辛苦啦。", "{tokens}>0"),
                    BubblePhrase("sessions", "换个话题也没关系，每个会话的草稿都留着呢。", "{sessions}>1"),
                    BubblePhrase("limit", "剩余额度{limit}"),
                    BubblePhrase("cost", "主人，你今天已经花了{cost}，再用就要变成穷光蛋惹", "{cost}>=30"),
                ),
            )
    }

    fun save() {
        context
            ?.getSharedPreferences("experience-v1", Context.MODE_PRIVATE)
            ?.edit()
            ?.putString("reasoning", reasoningMode)
            ?.putString("browserMode", browserMode.key)
            ?.putInt("agents", maxAgents)
            ?.putFloat("textScale", textScale)
            ?.putFloat("lineSpacing", lineSpacing)
            ?.putBoolean("taskNavigation", taskNavigation)
            ?.putBoolean("keepTasksAwake", keepTasksAwake)
            ?.putBoolean("privilegedKeepAlive", privilegedKeepAlive)
            ?.putFloat("petMovement", petMotion.movement)
            ?.putFloat("petDamping", petMotion.damping)
            ?.putInt("petInterval", petMotion.interval)
            ?.putFloat("petRate", petMotion.rate)
            ?.putInt("petPressUnits", petMotion.pressUnits)
            ?.putString("phrases", JSONArray(phrases.map { it.json() }).toString())
            ?.apply()
    }

    private val term = Regex("\\{([a-zA-Z]+)\\}\\s*(>=|<=|==|!=|>|<)\\s*(-?\\d+(?:\\.\\d+)?)")

    fun validCondition(condition: String): Boolean =
        condition.isBlank() ||
            condition.split("&&").all { part ->
                term.matchEntire(part.trim())?.groupValues?.get(1) in
                    setOf(
                        "cost",
                        "limit",
                        "tokens",
                        "sessions",
                        "busy",
                        "waiting",
                        "heavy",
                        "draft",
                        "ultra",
                        "queued",
                    )
            }

    fun matches(condition: String, values: Map<String, String> = values()): Boolean {
        if (condition.isBlank()) return true
        if (!validCondition(condition)) return false
        return condition.split("&&").all {
            val m = term.matchEntire(it.trim()) ?: return@all false
            val a = values[m.groupValues[1]]?.toDoubleOrNull() ?: return@all false
            val b = m.groupValues[3].toDouble()
            when (m.groupValues[2]) {
                ">=" -> a >= b
                "<=" -> a <= b
                ">" -> a > b
                "<" -> a < b
                "!=" -> a != b
                else -> a == b
            }
        }
    }

    fun values(): Map<String, String> {
        val c = AssistantState.sessions.firstOrNull { it.id == AssistantState.selectedId }
        val usage = context?.getSharedPreferences("usage-v1", Context.MODE_PRIVATE)
        val today = java.time.LocalDate.now().toString()
        return mapOf(
            "limit" to (usage?.getString("limit", "") ?: ""),
            "cost" to
                if (usage?.getString("date", "") == today) usage.getString("cost", "0").orEmpty()
                else "0",
            "queued" to AssistantState.tasks.count { it.status == "queued" }.toString(),
            "tokens" to AssistantState.totalTokens.toString(),
            "sessions" to AssistantState.sessions.size.toString(),
            "busy" to if (AssistantState.busy) "1" else "0",
            "waiting" to if (AssistantState.running?.status == "waiting") "1" else "0",
            "heavy" to if (c?.model == "deepseek-v4-pro" && c.effort != "low") "1" else "0",
            "draft" to if (c?.draft?.isNotBlank() == true) "1" else "0",
            "ultra" to if (c?.effort == "ultra") "1" else "0",
        )
    }

    fun render(text: String): String {
        val values = values()
        return Regex("\\{([a-zA-Z]+)\\}").replace(text) { m ->
            when (m.groupValues[1]) {
                "limit" ->
                    AssistantState.balance.takeIf { it != "未查询" }.orEmpty().ifBlank { "暂未查询" }
                "cost" -> "约 ¥${values["cost"]}"
                else -> values[m.groupValues[1]] ?: m.value
            }
        }
    }

    /** Account balance decreases are estimates; never infer token prices or combine currencies. */
    fun recordBalance(account: String, currency: String, amount: String) {
        val number = amount.toBigDecimalOrNull() ?: return
        if (currency != "CNY") return
        val p = context?.getSharedPreferences("usage-v1", Context.MODE_PRIVATE) ?: return
        val today = java.time.LocalDate.now().toString()
        val previous =
            if (p.getString("account", "") == account && p.getString("date", "") == today)
                p.getString("limit", "")?.toBigDecimalOrNull()
            else null
        val old =
            if (p.getString("date", "") == today)
                p.getString("cost", "0")!!.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
            else java.math.BigDecimal.ZERO
        val cost =
            old +
                if (previous != null && previous > number) previous - number
                else java.math.BigDecimal.ZERO
        p.edit()
            .putString("account", account)
            .putString("date", today)
            .putString("limit", amount)
            .putString("cost", cost.toPlainString())
            .apply()
    }

    fun sticker(id: String): Int =
        when (id) {
            "cute" -> R.drawable.meme_cute
            "ohjing" -> R.drawable.meme_ohjing
            "rice" -> R.drawable.meme_rice
            "sleep" -> R.drawable.meme_sleep
            else -> 0
        }
}
