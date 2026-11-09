package dev.deepseek.mobile

import androidx.compose.ui.test.onNodeWithTag

/**
 * These modal selections intentionally survive Activity recreation; independent tests reset them.
 */
internal fun resetTransientUiForTest() {
    OfficialChatNavigation.cancelLogin()
    ReasoningState.selected = null
    WorkspaceState.reset()
    FilePreviewState.clear()
    ChangeState.show = false
    ChangeState.selected = null
    AssistantState.pendingMemory = null
    AssistantState.showAccessibilityInfo = false
}

/** Real Android IME animation is independent of Compose's virtual test animation clock. */
internal fun androidx.compose.ui.test.junit4.ComposeContentTestRule.awaitStableComposerTarget(
    tag: String,
) {
    var before: androidx.compose.ui.geometry.Rect? = null
    var stableSince = android.os.SystemClock.uptimeMillis()
    waitUntil(5000) {
        val activity = MainActivity.current
        val ime =
            activity
                ?.window
                ?.decorView
                ?.rootWindowInsets
                ?.isVisible(android.view.WindowInsets.Type.ime()) == true
        val rect = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val now = android.os.SystemClock.uptimeMillis()
        if (rect != before || !ime) {
            before = rect
            stableSince = now
            false
        } else now - stableSince >= 140
    }
}
