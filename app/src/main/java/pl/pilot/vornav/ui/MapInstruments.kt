package pl.pilot.vornav.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.pilot.vornav.loc.Position
import pl.pilot.vornav.nav.AdfReceiver
import pl.pilot.vornav.nav.NavReading
import pl.pilot.vornav.nav.NavSettings
import pl.pilot.vornav.nav.ToFrom
import pl.pilot.vornav.nav.VorReceiver

private val MiniBg = Color(0xE607111C)
private val MiniLabel = Color(0xFF8FA3B8)

/**
 * Małe przyrządy na mapie: NAV1, NAV2 (pasek CDI, TO/FROM, OBS, DME, QDM) i ADF (mini-RMI, QDM, odległość).
 * Pokazują się tylko dla nastrojonych odbiorników.
 */
@Composable
fun MapInstruments(vm: NavViewModel, pos: Position?, settings: NavSettings, modifier: Modifier = Modifier) {
    val nav1 by vm.nav[0].collectAsState()
    val nav2 by vm.nav[1].collectAsState()
    val adf by vm.adf.collectAsState()
    val hdg by vm.manualHdg.collectAsState()
    if (nav1.station == null && nav2.station == null && adf == null) return

    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        listOf(nav1, nav2).forEachIndexed { i, slot ->
            val st = slot.station
            if (st != null) key(i) {
                val r = remember(st, pos, slot.obs, settings) {
                    if (pos != null) VorReceiver.compute(st, pos, slot.obs, settings) else null
                }
                MiniNav(i, st.ident, slot.obs, st.hasVor, st.hasDme, r,
                    onMinus = { vm.setObs(i, slot.obs - 1) },
                    onPlus = { vm.setObs(i, slot.obs + 1) },
                    onCenter = { vm.centerObs(i) })
            }
        }
        adf?.let { st ->
            val r = remember(st, pos, hdg, settings) { if (pos != null) AdfReceiver.compute(st, pos, hdg, settings) else null }
            Column(
                Modifier.width(120.dp).background(MiniBg, RoundedCornerShape(10.dp)).padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("ADF ${st.ident}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = NdbBrown, maxLines = 1)
                AdfIndicator(r, Modifier.size(64.dp))
                Text("QDM ${fmtDeg(r?.qdmMag)}", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = NdbBrown)
                Text("${fmtNm(r?.dmeNm ?: r?.distNm)} NM", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Amber)
            }
        }
    }
}

@Composable
private fun MiniNav(
    i: Int, ident: String, obs: Double, hasVor: Boolean, hasDme: Boolean, r: NavReading?,
    onMinus: () -> Unit, onPlus: () -> Unit, onCenter: () -> Unit,
) {
    Column(
        Modifier.width(150.dp).background(MiniBg, RoundedCornerShape(10.dp)).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("NAV${i + 1} ", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Amber)
            Text(ident, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
            if (r != null && !r.inRange) Text("OFF", fontSize = 11.sp, color = ZoneRed, fontWeight = FontWeight.Bold)
        }
        if (hasVor) {
            MiniCdi(r, Modifier.fillMaxWidth().height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("−", fontSize = 18.sp, color = Magenta, modifier = Modifier.clickable(onClick = onMinus).padding(horizontal = 6.dp))
                Text(
                    "OBS ${fmtDeg(obs)} ${when (r?.toFrom) { ToFrom.TO -> "TO"; ToFrom.FROM -> "FR"; else -> "--" }}",
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Magenta, maxLines = 1,
                    modifier = Modifier.weight(1f).clickable(onClick = onCenter)
                )
                Text("+", fontSize = 18.sp, color = Magenta, modifier = Modifier.clickable(onClick = onPlus).padding(horizontal = 6.dp))
            }
        }
        Row {
            if (hasVor) Column(Modifier.weight(1f)) {
                Text("QDM", fontSize = 9.sp, color = MiniLabel)
                Text(fmtDeg(r?.bearingToMag), fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = NavGreen)
            }
            if (hasDme) Column(Modifier.weight(1f)) {
                Text("DME", fontSize = 9.sp, color = MiniLabel)
                Text(if (r?.dmeValid == true) fmtNm(r.dmeNm) else "-.--", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Amber)
            }
        }
    }
}

/** Poziomy pasek CDI: 5 kropek na stronę (2°/kropkę), wskazówka i trójkąt TO/FROM. */
@Composable
private fun MiniCdi(r: NavReading?, modifier: Modifier) {
    Canvas(modifier) {
        val cy = size.height / 2; val cx = size.width / 2
        val dot = size.width / 12f
        for (k in -5..5) {
            if (k == 0) drawLine(Color.White, Offset(cx, cy - 7f), Offset(cx, cy + 7f), 2f)
            else drawCircle(Color.White, 3.5f, Offset(cx + k * dot, cy), style = Stroke(1.5f))
        }
        val dev = r?.deviationDeg
        if (r?.vorValid == true && dev != null) {
            val x = cx + (dev.coerceIn(-10.0, 10.0) / 2.0).toFloat() * dot
            drawLine(Magenta, Offset(x, 0f), Offset(x, size.height), 6f)
            val s = 7f; val tx = size.width - 10f
            val p = Path().apply {
                if (r.toFrom == ToFrom.TO) { moveTo(tx, cy - s); lineTo(tx - s, cy + s); lineTo(tx + s, cy + s) }
                else { moveTo(tx, cy + s); lineTo(tx - s, cy - s); lineTo(tx + s, cy - s) }
                close()
            }
            drawPath(p, Color.White)
        } else {
            drawLine(ZoneRed, Offset(cx - 2 * dot, cy), Offset(cx + 2 * dot, cy), 5f)
        }
    }
}
