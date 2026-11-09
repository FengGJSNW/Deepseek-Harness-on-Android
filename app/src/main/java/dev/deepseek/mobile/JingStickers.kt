package dev.deepseek.mobile

/** One character family only: chibi blue-haired whale maid. */
internal data class JingSticker(
    val art: Int,
    val title: String,
    val words: String,
    val mood: String = "happy",
)

internal val jingStickers =
    listOf(
        JingSticker(R.drawable.meme_ohjing, "哦鲸鲸", "哦鲸鲸… 我在呢。", "idle"),
        JingSticker(R.drawable.meme_ciallo, "打个招呼", "Ciallo～ 今天也一起加油。", "wave"),
        JingSticker(R.drawable.meme_cute, "摸摸收到", "摸摸收到！呆毛也翘起来啦。"),
        JingSticker(R.drawable.meme_rice, "就一碗", "就一碗！吃饱了陪你干活。"),
        JingSticker(R.drawable.meme_full, "吃饱饱", "吃饱饱，摸摸肚子！"),
        JingSticker(R.drawable.meme_opensource, "开源小鲸", "认真干活，快乐开源！", "done"),
        JingSticker(R.drawable.meme_pressure, "让我缓缓", "压力一只大肥鱼… 让我理一理。", "think"),
        JingSticker(R.drawable.meme_vision, "认真思考", "这次，我会认真看看再想。", "think"),
        JingSticker(R.drawable.meme_sleep, "深度睡眠", "我眯一会儿，有事叫我。", "sleep"),
    )
