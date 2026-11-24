package com.shiguang.photograph

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.math.BigDecimal

@Composable
internal fun PhotographerDetail(state: StudioState, model: StudioViewModel, signIn: () -> Unit, openChat: (String) -> Unit) {
    val selected = state.selected
    if (selected != null) {
        var packageId by rememberSaveable(selected.id) { mutableStateOf<String?>(null) }
        PageBack("返回首页", model::closePhotographer)
        Text(selected.nickname, style = MaterialTheme.typography.headlineMedium)
        Text(selected.city)
        if (selected.bio.isNotBlank()) Text(selected.bio)
        if (state.studio?.id != selected.id) {
            OutlinedButton(enabled = !state.busy, onClick = { openChat(selected.id) }) { Text("联系摄影师") }
        }
        HorizontalDivider()
        Text("拍摄套餐", style = MaterialTheme.typography.titleLarge)
        if (state.publicPackagesLoaded && state.publicPackages.isEmpty()) Text("暂无上架套餐。")
        if (!state.publicPackagesLoaded && !state.busy) {
            OutlinedButton(onClick = model::reloadPublicPackages) { Text("重试加载套餐") }
        }
        state.publicPackages.forEach { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    Text("¥${money(item.priceMinor)} · ${item.durationMinutes} 分钟")
                    Text("精修 ${item.retouchedCount} 张 · ${item.deliveryDays} 天交片")
                    OutlinedButton(enabled = !state.busy, onClick = {
                        packageId = item.id
                        model.clearSlots()
                    }) { Text("申请预约") }
                }
            }
        }
        if (state.publicPackageCursor != null) {
            OutlinedButton(enabled = !state.busy, onClick = model::morePublicPackages) { Text("更多套餐") }
        }
        state.publicPackages.find { it.id == packageId }?.let { item ->
            if (state.account == null) {
                Button(onClick = signIn) { Text("登录后预约") }
            } else {
                BookingForm(selected.id, item, state, model)
            }
        }
    }
}

@Composable
internal fun WorkspaceScreen(state: StudioState, styles: StylesState, retryStyles: () -> Unit, model: StudioViewModel) {
    val account = state.account
    if (account == null) {
        var phone by rememberSaveable { mutableStateOf(state.challengePhone.orEmpty()) }
        var code by rememberSaveable { mutableStateOf("") }
        Text("短信验证码登录", style = MaterialTheme.typography.titleLarge)
        Field(phone, { phone = it; code = "" }, "手机号", KeyboardType.Phone)
        OutlinedButton(enabled = !state.busy && phone.isNotBlank(), onClick = { model.sendCode(phone) }) {
            Text(if (phone == state.challengePhone) "重新发送" else "发送验证码")
        }
        if (phone == state.challengePhone) {
            Field(code, { code = it.filter { digit -> digit in '0'..'9' }.take(6) }, "六位验证码", KeyboardType.Number)
            Button(enabled = !state.busy && code.length == 6, onClick = { model.login(phone, code) }) { Text("登录") }
        }
        if (state.sessionActive) {
            OutlinedButton(enabled = !state.busy, onClick = model::reload) { Text("重新加载账号") }
            OutlinedButton(enabled = !state.busy, onClick = model::logout) { Text("退出登录") }
        }
        return
    }
    var nickname by rememberSaveable(account) { mutableStateOf(account.nickname) }
    var paid by rememberSaveable(account) { mutableStateOf(account.acceptsPaidBookings) }
    var tfp by rememberSaveable(account) { mutableStateOf(account.acceptsTfpBookings) }
    Text("账号资料", style = MaterialTheme.typography.titleLarge)
    Field(nickname, { nickname = it }, "昵称")
    Toggle("接受付费约拍", paid) { paid = it }
    Toggle("接受互免", tfp) { tfp = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !state.busy && nickname.isNotBlank(), onClick = { model.saveAccount(AccountEdit(nickname, paid, tfp)) }) {
            Text("保存")
        }
        OutlinedButton(enabled = !state.busy, onClick = model::logout) { Text("退出登录") }
    }
    HorizontalDivider()
    Text("摄影师工作台", style = MaterialTheme.typography.titleLarge)
    val studio = state.studio
    var city by rememberSaveable(studio) { mutableStateOf(studio?.city.orEmpty()) }
    var bio by rememberSaveable(studio) { mutableStateOf(studio?.bio.orEmpty()) }
    val status = studio?.approvalStatus
    Text(when (status) {
        "PENDING" -> "申请待审核"
        "APPROVED" -> "入驻已通过"
        "REJECTED" -> "申请未通过，可修改资料后重新提交"
        "SUSPENDED" -> "摄影师服务已暂停"
        else -> "申请摄影师入驻"
    })
    if (status == null || status == "REJECTED" || status == "APPROVED") {
        Field(city, { city = it }, "服务城市")
        OutlinedTextField(value = bio, onValueChange = { bio = it }, label = { Text("个人介绍") },
            modifier = Modifier.fillMaxWidth(), minLines = 2)
        Button(enabled = !state.busy && city.isNotBlank(), onClick = { model.saveStudio(BioEdit(city, bio)) }) {
            Text(if (status == "APPROVED") "保存摄影师资料" else "提交申请")
        }
    }
    OutlinedButton(enabled = !state.busy, onClick = model::reload) { Text("刷新工作台") }
    if (studio?.approvalStatus == "APPROVED") {
        StyleEditor(studio, styles, retryStyles, state.busy, model)
        PackageEditor(state, model)
        CalendarEditor(state, model)
    }
}

@Composable
private fun StyleEditor(profile: StudioProfile, styles: StylesState, retry: () -> Unit, busy: Boolean, model: StudioViewModel) {
    var selected by rememberSaveable(profile.id, profile.styleIds) { mutableStateOf(ArrayList(profile.styleIds)) }
    Text("服务风格", style = MaterialTheme.typography.titleMedium)
    when (styles) {
        is StylesState.Loaded -> {
            styles.styles.forEach { style ->
                Toggle(style.name, style.id in selected) { checked ->
                    selected = ArrayList(if (checked) selected + style.id else selected - style.id)
                }
            }
            OutlinedButton(enabled = !busy, onClick = { model.saveStyles(selected.toSet()) }) { Text("保存风格") }
        }
        StylesState.Loading -> Text("正在加载风格词表。")
        StylesState.Unconfigured -> Text("尚未配置服务地址。")
        StylesState.Failed -> OutlinedButton(onClick = retry) { Text("重试加载风格") }
    }
}

@Composable
private fun PackageEditor(state: StudioState, model: StudioViewModel) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    Text("套餐管理", style = MaterialTheme.typography.titleMedium)
    state.packages.forEach { item ->
        Card(onClick = { selectedId = item.id; editing = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium)
                Text("¥${money(item.priceMinor)} · ${if (item.listed) "已上架" else "未上架"}")
            }
        }
    }
    if (state.packageCursor != null) {
        OutlinedButton(enabled = !state.busy, onClick = model::moreOwnPackages) { Text("更多套餐") }
    }
    OutlinedButton(enabled = !state.busy, onClick = { selectedId = null; editing = true }) { Text("新增套餐") }
    if (!editing) return
    val current = state.packages.find { it.id == selectedId }
    if (selectedId != null && current == null) {
        Text("当前列表中没有此套餐，请重新选择。")
        OutlinedButton(onClick = { editing = false }) { Text("关闭编辑") }
        return
    }
    var name by rememberSaveable(current?.id, current?.version) { mutableStateOf(current?.name.orEmpty()) }
    var duration by rememberSaveable(current?.id, current?.version) { mutableStateOf(current?.durationMinutes?.toString() ?: "90") }
    var count by rememberSaveable(current?.id, current?.version) { mutableStateOf(current?.retouchedCount?.toString() ?: "9") }
    var days by rememberSaveable(current?.id, current?.version) { mutableStateOf(current?.deliveryDays?.toString() ?: "7") }
    var price by rememberSaveable(current?.id, current?.version) { mutableStateOf(current?.priceMinor?.let(::money).orEmpty()) }
    var listed by rememberSaveable(current?.id, current?.version) { mutableStateOf(current?.listed ?: false) }
    Field(name, { name = it }, "套餐名称")
    Field(duration, { duration = it }, "拍摄分钟数", KeyboardType.Number)
    Field(count, { count = it }, "精修张数", KeyboardType.Number)
    Field(days, { days = it }, "交付天数", KeyboardType.Number)
    Field(price, { price = it }, "价格（元）", KeyboardType.Decimal)
    Toggle("公开上架", listed) { listed = it }
    val durationValue = duration.toIntOrNull()?.takeIf { it in 30..720 }
    val countValue = count.toIntOrNull()?.takeIf { it >= 0 }
    val dayValue = days.toIntOrNull()?.takeIf { it > 0 }
    val minor = runCatching { BigDecimal(price).movePointRight(2).intValueExact() }.getOrNull()?.takeIf { it >= 0 }
    val edit = if (name.isNotBlank() && durationValue != null && countValue != null && dayValue != null && minor != null) {
        PackageEdit(name, durationValue, countValue, dayValue, minor, listed, current?.version)
    } else null
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !state.busy && edit != null, onClick = { edit?.let { model.savePackage(current?.id, it) } }) {
            Text("保存套餐")
        }
        OutlinedButton(enabled = !state.busy, onClick = { editing = false }) { Text("关闭编辑") }
    }
}

@Composable
internal fun Field(value: String, change: (String) -> Unit, label: String, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}

@Composable
internal fun Toggle(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, change)
        Text(label)
    }
}

internal fun money(minor: Int) = BigDecimal.valueOf(minor.toLong(), 2).toPlainString()

