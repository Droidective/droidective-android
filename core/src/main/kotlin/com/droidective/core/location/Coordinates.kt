package com.droidective.core.location

import java.util.Locale
import kotlinx.serialization.Serializable

/** A point on the Earth, in degrees, north and east positive. */
@Serializable
data class Coordinates(val latitude: Double, val longitude: Double) {
    init {
        require(latitude in LATITUDES) { "a latitude of $latitude, past ±90°" }
        require(longitude in LONGITUDES) { "a longitude of $longitude, past ±180°" }
    }

    /** "51.507400, -0.127800": six places, a tenth of a metre, as Maps copies them. */
    override fun toString(): String = String.format(Locale.ROOT, "%.6f, %.6f", latitude, longitude)

    companion object {
        private val LATITUDES = -90.0..90.0
        private val LONGITUDES = -180.0..180.0

        /**
         * The place [text] names, however it was copied: `51.5074, -0.1278`, a `geo:` URI, a Google
         * Maps or OpenStreetMap link, or degrees, minutes and seconds. Null when it names none, or
         * one off the Earth.
         */
        fun parse(text: String): Coordinates? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            return sequenceOf(::osm, ::marked, ::pair, ::dms)
                .mapNotNull { reading -> reading(trimmed) }
                .firstOrNull()
        }

        /**
         * OpenStreetMap's `mlat=`/`mlon=` marker, which is the place, or else its `#map=` view,
         * which is only where the map was looking.
         */
        private fun osm(text: String): Coordinates? {
            val lat = OSM_LAT.find(text)?.groupValues?.get(1)
            val lon = OSM_LON.find(text)?.groupValues?.get(1)
            if (lat != null && lon != null) return of(lat, lon)
            val view = OSM_MAP.find(text) ?: return null
            return of(view.groupValues[1], view.groupValues[2])
        }

        /**
         * A pair after a marker that says what it is: Maps' `@lat,lng`, `q=`, `ll=` or `query=`, or
         * a `geo:` URI's own — whose `q=` names the place when its own pair is `0,0`.
         */
        private fun marked(text: String): Coordinates? {
            for (marker in MARKERS) {
                val match = marker.find(text) ?: continue
                val found = of(match.groupValues[1], match.groupValues[2]) ?: continue
                val unset = found.latitude == 0.0 && found.longitude == 0.0
                if (!(unset && text.startsWith("geo:", ignoreCase = true))) return found
            }
            return null
        }

        /** Two numbers and nothing else of note: `51.5074, -0.1278` or `51.5074 -0.1278`. */
        private fun pair(text: String): Coordinates? {
            val match = PAIR.matchEntire(text) ?: return null
            return of(match.groupValues[1], match.groupValues[2])
        }

        /** `51°30'26.6"N 0°07'40.1"W`, as a degree sign and its hemisphere letter write it. */
        private fun dms(text: String): Coordinates? {
            val parts = DMS.findAll(text).toList()
            if (parts.size != 2) return null
            val (first, second) = parts.map { degrees(it) ?: return null }
            val firstIsLatitude = parts[0].groupValues[4].uppercase() in "NS"
            val latitude = if (firstIsLatitude) first else second
            val longitude = if (firstIsLatitude) second else first
            return runCatching { Coordinates(latitude, longitude) }.getOrNull()
        }

        private fun degrees(match: MatchResult): Double? {
            val (degrees, minutes, seconds, hemisphere) = match.destructured
            val value =
                (degrees.toDoubleOrNull() ?: return null) +
                    (minutes.toDoubleOrNull() ?: 0.0) / MINUTES +
                    (seconds.toDoubleOrNull() ?: 0.0) / SECONDS
            return if (hemisphere.uppercase() in "SW") -value else value
        }

        private fun of(latitude: String, longitude: String): Coordinates? {
            val lat = latitude.toDoubleOrNull() ?: return null
            val lng = longitude.toDoubleOrNull() ?: return null
            return runCatching { Coordinates(lat, lng) }.getOrNull()
        }

        private const val NUMBER = """([-+]?\d{1,3}(?:\.\d+)?)"""
        private const val MINUTES = 60.0
        private const val SECONDS = 3600.0

        private val PAIR = Regex("""^\(?$NUMBER\s*[,;\s]\s*$NUMBER\)?$""")
        private val MARKERS =
            listOf(
                Regex("""@$NUMBER,$NUMBER"""),
                Regex("""[?&](?:q|ll|query|center|daddr)=$NUMBER(?:,|%2C)\s*$NUMBER"""),
                Regex("""^geo:$NUMBER,$NUMBER""", RegexOption.IGNORE_CASE),
            )
        private val OSM_MAP = Regex("""#map=\d{1,2}/$NUMBER/$NUMBER""")
        private val OSM_LAT = Regex("""[?&]mlat=$NUMBER""")
        private val OSM_LON = Regex("""[?&]mlon=$NUMBER""")
        private val DMS =
            Regex(
                """(\d{1,3}(?:\.\d+)?)°\s*(?:(\d{1,2}(?:\.\d+)?)['′]\s*)?(?:(\d{1,2}(?:\.\d+)?)["″]\s*)?([NSEWnsew])"""
            )
    }
}
