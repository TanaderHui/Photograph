package com.shiguang.photograph

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

internal fun hasCityPermission(context: Context) =
    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
internal suspend fun locateCity(context: Context): String? {
    if (!hasCityPermission(context)) return null
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .filter { manager.isProviderEnabled(it) }
    val cached = providers.mapNotNull { provider ->
        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
    }.maxByOrNull { it.elapsedRealtimeNanos }
    val recent = cached?.takeIf { android.os.SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < 300_000_000_000L }
    val location = recent ?: withTimeoutOrNull(8_000) {
        val provider = providers.firstOrNull() ?: return@withTimeoutOrNull null
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            LocationManagerCompat.getCurrentLocation(manager, provider, signal, ContextCompat.getMainExecutor(context)) {
                if (continuation.isActive) continuation.resume(it)
            }
        }
    }
    if (location == null || !Geocoder.isPresent()) return null
    return withContext(Dispatchers.IO) {
        Geocoder(context, Locale.SIMPLIFIED_CHINESE)
            .getFromLocation(location.latitude, location.longitude, 1)
            ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
            ?.trim()?.removeSuffix("市")?.takeIf(String::isNotBlank)
    }
}
