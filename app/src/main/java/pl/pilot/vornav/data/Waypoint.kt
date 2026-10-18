package pl.pilot.vornav.data

import java.util.Locale

/** Punkt z bazy: lotnisko/lądowisko albo pomoc nawigacyjna. */
data class Waypoint(
    val kind: Kind,
    val id: Long,
    val ident: String,
    val name: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    val elevFt: Double,
    val country: String = "",
    val municipality: String = "",
    val freqKhz: Int = 0,
    val dmeChannel: String? = null,
    val dmeLat: Double? = null,
    val dmeLon: Double? = null,
    val variation: Double? = null,
    val power: String? = null,
    val source: String = SOURCE_OA,
) {
    enum class Kind { AIRPORT, NAVAID }

    val key: String get() = "${kind.name}:$id"

    val hasVor: Boolean get() = kind == Kind.NAVAID && (type == "VOR" || type == "VOR-DME" || type == "VORTAC")
    val hasDme: Boolean get() = kind == Kind.NAVAID && (type.contains("DME") || type == "VORTAC" || type == "TACAN")
    val isNdb: Boolean get() = kind == Kind.NAVAID && type.startsWith("NDB")
    val isTunable: Boolean get() = hasVor || hasDme
    val isAdfTunable: Boolean get() = isNdb

    /** Częstotliwość w formie jak w odbiorniku: 114.90 MHz albo 375 kHz (NDB). */
    val freqText: String
        get() = when {
            freqKhz <= 0 -> ""
            isNdb -> "$freqKhz kHz"
            else -> String.format(Locale.US, "%.2f", freqKhz / 1000.0)
        }

    val typeLabel: String
        get() = when (kind) {
            Kind.NAVAID -> type
            Kind.AIRPORT -> when (type) {
                "large_airport" -> "Lotnisko duże"
                "medium_airport" -> "Lotnisko"
                "small_airport" -> "Lotnisko małe"
                "heliport" -> "Heliport"
                "seaplane_base" -> "Wodne"
                "ul" -> "Lądowisko UL"
                "landing_site" -> "Lądowisko"
                else -> type
            }
        }

    companion object {
        const val SOURCE_OA = "oa"
        const val SOURCE_USER = "user"
    }
}
