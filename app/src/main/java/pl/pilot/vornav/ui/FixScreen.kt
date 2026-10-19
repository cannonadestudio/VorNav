package pl.pilot.vornav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.pilot.vornav.data.parseCoord
import pl.pilot.vornav.nav.Geo
import pl.pilot.vornav.nav.VorReceiver
import pl.pilot.vornav.nav.AdfReceiver
import pl.pilot.vornav.nav.Mag
import java.util.Locale

private fun num(s: String) = s.trim().replace(',', '.').toDoubleOrNull()

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(PanelBg, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, fontWeight = FontWeight.Bold, color = Amber)
        content()
    }
}

@Composable
private fun NumField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) =
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )

/** Korekta pozycji GPS: z odczytu VOR/DME, z dwóch radiali, ręcznie; wysokość; symulacja. */
@Composable
fun FixScreen(vm: NavViewModel) {
    val pos by vm.position.collectAsState()
    val settings by vm.settings.collectAsState()
    val nav1 by vm.nav[0].collectAsState()
    val nav2 by vm.nav[1].collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val corr = remember(pos, refresh) { vm.engine.correctionVector() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

        Section("Bieżąca pozycja") {
            val p = pos
            if (p == null) Text("Brak pozycji", color = Color(0xFFFF7043))
            else {
                Text("${Geo.formatLat(p.lat)}  ${Geo.formatLon(p.lon)}", fontSize = 18.sp)
                Text("Źródło: ${p.source.label}" + (p.accuracyM?.let { "  •  dokładność GPS ±${it.toInt()} m" } ?: ""), fontSize = 13.sp)
            }
            if (corr != null) {
                Text("Korekta: ${fmtNm(corr.first)} NM w kierunku ${fmtDeg(corr.second)} (rzecz.)", color = Amber)
                OutlinedButton(onClick = { vm.clearCorrection(); refresh++ }) { Text("Usuń korektę — czysty GPS") }
            }
        }

        // --- fix radial + DME ---
        Section("Fix z VOR/DME (odczyt z przyrządu w samolocie)") {
            var slot by remember { mutableIntStateOf(0) }
            val st = (if (slot == 0) nav1 else nav2).station
            var radial by remember(st) { mutableStateOf("") }
            var dme by remember(st) { mutableStateOf("") }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(slot == 0, { slot = 0 }, { Text("NAV1 ${nav1.station?.ident ?: "-"}") })
                FilterChip(slot == 1, { slot = 1 }, { Text("NAV2 ${nav2.station?.ident ?: "-"}") })
            }
            if (st == null || !st.hasVor || !st.hasDme) {
                Text("Nastrój na tym odbiorniku stację VOR-DME/VORTAC.", fontSize = 12.sp, color = Color(0xFF8FA3B8))
            } else {
                TextButton(onClick = {
                    pos?.let { p ->
                        val r = VorReceiver.compute(st, p, 0.0, settings)
                        radial = r.radialFrom?.let { String.format(Locale.US, "%.0f", it) } ?: ""
                        dme = r.dmeNm?.let { String.format(Locale.US, "%.1f", it) } ?: ""
                    }
                }) { Text("Wstaw obecne wartości z GPS (do poprawienia)") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField(radial, { radial = it }, "Radial ° (QDR)", Modifier.weight(1f))
                    NumField(dme, { dme = it }, "DME NM", Modifier.weight(1f))
                }
                val r = num(radial); val d = num(dme)
                val fix = if (r != null && d != null) VorReceiver.fixFromRadialDme(st, r, d, pos?.altFt ?: 0.0, settings) else null
                fix?.let { f ->
                    val shift = pos?.let { Geo.distanceNm(it.lat, it.lon, f.first, f.second) }
                    Text("Fix: ${Geo.formatLat(f.first)} ${Geo.formatLon(f.second)}" + (shift?.let { "  (różnica ${fmtNm(it)} NM)" } ?: ""), fontSize = 13.sp)
                    Button(onClick = { vm.applyFix(f.first, f.second, "${st.ident} R${r?.toInt()} D$d"); refresh++ }) { Text("Zastosuj korektę") }
                }
            }
        }

        // --- fix z dwóch radiali ---
        Section("Fix z dwóch radiali (NAV1 × NAV2)") {
            val s1 = nav1.station; val s2 = nav2.station
            var r1 by remember(s1) { mutableStateOf("") }
            var r2 by remember(s2) { mutableStateOf("") }
            if (s1 == null || s2 == null || !s1.hasVor || !s2.hasVor) {
                Text("Nastrój dwie różne stacje VOR na NAV1 i NAV2.", fontSize = 12.sp, color = Color(0xFF8FA3B8))
            } else {
                TextButton(onClick = {
                    pos?.let { p ->
                        r1 = VorReceiver.compute(s1, p, 0.0, settings).radialFrom?.let { String.format(Locale.US, "%.0f", it) } ?: ""
                        r2 = VorReceiver.compute(s2, p, 0.0, settings).radialFrom?.let { String.format(Locale.US, "%.0f", it) } ?: ""
                    }
                }) { Text("Wstaw obecne wartości z GPS") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField(r1, { r1 = it }, "Radial ${s1.ident}", Modifier.weight(1f))
                    NumField(r2, { r2 = it }, "Radial ${s2.ident}", Modifier.weight(1f))
                }
                val a = num(r1); val b = num(r2)
                if (a != null && b != null) {
                    val fix = VorReceiver.fixFromTwoRadials(s1, a, s2, b, settings)
                    if (fix == null) Text("Radiale się nie przecinają (sprawdź wartości)", color = Color(0xFFFF7043), fontSize = 13.sp)
                    else {
                        val shift = pos?.let { Geo.distanceNm(it.lat, it.lon, fix.first, fix.second) }
                        Text("Fix: ${Geo.formatLat(fix.first)} ${Geo.formatLon(fix.second)}" + (shift?.let { "  (różnica ${fmtNm(it)} NM)" } ?: ""), fontSize = 13.sp)
                        Text("Uwaga: przecięcie pod ostrym kątem (<30°) daje mało dokładny fix.", fontSize = 11.sp, color = Color(0xFF8FA3B8))
                        Button(onClick = { vm.applyFix(fix.first, fix.second, "${s1.ident}/${s2.ident}"); refresh++ }) { Text("Zastosuj korektę") }
                    }
                }
            }
        }

        // --- NDB × VOR ---
        Section("Fix z namiaru NDB (QDM z ADF) × radial VOR") {
            val adfSt by vm.adf.collectAsState()
            val ndb = adfSt
            var vSlot by remember { mutableIntStateOf(0) }
            val vor = (if (vSlot == 0) nav1 else nav2).station
            var qdm by remember(ndb) { mutableStateOf("") }
            var rad by remember(vor) { mutableStateOf("") }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(vSlot == 0, { vSlot = 0 }, { Text("NAV1 ${nav1.station?.ident ?: "-"}") })
                FilterChip(vSlot == 1, { vSlot = 1 }, { Text("NAV2 ${nav2.station?.ident ?: "-"}") })
            }
            if (ndb == null || vor == null || !vor.hasVor) {
                Text("Nastrój NDB w ADF i stację VOR na wybranym NAV.", fontSize = 12.sp, color = Color(0xFF8FA3B8))
            } else {
                Text("QDM = HDG + kąt kursowy ADF (RB)", fontSize = 11.sp, color = Color(0xFF8FA3B8))
                TextButton(onClick = {
                    pos?.let { p ->
                        qdm = String.format(Locale.US, "%.0f", AdfReceiver.compute(ndb, p, null, settings).qdmMag)
                        rad = VorReceiver.compute(vor, p, 0.0, settings).radialFrom?.let { String.format(Locale.US, "%.0f", it) } ?: ""
                    }
                }) { Text("Wstaw obecne wartości z GPS") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField(qdm, { qdm = it }, "QDM ${ndb.ident}", Modifier.weight(1f))
                    NumField(rad, { rad = it }, "Radial ${vor.ident}", Modifier.weight(1f))
                }
                val q = num(qdm); val rv = num(rad)
                if (q != null && rv != null) {
                    // QDR z NDB liczony z deklinacją lokalną przy NDB
                    val ndbDecl = Mag.declination(ndb.lat, ndb.lon, ndb.elevFt)
                    val fix = Geo.intersection(
                        ndb.lat, ndb.lon, Mag.magToTrue(q + 180.0, ndbDecl),
                        vor.lat, vor.lon, Mag.magToTrue(rv, VorReceiver.stationVariation(vor, settings))
                    )
                    if (fix == null) Text("Linie pozycyjne się nie przecinają (sprawdź wartości)", color = Color(0xFFFF7043), fontSize = 13.sp)
                    else {
                        val shift = pos?.let { Geo.distanceNm(it.lat, it.lon, fix.first, fix.second) }
                        Text("Fix: ${Geo.formatLat(fix.first)} ${Geo.formatLon(fix.second)}" + (shift?.let { "  (różnica ${fmtNm(it)} NM)" } ?: ""), fontSize = 13.sp)
                        Button(onClick = { vm.applyFix(fix.first, fix.second, "${ndb.ident}/${vor.ident}"); refresh++ }) { Text("Zastosuj korektę") }
                    }
                }
            }
        }

        // --- ręcznie ---
        Section("Ręczne współrzędne") {
            var la by remember { mutableStateOf("") }
            var lo by remember { mutableStateOf("") }
            OutlinedTextField(la, { la = it }, label = { Text("Szerokość (52.2297 lub N52°13'47\")") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(lo, { lo = it }, label = { Text("Długość (21.0122 lub E021°00'44\")") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val a = parseCoord(la); val b = parseCoord(lo)
            Button(enabled = a != null && b != null, onClick = { vm.applyFix(a!!, b!!, "ręcznie"); refresh++ }) { Text("Ustaw pozycję") }
            Text("Przelot nad punktem: wybierz punkt na mapie lub w „Szukaj” → „Jestem nad”.", fontSize = 12.sp, color = Color(0xFF8FA3B8))
        }

        // --- wysokość ---
        Section("Wysokość (do obliczania DME)") {
            var manual by remember { mutableStateOf(vm.engine.manualAltFt != null) }
            var alt by remember { mutableStateOf(vm.engine.manualAltFt?.toInt()?.toString() ?: "") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(manual, { manual = it; if (!it) vm.engine.manualAltFt = null else vm.engine.manualAltFt = num(alt) })
                Spacer(Modifier.width(8.dp))
                Text(if (manual) "Z wysokościomierza (ręcznie)" else "Z GPS")
            }
            if (manual) NumField(alt, { alt = it; vm.engine.manualAltFt = num(it) }, "Wysokość ft AMSL", Modifier.fillMaxWidth())
        }

        // --- ustawienia odbiornika ---
        Section("Ustawienia odbiornika") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(settings.useStationVariation, { vm.updateSettings(settings.copy(useStationVariation = it)) })
                Spacer(Modifier.width(8.dp))
                Text("Deklinacja stacji z bazy (jak prawdziwy VOR); wył. = model WMM", fontSize = 13.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(settings.ignoreRange, { vm.updateSettings(settings.copy(ignoreRange = it)) })
                Spacer(Modifier.width(8.dp))
                Text("Ignoruj zasięg stacji (pokazuj zawsze)", fontSize = 13.sp)
            }
        }

        // --- symulacja ---
        Section("Symulacja (ćwiczenia na ziemi)") {
            var active by remember { mutableStateOf(vm.engine.simActive) }
            var gs by remember { mutableStateOf(vm.engine.simGsKt.toInt().toString()) }
            var trk by remember { mutableStateOf(vm.engine.simTrackMag.toInt().toString()) }
            var alt by remember { mutableStateOf(vm.engine.simAltFt.toInt().toString()) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumField(gs, { gs = it; num(it)?.let { v -> vm.engine.simGsKt = v } }, "TAS kt", Modifier.weight(1f))
                NumField(trk, { trk = it; num(it)?.let { v -> vm.engine.simTrackMag = v } }, "HDG °M", Modifier.weight(1f))
                NumField(alt, { alt = it; num(it)?.let { v -> vm.engine.simAltFt = v } }, "ALT ft", Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(active, {
                    active = it
                    if (it) {
                        val start = pos ?: vm.direct.value?.let { d -> pl.pilot.vornav.loc.Position(d.lat, d.lon, 0.0, 0.0, null, pl.pilot.vornav.loc.PosSource.MANUAL) }
                        vm.engine.startSim(start?.lat ?: 52.1657, start?.lon ?: 20.9671)
                    } else vm.engine.stopSim()
                })
                Spacer(Modifier.width(8.dp))
                Text(if (active) "Symulacja włączona" else "Start od bieżącej pozycji (lub celu D→)", fontSize = 13.sp)
            }
        }
        Text(
            "Aplikacja pomocnicza — nie jest certyfikowanym urządzeniem nawigacyjnym. Wskazania VOR/DME są wyliczane z GPS i bazy danych, nie z sygnału radiowego.",
            fontSize = 11.sp, color = Color(0xFF8FA3B8), modifier = Modifier.padding(4.dp)
        )
    }
}
