package pl.pilot.vornav.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import pl.pilot.vornav.data.Waypoint
import pl.pilot.vornav.loc.Position
import pl.pilot.vornav.nav.Geo
import pl.pilot.vornav.nav.Mag
import pl.pilot.vornav.nav.VorReceiver
import kotlin.math.hypot

/** Rysuje na mapie lotniska, pomoce nawigacyjne, samolot, linię Direct-To i wybrane radiale OBS. */
class NavOverlay(
    private val onTap: (Waypoint) -> Unit,
    private val onEmptyTap: (Double, Double) -> Unit = { _, _ -> },
) : Overlay() {
    /** Strefy openAIP do narysowania (już przefiltrowane). */
    var areas: List<pl.pilot.vornav.data.AirspaceArea> = emptyList()
    private val areaFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val areaText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f; textAlign = Paint.Align.CENTER
        setShadowLayer(3f, 0f, 0f, android.graphics.Color.WHITE)
    }

    var points: List<Waypoint> = emptyList()
    var aircraft: Position? = null
    var direct: Waypoint? = null
    /** Strefy AUP/UUP: (strefa, aktywna teraz?, etykieta wysokości). */
    var zones: List<Triple<pl.pilot.vornav.data.Zone, Boolean, String>> = emptyList()
    private val zoneFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val zoneText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        setShadowLayer(4f, 0f, 0f, android.graphics.Color.WHITE)
    }

    /** Punkty trasy i indeks aktywnego odcinka (-1 = trasa nieaktywna). */
    var route: List<Waypoint> = emptyList()
    var activeLeg: Int = -1
    /** NDB nastrojona w ADF — rysowana linia namiaru od samolotu */
    var adf: Waypoint? = null
    /** stacja + kurs rzeczywisty radialu OBS (kolor wg NAV1/NAV2) */
    var radials: List<Triple<Waypoint, Double, Int>> = emptyList()

    private val pt = Point(); private val pt2 = Point()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f; color = android.graphics.Color.BLACK; isFakeBoldText = true
        setShadowLayer(4f, 0f, 0f, android.graphics.Color.WHITE)
    }
    private val drawn = ArrayList<Pair<Waypoint, Point>>()

    override fun draw(c: Canvas, mv: MapView, shadow: Boolean) {
        if (shadow) return
        val pj = mv.projection
        val zoom = mv.zoomLevelDouble
        drawn.clear()

        for ((st, brg, idx) in radials) {
            // linia kursu przez stację (obejmuje lot TO i FROM)
            val e1 = Geo.destination(st.lat, st.lon, brg, 120.0)
            val e2 = Geo.destination(st.lat, st.lon, brg + 180.0, 120.0)
            pj.toPixels(GeoPoint(e2.first, e2.second), pt); pj.toPixels(GeoPoint(e1.first, e1.second), pt2)
            stroke.color = if (idx == 0) 0xFFE040FB.toInt() else 0xFF00BCD4.toInt()
            stroke.strokeWidth = 4f
            stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(24f, 12f), 0f)
            c.drawLine(pt.x.toFloat(), pt.y.toFloat(), pt2.x.toFloat(), pt2.y.toFloat(), stroke)
            stroke.pathEffect = null
        }

        // statyczna przestrzeń (openAIP): tylko w widoku, bez strzępków mniejszych niż kilka pikseli
        if (areas.isNotEmpty()) {
            val bb = mv.boundingBox
            for (a in areas) {
                if (a.latMax < bb.latSouth || a.latMin > bb.latNorth || a.lonMax < bb.lonWest || a.lonMin > bb.lonEast) continue
                pj.toPixels(GeoPoint(a.latMin, a.lonMin), pt); pj.toPixels(GeoPoint(a.latMax, a.lonMax), pt2)
                val wPx = kotlin.math.abs(pt2.x - pt.x); val hPx = kotlin.math.abs(pt2.y - pt.y)
                if (wPx < 4 && hPx < 4) continue
                val col = a.category.color
                val dashed = a.category == pl.pilot.vornav.data.AspCategory.CTR || a.category == pl.pilot.vornav.data.AspCategory.RMZ
                for (r in a.rings) {
                    val path = Path()
                    var i = 0
                    while (i < r.size - 1) {
                        pj.toPixels(GeoPoint(r[i], r[i + 1]), pt)
                        if (i == 0) path.moveTo(pt.x.toFloat(), pt.y.toFloat()) else path.lineTo(pt.x.toFloat(), pt.y.toFloat())
                        i += 2
                    }
                    path.close()
                    if (a.category == pl.pilot.vornav.data.AspCategory.RPD || a.category == pl.pilot.vornav.data.AspCategory.CTR) {
                        areaFill.color = (col and 0x00FFFFFF) or 0x18000000
                        c.drawPath(path, areaFill)
                    }
                    stroke.pathEffect = if (dashed) android.graphics.DashPathEffect(floatArrayOf(18f, 10f), 0f) else null
                    stroke.color = col; stroke.strokeWidth = 3f
                    c.drawPath(path, stroke)
                    stroke.pathEffect = null
                }
                if (zoom >= 9 && wPx > 120) {
                    pj.toPixels(GeoPoint(a.centerLat, a.centerLon), pt)
                    areaText.color = col
                    c.drawText(a.name, pt.x.toFloat(), pt.y.toFloat(), areaText)
                    c.drawText("${a.lowerText}–${a.upperText}", pt.x.toFloat(), pt.y + 24f, areaText)
                }
            }
        }

        for ((z, active, label) in zones) {
            val col = if (active) 0xFFE53935.toInt() else 0xFFFF9800.toInt()
            for (r in z.rings) {
                val path = Path()
                var i = 0
                while (i < r.size - 1) {
                    pj.toPixels(GeoPoint(r[i], r[i + 1]), pt)
                    if (i == 0) path.moveTo(pt.x.toFloat(), pt.y.toFloat()) else path.lineTo(pt.x.toFloat(), pt.y.toFloat())
                    i += 2
                }
                path.close()
                zoneFill.color = (col and 0x00FFFFFF) or (if (active) 0x44000000 else 0x22000000)
                c.drawPath(path, zoneFill)
                stroke.pathEffect = if (active) null else android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
                stroke.color = col; stroke.strokeWidth = if (active) 4f else 3f
                c.drawPath(path, stroke)
                stroke.pathEffect = null
            }
            if (zoom >= 8) {
                pj.toPixels(GeoPoint(z.centerLat, z.centerLon), pt)
                zoneText.color = if (active) 0xFFB71C1C.toInt() else 0xFFE65100.toInt()
                c.drawText(z.designator, pt.x.toFloat(), pt.y.toFloat(), zoneText)
                if (zoom >= 9) c.drawText(label, pt.x.toFloat(), pt.y + 28f, zoneText)
            }
        }

        if (route.size >= 2) {
            for (k in 0 until route.size - 1) {
                pj.toPixels(GeoPoint(route[k].lat, route[k].lon), pt)
                pj.toPixels(GeoPoint(route[k + 1].lat, route[k + 1].lon), pt2)
                stroke.pathEffect = null
                stroke.color = if (k == activeLeg) 0xFFE040FB.toInt() else 0xFF7B1FA2.toInt()
                stroke.strokeWidth = if (k == activeLeg) 9f else 6f
                c.drawLine(pt.x.toFloat(), pt.y.toFloat(), pt2.x.toFloat(), pt2.y.toFloat(), stroke)
            }
        }

        for (wp in points) {
            if (wp.kind == Waypoint.Kind.AIRPORT && zoom < 8 && wp.type != "large_airport" && wp.type != "medium_airport") continue
            val p = Point(); pj.toPixels(GeoPoint(wp.lat, wp.lon), p)
            drawn.add(wp to p)
            val x = p.x.toFloat(); val y = p.y.toFloat()
            when {
                wp.hasVor -> {
                    stroke.color = 0xFF1565C0.toInt(); stroke.strokeWidth = 4f
                    val path = Path()
                    for (k in 0..5) {
                        val a = Math.toRadians(60.0 * k)
                        val px = x + 18f * Math.cos(a).toFloat(); val py = y + 18f * Math.sin(a).toFloat()
                        if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    path.close(); c.drawPath(path, stroke)
                    if (wp.hasDme) c.drawRect(x - 18f, y - 16f, x + 18f, y + 16f, stroke)
                    fill.color = 0xFF1565C0.toInt(); c.drawCircle(x, y, 4f, fill)
                }
                wp.hasDme -> { stroke.color = 0xFF1565C0.toInt(); c.drawRect(x - 14f, y - 14f, x + 14f, y + 14f, stroke) }
                wp.isNdb -> { fill.color = 0xFF8D6E63.toInt(); c.drawCircle(x, y, 9f, fill) }
                wp.kind == Waypoint.Kind.AIRPORT -> {
                    stroke.color = when {
                        wp.source == Waypoint.SOURCE_USER -> 0xFFC2185B.toInt()
                        wp.type == "heliport" -> 0xFF6D4C41.toInt()
                        else -> 0xFF0D47A1.toInt()
                    }
                    stroke.strokeWidth = 5f
                    val rr = if (wp.type == "large_airport" || wp.type == "medium_airport") 16f else 11f
                    c.drawCircle(x, y, rr, stroke)
                }
            }
            if (zoom >= 9 || (wp.kind == Waypoint.Kind.NAVAID && zoom >= 7.5)) c.drawText(wp.ident, x + 20f, y - 10f, text)
        }

        val n = adf; val ac = aircraft
        if (n != null && ac != null) {
            pj.toPixels(GeoPoint(ac.lat, ac.lon), pt); pj.toPixels(GeoPoint(n.lat, n.lon), pt2)
            stroke.color = 0xFFFF9800.toInt(); stroke.strokeWidth = 4f
            stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
            c.drawLine(pt.x.toFloat(), pt.y.toFloat(), pt2.x.toFloat(), pt2.y.toFloat(), stroke)
            stroke.pathEffect = null
        }

        val t = direct; val a = aircraft
        if (t != null && a != null) {
            pj.toPixels(GeoPoint(a.lat, a.lon), pt); pj.toPixels(GeoPoint(t.lat, t.lon), pt2)
            stroke.color = 0xFFE040FB.toInt(); stroke.strokeWidth = 6f
            c.drawLine(pt.x.toFloat(), pt.y.toFloat(), pt2.x.toFloat(), pt2.y.toFloat(), stroke)
        }
        if (a != null) {
            pj.toPixels(GeoPoint(a.lat, a.lon), pt)
            c.save()
            c.rotate(((a.trackTrue ?: 0.0) - mv.mapOrientation).toFloat(), pt.x.toFloat(), pt.y.toFloat())
            val path = Path().apply {
                moveTo(pt.x.toFloat(), pt.y - 30f)
                lineTo(pt.x + 20f, pt.y + 22f); lineTo(pt.x.toFloat(), pt.y + 12f); lineTo(pt.x - 20f, pt.y + 22f); close()
            }
            fill.color = when (a.source.name) { "GPS" -> 0xFF2E7D32.toInt(); "SIM" -> 0xFF1565C0.toInt(); else -> 0xFFF57C00.toInt() }
            c.drawPath(path, fill)
            stroke.color = android.graphics.Color.WHITE; stroke.strokeWidth = 3f
            c.drawPath(path, stroke)
            c.restore()
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val hit = drawn.minByOrNull { hypot((it.second.x - e.x).toDouble(), (it.second.y - e.y).toDouble()) }
        if (hit != null && hypot((hit.second.x - e.x).toDouble(), (hit.second.y - e.y).toDouble()) <= 60) {
            onTap(hit.first); return true
        }
        // dotknięcie pustego miejsca: pokaż strefy w tym punkcie
        val g = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt())
        onEmptyTap(g.latitude, g.longitude)
        return true
    }
}

@OptIn(FlowPreview::class)
@Composable
fun MapScreen(vm: NavViewModel) {
    val ctx = LocalContext.current
    val pos by vm.position.collectAsState()
    val gps by vm.gpsStatus.collectAsState()
    val direct by vm.direct.collectAsState()
    val adfSt by vm.adf.collectAsState()
    val routePts by vm.route.collectAsState()
    val routeOn by vm.routeActive.collectAsState()
    val legIdx by vm.activeLeg.collectAsState()
    val allZones by vm.zones.collectAsState()
    val showZones by vm.showZones.collectAsState()
    val zoneMaxFt by vm.zoneMaxFt.collectAsState()
    val nav1 by vm.nav[0].collectAsState()
    val nav2 by vm.nav[1].collectAsState()
    val settings by vm.settings.collectAsState()
    val counts by vm.counts.collectAsState()
    var follow by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Waypoint?>(null) }
    var tapPoint by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val allAreas by vm.areas.collectAsState()
    val showAreas by vm.showAreas.collectAsState()
    val areaCats by vm.areaCats.collectAsState()

    val overlay = remember { NavOverlay({ selected = it; tapPoint = null }, { la, lo -> tapPoint = la to lo; selected = null }) }

    val boxFlow = remember { MutableStateFlow<DoubleArray?>(null) }
    val mapView = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(9.0)
            controller.setCenter(GeoPoint(52.1, 19.4))
            overlays.add(overlay)
            addOnFirstLayoutListener { _, _, _, _, _ -> pushBox(this, boxFlow) }
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean { pushBox(this@apply, boxFlow); return false }
                override fun onZoom(event: ZoomEvent?): Boolean { pushBox(this@apply, boxFlow); return false }
            })
        }
    }

    LaunchedEffect(allAreas, showAreas, areaCats, zoneMaxFt) {
        overlay.areas = if (showAreas) vm.visibleAreas(allAreas, areaCats, zoneMaxFt) else emptyList()
        mapView.invalidate()
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onPause(); mapView.onDetach() }
    }

    LaunchedEffect(counts) { if (mapView.width > 0) pushBox(mapView, boxFlow) }

    LaunchedEffect(Unit) {
        boxFlow.debounce(250).collect { b ->
            if (b != null) {
                overlay.points = vm.inBox(b[0], b[1], b[2], b[3])
                mapView.invalidate()
            }
        }
    }

    // strefy: aktywne teraz (czerwone) i później dziś (pomarańczowe, przerywane); odświeżane co minutę
    LaunchedEffect(allZones, showZones, zoneMaxFt) {
        while (true) {
            val now = System.currentTimeMillis()
            overlay.zones = if (!showZones) emptyList() else vm.visibleZones(allZones, zoneMaxFt).mapNotNull { z ->
                val act = z.activeAt(now)
                val r = act ?: z.nextFrom(now)?.takeIf { it.start - now < 12 * 3600_000L } ?: return@mapNotNull null
                Triple(z, act != null, pl.pilot.vornav.nav.ZoneAlerts.altText(r))
            }
            mapView.invalidate()
            kotlinx.coroutines.delay(60_000)
        }
    }

    LaunchedEffect(routePts, routeOn, legIdx) {
        overlay.route = routePts
        overlay.activeLeg = if (routeOn) legIdx else -1
        mapView.invalidate()
    }

    LaunchedEffect(pos, direct, nav1, nav2, follow, settings, adfSt) {
        overlay.aircraft = pos
        overlay.direct = direct
        overlay.adf = adfSt
        overlay.radials = listOfNotNull(
            nav1.station?.takeIf { it.hasVor }?.let { Triple(it, nav1RadialTrue(it, nav1.obs, settings), 0) },
            nav2.station?.takeIf { it.hasVor }?.let { Triple(it, nav1RadialTrue(it, nav2.obs, settings), 1) }
        )
        val p = pos
        if (follow && p != null) mapView.controller.animateTo(GeoPoint(p.lat, p.lon))
        mapView.invalidate()
    }

    Column(Modifier.fillMaxSize()) {
        StatusBar(pos, gps)
        Box(Modifier.weight(1f)) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallFloatingActionButton(
                    onClick = { follow = !follow },
                    containerColor = if (follow) NavGreen else Color(0xFF37474F)
                ) { Icon(Icons.Filled.MyLocation, "Śledź samolot") }
                SmallFloatingActionButton(
                    onClick = { vm.setShowZones(!showZones) },
                    containerColor = if (showZones) ZoneRed else Color(0xFF37474F)
                ) { Icon(Icons.Filled.Layers, "Strefy") }
                SmallFloatingActionButton(onClick = { mapView.controller.zoomIn() }) { Text("+") }
                SmallFloatingActionButton(onClick = { mapView.controller.zoomOut() }) { Text("−") }
            }
            Box(Modifier.align(Alignment.TopCenter).padding(start = 8.dp, end = 64.dp, top = 64.dp)) { ZoneAlertBanner(vm) }
            if (direct != null && pos != null) {
                val bd = brgDist(pos, direct!!)
                Row(
                    Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0xCC07111C), RoundedCornerShape(8.dp)).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Readout("D→ ${direct!!.ident}", fmtDeg(bd?.first))
                    Readout("NM", fmtNm(bd?.second))
                }
            }
            Text(
                "© OpenStreetMap contributors",
                fontSize = 10.sp,
                color = Color.DarkGray,
                modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99FFFFFF)).padding(2.dp)
            )
            tapPoint?.let { (la, lo) ->
                val now = System.currentTimeMillis()
                val inStatic = remember(la, lo, allAreas, areaCats) {
                    allAreas.filter { (it.category in areaCats || it.category == pl.pilot.vornav.data.AspCategory.FIR) && it.contains(la, lo) }
                        .sortedBy { it.lowerFt }
                }
                val inAup = remember(la, lo, allZones) { allZones.filter { it.contains(la, lo) } }
                Surface(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp),
                    shape = RoundedCornerShape(12.dp), color = PanelBg, tonalElevation = 6.dp
                ) {
                    Box {
                        Column(
                            Modifier.padding(12.dp).heightIn(max = 300.dp)
                                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("${Geo.formatLat(la)} ${Geo.formatLon(lo)}", fontSize = 12.sp, color = Color(0xFF8FA3B8))
                            if (inStatic.isEmpty() && inAup.isEmpty()) Text("Brak stref w tym punkcie (w pobranych danych)", fontSize = 13.sp)
                            inAup.forEach { z ->
                                val r = z.activeAt(now) ?: z.nextFrom(now)
                                if (r != null) Text(
                                    "AUP ${z.designator}: ${pl.pilot.vornav.nav.ZoneAlerts.altText(r)} • ${pl.pilot.vornav.nav.ZoneAlerts.timeText(r)}" +
                                        if (z.activeAt(now) != null) " • AKTYWNA" else "",
                                    fontSize = 13.sp, color = if (z.activeAt(now) != null) ZoneRed else ZoneOrange
                                )
                            }
                            inStatic.forEach { a ->
                                Text("${a.name} • ${a.label} • ${a.lowerText}–${a.upperText}", fontSize = 13.sp, color = Color(a.category.color))
                            }
                        }
                        IconButton(onClick = { tapPoint = null }, modifier = Modifier.align(Alignment.TopEnd)) {
                            Icon(Icons.Filled.Close, "Zamknij")
                        }
                    }
                }
            }
            selected?.let { wp ->
                Surface(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp),
                    shape = RoundedCornerShape(12.dp), color = PanelBg, tonalElevation = 6.dp
                ) {
                    Box {
                        WaypointActions(wp, vm) { selected = null }
                        IconButton(onClick = { selected = null }, modifier = Modifier.align(Alignment.TopEnd)) {
                            Icon(Icons.Filled.Close, "Zamknij")
                        }
                    }
                }
            }
        }
    }
}

private fun nav1RadialTrue(st: Waypoint, obs: Double, s: pl.pilot.vornav.nav.NavSettings): Double =
    Mag.magToTrue(obs, VorReceiver.stationVariation(st, s))

private fun pushBox(mv: MapView, flow: MutableStateFlow<DoubleArray?>) {
    val b = mv.boundingBox
    flow.value = doubleArrayOf(b.latSouth, b.latNorth, b.lonWest, b.lonEast)
}
