package pl.pilot.vornav.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
class NavOverlay(private val onTap: (Waypoint) -> Unit) : Overlay() {
    var points: List<Waypoint> = emptyList()
    var aircraft: Position? = null
    var direct: Waypoint? = null
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
        val hit = drawn.minByOrNull { hypot((it.second.x - e.x).toDouble(), (it.second.y - e.y).toDouble()) } ?: return false
        if (hypot((hit.second.x - e.x).toDouble(), (hit.second.y - e.y).toDouble()) > 60) return false
        onTap(hit.first); return true
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
    val nav1 by vm.nav[0].collectAsState()
    val nav2 by vm.nav[1].collectAsState()
    val settings by vm.settings.collectAsState()
    val counts by vm.counts.collectAsState()
    var follow by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Waypoint?>(null) }

    val overlay = remember { NavOverlay { selected = it } }
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
                SmallFloatingActionButton(onClick = { mapView.controller.zoomIn() }) { Text("+") }
                SmallFloatingActionButton(onClick = { mapView.controller.zoomOut() }) { Text("−") }
            }
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
