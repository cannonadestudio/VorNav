package pl.pilot.vornav.nav

import pl.pilot.vornav.data.Zone
import pl.pilot.vornav.data.ZoneReservation
import pl.pilot.vornav.loc.Position
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ZoneAlert(
    val zone: Zone,
    val res: ZoneReservation,
    val inside: Boolean,
    val distNm: Double,
    val minutes: Double?,
)

data class RouteZoneConflict(
    val legIndex: Int,
    val zone: Zone,
    val res: ZoneReservation,
    val activeNow: Boolean,
)

object ZoneAlerts {

    private val utcFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("UTC"))
    private val locFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    private val dayFmt = DateTimeFormatter.ofPattern("dd.MM").withZone(ZoneId.systemDefault())

    /** „07:30–13:30Z (09:30–15:30 LT)” */
    fun timeText(r: ZoneReservation): String {
        val s = Instant.ofEpochMilli(r.start); val e = Instant.ofEpochMilli(r.end)
        return "${utcFmt.format(s)}–${utcFmt.format(e)}Z (${locFmt.format(s)}–${locFmt.format(e)} LT ${dayFmt.format(s)})"
    }

    fun altText(r: ZoneReservation) = "${r.lower.ifBlank { "?" }} – ${r.upper.ifBlank { "?" }}"

    /** Czy wysokość samolotu (± margines) mieści się w pionowych granicach rezerwacji. Nieznane granice = tak. */
    fun verticallyRelevant(r: ZoneReservation, altFt: Double, marginFt: Int = 500): Boolean {
        val lo = r.lowerFt ?: return true
        val up = r.upperFt ?: return true
        return altFt >= lo - marginFt && altFt <= up + marginFt
    }

    /**
     * Strefy aktywne teraz, w których jest samolot, albo na które leci w ciągu [lookAheadNm].
     * Posortowane: najpierw „jesteś w strefie”, potem najbliższe.
     */
    fun check(zones: List<Zone>, pos: Position, now: Long, lookAheadNm: Double = 5.0): List<ZoneAlert> {
        val out = ArrayList<ZoneAlert>()
        val trk = pos.trackTrue
        val moving = trk != null && pos.gsKt > 20.0
        val ahead = if (moving) {
            generateSequence(0.5) { it + 0.5 }.takeWhile { it <= lookAheadNm }
                .map { d -> d to Geo.destination(pos.lat, pos.lon, trk!!, d) }.toList()
        } else emptyList()
        for (z in zones) {
            val r = z.activeAt(now) ?: continue
            if (!verticallyRelevant(r, pos.altFt)) continue
            if (z.contains(pos.lat, pos.lon)) {
                out.add(ZoneAlert(z, r, true, 0.0, null)); continue
            }
            val hit = ahead.firstOrNull { (_, p) -> z.contains(p.first, p.second) } ?: continue
            out.add(ZoneAlert(z, r, false, hit.first, hit.first / pos.gsKt * 60.0))
        }
        return out.sortedWith(compareBy({ !it.inside }, { it.distNm }))
    }

    /**
     * Strefy przecinane przez trasę, aktywne teraz lub w ciągu [hoursAhead] godzin,
     * na planowanej wysokości [altFt]. Próbkowanie odcinków co 1 NM.
     */
    fun routeConflicts(zones: List<Zone>, legs: List<LegPlan>, altFt: Double, now: Long, hoursAhead: Double = 12.0): List<RouteZoneConflict> {
        val until = now + (hoursAhead * 3600_000).toLong()
        val out = ArrayList<RouteZoneConflict>()
        for (leg in legs) {
            val a = leg.from; val b = leg.to
            val brg = Geo.bearingTrue(a.lat, a.lon, b.lat, b.lon)
            val n = maxOf(1, leg.distNm.toInt())
            val pts = (0..n).map { k -> Geo.destination(a.lat, a.lon, brg, leg.distNm * k / n) }
            for (z in zones) {
                val rs = z.reservations.filter { it.end > now && it.start < until && verticallyRelevant(it, altFt) }
                if (rs.isEmpty()) continue
                if (pts.none { z.contains(it.first, it.second) }) continue
                for (r in rs) out.add(RouteZoneConflict(leg.index, z, r, now >= r.start && now < r.end))
            }
        }
        return out.sortedWith(compareBy({ it.legIndex }, { it.res.start }))
    }
}
