package com.droidective.core.location

import kotlinx.serialization.Serializable

/** A place someone kept, to put the phone at again. */
@Serializable data class Place(val id: String, val name: String, val where: Coordinates)

/**
 * Places ready to use, for what changes by country: a currency, a store that ships there or
 * doesn't, an address form, a feature behind a region check.
 */
object Places {
    val ready: List<Place> =
        listOf(
            ready("London", 51.507400, -0.127800),
            ready("New York", 40.712800, -74.006000),
            ready("San Francisco", 37.774900, -122.419400),
            ready("São Paulo", -23.550500, -46.633300),
            ready("Berlin", 52.520000, 13.405000),
            ready("Dubai", 25.204800, 55.270800),
            ready("Bengaluru", 12.971600, 77.594600),
            ready("Singapore", 1.352100, 103.819800),
            ready("Tokyo", 35.676200, 139.650300),
            ready("Sydney", -33.868800, 151.209300),
        )

    private fun ready(name: String, latitude: Double, longitude: Double) =
        Place("ready:$name", name, Coordinates(latitude, longitude))
}
