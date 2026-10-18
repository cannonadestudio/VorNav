package pl.pilot.vornav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
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
import pl.pilot.vornav.data.Waypoint
import pl.pilot.vornav.nav.LegPlan
import pl.pilot.vornav.nav.RoutePlanner

private enum class PickFor { FROM, TO, VIA }

/** Planowanie trasy „od–do” z instrukcjami nawigacji po radialach dla każdego odcinka. */
@Composable
fun RouteScreen(vm: NavViewModel) {
    val route by vm.route.collectAsState()
    val legs by vm.legs.collectAsState()
    val active by vm.routeActive.collectAsState()
    val activeLeg by vm.activeLeg.collectAsState()
    val cruise by vm.cruiseKt.collectAsState()
    val maxLeg by vm.maxLegNm.collectAsState()
    val cruiseAlt by vm.cruiseAltFt.collectAsState()
    val allZones by vm.zones.collectAsState()
    var pick by remember { mutableStateOf<PickFor?>(null) }

    val from = route.firstOrNull()
    val to = if (route.size >= 2) route.last() else null
    val via = if (route.size > 2) route.subList(1, route.size - 1) else emptyList()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // --- wybór punktów ---
        Column(
            Modifier.fillMaxWidth().background(PanelBg, RoundedCornerShape(12.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Trasa", fontWeight = FontWeight.Bold, color = Amber)
            PointButton("OD", from) { pick = PickFor.FROM }
            via.forEachIndexed { k, wp ->
                val idx = k + 1
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("  przez ", fontSize = 12.sp, color = Color(0xFF8FA3B8), modifier = Modifier.width(56.dp))
                    Column(Modifier.weight(1f)) {
                        Text(wp.ident, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text("${wp.typeLabel} ${wp.freqText}", fontSize = 11.sp, color = if (wp.hasVor) NavGreen else Color(0xFF8FA3B8))
                    }
                    IconButton(onClick = { vm.moveRoutePoint(idx, -1) }, enabled = idx > 1) { Icon(Icons.Filled.ArrowUpward, "W górę") }
                    IconButton(onClick = { vm.moveRoutePoint(idx, 1) }, enabled = idx < route.size - 2) { Icon(Icons.Filled.ArrowDownward, "W dół") }
                    IconButton(onClick = { vm.removeRoutePoint(idx) }) { Icon(Icons.Filled.Close, "Usuń") }
                }
            }
            PointButton("DO", to, enabled = from != null) { pick = PickFor.TO }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.suggestVorRoute() }, enabled = from != null && to != null) { Text("Wyznacz przez VOR") }
                OutlinedButton(onClick = { pick = PickFor.VIA }, enabled = from != null && to != null) { Text("+ Punkt") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.reverseRoute() }, enabled = route.size >= 2) { Text("Odwróć") }
                TextButton(onClick = { vm.clearRoute() }, enabled = route.isNotEmpty()) { Text("Wyczyść") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var cs by remember { mutableStateOf(cruise.toInt().toString()) }
                var ml by remember { mutableStateOf(maxLeg.toInt().toString()) }
                OutlinedTextField(
                    cs, { cs = it; it.toDoubleOrNull()?.takeIf { v -> v > 10 }?.let(vm::setCruise) },
                    label = { Text("Prędkość kt") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    ml, { ml = it; it.toDoubleOrNull()?.takeIf { v -> v >= 10 }?.let(vm::setMaxLeg) },
                    label = { Text("Maks. odcinek NM") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            var ca by remember { mutableStateOf(cruiseAlt.toInt().toString()) }
            OutlinedTextField(
                ca, { ca = it; it.toDoubleOrNull()?.takeIf { v -> v >= 0 }?.let(vm::setCruiseAlt) },
                label = { Text("Wysokość przelotu ft (do sprawdzania stref)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Text(
                "„Wyznacz przez VOR” szuka najkrótszej trasy, w której każdy odcinek zaczyna się lub kończy na VOR — wtedy cały lot prowadzisz po radialach.",
                fontSize = 11.sp, color = Color(0xFF8FA3B8)
            )
        }

        // --- plan odcinków ---
        if (legs.isNotEmpty()) {
            val total = legs.sumOf { it.distNm }
            Row(
                Modifier.fillMaxWidth().background(PanelBg, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Razem ${fmtNm(total)} NM • ${legs.size} odc.", fontWeight = FontWeight.Bold)
                    Text("Czas przy ${cruise.toInt()} kt: ${fmtMin(total / cruise * 60)} (bez wiatru)", fontSize = 12.sp, color = Color(0xFF8FA3B8))
                }
                if (active) OutlinedButton(onClick = { vm.stopRoute() }) { Text("Stop") }
                else Button(onClick = { vm.startRoute(0) }) { Text("Start") }
            }
            val conflicts = remember(legs, allZones, cruiseAlt) {
                pl.pilot.vornav.nav.ZoneAlerts.routeConflicts(allZones, legs, cruiseAlt, System.currentTimeMillis())
            }
            Column(
                Modifier.fillMaxWidth().background(PanelBg, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    if (conflicts.isEmpty()) "Strefy na trasie: brak aktywnych w ciągu 12 h na ${cruiseAlt.toInt()} ft"
                    else "Strefy na trasie (${conflicts.size}) na ${cruiseAlt.toInt()} ft",
                    fontWeight = FontWeight.Bold, color = if (conflicts.isEmpty()) NavGreen else ZoneOrange
                )
                if (allZones.isEmpty()) Text("Brak danych AUP/UUP — pobierz je w zakładce Baza.", fontSize = 12.sp, color = Color(0xFF8FA3B8))
                conflicts.forEach { c ->
                    Text(
                        "Odc. ${c.legIndex + 1}: ${c.zone.designator} ${pl.pilot.vornav.nav.ZoneAlerts.altText(c.res)} • " +
                            pl.pilot.vornav.nav.ZoneAlerts.timeText(c.res) + if (c.activeNow) " • AKTYWNA TERAZ" else "",
                        fontSize = 12.sp, color = if (c.activeNow) ZoneRed else ZoneOrange
                    )
                }
            }
            legs.forEach { leg ->
                LegCard(leg, cruise, isActive = active && leg.index == activeLeg) { vm.startRoute(leg.index) }
            }
            Text(
                "W locie: NAV1 jest strojony automatycznie na stację odcinka z gotowym OBS, D→ wskazuje koniec odcinka, a po minięciu punktu aplikacja przechodzi do następnego odcinka. Sprawdź częstotliwości i radiale z mapą VFR/AIP.",
                fontSize = 11.sp, color = Color(0xFF8FA3B8), modifier = Modifier.padding(4.dp)
            )
        }
    }

    when (pick) {
        PickFor.FROM -> WaypointPickerDialog("Lotnisko startu", vm, { vm.setRouteFrom(it) }) { pick = null }
        PickFor.TO -> WaypointPickerDialog("Lotnisko docelowe", vm, { vm.setRouteTo(it) }) { pick = null }
        PickFor.VIA -> WaypointPickerDialog("Punkt pośredni (np. VOR)", vm, { vm.addVia(it) }) { pick = null }
        null -> {}
    }
}

@Composable
private fun PointButton(label: String, wp: Waypoint?, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Amber, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp))
        Text(
            wp?.let { "${it.ident}  ${it.name}" } ?: "wybierz…",
            maxLines = 1, modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun LegCard(leg: LegPlan, cruise: Double, isActive: Boolean, onStartHere: () -> Unit) {
    val base = Modifier.fillMaxWidth().background(PanelBg, RoundedCornerShape(12.dp))
    Column(
        (if (isActive) base.border(2.dp, Magenta, RoundedCornerShape(12.dp)) else base).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${leg.index + 1}.", color = Amber, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
            Text(
                "${leg.from.ident} → ${leg.to.ident}", fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f)
            )
            if (isActive) Text("AKTYWNY", color = Magenta, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            else TextButton(onClick = onStartHere) { Text("Leć od tu") }
        }
        Row {
            Readout("KURS M", "${RoutePlanner.fmt3(leg.courseMag)}°", Modifier.weight(1f))
            Readout("NM", fmtNm(leg.distNm), Modifier.weight(1f))
            Readout("CZAS", fmtMin(leg.distNm / cruise * 60), Modifier.weight(1f))
        }
        Text(
            leg.navText, fontSize = 13.sp,
            color = if (leg.navStation != null) NavGreen else Color(0xFFFF7043)
        )
        leg.checkpoint?.let { Text("Kontrola: $it", fontSize = 12.sp, color = Color(0xFFB0BEC5)) }
    }
}
