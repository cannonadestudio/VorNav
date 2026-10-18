package pl.pilot.vornav.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pl.pilot.vornav.data.Waypoint

@Composable
fun SearchScreen(vm: NavViewModel) {
    val pos by vm.position.collectAsState()
    val counts by vm.counts.collectAsState()
    var q by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) }   // 0 wszystko, 1 lotniska, 2 VOR/DME/NDB
    var results by remember { mutableStateOf<List<Waypoint>>(emptyList()) }
    var selected by remember { mutableStateOf<Waypoint?>(null) }
    val hasPos = pos != null

    LaunchedEffect(q, hasPos, counts) {
        delay(200)
        results = if (q.isBlank()) vm.nearest() else vm.search(q)
    }
    val shown = results.filter {
        when (filter) { 1 -> it.kind == Waypoint.Kind.AIRPORT; 2 -> it.kind == Waypoint.Kind.NAVAID; else -> true }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            label = { Text("ICAO, nazwa, miejscowość, VOR…") },
            modifier = Modifier.fillMaxWidth().padding(8.dp)
        )
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Wszystko", "Lotniska", "Pomoce nav.").forEachIndexed { i, s ->
                FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(s) })
            }
        }
        if (counts.first + counts.second + counts.third == 0) {
            Text(
                "Baza jest pusta — przejdź do zakładki „Baza” i pobierz dane lub dodaj własne lądowiska.",
                color = Amber, modifier = Modifier.padding(12.dp)
            )
        } else if (q.isBlank()) {
            Text(
                if (pos == null) "Brak pozycji — wpisz szukaną nazwę" else "Najbliższe w promieniu 50 NM",
                fontSize = 12.sp, color = Color(0xFF8FA3B8), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
        selected?.let { wp ->
            Surface(color = PanelBg, modifier = Modifier.fillMaxWidth().padding(8.dp), tonalElevation = 4.dp) {
                WaypointActions(wp, vm) { selected = null }
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.key }) { wp ->
                WaypointRow(wp, pos) { selected = wp }
                HorizontalDivider(color = Color(0xFF1E2E40))
            }
        }
    }
}
