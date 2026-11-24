package com.shiguang.photograph

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.location.Geocoder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import java.util.Locale

private data class MapCamera(val latitude: Double, val longitude: Double, val zoom: Double)
private data class MapViewport(val camera: MapCamera, val bounds: String)

private val StartingCamera = MapCamera(31.2304, 121.4737, 10.8)
private val MapShape = RoundedCornerShape(18.dp)

@Composable
internal fun FullPhotoMap(city: String, back: () -> Unit) {
    BackHandler(onBack = back)
    var category by rememberSaveable { mutableStateOf("") }
    var input by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var mapCity by rememberSaveable { mutableStateOf(city) }
    var results by remember { mutableStateOf<List<PhotoPlace>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<PhotoPlace?>(null) }
    val api = remember { Network.api(StudioApi::class.java) }
    LaunchedEffect(query) {
        results = null
        failed = false
        if (query.isBlank()) return@LaunchedEffect
        try {
            results = requireNotNull(api).places(query = query).items
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed = true
        }
    }

    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        PageBack("返回首页", back)
        FeedSearch(input, { input = it }, "搜索地点或城市") { query = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("" to "全部", "SCENIC" to "风景", "SPOT" to "机位").forEach { (id, title) ->
                FilterChip(selected = category == id, onClick = { category = id }, label = { Text(title) })
            }
        }
        if (query.isBlank()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("北京", "上海", "杭州", "纽约").forEach { name ->
                    TextButton(onClick = { mapCity = name; input = name; query = name }) { Text(name) }
                }
            }
        }
        if (failed) Text("暂时无法搜索地点。")
        results?.let { places ->
            if (places.isEmpty()) Text("没有匹配的地点。")
            else places.take(4).forEach { place ->
                TextButton(onClick = {
                    chosen = place
                    category = ""
                    input = ""
                    query = ""
                    results = null
                }) { Text("${place.name} · ${place.city}") }
            }
        }
        PhotoMap(mapCity, category, chosen,
            modifier = Modifier.fillMaxWidth().weight(1f).clip(MapShape))
    }
}

@Composable
private fun PhotoMap(city: String, category: String, target: PhotoPlace?, modifier: Modifier) {
    val context = LocalContext.current
    val api = remember { Network.api(StudioApi::class.java) }
    var camera by remember { mutableStateOf(StartingCamera) }
    var viewport by remember { mutableStateOf<MapViewport?>(null) }
    var markers by remember { mutableStateOf<List<PhotoMarker>>(emptyList()) }
    var markerStatus by remember { mutableStateOf<String?>(null) }
    var markersLoaded by remember { mutableStateOf(false) }
    var baseFailed by remember { mutableStateOf(false) }
    var baseLoading by remember { mutableStateOf(true) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<PhotoPlace?>(null) }
    var detailFailed by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(city) {
        if (city.isBlank()) return@LaunchedEffect
        val resolved = placeCamera(context, api, city)
        if (resolved != null) camera = resolved
    }
    LaunchedEffect(target) {
        if (target != null) {
            camera = MapCamera(target.latitude, target.longitude, 14.0)
            selectedId = target.id
        }
    }
    LaunchedEffect(viewport, category, refresh) {
        val frame = viewport ?: return@LaunchedEffect
        markers = emptyList()
        markerStatus = null
        markersLoaded = false
        delay(160)
        try {
            markers = requireNotNull(api).mapMarkers(frame.bounds, frame.camera.zoom, category).items
            markersLoaded = true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            markerStatus = "地点加载失败"
        }
    }
    LaunchedEffect(selectedId) {
        detail = null
        detailFailed = false
        val id = selectedId ?: return@LaunchedEffect
        try {
            detail = requireNotNull(api).place(id)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            detailFailed = true
        }
    }

    Box(modifier) {
        NativePhotoMap(camera, markers, refresh, onViewport = { viewport = it },
            onPlace = { selectedId = it }, onStyleFailure = { baseFailed = it; baseLoading = false })

        Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("放大" to 1, "缩小" to -1).forEach { (text, delta) ->
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                    IconButton(onClick = {
                            val current = viewport?.camera ?: camera
                            camera = current.copy(zoom = (current.zoom + delta).coerceIn(2.0, 19.0))
                        }, modifier = Modifier.semantics { contentDescription = text }) {
                        Text(if (delta > 0) "+" else "−", style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
        markerStatus?.let { message ->
            Surface(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 26.dp),
                shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
                TextButton(onClick = { refresh++ }) { Text("$message · 重试") }
            }
        }
        if (baseFailed) {
            Button(onClick = { refresh++; baseFailed = false; baseLoading = true }, modifier = Modifier.align(Alignment.Center)) {
                Text("重试加载底图")
            }
        } else if (baseLoading) {
            Surface(Modifier.align(Alignment.Center), shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface) {
                Text("正在加载底图", Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
            }
        } else if (markersLoaded && markers.isEmpty()) {
            Surface(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 32.dp),
                shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
                Text("当前视野暂无拍摄地点", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (selectedId != null) {
        val place = detail
        var navigationUnavailable by remember(selectedId) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { selectedId = null },
            title = { Text(place?.name ?: if (detailFailed) "地点加载失败" else "正在加载地点") },
            text = {
                if (place != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${place.city} · ${if (place.category == "SCENIC") "风景" else "机位"}")
                        Text(place.description)
                        Text("暂无公开例图", style = MaterialTheme.typography.bodySmall)
                        if (navigationUnavailable) Text("当前设备没有可用的地图应用。")
                    }
                }
            },
            confirmButton = {
                if (place != null) {
                    Button(onClick = { navigationUnavailable = !openNavigation(context, place) }) { Text("导航") }
                }
            },
            dismissButton = { TextButton(onClick = { selectedId = null }) { Text("关闭") } },
        )
    }
}

@Suppress("DEPRECATION")
private suspend fun placeCamera(context: Context, api: StudioApi?, city: String): MapCamera? {
    try {
        val places = api?.places(city = city)?.items.orEmpty()
        if (places.isNotEmpty()) {
            return MapCamera(places.map { it.latitude }.average(), places.map { it.longitude }.average(), 10.8)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // The system geocoder can still resolve the city independently.
    }
    return withContext(Dispatchers.IO) {
        runCatching {
            Geocoder(context, Locale.SIMPLIFIED_CHINESE).getFromLocationName(city, 1)?.firstOrNull()
                ?.let { MapCamera(it.latitude, it.longitude, 10.8) }
        }.getOrNull()
    }
}

private fun openNavigation(context: Context, place: PhotoPlace): Boolean {
    val point = "${place.latitude},${place.longitude}"
    val uri = Uri.parse("geo:$point?q=" + Uri.encode("$point(${place.name})"))
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

@Suppress("DEPRECATION")
@Composable
private fun NativePhotoMap(camera: MapCamera, markers: List<PhotoMarker>, refresh: Int,
    onViewport: (MapViewport) -> Unit, onPlace: (String) -> Unit, onStyleFailure: (Boolean) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember(context) {
        MapLibre.getInstance(context)
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true))
    }
    var controller by remember(view) { mutableStateOf<MapLibreMap?>(null) }
    val viewportListener by rememberUpdatedState(onViewport)
    val placeListener by rememberUpdatedState(onPlace)
    val styleFailure by rememberUpdatedState(onStyleFailure)
    val markerLookup = remember(view) { mutableMapOf<Long, PhotoMarker>() }
    val icons = remember(view) { mutableMapOf<String, org.maplibre.android.annotations.Icon>() }

    DisposableEffect(view, owner) {
        view.onCreate(null)
        var disposed = false
        var started = false
        var resumed = false
        val listener = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!started) { view.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> if (!resumed) { view.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { view.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> if (started) { view.onStop(); started = false }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(listener)
        view.addOnDidFailLoadingMapListener { styleFailure(true) }
        view.getMapAsync { map ->
            if (!disposed) {
                map.uiSettings.isRotateGesturesEnabled = false
                map.uiSettings.isTiltGesturesEnabled = false
                map.setMinZoomPreference(2.0)
                map.setMaxZoomPreference(19.0)
                map.addOnCameraIdleListener { readViewport(map)?.let { viewportListener(it) } }
                map.setOnMarkerClickListener { marker ->
                    val item = markerLookup[marker.id]
                    if (item?.type == "CLUSTER") {
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(marker.position, (map.cameraPosition.zoom + 3).coerceAtMost(19.0)))
                    } else {
                        item?.placeId?.let { placeListener(it) }
                    }
                    true
                }
                controller = map
            }
        }
        onDispose {
            disposed = true
            owner.lifecycle.removeObserver(listener)
            if (resumed) view.onPause()
            if (started) view.onStop()
            view.onDestroy()
        }
    }

    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
    LaunchedEffect(controller, refresh) {
        val map = controller ?: return@LaunchedEffect
        map.setStyle(BuildConfig.MAP_STYLE_URL) { styleFailure(false) }
    }
    LaunchedEffect(controller, camera) {
        controller?.let { map ->
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(camera.latitude, camera.longitude), camera.zoom))
            readViewport(map)?.let(onViewport)
        }
    }
    LaunchedEffect(controller, markers) {
        val map = controller ?: return@LaunchedEffect
        map.removeAnnotations()
        markerLookup.clear()
        for (item in markers) {
            val id = if (item.type == "CLUSTER") item.count.toString() else item.category.orEmpty()
            val icon = icons.getOrPut(id) { IconFactory.getInstance(context).fromBitmap(drawMarker(context, item)) }
            val marker = map.addMarker(MarkerOptions().position(LatLng(item.latitude, item.longitude))
                .title(item.name ?: "${item.count} 个地点").icon(icon))
            markerLookup[marker.id] = item
        }
    }
}

private fun readViewport(map: MapLibreMap): MapViewport? {
    val center = map.cameraPosition
    val target = center.target ?: return null
    val bounds = map.projection.visibleRegion.latLngBounds
    val south = bounds.latitudeSouth.coerceIn(-90.0, 90.0)
    val north = bounds.latitudeNorth.coerceIn(-90.0, 90.0)
    var west = wrapLongitude(bounds.longitudeWest)
    var east = wrapLongitude(bounds.longitudeEast)
    if (bounds.longitudeEast - bounds.longitudeWest >= 360.0) {
        west = -180.0
        east = 180.0
    }
    if (south >= north || west == east) return null
    return MapViewport(MapCamera(target.latitude, target.longitude, center.zoom), "$west,$south,$east,$north")
}

private fun wrapLongitude(value: Double) = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

private fun drawMarker(context: Context, marker: PhotoMarker): Bitmap {
    val size = (36 * context.resources.displayMetrics.density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.scale(size / 36f, size / 36f)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(18f, 18f, 17f, paint)
    paint.color = if (marker.category == "SPOT") 0xFFA34332.toInt() else 0xFF355B4E.toInt()
    canvas.drawCircle(18f, 18f, 15f, paint)
    paint.color = android.graphics.Color.WHITE
    if (marker.type == "CLUSTER") {
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = if (marker.count > 99) 10f else 14f
        paint.isFakeBoldText = true
        canvas.drawText(marker.count.toString(), 18f, 18f - (paint.ascent() + paint.descent()) / 2, paint)
    } else if (marker.category == "SPOT") {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.7f
        canvas.drawRoundRect(9f, 13f, 27f, 25f, 2f, 2f, paint)
        canvas.drawCircle(18f, 19f, 3.3f, paint)
        canvas.drawLine(14f, 11f, 20f, 11f, paint)
    } else {
        val mountain = android.graphics.Path().apply {
            moveTo(8f, 24f)
            lineTo(16f, 12f)
            lineTo(21f, 19f)
            lineTo(24f, 15f)
            lineTo(29f, 24f)
            close()
        }
        canvas.drawPath(mountain, paint)
    }
    return bitmap
}

