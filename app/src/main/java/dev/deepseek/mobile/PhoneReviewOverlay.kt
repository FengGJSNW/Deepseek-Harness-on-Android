package dev.deepseek.mobile

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** A background confirmation remains visible to the user while the target app keeps focus. */
internal class PhoneReviewOverlay(
    private val service: AgentService,
    private val manager: WindowManager,
) {
    var view: View? = null
        private set

    private var shown: Triple<String, String, String>? = null

    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()

    fun update(task: PhoneTask?, hidden: Boolean) {
        if (task == null || task.question.isBlank() || MainActivity.inForeground) {
            dismiss()
            return
        }
        val memory = AssistantState.pendingMemory?.taskId == task.id
        val title = task.reviewTitle(memory)
        val key = Triple(task.id, title, task.question)
        if (key == shown) {
            view?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
            return
        }
        dismiss()
        fun TextView.base(size: Float, color: Int) {
            textSize = size
            setTextColor(color)
        }
        val paper =
            LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(12), dp(15), dp(8))
                background =
                    ContinuousDrawable(
                        0xFFFCFBF9.toInt(),
                        dp(20).toFloat(),
                        0xFFE6E5E2.toInt(),
                        dp(1).toFloat(),
                    )
                elevation = dp(7).toFloat()
                visibility = if (hidden) View.INVISIBLE else View.VISIBLE
            }
        paper.addView(
            TextView(service).apply {
                text = title
                base(15f, 0xFF242424.toInt())
                setTypeface(null, Typeface.BOLD)
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            },
        )
        paper.addView(
            TextView(service).apply {
                text = task.question
                base(13f, 0xFF494949.toInt())
                setPadding(0, dp(7), 0, dp(3))
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        val buttons =
            LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
        val allow =
            action("允许本次", primary = true) {
                if (AssistantState.running === task && task.question == key.third)
                    service.answer("", true)
            }
        buttons.addView(allow)
        allow.visibility = View.GONE
        val direct = task.approval && !task.terminalApproval && !memory
        val summary = paper.getChildAt(1) as TextView
        if (direct)
            summary.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val layout = summary.layout
                if (
                    layout != null &&
                        layout.lineCount > 0 &&
                        layout.getEllipsisCount(layout.lineCount - 1) == 0
                )
                    allow.visibility = View.VISIBLE
            }
        if (direct)
            buttons.addView(
                action("拒绝", primary = false) {
                    if (AssistantState.running === task && task.question == key.third)
                        service.denyCurrentApproval()
                },
                0,
            )
        buttons.addView(
            action(if (task.approval) "查看详情" else "打开并回复", primary = !direct) {
                service.openTask()
            },
            0,
        )
        paper.addView(buttons)
        val params =
            WindowManager.LayoutParams(
                    (manager.currentWindowMetrics.bounds.width() - dp(24)).coerceIn(
                        dp(220),
                        dp(480),
                    ),
                    -2,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_SECURE,
                    PixelFormat.TRANSLUCENT,
                )
                .apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = dp(12)
                }
        try {
            manager.addView(paper, params)
            view = paper
            shown = key
        } catch (_: Exception) {
            // The app's notification still opens the same confirmation.
        }
    }

    private fun action(label: String, primary: Boolean, click: () -> Unit) =
        TextView(service).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(11), 0, dp(11), 0)
            setTextColor(if (primary) Color.WHITE else 0xFF3B58A4.toInt())
            if (primary)
                background =
                    ContinuousDrawable(0xFF426EFE.toInt(), dp(12).toFloat(), 0xFF426EFE.toInt(), 0f)
            else {
                val typed = android.util.TypedValue()
                if (
                    context.theme.resolveAttribute(
                        android.R.attr.selectableItemBackground,
                        typed,
                        true,
                    )
                )
                    setBackgroundResource(typed.resourceId)
            }
            setOnClickListener { click() }
            accessibilityDelegate =
                object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: View,
                        info: android.view.accessibility.AccessibilityNodeInfo,
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = android.widget.Button::class.java.name
                    }
                }
        }

    fun dismiss() {
        view?.let { runCatching { manager.removeViewImmediate(it) } }
        view = null
        shown = null
    }
}
