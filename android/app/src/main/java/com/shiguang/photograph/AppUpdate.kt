package com.shiguang.photograph

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.http.GET

internal data class AppRelease(val versionCode: Int, val versionName: String, val downloadUrl: String) {
    fun verifiedDownloadUrl(): String? {
        if (versionCode !in 1..2_100_000_000 || !VERSION.matches(versionName)) return null
        val uri = runCatching { URI(downloadUrl) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != "github.com" || uri.userInfo != null || uri.port != -1 ||
            uri.rawQuery != null || uri.rawFragment != null) return null
        if (!DOWNLOAD_PATH.matches(uri.rawPath) ||
            !uri.rawPath.endsWith("/releases/download/v$versionName/photograph-$versionCode.apk")) return null
        return downloadUrl
    }

    companion object {
        private val VERSION = Regex("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")
        private val DOWNLOAD_PATH = Regex("/[A-Za-z0-9-]{1,39}/[A-Za-z0-9_-][A-Za-z0-9_.-]{0,99}/releases/download/v[0-9.]+/photograph-[0-9]+\\.apk")
    }
}

internal data class AppUpdateInfo(val configured: Boolean, val release: AppRelease?)

internal interface AppUpdateApi {
    @GET("api/v1/app-update")
    suspend fun update(): AppUpdateInfo
}

private suspend fun checkRelease(): AppUpdateInfo {
    val api = Network.api(AppUpdateApi::class.java) ?: return AppUpdateInfo(false, null)
    val result = api.update()
    require(result.release == null || result.release.verifiedDownloadUrl() != null)
    return result
}

internal data class AppUpdateState(
    val checking: Boolean = false,
    val opened: Boolean = false,
    val failed: Boolean = false,
    val info: AppUpdateInfo? = null,
) {
    val available: AppRelease? get() = info?.release?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
}

internal class AppUpdateViewModel : ViewModel() {
    private val updates = MutableStateFlow(AppUpdateState())
    val state = updates.asStateFlow()
    private var lastAttempt: Long? = null
    private var promptedVersion = 0

    fun open() {
        updates.update { it.copy(opened = true) }
        check(force = true)
    }

    fun close() {
        updates.update { it.copy(opened = false) }
    }

    fun check(force: Boolean = false) {
        if (updates.value.checking) return
        val now = SystemClock.elapsedRealtime()
        if (!force && lastAttempt?.let { now - it < TimeUnit.MINUTES.toMillis(2) } == true) return
        lastAttempt = now
        updates.update { it.copy(checking = true, failed = false) }
        viewModelScope.launch {
            try {
                val info = checkRelease()
                val version = info.release?.versionCode ?: 0
                val newVersion = version > BuildConfig.VERSION_CODE && version > promptedVersion
                if (newVersion) promptedVersion = version
                updates.update { it.copy(opened = it.opened || newVersion, info = info) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                updates.update { it.copy(failed = true) }
            } finally {
                updates.update { it.copy(checking = false) }
            }
        }
    }
}

internal object AppUpdateAlerts {
    const val OPEN_UPDATES = "com.shiguang.photograph.OPEN_UPDATES"
    const val JOB_ID = 8101
    private const val CHANNEL = "app_updates"
    private const val NOTIFICATION_ID = 8102

    private fun prefs(context: Context) = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("background_reminders", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("background_reminders", enabled).apply()
        if (enabled) {
            schedule(context)
        } else {
            context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }

    fun schedule(context: Context) {
        if (!enabled(context) || BuildConfig.API_BASE_URL.isBlank()) return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, AppUpdateJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setPeriodic(TimeUnit.MINUTES.toMillis(15))
            .build()
        scheduler.schedule(job)
    }

    fun needsPermission(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun canNotify(context: Context): Boolean = !needsPermission(context) &&
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun post(context: Context, release: AppRelease?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (release == null || release.versionCode <= BuildConfig.VERSION_CODE) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (!canNotify(context) || release.versionCode <= prefs(context).getInt("notified_version", 0)) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "应用更新", NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) return
        val intent = Intent(context, MainActivity::class.java)
            .setAction(OPEN_UPDATES)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("拾光永恒有新版本")
            .setContentText("${release.versionName} 已发布，点此查看更新。")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        prefs(context).edit().putInt("notified_version", release.versionCode).apply()
    }
}

class AppUpdateJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var task: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (!AppUpdateAlerts.enabled(this) || !AppUpdateAlerts.canNotify(this)) return false
        task = scope.launch {
            try {
                AppUpdateAlerts.post(this@AppUpdateJobService, checkRelease().release)
                jobFinished(params, false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                jobFinished(params, true)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        task?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
