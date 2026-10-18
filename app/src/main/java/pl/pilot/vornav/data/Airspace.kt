package pl.pilot.vornav.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Rezerwacja strefy z AUP/UUP. Czasy w ms (UTC), wysokości jak w AUP + przeliczone na stopy (jeśli się da). */
data class ZoneReservation(
    val start: Long,
    val end: Long,
    val lower: String,
    val upper: String,
    val lowerFt: Int?,
    val upperFt: Int?,
    val remarks: String,
    val status: String,
)

/** Strefa przestrzeni powietrznej z geometrią (pierścienie: lat,lon,lat,lon,...). */
class Zone(
    val designator: String,
    val type: String,
    val rings: List<DoubleArray>,
    val reservations: List<ZoneReservation>,
) {
    val latMin: Double; val latMax: Double; val lonMin: Double; val lonMax: Double
    val centerLat: Double; val centerLon: Double

    init {
        var a = 90.0; var b = -90.0; var c = 180.0; var d = -180.0
        var sLat = 0.0; var sLon = 0.0; var n = 0
        for (r in rings) {
            var i = 0
            while (i < r.size - 1) {
                val la = r[i]; val lo = r[i + 1]
                if (la < a) a = la; if (la > b) b = la; if (lo < c) c = lo; if (lo > d) d = lo
                sLat += la; sLon += lo; n++
                i += 2
            }
        }
        latMin = a; latMax = b; lonMin = c; lonMax = d
        centerLat = if (n > 0) sLat / n else 0.0
        centerLon = if (n > 0) sLon / n else 0.0
    }

    fun activeAt(t: Long): ZoneReservation? = reservations.firstOrNull { t >= it.start && t < it.end }

    /** Najbliższa rezerwacja trwająca teraz lub zaczynająca się później. */
    fun nextFrom(t: Long): ZoneReservation? = reservations.filter { it.end > t }.minByOrNull { it.start }

    /** Czy punkt leży w strefie (ray casting, wystarczające dla małych obszarów). */
    fun contains(lat: Double, lon: Double): Boolean {
        if (lat < latMin || lat > latMax || lon < lonMin || lon > lonMax) return false
        var inside = false
        for (r in rings) {
            val n = r.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val yi = r[2 * i]; val xi = r[2 * i + 1]
                val yj = r[2 * j]; val xj = r[2 * j + 1]
                if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
        }
        return inside
    }
}

/**
 * Pobieranie aktywnych stref (AUP/UUP) z serwisu PAŻP airspace.pansa.pl.
 * UUP to aktualizacja AUP w ciągu dnia, więc pobieramy najpierw UUP, a gdy jest pusty — AUP.
 * Odpowiedź jest zapisywana na dysku, żeby strefy były dostępne bez internetu.
 */
class AirspaceRepo(ctx: Context) {

    companion object {
        val URLS = listOf(
            "UUP" to "https://airspace.pansa.pl/map-configuration/uup",
            "AUP" to "https://airspace.pansa.pl/map-configuration/aup",
        )

        /** GND, F095, FL095, A055, 2500, 2500 FT AMSL, 750 M → stopy (null gdy nieznany format). */
        fun parseAltFt(s: String?): Int? {
            val t = s?.trim()?.uppercase()?.replace(" ", "") ?: return null
            if (t.isEmpty()) return null
            if (t == "GND" || t == "SFC" || t == "0") return 0
            if (t == "UNL" || t == "UNLTD") return 99999
            Regex("^FL?(\\d{2,3})$").find(t)?.let { return it.groupValues[1].toInt() * 100 }
            Regex("^A(\\d{2,3})$").find(t)?.let { return it.groupValues[1].toInt() * 100 }
            Regex("^(\\d+)M(AMSL|AGL|MSL)?$").find(t)?.let { return (it.groupValues[1].toDouble() * 3.28084).toInt() }
            Regex("^(\\d+)(FT)?(AMSL|AGL|MSL)?$").find(t)?.let { return it.groupValues[1].toInt() }
            return null
        }

        fun parseTime(s: String?): Long? {
            if (s.isNullOrBlank()) return null
            return runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
                ?: runCatching { LocalDateTime.parse(s.removeSuffix("Z")).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
                ?: s.toLongOrNull()
        }
    }

    private val file = File(ctx.filesDir, "airspace.json")
    private val meta = File(ctx.filesDir, "airspace.meta")

    /** (czas pobrania ms, źródło) z ostatniego udanego pobrania. */
    fun cacheInfo(): Pair<Long, String>? = runCatching {
        val p = meta.readText().split(";"); p[0].toLong() to p[1]
    }.getOrNull()

    suspend fun loadCached(): List<Zone> = withContext(Dispatchers.IO) {
        if (!file.exists()) emptyList() else runCatching { parse(file.readText()) }.getOrDefault(emptyList())
    }

    /** Pobiera strefy z PAŻP. Zwraca (strefy, źródło). Rzuca wyjątek, gdy żadne źródło nie odpowiedziało. */
    suspend fun download(): Pair<List<Zone>, String> = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for ((name, url) in URLS) {
            try {
                val text = get(url)
                val zones = parse(text)
                if (zones.isNotEmpty()) {
                    file.writeText(text)
                    meta.writeText("${System.currentTimeMillis()};$name")
                    return@withContext zones to name
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Brak danych o strefach")
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "VorNav-Android")
        c.setRequestProperty("Accept", "application/json")
        if (c.responseCode != 200) error("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    fun parse(text: String): List<Zone> {
        val root = JSONTokener(text).nextValue()
        val features: JSONArray = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("features") ?: root.optJSONArray("data") ?: JSONArray()
            else -> JSONArray()
        }
        val out = ArrayList<Zone>()
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val geom = f.optJSONObject("geometry") ?: continue
            val props = f.optJSONObject("properties") ?: f
            val rings = parseGeometry(geom)
            if (rings.isEmpty()) continue
            val resArr = props.optJSONArray("airspaceReservations") ?: JSONArray()
            val res = ArrayList<ZoneReservation>()
            for (k in 0 until resArr.length()) {
                val r = resArr.optJSONObject(k) ?: continue
                val start = parseTime(r.optString("startDate")) ?: continue
                val end = parseTime(r.optString("endDate")) ?: continue
                val lo = r.optString("lowerAltitude"); val up = r.optString("upperAltitude")
                res.add(
                    ZoneReservation(
                        start, end, lo, up, parseAltFt(lo), parseAltFt(up),
                        r.optString("remarks").takeIf { it != "null" }.orEmpty(),
                        r.optString("reservationStatus")
                    )
                )
            }
            if (res.isEmpty()) continue
            out.add(
                Zone(
                    props.optString("designator").ifBlank { props.optString("name", "?") },
                    props.optString("airspaceElementType"),
                    rings, res.sortedBy { it.start }
                )
            )
        }
        return out
    }

    /** GeoJSON Polygon / MultiPolygon → zewnętrzne pierścienie jako tablice lat,lon. */
    private fun parseGeometry(g: JSONObject): List<DoubleArray> {
        val coords = g.optJSONArray("coordinates") ?: return emptyList()
        fun ring(a: JSONArray): DoubleArray {
            val r = DoubleArray(a.length() * 2)
            for (i in 0 until a.length()) {
                val p = a.getJSONArray(i)
                r[2 * i] = p.getDouble(1); r[2 * i + 1] = p.getDouble(0)
            }
            return r
        }
        return when (g.optString("type")) {
            "Polygon" -> if (coords.length() > 0) listOf(ring(coords.getJSONArray(0))) else emptyList()
            "MultiPolygon" -> (0 until coords.length()).mapNotNull { k ->
                coords.getJSONArray(k).let { if (it.length() > 0) ring(it.getJSONArray(0)) else null }
            }
            else -> emptyList()
        }
    }
}
