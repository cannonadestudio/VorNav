package pl.pilot.vornav.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.pilot.vornav.data.Importer
import pl.pilot.vornav.data.NavDb
import pl.pilot.vornav.data.Waypoint
import pl.pilot.vornav.loc.PositionEngine
import pl.pilot.vornav.nav.Geo
import pl.pilot.vornav.nav.NavSettings
import pl.pilot.vornav.nav.VorReceiver

data class NavSlot(val station: Waypoint? = null, val obs: Double = 0.0)

class NavViewModel(app: Application) : AndroidViewModel(app) {

    val db = NavDb(app)
    private val importer = Importer(db)
    val engine = PositionEngine(app)
    private val prefs = app.getSharedPreferences("vornav", Context.MODE_PRIVATE)

    val position = engine.position
    val gpsStatus = engine.gpsStatus

    val nav = listOf(MutableStateFlow(NavSlot()), MutableStateFlow(NavSlot()))
    val direct = MutableStateFlow<Waypoint?>(null)
    /** Odbiornik ADF (NDB). */
    val adf = MutableStateFlow<Waypoint?>(null)
    /** Kurs magnetyczny z żyrobusoli wpisany ręcznie; null = używaj TRK z GPS. */
    val manualHdg = MutableStateFlow<Double?>(null)
    val settings = MutableStateFlow(
        NavSettings(
            ignoreRange = prefs.getBoolean("ignoreRange", false),
            useStationVariation = prefs.getBoolean("useStationVar", true)
        )
    )
    val countries = MutableStateFlow(prefs.getString("countries", "PL,DE,CZ,SK,LT") ?: "PL")
    val includeHeliports = MutableStateFlow(prefs.getBoolean("heli", false))

    private val _counts = MutableStateFlow(Triple(0, 0, 0))
    val counts: StateFlow<Triple<Int, Int, Int>> = _counts
    private val _importStatus = MutableStateFlow("")
    val importStatus: StateFlow<String> = _importStatus
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _userSites = MutableStateFlow<List<Waypoint>>(emptyList())
    val userSites: StateFlow<List<Waypoint>> = _userSites

    /** Komunikat jednorazowy (snackbar). */
    val toast = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                for (i in 0..1) {
                    val st = prefs.getString("nav${i}_key", null)?.let { db.byKey(it) }
                    nav[i].value = NavSlot(st, prefs.getFloat("nav${i}_obs", 0f).toDouble())
                }
                direct.value = prefs.getString("direct_key", null)?.let { db.byKey(it) }
                adf.value = prefs.getString("adf_key", null)?.let { db.byKey(it) }
            }
            refreshCounts()
        }
    }

    fun startGps() = engine.start()

    override fun onCleared() { engine.stop(); super.onCleared() }

    // ---------- NAV1 / NAV2 ----------

    fun tune(i: Int, wp: Waypoint) {
        val pos = position.value
        // ustaw OBS tak, by wskazówka była wycentrowana z TO (jak "CDI center")
        val obs = if (pos != null && wp.hasVor)
            Geo.norm360(VorReceiver.compute(wp, pos, 0.0, settings.value).radialFrom?.plus(180.0) ?: nav[i].value.obs)
        else nav[i].value.obs
        nav[i].value = NavSlot(wp, Math.round(obs).toDouble() % 360.0)
        saveNav(i)
        toast.value = "NAV${i + 1}: ${wp.ident} ${wp.freqText}"
    }

    fun setObs(i: Int, obs: Double) {
        nav[i].value = nav[i].value.copy(obs = Geo.norm360(Math.round(obs).toDouble()))
        saveNav(i)
    }

    fun centerObs(i: Int) {
        val slot = nav[i].value; val st = slot.station ?: return; val pos = position.value ?: return
        val r = VorReceiver.compute(st, pos, slot.obs, settings.value).radialFrom ?: return
        setObs(i, r + 180.0)
    }

    private fun saveNav(i: Int) {
        prefs.edit()
            .putString("nav${i}_key", nav[i].value.station?.key)
            .putFloat("nav${i}_obs", nav[i].value.obs.toFloat())
            .apply()
    }

    /** Strojenie częstotliwością jak w radiu: 114.90 → najbliższa stacja na tej częstotliwości. */
    suspend fun findByFrequency(text: String): List<Waypoint> = withContext(Dispatchers.IO) {
        val f = text.replace(',', '.').toDoubleOrNull() ?: return@withContext emptyList()
        val khz = Math.round(f * 1000).toInt()
        val pos = position.value
        val list = db.byFrequency(khz).filter { it.isTunable }
        if (pos == null) list else list.sortedBy { Geo.distanceNm(pos.lat, pos.lon, it.lat, it.lon) }
    }

    // ---------- ADF ----------

    fun tuneAdf(wp: Waypoint) {
        adf.value = wp
        prefs.edit().putString("adf_key", wp.key).apply()
        toast.value = "ADF: ${wp.ident} ${wp.freqText}"
    }

    fun setHeading(hdg: Double?) {
        manualHdg.value = hdg?.let { Geo.norm360(Math.round(it).toDouble()) }
    }

    /** HDG = bieżący TRK z GPS (punkt startowy do ręcznej korekty). */
    fun syncHeadingToTrack() {
        val p = position.value
        val t = p?.trackTrue
        if (p == null || t == null) { setHeading(0.0); return }
        setHeading(pl.pilot.vornav.nav.Mag.trueToMag(t, pl.pilot.vornav.nav.Mag.declination(p.lat, p.lon, p.altFt)))
    }

    /** Strojenie ADF częstotliwością w kHz, np. 375 → najbliższa NDB na tej częstotliwości. */
    suspend fun findNdbByFrequency(text: String): List<Waypoint> = withContext(Dispatchers.IO) {
        val khz = text.replace(',', '.').toDoubleOrNull()?.let { Math.round(it).toInt() } ?: return@withContext emptyList()
        val pos = position.value
        val list = db.byFrequency(khz).filter { it.isNdb }
        if (pos == null) list else list.sortedBy { Geo.distanceNm(pos.lat, pos.lon, it.lat, it.lon) }
    }

    // ---------- Direct-To ----------

    fun directTo(wp: Waypoint?) {
        direct.value = wp
        prefs.edit().putString("direct_key", wp?.key).apply()
        if (wp != null) toast.value = "Direct-To: ${wp.ident}"
    }

    // ---------- korekta pozycji ----------

    fun applyFix(lat: Double, lon: Double, what: String) {
        engine.applyFix(lat, lon)
        toast.value = "Pozycja skorygowana ($what)"
    }

    fun overfly(wp: Waypoint) = applyFix(wp.lat, wp.lon, "nad ${wp.ident}")

    fun clearCorrection() { engine.clearCorrection(); toast.value = "Korekta usunięta" }

    // ---------- ustawienia ----------

    fun updateSettings(s: NavSettings) {
        settings.value = s
        prefs.edit().putBoolean("ignoreRange", s.ignoreRange).putBoolean("useStationVar", s.useStationVariation).apply()
    }

    // ---------- baza ----------

    suspend fun search(q: String): List<Waypoint> = withContext(Dispatchers.IO) { db.search(q) }

    suspend fun nearest(filter: ((Waypoint) -> Boolean)? = null): List<Waypoint> = withContext(Dispatchers.IO) {
        val p = position.value ?: return@withContext emptyList()
        db.nearest(p.lat, p.lon, if (filter != null) 150.0 else 50.0, 60, filter)
    }

    suspend fun inBox(latMin: Double, latMax: Double, lonMin: Double, lonMax: Double) =
        withContext(Dispatchers.IO) { db.inBox(latMin, latMax, lonMin, lonMax) }

    fun refreshCounts() {
        viewModelScope.launch {
            val (c, u) = withContext(Dispatchers.IO) { db.counts() to db.userSites() }
            _counts.value = c; _userSites.value = u
        }
    }

    fun importOurAirports() {
        if (_busy.value) return
        val set = countries.value.split(',', ' ', ';').map { it.trim().uppercase() }.filter { it.length == 2 }.toSet()
        prefs.edit().putString("countries", set.joinToString(",")).putBoolean("heli", includeHeliports.value).apply()
        viewModelScope.launch {
            _busy.value = true
            try {
                importer.importOurAirports(set, includeHeliports.value) { msg -> _importStatus.value = msg }
            } catch (e: Exception) {
                _importStatus.value = "Błąd: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _busy.value = false
                refreshCounts()
            }
        }
    }

    fun importUserCsv(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val n = importer.importUserCsv(getApplication<Application>(), uri)
                _importStatus.value = "Zaimportowano $n lądowisk"
            } catch (e: Exception) {
                _importStatus.value = "Błąd pliku: ${e.message}"
            } finally {
                _busy.value = false; refreshCounts()
            }
        }
    }

    fun addUserSite(ident: String, name: String, lat: Double, lon: Double, elev: Double) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.addUserSite(ident, name, lat, lon, elev) }
            toast.value = "Dodano ${ident.uppercase()}"
            refreshCounts()
        }
    }

    fun deleteUserSite(wp: Waypoint) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.deleteUserSite(wp.id) }
            refreshCounts()
        }
    }
}
