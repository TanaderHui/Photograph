package com.shiguang.photograph

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CardShape = RoundedCornerShape(20.dp)
private val DayFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm")

@Composable
internal fun HomeFeed(styles: StylesState, studio: StudioState, page: FeedPage<PublicWork>,
    model: StudioViewModel, signIn: () -> Unit) {
    val context = LocalContext.current
    val photographers by model.photographers.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf("discover") }
    var city by rememberSaveable { mutableStateOf("") }
    var style by rememberSaveable { mutableStateOf("") }
    var filterCity by rememberSaveable { mutableStateOf(false) }
    var cityDialog by rememberSaveable { mutableStateOf(false) }
    var locationRequest by rememberSaveable { mutableStateOf(0) }
    var locating by rememberSaveable { mutableStateOf(false) }
    var cityFeedback by rememberSaveable { mutableStateOf<String?>(null) }
    var mapExpanded by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            locationRequest++
        } else {
            cityFeedback = "定位权限未开启，可手动选择城市。"
        }
    }
    val filter = FeedFilter(scope, query, city.takeIf { filterCity }.orEmpty(), style)
    val account = studio.account
    LaunchedEffect(filter, account?.id) {
        if (scope == "following") model.loadWorks(filter) else model.searchPhotographers(filter)
    }
    LaunchedEffect(locationRequest) {
        if (locationRequest == 0 || !hasCityPermission(context)) return@LaunchedEffect
        locating = true
        try {
            val result = locateCity(context)
            if (result == null) {
                cityFeedback = "暂时无法定位，可手动选择城市。"
            } else {
                city = result
                filterCity = true
                cityDialog = false
                cityFeedback = null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            cityFeedback = "暂时无法定位，可手动选择城市。"
        } finally {
            locating = false
        }
    }

    if (mapExpanded) {
        FullPhotoMap(city) { mapExpanded = false }
        return
    }

    Column(Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, end = 20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val hint = if (scope == "following") "搜索关注的作品" else "搜索摄影师、城市或风格"
            FeedSearch(input, { input = it.take(80) }, hint, Modifier.weight(1f)) { query = it }
            Surface(
                onClick = { mapExpanded = true },
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = Color.White,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.LocationOn, contentDescription = "展开地图", tint = Color(0xFFD62D32),
                        modifier = Modifier.size(28.dp))
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (BuildConfig.COMMUNITY_ENABLED) {
                item { FilterChip(selected = scope == "following", onClick = { scope = "following" }, label = { Text("关注") }) }
                item { FilterChip(selected = scope == "discover", onClick = { scope = "discover" }, label = { Text("约拍") }) }
            }
            item {
                FilterChip(selected = filterCity, onClick = { cityDialog = true },
                    label = { Text(city.ifBlank { "定位城市" }) },
                    leadingIcon = { Icon(Icons.Filled.LocationOn, null, Modifier.size(16.dp)) })
            }
            if (styles is StylesState.Loaded) {
                items(styles.styles, key = Style::id) { item ->
                    FilterChip(selected = style == item.id, onClick = { style = if (style == item.id) "" else item.id },
                        label = { Text(item.name) })
                }
            }
        }
    }
    val shown = page.takeIf { it.filter == filter }
    LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 18.dp)) {
        if (scope == "discover") {
            item { PageTitle("可预约摄影师") }
            val directory = photographers.takeIf { it.filter == filter }
            if (directory == null || directory.loading && directory.items.isEmpty()) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            } else {
                items(directory.items, key = SearchHit::id) { photographer ->
                    Surface(onClick = { model.select(photographer) }, enabled = !studio.busy, shape = CardShape,
                        color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            AuthorAvatar(photographer.nickname)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(photographer.nickname, style = MaterialTheme.typography.titleMedium)
                                Text(photographer.city, style = MaterialTheme.typography.bodySmall)
                                if (photographer.bio.isNotBlank()) Text(photographer.bio, maxLines = 2)
                                Text("查看套餐与档期", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                if (directory.error != null) {
                    item { EmptyPanel(directory.error, actionLabel = "重试",
                        action = { model.searchPhotographers(filter, append = directory.items.isNotEmpty()) }) }
                } else if (directory.items.isEmpty()) {
                    item { EmptyPanel("暂无可预约摄影师", "可试试其他城市、风格或关键词。") }
                } else if (directory.cursor != null) {
                    item { TextButton(enabled = !directory.loading, onClick = { model.searchPhotographers(filter, true) }) {
                        Text("更多摄影师")
                    } }
                }
            }
            return@LazyColumn
        }
        item { PageTitle("关注的摄影作品") }
        when {
            scope == "following" && account == null -> item {
                EmptyPanel("登录后查看关注的作品", "关注摄影师后，他们发布的作品会出现在这里。", "去登录", signIn)
            }
            shown == null || shown.loading && shown.items.isEmpty() -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            else -> {
                items(shown.items, key = PublicWork::id) { work ->
                    WorkCard(work, studio, startBooking = {
                        model.select(SearchHit(work.photographerId, work.nickname, work.city, work.bio, emptyList()))
                    }, onFollow = { if (account == null) signIn() else model.toggleFollow(work.authorId, work.following) })
                }
                if (shown.error != null) item {
                    EmptyPanel(shown.error, actionLabel = "重试", action = { model.loadWorks(filter, shown.items.isNotEmpty()) })
                } else if (shown.items.isEmpty()) item {
                    val title = if (scope == "following") "关注的摄影师还没有发布作品" else "暂无符合条件的作品"
                    EmptyPanel(title, if (filterCity || style.isNotEmpty()) "可调整城市或风格后再查看。" else null)
                }
                if (shown.cursor != null && shown.error == null) item {
                    TextButton(enabled = !shown.loading, onClick = { model.loadWorks(filter, true) }) { Text("加载更多作品") }
                }
            }
        }

    }
    if (cityDialog) {
        CityPicker(city, locating, cityFeedback, close = { cityDialog = false }, locate = {
            cityFeedback = null
            if (hasCityPermission(context)) locationRequest++ else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }, select = {
            city = it
            filterCity = it.isNotBlank()
            cityFeedback = null
            cityDialog = false
        })
    }
}

@Composable
private fun WorkCard(work: PublicWork, state: StudioState, startBooking: () -> Unit, onFollow: () -> Unit) {
    Card(shape = CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()) {
        Surface(onClick = startBooking, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            AsyncImage(model = work.thumbnailUrl, contentDescription = work.title, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(1.45f).background(MaterialTheme.colorScheme.surfaceVariant))
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(work.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (work.caption.isNotBlank()) Text(work.caption)
            Text(listOfNotNull(work.city.takeIf(String::isNotBlank), work.styleName).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AuthorAvatar(work.nickname)
                TextButton(onClick = startBooking, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text(work.nickname) }
                if (work.authorId != state.account?.id) {
                    TextButton(enabled = !state.busy, onClick = onFollow) { Text(if (work.following) "已关注" else "关注") }
                }
            }
        }
    }
}

@Composable
private fun CityPicker(current: String, locating: Boolean, feedback: String?, close: () -> Unit,
    locate: () -> Unit, select: (String) -> Unit) {
    var input by rememberSaveable { mutableStateOf(current) }
    AlertDialog(onDismissRequest = close, title = { Text("选择城市") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(enabled = !locating, onClick = locate) { Text(if (locating) "定位中…" else "使用当前位置") }
            feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(input, { input = it.take(100) }, label = { Text("输入城市") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("北京", "上海", "纽约").forEach { city -> TextButton(onClick = { select(city) }) { Text(city) } }
            }
        }
    }, confirmButton = {
        TextButton(enabled = input.isNotBlank(), onClick = { select(input.trim().removeSuffix("市")) }) { Text("确定") }
    }, dismissButton = { TextButton(onClick = { select("") }) { Text("不限城市") } })
}

@Composable
internal fun CommunityFeed(state: StudioState, page: FeedPage<CommunityPost>, published: String?, model: StudioViewModel,
    mine: Boolean, signIn: () -> Unit, back: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf("discover") }
    var composing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingComposer by rememberSaveable { mutableStateOf(false) }
    val account = state.account
    val filter = FeedFilter(if (mine) "mine" else scope, query)
    LaunchedEffect(filter, account?.id) {
        model.loadPosts(filter)
        if (account != null && pendingComposer) {
            pendingComposer = false
            composing = true
        }
    }
    LaunchedEffect(published) {
        if (published != null) {
            draft = ""
            composing = false
            input = ""
            query = ""
            scope = "discover"
            model.loadPosts(FeedFilter(if (mine) "mine" else "discover"))
            model.clearPublished()
        }
    }
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, end = 20.dp)) {
        if (mine) PageBack("返回我的") { back() }
        FeedSearch(input, { input = it }, if (mine) "搜索我的帖子" else "搜索帖子或作者") { query = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (mine) {
                PageTitle("我的帖子")
            } else {
                FilterChip(selected = scope == "following", onClick = { scope = "following" }, label = { Text("关注") })
                FilterChip(selected = scope == "discover", onClick = { scope = "discover" }, label = { Text("推荐") })
            }
            Spacer(Modifier.weight(1f))
            FilledIconButton(onClick = {
                if (account == null) {
                    pendingComposer = true
                    signIn()
                } else {
                    composing = true
                }
            }) { Icon(Icons.Filled.Add, contentDescription = "发布帖子") }
        }
    }
    val shown = page.takeIf { it.filter == filter }
    LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 18.dp)) {
        when {
            filter.scope != "discover" && account == null -> item {
                EmptyPanel("登录后查看帖子", actionLabel = "去登录", action = signIn)
            }
            shown == null || shown.loading && shown.items.isEmpty() -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            else -> {
                items(shown.items, key = CommunityPost::id) { post ->
                    PostCard(post, state, onFollow = {
                        if (account == null) signIn() else model.toggleFollow(post.authorId, post.following)
                    }, onDelete = { deleting = post.id })
                }
                if (shown.error != null) item {
                    EmptyPanel(shown.error, actionLabel = "重试", action = { model.loadPosts(filter, shown.items.isNotEmpty()) })
                } else if (shown.items.isEmpty()) item {
                    val title = when {
                        query.isNotBlank() -> "没有找到相关帖子"
                        mine -> "你还没有发布帖子"
                        scope == "following" -> "关注的人还没有发布帖子"
                        else -> "社区还没有帖子"
                    }
                    EmptyPanel(title, if (scope == "discover" && query.isBlank()) "点右上方加号，分享你的摄影见闻。" else null)
                }
                if (shown.cursor != null && shown.error == null) item {
                    TextButton(enabled = !shown.loading, onClick = { model.loadPosts(filter, true) }) { Text("加载更多帖子") }
                }
            }
        }
    }
    if (composing && account != null) {
        AlertDialog(onDismissRequest = { if (!state.busy) composing = false }, title = { Text("发布帖子") },
            text = {
                OutlinedTextField(draft, { draft = it.take(2000) }, label = { Text("正文") },
                    placeholder = { Text("分享拍摄见闻、心得或问题") }, minLines = 4, maxLines = 8,
                    supportingText = { Text("${draft.length}/2000") }, modifier = Modifier.fillMaxWidth())
            }, confirmButton = {
                Button(enabled = !state.busy && draft.isNotBlank(), onClick = { model.publishPost(draft) }) { Text("发布") }
            }, dismissButton = { TextButton(enabled = !state.busy, onClick = { composing = false }) { Text("取消") } })
    }
    deleting?.let { id ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这条帖子？") },
            text = { Text("删除后，这条帖子将从社区中移除。") },
            confirmButton = { TextButton(enabled = !state.busy, onClick = { deleting = null; model.deletePost(id) }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("保留") } })
    }
}

@Composable
private fun PostCard(post: CommunityPost, state: StudioState, onFollow: () -> Unit, onDelete: () -> Unit) {
    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AuthorAvatar(post.nickname)
                Column(Modifier.weight(1f)) {
                    Text(post.nickname, style = MaterialTheme.typography.titleMedium)
                    Text(formatFeedTime(post.createdAt), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (post.authorId == state.account?.id) {
                    TextButton(enabled = !state.busy, onClick = onDelete) { Text("删除") }
                } else {
                    TextButton(enabled = !state.busy, onClick = onFollow) { Text(if (post.following) "已关注" else "关注") }
                }
            }
            Text(post.body, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
internal fun FeedSearch(input: String, update: (String) -> Unit, placeholder: String,
    modifier: Modifier = Modifier, submit: (String) -> Unit) {
    val focus = LocalFocusManager.current
    TextField(value = input, onValueChange = { update(it.take(80)); if (it.isEmpty()) submit("") },
        modifier = modifier.fillMaxWidth(), shape = CircleShape, singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = {
            IconButton(onClick = { finishSearch(focus, input, submit) }) { Icon(Icons.Filled.Search, "搜索") }
        },
        trailingIcon = if (input.isNotEmpty()) {
            { IconButton(onClick = { update(""); submit("") }) { Icon(Icons.Filled.Close, "清空搜索") } }
        } else null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { finishSearch(focus, input, submit) }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ))
}

private fun finishSearch(focus: FocusManager, text: String, submit: (String) -> Unit) {
    focus.clearFocus()
    submit(text.trim())
}

@Composable
internal fun PageTitle(title: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun PageBack(label: String, back: () -> Unit) {
    TextButton(onClick = back) { Icon(Icons.Filled.ArrowBack, null); Text(label, Modifier.padding(start = 8.dp)) }
}

@Composable
internal fun EmptyPanel(title: String, description: String? = null, actionLabel: String? = null, action: () -> Unit = {}) {
    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            description?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
            if (actionLabel != null) TextButton(onClick = action) { Text(actionLabel) }
        }
    }
}

@Composable
internal fun AuthorAvatar(nickname: String, modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        contentAlignment = Alignment.Center) {
        Text(nickname.trim().take(1).ifBlank { "拾" }, color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

internal fun formatFeedTime(raw: String): String = runCatching {
    OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault()).format(DayFormat)
}.getOrDefault(raw)

