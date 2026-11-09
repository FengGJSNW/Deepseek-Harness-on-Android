package dev.deepseek.mobile

import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.*
import android.widget.*
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlinx.coroutines.suspendCancellableCoroutine

/** Small non-focusable overlay; it never steals keyboard focus from the target app. */
class FloatingControl(private val service: AgentService) {
    private val manager = service.getSystemService(android.view.WindowManager::class.java)
    private var root: LinearLayout? = null
    private val review = PhoneReviewOverlay(service, manager)
    private var suppressed = 0
    private var avatar: ImageView? = null
    private var avatarResource = 0
    private val pulse = android.os.Handler(android.os.Looper.getMainLooper())
    private val refresh =
        object : Runnable {
            override fun run() {
                if (root != null) {
                    update()
                    pulse.postDelayed(this, 400)
                }
            }
        }
    private var bubbleRevision = -1
    private var bubbleUntil = 0L
    private var label: TextView? = null
    private var expanded = false

    private fun dp(v: Int) = (v * service.resources.displayMetrics.density).toInt()

    private val params =
        WindowManager.LayoutParams(
                -2,
                -2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT,
            )
            .apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = dp(180)
            }

    fun show() {
        if (root != null || !Settings.canDrawOverlays(service)) return
        val layout =
            LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (suppressed == 0) View.VISIBLE else View.INVISIBLE
                setPadding(dp(5), dp(5), dp(5), dp(5))
                background =
                    ContinuousDrawable(
                        Color.WHITE,
                        dp(20).toFloat(),
                        0xFFE4E9F4.toInt(),
                        dp(1).toFloat(),
                    )
                elevation = dp(6).toFloat()
            }
        val icon =
            ImageView(service).apply {
                setImageResource(dev.deepseek.mobile.R.drawable.whale_chibi_expanded)
                contentDescription = "DSHA 鲸鲸悬浮球，点击控制，长按紧急停止"
                layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
                isClickable = true
            }
        avatar = icon
        layout.addView(icon)
        val panel =
            LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(dp(164), -2)
            }
        label =
            TextView(service).apply {
                textSize = 12f
                setTextColor(0xFF334155.toInt())
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
        panel.addView(label)
        fun button(text: String, action: () -> Unit) {
            panel.addView(
                Button(service).apply {
                    this.text = text
                    textSize = 12f
                    isAllCaps = false
                    setTextColor(0xFF426EFE.toInt())
                    background =
                        ContinuousDrawable(
                            0xFFEEF3FF.toInt(),
                            dp(12).toFloat(),
                            0xFFDDE6FC.toInt(),
                            dp(1).toFloat(),
                        )
                    stateListAnimator = null
                    elevation = 0f
                    minimumHeight = 0
                    setPadding(dp(8), 0, dp(8), 0)
                    layoutParams = LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(5) }
                    setOnClickListener { action() }
                },
            )
        }
        button("返回当前任务") {
            service.openTask()
            expanded = false
            panel.visibility = View.GONE
        }
        button("中断当前操作") { service.stopTask() }
        button("隐藏悬浮球") { service.setFloating(false) }
        layout.addView(panel)
        var taps = 0
        icon.setOnClickListener {
            icon.animate().cancel()
            icon.scaleX = 1.15f
            icon.scaleY = .82f
            icon
                .animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(550)
                .setInterpolator(android.view.animation.OvershootInterpolator(4f))
                .start()
            WhaleCompanion.touch()
            expanded = true
            panel.visibility = View.VISIBLE
            update()
            layout.post {
                params.x =
                    params.x.coerceIn(
                        0,
                        (manager.currentWindowMetrics.bounds.width() - layout.width).coerceAtLeast(
                            0,
                        ),
                    )
                try {
                    manager.updateViewLayout(layout, params)
                } catch (_: Exception) {}
            }
        }
        icon.setOnLongClickListener {
            service.stopTask()
            true
        }
        var sx = 0f
        var sy = 0f
        var px = 0
        var py = 0
        var moved = false
        val detector =
            GestureDetector(
                service,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent) = true

                    override fun onSingleTapUp(e: MotionEvent): Boolean {
                        icon.performClick()
                        return true
                    }

                    override fun onLongPress(e: MotionEvent) {
                        if (!moved) icon.performLongClick()
                    }
                },
            )
        icon.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    WhaleSounds.press()
                    sx = e.rawX
                    sy = e.rawY
                    px = params.x
                    py = params.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - sx) + abs(e.rawY - sy) > dp(8)) {
                        moved = true
                        val bounds = manager.currentWindowMetrics.bounds
                        params.x =
                            (px + (e.rawX - sx).toInt()).coerceIn(
                                0,
                                (bounds.width() - layout.width).coerceAtLeast(0),
                            )
                        params.y =
                            (py + (e.rawY - sy).toInt()).coerceIn(
                                0,
                                (bounds.height() - layout.height).coerceAtLeast(0),
                            )
                        manager.updateViewLayout(layout, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    WhaleSounds.releaseTap()
                    if (moved) {
                        params.x =
                            if (params.x > manager.currentWindowMetrics.bounds.width() / 2)
                                (manager.currentWindowMetrics.bounds.width() - layout.width)
                                    .coerceAtLeast(0)
                            else 0
                        manager.updateViewLayout(layout, params)
                    }
                }
            }
            if (!moved) detector.onTouchEvent(e)
            true
        }
        try {
            manager.addView(layout, params)
            root = layout
            update()
            pulse.post(refresh)
        } catch (_: Exception) {
            AssistantState.error = "无法显示悬浮球，请检查悬浮窗权限"
        }
    }

    fun update() {
        val art = R.drawable.whale_chibi_expanded
        if (art != avatarResource) {
            (avatar?.drawable as? android.graphics.drawable.AnimatedImageDrawable)?.stop()
            val drawable =
                android.graphics.ImageDecoder.decodeDrawable(
                    android.graphics.ImageDecoder.createSource(service.resources, art),
                )
            avatar?.setImageDrawable(drawable)
            (drawable as? android.graphics.drawable.AnimatedImageDrawable)?.start()
            avatarResource = art
        }
        if (bubbleRevision != WhaleCompanion.revision) {
            bubbleRevision = WhaleCompanion.revision
            bubbleUntil = android.os.SystemClock.uptimeMillis() + 6500
        }
        val words =
            if (android.os.SystemClock.uptimeMillis() < bubbleUntil)
                WhaleCompanion.text +
                    (if (WhaleCompanion.showBalance) "\n剩余额度：" + WhaleCompanion.balanceText else "")
            else ""
        val task = AssistantState.running
        label?.text =
            if (task == null) {
                (if (words.isNotBlank()) "$words\n\n" else "") +
                    "鲸鲸待命中\n${AssistantState.tasks.count { it.status == "queued" }} 个排队任务"
            } else {
                val title =
                    AssistantState.sessions.firstOrNull { it.id == task.sessionId }?.title.orEmpty()
                val summary = task.text.lineSequence().firstOrNull().orEmpty().take(72)
                listOf(title, summary, task.progress, "${task.steps.size} 步 · 长按可中断")
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString("\n")
            }
        if (root != null) review.update(task, suppressed != 0)
    }

    internal suspend fun <T> withoutPanels(block: suspend () -> T): T {
        val wait = suppressed == 0 && (root?.isShown == true || review.view?.isShown == true)
        suppressed++
        root?.visibility = View.INVISIBLE
        review.view?.visibility = View.INVISIBLE
        try {
            if (wait) {
                // Let WindowManager commit the hidden surfaces before capturing or tapping.
                repeat(2) {
                    suspendCancellableCoroutine<Unit> { pending ->
                        val choreographer = Choreographer.getInstance()
                        val frame = Choreographer.FrameCallback { pending.resume(Unit) }
                        choreographer.postFrameCallback(frame)
                        pending.invokeOnCancellation { choreographer.removeFrameCallback(frame) }
                    }
                }
            }
            return block()
        } finally {
            suppressed--
            if (suppressed == 0) {
                root?.visibility = View.VISIBLE
                update()
            }
        }
    }

    fun hide() {
        review.dismiss()
        pulse.removeCallbacks(refresh)
        (avatar?.drawable as? android.graphics.drawable.AnimatedImageDrawable)?.stop()
        avatar = null
        avatarResource = 0
        root?.let {
            try {
                manager.removeView(it)
            } catch (_: Exception) {}
        }
        root = null
        label = null
    }
}
