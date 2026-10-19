package pl.pilot.vornav.ui

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.pilot.vornav.data.Waypoint
import pl.pilot.vornav.loc.Position
import pl.pilot.vornav.nav.Geo
import pl.pilot.vornav.nav.HoldEntry
import pl.pilot.vornav.nav.Mag
import pl.pilot.vornav.nav.TrainerMath
import pl.pilot.vornav.nav.VorReceiver
import kotlin.math.abs
import kotlin.random.Random

/** Zadanie holdingu. */
data class HoldTask(
    val fix: Waypoint,
    val inboundMag: Double,
    val rightTurns: Boolean,
    val legSec: Int,
    val altFt: Double,
    val windFrom: Double,
    val windKt: Double,
    val tasKt: Double,
    val startHdg: Double,
) {
    val radial: Double get() = Geo.norm360(inboundMag + 180.0)
    val description: String
        get() = (if (fix.hasVor) "Holding nad ${fix.ident} ${fix.freqText} na radialu ${fmtDeg(radial)}"
        else "Holding nad NDB ${fix.ident} ${fix.freqText}, kurs dolotowy (QDM) ${fmtDeg(inboundMag)}") +
            ", zakręty w ${if (rightTurns) "PRAWO" else "LEWO"}, odcinek ${legSec / 60}:${"%02d".format(legSec % 60)}" +
            ", ${altFt.toInt()} ft, wiatr ${fmtDeg(windFrom)}/${windKt.toInt()} kt"
    val entry: HoldEntry get() = TrainerMath.holdEntry(startHdg, inboundMag, rightTurns)
    val solution get() = TrainerMath.holdSolution(inboundMag, windFrom, windKt, tasKt, legSec)
}

/** Jedno okrążenie holdingu ocenione na odcinku dolotowym. */
data class HoldLap(val n: Int, val inboundSec: Int, val maxXtkNm: Double)

data class HoldLive(
    val passages: Int = 0,
    val sinceFixSec: Int = 0,
    val outboundSec: Int? = null,
    val laps: List<HoldLap> = emptyList(),
)

/** Zadanie przechwycenia: radial VOR (TO/FROM) albo QDM/QDR NDB. */
data class InterceptTask(
    val station: Waypoint,
    val courseMag: Double,
    val inbound: Boolean,
    val startLat: Double,
    val startLon: Double,
    val startHdg: Double,
) {
    val description: String
        get() = if (station.hasVor) {
            if (inbound) "Przechwyć radial ${fmtDeg(courseMag + 180)} i leć DO ${station.ident} (OBS ${fmtDeg(courseMag)})"
            else "Przechwyć radial ${fmtDeg(courseMag)} i leć OD ${station.ident} (OBS ${fmtDeg(courseMag)})"
        } else {
            if (inbound) "Przechwyć QDM ${fmtDeg(courseMag)} i leć DO NDB ${station.ident}"
            else "Przechwyć QDR ${fmtDeg(courseMag)} i leć OD NDB ${station.ident}"
        }
}

data class InterceptLive(
    val elapsedSec: Int = 0,
    val devDeg: Double? = null,
    val crossed: Boolean = false,
    val overshootDeg: Double = 0.0,
    val establishedSec: Int? = null,
)

/**
 * Trenery IFR: holding i przechwytywanie radiali/namiarów. Działają w symulacji
 * (samolot ustawiany automatycznie) albo w realnym locie z GPS.
 */
class TrainerController(private val vm: NavViewModel) {

    val hold = MutableStateFlow<HoldTask?>(null)
    val holdLive = MutableStateFlow(HoldLive())
    val holdShape = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())

    val intercept = MutableStateFlow<InterceptTask?>(null)
    val interceptLive = MutableStateFlow(InterceptLive())

    init {
        vm.viewModelScope.launch { vm.position.collect { p -> if (p != null) { stepHold(p); stepIntercept(p) } } }
    }

    private suspend fun stations(p: Position?, vor: Boolean): List<Waypoint> = withContext(Dispatchers.IO) {
        val lat = p?.lat ?: 52.17; val lon = p?.lon ?: 20.97
        vm.db.nearest(lat, lon, 120.0, 40) { if (vor) it.hasVor else it.isNdb }
    }

    // ---------------- holding ----------------

    /** Losuje holding przy najbliższej stacji (VOR lub NDB) i ustawia samolot symulacji 8–12 NM od fixu. */
    fun newHold(useNdb: Boolean, withWind: Boolean, simulate: Boolean) {
        vm.viewModelScope.launch {
            val list = stations(vm.position.value, !useNdb)
            if (list.isEmpty()) { vm.toast.value = "Brak ${if (useNdb) "NDB" else "VOR"} w bazie w pobliżu — pobierz bazę"; return@launch }
            val fix = list.take(6).random()
            val inbound = (Random.nextInt(72) * 5).toDouble()
            val tas = vm.engine.simGsKt.coerceIn(60.0, 200.0)
            val wFrom = (Random.nextInt(36) * 10).toDouble()
            val wKt = if (withWind) Random.nextInt(5, 26).toDouble() else 0.0
            val approachBrg = Random.nextDouble(0.0, 360.0)       // skąd nadlatujemy (namiar od fixu, rzeczywisty)
            val dist = Random.nextDouble(8.0, 12.0)
            val start = Geo.destination(fix.lat, fix.lon, approachBrg, dist)
            val decl = Mag.declination(fix.lat, fix.lon)
            val hdgToFix = Mag.trueToMag(Geo.bearingTrue(start.first, start.second, fix.lat, fix.lon), decl)
            val alt = (Random.nextInt(3, 8) * 500).toDouble()
            val t = HoldTask(fix, inbound, Random.nextInt(4) != 0, 60, alt, wFrom, wKt, tas, hdgToFix)
            hold.value = t
            holdLive.value = HoldLive()
            lastDist = null; wasAbeamSide = null; inboundStart = null; inboundMaxXtk = 0.0
            holdShape.value = TrainerMath.holdShape(fix.lat, fix.lon, Mag.magToTrue(inbound, decl), t.rightTurns, tas, t.legSec)
            // NAV1/ADF na fix, OBS na kurs dolotowy
            if (fix.hasVor) { vm.tune(0, fix); vm.setObs(0, inbound) } else vm.tuneAdf(fix)
            vm.directTo(fix)
            if (simulate) {
                vm.engine.windFromMag = wFrom; vm.engine.windKt = wKt
                vm.engine.placeSim(start.first, start.second, hdgToFix, alt)
                vm.startTrackRecording("Holding ${fix.ident}")
            }
        }
    }

    fun clearHold() { hold.value = null; holdShape.value = emptyList(); holdLive.value = HoldLive() }

    private var lastDist: Double? = null
    private var lastPassageMs = 0L
    private var wasAbeamSide: Boolean? = null
    private var abeamMs: Long? = null
    private var inboundStart: Long? = null
    private var inboundMaxXtk = 0.0

    private fun stepHold(p: Position) {
        val t = hold.value ?: return
        val now = p.timeMs
        val d = Geo.distanceNm(p.lat, p.lon, t.fix.lat, t.fix.lon)
        val decl = Mag.declination(t.fix.lat, t.fix.lon)
        val inboundTrue = Mag.magToTrue(t.inboundMag, decl)
        // pozycja względem fixu wzdłuż kursu dolotowego: along < 0 = przed fixem (na dolocie), > 0 = za fixem
        val brgFix = Geo.bearingTrue(t.fix.lat, t.fix.lon, p.lat, p.lon)
        val rel = Math.toRadians(brgFix - inboundTrue)
        val along = d * kotlin.math.cos(rel)            // + = przed fixem w kierunku dolotu (już minięty), − = przed fixem
        val xtk = d * kotlin.math.sin(rel)
        val trkMag = p.trackTrue?.let { Mag.trueToMag(it, decl) }
        var live = holdLive.value

        // przelot nad fixem: minimum odległości poniżej 0.6 NM
        val ld = lastDist
        if (ld != null && ld < 0.6 && d > ld && now - lastPassageMs > 30_000) {
            lastPassageMs = now
            val inStart = inboundStart
            val laps = if (live.passages >= 1 && inStart != null) {
                live.laps + HoldLap(live.laps.size + 1, ((now - inStart) / 1000).toInt(), inboundMaxXtk)
            } else live.laps
            live = live.copy(passages = live.passages + 1, laps = laps, outboundSec = null)
            inboundStart = null; inboundMaxXtk = 0.0; abeamMs = null; wasAbeamSide = null
        }
        lastDist = d

        // trawers fixu na odcinku odlotowym: along zmienia znak przy torze ~ odlotowym
        val outbound = Geo.norm360(t.inboundMag + 180.0)
        if (trkMag != null && abs(Geo.norm180(trkMag - outbound)) < 45) {
            val side = along > 0
            if (wasAbeamSide == true && !side && abeamMs == null) abeamMs = now
            wasAbeamSide = side
        }
        abeamMs?.let { live = live.copy(outboundSec = ((now - it) / 1000).toInt()) }

        // odcinek dolotowy: tor w granicach 20° kursu dolotowego, przed fixem
        if (trkMag != null && abs(Geo.norm180(trkMag - t.inboundMag)) < 20 && along < 0 && live.passages >= 1) {
            if (inboundStart == null) { inboundStart = now; inboundMaxXtk = 0.0 }
            inboundMaxXtk = maxOf(inboundMaxXtk, abs(xtk))
        }
        live = live.copy(sinceFixSec = if (lastPassageMs > 0) ((now - lastPassageMs) / 1000).toInt() else 0)
        holdLive.value = live
    }

    // ---------------- przechwytywanie ----------------

    fun newIntercept(useNdb: Boolean, simulate: Boolean) {
        vm.viewModelScope.launch {
            val list = stations(vm.position.value, !useNdb)
            if (list.isEmpty()) { vm.toast.value = "Brak ${if (useNdb) "NDB" else "VOR"} w bazie w pobliżu — pobierz bazę"; return@launch }
            val st = list.take(6).random()
            val inbound = Random.nextBoolean()
            val radialOrBrg = (Random.nextInt(72) * 5).toDouble()       // radial (VOR) / QDR (NDB), magnetyczny
            val course = if (inbound) Geo.norm360(radialOrBrg + 180.0) else radialOrBrg
            val decl = if (st.hasVor) VorReceiver.stationVariation(st, vm.settings.value) else Mag.declination(st.lat, st.lon)
            // samolot po tej samej stronie stacji, 15–60° od radialu, 8–18 NM
            val off = Random.nextDouble(15.0, 60.0) * (if (Random.nextBoolean()) 1 else -1)
            val start = Geo.destination(st.lat, st.lon, Mag.magToTrue(radialOrBrg + off, decl), Random.nextDouble(8.0, 18.0))
            val hdg = (Random.nextInt(36) * 10).toDouble()
            val task = InterceptTask(st, course, inbound, start.first, start.second, hdg)
            intercept.value = task
            interceptLive.value = InterceptLive()
            iStart = 0L; iSide = 0; iEstSince = null
            if (st.hasVor) { vm.tune(0, st); vm.setObs(0, course) } else vm.tuneAdf(st)
            if (simulate) {
                vm.engine.windKt = 0.0
                vm.engine.placeSim(start.first, start.second, hdg, (Random.nextInt(3, 7) * 500).toDouble())
                vm.startTrackRecording("Przechwycenie ${st.ident}")
            }
        }
    }

    fun clearIntercept() { intercept.value = null; interceptLive.value = InterceptLive() }

    /** Strona samolotu względem linii kursu: +1 = na prawo od kursu, −1 = na lewo. */
    fun interceptSide(t: InterceptTask, lat: Double, lon: Double): Int {
        val dev = deviation(t, lat, lon) ?: return 0
        return if (dev < 0) 1 else -1
    }

    /**
     * Odchylenie w stopniach w konwencji CDI (+ = leć w prawo).
     * VOR — z odbiornika, NDB — różnica namiaru.
     */
    fun deviation(t: InterceptTask, lat: Double, lon: Double): Double? {
        val st = t.station
        return if (st.hasVor) {
            val pos = Position(lat, lon, 3000.0, 0.0, null, pl.pilot.vornav.loc.PosSource.SIM)
            VorReceiver.compute(st, pos, t.courseMag, vm.settings.value.copy(ignoreRange = true)).deviationDeg
        } else {
            val decl = Mag.declination(st.lat, st.lon)
            if (t.inbound) {
                val qdm = Mag.trueToMag(Geo.bearingTrue(lat, lon, st.lat, st.lon), decl)
                Geo.norm180(qdm - t.courseMag)
            } else {
                val qdr = Mag.trueToMag(Geo.bearingTrue(st.lat, st.lon, lat, lon), decl)
                -Geo.norm180(qdr - t.courseMag)
            }
        }
    }

    /**
     * Ocena podanego kursu przechwycenia: musi zbliżać do linii kursu pod kątem 20–90°.
     * Zwraca (poprawny?, komentarz).
     */
    fun checkInterceptHeading(t: InterceptTask, hdg: Double): Pair<Boolean, String> {
        val side = interceptSide(t, t.startLat, t.startLon)
        val angle = Geo.norm180(hdg - t.courseMag)          // + = w prawo od kursu
        val need = if (side > 0) "w lewo" else "w prawo"
        val ok45 = Geo.norm360(t.courseMag - side * 45.0)
        val ok30 = Geo.norm360(t.courseMag - side * 30.0)
        val converging = (side > 0 && angle < 0) || (side < 0 && angle > 0)
        return when {
            !converging -> false to "Ten kurs oddala od linii kursu. Jesteś na ${if (side > 0) "prawo" else "lewo"} od kursu — skręcaj $need od ${fmtDeg(t.courseMag)}. Np. ${fmtDeg(ok45)} (45°) lub ${fmtDeg(ok30)} (30°)."
            abs(angle) < 20 -> false to "Kąt przechwycenia ${abs(angle).toInt()}° jest za mały — przechwycenie potrwa bardzo długo. Typowo 30–45°: ${fmtDeg(ok30)}–${fmtDeg(ok45)}."
            abs(angle) > 90 -> false to "Kąt ${abs(angle).toInt()}° jest większy niż 90° — polecisz w złą stronę wzdłuż kursu. Spróbuj ${fmtDeg(ok45)}."
            else -> true to "Dobrze: przechwycenie pod kątem ${abs(angle).toInt()}° ($need). Typowe odpowiedzi: ${fmtDeg(ok30)} (30°) lub ${fmtDeg(ok45)} (45°)."
        }
    }

    private var iStart = 0L
    private var iSide = 0
    private var iEstSince: Long? = null

    private fun stepIntercept(p: Position) {
        val t = intercept.value ?: return
        var live = interceptLive.value
        if (live.establishedSec != null) return
        if (iStart == 0L) iStart = p.timeMs
        val dev = deviation(t, p.lat, p.lon) ?: return
        val side = if (dev < 0) 1 else -1
        if (iSide == 0) iSide = side
        var crossed = live.crossed
        var over = live.overshootDeg
        if (side != iSide) crossed = true
        if (crossed && side != iSide) over = maxOf(over, abs(dev).coerceAtMost(10.0))
        val decl = Mag.declination(p.lat, p.lon)
        val trk = p.trackTrue?.let { Mag.trueToMag(it, decl) }
        val onCourse = abs(dev) < 2.0 && trk != null && abs(Geo.norm180(trk - t.courseMag)) < 10
        val est = if (onCourse) (iEstSince ?: p.timeMs.also { iEstSince = it }) else { iEstSince = null; null }
        val elapsed = ((p.timeMs - iStart) / 1000).toInt()
        live = live.copy(
            elapsedSec = elapsed, devDeg = dev, crossed = crossed, overshootDeg = over,
            establishedSec = if (est != null && p.timeMs - est >= 20_000) ((est - iStart) / 1000).toInt() else null
        )
        interceptLive.value = live
    }
}
