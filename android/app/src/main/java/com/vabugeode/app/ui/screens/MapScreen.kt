package com.vabugeode.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vabugeode.app.model.Mark
import com.vabugeode.app.model.MarkType
import com.vabugeode.app.model.PositionSource
import com.vabugeode.app.model.TrackPoint
import com.vabugeode.app.ui.AppViewModel
import com.vabugeode.app.ui.Fmt
import com.vabugeode.app.ui.components.Panel
import com.vabugeode.app.ui.components.Pill
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
 * Онлайн-стиль для макета. В походе интернета нет — потом заменим на офлайн-файл
 * региона (PMTiles/MBTiles), см. ТЗ, раздел 12. Массово качать тайлы OSM нельзя.
 */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/dark"

@Composable
fun MapScreen(vm: AppViewModel, contentPadding: PaddingValues) {
    val track by vm.repo.track.collectAsStateWithLifecycle()
    val marks by vm.repo.marks.collectAsStateWithLifecycle()
    val status by vm.repo.status.collectAsStateWithLifecycle()
    val start by vm.repo.hikeStartMs.collectAsStateWithLifecycle()

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
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                setupLayers(s)
                style = s
            }
        }
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    // Обновляем слои при новых точках
    LaunchedEffect(style, track.size, marks.size) {
        val s = style ?: return@LaunchedEffect
        updateLayers(s, track, marks)
        if (!fitted && track.size > 1) {
            fitted = true
            fitTrack(map, track)
        }
    }
    // Следование за позицией
    LaunchedEffect(follow, track.size) {
        val st = status
        if (follow && st?.lat != null && st.lon != null) {
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(st.lat, st.lon), 15.5), 400)
        }
    }

    val spoofCount = track.count { it.spoof }
    Box(Modifier.fillMaxSize().padding(contentPadding)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // Сводка и легенда
        Panel(
            Modifier.padding(12.dp).fillMaxWidth(),
            padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        ) {
            val st = status
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${Fmt.num(st?.distanceKm ?: 0.0)} км" + (start?.let { s -> st?.let { " · ${Fmt.duration(it.timeUtcMs - s)}" } } ?: ""),
                        fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text,
                    )
                    if (st?.lat != null && st.lon != null) {
                        Text(Fmt.coord(st.lat, st.lon), style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
                    }
                }
                if (spoofCount > 0) {
                    Pill(
                        "подмена: $spoofCount",
                        Palette.Bad,
                        Modifier.clickable { follow = false; fitSpoof(map, track) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("━ GPS", color = Palette.Geo, fontSize = 11.sp)
                Text("┅ по шагам", color = Palette.Pdr, fontSize = 11.sp)
                Text("● подмена", color = Palette.Bad, fontSize = 11.sp)
                Text("● метки", color = Palette.Text, fontSize = 11.sp)
            }
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SmallFloatingActionButton(
                onClick = { follow = false; fitTrack(map, track) },
                containerColor = Palette.SurfaceHigh, contentColor = Palette.Text,
            ) { Icon(Icons.Filled.ZoomOutMap, "Весь трек") }
            SmallFloatingActionButton(
                onClick = { follow = !follow },
                containerColor = if (follow) Palette.Geo else Palette.SurfaceHigh,
                contentColor = if (follow) Palette.Bg else Palette.Text,
            ) { Icon(Icons.Filled.MyLocation, "Следовать") }
        }

        Text(
            "Онлайн-карта © OpenStreetMap · офлайн будет позже",
            fontSize = 10.sp, color = Palette.TextDim,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 14.dp).width(200.dp),
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
        val src = if (p.src == PositionSource.PDR) "pdr" else "gps"
        val pt = Point.fromLngLat(p.lon, p.lat)
        if (src != curSrc) {
            if (cur.size > 1) segments += lineFeature(cur, curSrc!!)
            // Отрезки стыкуем, чтобы не было дырок
            cur = if (cur.isNotEmpty()) arrayListOf(cur.last(), pt) else arrayListOf(pt)
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

    track.lastOrNull()?.let { p ->
        s.getSourceAs<GeoJsonSource>("me")?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)))
    }
}

private fun lineFeature(pts: List<Point>, src: String) =
    Feature.fromGeometry(LineString.fromLngLats(pts)).apply { addStringProperty("src", src) }

private fun fitTrack(map: MapLibreMap?, track: List<TrackPoint>) {
    val pts = track.map { LatLng(it.lat, it.lon) }
    fitPoints(map, pts)
}

private fun fitSpoof(map: MapLibreMap?, track: List<TrackPoint>) {
    val pts = track.mapNotNull { p -> p.spoofLat?.let { LatLng(it, p.spoofLon!!) } }
    fitPoints(map, pts)
}

private fun fitPoints(map: MapLibreMap?, pts: List<LatLng>) {
    map ?: return
    if (pts.isEmpty()) return
    if (pts.size == 1 || pts.all { it == pts.first() }) {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(pts.first(), 14.0), 500)
        return
    }
    val bounds = LatLngBounds.Builder().includes(pts).build()
    // Сверху место под панель сводки
    map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 60, 260, 60, 120), 500)
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
