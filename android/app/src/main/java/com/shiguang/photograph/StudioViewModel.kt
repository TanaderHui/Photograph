package com.shiguang.photograph

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.time.OffsetDateTime
import java.util.UUID

internal data class StudioState(
    val busy: Boolean = false,
    val message: String? = null,
    val challengePhone: String? = null,
    val sessionActive: Boolean = false,
    val account: Account? = null,
    val studio: StudioProfile? = null,
    val packages: List<ManagedPackage> = emptyList(),
    val packageCursor: String? = null,
    val selected: SearchHit? = null,
    val publicPackages: List<Listing> = emptyList(),
    val publicPackageCursor: String? = null,
    val publicPackagesLoaded: Boolean = false,
    val calendar: WorkCalendar? = null,
    val slots: AvailableSlots? = null,
    val bookings: List<Booking> = emptyList(),
    val bookingCursor: String? = null,
    val selectedBooking: Booking? = null,
    val notices: List<BookingNotice> = emptyList(),
    val noticeCursor: String? = null,
    val conversations: List<Conversation> = emptyList(),
    val conversationCursor: String? = null,
    val inboxExtended: Boolean = false,
    val chat: ChatThread? = null,
    val lastBookingRequest: BookingRequest? = null,
    val completedBookings: Boolean = false,
)

internal data class ChatThread(
    val conversation: Conversation,
    val messages: List<ChatMessage> = emptyList(),
    val olderCursor: Long? = null,
    val syncedThrough: Long? = null,
    val readThrough: Long = 0,
    val sent: ChatMessage? = null,
)

internal data class FeedFilter(val scope: String = "discover", val query: String = "", val city: String = "", val style: String = "")
internal data class FeedPage<T>(
    val filter: FeedFilter = FeedFilter(),
    val items: List<T> = emptyList(),
    val cursor: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

internal class StudioViewModel : ViewModel() {
    private val network = Network.api(StudioApi::class.java)
    private val mutableState = MutableStateFlow(StudioState())
    val state = mutableState.asStateFlow()
    private var tokens: Tokens? = null
    private var challengeId: String? = null
    private var pendingWrite: Pair<Any, String>? = null
    private var pendingChat: Pair<String, ChatSend>? = null
    private val refreshLock = Mutex()
    private val worksState = MutableStateFlow(FeedPage<PublicWork>())
    private val postsState = MutableStateFlow(FeedPage<CommunityPost>())
    val works = worksState.asStateFlow()
    val posts = postsState.asStateFlow()
    private val photographersState = MutableStateFlow(FeedPage<SearchHit>(loading = true))
    val photographers = photographersState.asStateFlow()
    private var photographerSearch: Job? = null
    private var worksJob: Job? = null
    private var postsJob: Job? = null
    private var postDraft: PostDraft? = null
    private val publishedState = MutableStateFlow<String?>(null)
    val published = publishedState.asStateFlow()

    fun sendCode(phone: String) = run {
        challengeId = sendCode(Phone(phone)).challengeId
        mutableState.update { it.copy(challengePhone = phone, message = "验证码已发出。") }
    }

    fun login(phone: String, code: String) = run {
        val id = challengeId?.takeIf { phone == state.value.challengePhone }
            ?: error("请先向此号码发送验证码。")
        tokens = login(Verification(id, code))
        challengeId = null
        mutableState.update { it.copy(challengePhone = null, sessionActive = true) }
        loadWorkspace()
    }

    fun reload() = run { loadWorkspace() }

    fun logout() = run {
        val response = authorized {
            val result = logout(it)
            if (!result.isSuccessful) throw HttpException(result)
            result
        }
        if (response.isSuccessful) clearAccount()
    }

    fun closeAccount() = run {
        authorized { token ->
            val result = closeAccount(token)
            if (!result.isSuccessful) throw HttpException(result)
        }
        clearAccount()
        mutableState.update { it.copy(message = "账号已注销。") }
    }

    fun saveAccount(edit: AccountEdit) = run {
        val account = authorized { updateProfile(it, edit) }
        mutableState.update { it.copy(account = account, message = "账号资料已保存。") }
    }

    fun saveStudio(edit: BioEdit) = run {
        val profile = authorized {
            if (state.value.studio?.approvalStatus == "APPROVED") updateBio(it, edit) else apply(it, edit)
        }
        mutableState.update {
            it.copy(studio = profile, message = if (profile.approvalStatus == "PENDING") "申请已提交。" else "资料已保存。")
        }
    }

    fun saveStyles(styles: Set<String>) = run {
        val profile = authorized { updateStyles(it, StyleEdit(styles)) }
        mutableState.update { it.copy(studio = profile, message = "风格已保存。") }
    }

    fun savePackage(id: String?, edit: PackageEdit) = run {
        val saved = authorized { if (id == null) createPackage(it, edit) else updatePackage(it, id, edit) }
        loadOwnPackages(false)
        mutableState.update { it.copy(message = "套餐「${saved.name}」已保存。") }
    }

    fun moreOwnPackages() = run { loadOwnPackages(true) }

    fun searchPhotographers(filter: FeedFilter, append: Boolean = false) {
        val previous = photographersState.value
        val cursor = if (append) previous.cursor?.takeIf { previous.filter == filter && !previous.loading } ?: return else null
        photographerSearch?.cancel()
        val items = if (append) previous.items else emptyList()
        photographersState.value = FeedPage(filter, items, cursor, loading = true)
        photographerSearch = viewModelScope.launch {
            try {
                val api = network ?: error("尚未配置服务地址。")
                val found = api.search(filter.query, filter.city, filter.style, true, cursor)
                photographersState.value = FeedPage(filter, items + found.items, found.nextCursor)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                photographersState.value = FeedPage(filter, items, cursor, error = "无法加载摄影师，请稍后重试。")
            }
        }
    }

    fun select(hit: SearchHit) = run {
        mutableState.update {
            it.copy(selected = hit, publicPackages = emptyList(), publicPackageCursor = null, publicPackagesLoaded = false)
        }
        loadPublicPackages(false)
    }

    fun morePublicPackages() = run { loadPublicPackages(true) }
    fun reloadPublicPackages() = run { loadPublicPackages(false) }

    fun loadCalendar() = run {
        val calendar = authorized { calendar(it) }
        mutableState.update { it.copy(calendar = calendar) }
    }

    fun saveCalendar(edit: CalendarEdit) = run {
        val calendar = authorized { updateCalendar(it, edit) }
        mutableState.update { it.copy(calendar = calendar, message = "工作区间已保存。") }
    }

    fun setCalendarVisibility(visibility: String) = run {
        val calendar = authorized { visibility(it, VisibilityEdit(visibility)) }
        mutableState.update { it.copy(calendar = calendar, message = "日历可见范围已更新。") }
    }

    fun clearSlots() { mutableState.update { it.copy(slots = null) } }

    fun loadSlots(photographer: String, packageId: String, date: String) = run {
        val slots = availability(photographer, packageId, date)
        mutableState.update { it.copy(slots = slots) }
    }

    fun createBooking(request: BookingRequest) = run {
        val booking = writeOnce(request) { key -> authorized { createBooking(it, key, request) } }
        mutableState.update {
            val bookings = if (it.completedBookings) it.bookings else listOf(booking) + it.bookings.filterNot { row -> row.id == booking.id }
            it.copy(message = "预约申请已提交，可在订单页查看。", lastBookingRequest = request,
                bookings = bookings)
        }
    }

    fun loadBookings(append: Boolean = false, completed: Boolean = state.value.completedBookings) = run {
        val cursor = if (append) state.value.bookingCursor ?: return@run else null
        if (!append && completed != state.value.completedBookings) {
            mutableState.update { it.copy(completedBookings = completed, bookings = emptyList(), bookingCursor = null) }
        }
        val page = authorized { bookings(it, cursor, completed) }
        mutableState.update { it.copy(bookings = if (append) it.bookings + page.items else page.items,
            bookingCursor = page.nextCursor, completedBookings = completed) }
    }

    fun selectBookingGroup(completed: Boolean) {
        mutableState.update { it.copy(completedBookings = completed, bookings = emptyList(), bookingCursor = null) }
    }

    fun refreshMessages() = run {
        loadNotices(false)
        loadConversations(false)
    }

    fun moreConversations() = run { loadConversations(true) }

    private suspend fun StudioApi.loadConversations(append: Boolean, preservePages: Boolean = false) {
        val previous = state.value
        val cursor = if (append) previous.conversationCursor ?: return else null
        val page = authorized { conversations(it, cursor) }
        mutableState.update { current ->
            if (current.account?.id != previous.account?.id) return@update current
            val retain = preservePages && current.inboxExtended && page.nextCursor != null
            val items = if (append || retain) (page.items + current.conversations).distinctBy(Conversation::id)
                .sortedWith(compareByDescending<Conversation> { OffsetDateTime.parse(it.updatedAt).toInstant() }
                    .thenByDescending { it.id }) else page.items
            current.copy(conversations = items, conversationCursor = if (retain) current.conversationCursor else page.nextCursor,
                inboxExtended = append || retain)
        }
    }

    fun openChat(request: ChatOpen) = run {
        val conversation = authorized { openConversation(it, request) }
        mutableState.update { it.copy(chat = ChatThread(conversation)) }
        loadChat(conversation.id)
    }

    fun openChat(conversation: Conversation) = run {
        mutableState.update { it.copy(chat = ChatThread(conversation)) }
        loadChat(conversation.id)
    }

    fun closeChat() {
        mutableState.update { it.copy(chat = null) }
    }

    fun refreshChat() = run {
        val chat = state.value.chat ?: return@run
        loadChat(chat.conversation.id)
    }

    fun olderChat() = run {
        val chat = state.value.chat ?: return@run
        val cursor = chat.olderCursor ?: return@run
        loadChat(chat.conversation.id, before = cursor)
    }

    private suspend fun StudioApi.loadChat(id: String, before: Long? = null) {
        val chat = state.value.chat?.takeIf { it.conversation.id == id } ?: return
        val after = chat.syncedThrough.takeIf { before == null }
        val page = authorized { chatMessages(it, id, before, after) }
        updateChat(id) { current ->
            val messages = (current.messages + page.items).distinctBy(ChatMessage::seq).sortedByDescending(ChatMessage::seq)
            val newest = page.items.maxOfOrNull(ChatMessage::seq) ?: 0
            current.copy(messages = messages,
                olderCursor = if (after == null) page.nextCursor?.toLongOrNull() else current.olderCursor,
                syncedThrough = if (before == null) maxOf(current.syncedThrough ?: 0, newest) else current.syncedThrough)
        }
        if (before == null) markChatRead(id)
    }

    private suspend fun StudioApi.markChatRead(id: String) {
        val chat = state.value.chat?.takeIf { it.conversation.id == id } ?: return
        val seq = chat.syncedThrough ?: return
        if (seq <= chat.readThrough) return
        authorized { readChat(it, id, ChatRead(seq)) }
        updateChat(id) { it.copy(readThrough = maxOf(it.readThrough, seq)) }
    }

    fun sendChat(text: String) = run {
        val chat = state.value.chat ?: return@run
        val id = chat.conversation.id
        val body = text.trim()
        if (body.isEmpty()) return@run
        val request = pendingChat?.takeIf { it.first == id && it.second.text == body }?.second
            ?: ChatSend(UUID.randomUUID().toString(), body).also { pendingChat = id to it }
        val sent = authorized { sendChatMessage(it, id, request) }
        pendingChat = null
        updateChat(id) { current ->
            current.copy(messages = (current.messages + sent).distinctBy(ChatMessage::seq).sortedByDescending(ChatMessage::seq),
                sent = sent)
        }
    }

    private fun updateChat(id: String, update: (ChatThread) -> ChatThread) {
        mutableState.update { state ->
            val thread = state.chat?.takeIf { it.conversation.id == id } ?: return@update state
            state.copy(chat = update(thread))
        }
    }

    suspend fun syncMessages() {
        val api = network ?: return
        val current = state.value
        if (current.busy || current.account == null) return
        try {
            val chat = current.chat
            if (chat == null) {
                api.loadConversations(false, preservePages = true)
            } else {
                api.loadChat(chat.conversation.id)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Foreground refresh still reports connection failures.
        }
    }

    fun moreNotices() = run { loadNotices(true) }

    private suspend fun StudioApi.loadNotices(append: Boolean) {
        val cursor = if (append) state.value.noticeCursor ?: return else null
        val notices = authorized { notices(it, cursor) }
        mutableState.update {
            it.copy(notices = if (append) it.notices + notices.items else notices.items, noticeCursor = notices.nextCursor)
        }
    }

    fun openBooking(id: String) = run {
        val booking = authorized { booking(it, id) }
        replaceBooking(booking)
    }

    fun closeBooking() {
        mutableState.update { it.copy(selectedBooking = null, message = null) }
    }

    fun bookingAction(booking: Booking, action: String, reason: String? = null) = run {
        val request = BookingAction(action, booking.version, reason)
        val updated = writeOnce(booking.id to request) { key -> authorized { bookingAction(it, booking.id, key, request) } }
        replaceBooking(updated)
    }

    fun reschedule(booking: Booking, startsAt: String) = run {
        val request = RescheduleRequest(booking.version, startsAt)
        writeOnce(booking.id to request) { key -> authorized { propose(it, booking.id, key, request) } }
        replaceBooking(authorized { booking(it, booking.id) })
    }

    fun decideReschedule(booking: Booking, reschedule: PendingReschedule, decision: String) = run {
        val request = RescheduleDecision(booking.version, decision)
        val updated = writeOnce(reschedule.id to request) { key ->
            authorized { decide(it, booking.id, reschedule.id, key, request) }
        }
        replaceBooking(updated)
    }

    private fun replaceBooking(updated: Booking) {
        mutableState.update { state ->
            val bookings = if ((updated.status == "COMPLETED") == state.completedBookings) {
                state.bookings.map { old -> if (old.id == updated.id) updated else old }
            } else {
                state.bookings.filterNot { it.id == updated.id }
            }
            state.copy(selectedBooking = updated, bookings = bookings)
        }
    }

    private suspend fun <T> writeOnce(request: Any, send: suspend (String) -> T): T {
        val key = pendingWrite?.takeIf { it.first == request }?.second ?: UUID.randomUUID().toString()
        pendingWrite = request to key
        return try {
            send(key).also { pendingWrite = null }
        } catch (error: HttpException) {
            if (error.code() in 400..499) pendingWrite = null
            throw error
        }
    }

    fun closePhotographer() {
        mutableState.update { it.copy(selected = null, message = null) }
    }

    fun loadWorks(filter: FeedFilter, append: Boolean = false) {
        worksJob?.cancel()
        worksJob = fetchFeed(worksState, filter, append) { token, cursor ->
            works(token, filter.scope, filter.query, filter.city, filter.style, cursor)
        }
    }

    fun loadPosts(filter: FeedFilter, append: Boolean = false) {
        postsJob?.cancel()
        postsJob = fetchFeed(postsState, filter, append) { token, cursor ->
            posts(token, filter.scope, filter.query, cursor)
        }
    }

    fun publishPost(body: String) = run {
        val text = body.trim()
        val draft = postDraft?.takeIf { it.body == text } ?: PostDraft(UUID.randomUUID().toString(), text)
        postDraft = draft
        val result = authorized { publish(it, draft) }
        postDraft = null
        publishedState.value = result.id
        mutableState.update { it.copy(message = "帖子已发布。") }
    }

    fun clearPublished() { publishedState.value = null }

    fun deletePost(id: String) = run {
        authorized {
            val response = removePost(it, id)
            if (!response.isSuccessful) throw HttpException(response)
        }
        loadPosts(postsState.value.filter)
    }

    fun toggleFollow(id: String, following: Boolean) = run {
        val result = authorized { if (following) unfollow(it, id) else follow(it, id) }
        worksState.update { page ->
            page.copy(items = page.items.map { item -> if (item.authorId == id) item.copy(following = result.following) else item }
                .filter { page.filter.scope != "following" || it.following })
        }
        postsState.update { page ->
            page.copy(items = page.items.map { item -> if (item.authorId == id) item.copy(following = result.following) else item }
                .filter { page.filter.scope != "following" || it.following })
        }
    }

    private fun <T> fetchFeed(destination: MutableStateFlow<FeedPage<T>>, filter: FeedFilter, append: Boolean,
        load: suspend StudioApi.(String?, String?) -> Page<T>): Job {
        val previous = destination.value
        val cursor = if (append && previous.filter == filter) previous.cursor else null
        val kept = if (cursor == null) emptyList() else previous.items
        destination.value = FeedPage(filter, kept, cursor, loading = true)
        return viewModelScope.launch {
            try {
                val api = network ?: error("尚未配置服务地址。")
                val page = if (tokens == null) api.load(null, cursor) else api.authorized { api.load(it, cursor) }
                destination.value = FeedPage(filter, kept + page.items, page.nextCursor)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                destination.value = FeedPage(filter, kept, cursor, error = "暂时无法加载，请重试。")
            }
        }
    }

    private suspend fun StudioApi.loadPublicPackages(append: Boolean) {
        val hit = state.value.selected ?: return
        val cursor = if (append) state.value.publicPackageCursor ?: return else null
        val page = publicPackages(hit.id, cursor)
        mutableState.update {
            it.copy(publicPackages = if (append) it.publicPackages + page.items else page.items,
                publicPackageCursor = page.nextCursor, publicPackagesLoaded = true)
        }
    }

    private suspend fun StudioApi.loadWorkspace() {
        val account = authorized { profile(it) }
        mutableState.update { it.copy(account = account) }
        val profile = try {
            authorized { photographer(it) }
        } catch (error: HttpException) {
            if (error.code() != 404) throw error
            null
        }
        mutableState.update { it.copy(studio = profile, packages = emptyList(), packageCursor = null) }
        if (profile?.approvalStatus == "APPROVED") loadOwnPackages(false)
    }

    private suspend fun StudioApi.loadOwnPackages(append: Boolean) {
        val cursor = if (append) state.value.packageCursor ?: return else null
        val page = authorized { ownPackages(it, cursor) }
        mutableState.update {
            it.copy(packages = if (append) it.packages + page.items else page.items, packageCursor = page.nextCursor)
        }
    }

    private suspend fun <T> StudioApi.authorized(request: suspend (String) -> T): T {
        val session = tokens ?: error("请先登录。")
        return try {
            request("Bearer ${session.accessToken}")
        } catch (error: HttpException) {
            if (error.code() != 401) throw error
            val renewed = refreshLock.withLock {
                val current = tokens ?: throw error
                if (current != session) {
                    current
                } else {
                    try {
                        refresh(Refresh(session.refreshToken)).also { if (tokens == session) tokens = it }
                    } catch (refreshError: HttpException) {
                        if (refreshError.code() == 401 && tokens == session) clearAccount()
                        throw refreshError
                    }
                }
            }
            request("Bearer ${renewed.accessToken}")
        }
    }

    private fun clearAccount() {
        tokens = null
        challengeId = null
        pendingWrite = null
        pendingChat = null
        postDraft = null
        worksJob?.cancel()
        postsJob?.cancel()
        worksState.value = FeedPage()
        postsState.value = FeedPage()
        mutableState.update {
            it.copy(account = null, challengePhone = null, sessionActive = false, studio = null,
                packages = emptyList(), packageCursor = null, calendar = null, selectedBooking = null,
                bookings = emptyList(), bookingCursor = null, notices = emptyList(), noticeCursor = null,
                lastBookingRequest = null, conversations = emptyList(), conversationCursor = null,
                inboxExtended = false, chat = null)
        }
    }

    private fun run(operation: suspend StudioApi.() -> Unit) {
        if (state.value.busy) return
        val api = network ?: kotlin.run {
            mutableState.update { it.copy(message = "尚未配置服务地址。") }
            return
        }
        mutableState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                api.operation()
            } catch (error: CancellationException) {
                throw error
            } catch (error: HttpException) {
                val message = runCatching {
                    JsonParser.parseString(error.response()?.errorBody()?.string()).asJsonObject.get("message").asString
                }.getOrDefault("请求失败（${error.code()}）。")
                mutableState.update { it.copy(message = message) }
            } catch (error: IllegalStateException) {
                mutableState.update { it.copy(message = error.message ?: "操作失败。") }
            } catch (_: Exception) {
                mutableState.update { it.copy(message = "暂时无法连接服务，请重试。") }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }
}
