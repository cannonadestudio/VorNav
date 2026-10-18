package pl.pilot.vornav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    var limit by remember { mutableStateOf(maxFt.toString()) }

    val now = System.currentTimeMillis()
    val list = remember(all, maxFt, onlyNow) {
        vm.visibleZones(all, maxFt).mapNotNull { z ->
            val r = (if (onlyNow) z.activeAt(now) else z.nextFrom(now)) ?: return@mapNotNull null
            z to r
        }.sortedBy { it.second.start }
    }

    Column(
        Modifier.fillMaxWidth().background(PanelBg, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Strefy aktywne (AUP/UUP PAŻP)", fontWeight = FontWeight.Bold, color = Amber)
        Text(status, fontSize = 12.sp, color = if (status.contains("błąd")) Color(0xFFFF7043) else NavGreen)
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
        if (list.isEmpty()) Text("Brak stref do pokazania", fontSize = 12.sp, color = Color(0xFF8FA3B8))
        list.take(150).forEach { (z, r) ->
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
