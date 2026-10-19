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
    val ils by vm.ils.collectAsState()
    if (nav1.station == null && nav2.station == null && adf == null && ils == null) return

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
        ils?.let { rw ->
            val r = remember(rw, pos) { pos?.let { ilsReading(rw, it) } }
            val samples by vm.ilsSamples.collectAsState()
            val session by vm.ilsSession.collectAsState()
            val cur = remember(rw, pos) { pos?.let { IlsGeom.sample(rw, it) } }
            Column(
                Modifier.width(210.dp).background(MiniBg, RoundedCornerShape(10.dp)).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ILS ${rw.ident}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = NavGreen, modifier = Modifier.weight(1f))
                    Text("${fmtNm(r?.distThrNm)} NM", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Amber)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IlsIndicator(r, Modifier.size(70.dp))
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text("VS teraz", fontSize = 9.sp, color = MiniLabel)
                        Text("%+d".format((pos?.vsFpm ?: 0.0).toInt()), fontSize = 15.sp, fontFamily = FontFamily.Monospace, color = Amber, fontWeight = FontWeight.Bold)
                        Text("VS na 3°", fontSize = 9.sp, color = MiniLabel)
                        Text(r?.let { "-%d".format(it.vsForGsFpm.toInt()) } ?: "--", fontSize = 15.sp, fontFamily = FontFamily.Monospace, color = NavGreen, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    "Wys. ${pos?.altFt?.toInt() ?: "--"} / ścieżka ${r?.let { (Math.round(it.glideAltFt / 10) * 10).toString() } ?: "--"} ft",
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = if (r != null && pos != null && kotlin.math.abs(pos.altFt - r.glideAltFt) > 150) ZoneOrange else Color.White
                )
                if (cur != null && cur.distNm in -0.5..12.0) {
                    MiniProfile(if (session) samples else emptyList(), cur, Modifier.fillMaxWidth().height(56.dp))
                }
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
        Modifier.width(176.dp).background(MiniBg, RoundedCornerShape(10.dp)).padding(6.dp),
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
                MapBtn("−", color = Magenta, onClick = onMinus)
                Column(Modifier.weight(1f).clickable(onClick = onCenter), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("OBS", fontSize = 9.sp, color = MiniLabel)
                    Text(fmtDeg(obs), fontSize = 15.sp, fontFamily = FontFamily.Monospace, color = Magenta, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(when (r?.toFrom) { ToFrom.TO -> "TO"; ToFrom.FROM -> "FROM"; else -> "--" }, fontSize = 10.sp, color = Color.White)
                }
                MapBtn("+", color = Magenta, onClick = onPlus)
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

/** Mały profil pionowy: ścieżka 3° (zielona przerywana), przeleciana droga (pomarańczowa), samolot (fiolet). */
@Composable
private fun MiniProfile(samples: List<IlsSample>, cur: IlsSample, modifier: Modifier) {
    val maxNm = maxOf(10.0, cur.distNm)
    val maxFt = maxOf(3200.0, cur.altAboveThr + 300)
    Canvas(modifier.background(Color(0xFF07111C), RoundedCornerShape(6.dp))) {
        fun x(d: Double) = ((maxNm - d) / (maxNm + 0.5)).toFloat().coerceIn(0f, 1f) * size.width
        fun y(a: Double) = (1f - (a / maxFt).toFloat().coerceIn(0f, 1f)) * (size.height - 4f) + 2f
        val tan3 = Math.tan(Math.toRadians(3.0)) * pl.pilot.vornav.nav.Geo.FT_PER_NM
        drawLine(NavGreen, Offset(x(maxNm), y(maxNm * tan3)), Offset(x(0.0), y(0.0)), 3f,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        drawLine(Color(0xFF8FA3B8), Offset(x(0.0), y(0.0)), Offset(size.width, y(0.0)), 4f)   // pas
        if (samples.size >= 2) {
            val path = Path()
            samples.forEachIndexed { i, s -> if (i == 0) path.moveTo(x(s.distNm), y(s.altAboveThr)) else path.lineTo(x(s.distNm), y(s.altAboveThr)) }
            drawPath(path, Amber, style = Stroke(3f))
        }
        drawCircle(Magenta, 6f, Offset(x(cur.distNm), y(cur.altAboveThr)))
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
