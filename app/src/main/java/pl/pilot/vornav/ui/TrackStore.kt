package pl.pilot.vornav.ui

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class TrackPoint(val lat: Double, val lon: Double, val altFt: Double, val timeMs: Long)

/** Zapis i odczyt śladów lotu w formacie GPX (folder aplikacji). */
class TrackStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "tracks").apply { mkdirs() }
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun list(): List<File> = dir.listFiles { f -> f.extension == "gpx" }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun save(name: String, pts: List<TrackPoint>): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date(pts.first().timeMs))
        val safe = name.replace(Regex("[^\\p{L}0-9 _-]"), "").trim().replace(' ', '_')
        val f = File(dir, if (safe.isEmpty()) "$stamp.gpx" else "${stamp}_$safe.gpx")
        f.writeText(toGpx(name.ifBlank { stamp }, pts))
        return f
    }

    fun toGpx(name: String, pts: List<TrackPoint>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"VorNav\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("<trk><name>").append(name.replace("&", "&amp;").replace("<", "&lt;")).append("</name><trkseg>\n")
        for (p in pts) {
            append(String.format(Locale.US, "<trkpt lat=\"%.6f\" lon=\"%.6f\"><ele>%.1f</ele><time>%s</time></trkpt>\n",
                p.lat, p.lon, p.altFt * 0.3048, iso.format(Date(p.timeMs))))
        }
        append("</trkseg></trk></gpx>\n")
    }

    fun load(f: File): List<TrackPoint> {
        val text = f.readText()
        val re = Regex("<trkpt lat=\"([-0-9.]+)\" lon=\"([-0-9.]+)\">(?:<ele>([-0-9.]+)</ele>)?(?:<time>([^<]+)</time>)?")
        return re.findAll(text).map { m ->
            TrackPoint(
                m.groupValues[1].toDouble(), m.groupValues[2].toDouble(),
                (m.groupValues[3].toDoubleOrNull() ?: 0.0) / 0.3048,
                runCatching { iso.parse(m.groupValues[4])!!.time }.getOrDefault(0L)
            )
        }.toList()
    }

    /** Krótki opis: czas trwania i długość. */
    fun summary(pts: List<TrackPoint>): String {
        if (pts.size < 2) return "${pts.size} pkt"
        var nm = 0.0
        for (i in 1 until pts.size) nm += pl.pilot.vornav.nav.Geo.distanceNm(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
        val min = (pts.last().timeMs - pts.first().timeMs) / 60000
        return String.format(Locale.US, "%.1f NM, %d:%02d h", nm, min / 60, min % 60)
    }
}
