package com.shiguang.photograph

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val DEFAULT_ZONE = "Asia/Shanghai"
private val DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
internal fun CalendarEditor(state: StudioState, model: StudioViewModel) {
    HorizontalDivider()
    Text("工作日历", style = MaterialTheme.typography.titleMedium)
    val calendar = state.calendar
    OutlinedButton(enabled = !state.busy, onClick = model::loadCalendar) {
        Text(if (calendar == null) "打开日历" else "刷新日历")
    }
    if (calendar == null) return
    var zone by rememberSaveable(calendar.zoneId) { mutableStateOf(calendar.zoneId) }
    var buffer by rememberSaveable(calendar.bufferMinutes) { mutableStateOf(calendar.bufferMinutes.toString()) }
    var startText by rememberSaveable { mutableStateOf("") }
    var endText by rememberSaveable { mutableStateOf("") }
    Text(if (calendar.visibility == "PUBLIC") "访客可查询可约时段" else "私密 · 访客可提交指定时间申请")
    OutlinedButton(enabled = !state.busy, onClick = {
        model.setCalendarVisibility(if (calendar.visibility == "PUBLIC") "PRIVATE" else "PUBLIC")
    }) { Text(if (calendar.visibility == "PUBLIC") "设为私密" else "公开可约时段") }
    Field(zone, { zone = it }, "时区（IANA）")
    Field(buffer, { buffer = it }, "拍摄后缓冲分钟数", KeyboardType.Number)
    val validZone = zone.trim().takeIf { it in ZoneId.getAvailableZoneIds() }
    val validBuffer = buffer.toIntOrNull()?.takeIf { it in 0..120 }
    Button(enabled = !state.busy && validZone != null && validBuffer != null, onClick = {
        if (validZone != null && validBuffer != null) model.saveCalendar(CalendarEdit(validZone, validBuffer, calendar.windows))
    }) { Text("保存日历设置") }
    Text("工作区间 · ${calendar.zoneId}")
    calendar.windows.forEach { window ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${displayTime(window.startsAt, calendar.zoneId)}\n至 ${displayTime(window.endsAt, calendar.zoneId)}",
                modifier = Modifier.weight(1f))
            OutlinedButton(enabled = !state.busy, onClick = {
                model.saveCalendar(CalendarEdit(calendar.zoneId, calendar.bufferMinutes, calendar.windows - window))
            }) { Text("移除") }
        }
    }
    Field(startText, { startText = it }, "开始（YYYY-MM-DD HH:mm）")
    Field(endText, { endText = it }, "结束（YYYY-MM-DD HH:mm）")
    val start = parseTime(startText, calendar.zoneId)
    val end = parseTime(endText, calendar.zoneId)
    OutlinedButton(enabled = !state.busy && start != null && end != null && end.isAfter(start), onClick = {
        if (start != null && end != null) {
            model.saveCalendar(CalendarEdit(calendar.zoneId, calendar.bufferMinutes,
                calendar.windows + WorkWindow(start.toString(), end.toString())))
        }
    }) { Text("添加工作区间") }
    if (calendar.busy.isNotEmpty()) {
        Text("已预约及缓冲", style = MaterialTheme.typography.titleSmall)
        calendar.busy.forEach { window ->
            Text("${displayTime(window.startsAt, calendar.zoneId)} 至 ${displayTime(window.endsAt, calendar.zoneId)}")
        }
    }
}

@Composable
internal fun BookingForm(photographerId: String, item: Listing, state: StudioState, model: StudioViewModel) {
    val available = state.slots
    val zone = available?.zoneId ?: DEFAULT_ZONE
    var date by rememberSaveable(item.id) { mutableStateOf(LocalDate.now(ZoneId.of(DEFAULT_ZONE)).plusDays(1).toString()) }
    var startText by rememberSaveable(item.id) { mutableStateOf("") }
    var meeting by rememberSaveable(item.id) { mutableStateOf("") }
    var note by rememberSaveable(item.id) { mutableStateOf("") }
    var tfp by rememberSaveable(item.id) { mutableStateOf(false) }
    HorizontalDivider()
    Text("预约 ${item.name}", style = MaterialTheme.typography.titleMedium)
    Field(date, { date = it; model.clearSlots() }, "查看日期（YYYY-MM-DD）")
    val validDate = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
    OutlinedButton(enabled = !state.busy && validDate != null, onClick = {
        validDate?.let { model.loadSlots(photographerId, item.id, it.toString()) }
    }) { Text("查看可约时段") }
    if (available?.visibility == "PRIVATE") Text("档期待确认。请填写商定的时间。")
    if (available?.visibility == "PUBLIC" && available.slots.isEmpty()) Text("该日没有可约时段。")
    available?.slots?.forEach { slot ->
        OutlinedButton(enabled = !state.busy, onClick = { startText = slot.startsAt }) {
            Text(displayTime(slot.startsAt, zone))
        }
    }
    Field(startText, { startText = it }, "拍摄时间（YYYY-MM-DD HH:mm）")
    Text("当前输入时区 $zone，可输入带偏移的 ISO 时间。", style = MaterialTheme.typography.bodySmall)
    Field(meeting, { meeting = it }, "集合区域")
    Field(note, { note = it }, "拍摄备注（选填）")
    Toggle("申请互免约拍", tfp) { tfp = it }
    Text(if (tfp) "拍摄服务费 ¥0" else "服务费 ¥${money(item.priceMinor)} · 线下结算")
    val start = parseTime(startText, zone)
    val request = start?.let { BookingRequest(item.id, item.version, it.toString(), meeting, note, if (tfp) "TFP" else "PAID") }
    Button(enabled = !state.busy && request != null && meeting.isNotBlank() && request != state.lastBookingRequest,
        onClick = { request?.let(model::createBooking) }) { Text("提交预约申请") }
}

@Composable
internal fun BookingScreen(state: StudioState, title: String, model: StudioViewModel, signIn: () -> Unit,
    openChat: (String) -> Unit) {
    val account = state.account
    val booking = state.selectedBooking
    if (booking != null && account != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = model::closeBooking) { Text("返回$title") }
            OutlinedButton(enabled = !state.busy, onClick = { model.openBooking(booking.id) }) { Text("刷新订单") }
        }
        BookingDetail(booking, account.id, state.busy, model) { openChat(booking.id) }
        return
    }
    PageTitle(title)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(false to "未完成", true to "已完成").forEachIndexed { index, (completed, label) ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index, 2),
                selected = state.completedBookings == completed,
                enabled = !state.busy,
                onClick = { if (account == null) model.selectBookingGroup(completed) else model.loadBookings(completed = completed) },
            ) { Text(label) }
        }
    }
    if (account == null) {
        EmptyPanel("登录后查看订单", actionLabel = "去登录", action = signIn)
        return
    }
    if (state.bookings.isEmpty() && !state.busy) {
        val title = if (state.completedBookings) "暂无已完成订单" else "暂无未完成订单"
        EmptyPanel(title, actionLabel = "刷新", action = { model.loadBookings() })
    }
    state.bookings.forEach { item ->
        Card(onClick = { model.openBooking(item.id) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.packageSnapshot.name, style = MaterialTheme.typography.titleMedium)
                Text("${statusLabel(item.status)} · ${displayTime(item.startsAt, item.zoneId)}")
            }
        }
    }
    if (state.bookingCursor != null) {
        OutlinedButton(enabled = !state.busy, onClick = { model.loadBookings(true) }) { Text("更多订单") }
    }
    if (state.bookings.isNotEmpty()) {
        OutlinedButton(enabled = !state.busy, onClick = { model.loadBookings() }) { Text("刷新订单") }
    }
}

@Composable
private fun BookingDetail(booking: Booking, accountId: String, busy: Boolean, model: StudioViewModel,
    openChat: () -> Unit) {
    val customer = accountId == booking.customerId
    val now = OffsetDateTime.now()
    val beforeStart = OffsetDateTime.parse(booking.startsAt).isAfter(now)
    val afterEnd = !OffsetDateTime.parse(booking.endsAt).isAfter(now)
    Text(booking.packageSnapshot.name, style = MaterialTheme.typography.headlineSmall)
    Text("${statusLabel(booking.status)} · ${if (customer) "顾客" else "摄影师"}")
    Text("${displayTime(booking.startsAt, booking.zoneId)} 至 ${displayTime(booking.endsAt, booking.zoneId)}")
    Text(booking.meetingArea)
    if (booking.note.isNotBlank()) Text(booking.note)
    OutlinedButton(enabled = !busy, onClick = openChat) { Text(if (customer) "联系摄影师" else "联系顾客") }
    Text("¥${money(booking.packageSnapshot.priceMinor)} · ${if (booking.engagementType == "TFP") "互免" else "线下结算"}")
    if (booking.status == "PENDING") {
        Text("申请截止 ${displayTime(booking.expiresAt, booking.zoneId)}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!customer) {
                Button(enabled = !busy, onClick = { model.bookingAction(booking, "accept") }) { Text("接单") }
            }
            OutlinedButton(enabled = !busy, onClick = {
                model.bookingAction(booking, if (customer) "withdraw" else "reject")
            }) { Text(if (customer) "撤回申请" else "拒单") }
        }
    }
    if (booking.status in listOf("AWAITING_DELIVERY", "DELIVERED", "COMPLETED")) {
        DeliveryActions(booking, customer, busy, model)
        return
    }
    if (booking.status != "CONFIRMED") return
    if (afterEnd) {
        val confirmed = if (customer) booking.customerConfirmed else booking.photographerConfirmed
        if (confirmed) Text("你已确认拍摄，等待对方确认。")
        Button(enabled = !busy && !confirmed, onClick = { model.bookingAction(booking, "confirmShoot") }) { Text("确认已拍摄") }
    }
    if (!beforeStart) return
    val pending = booking.pendingReschedule
    if (pending == null) {
        var newTime by rememberSaveable(booking.id, booking.version) { mutableStateOf("") }
        Field(newTime, { newTime = it }, "改期（YYYY-MM-DD HH:mm）")
        val parsed = parseTime(newTime, booking.zoneId)
        OutlinedButton(enabled = !busy && parsed != null, onClick = {
            parsed?.let { model.reschedule(booking, it.toString()) }
        }) { Text("提议改期") }
    } else {
        Text("拟改期至 ${displayTime(pending.startsAt, booking.zoneId)}")
        if (pending.proposedBy == accountId) {
            Text("等待对方决定。")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { model.decideReschedule(booking, pending, "accept") }) { Text("同意改期") }
                OutlinedButton(enabled = !busy, onClick = { model.decideReschedule(booking, pending, "reject") }) { Text("拒绝改期") }
            }
        }
    }
    var cancelOpen by rememberSaveable(booking.id) { mutableStateOf(false) }
    OutlinedButton(enabled = !busy, onClick = { cancelOpen = !cancelOpen }) {
        Text(if (cancelOpen) "保留预约" else "取消预约")
    }
    if (cancelOpen) {
        Text("请选择取消原因")
        listOf("SCHEDULE" to "时间冲突", "WEATHER" to "天气", "OTHER" to "其他").forEach { (code, label) ->
            OutlinedButton(enabled = !busy, onClick = { model.bookingAction(booking, "cancel", code) }) { Text(label) }
        }
    }
}

@Composable
private fun DeliveryActions(booking: Booking, customer: Boolean, busy: Boolean, model: StudioViewModel) {
    val action = when (booking.status) {
        "AWAITING_DELIVERY" -> "markDelivered".takeUnless { customer }
        "DELIVERED" -> "confirmReceipt".takeIf { customer }
        else -> null
    }
    val message = when (booking.status) {
        "AWAITING_DELIVERY" -> if (customer) "请与摄影师约定线下收片方式。" else "按约定线下交付照片后，在这里标记已交片。"
        "DELIVERED" -> if (customer) "摄影师已标记交片。请核对收到的照片后再确认。" else "已通知顾客确认收片。"
        else -> "顾客已确认收片。"
    }
    Text(message)
    booking.deliveredAt?.let { Text("交片记录 ${displayTime(it, booking.zoneId)}") }
    booking.completedAt?.let { Text("收片确认 ${displayTime(it, booking.zoneId)}") }
    val supportUrl = BuildConfig.SUPPORT_URL
    if (supportUrl.isNotBlank()) {
        val uriHandler = LocalUriHandler.current
        TextButton(onClick = { uriHandler.openUri(supportUrl) }) { Text("联系约拍客服") }
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text("订单编号 ${booking.id}", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (action == null) return

    var confirming by rememberSaveable(booking.id, booking.version) { mutableStateOf(false) }
    val label = if (customer) "确认收片" else "标记已线下交片"
    Button(enabled = !busy, onClick = { confirming = true }) { Text(label) }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(label) },
            text = { Text(if (customer) "收到并核对照片后再确认。确认后订单将完成。" else "请确认已按约定把照片交给顾客。") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    confirming = false
                    model.bookingAction(booking, action)
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("返回") } },
        )
    }
}

private fun parseTime(raw: String, zone: String): OffsetDateTime? {
    val text = raw.trim().replace(' ', 'T')
    return runCatching {
        runCatching { OffsetDateTime.parse(text) }.getOrElse {
            val local = LocalDateTime.parse(text)
            val offset = ZoneId.of(zone).rules.getValidOffsets(local).single()
            local.atOffset(offset)
        }
    }.getOrNull()
}

private fun displayTime(raw: String, zone: String): String =
    OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.of(zone)).format(DISPLAY_TIME)

private fun statusLabel(status: String) = when (status) {
    "PENDING" -> "待确认"
    "CONFIRMED" -> "已确认"
    "AWAITING_DELIVERY" -> "待交片"
    "DELIVERED" -> "已交片"
    "COMPLETED" -> "已完成"
    "REJECTED" -> "已拒单"
    "WITHDRAWN" -> "已撤回"
    "CONFLICT_CLOSED" -> "档期冲突，申请已关闭"
    "EXPIRED" -> "已过期"
    "CANCELLED" -> "已取消"
    else -> "未知状态"
}

internal fun eventLabel(event: String) = when (event) {
    "BOOKING_REQUESTED" -> "收到预约申请"
    "BOOKING_CONFIRMED" -> "预约已确认"
    "BOOKING_REJECTED" -> "预约已拒绝"
    "BOOKING_WITHDRAWN" -> "预约已撤回"
    "BOOKING_CONFLICT_CLOSED" -> "申请因档期冲突关闭"
    "BOOKING_EXPIRED" -> "预约申请已过期"
    "BOOKING_CANCELLED" -> "预约已取消"
    "BOOKING_SHOOT_CONFIRMED" -> "对方已确认拍摄"
    "BOOKING_AWAITING_DELIVERY" -> "双方已确认拍摄"
    "BOOKING_DELIVERED" -> "摄影师已标记交片，请确认收片"
    "BOOKING_COMPLETED" -> "顾客已确认收片，订单已完成"
    "RESCHEDULE_PROPOSED" -> "收到改期申请"
    "RESCHEDULE_ACCEPTED" -> "改期已通过"
    "RESCHEDULE_REJECTED" -> "改期已拒绝"
    else -> "订单有更新"
}
