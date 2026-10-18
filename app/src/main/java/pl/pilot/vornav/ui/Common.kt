package pl.pilot.vornav.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.pilot.vornav.data.Waypoint
import pl.pilot.vornav.loc.Position
import pl.pilot.vornav.nav.Geo
import pl.pilot.vornav.nav.Mag
import java.util.Locale

fun fmtDeg(d: Double?): String {
    if (d == null) return "---°"
    var v = Math.round(Geo.norm360(d)).toInt() % 360
    if (v == 0) v = 360
    return String.format(Locale.US, "%03d°", v)
}

fun fmtNm(d: Double?): String = when {
    d == null -> "--.-"
    d < 10 -> String.format(Locale.US, "%.2f", d)
    d < 100 -> String.format(Locale.US, "%.1f", d)
    else -> String.format(Locale.US, "%.0f", d)
}

fun fmtMin(m: Double?): String {
    if (m == null || m.isInfinite() || m.isNaN() || m > 999) return "--:--"
    val total = Math.round(m * 60).toInt()
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}

/** Kurs magnetyczny i odległość z pozycji do punktu. */
fun brgDist(pos: Position?, wp: Waypoint): Pair<Double, Double>? {
    pos ?: return null
    val t = Geo.bearingTrue(pos.lat, pos.lon, wp.lat, wp.lon)
    val d = Geo.distanceNm(pos.lat, pos.lon, wp.lat, wp.lon)
    return Mag.trueToMag(t, Mag.declination(pos.lat, pos.lon, pos.altFt)) to d
}

@Composable
fun Readout(label: String, value: String, modifier: Modifier = Modifier, color: Color = NavGreen) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = Color(0xFF8FA3B8))
        Text(value, fontSize = 22.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
fun WaypointRow(wp: Waypoint, pos: Position?, onClick: () -> Unit) {
    val bd = brgDist(pos, wp)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(wp.ident, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 17.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    wp.typeLabel + if (wp.freqText.isNotEmpty()) "  ${wp.freqText}" else "",
                    fontSize = 12.sp,
                    color = if (wp.kind == Waypoint.Kind.NAVAID) NavGreen else if (wp.source == Waypoint.SOURCE_USER) Magenta else Color(0xFF64B5F6)
                )
            }
            Text(wp.name, fontSize = 13.sp, maxLines = 1, color = Color(0xFFB0BEC5))
        }
        if (bd != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(fmtDeg(bd.first), fontFamily = FontFamily.Monospace)
                Text("${fmtNm(bd.second)} NM", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFF8FA3B8))
            }
        }
    }
}

/** Akcje dla wybranego punktu: Direct-To, strojenie NAV1/NAV2, korekta „jestem nad”. */
@Composable
fun WaypointActions(wp: Waypoint, vm: NavViewModel, onDone: () -> Unit) {
    var confirmOverfly by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text("${wp.ident} — ${wp.name}", fontWeight = FontWeight.Bold)
        Text(
            "${wp.typeLabel} ${wp.freqText}  ${Geo.formatLat(wp.lat)} ${Geo.formatLon(wp.lon)}  ${wp.elevFt.toInt()} ft" +
                (wp.dmeChannel?.let { "  DME $it" } ?: ""),
            fontSize = 12.sp, color = Color(0xFFB0BEC5)
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.directTo(wp); onDone() }) { Text("D→ Direct") }
            if (wp.isTunable) {
                OutlinedButton(onClick = { vm.tune(0, wp); onDone() }) { Text("NAV1") }
                OutlinedButton(onClick = { vm.tune(1, wp); onDone() }) { Text("NAV2") }
            }
            if (wp.isAdfTunable) {
                OutlinedButton(onClick = { vm.tuneAdf(wp); onDone() }) { Text("ADF") }
            }
            OutlinedButton(onClick = { confirmOverfly = true }) { Text("Jestem nad") }
        }
    }
    if (confirmOverfly) {
        AlertDialog(
            onDismissRequest = { confirmOverfly = false },
            title = { Text("Korekta pozycji") },
            text = { Text("Ustawić pozycję samolotu dokładnie nad ${wp.ident}? GPS zostanie przesunięty o różnicę.") },
            confirmButton = { TextButton(onClick = { vm.overfly(wp); confirmOverfly = false; onDone() }) { Text("Tak, jestem nad") } },
            dismissButton = { TextButton(onClick = { confirmOverfly = false }) { Text("Anuluj") } }
        )
    }
}

const val ADF_SLOT = 2

/**
 * Okno strojenia: NAV1/NAV2 — częstotliwość (114.90) lub ident VOR/DME;
 * ADF (slot = ADF_SLOT) — częstotliwość w kHz (375) lub ident NDB.
 */
@Composable
fun TuneDialog(slot: Int, vm: NavViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Waypoint>>(emptyList()) }
    val pos by vm.position.collectAsState()
    val isAdf = slot == ADF_SLOT
    val ok: (Waypoint) -> Boolean = if (isAdf) { w -> w.isAdfTunable } else { w -> w.isTunable }
    LaunchedEffect(text) {
        val t = text.trim()
        results = when {
            t.isEmpty() -> vm.nearest(ok)
            !isAdf && Regex("^1[01][0-9]([.,][0-9]{0,2})?$").matches(t) && t.length >= 5 -> vm.findByFrequency(t)
            isAdf && Regex("^[0-9]{3,4}([.,][0-9])?$").matches(t) -> vm.findNdbByFrequency(t)
            else -> vm.search(t).filter(ok)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isAdf) "Strój ADF" else "Strój NAV${slot + 1}") },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    label = { Text(if (isAdf) "Częstotliwość kHz (375) lub ident" else "Częstotliwość (114.90) lub ident") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(if (text.isBlank()) if (isAdf) "Najbliższe NDB" else "Najbliższe stacje VOR/DME" else "${results.size} wyników", fontSize = 12.sp, color = Color(0xFF8FA3B8))
                // zwykła przewijana kolumna (LazyColumn w AlertDialog bywa problematyczny)
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    results.take(60).forEach { wp ->
                        WaypointRow(wp, pos) { if (isAdf) vm.tuneAdf(wp) else vm.tune(slot, wp); onDismiss() }
                        HorizontalDivider(color = Color(0xFF1E2E40))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zamknij") } }
    )
}
