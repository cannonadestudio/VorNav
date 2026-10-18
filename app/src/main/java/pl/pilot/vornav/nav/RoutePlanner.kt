package pl.pilot.vornav.nav

import pl.pilot.vornav.data.Waypoint
import pl.pilot.vornav.loc.Position
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/** Plan jednego odcinka trasy z instrukcją nawigacji po radialu. */
data class LegPlan(
    val index: Int,
    val from: Waypoint,
    val to: Waypoint,
    /** Kurs magnetyczny odcinka (na początku). */
    val courseMag: Double,
    val distNm: Double,
    /** Stacja VOR do prowadzenia po radialu (null = brak VOR na linii odcinka). */
    val navStation: Waypoint?,
    val mode: ToFrom?,
    /** OBS do ustawienia na NAV1. */
    val obs: Double?,
    /** Opis dla pilota, np. „FROM WAR radial 245”. */
    val navText: String,
    /** Identyfikacja punktu końcowego radialem/DME z innej stacji. */
    val checkpoint: String?,
)

/**
 * Planowanie trasy „od–do” do nawigacji po radialach:
 * - automatyczny dobór VOR po drodze (najkrótsza ścieżka, gdzie każdy odcinek zaczyna się lub kończy na VOR),
 * - dla każdego odcinka: stacja, tryb TO/FROM, OBS,
 * - punkty kontrolne (radial/DME) dla punktów zwrotnych,
 * - odchylenie od linii odcinka (XTK) i odległość wzdłuż odcinka.
 */
object RoutePlanner {

    private fun f3(d: Double): String = fmt3(d)
    fun fmt3(d: Double): String {
        var v = Math.round(Geo.norm360(d)).toInt() % 360
        if (v == 0) v = 360
        return String.format(Locale.US, "%03d", v)
    }

    /** Radial (magnetyczny wg deklinacji stacji), na którym leży punkt. */
    private fun radialOf(st: Waypoint, lat: Double, lon: Double, s: NavSettings) =
        Mag.trueToMag(Geo.bearingTrue(st.lat, st.lon, lat, lon), VorReceiver.stationVariation(st, s))

    fun planLegs(points: List<Waypoint>, vors: List<Waypoint>, s: NavSettings): List<LegPlan> {
        if (points.size < 2) return emptyList()
        return (0 until points.size - 1).map { i -> planLeg(i, points[i], points[i + 1], vors, s) }
    }

    private fun planLeg(i: Int, a: Waypoint, b: Waypoint, vors: List<Waypoint>, s: NavSettings): LegPlan {
        val dist = Geo.distanceNm(a.lat, a.lon, b.lat, b.lon)
        val course = Mag.trueToMag(Geo.bearingTrue(a.lat, a.lon, b.lat, b.lon), Mag.declination(a.lat, a.lon, a.elevFt))

        var st: Waypoint? = null; var mode: ToFrom? = null; var obs: Double? = null; var text: String

        if (b.hasVor) {
            // lecimy DO stacji: kurs do stacji = radial punktu startu + 180
            st = b; mode = ToFrom.TO
            obs = Geo.norm360(radialOf(b, a.lat, a.lon, s) + 180.0)
            text = "TO ${b.ident} ${b.freqText}, OBS ${f3(obs)} (inbound radial ${f3(obs + 180)})"
        } else if (a.hasVor) {
            st = a; mode = ToFrom.FROM
            obs = radialOf(a, b.lat, b.lon, s)
            text = "FROM ${a.ident} ${a.freqText}, radial ${f3(obs)} (OBS ${f3(obs)})"
        } else {
            // szukamy VOR leżącego na przedłużeniu linii odcinka (oba końce na tym samym radialu)
            var best: Triple<Waypoint, Double, ToFrom>? = null
            var bestErr = Double.MAX_VALUE
            for (v in vors) {
                val dA = Geo.distanceNm(v.lat, v.lon, a.lat, a.lon)
                val dB = Geo.distanceNm(v.lat, v.lon, b.lat, b.lon)
                if (maxOf(dA, dB) > VorReceiver.rangeNm(v, 3000.0)) continue
                if (minOf(dA, dB) < 1.0) continue
                val rA = radialOf(v, a.lat, a.lon, s); val rB = radialOf(v, b.lat, b.lon, s)
                val diff = abs(Geo.norm180(rA - rB))
                // odchylenie końca odcinka od radialu w NM
                val err = Math.toRadians(diff) * minOf(dA, dB)
                if (diff < 6.0 && err < 2.5 && err < bestErr) {
                    bestErr = err
                    best = if (dA < dB) Triple(v, rB, ToFrom.FROM) else Triple(v, Geo.norm360(rA + 180.0), ToFrom.TO)
                }
            }
            if (best != null) {
                st = best.first; obs = best.second; mode = best.third
                text = if (mode == ToFrom.FROM) "FROM ${st.ident} ${st.freqText}, radial ${f3(obs)} (VOR za punktem startu)"
                else "TO ${st.ident} ${st.freqText}, OBS ${f3(obs)} (VOR za punktem końcowym)"
            } else {
                text = "Brak VOR na linii odcinka — leć kursem ${f3(course)}°, pozycję kontroluj radialami"
            }
        }
        return LegPlan(i, a, b, course, dist, st, mode, obs, text, checkpoint(b, vors, s))
    }

    /** Opis punktu radialem i DME (VOR-DME) albo przecięciem dwóch radiali. */
    fun checkpoint(p: Waypoint, vors: List<Waypoint>, s: NavSettings): String? {
        val near = vors.filter { it.key != p.key }
            .map { it to Geo.distanceNm(it.lat, it.lon, p.lat, p.lon) }
            .filter { it.second in 2.0..60.0 }
            .sortedBy { it.second }
        val dme = near.firstOrNull { it.first.hasDme }
        if (dme != null) {
            val (v, d) = dme
            return "${p.ident} = R${f3(radialOf(v, p.lat, p.lon, s))} / D${String.format(Locale.US, "%.1f", d)} ${v.ident} ${v.freqText}"
        }
        if (near.size >= 2) {
            val (v1, _) = near[0]
            val v2 = near.drop(1).firstOrNull {
                val ang = abs(Geo.norm180(Geo.bearingTrue(p.lat, p.lon, v1.lat, v1.lon) - Geo.bearingTrue(p.lat, p.lon, it.first.lat, it.first.lon)))
                ang in 30.0..150.0
            }?.first ?: return null
            return "${p.ident} = R${f3(radialOf(v1, p.lat, p.lon, s))} ${v1.ident} × R${f3(radialOf(v2, p.lat, p.lon, s))} ${v2.ident}"
        }
        return null
    }

    /**
     * Najkrótsza trasa z [from] do [to] przez VOR: każdy odcinek ≤ [maxLegNm] i ma VOR na jednym z końców.
     * Zwraca listę punktów (z from i to) albo null, gdy nie da się takiej zbudować.
     */
    fun suggestViaVor(from: Waypoint, to: Waypoint, vors: List<Waypoint>, maxLegNm: Double): List<Waypoint>? {
        val direct = Geo.distanceNm(from.lat, from.lon, to.lat, to.lon)
        val cand = vors.filter {
            val d = Geo.distanceNm(from.lat, from.lon, it.lat, it.lon) + Geo.distanceNm(it.lat, it.lon, to.lat, to.lon)
            d <= direct * 1.35 + 15.0 && it.key != from.key && it.key != to.key
        }
        val nodes = listOf(from) + cand + listOf(to)
        val n = nodes.size
        val dist = DoubleArray(n) { Double.MAX_VALUE }
        val prev = IntArray(n) { -1 }
        val done = BooleanArray(n)
        dist[0] = 0.0
        while (true) {
            var u = -1
            for (k in 0 until n) if (!done[k] && dist[k] < Double.MAX_VALUE && (u == -1 || dist[k] < dist[u])) u = k
            if (u == -1 || u == n - 1) break
            done[u] = true
            for (v in 0 until n) {
                if (done[v] || v == u) continue
                if (!nodes[u].hasVor && !nodes[v].hasVor) continue
                val d = Geo.distanceNm(nodes[u].lat, nodes[u].lon, nodes[v].lat, nodes[v].lon)
                if (d > maxLegNm) continue
                val c = dist[u] + d + 3.0   // kara za każdy punkt zwrotny
                if (c < dist[v]) { dist[v] = c; prev[v] = u }
            }
        }
        if (prev[n - 1] == -1) return null
        val path = ArrayList<Waypoint>()
        var k = n - 1
        while (k != -1) { path.add(nodes[k]); k = prev[k] }
        return path.reversed()
    }

    /**
     * Odchylenie od linii odcinka: XTK w NM (+ = samolot na prawo od linii)
     * i odległość przebyta wzdłuż odcinka (ATD) w NM.
     */
    fun crossTrack(leg: LegPlan, pos: Position): Pair<Double, Double> {
        val a = leg.from; val b = leg.to
        val d13 = Geo.distanceNm(a.lat, a.lon, pos.lat, pos.lon) / Geo.R_NM
        val t13 = Math.toRadians(Geo.bearingTrue(a.lat, a.lon, pos.lat, pos.lon))
        val t12 = Math.toRadians(Geo.bearingTrue(a.lat, a.lon, b.lat, b.lon))
        val xt = asin((sin(d13) * sin(t13 - t12)).coerceIn(-1.0, 1.0))
        val at = acos((cos(d13) / cos(xt)).coerceIn(-1.0, 1.0))
        val sign = if (cos(t13 - t12) < 0) -1.0 else 1.0
        return xt * Geo.R_NM to sign * at * Geo.R_NM
    }
}
