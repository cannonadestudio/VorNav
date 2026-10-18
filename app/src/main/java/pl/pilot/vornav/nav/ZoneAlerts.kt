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

/** Ostrzeżenie do wyświetlenia: poziom 2 = czerwone, 1 = pomarańczowe, 0 = informacja. */
data class AirAlert(
    val key: String,
    val title: String,
    val detail: String,
    val level: Int,
    val distNm: Double,
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

    private fun aheadPoints(pos: Position, lookAheadNm: Double): List<Pair<Double, Pair<Double, Double>>> {
        val trk = pos.trackTrue ?: return emptyList()
        if (pos.gsKt <= 20.0) return emptyList()
        return generateSequence(0.5) { it + 0.5 }.takeWhile { it <= lookAheadNm }
            .map { d -> d to Geo.destination(pos.lat, pos.lon, trk, d) }.toList()
    }

    /**
     * Wszystkie ostrzeżenia: aktywne strefy z AUP/UUP oraz strefy z AIP (openAIP):
     * P — zawsze czerwone/pomarańczowe, R i D — pomarańczowe (aktywność sprawdź w AUP),
     * CTR/TMA/ATZ/RMZ/TMZ — informacja o zbliżaniu się do granicy.
     */
    fun allAlerts(
        zones: List<Zone>,
        areas: List<pl.pilot.vornav.data.AirspaceArea>,
        pos: Position,
        now: Long,
        lookAheadNm: Double = 5.0,
    ): List<AirAlert> {
        val out = ArrayList<AirAlert>()
        for (a in check(zones, pos, now, lookAheadNm)) {
            out.add(
                AirAlert(
                    "AUP:${a.zone.designator}",
                    if (a.inside) "JESTEŚ W AKTYWNEJ STREFIE ${a.zone.designator}"
                    else "Aktywna strefa ${a.zone.designator} za ${fmtNm(a.distNm)} NM" + (a.minutes?.let { " (${fmtMin(it)})" } ?: ""),
                    "${altText(a.res)} • ${timeText(a.res)}" + if (a.res.remarks.isNotBlank()) " • ${a.res.remarks}" else "",
                    if (a.inside) 2 else 1, a.distNm
                )
            )
        }
        val ahead = aheadPoints(pos, lookAheadNm)
        val aupNames = zones.map { norm(it.designator) }.toSet()
        for (ar in areas) {
            val c = ar.category
            val relevantCat = c == pl.pilot.vornav.data.AspCategory.RPD || c == pl.pilot.vornav.data.AspCategory.CTR ||
                c == pl.pilot.vornav.data.AspCategory.TMA || c == pl.pilot.vornav.data.AspCategory.ATZ ||
                c == pl.pilot.vornav.data.AspCategory.RMZ
            if (!relevantCat) continue
            if (norm(ar.name) in aupNames) continue          // już obsłużona przez AUP
            if (!ar.verticallyContains(pos.altFt, 300)) continue
            val inside = ar.contains(pos.lat, pos.lon)
            val isRpd = c == pl.pilot.vornav.data.AspCategory.RPD
            if (inside) {
                if (!isRpd) continue                           // CTR/TMA nad nami pokazuje linia „Przestrzeń w Twojej pozycji”
                val prohibited = ar.type == 3
                out.add(
                    AirAlert(
                        "ASP:${ar.name}",
                        if (prohibited) "JESTEŚ W STREFIE ZAKAZANEJ ${ar.name}" else "Jesteś w strefie ${ar.name} (${ar.label})",
                        "${ar.lowerText}–${ar.upperText}" + if (!prohibited) " • sprawdź aktywność w AUP/NOTAM" else "",
                        if (prohibited) 2 else 1, 0.0
                    )
                )
                continue
            }
            val hit = ahead.firstOrNull { (_, p) -> ar.contains(p.first, p.second) } ?: continue
            val d = hit.first
            val mins = d / pos.gsKt * 60.0
            out.add(
                AirAlert(
                    "ASP:${ar.name}",
                    when {
                        ar.type == 3 -> "STREFA ZAKAZANA ${ar.name} za ${fmtNm(d)} NM (${fmtMin(mins)})"
                        isRpd -> "${ar.label} ${ar.name} za ${fmtNm(d)} NM (${fmtMin(mins)})"
                        else -> "Granica ${ar.name} (${ar.label}) za ${fmtNm(d)} NM"
                    },
                    "${ar.lowerText}–${ar.upperText}" + if (isRpd && ar.type != 3) " • sprawdź aktywność w AUP/NOTAM" else "",
                    when { ar.type == 3 -> 1; isRpd -> 1; else -> 0 }, d
                )
            )
        }
        return out.distinctBy { it.key }.sortedWith(compareBy({ -it.level }, { it.distNm }))
    }

    private fun norm(s: String) = s.uppercase().replace(" ", "")

    private fun fmtNm(d: Double) = String.format(java.util.Locale.US, if (d < 10) "%.1f" else "%.0f", d)
    private fun fmtMin(m: Double): String {
        val t = Math.round(m * 60).toInt(); return String.format(java.util.Locale.US, "%d:%02d", t / 60, t % 60)
    }
}
