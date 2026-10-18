package pl.pilot.vornav.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
import pl.pilot.vornav.data.parseCoord
import pl.pilot.vornav.nav.Geo

@Composable
fun DbScreen(vm: NavViewModel) {
    val counts by vm.counts.collectAsState()
    val status by vm.importStatus.collectAsState()
    val busy by vm.busy.collectAsState()
    val countries by vm.countries.collectAsState()
    val heli by vm.includeHeliports.collectAsState()
    val sites by vm.userSites.collectAsState()
    val pos by vm.position.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importUserCsv(uri)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ZonesSection(vm)
        AirspaceSection(vm)
        CollapsibleCard(
            "Lotniska i pomoce nawigacyjne",
            "Lotniska: ${counts.first} • VOR/DME/NDB: ${counts.second}",
            startOpen = counts.first + counts.second == 0
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    countries, { vm.countries.value = it.uppercase() }, singleLine = true,
                    label = { Text("Kraje (kody ISO, np. PL,DE,CZ,SK,LT)") }, modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(heli, { vm.includeHeliports.value = it })
                    Text("Także heliporty")
                }
                Button(onClick = { vm.importOurAirports() }, enabled = !busy) {
                    Text(if (counts.first + counts.second == 0) "Pobierz bazę (OurAirports)" else "Aktualizuj bazę")
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (status.isNotEmpty()) Text(status, fontSize = 13.sp, color = if (status.startsWith("Błąd")) Color(0xFFFF7043) else NavGreen)
                Text(
                    "Źródło: OurAirports (domena publiczna). Dane mogą być niekompletne lub nieaktualne — przed lotem sprawdź AIP / VFR Polska. Pobieranie wymaga internetu, potem baza działa offline.",
                    fontSize = 11.sp, color = Color(0xFF8FA3B8)
                )
            }
        }

        CollapsibleCard("Własne lądowiska (UL, trawiaste, prywatne)", "Zapisane: ${sites.size}") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { picker.launch(arrayOf("text/*", "text/csv", "application/octet-stream")) }, enabled = !busy) {
                    Text("Importuj plik CSV")
                }
                Text(
                    "Format wiersza: IDENT;Nazwa;szerokość;długość;wysokość_ft\nnp. EPXY;Lądowisko Przykład;52.1234;20.5678;350\nlub EPXY;Przykład;N52°07'24\";E020°34'04\";350",
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF8FA3B8)
                )
                HorizontalDivider(color = Color(0xFF1E2E40))
                AddSiteForm(vm, pos?.lat, pos?.lon)
                HorizontalDivider(color = Color(0xFF1E2E40))
                var sitesOpen by remember { mutableStateOf(false) }
                ExpandRow("Lista lądowisk (${sites.size})", sitesOpen) { sitesOpen = !sitesOpen }
                if (sitesOpen) sites.forEach { wp ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${wp.ident}  ${wp.name}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("${Geo.formatLat(wp.lat)} ${Geo.formatLon(wp.lon)}  ${wp.elevFt.toInt()} ft", fontSize = 12.sp, color = Color(0xFF8FA3B8))
                        }
                        IconButton(onClick = { vm.deleteUserSite(wp) }) { Icon(Icons.Filled.Delete, "Usuń") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddSiteForm(vm: NavViewModel, curLat: Double?, curLon: Double?) {
    var ident by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    var elev by remember { mutableStateOf("") }
    Text("Dodaj lądowisko", fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(ident, { ident = it.uppercase().take(8) }, label = { Text("Ident") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(name, { name = it }, label = { Text("Nazwa") }, singleLine = true, modifier = Modifier.weight(2f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(lat, { lat = it }, label = { Text("Szerokość") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(lon, { lon = it }, label = { Text("Długość") }, singleLine = true, modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            elev, { elev = it }, label = { Text("Elewacja ft") }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        if (curLat != null && curLon != null) TextButton(onClick = {
            lat = String.format(java.util.Locale.US, "%.5f", curLat); lon = String.format(java.util.Locale.US, "%.5f", curLon)
        }) { Text("Moja pozycja") }
    }
    val la = parseCoord(lat); val lo = parseCoord(lon)
    Button(
        enabled = ident.isNotBlank() && la != null && lo != null,
        onClick = {
            vm.addUserSite(ident, name.ifBlank { ident }, la!!, lo!!, elev.replace(',', '.').toDoubleOrNull() ?: 0.0)
            ident = ""; name = ""; lat = ""; lon = ""; elev = ""
        }
    ) { Text("Zapisz lądowisko") }
}
