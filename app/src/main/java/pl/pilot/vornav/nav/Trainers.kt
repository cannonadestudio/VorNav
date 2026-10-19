package pl.pilot.vornav.nav

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

enum class HoldEntry(val label: String) {
    DIRECT("Bezpośrednie (direct)"),
    PARALLEL("Równoległe (parallel)"),
    TEARDROP("Kroplowe (teardrop)"),
}

/** Matematyka do trenerów: wejścia w holding, poprawki wiatru, kształt holdingu, ILS. */
object TrainerMath {

    /**
     * Wejście w holding wg ICAO (sektory 70°/110°).
     * hdgToFixMag — kurs samolotu przy dolocie do fixu, inboundMag — kurs dolotowy holdingu.
     */
    fun holdEntry(hdgToFixMag: Double, inboundMag: Double, rightTurns: Boolean): HoldEntry {
        val d = Geo.norm360(hdgToFixMag - inboundMag)
        return if (rightTurns) when {
            d <= 110.0 || d >= 290.0 -> HoldEntry.DIRECT
            d <= 180.0 -> HoldEntry.TEARDROP
            else -> HoldEntry.PARALLEL
        } else when {
            d >= 250.0 || d <= 70.0 -> HoldEntry.DIRECT
            d >= 180.0 -> HoldEntry.TEARDROP
            else -> HoldEntry.PARALLEL
        }
    }

    /** Kąt znoszenia (WCA): kurs = tor + WCA. Wiatr podany jako kierunek Z KTÓREGO wieje. */
    fun wca(trackMag: Double, windFromMag: Double, windKt: Double, tasKt: Double): Double {
        if (tasKt <= 1.0 || windKt <= 0.0) return 0.0
        val x = (windKt * sin(Math.toRadians(windFromMag - trackMag)) / tasKt).coerceIn(-1.0, 1.0)
        return Math.toDegrees(asin(x))
    }

    /** Składowa czołowa wiatru dla toru (+ = wiatr w twarz). */
    fun headwind(trackMag: Double, windFromMag: Double, windKt: Double): Double =
        windKt * cos(Math.toRadians(windFromMag - trackMag))

    /** Prędkość względem ziemi na torze. */
    fun groundSpeed(trackMag: Double, windFromMag: Double, windKt: Double, tasKt: Double): Double {
        val w = wca(trackMag, windFromMag, windKt, tasKt)
        return tasKt * cos(Math.toRadians(w)) - headwind(trackMag, windFromMag, windKt)
    }

    /**
     * Zalecenia dla holdingu: kurs na odcinku dolotowym, kurs na odcinku odlotowym (potrójna poprawka)
     * i czas odcinka odlotowego, aby dolotowy trwał [legSec].
     */
    data class HoldSolution(val inboundHdg: Double, val outboundHdg: Double, val outboundSec: Int, val wcaIn: Double)

    fun holdSolution(inboundMag: Double, windFromMag: Double, windKt: Double, tasKt: Double, legSec: Int): HoldSolution {
        val wIn = wca(inboundMag, windFromMag, windKt, tasKt)
        val out = Geo.norm360(inboundMag + 180.0)
        val wOut = wca(out, windFromMag, windKt, tasKt)
        val gsIn = groundSpeed(inboundMag, windFromMag, windKt, tasKt)
        val gsOut = groundSpeed(out, windFromMag, windKt, tasKt)
        val tOut = if (gsOut > 1) legSec * gsIn / gsOut else legSec.toDouble()
        return HoldSolution(
            Geo.norm360(inboundMag + wIn),
            Geo.norm360(out + 3.0 * wOut),
            Math.round(tOut).toInt().coerceIn(20, 180),
            wIn
        )
    }

    /** Kształt holdingu (bez wiatru) jako lista punktów lat/lon do narysowania na mapie. */
    fun holdShape(fixLat: Double, fixLon: Double, inboundTrue: Double, rightTurns: Boolean, tasKt: Double, legSec: Int): List<Pair<Double, Double>> {
        val legNm = tasKt * legSec / 3600.0
        val r = tasKt / (60.0 * PI)                     // promień zakrętu standardowego (180° w 60 s)
        val side = if (rightTurns) 90.0 else -90.0
        val outbound = Geo.norm360(inboundTrue + 180.0)
        val p1 = Geo.destination(fixLat, fixLon, outbound, legNm)                     // początek odcinka dolotowego
        val c1 = Geo.destination(fixLat, fixLon, inboundTrue + side, r)               // środek zakrętu za fixem
        val c2 = Geo.destination(p1.first, p1.second, inboundTrue + side, r)          // środek zakrętu na końcu odlotu
        val pts = ArrayList<Pair<Double, Double>>()
        pts.add(p1); pts.add(fixLat to fixLon)
        // zakręt 180° wokół c1: od fixu do początku odlotu
        for (k in 0..12) {
            val a = inboundTrue - side + k * (if (rightTurns) 15.0 else -15.0)
            pts.add(Geo.destination(c1.first, c1.second, a, r))
        }
        val q2 = Geo.destination(c2.first, c2.second, inboundTrue + side, r)
        pts.add(q2)
        // zakręt 180° wokół c2: z końca odlotu na kurs dolotowy
        for (k in 0..12) {
            val a = inboundTrue + side + k * (if (rightTurns) 15.0 else -15.0)
            pts.add(Geo.destination(c2.first, c2.second, a, r))
        }
        pts.add(p1)
        return pts
    }

    // ---------------- ILS (syntetyczny, z danych pasa) ----------------

    const val GS_ANGLE = 3.0
    const val LOC_FULL_SCALE = 2.5
    const val GS_FULL_SCALE = 0.7

    data class IlsReading(
        val locValid: Boolean,
        val gsValid: Boolean,
        /** + = wskazówka w prawo (leć w prawo), stopnie. */
        val locDevDeg: Double,
        /** + = wskazówka w górę (jesteś poniżej ścieżki), stopnie. */
        val gsDevDeg: Double,
        val distThrNm: Double,
        val glideAltFt: Double,
        val vsForGsFpm: Double,
    )

    /**
     * Syntetyczny ILS: antena LOC 1000 ft za końcem pasa, antena GS 1000 ft za progiem, ścieżka 3°.
     * hdgTrue — kierunek lądowania (od progu do końca pasa).
     */
    fun ils(
        thrLat: Double, thrLon: Double, thrElevFt: Double, hdgTrue: Double, lengthFt: Double,
        lat: Double, lon: Double, altFt: Double, gsKt: Double,
    ): IlsReading {
        val ftNm = Geo.FT_PER_NM
        val loc = Geo.destination(thrLat, thrLon, hdgTrue, (lengthFt + 1000.0) / ftNm)
        val gsAnt = Geo.destination(thrLat, thrLon, hdgTrue, 1000.0 / ftNm)
        val brgFromLoc = Geo.bearingTrue(loc.first, loc.second, lat, lon)
        val locAngle = Geo.norm180(brgFromLoc - (hdgTrue + 180.0))
        val dLoc = Geo.distanceNm(loc.first, loc.second, lat, lon)
        val dGs = Geo.distanceNm(gsAnt.first, gsAnt.second, lat, lon)
        val dThr = Geo.distanceNm(thrLat, thrLon, lat, lon)
        val gsAngle = Math.toDegrees(atan2(altFt - thrElevFt, dGs * ftNm))
        val glideAlt = thrElevFt + tan(Math.toRadians(GS_ANGLE)) * dGs * ftNm
        return IlsReading(
            locValid = abs(locAngle) <= 35.0 && dLoc <= 25.0,
            gsValid = abs(locAngle) <= 8.0 && dGs <= 10.0 && abs(gsAngle - GS_ANGLE) < 3.0,
            locDevDeg = locAngle,
            gsDevDeg = GS_ANGLE - gsAngle,
            distThrNm = dThr,
            glideAltFt = glideAlt,
            vsForGsFpm = gsKt * ftNm / 60.0 * tan(Math.toRadians(GS_ANGLE)),
        )
    }
}
