package pl.pilot.vornav.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Grupy przestrzeni do włączania/wyłączania na mapie. */
enum class AspCategory(val label: String, val color: Int, val defaultOn: Boolean) {
    CTR("CTR / MCTR", 0xFFD32F2F.toInt(), true),
    TMA("TMA / CTA", 0xFF1565C0.toInt(), true),
    ATZ("ATZ / MATZ", 0xFF8E24AA.toInt(), true),
    TRA("TRA / TSA", 0xFF2E7D32.toInt(), true),
    RPD("R / P / D", 0xFFB71C1C.toInt(), true),
    RMZ("RMZ / TMZ", 0xFFF9A825.toInt(), true),
    ROUTE("MRT / drogi lotnicze", 0xFF00838F.toInt(), false),
    FIR("FIR / FIS / sektory", 0xFF546E7A.toInt(), false),
    OTHER("Inne (szybowcowe, ADIZ…)", 0xFF757575.toInt(), false);

    companion object {
        /** Kody typów openAIP → grupa. */
        fun of(type: Int): AspCategory = when (type) {
            4, 36 -> CTR
            7, 26 -> TMA
            13, 14 -> ATZ
            8, 9 -> TRA
            1, 2, 3, 17, 18, 19, 29, 31 -> RPD
            5, 6 -> RMZ
            15, 16, 30 -> ROUTE
            10, 11, 27, 32, 33 -> FIR
            else -> OTHER
        }

        private val TYPE_NAMES = arrayOf(
            "Inna", "R", "D", "P", "CTR", "TMZ", "RMZ", "TMA", "TRA", "TSA", "FIR", "UIR", "ADIZ", "ATZ", "MATZ",
            "AWY", "MTR", "Alert", "Warning", "Protected", "HTZ", "Szybowcowa", "TRP", "TIZ", "TIA", "MTA", "CTA",
            "Sektor ACC", "Sportowa", "Ogr. przelotu", "MRT", "TFR", "Sektor VFR", "Sektor FIS", "LTA", "UTA", "MCTR"
        )

        fun typeName(type: Int) = TYPE_NAMES.getOrElse(type) { "Typ $type" }
    }
}

/** Strefa z openAIP (statyczna przestrzeń z AIP). */
class AirspaceArea(
    val name: String,
    val type: Int,
    val icaoClass: Int,
    val country: String,
    val lowerText: String,
    val upperText: String,
    val lowerFt: Int,
    val upperFt: Int,
    val rings: List<DoubleArray>,
) {
    val category = AspCategory.of(type)
    val classLetter: String get() = if (icaoClass in 0..6) "ABCDEFG"[icaoClass].toString() else ""
    val label: String get() = AspCategory.typeName(type) + if (classLetter.isNotEmpty()) " ($classLetter)" else ""

    val latMin: Double; val latMax: Double; val lonMin: Double; val lonMax: Double
    val centerLat: Double; val centerLon: Double

    init {
        var a = 90.0; var b = -90.0; var c = 180.0; var d = -180.0
        var sLat = 0.0; var sLon = 0.0; var n = 0
        for (r in rings) {
            var i = 0
            while (i < r.size - 1) {
                val la = r[i]; val lo = r[i + 1]
                if (la < a) a = la
                if (la > b) b = la
                if (lo < c) c = lo
                if (lo > d) d = lo
                sLat += la; sLon += lo; n++
                i += 2
            }
        }
        latMin = a; latMax = b; lonMin = c; lonMax = d
        centerLat = if (n > 0) sLat / n else 0.0
        centerLon = if (n > 0) sLon / n else 0.0
    }

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

    fun verticallyContains(altFt: Double, marginFt: Int = 0): Boolean =
        altFt >= lowerFt - marginFt && altFt <= upperFt + marginFt
}

/**
 * Przestrzeń powietrzna z openAIP (openaip.net, licencja CC BY-NC 4.0) — pliki krajów
 * `{kod}_asp.geojson`, aktualizowane przez openAIP codziennie. Zapis na dysku, czytanie strumieniowe.
 */
class StaticAirspaceRepo(ctx: Context) {

    companion object {
        const val BASE = "https://s3.openaip.net/openaip-system-exports/"

        val EUROPE = listOf(
            "PL", "DE", "CZ", "SK", "LT", "LV", "EE", "UA", "BY", "AT", "CH", "HU", "SI", "HR", "RO", "BG",
            "RS", "BA", "ME", "MK", "AL", "GR", "IT", "FR", "ES", "PT", "BE", "NL", "LU", "DK", "SE", "NO",
            "FI", "GB", "IE", "IS", "MD", "MT", "CY", "LI"
        )
        val NEIGHBOURS = listOf("PL", "DE", "CZ", "SK", "LT", "UA", "BY")
    }

    private val dir = File(ctx.filesDir, "asp").apply { mkdirs() }

    fun files(): List<File> = dir.listFiles { f -> f.name.endsWith(".geojson") }?.sortedBy { it.name } ?: emptyList()

    fun lastUpdate(): Long = files().maxOfOrNull { it.lastModified() } ?: 0L

    fun storedCountries(): List<String> = files().map { it.name.substringBefore(".").uppercase() }

    /** Pobiera pliki krajów. Kraje spoza listy są usuwane. Zwraca liczbę pobranych plików. */
    suspend fun download(countries: List<String>, progress: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        val wanted = countries.map { it.trim().lowercase() }.filter { it.length == 2 }.toSet()
        files().filter { it.name.substringBefore(".") !in wanted }.forEach { it.delete() }
        var ok = 0
        val errors = ArrayList<String>()
        for ((k, cc) in wanted.withIndex()) {
            progress("Pobieranie ${cc.uppercase()} (${k + 1}/${wanted.size})…")
            try {
                val c = URL("$BASE${cc}_asp.geojson").openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 60000
                c.setRequestProperty("User-Agent", "VorNav-Android")
                if (c.responseCode != 200) error("HTTP ${c.responseCode}")
                val tmp = File(dir, "$cc.tmp")
                c.inputStream.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
                tmp.renameTo(File(dir, "$cc.geojson"))
                ok++
            } catch (e: Exception) {
                errors.add("${cc.uppercase()}: ${e.message}")
            }
        }
        if (errors.isNotEmpty()) progress("Błędy: ${errors.joinToString("; ")}")
        ok
    }

    suspend fun loadAll(): List<AirspaceArea> = withContext(Dispatchers.IO) {
        files().flatMap { f -> runCatching { parseFile(f) }.getOrDefault(emptyList()) }
    }

    // ---------- strumieniowe czytanie GeoJSON (pliki mogą mieć kilkanaście MB) ----------

    private class Limit(var value: Double = 0.0, var unit: Int = 1, var datum: Int = 0)

    private fun parseFile(f: File): List<AirspaceArea> {
        val out = ArrayList<AirspaceArea>()
        JsonReader(BufferedReader(InputStreamReader(FileInputStream(f), Charsets.UTF_8), 1 shl 16)).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() == "features" && r.peek() == JsonToken.BEGIN_ARRAY) {
                    r.beginArray()
                    while (r.hasNext()) readFeature(r)?.let { out.add(it) }
                    r.endArray()
                } else r.skipValue()
            }
            r.endObject()
        }
        return out
    }

    private fun str(r: JsonReader): String =
        if (r.peek() == JsonToken.NULL) { r.nextNull(); "" } else r.nextString()

    private fun int(r: JsonReader, def: Int): Int =
        if (r.peek() == JsonToken.NUMBER) r.nextInt() else { r.skipValue(); def }

    private fun dbl(r: JsonReader, def: Double): Double =
        if (r.peek() == JsonToken.NUMBER) r.nextDouble() else { r.skipValue(); def }

    private fun readLimit(r: JsonReader): Limit {
        val l = Limit()
        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return l }
        r.beginObject()
        while (r.hasNext()) when (r.nextName()) {
            "value" -> l.value = dbl(r, 0.0)
            "unit" -> l.unit = int(r, 1)
            "referenceDatum" -> l.datum = int(r, 0)
            else -> r.skipValue()
        }
        r.endObject()
        return l
    }

    private fun readFeature(r: JsonReader): AirspaceArea? {
        var name = ""; var type = 0; var cls = 8; var country = ""
        var lo = Limit(); var up = Limit(value = 999.0, unit = 6, datum = 2)
        var rings: List<DoubleArray> = emptyList()
        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return null }
        r.beginObject()
        while (r.hasNext()) when (r.nextName()) {
            "properties" -> {
                if (r.peek() != JsonToken.BEGIN_OBJECT) r.skipValue() else {
                    r.beginObject()
                    while (r.hasNext()) when (r.nextName()) {
                        "name" -> name = str(r)
                        "type" -> type = int(r, 0)
                        "icaoClass" -> cls = int(r, 8)
                        "country" -> country = str(r)
                        "lowerLimit" -> lo = readLimit(r)
                        "upperLimit" -> up = readLimit(r)
                        else -> r.skipValue()
                    }
                    r.endObject()
                }
            }
            "geometry" -> rings = readGeometry(r)
            else -> r.skipValue()
        }
        r.endObject()
        if (rings.isEmpty()) return null
        return AirspaceArea(name, type, cls, country, limitText(lo), limitText(up), limitFt(lo), limitFt(up), rings)
    }

    private fun readGeometry(r: JsonReader): List<DoubleArray> {
        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return emptyList() }
        var coords: Any? = null
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() == "coordinates") coords = readNested(r) else r.skipValue()
        }
        r.endObject()
        return ringsFrom(coords)
    }

    private fun readNested(r: JsonReader): Any? = when (r.peek()) {
        JsonToken.BEGIN_ARRAY -> {
            val l = ArrayList<Any?>()
            r.beginArray(); while (r.hasNext()) l.add(readNested(r)); r.endArray()
            l
        }
        JsonToken.NUMBER -> r.nextDouble()
        else -> { r.skipValue(); null }
    }

    private fun depth(o: Any?): Int = if (o is List<*> && o.isNotEmpty()) 1 + depth(o[0]) else 0

    /** Polygon (głębokość 3) → pierwszy pierścień; MultiPolygon (4) → pierwszy pierścień każdego wielokąta. */
    private fun ringsFrom(c: Any?): List<DoubleArray> {
        fun ring(pts: List<*>): DoubleArray {
            val a = DoubleArray(pts.size * 2)
            pts.forEachIndexed { i, p ->
                val q = p as List<*>
                a[2 * i] = q[1] as Double; a[2 * i + 1] = q[0] as Double
            }
            return a
        }
        val l = c as? List<*> ?: return emptyList()
        return when (depth(l)) {
            3 -> listOf(ring(l[0] as List<*>))
            4 -> l.map { ring((it as List<*>)[0] as List<*>) }
            else -> emptyList()
        }
    }

    private fun limitFt(l: Limit): Int = when (l.unit) {
        6 -> (l.value * 100).toInt()
        0 -> (l.value * 3.28084).toInt()
        else -> l.value.toInt()
    }

    private fun limitText(l: Limit): String = when {
        l.unit == 6 -> String.format(Locale.US, "FL%03d", l.value.toInt())
        l.value == 0.0 && l.datum == 0 -> "GND"
        else -> {
            val u = if (l.unit == 0) "m" else "ft"
            val d = when (l.datum) { 0 -> "AGL"; 1 -> "AMSL"; else -> "STD" }
            "${l.value.toInt()} $u $d"
        }
    }
}
