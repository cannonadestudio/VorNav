package pl.pilot.vornav.nav

import pl.pilot.vornav.data.Waypoint
import pl.pilot.vornav.loc.Position
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class ToFrom { TO, FROM }

data class NavReading(
    val inRange: Boolean,
    val vorValid: Boolean,
    /** Radial (magnetyczny, wg deklinacji stacji) na którym jest samolot — QDR. */
    val radialFrom: Double?,
    /** Kurs magnetyczny do stacji — QDM. */
    val bearingToMag: Double?,
    val toFrom: ToFrom?,
    /** Odchylenie wskazówki CDI w stopniach, + = wskazówka w prawo (leć w prawo). */
    val deviationDeg: Double?,
    val dmeValid: Boolean,
    /** Odległość skośna DME w NM (jak prawdziwe DME). */
    val dmeNm: Double?,
    /** Prędkość względem stacji DME, + = zbliżanie. */
    val dmeGsKt: Double?,
    /** Czas do stacji w minutach. */
    val ttsMin: Double?,
    val groundDistNm: Double,
    val rangeNm: Double,
)

data class NavSettings(
    val ignoreRange: Boolean = false,
    /** Używaj deklinacji stacji z bazy (tak jak jest skalibrowany prawdziwy VOR). */
    val useStationVariation: Boolean = true,
)

/**
 * „Wirtualny odbiornik” VOR/DME. Telefon nie odbiera VHF, więc wskazania są liczone
 * geometrycznie z pozycji samolotu i współrzędnych stacji — tak samo jak pokazałby
 * je prawdziwy przyrząd.
 */
object VorReceiver {

    fun stationVariation(st: Waypoint, s: NavSettings): Double =
        if (s.useStationVariation && st.variation != null) st.variation
        else Mag.declination(st.lat, st.lon, st.elevFt)

    /** Teoretyczny zasięg: horyzont radiowy ograniczony klasą mocy stacji. */
    fun rangeNm(st: Waypoint, altFt: Double): Double {
        val los = 1.23 * (sqrt(max(altFt, 50.0)) + sqrt(max(st.elevFt, 0.0)))
        val cap = when (st.power?.uppercase()) {
            "HIGH" -> 130.0
            "MEDIUM" -> 80.0
            "LOW" -> 40.0
            else -> 100.0
        }
        return min(los, cap)
    }

    fun compute(st: Waypoint, pos: Position, obs: Double, s: NavSettings): NavReading {
        val ground = Geo.distanceNm(st.lat, st.lon, pos.lat, pos.lon)
        val range = rangeNm(st, pos.altFt)
        val inRange = s.ignoreRange || ground <= range

        var radial: Double? = null; var qdm: Double? = null
        var tf: ToFrom? = null; var dev: Double? = null
        val vorValid = st.hasVor && inRange && ground > 0.2   // nad stacją: stożek ciszy
        if (st.hasVor && inRange) {
            val v = stationVariation(st, s)
            val brgFromTrue = Geo.bearingTrue(st.lat, st.lon, pos.lat, pos.lon)
            radial = Mag.trueToMag(brgFromTrue, v)
            qdm = Geo.norm360(radial + 180.0)
            if (vorValid) {
                val dFrom = Geo.norm180(radial - obs)
                if (abs(dFrom) <= 90.0) {
                    tf = ToFrom.FROM; dev = -dFrom
                } else {
                    tf = ToFrom.TO; dev = Geo.norm180(radial - obs - 180.0)
                }
            }
        }

        var dme: Double? = null; var dmeGs: Double? = null; var tts: Double? = null
        val dmeValid = st.hasDme && inRange
        if (dmeValid) {
            val dLat = st.dmeLat ?: st.lat
            val dLon = st.dmeLon ?: st.lon
            val g = Geo.distanceNm(dLat, dLon, pos.lat, pos.lon)
            val dh = (pos.altFt - st.elevFt) / Geo.FT_PER_NM
            dme = sqrt(g * g + dh * dh)
            val trk = pos.trackTrue
            if (trk != null && pos.gsKt > 2.0) {
                val brgTo = Geo.bearingTrue(pos.lat, pos.lon, dLat, dLon)
                val closing = pos.gsKt * cos(Math.toRadians(trk - brgTo))
                dmeGs = closing
                if (closing > 5.0) tts = g / closing * 60.0
            }
        }
        return NavReading(inRange, vorValid, radial, qdm, tf, dev, dmeValid, dme, dmeGs, tts, ground, range)
    }

    /** Pozycja z odczytu radial + DME (np. z prawdziwego przyrządu w samolocie). */
    fun fixFromRadialDme(st: Waypoint, radialMag: Double, dmeSlantNm: Double, altFt: Double, s: NavSettings): Pair<Double, Double> {
        val dh = (altFt - st.elevFt) / Geo.FT_PER_NM
        val ground = sqrt(max(0.0, dmeSlantNm * dmeSlantNm - dh * dh))
        val brgTrue = Mag.magToTrue(radialMag, stationVariation(st, s))
        return Geo.destination(st.dmeLat ?: st.lat, st.dmeLon ?: st.lon, brgTrue, ground)
    }

    /** Pozycja z przecięcia dwóch radiali. */
    fun fixFromTwoRadials(st1: Waypoint, r1: Double, st2: Waypoint, r2: Double, s: NavSettings): Pair<Double, Double>? =
        Geo.intersection(
            st1.lat, st1.lon, Mag.magToTrue(r1, stationVariation(st1, s)),
            st2.lat, st2.lon, Mag.magToTrue(r2, stationVariation(st2, s))
        )
}
