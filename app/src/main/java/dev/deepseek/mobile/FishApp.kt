package dev.deepseek.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The conversation is a raised card that moves aside to reveal its session list. */
@Composable fun FishApp() = FontEnvironment { FishAppContent() }

@Composable
private fun FishAppContent() {
    val state = AssistantState
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(context) { AgentIdentity.load(context) }
    if (state.sessions.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    DisposableEffect(Unit) { onDispose { OfficialChatNavigation.dispose() } }
    LaunchedEffect(state.appMode) {
        if (state.appMode == "chat" && !OfficialChatNavigation.accessConfirmed)
            OfficialChatNavigation.requestChat(context)
    }
    val sceneStates = rememberSaveableStateHolder()
    LaunchedEffect(OfficialChatNavigation.accessConfirmed) {
        if (!OfficialChatNavigation.accessConfirmed) sceneStates.removeState("official")
    }
    var settings by rememberSaveable { mutableStateOf(false) }
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(drawerOpen) { focusManager.clearFocus() }
    LaunchedEffect(state.appMode, OfficialChatNavigation.accessConfirmed) {
        if (state.appMode == "chat" && OfficialChatNavigation.accessConfirmed) {
            settings = false
        }
    }
    var retainedSettings by remember { mutableStateOf(false) }
    var settingsEntryFromWork by remember { mutableStateOf(false) }
    val motion = remember { Animatable(0f) }
    val workspaceOpen = WorkspaceState.visible
    val official = state.appMode == "chat" && OfficialChatNavigation.accessConfirmed
    val workspaceMotion =
        animateFloatAsState(
            if (workspaceOpen) 1f else 0f,
            UiSpring.drawer(.001f),
            label = "right-workspace",
        )
    LaunchedEffect(workspaceOpen) {
        if (workspaceOpen) drawerOpen = false
        focusManager.clearFocus()
    }
    val conversationShape = remember { RoundedCornerShape(34.dp) }
    val cardAside = remember {
        derivedStateOf {
            drawerOpen ||
                motion.value > .001f ||
                WorkspaceState.visible ||
                workspaceMotion.value > .001f
        }
    }
    val workspaceChrome by remember {
        derivedStateOf { WorkspaceState.visible || workspaceMotion.value > .001f }
    }
    val showingSettings = (settings || retainedSettings) && !settingsEntryFromWork
    LaunchedEffect(drawerOpen, settings) {
        if (settings && settingsEntryFromWork) {
            motion.snapTo(1f)
            settingsEntryFromWork = false
        }
        motion.animateTo(if (drawerOpen) 1f else 0f, UiSpring.drawer(.001f))
        if (!settings) retainedSettings = false
    }
    val snack = remember { SnackbarHostState() }
    val density = LocalDensity.current
    LaunchedEffect(state.error) {
        if (state.error.isNotBlank()) {
            val error = state.error
            state.error = ""
            snack.showSnackbar(error)
        }
    }
    BackHandler(!workspaceOpen && (settings || drawerOpen)) {
        if (settings) {
            retainedSettings = true
            settings = false
            drawerOpen = true
        } else {
            retainedSettings = false
            drawerOpen = false
        }
    }
    MaterialTheme(
        colorScheme =
            lightColorScheme(
                primary = WhaleBlue,
                onPrimary = Color.White,
                background = Color.White,
                surface = Color.White,
                onSurface = WhaleInk,
                onSurfaceVariant = WhaleMuted,
                outline = WhaleLine,
                secondaryContainer = WhaleTint,
            ),
        shapes =
            Shapes(
                extraSmall = ContinuousShape(8.dp),
                small = ContinuousShape(12.dp),
                medium = ContinuousShape(18.dp),
                large = ContinuousShape(24.dp),
                extraLarge = ContinuousShape(30.dp),
            ),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFFF2F2F0))) {
            val drawerWidth = this.maxWidth * .82f
            val drawerPx = with(density) { drawerWidth.toPx() }
            // Keep the measured drawer warm. Unplaced content cannot draw, receive input,
            // or expose accessibility nodes, and reopening preserves its search/scroll state.
            Box(
                Modifier.width(drawerWidth)
                    .fillMaxHeight()
                    .systemBarsPadding()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            if (cardAside.value) placeable.placeRelative(0, 0)
                        }
                    }
                    .inactiveIf(!drawerOpen),
            ) {
                SessionDrawer(
                    onSelect = {
                        retainedSettings = false
                        drawerOpen = false
                    },
                    onSettings = {
                        retainedSettings = true
                        settings = true
                        drawerOpen = false
                    },
                )
            }
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        val progress = motion.value
                        val raised = progress > .001f
                        // Reuse the card's composited pixels while its transform changes.
                        // Let normal chat rendering release the full-size buffer at rest.
                        compositingStrategy =
                            if (drawerOpen || raised) CompositingStrategy.Offscreen
                            else CompositingStrategy.Auto
                        translationX = drawerPx * progress
                        scaleX = 1f - .025f * progress
                        scaleY = 1f - .055f * progress
                        transformOrigin = TransformOrigin(0f, .5f)
                        // Keep the outline and elevation stable while the layer moves.
                        shadowElevation = if (raised) 10.dp.toPx() else 0f
                        shape = conversationShape
                        clip = raised
                        ambientShadowColor = Color(0xFF4B5260)
                        spotShadowColor = Color(0xFF4B5260)
                    }
                    .background(Color.White)
                    .testTag("conversation-card"),
            ) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        translationX = -size.width * workspaceMotion.value.coerceIn(0f, 1f)
                    },
                ) {
                    Column(
                        Modifier.fillMaxSize()
                            .inactiveIf(drawerOpen || workspaceOpen)
                            .windowInsetsPadding(
                                if (!showingSettings)
                                    WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)
                                else WindowInsets.systemBars.union(WindowInsets.displayCutout),
                            )
                            .imePadding(),
                    ) {
                        if (showingSettings)
                            SettingsScreen(
                                onCreatePlugin = {
                                    state.addSession(state.current.projectId).apply {
                                        creatorMode = true
                                        phoneTools = false
                                        fileTools = false
                                        terminalEnabled = false
                                    }
                                    state.appMode = "work"
                                    state.persist()
                                    retainedSettings = false
                                    settings = false
                                    drawerOpen = false
                                },
                            ) {
                                retainedSettings = true
                                settings = false
                                drawerOpen = true
                            }
                        else
                            sceneStates.SaveableStateProvider(
                                if (official) "official" else "work-${state.selectedId}",
                            ) {
                                if (official) OfficialChatScreen(cardAside)
                                else ConversationScreen(cardAside)
                            }
                    }
                    ReasoningBubble()
                }
                if (!showingSettings || workspaceChrome) {
                    val title by
                        remember(official) {
                            derivedStateOf {
                                if (official) OfficialChatNavigation.snapshot.title
                                else state.current.headerTitle
                            }
                        }
                    ConversationTitleBar(
                        title = title,
                        navigationLabel =
                            when {
                                workspaceOpen -> "返回会话"
                                official -> "官方会话列表"
                                else -> "会话列表"
                            },
                        onNavigate = {
                            if (workspaceOpen) WorkspaceState.hide() else drawerOpen = true
                        },
                        workspaceMotion = workspaceMotion,
                        workspaceOpen = workspaceOpen,
                        fontWeight = if (official) null else FontWeight.Medium,
                        modifier = Modifier.inactiveIf(drawerOpen),
                    )
                }
                if (drawerOpen)
                    Box(
                        Modifier.fillMaxSize()
                            .clickable(
                                interactionSource =
                                    remember {
                                        androidx.compose.foundation.interaction
                                            .MutableInteractionSource()
                                    },
                                indication = null,
                            ) {
                                retainedSettings = false
                                drawerOpen = false
                            }
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures { change, drag ->
                                    if (drag < -8f) {
                                        change.consume()
                                        drawerOpen = false
                                    }
                                }
                            },
                    )
                else if (!settings && !workspaceOpen)
                    Box(
                        Modifier.align(Alignment.CenterStart)
                            .width(if (ExperienceSettings.taskNavigation) 8.dp else 18.dp)
                            .fillMaxHeight()
                            .pointerInput(Unit) {
                                var dragged = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { dragged = 0f },
                                    onHorizontalDrag = { change, amount ->
                                        dragged += amount
                                        if (dragged > 30.dp.toPx()) {
                                            change.consume()
                                            drawerOpen = true
                                        }
                                    },
                                )
                            },
                    )
                SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp))
            }
            ConversationWorkspace(workspaceMotion)
            val pending = state.running?.takeIf { it.question.isNotBlank() }
            if (
                pending != null &&
                    (workspaceOpen ||
                        showingSettings ||
                        drawerOpen ||
                        state.selectedId != pending.sessionId ||
                        (state.appMode == "chat" && OfficialChatNavigation.accessConfirmed))
            )
                QuestionCard(
                    pending,
                    Modifier.align(Alignment.BottomCenter)
                        .widthIn(max = 580.dp)
                        .navigationBarsPadding()
                        .imePadding(),
                )
        }
        OfficialLoginIsland()
        ChangesPanel()
        ReasoningIsland()
        FilePreview()
        if (state.showAccessibilityInfo)
            WhaleSheet("允许助手查看和操作界面", close = { state.showAccessibilityInfo = false }) {
                Text(
                    "仅在你启动任务期间，助手会读取屏幕文字和截图，并通过 DeepSeek API 理解界面；可执行点击、滑动和文字输入。\n\n任务数据会发送至 DeepSeek，可能包含屏幕上的个人信息。你可以随时在应用或通知栏停止任务，并在系统设置中撤销权限。\n\n继续后，请在系统设置中手动启用“DSHA 手机助手”。",
                    fontSize = 13.sp,
                    color = WhaleMuted,
                )
                Row(Modifier.align(Alignment.End).padding(top = 16.dp)) {
                    TextButton(onClick = { state.showAccessibilityInfo = false }) { Text("暂不开启") }
                    TextButton(
                        onClick = {
                            state.showAccessibilityInfo = false
                            MainActivity.current?.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS,
                                ),
                            )
                        },
                    ) {
                        Text("了解并打开设置")
                    }
                }
            }
    }
}
