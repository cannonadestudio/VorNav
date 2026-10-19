package pl.pilot.vornav.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.pilot.vornav.data.Runway
import pl.pilot.vornav.loc.Position
import pl.pilot.vornav.nav.Geo
import pl.pilot.vornav.nav.TrainerMath
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Próbka podejścia ILS. distNm > 0 = przed progiem (na podejściu). */
data class IlsSample(
    val distNm: Double,
    val xtkFt: Double,
    val altAboveThr: Double,
    val glideAboveThr: Double,
    val vs: Double,
    val vsTarget: Double,
    val locDots: Double,
    val gsDots: Double?,
)

data class IlsResult(
    val rw: Runway,
    val samples: List<IlsSample>,
    val landed: Boolean,
    val verdict: String,
    val touchdownFt: Double?,
    val touchdownVs: Double?,
    val touchdownXtkFt: Double?,
    val locMaxDots: Double,
    val locAvgDots: Double,
    val gsMaxDots: Double,
    val gsAvgDots: Double,
    val grade: String,
)

object IlsGeom {
    /** (odległość wzdłuż osi przed progiem NM, odchylenie boczne ft, + = na prawo od osi patrząc w kierunku lądowania). */
    fun alongCross(rw: Runway, lat: Double, lon: Double): Pair<Double, Double> {
        val d = Geo.distanceNm(rw.lat, rw.lon, lat, lon)
        val rel = Math.toRadians(Geo.bearingTrue(rw.lat, rw.lon, lat, lon) - (rw.hdgTrue + 180.0))
        return d * cos(rel) to -d * sin(rel) * Geo.FT_PER_NM
    }

    fun sample(rw: Runway, p: Position): IlsSample {
        val (along, xtk) = alongCross(rw, p.lat, p.lon)
        val r = ilsReading(rw, p)
        return IlsSample(
            along, xtk, p.altFt - rw.elevFt, r.glideAltFt - rw.elevFt,
            p.vsFpm ?: 0.0, -r.vsForGsFpm,
            r.locDevDeg / (TrainerMath.LOC_FULL_SCALE / 5),
            if (r.gsValid) r.gsDevDeg / (TrainerMath.GS_FULL_SCALE / 5) else null
        )
    }

    fun evaluate(rw: Runway, samples: List<IlsSample>, landed: Boolean, verdict: String, last: IlsSample?): IlsResult {
        val loc = samples.filter { it.distNm in 0.2..8.0 }.map { abs(it.locDots) }
        val gs = samples.filter { it.distNm in 0.5..6.0 && it.altAboveThr > 200 }.mapNotNull { it.gsDots?.let(::abs) }
        val lMax = loc.maxOrNull() ?: 0.0; val gMax = gs.maxOrNull() ?: 0.0
        val tdFt = if (landed && last != null) -last.distNm * Geo.FT_PER_NM else null
        val tdVs = if (landed) last?.vs else null
        val tdOk = landed && tdVs != null && tdVs > -400 && tdFt != null && tdFt in 0.0..2500.0
        val grade = when {
            !landed -> "Do poprawy"
            lMax < 1.0 && gMax < 1.0 && tdOk -> "Bardzo dobre"
            lMax < 2.5 && gMax < 2.5 -> "Dobre"
            else -> "Do poprawy"
        }
        return IlsResult(
            rw, samples, landed, verdict, tdFt, tdVs, if (landed) last?.xtkFt else null,
            lMax, loc.average().takeIf { !it.isNaN() } ?: 0.0, gMax, gs.average().takeIf { !it.isNaN() } ?: 0.0, grade
        )
    }
}

// ---------------- wykresy ----------------

data class ChartSeries(val pts: List<Pair<Double, Double>>, val color: Color, val dashed: Boolean = false, val width: Float = 4f)

/**
 * Prosty wykres liniowy. Oś X to odległość od progu (od lewej: daleko → próg),
 * dlatego jest odwrócona: xMax po lewej, xMin po prawej.
 */
@Composable
fun LineChart(
    title: String, series: List<ChartSeries>, xMin: Double, xMax: Double, yMin: Double, yMax: Double,
    yUnit: String, modifier: Modifier = Modifier, zeroLine: Boolean = false, marker: Pair<Double, Double>? = null,
) {
    Column(modifier) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Canvas(Modifier.fillMaxWidth().height(130.dp).background(Color(0xFF07111C), RoundedCornerShape(6.dp))) {
            val padL = 44f; val padB = 22f; val w = size.width - padL - 6f; val h = size.height - padB - 6f
            fun x(v: Double) = padL + ((xMax - v) / (xMax - xMin)).toFloat().coerceIn(0f, 1f) * w
            fun y(v: Double) = 6f + (1f - ((v - yMin) / (yMax - yMin)).toFloat().coerceIn(0f, 1f)) * h
            val txt = android.graphics.Paint().apply { color = android.graphics.Color.LTGRAY; textSize = 20f; isAntiAlias = true }
            for (k in 0..4) {
                val v = yMin + (yMax - yMin) * k / 4
                drawLine(Color(0xFF1E2E40), Offset(padL, y(v)), Offset(padL + w, y(v)), 1f)
                drawContext.canvas.nativeCanvas.drawText("%.0f".format(v), 2f, y(v) + 7f, txt)
            }
            val step = if (xMax - xMin > 6) 2.0 else 1.0
            var xv = xMin
            while (xv <= xMax + 1e-6) {
                drawContext.canvas.nativeCanvas.drawText("%.0f".format(xv), x(xv) - 8f, size.height - 4f, txt)
                xv += step
            }
            drawContext.canvas.nativeCanvas.drawText(yUnit, padL + 4f, 24f, txt)
            if (zeroLine) drawLine(Color(0xFF8FA3B8), Offset(padL, y(0.0)), Offset(padL + w, y(0.0)), 2f)
            for (s in series) {
                val pts = s.pts.filter { it.first in xMin..xMax }
                if (pts.size < 2) continue
                val path = Path()
                pts.forEachIndexed { i, (a, b) -> if (i == 0) path.moveTo(x(a), y(b)) else path.lineTo(x(a), y(b)) }
                drawPath(
                    path, s.color,
                    style = Stroke(s.width, pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null)
                )
            }
            marker?.let { (a, b) -> drawCircle(Magenta, 7f, Offset(x(a), y(b))) }
        }
    }
}

/** Profil pionowy podejścia: ścieżka 3° (przerywana), przeleciana wysokość, aktualna pozycja. */
@Composable
fun IlsProfileChart(samples: List<IlsSample>, current: IlsSample?, maxNm: Double = 10.0, modifier: Modifier = Modifier) {
    val glide = (0..20).map { k -> val d = maxNm * k / 20; d to Math.tan(Math.toRadians(TrainerMath.GS_ANGLE)) * (d * Geo.FT_PER_NM + 0) }
    LineChart(
        "Profil pionowy (ft nad progiem)",
        listOf(
            ChartSeries(glide, NavGreen, dashed = true, width = 3f),
            ChartSeries(samples.map { it.distNm to it.altAboveThr }, Amber, width = 4f),
        ),
        0.0, maxNm, 0.0, maxOf(3200.0, (samples.maxOfOrNull { it.altAboveThr } ?: 0.0) + 200), "ft",
        modifier, marker = current?.let { it.distNm.coerceIn(0.0, maxNm) to it.altAboveThr }
    )
}

/** Okno wyniku podejścia ILS z wykresami. */
@Composable
fun IlsResultDialog(res: IlsResult, onRetry: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Podejście ILS ${res.rw.airport} RWY ${res.rw.ident}") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Ocena: ${res.grade}", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    color = when (res.grade) { "Bardzo dobre" -> NavGreen; "Dobre" -> Amber; else -> ZoneRed }
                )
                Text(res.verdict, fontSize = 14.sp, color = if (res.landed) NavGreen else ZoneRed)
                if (res.landed) {
                    Text("Przyziemienie: ${res.touchdownFt?.let { "%.0f ft za progiem".format(it) } ?: "--"}", fontSize = 13.sp)
                    Text("VS w przyziemieniu: ${res.touchdownVs?.let { "%.0f ft/min".format(it) } ?: "--"}" +
                        (res.touchdownVs?.let { if (it < -400) " — twarde, zmniejsz opadanie przed przyziemieniem" else " ✓" } ?: ""), fontSize = 13.sp)
                    Text("Odchylenie od osi pasa: ${res.touchdownXtkFt?.let { "%.0f ft %s".format(abs(it), if (it >= 0) "w prawo" else "w lewo") } ?: "--"}", fontSize = 13.sp)
                }
                Text("LOC: maks. %.1f kropki, średnio %.1f".format(res.locMaxDots, res.locAvgDots), fontSize = 13.sp)
                Text("GS: maks. %.1f kropki, średnio %.1f".format(res.gsMaxDots, res.gsAvgDots), fontSize = 13.sp)
                val maxNm = minOf(10.0, (res.samples.maxOfOrNull { it.distNm } ?: 10.0).coerceAtLeast(2.0))
                val minNm = minOf(0.0, res.samples.minOfOrNull { it.distNm } ?: 0.0)
                IlsProfileChart(res.samples, null, maxNm)
                val xMax = res.samples.maxOfOrNull { abs(it.xtkFt) }?.coerceIn(200.0, 6000.0) ?: 500.0
                LineChart(
                    "Odchylenie boczne od osi (ft, + w prawo)",
                    listOf(ChartSeries(res.samples.map { it.distNm to it.xtkFt }, Color(0xFF29B6F6))),
                    minNm, maxNm, -xMax, xMax, "ft", zeroLine = true
                )
                LineChart(
                    "Prędkość pionowa (ft/min): Twoja i wymagana na ścieżce",
                    listOf(
                        ChartSeries(res.samples.map { it.distNm to it.vsTarget }, NavGreen, dashed = true, width = 3f),
                        ChartSeries(res.samples.map { it.distNm to it.vs }, Amber),
                    ),
                    minNm, maxNm, -1500.0, 500.0, "ft/min", zeroLine = true
                )
            }
        },
        confirmButton = { TextButton(onClick = onRetry) { Text("Powtórz") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Zamknij") } }
    )
}
