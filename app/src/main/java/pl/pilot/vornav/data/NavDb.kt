package pl.pilot.vornav.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import pl.pilot.vornav.nav.Geo
import kotlin.math.cos
import kotlin.math.max

/** Baza lotnisk, lądowisk i pomocy nawigacyjnych (SQLite, bez zależności zewnętrznych). */
class NavDb(ctx: Context) : SQLiteOpenHelper(ctx, "navdb.sqlite", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE airports(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ident TEXT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL,
                lat REAL NOT NULL, lon REAL NOT NULL, elev REAL NOT NULL DEFAULT 0,
                country TEXT NOT NULL DEFAULT '', municipality TEXT NOT NULL DEFAULT '',
                source TEXT NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE navaids(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ident TEXT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL,
                freq_khz INTEGER NOT NULL DEFAULT 0,
                lat REAL NOT NULL, lon REAL NOT NULL, elev REAL NOT NULL DEFAULT 0,
                country TEXT NOT NULL DEFAULT '', dme_channel TEXT,
                dme_lat REAL, dme_lon REAL, variation REAL, power TEXT,
                source TEXT NOT NULL)"""
        )
        db.execSQL("CREATE INDEX ix_ap_ident ON airports(ident)")
        db.execSQL("CREATE INDEX ix_ap_lat ON airports(lat)")
        db.execSQL("CREATE INDEX ix_na_ident ON navaids(ident)")
        db.execSQL("CREATE INDEX ix_na_freq ON navaids(freq_khz)")
        db.execSQL("CREATE INDEX ix_na_lat ON navaids(lat)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------- odczyt ----------

    private fun airport(c: Cursor) = Waypoint(
        kind = Waypoint.Kind.AIRPORT,
        id = c.getLong(0), ident = c.getString(1), name = c.getString(2), type = c.getString(3),
        lat = c.getDouble(4), lon = c.getDouble(5), elevFt = c.getDouble(6),
        country = c.getString(7), municipality = c.getString(8), source = c.getString(9)
    )

    private fun navaid(c: Cursor) = Waypoint(
        kind = Waypoint.Kind.NAVAID,
        id = c.getLong(0), ident = c.getString(1), name = c.getString(2), type = c.getString(3),
        freqKhz = c.getInt(4), lat = c.getDouble(5), lon = c.getDouble(6), elevFt = c.getDouble(7),
        country = c.getString(8),
        dmeChannel = if (c.isNull(9)) null else c.getString(9),
        dmeLat = if (c.isNull(10)) null else c.getDouble(10),
        dmeLon = if (c.isNull(11)) null else c.getDouble(11),
        variation = if (c.isNull(12)) null else c.getDouble(12),
        power = if (c.isNull(13)) null else c.getString(13),
        source = c.getString(14)
    )

    private val apCols = "id,ident,name,type,lat,lon,elev,country,municipality,source"
    private val naCols = "id,ident,name,type,freq_khz,lat,lon,elev,country,dme_channel,dme_lat,dme_lon,variation,power,source"

    private inline fun <T> query(sql: String, args: Array<String>, map: (Cursor) -> T): List<T> {
        val out = ArrayList<T>()
        readableDatabase.rawQuery(sql, args).use { c -> while (c.moveToNext()) out.add(map(c)) }
        return out
    }

    fun byKey(key: String): Waypoint? {
        val parts = key.split(":"); if (parts.size != 2) return null
        val id = parts[1]
        return when (parts[0]) {
            Waypoint.Kind.AIRPORT.name -> query("SELECT $apCols FROM airports WHERE id=?", arrayOf(id), ::airport).firstOrNull()
            Waypoint.Kind.NAVAID.name -> query("SELECT $naCols FROM navaids WHERE id=?", arrayOf(id), ::navaid).firstOrNull()
            else -> null
        }
    }

    /** Szukanie po identyfikatorze (ICAO, VOR), nazwie lub miejscowości. */
    fun search(text: String, limit: Int = 80): List<Waypoint> {
        val q = text.trim().uppercase()
        if (q.isEmpty()) return emptyList()
        val pre = "$q%"; val any = "%$q%"
        val na = query(
            "SELECT $naCols FROM navaids WHERE ident LIKE ? OR upper(name) LIKE ? " +
                "ORDER BY (ident=?) DESC, length(ident) LIMIT $limit",
            arrayOf(pre, any, q), ::navaid
        )
        val ap = query(
            "SELECT $apCols FROM airports WHERE ident LIKE ? OR upper(name) LIKE ? OR upper(municipality) LIKE ? " +
                "ORDER BY (ident=?) DESC, (source='user') DESC, length(ident) LIMIT $limit",
            arrayOf(pre, any, any, q), ::airport
        )
        return (na + ap).sortedByDescending { it.ident == q }
    }

    fun inBox(latMin: Double, latMax: Double, lonMin: Double, lonMax: Double, limit: Int = 400): List<Waypoint> {
        val a = arrayOf(latMin.toString(), latMax.toString(), lonMin.toString(), lonMax.toString())
        val na = query("SELECT $naCols FROM navaids WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? LIMIT $limit", a, ::navaid)
        val ap = query(
            "SELECT $apCols FROM airports WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? " +
                "ORDER BY CASE type WHEN 'large_airport' THEN 0 WHEN 'medium_airport' THEN 1 ELSE 2 END LIMIT $limit",
            a, ::airport
        )
        return na + ap
    }

    fun nearest(lat: Double, lon: Double, radiusNm: Double = 60.0, limit: Int = 50, filter: ((Waypoint) -> Boolean)? = null): List<Waypoint> {
        val dLat = radiusNm / 60.0
        val dLon = radiusNm / (60.0 * max(0.1, cos(Math.toRadians(lat))))
        val list = inBox(lat - dLat, lat + dLat, lon - dLon, lon + dLon, 2000)
            .filter { filter == null || filter(it) }
        return list.map { it to Geo.distanceNm(lat, lon, it.lat, it.lon) }
            .filter { it.second <= radiusNm }
            .sortedBy { it.second }
            .take(limit)
            .map { it.first }
    }

    fun byFrequency(freqKhz: Int): List<Waypoint> =
        query("SELECT $naCols FROM navaids WHERE freq_khz=?", arrayOf(freqKhz.toString()), ::navaid)

    fun counts(): Triple<Int, Int, Int> {
        fun n(sql: String) = readableDatabase.rawQuery(sql, null).use { it.moveToFirst(); it.getInt(0) }
        return Triple(
            n("SELECT count(*) FROM airports WHERE source='oa'"),
            n("SELECT count(*) FROM navaids"),
            n("SELECT count(*) FROM airports WHERE source='user'")
        )
    }

    fun userSites(): List<Waypoint> =
        query("SELECT $apCols FROM airports WHERE source='user' ORDER BY ident", emptyArray(), ::airport)

    // ---------- zapis ----------

    fun addUserSite(ident: String, name: String, lat: Double, lon: Double, elevFt: Double, type: String = "ul"): Long {
        val v = ContentValues().apply {
            put("ident", ident.trim().uppercase()); put("name", name.trim()); put("type", type)
            put("lat", lat); put("lon", lon); put("elev", elevFt)
            put("country", ""); put("municipality", ""); put("source", Waypoint.SOURCE_USER)
        }
        return writableDatabase.insert("airports", null, v)
    }

    fun deleteUserSite(id: Long) {
        writableDatabase.delete("airports", "id=? AND source='user'", arrayOf(id.toString()))
    }

    fun deleteAllUserSites() {
        writableDatabase.delete("airports", "source='user'", null)
    }
}
