package pl.drivemonitor

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Zgłaszanie prawidłowego ograniczenia prędkości.
 *  - Poprawka trafia od razu do lokalnej bazy (dla całej drogi OSM) i jest używana przy następnych jazdach.
 *  - Opcjonalnie: anonimowa notatka w OpenStreetMap – mapujący zweryfikują i poprawią mapę dla wszystkich.
 *  - W trakcie jazdy jedno dotknięcie tylko zapamiętuje miejsce („do uzupełnienia”), a wartość
 *    podaje się na postoju – żeby nie obsługiwać telefonu w ruchu.
 */
object LimitReports {

    data class Pending(val timeMs: Long, val lat: Double, val lon: Double, val wayId: Long?, val shownLimit: Int?)

    private val _pending = MutableStateFlow<List<Pending>>(emptyList())
    val pending: StateFlow<List<Pending>> = _pending
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "limit_reports.json")

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = file(ctx)
        if (!f.exists()) return
        _pending.value = runCatching {
            val arr = JSONArray(f.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Pending(
                    o.getLong("t"), o.getDouble("lat"), o.getDouble("lon"),
                    if (o.has("way")) o.getLong("way") else null,
                    if (o.has("limit")) o.getInt("limit") else null
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(ctx: Context) {
        val arr = JSONArray()
        _pending.value.forEach { p ->
            arr.put(JSONObject().apply {
                put("t", p.timeMs); put("lat", p.lat); put("lon", p.lon)
                p.wayId?.let { put("way", it) }; p.shownLimit?.let { put("limit", it) }
            })
        }
        file(ctx).writeText(arr.toString())
    }

    fun addPending(ctx: Context, p: Pending) {
        load(ctx)
        _pending.value = _pending.value + p
        save(ctx)
    }

    fun removePending(ctx: Context, p: Pending) {
        _pending.value = _pending.value - p
        save(ctx)
    }

    /**
     * Zapisuje prawidłowy limit. Zwraca opis wyniku do pokazania użytkownikowi.
     * Wysyłanie notatki do OSM odbywa się w tle.
     */
    fun submit(ctx: Context, lat: Double, lon: Double, wayId: Long?, shownLimit: Int?, correctKmh: Int, sendToOsm: Boolean): String {
        val parts = ArrayList<String>()
        if (wayId != null) {
            OnlineLimitCache.get(ctx).setOverride(wayId, correctKmh)
            parts += "Zapisano $correctKmh km/h dla tej drogi"
        } else {
            parts += "Nie rozpoznano drogi – poprawka lokalna niemożliwa"
        }
        if (sendToOsm) {
            Thread { sendOsmNote(lat, lon, wayId, shownLimit, correctKmh) }.start()
            parts += "notatka do OpenStreetMap wysłana"
        }
        return parts.joinToString(", ") + "."
    }

    /** Anonimowa notatka OSM (API 0.6 notes) – trafia do weryfikacji przez mapujących. */
    private fun sendOsmNote(lat: Double, lon: Double, wayId: Long?, shownLimit: Int?, correctKmh: Int) {
        val text = buildString {
            append("Ograniczenie prędkości na tej drodze wg kierowcy: $correctKmh km/h")
            shownLimit?.let { append(" (mapa podaje: $it km/h)") }
            wayId?.let { append(". Droga: https://www.openstreetmap.org/way/$it") }
            append(". Zgłoszone z aplikacji DriveMonitor – proszę o weryfikację ze znakiem (maxspeed).")
        }
        try {
            val body = "lat=$lat&lon=$lon&text=" + URLEncoder.encode(text, "UTF-8")
            val conn = URL("https://api.openstreetmap.org/api/0.6/notes").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000; conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "DriveMonitor/1.0 (Android)")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray()) }
            Log.i("LimitReports", "Notatka OSM: HTTP ${conn.responseCode}")
            conn.disconnect()
        } catch (e: Exception) {
            Log.w("LimitReports", "Nie wysłano notatki OSM", e)
        }
    }

    val COMMON_LIMITS = listOf(20, 30, 40, 50, 70, 90, 120)
}
