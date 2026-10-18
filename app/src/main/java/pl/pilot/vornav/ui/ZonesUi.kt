package pl.pilot.vornav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import pl.pilot.vornav.data.AspCategory
import pl.pilot.vornav.data.StaticAirspaceRepo
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.pilot.vornav.nav.Geo
import pl.pilot.vornav.nav.ZoneAlerts

val ZoneRed = Color(0xFFE53935)
val ZoneOrange = Color(0xFFFF9800)

/** Ostrzeżenie: samolot w aktywnej strefie albo leci na nią (w ciągu 5 NM). */
@Composable
fun ZoneAlertBanner(vm: NavViewModel) {
    val pos by vm.position.collectAsState()
    val all by vm.zones.collectAsState()
    val maxFt by vm.zoneMaxFt.collectAsState()
    val p = pos ?: return
    val visible = remember(all, maxFt) { vm.visibleZones(all, maxFt) }
    val alerts = remember(p, visible) { ZoneAlerts.check(visible, p, System.currentTimeMillis()) }
    if (alerts.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        alerts.take(2).forEach { a ->
            Column(
                Modifier.fillMaxWidth()
                    .background(if (a.inside) ZoneRed else ZoneOrange, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    if (a.inside) "JESTEŚ W AKTYWNEJ STREFIE ${a.zone.designator}"
                    else "Aktywna strefa ${a.zone.designator} za ${fmtNm(a.distNm)} NM (${fmtMin(a.minutes)})",
                    color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp
                )
                Text(
                    "${ZoneAlerts.altText(a.res)} • ${ZoneAlerts.timeText(a.res)}" +
                        if (a.res.remarks.isNotBlank()) " • ${a.res.remarks}" else "",
                    color = Color.Black, fontSize = 12.sp
                )
            }
        }
        if (alerts.size > 2) Text("…i ${alerts.size - 2} kolejne", fontSize = 11.sp, color = ZoneOrange)
    }
}

/** Sekcja w zakładce Baza: pobieranie AUP/UUP i lista stref. */
@Composable
fun ZonesSection(vm: NavViewModel) {
    val all by vm.zones.collectAsState()
    val status by vm.zonesStatus.collectAsState()
    val busy by vm.zonesBusy.collectAsState()
    val show by vm.showZones.collectAsState()
    val maxFt by vm.zoneMaxFt.collectAsState()
    val pos by vm.position.collectAsState()
    var onlyNow by remember { mutableStateOf(true) }
    var listOpen by rememberSaveable { mutableStateOf(false) }
    var limit by remember { mutableStateOf(maxFt.toString()) }

    val now = System.currentTimeMillis()
    val list = remember(all, maxFt, onlyNow) {
        vm.visibleZones(all, maxFt).mapNotNull { z ->
            val r = (if (onlyNow) z.activeAt(now) else z.nextFrom(now)) ?: return@mapNotNull null
            z to r
        }.sortedBy { it.second.start }
    }

    CollapsibleCard("Strefy aktywne dziś (AUP/UUP PAŻP)", status) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.refreshZones() }, enabled = !busy) { Text(if (busy) "Pobieram…" else "Odśwież teraz") }
            Switch(show, { vm.setShowZones(it) })
            Text("Na mapie", fontSize = 13.sp)
        }
        OutlinedTextField(
            limit, { limit = it; it.toIntOrNull()?.takeIf { v -> v >= 500 }?.let(vm::setZoneMaxFt) },
            label = { Text("Pokaż strefy z dolną granicą poniżej (ft)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(onlyNow, { onlyNow = true }, { Text("Aktywne teraz") })
            FilterChip(!onlyNow, { onlyNow = false }, { Text("Teraz i później") })
        }
        ExpandRow("Lista stref (${list.size})", listOpen) { listOpen = !listOpen }
        if (listOpen && list.isEmpty()) Text("Brak stref do pokazania", fontSize = 12.sp, color = Color(0xFF8FA3B8))
        if (listOpen) list.take(150).forEach { (z, r) ->
            val active = now >= r.start && now < r.end
            val dist = pos?.let { Geo.distanceNm(it.lat, it.lon, z.centerLat, z.centerLon) }
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        z.designator, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        color = if (active) ZoneRed else ZoneOrange, modifier = Modifier.weight(1f)
                    )
                    if (dist != null) Text("${fmtNm(dist)} NM", fontSize = 12.sp, color = Color(0xFF8FA3B8))
                }
                Text("${ZoneAlerts.altText(r)} • ${ZoneAlerts.timeText(r)}", fontSize = 12.sp)
                if (r.remarks.isNotBlank()) Text(r.remarks, fontSize = 11.sp, color = Color(0xFF8FA3B8))
            }
            HorizontalDivider(color = Color(0xFF1E2E40))
        }
        Text(
            "Źródło: airspace.pansa.pl (AUP/UUP). Odświeżanie automatyczne co 30 min przy otwartej aplikacji, dane zapisywane do użytku offline. Informacja pomocnicza — przed lotem sprawdź AUP/UUP i NOTAM w oficjalnych źródłach.",
            fontSize = 11.sp, color = Color(0xFF8FA3B8)
        )
    }
}


/** Wiersz rozwijania/zwijania listy. */
@Composable
fun ExpandRow(title: String, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(if (open) "▲ zwiń" else "▼ rozwiń", fontSize = 12.sp, color = NavGreen)
    }
}

/** Zwijana karta sekcji w zakładce Baza. */
@Composable
fun CollapsibleCard(title: String, subtitle: String? = null, startOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
    Column(
        Modifier.fillMaxWidth().background(PanelBg, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = Amber)
                if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = Color(0xFF8FA3B8), maxLines = 2)
            }
            Text(if (open) "▲" else "▼", color = NavGreen, fontSize = 18.sp)
        }
        if (open) content()
    }
}

/** Sekcja przestrzeni powietrznej z openAIP: kraje, pobieranie, grupy stref do włączania. */
@Composable
fun AirspaceSection(vm: NavViewModel) {
    val status by vm.aspStatus.collectAsState()
    val busy by vm.aspBusy.collectAsState()
    val countries by vm.aspCountries.collectAsState()
    val show by vm.showAreas.collectAsState()
    val cats by vm.areaCats.collectAsState()
    val all by vm.areas.collectAsState()
    val counts = remember(all) { all.groupingBy { it.category }.eachCount() }

    CollapsibleCard("Przestrzeń powietrzna (CTR, TMA, TRA…)", status, startOpen = all.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(show, { vm.setShowAreas(it) })
            Spacer(Modifier.width(8.dp))
            Text("Pokazuj na mapie", fontSize = 13.sp)
        }
        Text("Grupy stref:", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        AspCategory.entries.forEach { c ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.toggleAreaCat(c) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(c in cats, { vm.toggleAreaCat(c) })
                Box(Modifier.size(14.dp).background(Color(c.color), RoundedCornerShape(3.dp)))
                Spacer(Modifier.width(8.dp))
                Text(c.label, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("${counts[c] ?: 0}", fontSize = 12.sp, color = Color(0xFF8FA3B8))
            }
        }
        HorizontalDivider(color = Color(0xFF1E2E40))
        OutlinedTextField(
            countries, { vm.aspCountries.value = it.uppercase() }, singleLine = true,
            label = { Text("Kraje (kody ISO)") }, modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { vm.setAspPreset(listOf("PL")) }, label = { Text("Polska") })
            AssistChip(onClick = { vm.setAspPreset(StaticAirspaceRepo.NEIGHBOURS) }, label = { Text("+ sąsiedzi") })
            AssistChip(onClick = { vm.setAspPreset(StaticAirspaceRepo.EUROPE) }, label = { Text("Europa") })
        }
        Button(onClick = { vm.downloadAirspace() }, enabled = !busy) { Text(if (busy) "Pobieram…" else "Pobierz / aktualizuj przestrzeń") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            "Źródło: openAIP (openaip.net, CC BY-NC 4.0), aktualizowane przez openAIP codziennie; aplikacja odświeża dane co 7 dni lub na żądanie. Cała Europa to kilkaset MB pamięci — zalecane: Polska + sąsiedzi. Dane pomocnicze, nie zastępują AIP.",
            fontSize = 11.sp, color = Color(0xFF8FA3B8)
        )
    }
}

/** Linia „Przestrzeń nad Tobą”: strefy (z openAIP) zawierające bieżącą pozycję i wysokość. */
@Composable
fun AirspaceHereLine(vm: NavViewModel) {
    val pos by vm.position.collectAsState()
    val all by vm.areas.collectAsState()
    val cats by vm.areaCats.collectAsState()
    val p = pos ?: return
    if (all.isEmpty()) return
    // FIR też pokazujemy w opisie miejsca, nawet gdy grupa jest wyłączona na mapie
    val here = remember(p.lat.toFloat(), p.lon.toFloat(), (p.altFt / 100).toInt(), all, cats) {
        all.filter { (it.category in cats || it.category == AspCategory.FIR) && it.contains(p.lat, p.lon) && it.verticallyContains(p.altFt) }
            .sortedBy { it.category.ordinal }
    }
    if (here.isEmpty()) return
    Column(Modifier.fillMaxWidth().background(PanelBg, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text("Przestrzeń w Twojej pozycji:", fontSize = 11.sp, color = Color(0xFF8FA3B8))
        here.take(4).forEach { a ->
            Text(
                "${a.name} • ${a.label} • ${a.lowerText}–${a.upperText}",
                fontSize = 12.sp, color = Color(a.category.color).copy(alpha = 1f), maxLines = 1
            )
        }
    }
}
