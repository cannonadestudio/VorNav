package pl.pilot.vornav.nav

import android.hardware.GeomagneticField
import kotlin.math.*

/** Geometria na kuli ziemskiej (odległości w milach morskich, kursy w stopniach). */
object Geo {
    const val R_NM = 3440.065
    const val FT_PER_NM = 6076.12

    fun norm360(a: Double): Double = ((a % 360.0) + 360.0) % 360.0
    fun norm180(a: Double): Double { var x = norm360(a); if (x > 180.0) x -= 360.0; return x }

    fun distanceNm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * R_NM * asin(min(1.0, sqrt(a)))
    }

    /** Kurs rzeczywisty (true) z punktu 1 do punktu 2. */
    fun bearingTrue(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return norm360(Math.toDegrees(atan2(y, x)))
    }

    /** Punkt odległy o distNm po kursie rzeczywistym brgTrue. */
    fun destination(lat: Double, lon: Double, brgTrue: Double, distNm: Double): Pair<Double, Double> {
        val d = distNm / R_NM
        val t = Math.toRadians(brgTrue)
        val p1 = Math.toRadians(lat); val l1 = Math.toRadians(lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(t))
        val l2 = l1 + atan2(sin(t) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return Math.toDegrees(p2) to norm180(Math.toDegrees(l2))
    }

    /**
     * Przecięcie dwóch półprostych (radiali) wychodzących z punktów 1 i 2 po kursach rzeczywistych.
     * Zwraca null, gdy radiale się nie przecinają.
     */
    fun intersection(
        lat1: Double, lon1: Double, brg1: Double,
        lat2: Double, lon2: Double, brg2: Double
    ): Pair<Double, Double>? {
        val p1 = Math.toRadians(lat1); val l1 = Math.toRadians(lon1)
        val p2 = Math.toRadians(lat2); val l2 = Math.toRadians(lon2)
        val t13 = Math.toRadians(brg1); val t23 = Math.toRadians(brg2)
        val dp = p2 - p1; val dl = l2 - l1
        val d12 = 2 * asin(sqrt(sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)))
        if (abs(d12) < 1e-12) return null
        val ta = acos(((sin(p2) - sin(p1) * cos(d12)) / (sin(d12) * cos(p1))).coerceIn(-1.0, 1.0))
        val tb = acos(((sin(p1) - sin(p2) * cos(d12)) / (sin(d12) * cos(p2))).coerceIn(-1.0, 1.0))
        val t12 = if (sin(l2 - l1) > 0) ta else 2 * PI - ta
        val t21 = if (sin(l2 - l1) > 0) 2 * PI - tb else tb
        val a1 = normPi(t13 - t12)
        val a2 = normPi(t21 - t23)
        if (sin(a1) == 0.0 && sin(a2) == 0.0) return null
        if (sin(a1) * sin(a2) < 0) return null
        val a3 = acos(-cos(a1) * cos(a2) + sin(a1) * sin(a2) * cos(d12))
        val d13 = atan2(sin(d12) * sin(a1) * sin(a2), cos(a2) + cos(a1) * cos(a3))
        val p3 = asin(sin(p1) * cos(d13) + cos(p1) * sin(d13) * cos(t13))
        val dl13 = atan2(sin(t13) * sin(d13) * cos(p1), cos(d13) - sin(p1) * sin(p3))
        return Math.toDegrees(p3) to norm180(Math.toDegrees(l1 + dl13))
    }

    private fun normPi(a: Double): Double {
        var x = (a + PI) % (2 * PI); if (x < 0) x += 2 * PI; return x - PI
    }

    fun formatLat(lat: Double): String = dm(lat, if (lat >= 0) 'N' else 'S', 2)
    fun formatLon(lon: Double): String = dm(lon, if (lon >= 0) 'E' else 'W', 3)
    private fun dm(v: Double, h: Char, w: Int): String {
        val a = abs(v); val d = floor(a).toInt(); val m = (a - d) * 60
        return "%c%0${w}d°%06.3f'".format(java.util.Locale.US, h, d, m)
    }
}

/** Deklinacja magnetyczna (model WMM wbudowany w Androida), dodatnia = wschodnia. */
object Mag {
    fun declination(lat: Double, lon: Double, altFt: Double = 0.0, timeMs: Long = System.currentTimeMillis()): Double =
        GeomagneticField(lat.toFloat(), lon.toFloat(), (altFt * 0.3048).toFloat(), timeMs).declination.toDouble()

    fun trueToMag(trueDeg: Double, decl: Double) = Geo.norm360(trueDeg - decl)
    fun magToTrue(magDeg: Double, decl: Double) = Geo.norm360(magDeg + decl)
}
