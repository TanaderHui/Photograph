package com.shiguang.photograph

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

private val PanelShape = RoundedCornerShape(20.dp)

@Composable
internal fun ProfileScreen(state: StudioState, assistantTitle: String, accountAction: () -> Unit, myPosts: () -> Unit,
    orders: () -> Unit, messages: () -> Unit, closeAccount: () -> Unit,
    availableVersion: String?, checkForUpdate: () -> Unit) {
    PageTitle("我的")
    AccountRow("检查更新", availableVersion?.let { "新版本 $it 可下载" } ?: "当前版本 ${BuildConfig.VERSION_NAME}", checkForUpdate)
    val uriHandler = LocalUriHandler.current
    if (BuildConfig.PRIVACY_URL.isNotBlank()) {
        AccountRow("隐私政策", "查看个人信息处理规则") { uriHandler.openUri(BuildConfig.PRIVACY_URL) }
    }
    if (BuildConfig.SUPPORT_URL.isNotBlank()) {
        AccountRow("联系客服", "约拍售后与投诉") { uriHandler.openUri(BuildConfig.SUPPORT_URL) }
    }
    if (BuildConfig.FILING_NUMBER.isNotBlank()) {
        AccountRow("APP 备案号", BuildConfig.FILING_NUMBER) { uriHandler.openUri("https://beian.miit.gov.cn/") }
    }
    val account = state.account
    if (account == null) {
        EmptyPanel("尚未登录", "登录后可管理个人资料和约拍订单。", "手机号登录", accountAction)
        return
    }
    Surface(shape = PanelShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AuthorAvatar(account.nickname)
                Column(Modifier.weight(1f)) {
                    Text(account.nickname, style = MaterialTheme.typography.titleLarge)
                    Text("已登录", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(studioStatus(state.studio?.approvalStatus), style = MaterialTheme.typography.titleMedium)
            state.studio?.let { Text(it.city, style = MaterialTheme.typography.bodyMedium) }
            if (state.studio?.approvalStatus == "APPROVED") {
                Text(if (account.acceptsPaidBookings) "接受付费约拍" else "付费约拍已关闭")
                Text(if (account.acceptsTfpBookings) "接受互免约拍" else "互免约拍已关闭")
            }
        }
    }
    AccountRow("账号与摄影师工作台", "资料、接单方式、风格、套餐、档期和退出登录", accountAction)
    if (BuildConfig.COMMUNITY_ENABLED) AccountRow("我的帖子", "管理你发布的内容", myPosts)
    AccountRow(assistantTitle, "查看已完成与未完成订单", orders)
    AccountRow("我的消息", "查看私聊和订单通知", messages)
    var confirmClosure by rememberSaveable(account.id) { mutableStateOf(false) }
    TextButton(enabled = !state.busy, onClick = { confirmClosure = true }) { Text("注销账号") }
    if (confirmClosure) {
        AlertDialog(
            onDismissRequest = { confirmClosure = false },
            title = { Text("注销账号") },
            text = { Text("注销后将退出登录、移除个人资料并下架摄影师服务。请先处理进行中的约拍订单。历史订单记录会按隐私政策处理。") },
            confirmButton = {
                TextButton(enabled = !state.busy, onClick = {
                    confirmClosure = false
                    closeAccount()
                }) { Text("确认注销") }
            },
            dismissButton = { TextButton(onClick = { confirmClosure = false }) { Text("返回") } },
        )
    }
}

@Composable
internal fun AccountRow(title: String, label: String, action: () -> Unit) {
    Surface(onClick = action, shape = PanelShape, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun studioStatus(status: String?) = when (status) {
    "PENDING" -> "摄影师入驻待审核"
    "APPROVED" -> "摄影师 · 入驻已通过"
    "REJECTED" -> "摄影师申请未通过"
    "SUSPENDED" -> "摄影师服务已暂停"
    else -> "普通用户 · 可申请摄影师"
}
