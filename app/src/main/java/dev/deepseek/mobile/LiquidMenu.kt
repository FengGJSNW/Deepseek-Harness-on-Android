package dev.deepseek.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import kotlin.math.*

private data class MenuWindow(
    val root: android.view.View,
    val safeTop: Int,
    val keyboard: Int,
    val navigationBar: Int,
)

private val LocalMenuWindow = compositionLocalOf<MenuWindow?> { null }

private class GlassMenuScope {
    var children by mutableIntStateOf(0)
    var childClock by mutableStateOf<PrismPopupClock?>(null)
    var childAnchorTop by mutableFloatStateOf(0f)
    var afterReturn: (() -> Unit)? = null
    var cardBounds by mutableStateOf(Rect.Zero)
    val position = PopupPlacementState()
}

private val LocalGlassMenuScope = staticCompositionLocalOf<GlassMenuScope?> { null }

/** Defer navigation until the source button or row has landed. */
internal val LocalAfterMenuReturn = staticCompositionLocalOf<((() -> Unit) -> Unit)> { { it() } }

internal val LocalDismissMenuStack = staticCompositionLocalOf<() -> Unit> { {} }
internal val LocalMenuInIsland = staticCompositionLocalOf { false }
internal val LocalMenuOwnerScroll = compositionLocalOf<ScrollState?> { null }

internal enum class MenuPlacement {
    Above,
    Below,
    BelowControl,
    SelectedRow,
}

@Composable
internal fun LiquidMenuAnchor(
    expanded: Boolean,
    close: () -> Unit,
    width: Dp = 272.dp,
    modifier: Modifier = Modifier,
    placement: MenuPlacement = MenuPlacement.Above,
    framed: Boolean = true,
    tag: String = "toolbar-popover",
    toggleAnchor: Boolean = false,
    bare: Boolean = true,
    sharedGlass: Boolean = false,
    menuTitle: String? = null,
    openOnPress: (() -> Unit)? = null,
    anchor: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var anchorBounds by remember { mutableStateOf(Rect.Zero) }
    var anchorCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var ownerBounds by remember { mutableStateOf(Rect.Zero) }
    val parentMenu = LocalGlassMenuScope.current
    val hideParent = LocalDismissMenuStack.current
    val inIsland = LocalMenuInIsland.current
    val menuScope = remember { GlassMenuScope() }
    val menuPlacement = if (parentMenu != null) MenuPlacement.SelectedRow else placement
    val followsRow = menuPlacement == MenuPlacement.SelectedRow
    val clock = remember(followsRow) { PrismPopupClock(followsRow) }
    val active = expanded || clock.retained
    val ownerScroll = if (followsRow) LocalMenuOwnerScroll.current else null
    val openingOffset = remember(active, ownerScroll) { if (active) ownerScroll?.value else null }
    val rowScrolled = openingOffset != null && ownerScroll?.value != openingOffset
    val showPopup = active && !rowScrolled
    DisposableEffect(parentMenu, active) {
        if (active)
            parentMenu?.let {
                it.children++
                it.childClock = clock
            }
        onDispose {
            if (active)
                parentMenu?.let {
                    it.children--
                    if (it.childClock === clock) it.childClock = null
                }
        }
    }
    val position = menuScope.position
    val ink = rememberGraphicsLayer()
    val capture = remember(active) { GlassInkCapture() }
    val density = LocalDensity.current
    val ownerRoot = LocalView.current.rootView
    val source = LocalGlassBackdrop.current
    // Nested Popup views are only as tall as the parent menu. All levels position in the
    // original activity or dialog window, including its system bars and keyboard.
    val menuWindow =
        LocalMenuWindow.current
            ?: MenuWindow(
                if (inIsland) ownerRoot
                else
                    source?.hostView?.rootView
                        ?: MainActivity.current?.window?.decorView
                        ?: ownerRoot,
                if (inIsland) 0 else WindowInsets.statusBars.getTop(density),
                WindowInsets.ime.getBottom(density),
                if (inIsland) 0 else WindowInsets.navigationBars.getBottom(density),
            )
    val root = menuWindow.root
    val overlays = LocalMenuGlassOverlays.current
    val bottomInset =
        if (followsRow) max(menuWindow.keyboard, menuWindow.navigationBar) else menuWindow.keyboard
    val currentClose by rememberUpdatedState(close)
    val openAction by rememberUpdatedState(openOnPress)
    val isExpanded by rememberUpdatedState(expanded)
    val inheritedTracking = LocalCarriedMenu.current
    val tracking = remember { PressMenuTracking() }
    val haptics = rememberUiHaptics()
    val openGesture =
        if (openOnPress == null) Modifier
        else
            Modifier.openMenuOnDown(
                tracking,
                { offset ->
                    anchorCoordinates?.takeIf { it.isAttached }?.localToScreen(offset) ?: offset
                },
            ) {
                when {
                    isExpanded -> {
                        currentClose()
                        false
                    }
                    clock.retained -> false
                    else -> {
                        haptics.perform(UiHaptic.Selection)
                        openAction?.invoke()
                        true
                    }
                }
            }
    BackHandler(expanded) { currentClose() }
    LaunchedEffect(expanded, rowScrolled) {
        if (rowScrolled) {
            clock.hide()
            if (expanded) currentClose()
        } else if (expanded && menuScope.afterReturn != null) {
            currentClose()
        } else {
            clock.move(expanded)
            if (!expanded) {
                val action = menuScope.afterReturn
                menuScope.afterReturn = null
                action?.invoke()
            }
        }
    }
    fun measuredAnchor(coordinates: LayoutCoordinates): Rect {
        if (parentMenu != null)
            return coordinates
                .boundsInRoot()
                .translate(
                    Offset(
                        parentMenu.position.origin.x.toFloat(),
                        parentMenu.position.origin.y.toFloat(),
                    ),
                )
        val ownerScreen = IntArray(2)
        val ownerWindow = IntArray(2)
        val rootScreen = IntArray(2)
        val rootWindow = IntArray(2)
        ownerRoot.getLocationOnScreen(ownerScreen)
        ownerRoot.getLocationInWindow(ownerWindow)
        root.getLocationOnScreen(rootScreen)
        root.getLocationInWindow(rootWindow)
        return coordinates
            .boundsInWindow()
            .translate(
                Offset(
                    (ownerScreen[0] - ownerWindow[0] - rootScreen[0] + rootWindow[0]).toFloat(),
                    (ownerScreen[1] - ownerWindow[1] - rootScreen[1] + rootWindow[1]).toFloat(),
                ),
            )
    }
    // Paint-only parent transforms do not invoke onGloballyPositioned. Read the live
    // coordinates when a row is picked, then keep that pose while its parent recedes.
    val pinnedAnchor =
        remember(active) {
            anchorCoordinates?.takeIf { it.isAttached }?.let(::measuredAnchor) ?: anchorBounds
        }
    SideEffect {
        if (active && parentMenu?.childClock === clock) parentMenu.childAnchorTop = pinnedAnchor.top
    }
    val pinnedParent = remember(active) { parentMenu?.cardBounds?.takeUnless { it.isEmpty } }
    Box(
        modifier
            .then(openGesture)
            .inactiveIf(followsRow && clock.retained && !expanded)
            .onGloballyPositioned {
                anchorCoordinates = it
                ownerBounds = it.boundsInWindow()
                anchorBounds = measuredAnchor(it)
            },
    ) {
        val paneReady = showPopup && clock.retained && clock.ready
        CompositionLocalProvider(
            LocalGlassInkCapture provides (if (showPopup) capture else null),
            LocalHideGlassPane provides (paneReady && capture.recorded),
        ) {
            val sourceInk =
                if (showPopup) Modifier.popupSourceInk(ink, capture, paneReady) else Modifier
            Box(sourceInk) { anchor() }
        }
        if (showPopup) {
            Popup(
                popupPositionProvider =
                    remember(position) {
                        object : PopupPositionProvider {
                            override fun calculatePosition(
                                anchorBounds: IntRect,
                                windowSize: IntSize,
                                layoutDirection: LayoutDirection,
                                popupContentSize: IntSize,
                            ) = position.origin
                        }
                    },
                onDismissRequest = { if (expanded && menuScope.children == 0) currentClose() },
                properties =
                    PopupProperties(
                        focusable = false,
                        clippingEnabled = false,
                        securePolicy = SecureFlagPolicy.Inherit,
                    ),
            ) {
                val menuScroll = if (framed) rememberScrollState() else null
                if (toggleAnchor) KeepMenuAnchorTouchable(ownerRoot, ownerBounds)
                CompositionLocalProvider(
                    LocalGlassMenuScope provides menuScope,
                    LocalMenuWindow provides menuWindow,
                    LocalMenuOwnerScroll provides menuScroll,
                    LocalDismissMenuStack provides
                        {
                            menuScope.afterReturn = null
                            clock.hide()
                            currentClose()
                            hideParent()
                        },
                    LocalCarriedMenu provides
                        (if (openOnPress != null) tracking else inheritedTracking),
                    LocalAfterMenuReturn provides
                        { action ->
                            if (expanded && menuScope.afterReturn == null) {
                                menuScope.afterReturn = action
                                currentClose()
                            }
                        },
                    LocalGlassBackdrop provides source,
                    LocalGlassBackdropOverlays provides overlays,
                    LocalGlassBackdropMotion provides clock.motion,
                    LocalGlassPress provides null,
                ) {
                    PrismPopupSurface(
                        clock,
                        (if (parentMenu == null) anchorBounds
                        else pinnedAnchor.takeUnless { it.isEmpty } ?: anchorBounds),
                        IntSize(root.width, root.height),
                        menuWindow.safeTop,
                        bottomInset,
                        if (framed) (width * .86f).coerceAtLeast(160.dp) else width,
                        menuPlacement,
                        position,
                        ink,
                        bare && !followsRow,
                        sharedGlass,
                        expanded,
                        receding = menuScope.childClock?.unfold?.coerceIn(0f, 1f) ?: 0f,
                        recedeAnchorTop = menuScope.childAnchorTop,
                        parentCard = pinnedParent,
                        onCard = { if (menuScope.cardBounds != it) menuScope.cardBounds = it },
                        ownerRoot = ownerRoot,
                        forwardOutside = toggleAnchor && parentMenu == null && !inIsland,
                        canClose = { menuScope.children == 0 },
                        close = { currentClose() },
                    ) {
                        Column(
                            Modifier.then(
                                    if (menuScroll != null)
                                        Modifier.verticalScroll(menuScroll).padding(6.dp)
                                    else Modifier,
                                )
                                .inactiveIf(!expanded || menuScope.children > 0)
                                .testTag(tag),
                        ) {
                            if (menuTitle != null) {
                                GlassMenuRow(AppIcons.ArrowBack, menuTitle) { currentClose() }
                                GlassMenuDivider()
                            } else if (parentMenu != null) {
                                GlassMenuRow(AppIcons.ArrowBack, "返回") { currentClose() }
                                GlassMenuDivider()
                            }
                            content()
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun GlassMenuRow(
    icon: ImageVector,
    title: String,
    detail: String = "",
    selected: Boolean = false,
    tint: Color = WhaleInk,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val haptics = rememberUiHaptics()
    Row(
        Modifier.fillMaxWidth()
            .menuPickTarget(LocalCarriedMenu.current, remember { Any() }, onClick)
            .dshaPressFeedback(interactions, shape = ContinuousShape(16.dp))
            .clickable(interactionSource = interactions, indication = null, role = Role.Button) {
                haptics.perform(UiHaptic.Selection)
                onClick()
            }
            .semantics { this.selected = selected }
            .heightIn(min = 48.dp)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, style = PrismTypography.callout, color = tint)
            if (detail.isNotEmpty())
                Text(detail, style = PrismTypography.caption, color = WhaleMuted)
        }
        Icon(
            if (selected) AppIcons.Check else icon,
            null,
            Modifier.size(20.dp),
            tint = if (selected) WhaleBlue else tint,
        )
    }
}

@Composable
internal fun GlassMenuDivider() {
    Box(
        Modifier.fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(1.dp)
            .background(WhaleLine.copy(alpha = .65f)),
    )
}

@Composable
internal fun GlassCard(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.clip(ContinuousShape(18.dp))) {
        LiquidGlass(
            Modifier.matchParentSize(),
            corner = 18f,
            tint = Color(0x8CFEFDFE),
            finish = GlassFinish.Menu,
        )
        content()
    }
}
