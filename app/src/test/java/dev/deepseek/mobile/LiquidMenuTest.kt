package dev.deepseek.mobile

import android.app.Application
import android.graphics.RenderNode
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.deepseek.mobile.runtime.SandboxPreset
import dev.deepseek.mobile.runtime.SandboxProfile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class LiquidMenuTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    private fun popupBounds(tag: String = "toolbar-popover"): androidx.compose.ui.geometry.Rect {
        val bounds = ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        val location = IntArray(2)
        ui.runOnIdle {
            WindowInspector.getGlobalWindowViews()
                .last { it.javaClass.name.contains("PopupLayout") }
                .getLocationOnScreen(location)
        }
        return bounds.translate(
            androidx.compose.ui.geometry.Offset(location[0].toFloat(), location[1].toFloat()),
        )
    }

    private fun searchFixture(controller: DrawerSearchController) {
        ui.setContent {
            MaterialTheme(typography = PrismTypography.material) {
                val growth = rememberDrawerSearchProgress(controller)
                Box(Modifier.width(380.dp).height(620.dp).background(DshaSurface.page)) {
                    Column(Modifier.width(280.dp)) {
                        DrawerSearchHeader(true, controller.visible, { controller.show(it) {} }) {}
                        Text("新对话", Modifier.padding(16.dp).testTag("below-search"))
                    }
                    DrawerSearchOverlay(controller, growth)
                }
            }
        }
    }

    @Test
    fun searchUsesFullWidthWithoutMovingTheSidebarAndKeepsResultsBelowTheBar() {
        val search = DrawerSearchController()
        searchFixture(search)
        val before = ui.onNodeWithTag("drawer-mode").fetchSemanticsNode().boundsInRoot
        val listBefore = ui.onNodeWithTag("below-search").fetchSemanticsNode().boundsInRoot
        ui.onNodeWithContentDescription("搜索内容").performClick()
        ui.onNodeWithTag("drawer-search-field").performTextInput("代码审查")
        assertEquals("代码审查", search.query)
        val bar = ui.onNodeWithTag("drawer-search-surface").fetchSemanticsNode().boundsInRoot
        val results = ui.onNodeWithTag("drawer-search-results").fetchSemanticsNode().boundsInRoot
        assertTrue(bar.width > 280f)
        assertEquals(48f, bar.height, 1f)
        assertTrue(results.top >= bar.bottom)
        assertEquals(before, ui.onNodeWithTag("drawer-mode").fetchSemanticsNode().boundsInRoot)
        assertEquals(listBefore, ui.onNodeWithTag("below-search").fetchSemanticsNode().boundsInRoot)
        ui.onNodeWithContentDescription("关闭搜索").performClick()
        ui.onNodeWithTag("drawer-search-overlay").assertDoesNotExist()
        ui.onNodeWithContentDescription("搜索内容").performClick()
        assertEquals("", search.query)
    }

    @Test
    fun interruptedExpansionLeavesNoSearchFieldOrResultHitTargets() {
        val search = DrawerSearchController()
        searchFixture(search)
        ui.mainClock.autoAdvance = false
        ui.onNodeWithContentDescription("搜索内容").performClick()
        ui.mainClock.advanceTimeBy(96)
        ui.runOnIdle { search.close() }
        ui.mainClock.autoAdvance = true
        ui.waitForIdle()
        ui.onNodeWithTag("drawer-search-overlay").assertDoesNotExist()
        ui.onNodeWithTag("drawer-search-field").assertDoesNotExist()
        ui.onNodeWithContentDescription("搜索内容").assertIsDisplayed()
    }

    @Test
    fun menuInterruptionKeepsTheAnchorUntilTheReturnOrRowSpringLands() = runBlocking {
        for (isRow in listOf(false, true)) {
            val frames = BroadcastFrameClock()
            val clock = PrismPopupClock(isRow)
            clock.ready = true
            fun travel(open: Boolean) =
                launch(frames, start = CoroutineStart.UNDISPATCHED) {
                    clock.move(open)
                }
            suspend fun tick(ms: Long) {
                frames.sendFrame(ms * 1_000_000)
                yield()
            }
            val open = travel(true)
            tick(100)
            tick(180)
            val atTurn = if (isRow) clock.unfold else clock.opening
            assertTrue(atTurn > 0f)
            open.cancelAndJoin()
            val close = travel(false)
            tick(180)
            assertEquals(atTurn, if (isRow) clock.unfold else clock.opening, .0001f)
            tick(181)
            if (isRow) {
                assertTrue("the selected row carries its opening velocity", clock.unfold > atTurn)
                tick(450)
                assertTrue(clock.unfold < atTurn)
                tick(2180)
            } else {
                assertEquals(.001f, checkNotNull(clock.returning), .0001f)
                tick(679)
                assertTrue(clock.retained)
                assertFalse(close.isCompleted)
                tick(680)
            }
            assertTrue(close.isCompleted)
            assertFalse(clock.retained)
            assertFalse(clock.ready)
        }
    }

    @Test
    fun pressOpensOnceAndPageActionWaitsForItsButtonToLand() {
        var open by mutableStateOf(false)
        var visits = 0
        ui.setContent {
            Box(Modifier.fillMaxSize().padding(start = 100.dp, top = 80.dp)) {
                LiquidMenuAnchor(
                    open,
                    { open = false },
                    placement = MenuPlacement.Below,
                    openOnPress = { open = true },
                    anchor = {
                        Text(
                            "按钮",
                            Modifier.size(48.dp).clickable { open = !open }.testTag("origin"),
                        )
                    },
                ) {
                    val pick = LocalAfterMenuReturn.current
                    GlassMenuRow(AppIcons.Chat, "切换页面") { pick { visits++ } }
                }
            }
        }
        ui.mainClock.autoAdvance = false
        val button = ui.onNodeWithTag("origin")
        button.performTouchInput { down(center) }
        ui.runOnIdle { assertTrue(open) }
        button.performTouchInput { up() }
        ui.runOnIdle { assertTrue(open) }
        ui.mainClock.advanceTimeBy(660)
        ui.onNodeWithText("切换页面")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        ui.runOnIdle {
            assertFalse(open)
            assertEquals(0, visits)
        }
        ui.mainClock.advanceTimeBy(320)
        ui.runOnIdle { assertEquals(0, visits) }
        ui.mainClock.advanceTimeBy(400)
        ui.runOnIdle { assertEquals(1, visits) }
    }

    @Test
    fun openingFingerCanSlideIntoThePopupAndSelectOnce() {
        var expanded by mutableStateOf(false)
        var picks = 0
        ui.setContent {
            Box(Modifier.fillMaxSize().padding(start = 152.dp, top = 88.dp)) {
                LiquidMenuAnchor(
                    expanded,
                    { expanded = false },
                    placement = MenuPlacement.Below,
                    openOnPress = { expanded = true },
                    anchor = {
                        Text(
                            "选项",
                            Modifier.size(48.dp)
                                .clickable { expanded = !expanded }
                                .testTag("held-menu"),
                        )
                    },
                ) {
                    GlassMenuRow(AppIcons.Chat, "选择") {
                        picks++
                        expanded = false
                    }
                }
            }
        }
        val button = ui.onNodeWithTag("held-menu")
        button.performTouchInput { down(center) }
        val target = popupBounds().center
        val screen = IntArray(2)
        ui.runOnIdle { ui.activity.window.decorView.getLocationOnScreen(screen) }
        val origin =
            button.fetchSemanticsNode().boundsInWindow.center +
                androidx.compose.ui.geometry.Offset(screen[0].toFloat(), screen[1].toFloat())
        button.performTouchInput {
            moveBy(target - origin)
            up()
        }
        ui.runOnIdle {
            assertEquals(1, picks)
            assertFalse(expanded)
        }
    }

    @Test
    fun longMarkdownFoldCanOpenScrollToItsEndAndClose() {
        val content = (1..220).joinToString("\n\n") { "折叠段落 $it" }
        val raw = "<details>\n<summary>长折叠块</summary>\n\n$content\n\n</details>"
        val parsed = MarkdownDocument.parse(raw).single() as MarkdownBlock.Fold
        assertEquals(content, parsed.body)
        ui.setContent {
            MaterialTheme(typography = PrismTypography.material) {
                MarkdownText(raw)
            }
        }
        ui.waitUntil(5000) {
            ui.onAllNodesWithTag("markdown-fold-toggle").fetchSemanticsNodes().size == 1
        }
        val toggle = ui.onNodeWithTag("markdown-fold-toggle")
        ui.onNodeWithTag("markdown-fold-scroll").assertDoesNotExist()
        toggle.performClick()
        ui.waitUntil(5000) { ui.onAllNodesWithText("折叠段落 1").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("markdown-fold-scroll").performScrollToIndex(219)
        ui.onNodeWithText("折叠段落 220").assertIsDisplayed()
        toggle.performClick()
        ui.onNodeWithTag("markdown-fold-scroll").assertDoesNotExist()
        val literal = MarkdownDocument.parse("```html\n<details>\n</details>\n```")
        assertTrue(literal.single() is MarkdownBlock.Code)
    }

    @Test
    fun appearanceChoiceStaysBesideTheTappedRowEvenWithALongList() {
        ui.setContent {
            MaterialTheme(typography = PrismTypography.material) {
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(380.dp))
                    Box(Modifier.testTag("choice-origin")) {
                        SettingsChoice(AppIcons.Spark, "思考链显示方式", "中心卡片") { close ->
                            repeat(18) {
                                GlassMenuRow(AppIcons.Check, "选项 " + (it + 1)) { close() }
                            }
                        }
                    }
                }
            }
        }
        val row = ui.onNodeWithTag("choice-origin").fetchSemanticsNode().boundsInWindow
        ui.onNodeWithText("思考链显示方式").performClick()
        val card = popupBounds()
        assertTrue("row $row, card $card", card.left - row.left in 30f..40f)
        assertTrue("row $row, card $card", card.top - row.top in -24f..-12f)
        ui.onNodeWithText("选项 18").performScrollTo().assertIsDisplayed().performClick()
        ui.onNodeWithTag("toolbar-popover").assertDoesNotExist()
    }

    @Test
    fun bottomRowKeepsActionsReachableWithoutRaisingTheWholeListAboveIt() {
        ui.setContent {
            Box(Modifier.fillMaxSize().padding(8.dp)) {
                Box(Modifier.align(Alignment.BottomEnd).width(180.dp).testTag("bottom-origin")) {
                    SettingsChoice(AppIcons.Check, "选择", "当前项") { close ->
                        repeat(24) { GlassMenuRow(AppIcons.Check, "操作 " + it) { close() } }
                    }
                }
            }
        }
        val row = ui.onNodeWithTag("bottom-origin").fetchSemanticsNode().boundsInWindow
        ui.onNodeWithText("选择").performClick()
        val card = popupBounds()
        assertTrue("bottom row $row, card $card", card.top in (row.top - 120f)..row.top)
        assertTrue(card.left >= row.left && card.right <= row.right)
        ui.onNodeWithText("操作 23").performScrollTo().assertIsDisplayed().performClick()
        ui.onNodeWithTag("toolbar-popover").assertDoesNotExist()
    }

    @Test
    fun nestedDrawerChoiceUsesTheTappedOptionInTheParentWindow() {
        var open by mutableStateOf(false)
        var nested by mutableStateOf(false)
        ui.setContent {
            Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 40.dp, top = 300.dp)) {
                LiquidMenuAnchor(
                    open,
                    { open = false },
                    placement = MenuPlacement.SelectedRow,
                    tag = "session-menu",
                    menuTitle = "会话一",
                    anchor = {
                        Text("打开", Modifier.fillMaxWidth().height(48.dp).clickable { open = true })
                    },
                ) {
                    GlassMenuRow(AppIcons.Edit, "重命名") {}
                    LiquidMenuAnchor(
                        nested,
                        { nested = false },
                        menuTitle = "移动到项目",
                        tag = "project-menu",
                        modifier = Modifier.testTag("project-origin"),
                        anchor = { GlassMenuRow(AppIcons.FolderOpen, "移动到项目") { nested = true } },
                    ) {
                        GlassMenuRow(AppIcons.Chat, "独立会话") { nested = false }
                    }
                }
            }
        }
        ui.onNodeWithText("打开").performClick()
        val row = popupBounds("project-origin")
        ui.onNodeWithText("移动到项目").performClick()
        val child = popupBounds("project-menu")
        assertTrue("nested row $row, card $child", child.left - row.left in 16f..29f)
        assertTrue("nested row $row, card $child", child.top - row.top in -24f..-12f)
        ui.onNodeWithText("独立会话").performClick()
        ui.onNodeWithTag("project-menu").assertDoesNotExist()
        ui.onNodeWithTag("session-menu").assertIsDisplayed()
    }

    @Test
    fun scrollingCustomSandboxOptionsRemovesVersionCardAndRestoresItsRow() {
        val initial = SandboxPreset(profile = SandboxProfile.CUSTOM)
        var preset by mutableStateOf(initial)
        ui.setContent {
            MaterialTheme(typography = PrismTypography.material) {
                WhaleSheet("创建 Sandbox", close = {}) { Text("DSHA Linux Custom") }
                WhaleSheet("自定义选项", close = {}) {
                    SandboxPresetEditor(preset) { preset = it }
                }
            }
        }
        val sheet = ui.onNode(hasScrollAction() and hasAnyDescendant(hasText("开发工具链")))
        val popup = ui.onNodeWithTag("toolbar-popover")
        for (title in listOf("Alpine Linux", "Python 版本", "Node.js 版本")) {
            ui.onNodeWithText("优先使用 DSHA Tool").performScrollTo()
            ui.onNodeWithText(title).performScrollTo().performClick()
            popup.assertIsDisplayed()
            val scroll = SemanticsProperties.VerticalScrollAxisRange
            val before = sheet.fetchSemanticsNode().config[scroll].value()
            sheet.performTouchInput {
                swipe(
                    Offset(width - 2f, height * .8f),
                    Offset(width - 2f, height * .3f),
                )
            }
            assertTrue(sheet.fetchSemanticsNode().config[scroll].value() > before)
            popup.assertDoesNotExist()
            ui.onAllNodesWithText(title).assertCountEquals(1)
            assertEquals(initial, preset)
        }
        ui.onNodeWithText("Python 版本").performScrollTo().performClick()
        ui.onNodeWithText("Python 3.12").performClick()
        popup.assertDoesNotExist()
        assertEquals("3.12", preset.release.python)
    }

    @Test
    fun scrollingInsideTheVersionMenuKeepsTheSheetAndMenuInPlace() {
        var chosen = -1
        ui.setContent {
            WhaleSheet("自定义选项", close = {}) {
                SettingsChoice(AppIcons.Code, "工具版本", "") { close ->
                    repeat(30) { index ->
                        GlassMenuRow(AppIcons.Code, "版本 " + index) {
                            chosen = index
                            close()
                        }
                    }
                }
                Spacer(Modifier.height(1000.dp))
            }
        }
        ui.onNodeWithText("工具版本").performClick()
        val before = popupBounds()
        ui.onNodeWithText("版本 29").performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("toolbar-popover").assertExists()
        ui.onNodeWithText("版本 29").performClick()
        ui.onNodeWithTag("toolbar-popover").assertDoesNotExist()
        ui.onNodeWithText("工具版本").assertIsDisplayed().performClick()
        assertEquals(before, popupBounds())
        ui.runOnIdle { assertEquals(29, chosen) }
    }

    @Test
    fun nestedSettingChoosesRowOnceAndReopensOnlyOnNewPress() {
        var chosen = ""
        ui.setContent {
            MaterialTheme(typography = PrismTypography.material) {
                WhaleSheet("自定义选项", close = {}) {
                    SettingsChoice(AppIcons.Code, "工具版本", chosen) { close ->
                        GlassMenuRow(AppIcons.Code, "新版") {
                            chosen = "新版"
                            close()
                        }
                    }
                }
            }
        }
        ui.onNodeWithText("工具版本").performClick()
        ui.onNodeWithText("新版").performClick()
        ui.runOnIdle { assertEquals("新版", chosen) }
        ui.onNodeWithTag("toolbar-popover").assertDoesNotExist()
        ui.onNodeWithText("工具版本").performClick()
        ui.onNodeWithTag("toolbar-popover").assertIsDisplayed()
    }

    @Test
    fun tappingTheFirstModeAtItsLabelSelectsChatOnce() {
        var expanded by mutableStateOf(false)
        var choices = 0
        ui.setContent {
            Box(Modifier.fillMaxSize().padding(12.dp)) {
                DrawerSearchHeader(
                    work = true,
                    searchVisible = false,
                    onSearch = {},
                    modeExpanded = expanded,
                    modeMenu = {
                        GlassMenuRow(AppIcons.Chat, "Chat", "DeepSeek 官方聊天") {
                            choices++
                            expanded = false
                        }
                        GlassMenuRow(AppIcons.Terminal, "Work", "手机与终端工具") {}
                    },
                    onMode = { expanded = !expanded },
                )
            }
        }
        val trigger = ui.onNodeWithTag("drawer-mode")
        ui.mainClock.autoAdvance = false
        trigger.performTouchInput { click() }
        ui.mainClock.advanceTimeBy(220)
        ui.onNodeWithText("DeepSeek 官方聊天").performTouchInput {
            click(Offset(12f, 16f))
        }
        ui.runOnIdle { assertEquals(1, choices) }
        ui.mainClock.autoAdvance = true
        ui.onNodeWithTag("drawer-mode-menu").assertDoesNotExist()
        trigger.performTouchInput { click() }
        trigger.performTouchInput { click() }
        ui.onNodeWithTag("drawer-mode-menu").assertDoesNotExist()
        ui.runOnIdle { assertEquals(1, choices) }
    }

    @Test
    fun dashboardCanCloseAndReopenOnTheSameAnchor() {
        ui.setContent {
            MaterialTheme(typography = PrismTypography.material) {
                Box(Modifier.fillMaxSize().padding(top = 60.dp)) { ContextUsageButton(null) }
            }
        }
        repeat(3) {
            ui.onNodeWithTag("context-usage-toggle").performClick()
            ui.onNodeWithTag("context-usage-card").assertIsDisplayed()
            ui.onNodeWithTag("context-usage-toggle").performClick()
            ui.onNodeWithTag("context-usage-card").assertDoesNotExist()
        }
    }

    @Test
    fun markdownMenuKeepsTheSameFocusedEditorAndText() {
        var text by mutableStateOf("## Title\n\n**editable**")
        var preview by mutableStateOf(false)
        ui.setContent {
            MaterialTheme(typography = PrismTypography.material) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                    ComposerDraft(text, { text = it }, "Write", "editor", preview = preview)
                    MarkdownPreviewToggle(preview, true) { preview = it }
                }
            }
        }
        ui.onNodeWithTag("editor").performClick().assertIsFocused()
        val editor = ui.onNodeWithTag("editor").fetchSemanticsNode().id
        ui.onNodeWithTag("composer-preview-toggle").performClick()
        ui.onNodeWithTag("editor").assertIsFocused()
        ui.onNodeWithText("Markdown 预览").performClick()
        assertTrue(preview)
        assertEquals(editor, ui.onNodeWithTag("editor").fetchSemanticsNode().id)
        ui.onNodeWithTag("editor").assertIsFocused().performTextInput(" more")
        assertTrue(text.contains("more"))
        ui.onNodeWithTag("composer-preview-toggle").performClick()
        ui.onNodeWithText("纯文本").performClick()
        assertFalse(preview)
        ui.onNodeWithTag("editor").assertIsFocused()
    }

    @Test
    fun contextualMenuUnfoldsFromItsRowAndClosesWithoutActing() {
        var open by mutableStateOf(false)
        var edits = 0
        ui.setContent {
            MaterialTheme(typography = PrismTypography.material) {
                Column(Modifier.width(340.dp).padding(top = 90.dp)) {
                    LiquidMenuAnchor(
                        open,
                        { open = false },
                        placement = MenuPlacement.SelectedRow,
                        anchor = {
                            Text(
                                "会话操作",
                                Modifier.fillMaxWidth()
                                    .height(48.dp)
                                    .clickable { open = true }
                                    .testTag("row"),
                            )
                        },
                    ) {
                        GlassMenuRow(AppIcons.Edit, "重命名") {
                            edits++
                            open = false
                        }
                        GlassMenuRow(AppIcons.FolderOpen, "移动到项目") {
                            edits++
                            open = false
                        }
                    }
                }
            }
        }
        ui.onNodeWithTag("row").performClick()
        val anchor = ui.onNodeWithTag("row").fetchSemanticsNode().boundsInWindow
        val menu = popupBounds()
        assertTrue("drawer row and menu: $anchor, $menu", menu.top - anchor.top in -24f..-12f)
        assertTrue(menu.left - anchor.left in 26f..38f)
        assertTrue(menu.bottom > anchor.bottom)
        ui.runOnIdle { open = false }
        ui.onNodeWithTag("toolbar-popover").assertDoesNotExist()
        assertEquals(0, edits)
    }

    @Test
    fun chromeTypefaceIgnoresConversationPresetWeightAndItalic() {
        val oldPreset = FontSettings.preset
        val oldWeight = FontSettings.weight
        val oldItalic = FontSettings.italic
        try {
            val normal = DshaFonts.native(FontRole.UI)
            FontSettings.preset = FontPreset.MONO
            FontSettings.weight = 800
            FontSettings.italic = true
            assertEquals(normal, DshaFonts.native(FontRole.UI))
            assertFalse(DshaFonts.native(FontRole.UI).isItalic)
            assertEquals(400, DshaFonts.native(FontRole.UI).weight)
        } finally {
            FontSettings.preset = oldPreset
            FontSettings.weight = oldWeight
            FontSettings.italic = oldItalic
        }
    }

    @Test
    fun menuSamplesTheOwningPageAcrossPopupWindowsAndRendersTheRealControls() {
        var open by mutableStateOf(false)
        var stripe by mutableStateOf(Color(0xFF2A58C2))
        ui.setContent {
            val host = LocalView.current
            val layer = rememberGraphicsLayer()
            val source = remember { ChatBackdrop(layer, host) }
            MaterialTheme(typography = PrismTypography.material) {
                Box(Modifier.fillMaxSize().onPlaced { source.viewport = it }) {
                    Column(
                        Modifier.fillMaxSize().chatBackdrop(source).background(DshaSurface.page),
                    ) {
                        Text("设置", Modifier.padding(24.dp), style = PrismTypography.pageTitle)
                        SettingsGroup {
                            SettingsLink(AppIcons.Image, "外观", "聊天正文保留 Anthropic 风格") {}
                            SettingsLink(AppIcons.Description, "记忆", "项目与全局记忆") {}
                            ToggleSetting("触感反馈", "跟随系统设置", true) {}
                        }
                        repeat(20) {
                            Box(
                                Modifier.fillMaxWidth()
                                    .height(30.dp)
                                    .background(if (it % 2 == 0) stripe else Color.White),
                            )
                        }
                    }
                    CompositionLocalProvider(LocalGlassBackdrop provides source) {
                        LiquidMenuAnchor(
                            open,
                            { open = false },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(32.dp),
                            anchor = {
                                DshaIconButton("打开菜单", { open = true }, glass = true) { Text("+") }
                            },
                        ) {
                            GlassMenuRow(AppIcons.Image, "图片", "最多 4 张") { open = false }
                            GlassMenuRow(AppIcons.Description, "文件与文档", "最多 32 MB") { open = false }
                        }
                    }
                }
            }
        }
        val trigger = ui.onNodeWithContentDescription("打开菜单")
        val triggerCenter = trigger.fetchSemanticsNode().boundsInRoot.center
        trigger.performClick()
        fun capture(name: String): android.graphics.Bitmap {
            lateinit var bitmap: android.graphics.Bitmap
            ui.runOnIdle {
                val decor = ui.activity.window.decorView
                val node = RenderNode(name)
                node.setPosition(0, 0, decor.width, decor.height)
                val canvas = node.beginRecording(decor.width, decor.height)
                val windows = WindowInspector.getGlobalWindowViews().ifEmpty { listOf(decor) }
                windows.forEach { view ->
                    val position = IntArray(2)
                    view.getLocationOnScreen(position)
                    canvas.save()
                    canvas.translate(position[0].toFloat(), position[1].toFloat())
                    view.draw(canvas)
                    canvas.restore()
                    androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                }
                node.endRecording()
                bitmap = renderHardware(node, decor.width, decor.height)
                saveGlassFrame(bitmap, name)
            }
            return bitmap
        }
        val before = capture("085-liquid-menu-blue")
        val x = triggerCenter.x.toInt()
        val y = triggerCenter.y.toInt()
        assertEquals(
            "the first painted popup must already own the glyph",
            before.getPixel(x - 48, y),
            before.getPixel(x, y),
        )
        ui.runOnIdle { stripe = Color(0xFFD64241) }
        val after = capture("085-liquid-menu-red")
        // The popup itself must change, not only the stripes outside it.
        val bounds = popupBounds()
        var changed = 0
        for (x in (bounds.left.toInt() + 24) until (bounds.right.toInt() - 24)) {
            for (y in (bounds.top.toInt() + 24) until (bounds.bottom.toInt() - 24)) {
                if (
                    x in 0 until before.width &&
                        y in 0 until before.height &&
                        before.getPixel(x, y) != after.getPixel(x, y)
                )
                    changed++
            }
        }
        assertTrue(
            "The popup must retain live backdrop sampling: $changed pixels in $bounds",
            changed > 40,
        )
        before.recycle()
        after.recycle()
    }
}
