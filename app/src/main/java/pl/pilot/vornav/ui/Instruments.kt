package pl.pilot.vornav.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import pl.pilot.vornav.nav.NavReading
import pl.pilot.vornav.nav.ToFrom
import kotlin.math.cos
import kotlin.math.sin

val Magenta = Color(0xFFE040FB)
val NavGreen = Color(0xFF3FD07F)
val Amber = Color(0xFFFFC107)
val PanelBg = Color(0xFF0F1C2C)

/**
 * Wskaźnik CDI/OBS: róża kierunków obrócona tak, że wybrany OBS jest u góry,
 * wskazówka odchylenia (pełna skala ±10°, 5 kropek po 2°), flaga TO/FROM i flaga OFF.
 */
@Composable
fun CdiIndicator(obs: Double, reading: NavReading?, modifier: Modifier = Modifier) {
    val paint = remember {
        Paint().apply { color = android.graphics.Color.WHITE; textAlign = Paint.Align.CENTER; isAntiAlias = true; isFakeBoldText = true }
    }
    Canvas(modifier.aspectRatio(1f)) {
        val r = size.minDimension / 2f
        val c = center
        drawCircle(Color(0xFF07111C), r, c)
        drawCircle(Color(0xFF5A6B7D), r, c, style = Stroke(3f))
        paint.textSize = r * 0.13f

        for (deg in 0 until 360 step 5) {
            val a = Math.toRadians(deg - obs - 90.0)
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            val len = when { deg % 30 == 0 -> r * 0.13f; deg % 10 == 0 -> r * 0.08f; else -> r * 0.04f }
            val ro = r * 0.96f
            drawLine(
                Color.White, Offset(c.x + ca * ro, c.y + sa * ro), Offset(c.x + ca * (ro - len), c.y + sa * (ro - len)),
                strokeWidth = if (deg % 30 == 0) 3f else 1.5f
            )
            if (deg % 30 == 0) {
                val label = when (deg) { 0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"; else -> (deg / 10).toString() }
                val rl = r * 0.70f
                drawContext.canvas.nativeCanvas.drawText(label, c.x + ca * rl, c.y + sa * rl + paint.textSize / 3f, paint)
            }
        }
        // znacznik (lubber line) u góry
        val tri = Path().apply {
            moveTo(c.x, c.y - r * 0.80f); lineTo(c.x - r * 0.06f, c.y - r * 0.98f); lineTo(c.x + r * 0.06f, c.y - r * 0.98f); close()
        }
        drawPath(tri, Amber)

        // strzałka kursu (OBS)
        val head = Path().apply {
            moveTo(c.x, c.y - r * 0.60f); lineTo(c.x - r * 0.07f, c.y - r * 0.47f); lineTo(c.x + r * 0.07f, c.y - r * 0.47f); close()
        }
        drawPath(head, Magenta)
        drawLine(Magenta, Offset(c.x, c.y - r * 0.47f), Offset(c.x, c.y - r * 0.44f), 6f)
        drawLine(Magenta, Offset(c.x, c.y + r * 0.44f), Offset(c.x, c.y + r * 0.60f), 6f)

        // kropki skali odchylenia
        val dot = r * 0.08f
        for (k in -5..5) {
            if (k == 0) continue
            drawCircle(Color.White, r * 0.022f, Offset(c.x + k * dot, c.y), style = Stroke(2.5f))
        }
        drawCircle(Color.White, r * 0.03f, c)

        val valid = reading?.vorValid == true && reading.deviationDeg != null
        if (valid) {
            val dev = reading!!.deviationDeg!!.coerceIn(-10.0, 10.0)
            val x = c.x + (dev / 2.0).toFloat() * dot
            drawLine(Magenta, Offset(x, c.y - r * 0.42f), Offset(x, c.y + r * 0.42f), strokeWidth = r * 0.035f)

            val tfY = if (reading.toFrom == ToFrom.TO) c.y - r * 0.22f else c.y + r * 0.22f
            val up = reading.toFrom == ToFrom.TO
            val s = r * 0.08f
            val tf = Path().apply {
                val tx = c.x + r * 0.30f
                if (up) { moveTo(tx, tfY - s); lineTo(tx - s, tfY + s * 0.6f); lineTo(tx + s, tfY + s * 0.6f) }
                else { moveTo(tx, tfY + s); lineTo(tx - s, tfY - s * 0.6f); lineTo(tx + s, tfY - s * 0.6f) }
                close()
            }
            drawPath(tf, Color.White)
        } else {
            // flaga OFF
            val w = r * 0.42f; val h = r * 0.18f
            val tl = Offset(c.x - r * 0.62f, c.y - r * 0.30f)
            drawRect(Color(0xFFD32F2F), tl, Size(w, h))
            paint.textSize = r * 0.13f
            drawContext.canvas.nativeCanvas.drawText("OFF", tl.x + w / 2, tl.y + h * 0.74f, paint)
        }
    }
}

/** Strzałka kierunku do celu względem kursu (toru) samolotu. */
@Composable
fun RelativeArrow(relativeDeg: Double?, modifier: Modifier = Modifier) {
    Canvas(modifier.aspectRatio(1f)) {
        val r = size.minDimension / 2f
        val c = center
        drawCircle(Color(0xFF07111C), r, c)
        drawCircle(Color(0xFF5A6B7D), r, c, style = Stroke(2f))
        // samolot u dołu, nos do góry
        drawLine(Color.White, Offset(c.x, c.y - r * 0.15f), Offset(c.x, c.y + r * 0.2f), 4f)
        drawLine(Color.White, Offset(c.x - r * 0.2f, c.y), Offset(c.x + r * 0.2f, c.y), 4f)
        drawLine(Color.White, Offset(c.x - r * 0.08f, c.y + r * 0.18f), Offset(c.x + r * 0.08f, c.y + r * 0.18f), 3f)
        if (relativeDeg != null) {
            rotate(relativeDeg.toFloat(), c) {
                val p = Path().apply {
                    moveTo(c.x, c.y - r * 0.92f)
                    lineTo(c.x - r * 0.16f, c.y - r * 0.62f)
                    lineTo(c.x + r * 0.16f, c.y - r * 0.62f)
                    close()
                }
                drawPath(p, NavGreen)
                drawLine(NavGreen, Offset(c.x, c.y - r * 0.62f), Offset(c.x, c.y - r * 0.35f), 8f)
            }
        }
    }
}

val NdbBrown = Color(0xFFFFB74D)

/**
 * Wskaźnik ADF/RMI. Róża obrócona tak, że bieżący kurs (HDG lub TRK) jest u góry,
 * wskazówka pokazuje radiostację: grot = QDM, ogon = QDR, kąt od góry = kąt kursowy (RB).
 * Bez kursu róża jest „północą do góry”, a wskazówka pokazuje sam QDM.
 */
@Composable
fun AdfIndicator(reading: pl.pilot.vornav.nav.AdfReading?, modifier: Modifier = Modifier) {
    val paint = remember {
        Paint().apply { color = android.graphics.Color.WHITE; textAlign = Paint.Align.CENTER; isAntiAlias = true; isFakeBoldText = true }
    }
    Canvas(modifier.aspectRatio(1f)) {
        val r = size.minDimension / 2f
        val c = center
        val cardUp = reading?.headingMag ?: 0.0
        drawCircle(Color(0xFF07111C), r, c)
        drawCircle(Color(0xFF5A6B7D), r, c, style = Stroke(3f))
        paint.textSize = r * 0.13f
        for (deg in 0 until 360 step 5) {
            val a = Math.toRadians(deg - cardUp - 90.0)
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            val len = when { deg % 30 == 0 -> r * 0.13f; deg % 10 == 0 -> r * 0.08f; else -> r * 0.04f }
            val ro = r * 0.96f
            drawLine(
                Color.White, Offset(c.x + ca * ro, c.y + sa * ro), Offset(c.x + ca * (ro - len), c.y + sa * (ro - len)),
                strokeWidth = if (deg % 30 == 0) 3f else 1.5f
            )
            if (deg % 30 == 0) {
                val label = when (deg) { 0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"; else -> (deg / 10).toString() }
                val rl = r * 0.70f
                drawContext.canvas.nativeCanvas.drawText(label, c.x + ca * rl, c.y + sa * rl + paint.textSize / 3f, paint)
            }
        }
        // znacznik kursu u góry
        val tri = Path().apply {
            moveTo(c.x, c.y - r * 0.80f); lineTo(c.x - r * 0.06f, c.y - r * 0.98f); lineTo(c.x + r * 0.06f, c.y - r * 0.98f); close()
        }
        drawPath(tri, Amber)
        // sylwetka samolotu
        drawLine(Color(0xFF8FA3B8), Offset(c.x, c.y - r * 0.18f), Offset(c.x, c.y + r * 0.22f), 5f)
        drawLine(Color(0xFF8FA3B8), Offset(c.x - r * 0.22f, c.y), Offset(c.x + r * 0.22f, c.y), 5f)
        drawLine(Color(0xFF8FA3B8), Offset(c.x - r * 0.09f, c.y + r * 0.2f), Offset(c.x + r * 0.09f, c.y + r * 0.2f), 4f)

        if (reading != null && reading.inRange) {
            val angle = (reading.relBearing ?: reading.qdmMag).toFloat()
            rotate(angle, c) {
                val head = Path().apply {
                    moveTo(c.x, c.y - r * 0.88f)
                    lineTo(c.x - r * 0.10f, c.y - r * 0.62f)
                    lineTo(c.x + r * 0.10f, c.y - r * 0.62f)
                    close()
                }
                drawPath(head, NdbBrown)
                drawLine(NdbBrown, Offset(c.x, c.y - r * 0.62f), Offset(c.x, c.y + r * 0.80f), r * 0.04f)
                drawLine(NdbBrown, Offset(c.x - r * 0.08f, c.y + r * 0.80f), Offset(c.x + r * 0.08f, c.y + r * 0.80f), r * 0.04f)
            }
        } else {
            val w = r * 0.42f; val h = r * 0.18f
            val tl = Offset(c.x - r * 0.62f, c.y - r * 0.40f)
            drawRect(Color(0xFFD32F2F), tl, Size(w, h))
            drawContext.canvas.nativeCanvas.drawText("ADF", tl.x + w / 2, tl.y + h * 0.74f, paint)
        }
    }
}
