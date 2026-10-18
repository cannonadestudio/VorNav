package pl.pilot.vornav.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Import bazy z OurAirports (domena publiczna): lotniska + VOR/DME/NDB z całego świata,
 * filtrowane do wybranych krajów, oraz import własnych lądowisk z pliku CSV.
 */
class Importer(private val db: NavDb) {

    companion object {
        const val BASE = "https://davidmegginson.github.io/ourairports-data/"
        private val SKIP_AIRPORT_TYPES = setOf("closed", "balloonport")
    }

    suspend fun importOurAirports(
        countries: Set<String>,
        includeHeliports: Boolean,
        progress: (String) -> Unit
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val w = db.writableDatabase
        var nAp = 0; var nNa = 0
        w.beginTransaction()
        try {
            w.delete("airports", "source='oa'", null)
            w.delete("navaids", "source='oa'", null)

            progress("Pobieranie pomocy nawigacyjnych…")
            val naStmt = w.compileStatement(
                "INSERT INTO navaids(ident,name,type,freq_khz,lat,lon,elev,country,dme_channel,dme_lat,dme_lon,variation,power,source) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'oa')"
            )
            open(BASE + "navaids.csv").use { r ->
                val iter = csvRecords(r).iterator()
                if (!iter.hasNext()) error("Pusty plik navaids.csv")
                val h = iter.next().withIndex().associate { (i, s) -> s to i }
                fun col(rec: List<String>, n: String) = h[n]?.let { rec.getOrNull(it) }.orEmpty()
                while (iter.hasNext()) {
                    val rec = iter.next()
                    val cc = col(rec, "iso_country")
                    if (countries.isNotEmpty() && cc !in countries) continue
                    val lat = col(rec, "latitude_deg").toDoubleOrNull() ?: continue
                    val lon = col(rec, "longitude_deg").toDoubleOrNull() ?: continue
                    naStmt.clearBindings()
                    naStmt.bindString(1, col(rec, "ident"))
                    naStmt.bindString(2, col(rec, "name"))
                    naStmt.bindString(3, col(rec, "type"))
                    naStmt.bindLong(4, col(rec, "frequency_khz").toDoubleOrNull()?.toLong() ?: 0L)
                    naStmt.bindDouble(5, lat)
                    naStmt.bindDouble(6, lon)
                    naStmt.bindDouble(7, col(rec, "elevation_ft").toDoubleOrNull() ?: 0.0)
                    naStmt.bindString(8, cc)
                    bindOpt(naStmt, 9, col(rec, "dme_channel"))
                    bindOptD(naStmt, 10, col(rec, "dme_latitude_deg").toDoubleOrNull())
                    bindOptD(naStmt, 11, col(rec, "dme_longitude_deg").toDoubleOrNull())
                    // deklinacja, do której skalibrowano stację (slaved), a w razie braku — bieżąca
                    val varSlaved = col(rec, "slaved_variation_deg").toDoubleOrNull()
                    bindOptD(naStmt, 12, varSlaved ?: col(rec, "magnetic_variation_deg").toDoubleOrNull())
                    bindOpt(naStmt, 13, col(rec, "power"))
                    naStmt.executeInsert(); nNa++
                }
            }

            progress("Pobieranie lotnisk… (duży plik, ok. 12 MB)")
            val apStmt = w.compileStatement(
                "INSERT INTO airports(ident,name,type,lat,lon,elev,country,municipality,source) VALUES(?,?,?,?,?,?,?,?,'oa')"
            )
            open(BASE + "airports.csv").use { r ->
                val iter = csvRecords(r).iterator()
                if (!iter.hasNext()) error("Pusty plik airports.csv")
                val h = iter.next().withIndex().associate { (i, s) -> s to i }
                fun col(rec: List<String>, n: String) = h[n]?.let { rec.getOrNull(it) }.orEmpty()
                var seen = 0
                while (iter.hasNext()) {
                    val rec = iter.next()
                    if (++seen % 20000 == 0) progress("Lotniska: przejrzano $seen, zapisano $nAp…")
                    val cc = col(rec, "iso_country")
                    if (countries.isNotEmpty() && cc !in countries) continue
                    val type = col(rec, "type")
                    if (type in SKIP_AIRPORT_TYPES) continue
                    if (type == "heliport" && !includeHeliports) continue
                    val lat = col(rec, "latitude_deg").toDoubleOrNull() ?: continue
                    val lon = col(rec, "longitude_deg").toDoubleOrNull() ?: continue
                    val ident = col(rec, "gps_code").ifBlank { col(rec, "ident") }
                    apStmt.clearBindings()
                    apStmt.bindString(1, ident)
                    apStmt.bindString(2, col(rec, "name"))
                    apStmt.bindString(3, type)
                    apStmt.bindDouble(4, lat)
                    apStmt.bindDouble(5, lon)
                    apStmt.bindDouble(6, col(rec, "elevation_ft").toDoubleOrNull() ?: 0.0)
                    apStmt.bindString(7, cc)
                    apStmt.bindString(8, col(rec, "municipality"))
                    apStmt.executeInsert(); nAp++
                }
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        progress("Gotowe: $nAp lotnisk, $nNa pomocy nawigacyjnych")
        nAp to nNa
    }

    /**
     * Własne lądowiska z CSV. Format wiersza (separator ; lub ,):
     * IDENT;Nazwa;szerokość;długość;[wysokość ft];[typ]
     * Współrzędne dziesiętnie (52.1234) albo w stopniach/minutach/sekundach (N52°10'30").
     */
    suspend fun importUserCsv(ctx: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        var n = 0
        val w = db.writableDatabase
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).useLines { lines ->
                w.beginTransaction()
                try {
                    for (raw in lines) {
                        val line = raw.trim().removePrefix("﻿")
                        if (line.isEmpty() || line.startsWith("#")) continue
                        val sep = if (line.contains(';')) ';' else if (line.contains('\t')) '\t' else ','
                        val f = line.split(sep).map { it.trim().trim('"') }
                        if (f.size < 4) continue
                        val lat = parseCoord(f[2]) ?: continue
                        val lon = parseCoord(f[3]) ?: continue
                        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
                        val elev = f.getOrNull(4)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
                        val type = f.getOrNull(5)?.ifBlank { null } ?: "ul"
                        db.addUserSite(f[0], f[1].ifBlank { f[0] }, lat, lon, elev, type)
                        n++
                    }
                    w.setTransactionSuccessful()
                } finally {
                    w.endTransaction()
                }
            }
        }
        n
    }

    private fun open(url: String): BufferedReader {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "VorNav-Android")
        if (c.responseCode != 200) error("HTTP ${c.responseCode} dla $url")
        return BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8), 1 shl 16)
    }

    private fun bindOpt(s: android.database.sqlite.SQLiteStatement, i: Int, v: String) =
        if (v.isBlank()) s.bindNull(i) else s.bindString(i, v)

    private fun bindOptD(s: android.database.sqlite.SQLiteStatement, i: Int, v: Double?) =
        if (v == null) s.bindNull(i) else s.bindDouble(i, v)
}

/** Parser CSV zgodny z RFC 4180 (cudzysłowy, przecinki i nowe linie w polach). */
fun csvRecords(r: BufferedReader): Sequence<List<String>> = sequence {
    val field = StringBuilder()
    var rec = ArrayList<String>()
    var inQ = false
    while (true) {
        val ch = r.read()
        if (ch == -1) {
            if (field.isNotEmpty() || rec.isNotEmpty()) { rec.add(field.toString()); yield(rec) }
            break
        }
        val c = ch.toChar()
        if (inQ) {
            if (c == '"') {
                r.mark(1)
                val nx = r.read()
                if (nx == '"'.code) field.append('"') else { inQ = false; if (nx != -1) r.reset() }
            } else field.append(c)
        } else when (c) {
            '"' -> inQ = true
            ',' -> { rec.add(field.toString()); field.setLength(0) }
            '\r' -> {}
            '\n' -> { rec.add(field.toString()); field.setLength(0); yield(rec); rec = ArrayList() }
            else -> field.append(c)
        }
    }
}

/** 52.1234 | 52,1234 | N52°10'30" | 52 10 30 N | 52°10.5'N | -16.5 */
fun parseCoord(s: String): Double? {
    val t = s.trim().uppercase()
    if (t.isEmpty()) return null
    t.replace(',', '.').toDoubleOrNull()?.let { return it }
    val neg = t.contains('S') || t.contains('W')
    val nums = Regex("[0-9]+(?:[.,][0-9]+)?").findAll(t).map { it.value.replace(',', '.').toDouble() }.toList()
    if (nums.isEmpty()) return null
    val v = nums[0] + (nums.getOrNull(1) ?: 0.0) / 60.0 + (nums.getOrNull(2) ?: 0.0) / 3600.0
    val sign = if (neg || t.startsWith("-")) -1 else 1
    return sign * v
}
