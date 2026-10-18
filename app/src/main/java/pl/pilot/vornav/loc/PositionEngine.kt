package pl.pilot.vornav.loc

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import pl.pilot.vornav.nav.Geo

enum class PosSource(val label: String) {
    GPS("GPS"), GPS_CORR("GPS+KOR"), DR("DR"), SIM("SYM"), MANUAL("RĘCZNA")
}

data class Position(
    val lat: Double,
    val lon: Double,
    val altFt: Double,
    val gsKt: Double,
    val trackTrue: Double?,
    val source: PosSource,
    val accuracyM: Float? = null,
    val timeMs: Long = System.currentTimeMillis(),
)

/**
 * Pozycja samolotu: GPS telefonu + korekta (przesunięcie) z fixu VOR/DME, przelotu nad punktem
 * lub ręcznie wpisanych współrzędnych. Przy utracie GPS przechodzi na zliczenie drogi (DR).
 * Ma też tryb symulacji do ćwiczeń na ziemi.
 */
class PositionEngine(ctx: Context) : LocationListener {

    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())

    private val _position = MutableStateFlow<Position?>(null)
    val position: StateFlow<Position?> = _position

    private val _gpsStatus = MutableStateFlow("GPS: brak")
    val gpsStatus: StateFlow<String> = _gpsStatus

    // korekta pozycji (stopnie)
    var offsetLat = 0.0; private set
    var offsetLon = 0.0; private set
    val hasCorrection get() = offsetLat != 0.0 || offsetLon != 0.0

    /** Wysokość z wysokościomierza (ft) — zastępuje wysokość GPS przy liczeniu DME. */
    var manualAltFt: Double? = null
        set(v) { field = v; republish() }

    private var lastRaw: Location? = null
    private var lastRawTime = 0L
    private var lastTrack: Double? = null

    // symulacja
    var simActive = false; private set
    private var simLat = 0.0; private var simLon = 0.0
    var simGsKt = 90.0
    var simTrackMag = 0.0
    var simAltFt = 2000.0

    private val ticker = object : Runnable {
        override fun run() { tick(); handler.postDelayed(this, 1000) }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            _gpsStatus.value = "GPS: szukam satelitów…"
        } catch (e: SecurityException) {
            _gpsStatus.value = "GPS: brak uprawnień"
        } catch (e: IllegalArgumentException) {
            _gpsStatus.value = "GPS: brak odbiornika"
        }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun stop() {
        lm.removeUpdates(this)
        handler.removeCallbacks(ticker)
    }

    override fun onLocationChanged(loc: Location) {
        lastRaw = loc
        lastRawTime = System.currentTimeMillis()
        val gs = if (loc.hasSpeed()) loc.speed * 1.943844 else 0.0
        if (loc.hasBearing() && gs > 3.0) lastTrack = loc.bearing.toDouble()
        _gpsStatus.value = "GPS: ±%.0f m".format(if (loc.hasAccuracy()) loc.accuracy else 0f)
        if (!simActive) republish()
    }

    @Deprecated("Wymagane dla API < 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) { _gpsStatus.value = "GPS: włączony" }
    override fun onProviderDisabled(provider: String) { _gpsStatus.value = "GPS: WYŁĄCZONY w ustawieniach" }

    private fun republish() {
        if (simActive) { publishSim(); return }
        val loc = lastRaw ?: return
        val gs = if (loc.hasSpeed()) loc.speed * 1.943844 else 0.0
        val alt = manualAltFt ?: if (loc.hasAltitude()) loc.altitude * 3.28084 else 0.0
        _position.value = Position(
            lat = loc.latitude + offsetLat,
            lon = loc.longitude + offsetLon,
            altFt = alt,
            gsKt = gs,
            trackTrue = lastTrack,
            source = if (hasCorrection) PosSource.GPS_CORR else PosSource.GPS,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
            timeMs = loc.time
        )
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        if (simActive) {
            val d = simGsKt / 3600.0
            val trueTrk = magToTrueHere(simLat, simLon, simTrackMag)
            val p = Geo.destination(simLat, simLon, trueTrk, d)
            simLat = p.first; simLon = p.second
            publishSim()
            return
        }
        val cur = _position.value ?: return
        if (cur.source == PosSource.MANUAL) return
        val gpsStale = lastRaw == null || now - lastRawTime > 5000
        if (!gpsStale) return
        // zliczenie drogi (DR) z ostatniej znanej prędkości i kierunku
        val trk = cur.trackTrue
        if (trk != null && cur.gsKt > 1.0) {
            val p = Geo.destination(cur.lat, cur.lon, trk, cur.gsKt / 3600.0)
            _position.value = cur.copy(lat = p.first, lon = p.second, source = PosSource.DR, timeMs = now)
        } else if (cur.source != PosSource.DR) {
            _position.value = cur.copy(source = PosSource.DR)
        }
        if (lastRaw != null) _gpsStatus.value = "GPS: utracony — zliczanie drogi"
    }

    private fun magToTrueHere(lat: Double, lon: Double, mag: Double) =
        Geo.norm360(mag + pl.pilot.vornav.nav.Mag.declination(lat, lon, simAltFt))

    private fun publishSim() {
        _position.value = Position(
            simLat, simLon, manualAltFt ?: simAltFt, simGsKt,
            magToTrueHere(simLat, simLon, simTrackMag), PosSource.SIM
        )
    }

    /**
     * Zastosuj fix: rzeczywista pozycja samolotu to (lat, lon).
     * Przy aktywnym GPS zapamiętuje przesunięcie, które jest dodawane do kolejnych odczytów.
     */
    fun applyFix(lat: Double, lon: Double) {
        if (simActive) { simLat = lat; simLon = lon; publishSim(); return }
        val raw = lastRaw
        if (raw != null && System.currentTimeMillis() - lastRawTime < 5000) {
            offsetLat = lat - raw.latitude
            offsetLon = lon - raw.longitude
            republish()
        } else {
            val cur = _position.value
            _position.value = Position(
                lat, lon, manualAltFt ?: cur?.altFt ?: 0.0, cur?.gsKt ?: 0.0, cur?.trackTrue,
                if (cur == null) PosSource.MANUAL else PosSource.DR
            )
        }
    }

    fun clearCorrection() {
        offsetLat = 0.0; offsetLon = 0.0
        republish()
    }

    /** Wielkość bieżącej korekty: (odległość NM, kierunek rzeczywisty). */
    fun correctionVector(): Pair<Double, Double>? {
        val raw = lastRaw ?: return null
        if (!hasCorrection) return null
        val la = raw.latitude; val lo = raw.longitude
        return Geo.distanceNm(la, lo, la + offsetLat, lo + offsetLon) to
            Geo.bearingTrue(la, lo, la + offsetLat, lo + offsetLon)
    }

    fun startSim(lat: Double, lon: Double) {
        simLat = lat; simLon = lon; simActive = true; publishSim()
    }

    fun stopSim() {
        simActive = false
        if (lastRaw != null) republish() else _position.value = null
    }
}
