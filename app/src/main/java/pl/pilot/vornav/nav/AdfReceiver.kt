package pl.pilot.vornav.nav

import pl.pilot.vornav.data.Waypoint
import pl.pilot.vornav.loc.Position
import kotlin.math.cos
import kotlin.math.sqrt

enum class HdgSource(val label: String) { MANUAL("HDG z żyrobusoli"), TRACK("TRK z GPS"), NONE("brak kursu") }

data class AdfReading(
    val inRange: Boolean,
    /** Namiar magnetyczny na stację (QDM) — wskazanie RMI. */
    val qdmMag: Double,
    /** Namiar od stacji (QDR). */
    val qdrMag: Double,
    /** Kąt kursowy radiostacji (RB) — wskazanie klasycznego ADF; null gdy brak kursu. */
    val relBearing: Double?,
    val headingMag: Double?,
    val headingSource: HdgSource,
    val distNm: Double,
    /** Dla NDB-DME: odległość skośna. */
    val dmeNm: Double?,
    val ttsMin: Double?,
    val rangeNm: Double,
)

/**
 * „Wirtualny ADF”. Podobnie jak VOR — wskazania liczone z GPS i współrzędnych NDB.
 * Klasyczny ADF pokazuje kąt względem osi samolotu (kursu), więc najlepiej podawać HDG
 * z żyrobusoli; bez tego używany jest kurs po ziemi (TRK) z GPS, który przy wietrze
 * różni się od kursu o kąt znoszenia.
 */
object AdfReceiver {

    /** Zasięg użytkowy NDB (fala przyziemna — nie zależy od wysokości jak VHF). */
    fun rangeNm(st: Waypoint): Double = when (st.power?.uppercase()) {
        "HIGH" -> 75.0
        "MEDIUM" -> 50.0
        "LOW" -> 25.0
        else -> 40.0
    }

    fun compute(st: Waypoint, pos: Position, manualHdgMag: Double?, s: NavSettings): AdfReading {
        val decl = Mag.declination(pos.lat, pos.lon, pos.altFt)
        val qdm = Mag.trueToMag(Geo.bearingTrue(pos.lat, pos.lon, st.lat, st.lon), decl)
        val dist = Geo.distanceNm(pos.lat, pos.lon, st.lat, st.lon)
        val range = rangeNm(st)
        val inRange = s.ignoreRange || dist <= range

        val trkMag = pos.trackTrue?.takeIf { pos.gsKt > 3.0 }?.let { Mag.trueToMag(it, decl) }
        val (hdg, src) = when {
            manualHdgMag != null -> manualHdgMag to HdgSource.MANUAL
            trkMag != null -> trkMag to HdgSource.TRACK
            else -> null to HdgSource.NONE
        }
        val rel = hdg?.let { Geo.norm360(qdm - it) }

        val dme = if (st.hasDme) {
            val g = Geo.distanceNm(pos.lat, pos.lon, st.dmeLat ?: st.lat, st.dmeLon ?: st.lon)
            val dh = (pos.altFt - st.elevFt) / Geo.FT_PER_NM
            sqrt(g * g + dh * dh)
        } else null

        val tts = pos.trackTrue?.let { trk ->
            val closing = pos.gsKt * cos(Math.toRadians(trk - Mag.magToTrue(qdm, decl)))
            if (closing > 5.0) dist / closing * 60.0 else null
        }
        return AdfReading(inRange, qdm, Geo.norm360(qdm + 180.0), rel, hdg, src, dist, dme, tts, range)
    }
}
