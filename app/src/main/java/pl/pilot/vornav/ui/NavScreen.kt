package pl.pilot.vornav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import pl.pilot.vornav.loc.Position
import pl.pilot.vornav.nav.Geo
import pl.pilot.vornav.nav.Mag
import pl.pilot.vornav.nav.NavSettings
import pl.pilot.vornav.nav.VorReceiver
import pl.pilot.vornav.nav.AdfReceiver
import pl.pilot.vornav.nav.HdgSource

@Composable
fun StatusBar(pos: Position?, gps: String) {
    val trkMag = pos?.trackTrue?.let { Mag.trueToMag(it, Mag.declination(pos.lat, pos.lon, pos.altFt)) }
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF07111C)).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        val srcColor = when (pos?.source?.name) {
            "GPS" -> NavGreen; "GPS_CORR" -> Amber; "SIM" -> Color(0xFF64B5F6); null -> Color.Red; else -> Color(0xFFFF7043)
        }
        Text(pos?.source?.label ?: "BRAK", color = srcColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Readout("GS kt", pos?.let { "%.0f".format(it.gsKt) } ?: "--")
        Readout("TRK M", fmtDeg(trkMag))
        Readout("ALT ft", pos?.let { "%.0f".format(it.altFt) } ?: "--")
    }
    if (pos == null) Text(gps, fontSize = 12.sp, color = Color(0xFFFF7043), modifier = Modifier.padding(horizontal = 8.dp))
}

@Composable
fun NavScreen(vm: NavViewModel) {
    val pos by vm.position.collectAsState()
    val gps by vm.gpsStatus.collectAsState()
    val settings by vm.settings.collectAsState()
    var tuneSlot by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxSize()) {
        StatusBar(pos, gps)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ZoneAlertBanner(vm)
            AirspaceHereLine(vm)
            RoutePanel(vm, pos)
            DirectPanel(vm, pos)
            for (i in 0..1) NavPanel(i, vm, pos, settings) { tuneSlot = i }
            AdfPanel(vm, pos, settings) { tuneSlot = ADF_SLOT }
        }
    }
    tuneSlot?.let { TuneDialog(it, vm) { tuneSlot = null } }
}

@Composable
private fun NavPanel(i: Int, vm: NavViewModel, pos: Position?, settings: NavSettings, onTune: () -> Unit) {
    val slot by vm.nav[i].collectAsState()
    val st = slot.station
    val reading = remember(st, pos, slot.obs, settings) {
        if (st != null && pos != null) VorReceiver.compute(st, pos, slot.obs, settings) else null
    }
    Column(Modifier.fillMaxWidth().background(PanelBg, RoundedCornerShape(12.dp)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("NAV${i + 1}", fontWeight = FontWeight.Bold, color = Amber, fontSize = 16.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    st?.let { "${it.freqText}  ${it.ident}" } ?: "nie nastrojony",
                    fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Bold
                )
                if (st != null) Text("${st.name} • ${st.type}" + (st.dmeChannel?.let { " • CH $it" } ?: ""), fontSize = 12.sp, color = Color(0xFF8FA3B8), maxLines = 1)
            }
            FilledTonalButton(onClick = onTune) { Text("Strój") }
        }
        if (st == null) return@Column
        if (reading != null && !reading.inRange) {
            Text(
                "Poza zasięgiem: ${fmtNm(reading.groundDistNm)} NM (zasięg ok. ${reading.rangeNm.toInt()} NM na tej wysokości)",
                color = Color(0xFFFF7043), fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (st.hasVor) {
                CdiIndicator(slot.obs, reading, Modifier.weight(1.1f))
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (st.hasVor) {
                    Readout("OBS", fmtDeg(slot.obs), color = Magenta)
                    Readout("RADIAL (QDR)", fmtDeg(reading?.radialFrom))
                    Readout("DO STACJI (QDM)", fmtDeg(reading?.bearingToMag))
                }
                if (st.hasDme) {
                    Readout("DME NM", if (reading?.dmeValid == true) fmtNm(reading?.dmeNm) else "-.--", color = Amber)
                    Readout("GS / TTS", "${reading?.dmeGsKt?.let { "%.0f".format(kotlin.math.abs(it)) } ?: "--"}  ${fmtMin(reading?.ttsMin)}")
                }
            }
        }
        if (st.hasVor) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (d in listOf(-10, -1)) OutlinedButton(onClick = { vm.setObs(i, slot.obs + d) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("$d") }
                Button(onClick = { vm.centerObs(i) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("CDI→TO") }
                for (d in listOf(1, 10)) OutlinedButton(onClick = { vm.setObs(i, slot.obs + d) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("+$d") }
            }
        }
    }
}

/** Aktywny odcinek trasy: instrukcja radialowa, XTK, odległość do końca odcinka. */
@Composable
private fun RoutePanel(vm: NavViewModel, pos: Position?) {
    val active by vm.routeActive.collectAsState()
    val legs by vm.legs.collectAsState()
    val idx by vm.activeLeg.collectAsState()
    if (!active) return
    val leg = legs.getOrNull(idx) ?: return
    val xa = pos?.let { pl.pilot.vornav.nav.RoutePlanner.crossTrack(leg, it) }
    val toGo = pos?.let { Geo.distanceNm(it.lat, it.lon, leg.to.lat, leg.to.lon) }
    Column(
        Modifier.fillMaxWidth().background(PanelBg, RoundedCornerShape(12.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TRASA ${idx + 1}/${legs.size}", color = Magenta, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Text("${leg.from.ident} → ${leg.to.ident}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.prevLeg() }, enabled = idx > 0, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("◀") }
            TextButton(onClick = { vm.nextLeg() }, enabled = idx < legs.size - 1, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("▶") }
        }
        Text(leg.navText, fontSize = 13.sp, color = if (leg.navStation != null) NavGreen else Color(0xFFFF7043))
        Row {
            Readout("KURS M", "${pl.pilot.vornav.nav.RoutePlanner.fmt3(leg.courseMag)}°", Modifier.weight(1f))
            val xtk = xa?.first
            Readout(
                "XTK NM",
                xtk?.let { (if (kotlin.math.abs(it) < 0.05) "" else if (it > 0) "P " else "L ") + fmtNm(kotlin.math.abs(it)) } ?: "--",
                Modifier.weight(1f),
                color = if (xtk != null && kotlin.math.abs(xtk) > 1.0) Amber else NavGreen
            )
            Readout("DO PKT NM", fmtNm(toGo), Modifier.weight(1f))
        }
        if (xa != null && kotlin.math.abs(xa.first) > 0.3) Text(
            if (xa.first > 0) "Jesteś na prawo od linii — koryguj w lewo" else "Jesteś na lewo od linii — koryguj w prawo",
            fontSize = 11.sp, color = Amber
        )
        leg.checkpoint?.let { Text("Kontrola: $it", fontSize = 11.sp, color = Color(0xFFB0BEC5)) }
    }
}

@Composable
private fun AdfPanel(vm: NavViewModel, pos: Position?, settings: NavSettings, onTune: () -> Unit) {
    val st by vm.adf.collectAsState()
    val hdg by vm.manualHdg.collectAsState()
    val s = st
    val reading = remember(s, pos, hdg, settings) {
        if (s != null && pos != null) AdfReceiver.compute(s, pos, hdg, settings) else null
    }
    Column(Modifier.fillMaxWidth().background(PanelBg, RoundedCornerShape(12.dp)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ADF", fontWeight = FontWeight.Bold, color = NdbBrown, fontSize = 16.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    s?.let { "${it.freqKhz} kHz  ${it.ident}" } ?: "nie nastrojony",
                    fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Bold
                )
                if (s != null) Text("${s.name} • ${s.type}", fontSize = 12.sp, color = Color(0xFF8FA3B8), maxLines = 1)
            }
            FilledTonalButton(onClick = onTune) { Text("Strój") }
        }
        if (s == null) return@Column
        if (reading != null && !reading.inRange) {
            Text(
                "Poza zasięgiem: ${fmtNm(reading.distNm)} NM (zasięg NDB ok. ${reading.rangeNm.toInt()} NM)",
                color = Color(0xFFFF7043), fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AdfIndicator(reading, Modifier.weight(1.1f))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Readout("QDM (na NDB)", fmtDeg(reading?.qdmMag), color = NdbBrown)
                Readout("QDR (od NDB)", fmtDeg(reading?.qdrMag))
                Readout("KĄT KURS. RB", fmtDeg(reading?.relBearing))
                Readout(
                    if (s.hasDme) "DME NM" else "ODL. NM (GPS)",
                    fmtNm(reading?.dmeNm ?: reading?.distNm), color = Amber
                )
                Readout("TTS", fmtMin(reading?.ttsMin))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            when (reading?.headingSource) {
                HdgSource.MANUAL -> "Kurs: HDG ${fmtDeg(hdg)} wpisany ręcznie (jak prawdziwy ADF)"
                HdgSource.TRACK -> "Kurs: TRK z GPS — przy wietrze RB różni się od ADF o kąt znoszenia"
                else -> "Brak kursu — wskazówka pokazuje QDM (tryb RMI, północ u góry)"
            },
            fontSize = 11.sp, color = Color(0xFF8FA3B8)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (hdg == null) {
                OutlinedButton(onClick = { vm.syncHeadingToTrack() }) { Text("Wpisz HDG z żyrobusoli") }
            } else {
                for (d in listOf(-10, -1)) OutlinedButton(onClick = { vm.setHeading(hdg!! + d) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("$d") }
                TextButton(onClick = { vm.setHeading(null) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("TRK") }
                for (d in listOf(1, 10)) OutlinedButton(onClick = { vm.setHeading(hdg!! + d) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("+$d") }
            }
        }
    }
}

@Composable
private fun DirectPanel(vm: NavViewModel, pos: Position?) {
    val target by vm.direct.collectAsState()
    val t = target ?: return
    val bd = brgDist(pos, t)
    val trkMag = pos?.trackTrue?.let { Mag.trueToMag(it, Mag.declination(pos.lat, pos.lon, pos.altFt)) }
    val rel = if (bd != null && trkMag != null && pos != null && pos.gsKt > 3) Geo.norm180(bd.first - trkMag) else null
    val ete = if (bd != null && pos != null && pos.gsKt > 5) bd.second / pos.gsKt * 60.0 else null
    val eta = ete?.let {
        val ms = System.currentTimeMillis() + (it * 60000).toLong()
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms)) + "Z"
    }
    Column(Modifier.fillMaxWidth().background(PanelBg, RoundedCornerShape(12.dp)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("D→", fontWeight = FontWeight.Bold, color = NavGreen, fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(t.ident, fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(t.name, fontSize = 12.sp, color = Color(0xFF8FA3B8), maxLines = 1)
            }
            TextButton(onClick = { vm.directTo(null) }) { Text("Usuń") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RelativeArrow(rel, Modifier.width(96.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row { Readout("BRG M", fmtDeg(bd?.first), Modifier.weight(1f)); Readout("DIST NM", fmtNm(bd?.second), Modifier.weight(1f)) }
                Row { Readout("ETE", fmtMin(ete), Modifier.weight(1f)); Readout("ETA", eta ?: "--:--", Modifier.weight(1f)) }
                if (rel != null) Text(
                    if (kotlin.math.abs(rel) < 2) "Na kursie" else if (rel > 0) "Skręć w prawo ${fmtDeg(kotlin.math.abs(rel)).trimStart('0')}" else "Skręć w lewo ${fmtDeg(kotlin.math.abs(rel)).trimStart('0')}",
                    fontSize = 12.sp, color = Amber
                )
            }
        }
    }
}
