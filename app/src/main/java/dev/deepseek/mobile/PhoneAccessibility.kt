package dev.deepseek.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject

/** All control operations run on the main thread and validate the current observation. */
class PhoneAccessibility : AccessibilityService() {
    companion object {
        @Volatile var current: PhoneAccessibility? = null
    }

    private val nodes = mutableMapOf<Int, AccessibilityNodeInfo>()
    private var snapshot = ""
    private var observedWindow = -1
    private var observedAt = 0L
    private var lastScreenshotAt = 0L
    private val privateRegions = mutableListOf<Rect>()

    private fun owned(node: AccessibilityNodeInfo): Boolean =
        node.packageName?.toString() == packageName ||
            node.viewIdResourceName?.startsWith("$packageName:id/") == true

    override fun onServiceConnected() {
        current = this
    }

    override fun onDestroy() {
        if (current === this) current = null
        clearNodes()
        super.onDestroy()
    }

    override fun onInterrupt() {
        AgentService.current?.stopTask()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (snapshot.isEmpty()) return
        if (
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                event.windowId == observedWindow
        )
            snapshot = ""
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val root = rootInActiveWindow
            try {
                if (root?.windowId != observedWindow) snapshot = ""
            } finally {
                root?.recycle()
            }
        }
    }

    private fun clearNodes() {
        nodes.values.forEach { it.recycle() }
        nodes.clear()
        privateRegions.clear()
    }

    private fun unlocked() {
        check(!getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "手机已锁定，请手动解锁后继续" }
    }

    private fun validate(args: JSONObject) {
        unlocked()
        check(
            snapshot.isNotEmpty() &&
                args.optString("snapshot") == snapshot &&
                SystemClock.uptimeMillis() - observedAt < 30_000,
        ) {
            "界面快照已过期，请先 observe"
        }
        val root = rootInActiveWindow ?: error("无法读取当前界面")
        try {
            check(root.windowId == observedWindow) { "窗口已改变，请先 observe" }
            check(!owned(root)) { "请由用户手动操作助手自身的设置" }
        } finally {
            root.recycle()
        }
    }

    private fun point(args: JSONObject, x: String, y: String): Pair<Float, Float> {
        val px = args.getDouble(x).toFloat()
        val py = args.getDouble(y).toFloat()
        val bounds =
            getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
        require(
            px.isFinite() &&
                py.isFinite() &&
                px >= 0 &&
                py >= 0 &&
                px < bounds.width() &&
                py < bounds.height(),
        ) {
            "坐标超出屏幕"
        }
        check(privateRegions.none { it.contains(px.toInt(), py.toInt()) }) {
            "此位置由助手自己的界面占用，请由用户手动操作"
        }
        return Pair(px, py)
    }

    suspend fun observe(): JSONObject {
        unlocked()
        delay(200)
        snapshot = ""
        clearNodes()
        val root = rootInActiveWindow ?: error("当前应用未提供界面，请稍后重试")
        val list = JSONArray()
        var id = 0
        if (owned(root)) {
            root.recycle()
            return JSONObject().put("nodes", list).put("imageError", "助手自身界面只供用户查看")
        }
        var notificationShade = false
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 28 || id >= 250 || !node.isVisibleToUser) return
            val rect = Rect().also { node.getBoundsInScreen(it) }
            if (owned(node)) {
                privateRegions.add(rect)
                return
            }
            if (
                node.viewIdResourceName in
                    setOf(
                        "com.android.systemui:id/notification_stack_scroller",
                        "com.android.systemui:id/notification_panel",
                        "com.android.systemui:id/notification_container_parent",
                    )
            )
                notificationShade = true
            val index = ++id
            nodes[index] = AccessibilityNodeInfo.obtain(node)
            val secret = node.isPassword
            list.put(
                JSONObject()
                    .put("id", index)
                    .put("text", if (secret) "[已隐藏]" else node.text?.toString()?.take(400) ?: "")
                    .put(
                        "description",
                        if (secret) "" else node.contentDescription?.toString()?.take(200) ?: "",
                    )
                    .put("class", node.className?.toString())
                    .put("viewId", node.viewIdResourceName)
                    .put("clickable", node.isClickable)
                    .put("editable", node.isEditable)
                    .put("scrollable", node.isScrollable)
                    .put("password", node.isPassword)
                    .put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))),
            )
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    visit(child, depth + 1)
                } finally {
                    child.recycle()
                }
            }
        }
        val activePackage = root.packageName?.toString() ?: ""
        observedWindow = root.windowId
        try {
            visit(root, 0)
        } finally {
            root.recycle()
        }
        val screen =
            getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
        if (notificationShade) {
            clearNodes()
            return JSONObject()
                .put("nodes", JSONArray())
                .put("imageError", "通知面板含有助手自己的私密任务提示，请由用户查看")
        }
        val result =
            JSONObject()
                .put("package", activePackage)
                .put("screenWidth", screen.width())
                .put("screenHeight", screen.height())
                .put("nodes", list)
        if (privateRegions.isNotEmpty()) result.put("imageError", "当前窗口含有助手自己的组件")
        else {
            // The platform limits the frequency of accessibility screenshots.
            val remaining = 450 - (SystemClock.uptimeMillis() - lastScreenshotAt)
            if (remaining > 0) delay(remaining)
            lastScreenshotAt = SystemClock.uptimeMillis()
            val service = AgentService.current
            val capture =
                if (service == null) capture() else service.withoutPhonePanels { capture() }
            if (capture.has("error")) result.put("imageError", capture.getString("error"))
            else result.put("image", capture)
        }
        val latestRoot = rootInActiveWindow
        try {
            check(
                latestRoot != null && latestRoot.windowId == observedWindow && !owned(latestRoot),
            ) {
                "观察期间窗口改变，请重新 observe"
            }
        } finally {
            latestRoot?.recycle()
        }
        snapshot = UUID.randomUUID().toString()
        observedAt = SystemClock.uptimeMillis()
        result.put("snapshot", snapshot)
        return result
    }

    private suspend fun capture(): JSONObject = suspendCancellableCoroutine { continuation ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hardware =
                        Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    try {
                        if (!continuation.isActive) return
                        val original =
                            hardware?.copy(Bitmap.Config.ARGB_8888, false) ?: error("截图解码失败")
                        val factor = minOf(1f, 1280f / maxOf(original.width, original.height))
                        val scaled =
                            Bitmap.createScaledBitmap(
                                original,
                                (original.width * factor).toInt(),
                                (original.height * factor).toInt(),
                                true,
                            )
                        val bytes =
                            ByteArrayOutputStream()
                                .apply { scaled.compress(Bitmap.CompressFormat.JPEG, 82, this) }
                                .toByteArray()
                        val imageId = UUID.randomUUID().toString()
                        val folder = File(filesDir, "screens").apply { mkdirs() }
                        File(folder, "$imageId.jpg").writeBytes(bytes)
                        continuation.resume(
                            JSONObject()
                                .put("attachmentId", imageId)
                                .put("mediaType", "image/jpeg")
                                .put("bytes", bytes.size)
                                .put("width", scaled.width)
                                .put("height", scaled.height)
                                .put("name", "phone-screen.jpg"),
                        )
                        if (scaled !== original) scaled.recycle()
                        original.recycle()
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } finally {
                        hardware?.recycle()
                        result.hardwareBuffer.close()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive)
                        continuation.resume(
                            JSONObject().put("error", "系统不允许截图（代码 $errorCode）；受保护界面不会被绕过"),
                        )
                }
            },
        )
    }

    private suspend fun gesture(path: Path, duration: Long) =
        suspendCancellableCoroutine<JSONObject> { continuation ->
            val accepted =
                dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                        .build(),
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (continuation.isActive)
                                continuation.resume(JSONObject().put("message", "手势完成，请重新观察验证结果"))
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (continuation.isActive)
                                continuation.resume(
                                    JSONObject().put("error", "手势被系统取消；结果未知，请观察，不要直接重试"),
                                )
                        }
                    },
                    null,
                )
            if (!accepted && continuation.isActive)
                continuation.resume(JSONObject().put("error", "系统拒绝执行手势"))
            // Cancellation replaces pending strokes with a stationary stroke.
            continuation.invokeOnCancellation {
                mainExecutor.execute {
                    dispatchGesture(
                        GestureDescription.Builder()
                            .addStroke(
                                GestureDescription.StrokeDescription(
                                    Path().apply { moveTo(0f, 0f) },
                                    0,
                                    1,
                                ),
                            )
                            .build(),
                        null,
                        null,
                    )
                }
            }
        }

    suspend fun execute(method: String, args: JSONObject): JSONObject {
        if (method == "observe") return observe()
        unlocked()
        if (method == "global_action") {
            val action =
                when (args.getString("action")) {
                    "back" -> GLOBAL_ACTION_BACK
                    "home" -> GLOBAL_ACTION_HOME
                    "recents" -> GLOBAL_ACTION_RECENTS
                    "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
                    else -> error("不支持的系统操作")
                }
            snapshot = ""
            return JSONObject()
                .put("message", if (performGlobalAction(action)) "已执行系统导航，请重新观察" else "系统导航失败")
        }
        validate(args)
        when (method) {
            "tap" -> {
                if (args.has("node")) {
                    var node = nodes[args.getInt("node")] ?: error("控件不存在")
                    check(node.refresh() && !node.isPassword && node.isEnabled) { "控件不可操作或已失效" }
                    var parent: AccessibilityNodeInfo? = null
                    var count = 0
                    while (!node.isClickable && count++ < 5) {
                        val next = node.parent ?: break
                        parent?.recycle()
                        parent = next
                        node = next
                    }
                    val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent?.recycle()
                    snapshot = ""
                    return JSONObject()
                        .put(
                            if (ok) "message" else "error",
                            if (ok) "点击已提交，请重新观察验证" else "控件不支持点击，可观察后使用坐标",
                        )
                }
                val (x, y) = point(args, "x", "y")
                snapshot = ""
                return gesture(Path().apply { moveTo(x, y) }, 70)
            }
            "long_press" -> {
                val (x, y) = point(args, "x", "y")
                snapshot = ""
                return gesture(Path().apply { moveTo(x, y) }, 650)
            }
            "swipe" -> {
                val (x1, y1) = point(args, "x1", "y1")
                val (x2, y2) = point(args, "x2", "y2")
                val duration = args.optLong("duration", 400)
                require(duration in 100..2000) { "滑动时间应为 100–2000ms" }
                snapshot = ""
                return gesture(
                    Path().apply {
                        moveTo(x1, y1)
                        lineTo(x2, y2)
                    },
                    duration,
                )
            }
            "input_text" -> {
                val node = nodes[args.getInt("node")] ?: error("控件不存在")
                check(node.refresh() && node.isEditable && node.isEnabled && !node.isPassword) {
                    "控件不可输入或属于密码框"
                }
                val text = args.getString("text")
                require(text.length <= 5000) { "单次输入最多 5000 字" }
                val ok =
                    node.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                text,
                            )
                        },
                    )
                snapshot = ""
                return JSONObject()
                    .put(if (ok) "message" else "error", if (ok) "文字已填入，请重新观察验证" else "输入失败")
            }
            else -> error("不支持的无障碍操作")
        }
    }
}
