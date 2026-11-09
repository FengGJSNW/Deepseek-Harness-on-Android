package dev.deepseek.mobile.lab

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

/** Standalone, offline controls with observable outcomes for human and agent testing. */
class ControlLabActivity : Activity() {
    private var taps = 0
    private var holds = 0
    private var submits = 0
    private var scrollCount = 0
    private var gesture = "未滑动"
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var result: TextView
    private lateinit var tapButton: Button
    private lateinit var holdButton: Button
    private lateinit var scroller: ScrollView
    private val navy = Color.rgb(22, 56, 87)
    private val blue = Color.rgb(40, 122, 187)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun background(color: Int, radius: Int = 14) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun label(text: String, size: Float = 14f) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(navy)
            setPadding(0, dp(9), 0, dp(9))
        }

    private fun button(text: String, action: () -> Unit) =
        Button(this).apply {
            this.text = text
            textSize = 14f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = background(blue, 12)
            layoutParams =
                LinearLayout.LayoutParams(-1, dp(49)).apply {
                    topMargin = dp(7)
                    bottomMargin = dp(6)
                }
            setOnClickListener { action() }
        }

    private fun update() {
        status.text = "点击 $taps 次  ·  长按 $holds 次  ·  提交 $submits 次\n滚动 $scrollCount 次  ·  $gesture"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(36), dp(22), dp(48))
                setBackgroundColor(Color.rgb(243, 248, 253))
            }
        scroller =
            ScrollView(this).apply {
                isFillViewport = true
                addView(layout)
            }
        layout.addView(
            label("CONTROL LAB", 10f).apply {
                setTextColor(blue)
                letterSpacing = .18f
            },
        )
        layout.addView(label("手机操控实验室", 26f))
        layout.addView(label("不用联网，不用密钥。每个动作都有可见结果。", 12f))
        status =
            label("", 12f).apply {
                background = background(Color.rgb(223, 239, 250))
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }
        layout.addView(status)
        update()
        layout.addView(label("01  文字输入与提交", 15f))
        input =
            EditText(this).apply {
                hint = "测试输入"
                contentDescription = "测试输入"
                isSingleLine = true
                textSize = 16f
                setTextColor(navy)
                background = background(Color.WHITE, 10)
                setPadding(dp(13), dp(11), dp(13), dp(11))
                id = View.generateViewId()
            }
        layout.addView(input)
        layout.addView(
            button("执行测试") {
                submits++
                result.text = "结果：${input.text}"
                update()
            },
        )
        result = label("尚未点击", 18f).apply { contentDescription = "输入提交结果" }
        layout.addView(result)
        layout.addView(label("02  点击与长按", 15f))
        tapButton =
            button("点击测试 · 0 次") {
                taps++
                tapButton.text = "点击测试 · $taps 次"
                update()
            }
        layout.addView(tapButton)
        holdButton = button("长按这里") { holdButton.text = "请长按至少 0.6 秒" }
        holdButton.setOnLongClickListener {
            holds++
            holdButton.text = "长按成功：$holds 次"
            update()
            true
        }
        layout.addView(holdButton)
        layout.addView(label("03  密码控件限制", 15f))
        layout.addView(
            EditText(this).apply {
                hint = "密码测试（应隐藏且拒绝代理输入）"
                inputType = 129
                setText("private-password")
                textSize = 14f
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                contentDescription = "密码限制测试"
            },
        )
        layout.addView(label("04  竖向滚动", 15f))
        layout.addView(label("向上滑动，检查下面的编号是否改变。", 12f))
        for (i in 1..15) layout.addView(
            label("滚动项目 $i", 16f).apply {
                setPadding(dp(15), dp(19), dp(15), dp(19))
                background =
                    background(if (i % 2 == 0) Color.rgb(229, 240, 249) else Color.WHITE, 8)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) }
            },
        )
        layout.addView(label("05  任意方向滑动", 15f))
        layout.addView(label("在蓝色区域内滑动。面板会显示方向和距离。", 12f))
        layout.addView(
            GesturePad().apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(200))
                contentDescription = "滑动测试区域"
            },
        )
        for (i in 16..30) layout.addView(
            label("滚动项目 $i", 16f).apply { setPadding(dp(15), dp(19), dp(15), dp(19)) },
        )
        layout.addView(
            button("复制测试结果") {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(
                        ClipData.newPlainText("操控测试结果", "${status.text}\n${result.text}"),
                    )
                result.text = "测试结果已复制"
            },
        )
        layout.addView(
            button("重置全部测试") {
                taps = 0
                holds = 0
                submits = 0
                scrollCount = 0
                gesture = "未滑动"
                input.setText("")
                result.text = "尚未点击"
                tapButton.text = "点击测试 · 0 次"
                holdButton.text = "长按这里"
                update()
                scroller.smoothScrollTo(0, 0)
            },
        )
        scroller.setOnScrollChangeListener { _, _, currentY, _, previousY ->
            if (abs(currentY - previousY) > dp(5)) {
                scrollCount++
                update()
            }
        }
        setContentView(scroller)
    }

    private inner class GesturePad : View(this@ControlLabActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var startX = 0f
        private var startY = 0f
        private var endX = 0f
        private var endY = 0f

        init {
            background = background(Color.rgb(221, 240, 253), 16)
            isClickable = true
            isFocusable = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = Color.rgb(46, 133, 195)
            paint.strokeWidth = dp(3).toFloat()
            if (startX != 0f || startY != 0f) {
                canvas.drawLine(startX, startY, endX, endY, paint)
                canvas.drawCircle(endX, endY, dp(8).toFloat(), paint)
            }
            paint.color = navy
            paint.textSize = dp(14).toFloat()
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(gesture, width / 2f, height / 2f, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent.requestDisallowInterceptTouchEvent(true)
                    startX = event.x
                    startY = event.y
                    endX = startX
                    endY = startY
                }
                MotionEvent.ACTION_MOVE -> {
                    endX = event.x
                    endY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    endX = event.x
                    endY = event.y
                    val dx = endX - startX
                    val dy = endY - startY
                    val distance = hypot(dx, dy).toInt()
                    gesture =
                        if (distance < dp(12)) "点击 · $distance px"
                        else
                            "${if(abs(dx)>abs(dy)) {if(dx>0) "向右" else "向左"} else {if(dy>0) "向下" else "向上"}}滑动 · $distance px"
                    contentDescription = "滑动测试区域：$gesture"
                    update()
                    parent.requestDisallowInterceptTouchEvent(false)
                    performClick()
                }
                MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
            }
            invalidate()
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }
}
