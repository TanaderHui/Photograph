package com.shiguang.photograph

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun MessagesScreen(state: StudioState, model: StudioViewModel, signIn: () -> Unit, openOrder: (String) -> Unit) {
    PageTitle("消息")
    val account = state.account
    if (account == null) {
        EmptyPanel("登录后查看消息", actionLabel = "去登录", action = signIn)
        return
    }

    var showOrderHistory by rememberSaveable(account.id) { mutableStateOf(false) }
    Text("订单通知", style = MaterialTheme.typography.titleMedium)
    val newest = state.notices.firstOrNull()
    if (newest == null) {
        if (!state.busy) EmptyPanel("暂无订单通知")
    } else {
        val historyAction = if (showOrderHistory) "收起历史通知" else "查看历史通知"
        AccountRow(eventLabel(newest.eventType), "${formatFeedTime(newest.createdAt)} · $historyAction") {
            showOrderHistory = !showOrderHistory
        }
        if (showOrderHistory) {
            TextButton(onClick = { openOrder(newest.bookingId) }) { Text("查看最新通知的订单") }
            state.notices.drop(1).forEach { notice ->
                AccountRow(eventLabel(notice.eventType), "${formatFeedTime(notice.createdAt)} · 查看订单") {
                    openOrder(notice.bookingId)
                }
            }
            if (state.noticeCursor != null) {
                TextButton(enabled = !state.busy, onClick = model::moreNotices) { Text("加载更早通知") }
            }
        }
    }

    Text("私聊", style = MaterialTheme.typography.titleMedium)
    if (state.conversations.isEmpty() && !state.busy) {
        EmptyPanel("暂无会话", "可从摄影师主页或订单详情发起私聊。")
    }
    state.conversations.forEach { conversation ->
        ConversationRow(conversation) { model.openChat(conversation) }
    }
    if (state.conversationCursor != null) {
        TextButton(enabled = !state.busy, onClick = model::moreConversations) { Text("更多会话") }
    }
    TextButton(enabled = !state.busy, onClick = model::refreshMessages) { Text("刷新消息") }
}

@Composable
private fun ConversationRow(conversation: Conversation, open: () -> Unit) {
    Surface(onClick = open, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            AuthorAvatar(conversation.peerName)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(conversation.peerName, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(conversation.lastText.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(formatFeedTime(conversation.updatedAt), style = MaterialTheme.typography.labelSmall)
                if (conversation.unreadCount > 0) {
                    val count = if (conversation.unreadCount > 99) "99+" else conversation.unreadCount.toString()
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text("$count 条未读", Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChatScreen(thread: ChatThread, accountId: String, busy: Boolean, model: StudioViewModel) {
    val conversation = thread.conversation
    var draft by rememberSaveable(conversation.id) { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(thread.sent?.clientMessageId) {
        val sent = thread.sent ?: return@LaunchedEffect
        if (draft.trim() == sent.text) draft = ""
        list.scrollToItem(0)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            PageBack("返回消息", model::closeChat)
            TextButton(enabled = !busy, onClick = model::refreshChat) { Text("刷新聊天") }
        }
        Text(conversation.peerName, style = MaterialTheme.typography.headlineSmall)
        if (thread.messages.isEmpty() && !busy && thread.syncedThrough != null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("还没有聊天消息", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(thread.messages, key = ChatMessage::seq) { message ->
                    ChatBubble(message, mine = accountId == message.senderId)
                }
                if (thread.olderCursor != null) {
                    item {
                        TextButton(enabled = !busy, onClick = model::olderChat) { Text("加载更早消息") }
                    }
                }
            }
        }
        if (conversation.peerClosed) {
            Text("对方账号已注销")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(draft, { draft = it.take(2000) }, Modifier.weight(1f), enabled = !busy,
                    label = { Text("输入消息") }, maxLines = 4)
                Button(enabled = !busy && draft.isNotBlank(), onClick = { model.sendChat(draft) }) {
                    Text("发送")
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, mine: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Surface(shape = RoundedCornerShape(16.dp), color = if (mine) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface, modifier = Modifier.widthIn(max = 300.dp)) {
            Text(message.text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
        Text(formatFeedTime(message.createdAt), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
