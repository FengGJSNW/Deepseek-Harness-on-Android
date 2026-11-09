package dev.deepseek.mobile

import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.provider.MediaStore
import android.util.AtomicFile
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class AvatarMode(val title: String, val detail: String) {
    DEFAULT("默认头像", "使用原有的鲸鲸头像"),
    UPLOAD("自定义头像", "从相册选取一张图片"),
    WHALE("DeepSeek Harness 鲸尾", "Agent 工作时播放高清鲸尾动图"),
    DOTS("Claude 风格圆点", "思考、执行、输出和等待时显示不同状态"),
}

internal enum class AvatarPhase(val label: String) {
    IDLE("空闲"),
    THINKING("思考中"),
    WORKING("执行中"),
    WRITING("回复中"),
    WAITING("等待确认"),
}

internal object AgentIdentity {
    const val DEFAULT_NAME = "小鲸鱼娘"
    private const val MAX_AVATAR_BYTES = 8 * 1024 * 1024
    var name by mutableStateOf(DEFAULT_NAME)
        private set

    var mode by mutableStateOf(AvatarMode.DEFAULT)
        private set

    var image by mutableStateOf<Bitmap?>(null)
        private set

    private fun prefs(context: Context) =
        context.getSharedPreferences("agent_identity", Context.MODE_PRIVATE)

    private fun avatar(context: Context) = AtomicFile(File(context.filesDir, "agent-avatar.png"))

    suspend fun load(context: Context) {
        val (storedName, storedMode, bitmap) =
            withContext(Dispatchers.IO) {
                val prefs = prefs(context)
                val storedMode =
                    AvatarMode.entries.firstOrNull {
                        it.name == prefs.getString("avatar", "")
                    } ?: AvatarMode.DEFAULT
                val bitmap = runCatching {
                    avatar(context).openRead().use { BitmapFactory.decodeStream(it) }
                }
                    .getOrNull()
                Triple(prefs.getString("name", DEFAULT_NAME) ?: DEFAULT_NAME, storedMode, bitmap)
            }
        name = storedName
        image = bitmap
        mode =
            if (storedMode == AvatarMode.UPLOAD && bitmap == null) AvatarMode.DEFAULT
            else storedMode
    }

    fun rename(context: Context, value: String) {
        name = value.lineSequence().joinToString(" ").trim().ifBlank { DEFAULT_NAME }
        prefs(context).edit().putString("name", name).apply()
    }

    fun select(context: Context, value: AvatarMode) {
        if (value == AvatarMode.UPLOAD && image == null) return
        mode = value
        prefs(context).edit().putString("avatar", value.name).apply()
    }

    suspend fun import(context: Context, uri: Uri) {
        val bitmap =
            withContext(Dispatchers.IO) {
                val bytes =
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(16384)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= MAX_AVATAR_BYTES) { "头像最大支持 8 MB" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: error("无法读取头像")
                val decoded =
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) {
                        decoder,
                        info,
                        _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val scale = 512f / maxOf(info.size.width, info.size.height, 512)
                        decoder.setTargetSize(
                            (info.size.width * scale).toInt().coerceAtLeast(1),
                            (info.size.height * scale).toInt().coerceAtLeast(1),
                        )
                    }
                val file = avatar(context)
                val output = file.startWrite()
                try {
                    check(decoded.compress(Bitmap.CompressFormat.PNG, 100, output))
                    file.finishWrite(output)
                } catch (e: Exception) {
                    file.failWrite(output)
                    throw e
                }
                decoded
            }
        image = bitmap
        select(context, AvatarMode.UPLOAD)
    }
}

@Composable
internal fun AgentAvatar(size: Dp, phase: AvatarPhase = AvatarPhase.IDLE) {
    val animated =
        phase != AvatarPhase.IDLE &&
            phase != AvatarPhase.WAITING &&
            ValueAnimator.areAnimatorsEnabled()
    val name = AgentIdentity.name
    val label = if (phase == AvatarPhase.IDLE) "${name}头像" else "${name} · ${phase.label}"
    when (AgentIdentity.mode) {
        AvatarMode.DEFAULT -> JingAvatar(size, label)
        AvatarMode.UPLOAD -> {
            val bitmap = AgentIdentity.image
            if (bitmap == null) JingAvatar(size, label)
            else
                Image(
                    remember(bitmap) { bitmap.asImageBitmap() },
                    label,
                    Modifier.size(size).clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
        }
        AvatarMode.WHALE ->
            AvatarSlot(size, label) {
                AndroidView(
                    factory = { context ->
                        ImageView(context).apply {
                            setImageDrawable(
                                ImageDecoder.decodeDrawable(
                                    ImageDecoder.createSource(
                                        resources,
                                        R.drawable.agent_running_whale,
                                    ),
                                ),
                            )
                            setColorFilter(android.graphics.Color.rgb(59, 95, 207))
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            importantForAccessibility =
                                android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }
                    },
                    update = { view ->
                        (view.drawable as? AnimatedImageDrawable)?.let {
                            if (animated && !it.isRunning) it.start()
                            if (!animated && it.isRunning) it.stop()
                        }
                    },
                    onRelease = { (it.drawable as? AnimatedImageDrawable)?.stop() },
                    modifier = Modifier.fillMaxSize(.88f),
                )
            }
        AvatarMode.DOTS -> AvatarSlot(size, label) { ThinkingDots(phase, animated) }
    }
}

@Composable
private fun AvatarSlot(size: Dp, label: String, content: @Composable () -> Unit) {
    Box(
        Modifier.size(size).semantics {
            contentDescription = label
        },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun ThinkingDots(phase: AvatarPhase, animated: Boolean) {
    val clock =
        if (animated) {
            val loop = rememberInfiniteTransition(label = "agent-dots")
            loop.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        tween(
                            when (phase) {
                                AvatarPhase.THINKING -> 1480
                                AvatarPhase.WORKING -> 1160
                                else -> 1900
                            },
                            easing = LinearEasing,
                        ),
                    ),
                label = "agent-dot-phase",
            )
        } else null
    val clay = Color(0xFFC56F52)
    Canvas(Modifier.fillMaxSize()) {
        val unit = size.minDimension
        val t = clock?.value ?: 0f
        fun dot(x: Float, y: Float, radius: Float, alpha: Float = 1f) {
            drawCircle(clay, unit * radius, center + Offset(x * unit, y * unit), alpha)
        }
        when (phase) {
            AvatarPhase.THINKING ->
                repeat(3) { index ->
                    val angle = 2 * PI * (t + index / 3f - .25f)
                    val depth = ((1 + sin(angle)) * .5).toFloat()
                    dot(
                        .235f * cos(angle).toFloat(),
                        .195f * sin(angle).toFloat(),
                        .05f + .025f * depth,
                        .64f + .36f * depth,
                    )
                }
            AvatarPhase.WORKING -> {
                dot(0f, 0f, .062f)
                repeat(6) { index ->
                    val angle = 2 * PI * (index / 6f - .25f) + .23 * sin(2 * PI * t)
                    val pulse = ((1 - cos(2 * PI * (t - index / 18f))) * .5).toFloat()
                    val distance = .142f + .144f * pulse
                    dot(
                        distance * cos(angle).toFloat(),
                        distance * sin(angle).toFloat(),
                        .043f + .012f * pulse,
                        .57f + .43f * pulse,
                    )
                }
            }
            AvatarPhase.WRITING ->
                repeat(3) { index ->
                    val local = (t + 1f - index * .08f) % 1f
                    val rise =
                        FastOutSlowInEasing.transform(((local - .08f) / .14f).coerceIn(0f, 1f))
                    val settle =
                        FastOutSlowInEasing.transform(((local - .22f) / .14f).coerceIn(0f, 1f))
                    val fade = ((local - .70f) / .20f).coerceIn(0f, 1f)
                    dot(
                        (index - 1) * .21f,
                        if (animated) .074f * (1f - rise) - .105f * (rise - settle) else 0f,
                        .062f,
                        if (animated) .24f + .76f * rise * (1f - fade) else .84f,
                    )
                }
            AvatarPhase.WAITING -> {
                dot(-.09f, 0f, .06f, .74f)
                dot(.09f, 0f, .06f, .74f)
            }
            AvatarPhase.IDLE -> dot(0f, 0f, .065f, .72f)
        }
    }
}

private object AvatarPhotoPicker : ActivityResultContracts.PickVisualMedia() {
    override fun createIntent(context: Context, input: PickVisualMediaRequest): Intent {
        val intent = super.createIntent(context, input)
        return if (intent.action == Intent.ACTION_OPEN_DOCUMENT) {
            Intent(Intent.ACTION_PICK)
                .setDataAndType(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    "image/*",
                )
        } else intent
    }
}

@Composable
internal fun AgentIdentitySettings() {
    val context = LocalContext.current
    val jobs = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var draft by rememberSaveable(AgentIdentity.name) { mutableStateOf(AgentIdentity.name) }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf("") }
    var phase by remember { mutableStateOf(AvatarPhase.THINKING) }
    val picker =
        rememberLauncherForActivityResult(AvatarPhotoPicker) { uri ->
            if (uri != null)
                jobs.launch {
                    busy = true
                    error = ""
                    try {
                        AgentIdentity.import(context, uri)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message?.takeIf { it == "头像最大支持 8 MB" } ?: "无法读取这张图片，请选择其他图片。"
                    } finally {
                        busy = false
                    }
                }
        }
    fun pickAvatar() {
        error = ""
        try {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (_: ActivityNotFoundException) {
            error = "当前设备的相册不可用。"
        }
    }
    Row(
        Modifier.fillMaxWidth().background(WhaleMist, ContinuousShape(16.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentAvatar(
            48.dp,
            if (AgentIdentity.mode in setOf(AvatarMode.WHALE, AvatarMode.DOTS)) phase
            else AvatarPhase.IDLE,
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(AgentIdentity.name, fontSize = 16.sp)
            Text(AgentIdentity.mode.title, fontSize = 12.sp, color = WhaleMuted)
        }
        if (AgentIdentity.mode == AvatarMode.DOTS)
            TextButton(
                onClick = {
                    val stages = AvatarPhase.entries.drop(1)
                    phase = stages[(stages.indexOf(phase) + 1) % stages.size]
                },
            ) {
                Text(phase.label)
            }
    }
    OutlinedTextField(
        draft,
        { if (it.codePointCount(0, it.length) <= 32) draft = it },
        Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Agent 显示名称") },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(
            onClick = {
                AgentIdentity.rename(context, draft)
                focus.clearFocus()
            },
            enabled = draft.isNotBlank() && draft.trim() != AgentIdentity.name,
        ) {
            Text("保存名称")
        }
        TextButton(
            onClick = {
                AgentIdentity.rename(context, AgentIdentity.DEFAULT_NAME)
                draft = AgentIdentity.DEFAULT_NAME
            },
        ) {
            Text("恢复默认名称")
        }
    }
    Text("头像", Modifier.padding(top = 10.dp), fontSize = 13.sp, color = WhaleMuted)
    AvatarMode.entries.forEach { mode ->
        OptionRow(
            when (mode) {
                AvatarMode.DEFAULT -> AppIcons.Agents
                AvatarMode.UPLOAD -> AppIcons.Image
                AvatarMode.WHALE -> AppIcons.Spark
                AvatarMode.DOTS -> AppIcons.MoreHoriz
            },
            mode.title,
            mode.detail,
            AgentIdentity.mode == mode,
        ) {
            if (!busy) {
                if (mode == AvatarMode.UPLOAD && AgentIdentity.image == null) pickAvatar()
                else AgentIdentity.select(context, mode)
            }
        }
    }
    if (AgentIdentity.image != null)
        TextButton(onClick = { pickAvatar() }, enabled = !busy) {
            Text("更换自定义图片")
        }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
}
