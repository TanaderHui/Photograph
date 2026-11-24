package com.shiguang.photograph

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive

class MainActivity : ComponentActivity() {
    private val styles by viewModels<StyleViewModel>()
    private val studio by viewModels<StudioViewModel>()
    private val appUpdates by viewModels<AppUpdateViewModel>()
    private var openUpdateFromNotification by mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == AppUpdateAlerts.OPEN_UPDATES) openUpdateFromNotification = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openUpdateFromNotification = intent.action == AppUpdateAlerts.OPEN_UPDATES
        val preferences = getSharedPreferences("privacy", MODE_PRIVATE)
        val policyUrl = BuildConfig.PRIVACY_URL
        setContent {
            var accepted by remember {
                mutableStateOf(policyUrl.isBlank() || preferences.getString("accepted_policy", null) == policyUrl)
            }
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF355B4E),
                secondary = Color(0xFFA34332),
                secondaryContainer = Color(0xFFDCE9DF),
                onSecondaryContainer = Color(0xFF244435),
                background = Color(0xFFF8F7F3),
                onBackground = Color(0xFF142B26),
                surface = Color.White,
                onSurface = Color(0xFF142B26),
                surfaceVariant = Color(0xFFE9EDE6),
                onSurfaceVariant = Color(0xFF455850),
                outline = Color(0xFF6F7C72),
                outlineVariant = Color(0xFFDCE2D9),
            )) {
                if (accepted) {
                    val styleState by styles.state.collectAsStateWithLifecycle()
                    val updateState by appUpdates.state.collectAsStateWithLifecycle()
                    PhotographScreen(styleState, styles::load, studio, updateState, appUpdates,
                        openUpdateFromNotification, {
                            openUpdateFromNotification = false
                            intent.action = Intent.ACTION_MAIN
                        })
                    AppUpdateDialog(updateState, appUpdates)
                } else {
                    val uriHandler = LocalUriHandler.current
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("请阅读隐私政策") },
                        text = {
                            Column {
                                Text("手机号用于验证码登录。定位仅在你主动使用定位城市时调用，也可以手动选择城市。")
                                TextButton(onClick = { uriHandler.openUri(policyUrl) }) { Text("查看隐私政策") }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                preferences.edit().putString("accepted_policy", policyUrl).apply()
                                accepted = true
                            }) { Text("同意并继续") }
                        },
                        dismissButton = {
                            TextButton(onClick = { finish() }) { Text("不同意并退出") }
                        },
                    )
                }
            }
        }
    }
}

private enum class Screen(val title: String, val icon: ImageVector) {
    Home("首页", Icons.Filled.Home),
    Community("社区", Icons.Filled.Favorite),
    Messages("消息", Icons.Filled.Email),
    Orders("约拍助手", Icons.Filled.List),
    Mine("我的", Icons.Filled.Person),
}

@Composable
private fun PhotographScreen(styles: StylesState, retryStyles: () -> Unit, model: StudioViewModel,
    updateState: AppUpdateState, appUpdates: AppUpdateViewModel, openUpdate: Boolean, consumeUpdate: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val works by model.works.collectAsStateWithLifecycle()
    val posts by model.posts.collectAsStateWithLifecycle()
    val published by model.published.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Screen.Home) }
    LaunchedEffect(tab) {
        if (tab == Screen.Community && !BuildConfig.COMMUNITY_ENABLED) tab = Screen.Home
    }
    var showWorkspace by rememberSaveable { mutableStateOf(false) }
    var myPosts by rememberSaveable { mutableStateOf(false) }
    var pendingNotice by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingChat by remember { mutableStateOf<ChatOpen?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(lifecycle) {
        AppUpdateAlerts.schedule(context)
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                appUpdates.check()
                delay(5 * 60_000L)
            }
        }
    }
    LaunchedEffect(openUpdate) {
        if (openUpdate) {
            tab = Screen.Mine
            showWorkspace = false
            appUpdates.open()
            consumeUpdate()
        }
    }
    var signInReturn by rememberSaveable { mutableStateOf<Screen?>(null) }
    val saveable = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    val accountId = state.account?.id
    val assistantTitle = if (accountId != null && state.studio?.approvalStatus == "APPROVED") {
        "摄影师助手"
    } else {
        Screen.Orders.title
    }

    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(accountId) {
        val destination = signInReturn
        if (accountId != null && destination != null && tab == Screen.Mine) {
            signInReturn = null
            showWorkspace = false
            tab = destination
        }
    }
    LaunchedEffect(tab, accountId, state.chat?.conversation?.id) {
        if (accountId != null && (tab == Screen.Messages || tab == Screen.Orders)) {
            snapshotFlow { state.busy }.first { !it }
            when (tab) {
                Screen.Messages -> if (pendingChat == null && state.chat == null) model.refreshMessages()
                Screen.Orders -> if (pendingNotice == null && state.selectedBooking == null) model.loadBookings()
                else -> Unit
            }
        }
    }
    LaunchedEffect(tab, accountId, pendingChat, state.busy) {
        val request = pendingChat
        if (tab == Screen.Messages && accountId != null && request != null && !state.busy) {
            pendingChat = null
            model.openChat(request)
        }
    }
    LaunchedEffect(tab, accountId, lifecycle) {
        if (tab == Screen.Messages && accountId != null) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    delay(5_000)
                    model.syncMessages()
                }
            }
        }
    }
    LaunchedEffect(tab, pendingNotice, state.busy) {
        val id = pendingNotice
        if (tab == Screen.Orders && id != null && !state.busy) {
            pendingNotice = null
            model.openBooking(id)
        }
    }

    BackHandler(enabled = tab != Screen.Home || state.selected != null) {
        when {
            tab == Screen.Home && state.selected != null -> model.closePhotographer()
            tab == Screen.Orders && state.selectedBooking != null -> model.closeBooking()
            tab == Screen.Messages && state.chat != null -> model.closeChat()
            tab == Screen.Mine && showWorkspace -> {
                showWorkspace = false
                signInReturn = null
                pendingChat = null
            }
            tab == Screen.Community && myPosts -> {
                myPosts = false
                tab = Screen.Mine
            }
            else -> tab = Screen.Home
        }
    }

    fun signIn() {
        signInReturn = tab.takeIf { accountId == null && it != Screen.Mine }
        showWorkspace = true
        tab = Screen.Mine
    }

    fun openChat(request: ChatOpen) {
        pendingChat = request
        if (accountId == null) {
            signIn()
            signInReturn = Screen.Messages
        } else {
            tab = Screen.Messages
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                Screen.entries.filter { BuildConfig.COMMUNITY_ENABLED || it != Screen.Community }.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = {
                            if (item != Screen.Mine) {
                                signInReturn = null
                                pendingChat = null
                            }
                            if (tab == Screen.Messages && item == Screen.Messages) model.closeChat()
                            if (item == Screen.Community) myPosts = false
                            if (item == Screen.Mine && tab != Screen.Mine) showWorkspace = false
                            tab = item
                        },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(if (item == Screen.Orders) assistantTitle else item.title) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { insets ->
        saveable.SaveableStateProvider(tab.name) {
            Column(Modifier.fillMaxSize().padding(insets).imePadding()) {
                if (state.busy && tab != Screen.Home) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (tab) {
                    Screen.Home -> if (state.selected != null) {
                        PaddedPage { PhotographerDetail(state, model, ::signIn) { openChat(ChatOpen(photographerId = it)) } }
                    } else {
                        HomeFeed(styles, state, works, model, ::signIn)
                    }
                    Screen.Community -> CommunityFeed(state, posts, published, model, myPosts, ::signIn) {
                        myPosts = false
                        tab = Screen.Mine
                    }
                    Screen.Messages -> {
                        val thread = state.chat
                        if (thread != null && accountId != null) {
                            ChatScreen(thread, accountId, state.busy, model)
                        } else {
                            PaddedPage {
                                MessagesScreen(state, model, ::signIn) { id ->
                                    pendingNotice = id
                                    tab = Screen.Orders
                                }
                            }
                        }
                    }
                    Screen.Orders -> PaddedPage {
                        BookingScreen(state, assistantTitle, model, ::signIn) { openChat(ChatOpen(bookingId = it)) }
                    }
                    Screen.Mine -> PaddedPage {
                        if (showWorkspace) {
                            PageBack("我的") {
                                showWorkspace = false
                                signInReturn = null
                                pendingChat = null
                            }
                            WorkspaceScreen(state, styles, retryStyles, model)
                        } else {
                            ProfileScreen(state, assistantTitle, ::signIn, {
                                myPosts = true
                                tab = Screen.Community
                            }, { tab = Screen.Orders }, { tab = Screen.Messages }, model::closeAccount,
                                updateState.available?.versionName, appUpdates::open)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaddedPage(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
}

