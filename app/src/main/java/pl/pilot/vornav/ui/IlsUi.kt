package pl.pilot.vornav.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.pilot.vornav.data.Runway
import pl.pilot.vornav.data.Waypoint
import pl.pilot.vornav.loc.Position
import pl.pilot.vornav.nav.TrainerMath

fun ilsReading(rw: Runway, p: Position): TrainerMath.IlsReading =
    TrainerMath.ils(rw.lat, rw.lon, rw.elevFt, rw.hdgTrue, rw.lengthFt, p.lat, p.lon, p.altFt, p.gsKt)

/** Wskaźnik ILS: pionowa wskazówka LOC (lewo/prawo) i pozioma wskazówka GS (góra/dół), po 5 kropek. */
@Composable
fun IlsIndicator(r: TrainerMath.IlsReading?, modifier: Modifier = Modifier) {
    Canvas(modifier.aspectRatio(1f)) {
        val rr = size.minDimension / 2f
        val c = center
        drawCircle(Color(0xFF07111C), rr, c)
        drawCircle(Color(0xFF5A6B7D), rr, c, style = Stroke(3f))
        val dot = rr * 0.15f
        for (k in -5..5) {
            if (k == 0) continue
            drawCircle(Color.White, rr * 0.03f, Offset(c.x + k * dot, c.y), style = Stroke(2f))
            drawCircle(Color.White, rr * 0.03f, Offset(c.x, c.y + k * dot), style = Stroke(2f))
        }
        drawLine(Amber, Offset(c.x - rr * 0.25f, c.y), Offset(c.x + rr * 0.25f, c.y), 3f)
        if (r != null && r.locValid) {
            val x = c.x + (r.locDevDeg / TrainerMath.LOC_FULL_SCALE).coerceIn(-1.0, 1.0).toFloat() * 5 * dot
            drawLine(NavGreen, Offset(x, c.y - rr * 0.8f), Offset(x, c.y + rr * 0.8f), rr * 0.05f)
        } else {
            drawRect(ZoneRed, Offset(c.x - rr * 0.7f, c.y - rr * 0.55f), Size(rr * 0.35f, rr * 0.16f))
        }
        if (r != null && r.gsValid) {
            // GS: + (jesteś poniżej) → wskazówka w górę
            val y = c.y - (r.gsDevDeg / TrainerMath.GS_FULL_SCALE).coerceIn(-1.0, 1.0).toFloat() * 5 * dot
            drawLine(NavGreen, Offset(c.x - rr * 0.8f, y), Offset(c.x + rr * 0.8f, y), rr * 0.05f)
        } else {
            drawRect(ZoneRed, Offset(c.x + rr * 0.35f, c.y + rr * 0.4f), Size(rr * 0.35f, rr * 0.16f))
        }
    }
}

/** Panel ILS w zakładce NAV: wybór lotniska i pasa, wskazania LOC/GS, wysokość na ścieżce, VS. */
@Composable
fun IlsPanel(vm: NavViewModel, pos: Position?) {
    val rw by vm.ils.collectAsState()
    var pickAirport by remember { mutableStateOf(false) }
    var airport by remember { mutableStateOf<Waypoint?>(null) }
    var runways by remember { mutableStateOf<List<Runway>>(emptyList()) }
    LaunchedEffect(airport) { airport?.let { runways = vm.runwaysOf(it) } }

    Column(Modifier.fillMaxWidth().background(PanelBg, RoundedCornerShape(12.dp)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ILS", fontWeight = FontWeight.Bold, color = NavGreen, fontSize = 16.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    rw?.let { "${it.airport} RWY ${it.ident}" } ?: "nie wybrany",
                    fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Bold
                )
                rw?.let { Text("Kurs LOC ${fmtDeg(vm.ilsCourseMag(it))} • ścieżka 3° • próg ${it.elevFt.toInt()} ft", fontSize = 12.sp, color = Color(0xFF8FA3B8)) }
            }
            FilledTonalButton(onClick = { pickAirport = true }) { Text("Wybierz") }
            if (rw != null) TextButton(onClick = { vm.tuneIls(null) }) { Text("✕") }
        }
        if (airport != null && runways.isNotEmpty()) {
            Text("Pasy ${airport!!.ident}:", fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                runways.forEach { r ->
                    FilterChip(rw?.key == r.key, { vm.tuneIls(r); airport = null }, { Text(r.ident) })
                }
            }
        } else if (airport != null) {
            Text("Brak danych pasów dla ${airport!!.ident} — zaktualizuj bazę w zakładce Baza.", fontSize = 12.sp, color = ZoneOrange)
        }
        val r = rw
        if (r != null) {
            val reading = remember(r, pos) { pos?.let { ilsReading(r, it) } }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IlsIndicator(reading, Modifier.weight(1.1f))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Readout("DO PROGU NM", fmtNm(reading?.distThrNm), color = Amber)
                    Readout("WYS. NA ŚCIEŻCE", reading?.let { "${(Math.round(it.glideAltFt / 10) * 10)}" } ?: "--")
                    Readout("TWOJA WYS.", pos?.let { "${it.altFt.toInt()}" } ?: "--",
                        color = if (reading != null && pos != null && pos.altFt < reading.glideAltFt - 150) ZoneRed else NavGreen)
                    Readout("VS NA 3° fpm", reading?.let { "-${it.vsForGsFpm.toInt()}" } ?: "--")
                }
            }
            Text(
                "Syntetyczny ILS liczony z GPS i danych pasa (oś pasa, ścieżka 3°) — do treningu i orientacji, nie do podejść w IMC.",
                fontSize = 11.sp, color = Color(0xFF8FA3B8)
            )
        }
    }
    if (pickAirport) WaypointPickerDialog("Lotnisko z ILS", vm, { if (it.kind == Waypoint.Kind.AIRPORT) airport = it }) { pickAirport = false }
}
