package com.shiguang.photograph

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

internal object Network {
    private val retrofit = BuildConfig.API_BASE_URL.takeIf(String::isNotBlank)?.let {
        Retrofit.Builder().baseUrl(it).addConverterFactory(GsonConverterFactory.create()).build()
    }

    fun <T> api(type: Class<T>): T? = retrofit?.create(type)
}

internal interface StudioApi {
    @POST("api/v1/auth/otp/challenges")
    suspend fun sendCode(@Body phone: Phone): Challenge

    @POST("api/v1/auth/sessions")
    suspend fun login(@Body verification: Verification): Tokens

    @POST("api/v1/auth/sessions/refresh")
    suspend fun refresh(@Body refresh: Refresh): Tokens

    @POST("api/v1/auth/sessions/logout")
    suspend fun logout(@Header("Authorization") token: String): retrofit2.Response<Unit>

    @DELETE("api/v1/auth/account")
    suspend fun closeAccount(@Header("Authorization") token: String): retrofit2.Response<Unit>

    @GET("api/v1/me/profile")
    suspend fun profile(@Header("Authorization") token: String): Account

    @PATCH("api/v1/me/profile")
    suspend fun updateProfile(@Header("Authorization") token: String, @Body edit: AccountEdit): Account

    @GET("api/v1/me/photographer")
    suspend fun photographer(@Header("Authorization") token: String): StudioProfile

    @POST("api/v1/me/photographer/applications")
    suspend fun apply(@Header("Authorization") token: String, @Body edit: BioEdit): StudioProfile

    @PUT("api/v1/me/photographer")
    suspend fun updateBio(@Header("Authorization") token: String, @Body edit: BioEdit): StudioProfile

    @PUT("api/v1/me/photographer/styles")
    suspend fun updateStyles(@Header("Authorization") token: String, @Body edit: StyleEdit): StudioProfile

    @GET("api/v1/me/photographer/packages")
    suspend fun ownPackages(@Header("Authorization") token: String, @Query("cursor") cursor: String?): Page<ManagedPackage>

    @POST("api/v1/me/photographer/packages")
    suspend fun createPackage(@Header("Authorization") token: String, @Body edit: PackageEdit): ManagedPackage

    @PUT("api/v1/me/photographer/packages/{id}")
    suspend fun updatePackage(@Header("Authorization") token: String, @Path("id") id: String,
        @Body edit: PackageEdit): ManagedPackage

    @GET("api/v1/search/home")
    suspend fun search(@Query("q") query: String, @Query("city") city: String, @Query("style") style: String,
        @Query("bookable") bookable: Boolean, @Query("cursor") cursor: String?): Page<SearchHit>

    @GET("api/v1/photographers/{id}/packages")
    suspend fun publicPackages(@Path("id") id: String, @Query("cursor") cursor: String?): Page<Listing>

    @GET("api/v1/photographers/{id}/availability")
    suspend fun availability(@Path("id") id: String, @Query("packageId") packageId: String,
        @Query("date") date: String): AvailableSlots

    @GET("api/v1/me/photographer/calendar")
    suspend fun calendar(@Header("Authorization") token: String): WorkCalendar

    @PUT("api/v1/me/photographer/availability")
    suspend fun updateCalendar(@Header("Authorization") token: String, @Body edit: CalendarEdit): WorkCalendar

    @PUT("api/v1/me/photographer/calendar-visibility")
    suspend fun visibility(@Header("Authorization") token: String, @Body edit: VisibilityEdit): WorkCalendar

    @POST("api/v1/bookings")
    suspend fun createBooking(@Header("Authorization") token: String, @Header("Idempotency-Key") key: String,
        @Body request: BookingRequest): Booking

    @GET("api/v1/me/bookings")
    suspend fun bookings(@Header("Authorization") token: String, @Query("cursor") cursor: String?,
        @Query("completed") completed: Boolean): Page<Booking>

    @GET("api/v1/bookings/{id}")
    suspend fun booking(@Header("Authorization") token: String, @Path("id") id: String): Booking

    @POST("api/v1/bookings/{id}/actions")
    suspend fun bookingAction(@Header("Authorization") token: String, @Path("id") id: String,
        @Header("Idempotency-Key") key: String, @Body request: BookingAction): Booking

    @POST("api/v1/bookings/{id}/reschedules")
    suspend fun propose(@Header("Authorization") token: String, @Path("id") id: String,
        @Header("Idempotency-Key") key: String, @Body request: RescheduleRequest): PendingReschedule

    @POST("api/v1/bookings/{id}/reschedules/{rid}/decision")
    suspend fun decide(@Header("Authorization") token: String, @Path("id") id: String, @Path("rid") rid: String,
        @Header("Idempotency-Key") key: String, @Body request: RescheduleDecision): Booking

    @POST("api/v1/conversations")
    suspend fun openConversation(@Header("Authorization") token: String, @Body request: ChatOpen): Conversation

    @GET("api/v1/me/conversations")
    suspend fun conversations(@Header("Authorization") token: String, @Query("cursor") cursor: String?): Page<Conversation>

    @GET("api/v1/conversations/{id}/messages")
    suspend fun chatMessages(@Header("Authorization") token: String, @Path("id") id: String,
        @Query("beforeSeq") before: Long? = null, @Query("afterSeq") after: Long? = null): Page<ChatMessage>

    @POST("api/v1/conversations/{id}/messages")
    suspend fun sendChatMessage(@Header("Authorization") token: String, @Path("id") id: String,
        @Body request: ChatSend): ChatMessage

    @PUT("api/v1/conversations/{id}/read")
    suspend fun readChat(@Header("Authorization") token: String, @Path("id") id: String, @Body request: ChatRead)

    @GET("api/v1/me/notifications")
    suspend fun notices(@Header("Authorization") token: String, @Query("cursor") cursor: String?): Page<BookingNotice>

    @GET("api/v1/works")
    suspend fun works(@Header("Authorization") token: String?, @Query("scope") scope: String, @Query("q") query: String,
        @Query("city") city: String, @Query("style") style: String, @Query("cursor") cursor: String?): Page<PublicWork>

    @GET("api/v1/places")
    suspend fun places(@Query("q") query: String = "", @Query("city") city: String = ""): PlaceItems<PhotoPlace>

    @GET("api/v1/places/{id}")
    suspend fun place(@Path("id") id: String): PhotoPlace

    @GET("api/v1/map/markers")
    suspend fun mapMarkers(@Query("bbox") bounds: String, @Query("zoom") zoom: Double,
        @Query("category") category: String): PlaceItems<PhotoMarker>

    @GET("api/v1/feed")
    suspend fun posts(@Header("Authorization") token: String?, @Query("scope") scope: String, @Query("q") query: String,
        @Query("cursor") cursor: String?): Page<CommunityPost>

    @POST("api/v1/posts")
    suspend fun publish(@Header("Authorization") token: String, @Body post: PostDraft): CommunityPost

    @DELETE("api/v1/me/posts/{id}")
    suspend fun removePost(@Header("Authorization") token: String, @Path("id") id: String): retrofit2.Response<Unit>

    @PUT("api/v1/me/following/{id}")
    suspend fun follow(@Header("Authorization") token: String, @Path("id") id: String): FollowState

    @DELETE("api/v1/me/following/{id}")
    suspend fun unfollow(@Header("Authorization") token: String, @Path("id") id: String): FollowState
}

internal data class Phone(val phone: String)
internal data class Challenge(val challengeId: String)
internal data class Verification(val challengeId: String, val code: String)
internal data class Refresh(val refreshToken: String)
internal data class Tokens(val accessToken: String, val refreshToken: String)
internal data class Account(val id: String, val nickname: String, val acceptsPaidBookings: Boolean, val acceptsTfpBookings: Boolean)
internal data class AccountEdit(val nickname: String, val acceptsPaidBookings: Boolean, val acceptsTfpBookings: Boolean)
internal data class StudioProfile(val id: String, val city: String, val bio: String, val approvalStatus: String, val styleIds: List<String>)
internal data class BioEdit(val city: String, val bio: String)
internal data class StyleEdit(val styleIds: Set<String>)
internal data class Page<T>(val items: List<T>, val nextCursor: String?)
internal data class SearchHit(val id: String, val nickname: String, val city: String, val bio: String, val matchedFields: List<String>)
internal data class Listing(val id: String, val name: String, val durationMinutes: Int, val retouchedCount: Int,
    val deliveryDays: Int, val priceMinor: Int, val version: Int)
internal data class ManagedPackage(val id: String, val name: String, val durationMinutes: Int, val retouchedCount: Int,
    val deliveryDays: Int, val priceMinor: Int, val listed: Boolean, val version: Int)
internal data class PackageEdit(val name: String, val durationMinutes: Int, val retouchedCount: Int, val deliveryDays: Int,
    val priceMinor: Int, val listed: Boolean, val expectedVersion: Int?)
internal data class WorkWindow(val startsAt: String, val endsAt: String)
internal data class WorkCalendar(val zoneId: String, val visibility: String, val bufferMinutes: Int,
    val windows: List<WorkWindow>, val busy: List<WorkWindow>)
internal data class CalendarEdit(val zoneId: String, val bufferMinutes: Int, val windows: List<WorkWindow>)
internal data class VisibilityEdit(val visibility: String)
internal data class AvailableSlots(val visibility: String, val zoneId: String?, val slots: List<WorkWindow>)
internal data class BookingRequest(val packageId: String, val packageVersion: Int, val startsAt: String,
    val meetingArea: String, val note: String, val engagementType: String)
internal data class BookingAction(val action: String, val expectedVersion: Int, val reasonCode: String? = null)
internal data class RescheduleRequest(val expectedVersion: Int, val startsAt: String)
internal data class RescheduleDecision(val expectedVersion: Int, val decision: String)
internal data class PendingReschedule(val id: String, val proposedBy: String, val startsAt: String)
internal data class PackageSnapshot(val name: String, val priceMinor: Int, val durationMinutes: Int,
    val retouchedCount: Int, val deliveryDays: Int)
internal data class Booking(val id: String, val customerId: String, val status: String, val version: Int,
    val startsAt: String, val endsAt: String, val expiresAt: String, val zoneId: String, val packageSnapshot: PackageSnapshot,
    val engagementType: String, val meetingArea: String, val note: String, val customerConfirmed: Boolean,
    val photographerConfirmed: Boolean, val deliveredAt: String?, val completedAt: String?, val pendingReschedule: PendingReschedule?)
internal data class ChatOpen(val photographerId: String? = null, val bookingId: String? = null)
internal data class Conversation(val id: String, val peerId: String, val peerName: String, val peerClosed: Boolean,
    val updatedAt: String, val lastText: String?, val unreadCount: Long)
internal data class ChatMessage(val seq: Long, val senderId: String, val clientMessageId: String,
    val text: String, val createdAt: String)
internal data class ChatSend(val clientMessageId: String, val text: String)
internal data class ChatRead(val seq: Long)
internal data class BookingNotice(val id: String, val bookingId: String, val eventType: String, val createdAt: String)
internal data class PublicWork(val id: String, val photographerId: String, val authorId: String, val nickname: String,
    val city: String, val bio: String, val title: String, val caption: String, val thumbnailUrl: String,
    val styleName: String?, val following: Boolean)
internal data class CommunityPost(val id: String, val authorId: String, val nickname: String, val body: String,
    val createdAt: String, val following: Boolean)
internal data class PostDraft(val clientPostId: String, val body: String)
internal data class FollowState(val following: Boolean)
internal data class PlaceItems<T>(val items: List<T>)
internal data class PhotoPlace(val id: String, val name: String, val city: String, val category: String,
    val description: String, val latitude: Double, val longitude: Double)
internal data class PhotoMarker(val type: String, val placeId: String?, val name: String?, val category: String?,
    val latitude: Double, val longitude: Double, val count: Int)
