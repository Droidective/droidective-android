package com.droidective.core.location

import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A way for the phone to travel: waypoints in order, at a speed, once or round and round — what a
 * delivery app's tracking screen or a run-tracking app needs to be tested against, which no single
 * place can show.
 */
data class Route(val points: List<Coordinates>, val metresPerSecond: Double, val loop: Boolean) {
    init {
        require(points.size >= 2) { "a route needs two places at least" }
        require(metresPerSecond > 0) { "a route needs a speed" }
    }

    /** Where the phone is along the way: the place, which way it faces, how fast it goes. */
    data class Fix(
        val where: Coordinates,
        /** Degrees clockwise from north, as Location's bearing is. */
        val bearing: Double,
        val metresPerSecond: Double,
        /** Whether a route that doesn't loop has come to its end, and stopped there. */
        val arrived: Boolean,
    )

    private val legs: List<Pair<Coordinates, Coordinates>> =
        points.zipWithNext() + if (loop) listOf(points.last() to points.first()) else emptyList()

    /** How far it is, end to end — and back to the start, for a loop. */
    val metres: Double = legs.sumOf { (from, to) -> Geo.distance(from, to) }

    /** Where the phone is [seconds] after setting off. */
    fun at(seconds: Double): Fix {
        var travelled = metresPerSecond * seconds.coerceAtLeast(0.0)
        if (loop && metres > 0) {
            travelled %= metres
        } else if (travelled >= metres) {
            val (from, to) = legs.last()
            return Fix(to, Geo.bearing(from, to), 0.0, arrived = true)
        }
        for ((from, to) in legs) {
            val leg = Geo.distance(from, to)
            if (travelled <= leg && leg > 0) {
                val bearing = Geo.bearing(from, to)
                return Fix(Geo.along(from, to, travelled / leg), bearing, metresPerSecond, false)
            }
            travelled -= leg
        }
        // Only legs of no length left: the phone stands at the last place.
        val (from, to) = legs.last()
        return Fix(to, Geo.bearing(from, to), metresPerSecond, arrived = false)
    }

    /** "3 places · 2.4 km · 29 min": how long the way is, and how long it takes. */
    fun summary(): String {
        val distance =
            if (metres < KILOMETRE) "${metres.roundToInt()} m"
            else String.format(Locale.ROOT, "%.1f km", metres / KILOMETRE)
        val minutes = (metres / metresPerSecond / MINUTE).roundToInt().coerceAtLeast(1)
        val time =
            if (minutes < HOUR) "$minutes min" else "${minutes / HOUR} h ${minutes % HOUR} min"
        return "${points.size} places · $distance · $time${if (loop) " a round" else ""}"
    }

    companion object {
        private const val KILOMETRE = 1_000.0
        private const val MINUTE = 60.0
        private const val HOUR = 60

        /**
         * The places [text] lists, one a line in any form [Coordinates.parse] reads, or a GPX
         * file's track, route or waypoints pasted whole. Null when a line names no place.
         */
        fun points(text: String): List<Coordinates>? {
            if (GPX_POINT.containsMatchIn(text)) return gpx(text)
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            return lines.map { Coordinates.parse(it) ?: return null }
        }

        private fun gpx(text: String): List<Coordinates>? {
            val points =
                GPX_POINT.findAll(text).mapNotNull { element ->
                    val attributes = element.value
                    val lat = GPX_LAT.find(attributes)?.groupValues?.get(1)?.toDoubleOrNull()
                    val lon = GPX_LON.find(attributes)?.groupValues?.get(1)?.toDoubleOrNull()
                    if (lat == null || lon == null) null
                    else runCatching { Coordinates(lat, lon) }.getOrNull()
                }
            return points.toList().takeIf { it.isNotEmpty() }
        }

        private val GPX_POINT = Regex("""<(?:trkpt|rtept|wpt)\b[^>]*>""")
        private val GPX_LAT = Regex("""\blat\s*=\s*["']([-+0-9.]+)["']""")
        private val GPX_LON = Regex("""\blon\s*=\s*["']([-+0-9.]+)["']""")
    }
}

/** Speeds a route is travelled at, as people move. */
enum class Pace(val title: String, val metresPerSecond: Double) {
    Walk("Walk", 1.4),
    Run("Run", 2.8),
    Cycle("Cycle", 5.0),
    Drive("Drive", 13.9),
    Highway("Highway", 27.8),
}

/** Distances and directions on the Earth's surface, taken as a sphere. */
object Geo {
    /** The mean radius, in metres. */
    private const val EARTH = 6_371_008.8

    /** The great-circle distance between [a] and [b], in metres. */
    fun distance(a: Coordinates, b: Coordinates): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h =
            sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(a.latitude)) *
                    cos(Math.toRadians(b.latitude)) *
                    sin(dLon / 2) *
                    sin(dLon / 2)
        return 2 * EARTH * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** The direction from [a] to [b] as it sets off, in degrees clockwise from north, 0 to 360. */
    fun bearing(a: Coordinates, b: Coordinates): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + FULL_TURN) % FULL_TURN
    }

    /** The place [fraction] of the way from [a] to [b] along the great circle. */
    fun along(a: Coordinates, b: Coordinates, fraction: Double): Coordinates {
        val delta = distance(a, b) / EARTH
        if (delta == 0.0) return a
        val lat1 = Math.toRadians(a.latitude)
        val lon1 = Math.toRadians(a.longitude)
        val lat2 = Math.toRadians(b.latitude)
        val lon2 = Math.toRadians(b.longitude)
        val first = sin((1 - fraction) * delta) / sin(delta)
        val second = sin(fraction * delta) / sin(delta)
        val x = first * cos(lat1) * cos(lon1) + second * cos(lat2) * cos(lon2)
        val y = first * cos(lat1) * sin(lon1) + second * cos(lat2) * sin(lon2)
        val z = first * sin(lat1) + second * sin(lat2)
        val latitude = Math.toDegrees(atan2(z, sqrt(x * x + y * y)))
        val longitude = Math.toDegrees(atan2(y, x))
        return Coordinates(latitude.coerceIn(-90.0, 90.0), longitude.coerceIn(-180.0, 180.0))
    }

    private const val FULL_TURN = 360.0
}
