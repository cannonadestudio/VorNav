package pl.pilot.vornav.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pl.pilot.vornav.data.Runway
import pl.pilot.vornav.data.Waypoint
import pl.pilot.vornav.loc.PosSource
import pl.pilot.vornav.nav.HoldEntry
import pl.pilot.vornav.nav.Mag
import java.io.File

private val Hint = Color(0xFF8FA3B8)

private fun num(s: String) = s.trim().replace(',', '.').toDoubleOrNull()

private fun mmss(sec: Int?): String = if (sec == null) "--:--" else "%d:%02d".format(sec / 60, sec % 60)

/**
 * Sterowanie samolotem symulacji: kurs (zakręt standardowy 3°/s), prędkość pionowa, prędkość, wiatr.
 * compact = wersja na mapę.
 */
@Composable
fun SimControls(vm: NavViewModel, compact: Boolean) {
    val pos by vm.position.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(500); tick++ } }
    val e = vm.engine
    if (tick < 0) return   // odczyt licznika odświeża widok co 0,5 s
    val p = pos
    val trkMag = p?.trackTrue?.let { Mag.trueToMag(it, Mag.declination(p.lat, p.lon)) }

    if (compact) {
        fun hdg(d: Int) { e.simTrackMag = pl.pilot.vornav.nav.Geo.norm360(e.simTrackMag + d) }
        Column(
            Modifier.width(112.dp).background(Color(0xF007111C), RoundedCornerShape(12.dp)).padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("KURS", fontSize = 11.sp, color = Hint)
            Text(fmtDeg(e.simTrackMag), fontSize = 20.sp, fontFamily = FontFamily.Monospace, color = Magenta, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { MapBtn("−10") { hdg(-10) }; MapBtn("+10") { hdg(10) } }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { MapBtn("−1") { hdg(-1) }; MapBtn("+1") { hdg(1) } }
            Spacer(Modifier.height(2.dp))
            Text("VS ft/min", fontSize = 11.sp, color = Hint)
            Text("%+d".format(e.simVsFpm.toInt()), fontSize = 18.sp, fontFamily = FontFamily.Monospace, color = Amber, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { MapBtn("▼") { e.simVsFpm -= 100 }; MapBtn("▲") { e.simVsFpm += 100 } }
            MapBtn("VS 0", wide = true) { e.simVsFpm = 0.0 }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Readout("HDG", fmtDeg(e.simHdgMag), Modifier.weight(1f), color = Magenta)
            Readout("TRK", fmtDeg(trkMag), Modifier.weight(1f))
            Readout("GS", p?.let { "%.0f".format(it.gsKt) } ?: "--", Modifier.weight(1f))
            Readout("ALT", "%.0f".format(e.simAltFt), Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Kurs ${fmtDeg(e.simTrackMag)}", fontSize = 13.sp, modifier = Modifier.width(92.dp))
            for (d in listOf(-30, -10, -1, 1, 10, 30)) OutlinedButton(
                onClick = { e.simTrackMag = pl.pilot.vornav.nav.Geo.norm360(e.simTrackMag + d) },
                modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)
            ) { Text(if (d > 0) "+$d" else "$d", fontSize = 12.sp) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("VS ${e.simVsFpm.toInt()}", fontSize = 13.sp, modifier = Modifier.width(92.dp))
            for (v in listOf(-1000, -500, 0, 500, 1000)) OutlinedButton(
                onClick = { e.simVsFpm = v.toDouble() }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)
            ) { Text("$v", fontSize = 12.sp) }
        }
        var tas by remember { mutableStateOf(e.simGsKt.toInt().toString()) }
        var wd by remember { mutableStateOf(e.windFromMag.toInt().toString()) }
        var wk by remember { mutableStateOf(e.windKt.toInt().toString()) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumIn(tas, { tas = it; num(it)?.takeIf { v -> v in 30.0..400.0 }?.let { v -> e.simGsKt = v } }, "TAS kt", Modifier.weight(1f))
            NumIn(wd, { wd = it; num(it)?.let { v -> e.windFromMag = v } }, "Wiatr z °M", Modifier.weight(1f))
            NumIn(wk, { wk = it; num(it)?.let { v -> e.windKt = v } }, "Wiatr kt", Modifier.weight(1f))
        }
    }
}

/** Duży, czytelny przycisk do sterowania na mapie (min. 46×40 dp). */
@Composable
fun MapBtn(t: String, wide: Boolean = false, color: Color = Color.White, onClick: () -> Unit) {
    Box(
        Modifier.size(if (wide) 96.dp else 46.dp, 40.dp)
            .background(Color(0xFF2B3D52), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(t, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1) }
}

@Composable
private fun SmallBtn(t: String, onClick: () -> Unit) {
    Text(
        t, fontSize = 12.sp, color = Color.White,
        modifier = Modifier.padding(2.dp).background(Color(0xFF263646), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

@Composable
private fun NumIn(v: String, on: (String) -> Unit, label: String, m: Modifier) =
    OutlinedTextField(v, on, label = { Text(label, fontSize = 11.sp) }, singleLine = true, modifier = m,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))

@Composable
fun TrainerScreen(vm: NavViewModel) {
    val pos by vm.position.collectAsState()
    val simOn = pos?.source == PosSource.SIM
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ZoneAlertBanner(vm, max = 1, compact = true)
        CollapsibleCard("Samolot symulacji", if (simOn) "Włączona — sterujesz kursem i VS (także na mapie)" else "Wyłączona — zadania uruchomią ją same", startOpen = simOn) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(simOn, {
                    if (it) { val p = pos; vm.engine.startSim(p?.lat ?: 52.1657, p?.lon ?: 20.9671) } else vm.engine.stopSim()
                })
                Spacer(Modifier.width(8.dp))
                Text(if (simOn) "Symulacja włączona" else "Włącz symulację", fontSize = 13.sp)
            }
            if (simOn) SimControls(vm, compact = false)
        }
        HoldingCard(vm)
        InterceptCard(vm)
        IlsPracticeCard(vm)
        TracksCard(vm)
        Text(
            "Trenery działają w symulacji (samolot ustawiany automatycznie) albo w prawdziwym locie (wyłącz „Symuluj”) — wtedy pozycja pochodzi z GPS.",
            fontSize = 11.sp, color = Hint, modifier = Modifier.padding(4.dp)
        )
    }
}

// ---------------- holding ----------------

@Composable
private fun HoldingCard(vm: NavViewModel) {
    val task by vm.trainer.hold.collectAsState()
    val live by vm.trainer.holdLive.collectAsState()
    var ndb by remember { mutableStateOf(false) }
    var wind by remember { mutableStateOf(true) }
    var sim by remember { mutableStateOf(true) }
    var entryAnswer by remember(task) { mutableStateOf<HoldEntry?>(null) }
    var outHdg by remember(task) { mutableStateOf("") }
    var outSec by remember(task) { mutableStateOf("") }
    var checked by remember(task) { mutableStateOf(false) }

    CollapsibleCard("Trener holdingu", task?.let { "${it.fix.ident} • ${it.legSec}s • ${if (it.rightTurns) "prawe" else "lewe"}" } ?: "Losowy holding nad VOR lub NDB") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(!ndb, { ndb = false }, { Text("VOR") })
            FilterChip(ndb, { ndb = true }, { Text("NDB") })
            FilterChip(wind, { wind = !wind }, { Text("Z wiatrem") })
            FilterChip(sim, { sim = !sim }, { Text("Symuluj") })
        }
        Button(onClick = { vm.trainer.newHold(ndb, wind, sim) }) { Text(if (task == null) "Losuj zadanie" else "Nowe zadanie") }
        val t = task ?: return@CollapsibleCard
        Text(t.description, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text("Dolatujesz do fixu kursem ${fmtDeg(t.startHdg)}.", fontSize = 13.sp)

        Text("1. Jakie wejście?", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HoldEntry.entries.forEach { en ->
                FilterChip(entryAnswer == en, { entryAnswer = en }, { Text(en.label.substringBefore(" ("), fontSize = 12.sp) })
            }
        }
        Text("2. Kurs na odcinku odlotowym i czas odlotu (dla 1:00 na dolocie)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumIn(outHdg, { outHdg = it }, "HDG odlot °", Modifier.weight(1f))
            NumIn(outSec, { outSec = it }, "Czas odlotu s", Modifier.weight(1f))
        }
        Button(onClick = { checked = true }, enabled = entryAnswer != null) { Text("Sprawdź") }
        if (checked) {
            val sol = t.solution
            val okEntry = entryAnswer == t.entry
            val h = num(outHdg); val s = num(outSec)
            val hErr = h?.let { kotlin.math.abs(pl.pilot.vornav.nav.Geo.norm180(it - sol.outboundHdg)) }
            val sErr = s?.let { kotlin.math.abs(it - sol.outboundSec) }
            Text(
                (if (okEntry) "✓ Wejście: ${t.entry.label}" else "✗ Wejście: poprawne to ${t.entry.label}"),
                color = if (okEntry) NavGreen else ZoneRed, fontSize = 13.sp
            )
            Text(
                "Kurs dolotowy z poprawką: ${fmtDeg(sol.inboundHdg)} (WCA ${"%+.0f".format(sol.wcaIn)}°). " +
                    "Odlot: ${fmtDeg(sol.outboundHdg)} (potrójna poprawka), ${sol.outboundSec} s.",
                fontSize = 13.sp
            )
            if (hErr != null) Text(
                if (hErr <= 5) "✓ Twój kurs odlotu ${fmtDeg(h)} — OK (±5°)" else "✗ Twój kurs odlotu ${fmtDeg(h)} — różnica ${hErr.toInt()}°",
                color = if (hErr <= 5) NavGreen else ZoneOrange, fontSize = 13.sp
            )
            if (sErr != null) Text(
                if (sErr <= 6) "✓ Czas odlotu ${s!!.toInt()} s — OK (±6 s)" else "✗ Czas odlotu ${s!!.toInt()} s — różnica ${sErr.toInt()} s",
                color = if (sErr <= 6) NavGreen else ZoneOrange, fontSize = 13.sp
            )
        }
        HorizontalDivider(color = Color(0xFF1E2E40))
        Text("Lot (holding narysowany na mapie na zielono)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Row {
            Readout("PRZELOTY", "${live.passages}", Modifier.weight(1f))
            Readout("OD FIXU", mmss(live.sinceFixSec.takeIf { live.passages > 0 }), Modifier.weight(1f))
            Readout("ODLOT", mmss(live.outboundSec), Modifier.weight(1f), color = Magenta)
        }
        live.laps.forEach { l ->
            val good = l.inboundSec in 50..70 && l.maxXtkNm < 0.3
            Text(
                "Okrążenie ${l.n}: dolot ${mmss(l.inboundSec)}, maks. zejście z osi ${"%.2f".format(l.maxXtkNm)} NM" + if (good) " ✓" else "",
                fontSize = 13.sp, color = if (good) NavGreen else ZoneOrange
            )
        }
        TextButton(onClick = { vm.trainer.clearHold(); vm.stopTrackRecording() }) { Text("Zakończ holding") }
    }
}

// ---------------- przechwytywanie ----------------

@Composable
private fun InterceptCard(vm: NavViewModel) {
    val task by vm.trainer.intercept.collectAsState()
    val live by vm.trainer.interceptLive.collectAsState()
    var ndb by remember { mutableStateOf(false) }
    var sim by remember { mutableStateOf(true) }
    var answer by remember(task) { mutableStateOf("") }
    var result by remember(task) { mutableStateOf<Pair<Boolean, String>?>(null) }

    CollapsibleCard("Trener przechwytywania", task?.description ?: "Radial VOR lub namiar NDB, TO/FROM") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(!ndb, { ndb = false }, { Text("VOR") })
            FilterChip(ndb, { ndb = true }, { Text("NDB") })
            FilterChip(sim, { sim = !sim }, { Text("Symuluj") })
        }
        Button(onClick = { vm.trainer.newIntercept(ndb, sim) }) { Text(if (task == null) "Losuj zadanie" else "Nowe zadanie") }
        val t = task ?: return@CollapsibleCard
        Text(t.description, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text("Odczytaj położenie z przyrządów (NAV1/ADF) i podaj kurs przechwycenia.", fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumIn(answer, { answer = it }, "Kurs przechwycenia °", Modifier.weight(1f))
            Button(onClick = { num(answer)?.let { result = vm.trainer.checkInterceptHeading(t, it) } }) { Text("Sprawdź") }
        }
        result?.let { (ok, msg) ->
            Text((if (ok) "✓ " else "✗ ") + msg, color = if (ok) NavGreen else ZoneOrange, fontSize = 13.sp)
            if (ok && vm.engine.simActive) TextButton(onClick = { num(answer)?.let { vm.engine.simTrackMag = it } }) {
                Text("Leć tym kursem (ustaw HDG ${fmtDeg(num(answer))})")
            }
        }
        HorizontalDivider(color = Color(0xFF1E2E40))
        Row {
            Readout("CZAS", mmss(live.elapsedSec), Modifier.weight(1f))
            Readout("ODCHYL.", live.devDeg?.let { "%+.1f°".format(it) } ?: "--", Modifier.weight(1f))
            Readout("PRZESTRZAŁ", if (live.crossed) "%.1f°".format(live.overshootDeg) else "-", Modifier.weight(1f), color = Amber)
        }
        live.establishedSec?.let {
            Text(
                "✓ Ustabilizowany na kursie po ${mmss(it)}" +
                    if (live.overshootDeg > 3) " — przestrzał ${"%.1f".format(live.overshootDeg)}°, zacznij zakręt wcześniej" else " — bez dużego przestrzału",
                color = NavGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold
            )
        }
        TextButton(onClick = { vm.trainer.clearIntercept(); vm.stopTrackRecording() }) { Text("Zakończ") }
    }
}

// ---------------- ILS ----------------

@Composable
private fun IlsPracticeCard(vm: NavViewModel) {
    val rw by vm.ils.collectAsState()
    var pick by remember { mutableStateOf(false) }
    var airport by remember { mutableStateOf<Waypoint?>(null) }
    var runways by remember { mutableStateOf<List<Runway>>(emptyList()) }
    var offset by remember { mutableIntStateOf(0) }
    LaunchedEffect(airport) { airport?.let { runways = vm.runwaysOf(it) } }

    CollapsibleCard("Podejście ILS (trening)", rw?.let { "${it.airport} RWY ${it.ident}" } ?: "Start 10 NM przed pasem, 3000 ft nad progiem") {
        Text("Wskazania ILS są też w zakładce NAV i na mapie — działają na żywo z GPS w prawdziwym locie.", fontSize = 12.sp, color = Hint)
        OutlinedButton(onClick = { pick = true }) { Text(airport?.let { "Lotnisko: ${it.ident}" } ?: "Wybierz lotnisko") }
        if (runways.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            runways.forEach { r -> FilterChip(rw?.key == r.key, { vm.tuneIls(r) }, { Text(r.ident) }) }
        } else if (airport != null) Text("Brak pasów w bazie — zaktualizuj bazę (Baza → Lotniska).", fontSize = 12.sp, color = ZoneOrange)
        Text("Start względem osi:", fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(15 to "15° w lewo", 0 to "na osi", -15 to "15° w prawo").forEach { (o, l) ->
                FilterChip(offset == o, { offset = o }, { Text(l, fontSize = 12.sp) })
            }
        }
        val r = rw
        val session by vm.ilsSession.collectAsState()
        if (session) {
            Text("Podejście w toku — przejdź na Mapę. Po przyziemieniu zobaczysz wynik i wykresy.", fontSize = 13.sp, color = NavGreen)
            OutlinedButton(onClick = { vm.stopIlsPractice() }) { Text("Przerwij podejście") }
        } else {
            Button(onClick = { if (r != null) vm.startIlsPractice(r, offset.toDouble()) }, enabled = r != null) { Text("Start podejścia (symulacja)") }
        }
        Text("Ląduj: utrzymuj LOC i GS, a tuż nad pasem zmniejsz opadanie (VS ▲) do ok. −200 ft/min.", fontSize = 12.sp, color = Hint)
        Text("Wskazówka: na ścieżce opadaj ok. 5 × GS (przy 90 kt ≈ 480 ft/min).", fontSize = 12.sp, color = Hint)
    }
    if (pick) WaypointPickerDialog("Lotnisko", vm, { if (it.kind == Waypoint.Kind.AIRPORT) airport = it }) { pick = false }
}

// ---------------- ślady ----------------

@Composable
private fun TracksCard(vm: NavViewModel) {
    val rec by vm.recording.collectAsState()
    val pts by vm.recPoints.collectAsState()
    var files by remember { mutableStateOf(vm.tracks.list()) }
    var toExport by remember { mutableStateOf<File?>(null) }
    val ctx = LocalContext.current
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        val f = toExport
        if (uri != null && f != null) runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(f.readBytes()) }
            vm.toast.value = "Wyeksportowano ${f.name}"
        }
        toExport = null
    }
    LaunchedEffect(rec) { files = vm.tracks.list() }

    CollapsibleCard("Zapis lotu (ślad)", if (rec) "Nagrywanie: ${pts.size} pkt • ${vm.tracks.summary(pts)}" else "Zapisane ślady: ${files.size}") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rec) Button(onClick = { vm.stopTrackRecording() }) { Text("■ Zatrzymaj i zapisz") }
            else Button(onClick = { vm.startTrackRecording("Lot") }) { Text("● Nagrywaj") }
            TextButton(onClick = { vm.showTrack(null) }) { Text("Ukryj ślad") }
        }
        Text("Ślad rysowany jest na mapie na niebiesko. Treningi nagrywają się automatycznie.", fontSize = 12.sp, color = Hint)
        files.take(30).forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.nameWithoutExtension, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                TextButton(onClick = { vm.showTrack(f) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Pokaż") }
                TextButton(onClick = { toExport = f; saver.launch(f.name) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("GPX") }
                TextButton(onClick = { f.delete(); files = vm.tracks.list() }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("✕") }
            }
        }
    }
}
