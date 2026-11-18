package dev.deepseek.mobile

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView

/** Let the original control handle its own tap without an earlier outside-dismiss racing it. */
@SuppressLint("ClickableViewAccessibility") // Only consumes ACTION_OUTSIDE, never an actual click.
@Composable
internal fun KeepMenuAnchorTouchable(ownerRoot: View, anchor: Rect) {
    val popupView = LocalView.current
    val currentAnchor by rememberUpdatedState(anchor)
    DisposableEffect(popupView, ownerRoot) {
        // LocalView is the Compose child; ACTION_OUTSIDE is delivered to the popup window root.
        // Resolve after attachment, when rootView belongs to that window rather than the child.
        var installed: View? = null
        val screen = IntArray(2)
        val window = IntArray(2)
        val listener = View.OnTouchListener { _, event ->
            if (event.actionMasked != MotionEvent.ACTION_OUTSIDE) false
            else {
                ownerRoot.getLocationOnScreen(screen)
                ownerRoot.getLocationInWindow(window)
                val point =
                    Offset(
                        event.rawX - (screen[0] - window[0]),
                        event.rawY - (screen[1] - window[1]),
                    )
                currentAnchor.contains(point)
            }
        }
        fun install() {
            val root = popupView.rootView
            if (root !== ownerRoot && root !== installed) {
                installed?.setOnTouchListener(null)
                root.setOnTouchListener(listener)
                installed = root
            }
        }
        val attachment =
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = install()

                override fun onViewDetachedFromWindow(view: View) {
                    installed?.setOnTouchListener(null)
                    installed = null
                }
            }
        popupView.addOnAttachStateChangeListener(attachment)
        if (popupView.isAttachedToWindow) install()
        onDispose {
            popupView.removeOnAttachStateChangeListener(attachment)
            installed?.setOnTouchListener(null)
        }
    }
}
