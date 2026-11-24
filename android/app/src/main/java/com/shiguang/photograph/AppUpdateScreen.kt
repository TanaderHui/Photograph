package com.shiguang.photograph

import android.Manifest
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

@Composable
internal fun AppUpdateDialog(state: AppUpdateState, model: AppUpdateViewModel) {
    if (!state.opened) return
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var reminders by remember { mutableStateOf(AppUpdateAlerts.enabled(context)) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        reminders = granted
        AppUpdateAlerts.setEnabled(context, granted)
        feedback = if (granted) "已开启系统更新提醒。" else "可在「我的」手动检查更新。"
    }
    val available = state.available
    val downloadUrl = available?.verifiedDownloadUrl()
    AlertDialog(
        onDismissRequest = model::close,
        title = { Text(if (available != null) "发现新版本 ${available.versionName}" else "应用更新") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前版本 ${BuildConfig.VERSION_NAME}")
                Text(when {
                    state.checking -> "正在检查更新…"
                    state.failed -> "暂时无法检查更新，请稍后重试。"
                    downloadUrl != null -> "从 GitHub 下载后，按 Android 的提示安装。"
                    state.info?.configured == false -> "尚未配置更新来源。"
                    state.info?.release == null -> "GitHub 还没有可用的正式安装包。"
                    else -> "已经是最新版本。"
                })
                if (state.info?.configured == true) {
                    TextButton(onClick = {
                        feedback = null
                        when {
                            reminders -> {
                                AppUpdateAlerts.setEnabled(context, false)
                                reminders = false
                            }
                            AppUpdateAlerts.needsPermission(context) -> requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            AppUpdateAlerts.canNotify(context) -> {
                                AppUpdateAlerts.setEnabled(context, true)
                                reminders = true
                            }
                            else -> feedback = "请在系统应用设置中允许通知。"
                        }
                    }) { Text(if (reminders) "关闭系统更新提醒" else "开启系统更新提醒") }
                }
                feedback?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(enabled = !state.checking, onClick = {
                if (downloadUrl != null) {
                    try {
                        uriHandler.openUri(downloadUrl)
                        model.close()
                    } catch (_: IllegalArgumentException) {
                        feedback = "无法打开下载链接。"
                    } catch (_: ActivityNotFoundException) {
                        feedback = "请安装浏览器后再试。"
                    }
                } else {
                    model.check(force = true)
                }
            }) { Text(if (downloadUrl != null) "下载更新" else "检查更新") }
        },
        dismissButton = { TextButton(onClick = model::close) { Text(if (downloadUrl != null) "稍后" else "关闭") } },
    )
}
