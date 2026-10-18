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
import pl.pilot.vornav.data.AirspaceRepo
import pl.pilot.vornav.data.Zone
import kotlinx.coroutines.delay
import pl.pilot.vornav.data.NavDb
import pl.pilot.vornav.data.Waypoint
import pl.pilot.vornav.loc.PositionEngine
import pl.pilot.vornav.nav.Geo
import pl.pilot.vornav.nav.NavSettings
import pl.pilot.vornav.nav.VorReceiver
import pl.pilot.vornav.nav.LegPlan
import pl.pilot.vornav.nav.RoutePlanner
import kotlin.math.cos
import kotlin.math.max

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

    // ---------- trasa ----------
    /** Punkty trasy: [od, ...pośrednie..., do]. */
    val route = MutableStateFlow<List<Waypoint>>(emptyList())
    private val _legs = MutableStateFlow<List<LegPlan>>(emptyList())
    val legs: StateFlow<List<LegPlan>> = _legs
    val routeActive = MutableStateFlow(false)
    val activeLeg = MutableStateFlow(0)
    val cruiseKt = MutableStateFlow(prefs.getFloat("cruiseKt", 90f).toDouble())
    val maxLegNm = MutableStateFlow(prefs.getFloat("maxLegNm", 60f).toDouble())

    val cruiseAltFt = MutableStateFlow(prefs.getFloat("cruiseAltFt", 2500f).toDouble())

    // ---------- strefy AUP/UUP ----------
    private val airspace = AirspaceRepo(app)
    val zones = MutableStateFlow<List<Zone>>(emptyList())
    val zonesStatus = MutableStateFlow("")
    val zonesBusy = MutableStateFlow(false)
    val showZones = MutableStateFlow(prefs.getBoolean("showZones", true))
    /** Pokazuj tylko strefy, których dolna granica jest poniżej tej wysokości (ft). */
    val zoneMaxFt = MutableStateFlow(prefs.getInt("zoneMaxFt", 9500))

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
                route.value = prefs.getString("route_keys", "").orEmpty()
                    .split(",").filter { it.isNotBlank() }.mapNotNull { db.byKey(it) }
            }
            replan()
            refreshCounts()
        }
        // strefy: najpierw z pamięci, potem odświeżanie co 30 min (AUP publikowany codziennie, UUP w ciągu dnia)
        viewModelScope.launch {
            zones.value = airspace.loadCached()
            updateZonesStatus(null)
            while (true) {
                val age = System.currentTimeMillis() - (airspace.cacheInfo()?.first ?: 0L)
                if (age > 30 * 60_000L) refreshZonesNow()
                delay(5 * 60_000L)
            }
        }
        // automatyczne przełączanie odcinków trasy
        viewModelScope.launch {
            position.collect { p -> if (p != null && routeActive.value) autoSequence(p) }
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

    // ---------- trasa (od–do, nawigacja po radialach) ----------

    private suspend fun vorsAround(points: List<Waypoint>, marginNm: Double = 100.0): List<Waypoint> = withContext(Dispatchers.IO) {
        if (points.isEmpty()) return@withContext emptyList()
        val latMin = points.minOf { it.lat } - marginNm / 60.0
        val latMax = points.maxOf { it.lat } + marginNm / 60.0
        val k = 60.0 * max(0.2, cos(Math.toRadians((latMin + latMax) / 2)))
        db.vorsInBox(latMin, latMax, points.minOf { it.lon } - marginNm / k, points.maxOf { it.lon } + marginNm / k)
    }

    private fun saveRoute() {
        prefs.edit().putString("route_keys", route.value.joinToString(",") { it.key }).apply()
    }

    private fun replan() {
        val pts = route.value
        viewModelScope.launch {
            val vors = vorsAround(pts)
            _legs.value = withContext(Dispatchers.Default) { RoutePlanner.planLegs(pts, vors, settings.value) }
            if (activeLeg.value >= _legs.value.size) activeLeg.value = 0
        }
    }

    private fun setRoute(pts: List<Waypoint>) {
        route.value = pts; saveRoute(); replan()
    }

    fun setRouteFrom(wp: Waypoint) {
        val r = route.value
        setRoute(if (r.isEmpty()) listOf(wp) else listOf(wp) + r.drop(1))
    }

    fun setRouteTo(wp: Waypoint) {
        val r = route.value
        setRoute(when (r.size) { 0 -> listOf(wp); 1 -> r + wp; else -> r.dropLast(1) + wp })
    }

    /** Dodaje punkt pośredni przed celem. */
    fun addVia(wp: Waypoint) {
        val r = route.value
        setRoute(if (r.size < 2) r + wp else r.dropLast(1) + wp + r.last())
    }

    fun removeRoutePoint(i: Int) {
        setRoute(route.value.filterIndexed { k, _ -> k != i })
    }

    fun moveRoutePoint(i: Int, delta: Int) {
        val r = route.value.toMutableList(); val j = i + delta
        if (i !in r.indices || j !in r.indices) return
        val t = r[i]; r[i] = r[j]; r[j] = t
        setRoute(r)
    }

    fun reverseRoute() = setRoute(route.value.reversed())

    fun clearRoute() { stopRoute(); setRoute(emptyList()) }

    fun setCruise(kt: Double) { cruiseKt.value = kt; prefs.edit().putFloat("cruiseKt", kt.toFloat()).apply() }
    fun setMaxLeg(nm: Double) { maxLegNm.value = nm; prefs.edit().putFloat("maxLegNm", nm.toFloat()).apply() }

    /** Wyznacza trasę od pierwszego do ostatniego punktu przez VOR-y. */
    fun suggestVorRoute() {
        val r = route.value
        if (r.size < 2) { toast.value = "Wybierz lotnisko startu i celu"; return }
        viewModelScope.launch {
            val vors = vorsAround(listOf(r.first(), r.last()), 80.0)
            val path = withContext(Dispatchers.Default) { RoutePlanner.suggestViaVor(r.first(), r.last(), vors, maxLegNm.value) }
            if (path == null) {
                toast.value = "Nie znalazłem trasy przez VOR z odcinkami ≤ ${maxLegNm.value.toInt()} NM — zwiększ limit"
            } else {
                setRoute(path)
                toast.value = "Trasa przez ${path.size - 2} VOR"
            }
        }
    }

    fun startRoute(fromLeg: Int = 0) {
        if (_legs.value.isEmpty()) { toast.value = "Trasa musi mieć co najmniej dwa punkty"; return }
        routeActive.value = true
        activateLeg(fromLeg.coerceIn(0, _legs.value.size - 1), announce = true)
    }

    fun stopRoute() { routeActive.value = false }

    fun nextLeg() = activateLeg((activeLeg.value + 1).coerceAtMost(_legs.value.size - 1), true)
    fun prevLeg() = activateLeg((activeLeg.value - 1).coerceAtLeast(0), true)

    /** Ustawia odcinek: Direct-To na koniec odcinka, NAV1 na stację odcinka z właściwym OBS. */
    private fun activateLeg(i: Int, announce: Boolean) {
        val leg = _legs.value.getOrNull(i) ?: return
        activeLeg.value = i
        direct.value = leg.to
        prefs.edit().putString("direct_key", leg.to.key).apply()
        val st = leg.navStation; val obs = leg.obs
        if (st != null && obs != null) {
            nav[0].value = NavSlot(st, Geo.norm360(Math.round(obs).toDouble()))
            saveNav(0)
        }
        if (announce) toast.value = "Odcinek ${i + 1}/${_legs.value.size}: ${leg.from.ident}→${leg.to.ident}" +
            (if (st != null && obs != null) " • NAV1 ${st.ident} OBS ${RoutePlanner.fmt3(obs)}" else "")
    }

    private fun autoSequence(p: pl.pilot.vornav.loc.Position) {
        val legs = _legs.value
        val i = activeLeg.value
        val leg = legs.getOrNull(i) ?: return
        if (i >= legs.size - 1) return
        val toGo = Geo.distanceNm(p.lat, p.lon, leg.to.lat, leg.to.lon)
        val (_, along) = RoutePlanner.crossTrack(leg, p)
        if (toGo < 0.7 || along > leg.distNm) activateLeg(i + 1, true)
    }

    // ---------- strefy ----------

    private fun updateZonesStatus(error: String?) {
        val info = airspace.cacheInfo()
        val now = System.currentTimeMillis()
        val active = zones.value.count { it.activeAt(now) != null }
        val base = if (info == null) "Brak pobranych stref"
        else "${info.second}: ${zones.value.size} stref (aktywnych teraz: $active), pobrano " +
            java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(info.first))
        zonesStatus.value = if (error != null) "$base • błąd odświeżania: $error" else base
    }

    private suspend fun refreshZonesNow() {
        if (zonesBusy.value) return
        zonesBusy.value = true
        try {
            zones.value = airspace.download().first
            updateZonesStatus(null)
        } catch (e: Exception) {
            updateZonesStatus(e.message ?: e.javaClass.simpleName)
        } finally {
            zonesBusy.value = false
        }
    }

    fun refreshZones() { viewModelScope.launch { refreshZonesNow() } }

    fun setShowZones(v: Boolean) { showZones.value = v; prefs.edit().putBoolean("showZones", v).apply() }
    fun setZoneMaxFt(v: Int) { zoneMaxFt.value = v; prefs.edit().putInt("zoneMaxFt", v).apply() }
    fun setCruiseAlt(v: Double) { cruiseAltFt.value = v; prefs.edit().putFloat("cruiseAltFt", v.toFloat()).apply() }

    /** Strefy po filtrze wysokości (dolna granica poniżej limitu albo nieznana). */
    fun visibleZones(all: List<Zone>, maxFt: Int): List<Zone> =
        all.filter { z -> z.reservations.any { (it.lowerFt ?: 0) < maxFt } }

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
