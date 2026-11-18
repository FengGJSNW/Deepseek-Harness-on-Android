package dev.deepseek.mobile

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real menu/window behavior on an emulator; no live requests or phone preference changes. */
class PrismInteractionTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Before
    fun prepare() {
        assumeTrue(Build.MODEL.contains("sdk_gphone"))
        ui.waitUntil(30000) { AssistantState.ready && DshaFonts.ready }
    }

    private fun popupScreenRect(node: SemanticsNodeInteraction): Rect {
        val bounds = node.fetchSemanticsNode().boundsInWindow
        val screen = IntArray(2)
        val window = IntArray(2)
        ui.runOnUiThread {
            val view =
                android.view.inspector.WindowInspector.getGlobalWindowViews().last {
                    it.javaClass.name.contains("PopupLayout") && it.isShown
                }
            view.getLocationOnScreen(screen)
            view.getLocationInWindow(window)
        }
        return bounds.translate(
            Offset((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat()),
        )
    }

    @Test
    fun sessionShortPressEntersWhileLongPressPeelsIntoTitledMenuAndNestedProjectCard() {
        val conversation = Conversation(title = "液态菜单回归会话")
        val selected = AssistantState.selectedId
        val mode = AssistantState.appMode
        var entries = 0
        ui.runOnUiThread {
            AssistantState.sessions.add(0, conversation)
            AssistantState.appMode = "work"
            ui.activity.setContent {
                MaterialTheme {
                    FontEnvironment {
                        CompositionLocalProvider(
                            LocalDrawerSearch provides remember { DrawerSearchController() },
                        ) {
                            Box(
                                Modifier.fillMaxSize().safeDrawingPadding().background(Color.White),
                            ) {
                                SessionDrawer({ entries++ }, {}, {}, {})
                            }
                        }
                    }
                }
            }
        }
        try {
            ui.onNodeWithText(conversation.title).performScrollTo().performClick()
            assertEquals(1, entries)
            assertEquals(conversation.id, AssistantState.selectedId)
            ui.onNodeWithTag("session-context-menu").assertDoesNotExist()
            ui.mainClock.autoAdvance = false
            ui.onNodeWithText(conversation.title)
                .performSemanticsAction(SemanticsActions.OnLongClick)
            ui.mainClock.advanceTimeBy(112)
            TestSnapshots.capture(ui, "086-session-peel-112ms")
            ui.mainClock.autoAdvance = true
            ui.waitForIdle()
            ui.onNodeWithTag("session-context-menu").assertIsDisplayed()
            ui.onNode(
                    hasText(conversation.title) and
                        hasAnyAncestor(hasTestTag("session-context-menu")),
                )
                .assertIsDisplayed()
            TestSnapshots.capture(ui, "086-session-peel-settled")
            assertEquals("Long press must not enter the conversation", 1, entries)

            val item = popupScreenRect(ui.onNodeWithText("移动到项目"))
            ui.mainClock.autoAdvance = false
            ui.onNodeWithText("移动到项目").performClick()
            ui.mainClock.advanceTimeBy(112)
            TestSnapshots.capture(ui, "086-project-submenu-112ms")
            ui.mainClock.autoAdvance = true
            ui.onNodeWithText("独立会话").assertIsDisplayed()
            TestSnapshots.capture(ui, "086-project-submenu-settled")
            val header = popupScreenRect(ui.onNodeWithText("移动到项目"))
            android.util.Log.i("PrismGeometry", "item=$item header=$header")
            assertTrue(
                "The submenu header must land on its source row: $item -> $header",
                kotlin.math.abs(item.center.y - header.center.y) <
                    with(ui.density) { 16.dp.toPx() },
            )
            ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
            ui.onNodeWithText("重命名").assertIsDisplayed()
            ui.onNodeWithText("独立会话").assertDoesNotExist()
            ui.onNode(
                    hasText(conversation.title) and
                        hasAnyAncestor(hasTestTag("session-context-menu")),
                )
                .performClick()
            ui.onNodeWithTag("session-context-menu").assertDoesNotExist()
            assertEquals(1, entries)
            ui.onNodeWithText(conversation.title).performClick()
            assertEquals(2, entries)
            ui.onNodeWithText("Remote").assertIsDisplayed()
            ui.onNodeWithText("Remote SSH").assertDoesNotExist()
        } finally {
            ui.mainClock.autoAdvance = true
            ui.runOnUiThread {
                AssistantState.sessions.remove(conversation)
                AssistantState.selectedId = selected
                AssistantState.appMode = mode
            }
        }
    }

    @Test
    fun quickMenuBelongsToLeftNavigationAndDashboardTogglesRepeatedly() {
        var navigations = 0
        ui.runOnUiThread {
            ui.activity.setContent {
                MaterialTheme {
                    FontEnvironment {
                        Box(Modifier.fillMaxSize().background(Color.White)) {
                            ConversationTitleBar(
                                "回归会话",
                                "会话列表",
                                { navigations++ },
                                remember { mutableFloatStateOf(0f) },
                                false,
                                null,
                            )
                        }
                    }
                }
            }
        }
        ui.onNodeWithContentDescription("会话列表").performClick()
        assertEquals(1, navigations)
        ui.onNodeWithContentDescription("打开功能区")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
        ui.onNodeWithContentDescription("会话列表").performSemanticsAction(SemanticsActions.OnLongClick)
        ui.onNodeWithTag("workspace-quick-menu").assertIsDisplayed()
        ui.onNodeWithText("修改会话标题").assertIsDisplayed()
        ui.onNodeWithContentDescription("会话列表").performClick()
        ui.onNodeWithTag("workspace-quick-menu").assertDoesNotExist()
        assertEquals(1, navigations)
        repeat(3) {
            ui.onNodeWithTag("context-usage-toggle").performClick()
            ui.onNodeWithTag("context-usage-card").assertIsDisplayed()
            ui.onNodeWithTag("context-usage-toggle").performClick()
            ui.onNodeWithTag("context-usage-card").assertDoesNotExist()
        }
    }

    @Test
    fun diskPresetsAndCustomSliderUseTheWholeRangeWithoutAllocatingStorage() {
        var megabytes by mutableIntStateOf(256)
        ui.runOnUiThread {
            ui.activity.setContent {
                MaterialTheme {
                    FontEnvironment {
                        Box(
                            Modifier.fillMaxSize()
                                .safeDrawingPadding()
                                .background(Color.White)
                                .padding(16.dp),
                        ) {
                            DiskCapacityPicker(megabytes) { megabytes = it }
                        }
                    }
                }
            }
        }
        for (value in listOf(512, 1024, 4096, 256)) {
            ui.onNodeWithTag("disk-capacity-$value").performClick()
            assertEquals(value, megabytes)
        }
        ui.onNodeWithTag("parameter-slider").performSemanticsAction(SemanticsActions.SetProgress) {
            it(9f)
        }
        assertEquals(32768, megabytes)
        TestSnapshots.capture(ui, "086-disk-32g")
        ui.onNodeWithTag("parameter-slider").performSemanticsAction(SemanticsActions.SetProgress) {
            it(0f)
        }
        assertEquals(64, megabytes)
    }

    @Test
    fun searchKeepsItsOriginAcrossOpenCloseAndMidFlightReversal() {
        lateinit var search: DrawerSearchController
        ui.runOnUiThread {
            ui.activity.setContent {
                MaterialTheme {
                    FontEnvironment {
                        search = remember { DrawerSearchController() }
                        val motion = rememberDrawerSearchProgress(search)
                        Box(
                            Modifier.fillMaxSize()
                                .safeDrawingPadding()
                                .background(Color(0xFFF2F2F0)),
                        ) {
                            Column(
                                Modifier.fillMaxSize()
                                    .searchBackgroundBlur(motion)
                                    .inactiveIf(search.visible),
                            ) {
                                DrawerSearchHeader(
                                    true,
                                    search.visible,
                                    { search.show(it) {} },
                                    searchButtonAlpha = search.motion.button.value,
                                    onMode = {},
                                )
                                repeat(9) {
                                    androidx.compose.material3.Text(
                                        "项目与会话 ${it + 1}",
                                        Modifier.padding(18.dp),
                                    )
                                }
                            }
                            DrawerSearchOverlay(search, motion)
                        }
                    }
                }
            }
        }
        TestSnapshots.capture(ui, "086-search-rest")
        ui.mainClock.autoAdvance = false
        ui.onNodeWithContentDescription("搜索内容").performClick()
        ui.mainClock.advanceTimeBy(80)
        TestSnapshots.capture(ui, "086-search-open-80ms")
        ui.runOnUiThread { search.close() }
        ui.mainClock.advanceTimeBy(48)
        ui.runOnUiThread { search.visible = true }
        ui.mainClock.advanceTimeBy(192)
        TestSnapshots.capture(ui, "086-search-reversed-192ms")
        ui.mainClock.autoAdvance = true
        ui.onNodeWithTag("drawer-search-field").assertIsFocused().performTextInput("回归")
        ui.onNodeWithTag("drawer-search-field").assertTextContains("回归")
        TestSnapshots.capture(ui, "086-search-open-settled")
        ui.mainClock.autoAdvance = false
        ui.onNodeWithContentDescription("关闭搜索").performClick()
        ui.mainClock.advanceTimeBy(160)
        TestSnapshots.capture(ui, "086-search-close-160ms")
        ui.mainClock.autoAdvance = true
        ui.onNodeWithTag("drawer-search-overlay").assertDoesNotExist()
        ui.onNodeWithContentDescription("搜索内容").assertIsDisplayed()
    }

    @Test
    fun roundControlHasConnectedGlassDuringSeparationAndReturnsWithoutReopening() {
        var expanded by mutableStateOf(false)
        val glass = ExperienceSettings.liquidGlassEnabled
        val transparency = ExperienceSettings.glassTransparency
        val blur = ExperienceSettings.liquidGlassBlur
        ui.runOnUiThread {
            ExperienceSettings.liquidGlassEnabled = true
            ExperienceSettings.glassTransparency = 1f
            ExperienceSettings.liquidGlassBlur = 0f
            ui.activity.setContent {
                MaterialTheme {
                    FontEnvironment {
                        val view = LocalView.current
                        val layer = rememberGraphicsLayer()
                        val backdrop = remember { ChatBackdrop(layer, view) }
                        CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                                Canvas(Modifier.fillMaxSize().chatBackdrop(backdrop)) {
                                    drawRect(Color(0xFFEEF2FA))
                                    for (line in 0..40) drawRect(
                                        if (line % 2 == 0) Color(0xFF6F97E3) else Color(0xFFB6CAE9),
                                        Offset(0f, (line * 24).dp.toPx()),
                                        Size(size.width, 8.dp.toPx()),
                                    )
                                }
                                Box(Modifier.align(Alignment.TopEnd).padding(24.dp)) {
                                    LiquidMenuAnchor(
                                        expanded,
                                        { expanded = false },
                                        placement = MenuPlacement.Below,
                                        bare = false,
                                        toggleAnchor = true,
                                        tag = "prism-round-menu",
                                        anchor = {
                                            DshaIconButton(
                                                "展开菜单",
                                                { expanded = !expanded },
                                                glass = true,
                                            ) {
                                                Icon(AppIcons.MoreHoriz, null)
                                            }
                                        },
                                    ) {
                                        GlassMenuRow(AppIcons.Edit, "编辑") {}
                                        GlassMenuDivider()
                                        GlassMenuRow(AppIcons.FolderOpen, "移动") {}
                                    }
                                }
                                Row(Modifier.align(Alignment.BottomEnd).padding(24.dp)) {
                                    ComposerSendButton(false, tag = "disabled-send") {}
                                    ComposerSendButton(true, tag = "blue-send") {}
                                }
                            }
                        }
                    }
                }
            }
        }
        try {
            TestSnapshots.capture(ui, "086-clear-glass-rest")
            ui.mainClock.autoAdvance = false
            ui.onNodeWithContentDescription("展开菜单").performClick()
            var previous = 0L
            for (time in listOf(32L, 64L, 112L, 176L, 288L, 480L)) {
                ui.mainClock.advanceTimeBy(time - previous)
                TestSnapshots.capture(ui, "086-goo-open-${time}ms")
                previous = time
            }
            ui.mainClock.autoAdvance = true
            ui.onNodeWithTag("prism-round-menu").assertIsDisplayed()
            ui.mainClock.autoAdvance = false
            ui.onNodeWithContentDescription("展开菜单").performClick()
            ui.mainClock.advanceTimeBy(160)
            TestSnapshots.capture(ui, "086-goo-return-160ms")
            ui.mainClock.autoAdvance = true
            ui.onNodeWithTag("prism-round-menu").assertDoesNotExist()
            assertFalse(expanded)
        } finally {
            ui.mainClock.autoAdvance = true
            ui.runOnUiThread {
                ExperienceSettings.liquidGlassEnabled = glass
                ExperienceSettings.glassTransparency = transparency
                ExperienceSettings.liquidGlassBlur = blur
            }
        }
    }
}
