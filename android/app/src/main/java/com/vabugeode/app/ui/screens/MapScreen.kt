package com.vabugeode.app.ui.screens

import android.os.Build
import androidx.compose.material.icons.filled.Layers
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.Satellite
import com.vabugeode.app.data.OfflineMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vabugeode.app.model.Mark
import com.vabugeode.app.model.MarkType
import com.vabugeode.app.model.PositionSource
import com.vabugeode.app.model.TrackPoint
import com.vabugeode.app.ui.AppViewModel
import com.vabugeode.app.ui.Fmt
import com.vabugeode.app.ui.components.Panel
import com.vabugeode.app.ui.components.Pill
import com.vabugeode.app.ui.components.SectionLabel
import com.vabugeode.app.ui.theme.Fonts
import com.vabugeode.app.ui.theme.Palette
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Онлайн-стиль — только если офлайн-файл региона не загружен (см. OfflineMap).
 * Массово качать тайлы OSM нельзя (ТЗ, раздел 12).
 */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/dark"

/** Вкладка «Карта»: сверху трек текущего похода, снизу метки. */
@Composable
fun MapScreen(vm: AppViewModel, contentPadding: PaddingValues, onAdded: (String) -> Unit) {
    val track by vm.repo.track.collectAsStateWithLifecycle()
    val marks by vm.repo.marks.collectAsStateWithLifecycle()
    val status by vm.repo.status.collectAsStateWithLifecycle()
    val start by vm.repo.hikeStartMs.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Box(Modifier.weight(1.1f).fillMaxWidth()) {
            val st = status
            TrackMap(
                track, marks, Modifier.fillMaxSize(),
                me = st?.lat?.let { la -> st.lon?.let { lo -> la to lo } },
                topInset = 100,
            )
            // Сводка и легенда
            Panel(
                Modifier.padding(12.dp).fillMaxWidth(),
                accent = Palette.Geo,
                padding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Fmt.num(st?.distanceKm ?: 0.0), fontFamily = Fonts.Pixel, fontSize = 34.sp, lineHeight = 32.sp, color = Palette.Geo)
                    Text(" км", style = MaterialTheme.typography.labelMedium, color = Palette.Geo)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        if (start != null && st != null) {
                            Text("в пути ${Fmt.duration(st.timeUtcMs - start!!)}", style = MaterialTheme.typography.labelMedium, color = Palette.Text)
                        }
                        if (st?.lat != null && st.lon != null) {
                            Text(Fmt.coord(st.lat, st.lon), style = MaterialTheme.typography.labelSmall, color = Palette.TextDim)
                        }
                    }
                }
                MapLegend(track.count { it.spoof })
            }
        }
        MarksPanel(vm, marks, start, onAdded, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
fun MapLegend(spoofCount: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
        Text("━ GPS", color = Palette.Geo, fontSize = 11.sp)
        Text("┅ по шагам", color = Palette.Pdr, fontSize = 11.sp)
        Text(if (spoofCount > 0) "● подмена $spoofCount" else "● подмена", color = Palette.Bad, fontSize = 11.sp)
        Text("● метки", color = Palette.Text, fontSize = 11.sp)
    }
}

/** Подложка карты */
private enum class BaseMap(val title: String) { SCHEME("Схема"), SATELLITE("Спутник"), HYBRID("Гибрид") }

/** На JVM-тестах (Robolectric) нативной карты нет — рисуем заглушку */
private val mapAvailable = Build.FINGERPRINT != "robolectric"

/**
 * Карта трека: GPS сплошной, по шагам пунктир, ложные точки подмены красным, метки.
 * me — текущая позиция для кнопки «следовать» (null — кнопки нет, например у прошлого похода).
 * topInset — сколько dp сверху занято панелью поверх карты.
 */
@Composable
fun TrackMap(
    track: List<TrackPoint>,
    marks: List<Mark>,
    modifier: Modifier = Modifier,
    me: Pair<Double, Double>? = null,
    topInset: Int = 40,
) {
    if (!mapAvailable) {
        Box(modifier.border(1.dp, Palette.Outline), contentAlignment = Alignment.Center) {
            Text("[ КАРТА | ${track.size} точек ]", style = MaterialTheme.typography.labelMedium, color = Palette.TextFaint)
        }
        return
    }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var follow by remember { mutableStateOf(false) }
    var fitted by remember { mutableStateOf(false) }
    val topPx = with(LocalDensity.current) { topInset.dp.roundToPx() }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        // Экран открыт поверх уже запущенной активности — догоняем состояние
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isLogoEnabled = false
        }
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    // Подложка: спутник или схема (офлайн-файлы), без файлов — онлайн-схема
    val hasSat = remember { OfflineMap.available(context, OfflineMap.Kind.SATELLITE) }
    val hasScheme = remember { OfflineMap.available(context, OfflineMap.Kind.SCHEME) }
    var mode by rememberSaveable { mutableStateOf(BaseMap.SCHEME) }
    if (!hasSat) mode = BaseMap.SCHEME
    LaunchedEffect(map, mode) {
        val m = map ?: return@LaunchedEffect
        style = null
        val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            when (mode) {
                BaseMap.SCHEME -> OfflineMap.styleJson(context)
                BaseMap.SATELLITE -> OfflineMap.satelliteStyleJson(context)
                BaseMap.HYBRID -> OfflineMap.satelliteStyleJson(context, hybrid = true)
            }
        }
        val builder = if (json != null) Style.Builder().fromJson(json) else Style.Builder().fromUri(STYLE_URL)
        m.setStyle(builder) { s ->
            setupLayers(s)
            style = s
        }
    }

    // Обновляем слои при новых точках
    LaunchedEffect(style, track.size, marks.size) {
        val s = style ?: return@LaunchedEffect
        updateLayers(s, track, marks)
        if (!fitted && track.size > 1) {
            fitted = true
            fitTrack(map, track, topPx)
        }
    }
    // Следование за позицией
    LaunchedEffect(follow, track.size) {
        if (follow && me != null) {
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(me.first, me.second), 15.5), 400)
        }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.BottomEnd).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (hasSat) {
                // По кругу: схема → спутник → гибрид (гибрид — только если есть схема для дорог и подписей)
                SmallFloatingActionButton(
                    onClick = {
                        mode = when (mode) {
                            BaseMap.SCHEME -> BaseMap.SATELLITE
                            BaseMap.SATELLITE -> if (hasScheme) BaseMap.HYBRID else BaseMap.SCHEME
                            BaseMap.HYBRID -> BaseMap.SCHEME
                        }
                    },
                    containerColor = if (mode != BaseMap.SCHEME) Palette.Geo else Palette.SurfaceHigh,
                    contentColor = if (mode != BaseMap.SCHEME) Palette.Bg else Palette.Text,
                ) { Icon(if (mode == BaseMap.HYBRID) Icons.Filled.Layers else Icons.Filled.Satellite, "Подложка: ${mode.title}") }
            }
            if (track.any { it.spoofLat != null }) {
                SmallFloatingActionButton(
                    onClick = { follow = false; fitSpoof(map, track, topPx) },
                    containerColor = Palette.BadBg, contentColor = Palette.Bad,
                ) { Text("!", fontFamily = Fonts.Pixel, fontSize = 24.sp) }
            }
            SmallFloatingActionButton(
                onClick = { follow = false; fitTrack(map, track, topPx) },
                containerColor = Palette.SurfaceHigh, contentColor = Palette.Text,
            ) { Icon(Icons.Filled.ZoomOutMap, "Весь трек") }
            if (me != null) {
                SmallFloatingActionButton(
                    onClick = { follow = !follow },
                    containerColor = if (follow) Palette.Geo else Palette.SurfaceHigh,
                    contentColor = if (follow) Palette.Bg else Palette.Text,
                ) { Icon(Icons.Filled.MyLocation, "Следовать") }
            }
        }
        Text(
            when {
                mode != BaseMap.SCHEME -> "${mode.title} | офлайн"
                OfflineMap.available(context) -> "© OpenStreetMap | офлайн"
                else -> "© OpenStreetMap | онлайн"
            },
            fontSize = 10.sp, color = Palette.TextDim,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 6.dp),
        )
    }
}

private fun hex(c: androidx.compose.ui.graphics.Color) = String.format("#%06X", 0xFFFFFF and c.toArgb())

private fun setupLayers(s: Style) {
    s.addSource(GeoJsonSource("track"))
    s.addSource(GeoJsonSource("spoof"))
    s.addSource(GeoJsonSource("marks"))
    s.addSource(GeoJsonSource("me"))

    s.addLayer(
        LineLayer("track-gps", "track").withProperties(
            PropertyFactory.lineColor(hex(Palette.Geo)),
            PropertyFactory.lineWidth(4f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ).apply { setFilter(Expression.eq(Expression.get("src"), "gps")) }
    )
    s.addLayer(
        LineLayer("track-pdr", "track").withProperties(
            PropertyFactory.lineColor(hex(Palette.Pdr)),
            PropertyFactory.lineWidth(3f),
            PropertyFactory.lineDasharray(arrayOf(1.5f, 1.5f)),
        ).apply { setFilter(Expression.eq(Expression.get("src"), "pdr")) }
    )
    s.addLayer(
        CircleLayer("spoof-pts", "spoof").withProperties(
            PropertyFactory.circleColor(hex(Palette.Bad)),
            PropertyFactory.circleRadius(5f),
            PropertyFactory.circleOpacity(0.7f),
            PropertyFactory.circleStrokeColor("#000000"),
            PropertyFactory.circleStrokeWidth(1f),
        )
    )
    s.addLayer(
        CircleLayer("marks-pts", "marks").withProperties(
            PropertyFactory.circleColor(Expression.get("color")),
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleStrokeColor("#0D1013"),
            PropertyFactory.circleStrokeWidth(2f),
        )
    )
    s.addLayer(
        SymbolLayer("marks-labels", "marks").withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(11f),
            PropertyFactory.textColor("#E9EDF0"),
            PropertyFactory.textHaloColor("#0D1013"),
            PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
        )
    )
    s.addLayer(
        CircleLayer("me-halo", "me").withProperties(
            PropertyFactory.circleColor(hex(Palette.Geo)),
            PropertyFactory.circleRadius(16f),
            PropertyFactory.circleOpacity(0.2f),
        )
    )
    s.addLayer(
        CircleLayer("me-dot", "me").withProperties(
            PropertyFactory.circleColor(hex(Palette.Geo)),
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2.5f),
        )
    )
}

/** Режем трек на отрезки по источнику позиции: GPS — сплошной, по шагам — пунктир. */
private fun updateLayers(s: Style, track: List<TrackPoint>, marks: List<Mark>) {
    val segments = ArrayList<Feature>()
    var cur = ArrayList<Point>()
    var curSrc: String? = null
    for (p in track) {
        // Пауза (переезд на машине) — линию рвём, а не тянем прямую через весь переезд
        if (p.src == PositionSource.PAUSE) {
            if (cur.size > 1) segments += lineFeature(cur, curSrc!!)
            cur = ArrayList()
            curSrc = null
            continue
        }
        if (p.lat.isNaN() || p.lon.isNaN()) continue
        val src = if (p.src == PositionSource.PDR) "pdr" else "gps"
        val pt = Point.fromLngLat(p.lon, p.lat)
        if (src != curSrc) {
            if (cur.size > 1) segments += lineFeature(cur, curSrc!!)
            // Отрезки стыкуем, чтобы не было дырок
            cur = if (cur.isNotEmpty() && curSrc != null) arrayListOf(cur.last(), pt) else arrayListOf(pt)
            curSrc = src
        } else cur.add(pt)
    }
    if (cur.size > 1 && curSrc != null) segments += lineFeature(cur, curSrc)
    s.getSourceAs<GeoJsonSource>("track")?.setGeoJson(FeatureCollection.fromFeatures(segments))

    val spoof = track.mapNotNull { p ->
        if (p.spoofLat != null && p.spoofLon != null) Feature.fromGeometry(Point.fromLngLat(p.spoofLon, p.spoofLat)) else null
    }
    s.getSourceAs<GeoJsonSource>("spoof")?.setGeoJson(FeatureCollection.fromFeatures(spoof))

    val mk = marks.mapNotNull { m ->
        if (m.lat == null || m.lon == null) return@mapNotNull null
        Feature.fromGeometry(Point.fromLngLat(m.lon, m.lat)).apply {
            addStringProperty("color", hex(markColor(m.type)))
            addStringProperty("label", if (m.text.isNotBlank()) m.text else m.type.title)
        }
    }
    s.getSourceAs<GeoJsonSource>("marks")?.setGeoJson(FeatureCollection.fromFeatures(mk))

    track.lastOrNull { !it.lat.isNaN() }?.let { p ->
        s.getSourceAs<GeoJsonSource>("me")?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)))
    }
}

private fun lineFeature(pts: List<Point>, src: String) =
    Feature.fromGeometry(LineString.fromLngLats(pts)).apply { addStringProperty("src", src) }

private fun fitTrack(map: MapLibreMap?, track: List<TrackPoint>, top: Int) {
    val pts = track.filter { !it.lat.isNaN() && !it.lon.isNaN() }.map { LatLng(it.lat, it.lon) }
    fitPoints(map, pts, top)
}

private fun fitSpoof(map: MapLibreMap?, track: List<TrackPoint>, top: Int) {
    val pts = track.mapNotNull { p -> p.spoofLat?.let { LatLng(it, p.spoofLon!!) } }
    fitPoints(map, pts, top)
}

private fun fitPoints(map: MapLibreMap?, pts: List<LatLng>, top: Int) {
    map ?: return
    if (pts.isEmpty()) return
    if (pts.size == 1 || pts.all { it == pts.first() }) {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(pts.first(), 14.0), 500)
        return
    }
    val bounds = LatLngBounds.Builder().includes(pts).build()
    // Сверху место под панель сводки
    map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 60, top + 60, 120, 60), 500)
}

fun markColor(t: MarkType) = when (t) {
    MarkType.MARK -> Palette.Geo
    MarkType.ENTER -> Palette.Cold
    MarkType.FOUND -> Palette.Good
    MarkType.GUARD -> Palette.Bad
    MarkType.INTERESTING -> Palette.Warn
    MarkType.SYNC -> Palette.Sunset
    MarkType.TEXT -> Palette.Text
}
