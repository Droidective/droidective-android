package com.droidective.core.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CoordinatesTest {
    private val bigBen = Coordinates(51.5007292, -0.1268141)

    @Test
    fun aPairReadsHoweverItIsSeparated() {
        assertEquals(Coordinates(51.5074, -0.1278), Coordinates.parse("51.5074, -0.1278"))
        assertEquals(Coordinates(51.5074, -0.1278), Coordinates.parse(" 51.5074 -0.1278 "))
        assertEquals(Coordinates(-33.8688, 151.2093), Coordinates.parse("(-33.8688,151.2093)"))
        assertEquals(Coordinates(12.0, 77.0), Coordinates.parse("12;77"))
    }

    @Test
    fun aMapsLinkReadsAsThePlaceItShows() {
        val place =
            "https://www.google.com/maps/place/Big+Ben/@51.5007292,-0.1268141,17z/data=!3m1!4b1"
        assertEquals(bigBen, Coordinates.parse(place))
        assertEquals(bigBen, Coordinates.parse("https://maps.google.com/?q=51.5007292,-0.1268141"))
        assertEquals(
            bigBen,
            Coordinates.parse(
                "https://www.google.com/maps/search/?api=1&query=51.5007292%2C-0.1268141"
            ),
        )
        assertEquals(
            bigBen,
            Coordinates.parse("https://maps.google.com/maps?ll=51.5007292,-0.1268141&z=17"),
        )
    }

    @Test
    fun aGeoUriReadsAsItsPlaceOrItsQuery() {
        assertEquals(bigBen, Coordinates.parse("geo:51.5007292,-0.1268141"))
        assertEquals(bigBen, Coordinates.parse("geo:0,0?q=51.5007292,-0.1268141(Big Ben)"))
        // Nowhere in particular, and nothing asked for.
        assertNull(Coordinates.parse("geo:0,0?q=Big+Ben"))
    }

    @Test
    fun anOpenStreetMapLinkReadsToo() {
        assertEquals(
            Coordinates(51.5007, -0.1268),
            Coordinates.parse("https://www.openstreetmap.org/#map=17/51.5007/-0.1268"),
        )
        assertEquals(
            Coordinates(51.5007, -0.1268),
            Coordinates.parse(
                "https://www.openstreetmap.org/?mlat=51.5007&mlon=-0.1268#map=17/51.5/-0.12"
            ),
        )
    }

    @Test
    fun degreesMinutesAndSecondsRead() {
        val parsed = Coordinates.parse("""51°30'02.6"N 0°07'36.5"W""")!!
        assertEquals(51.500722, parsed.latitude, 0.00001)
        assertEquals(-0.126806, parsed.longitude, 0.00001)
        // Longitude first is still read the right way round, by its letters.
        val swapped = Coordinates.parse("""0°07'36.5"W 51°30'02.6"N""")!!
        assertEquals(parsed, swapped)
    }

    @Test
    fun aPlaceOffTheEarthIsNoPlace() {
        assertNull(Coordinates.parse("91, 0"))
        assertNull(Coordinates.parse("0, 181"))
        assertNull(Coordinates.parse("Big Ben"))
        assertNull(Coordinates.parse(""))
        assertNull(Coordinates.parse("51.5"))
        assertFailsWith<IllegalArgumentException> { Coordinates(-90.5, 0.0) }
    }

    @Test
    fun itWritesSixPlacesWhateverTheLocale() {
        assertEquals("51.500729, -0.126814", bigBen.toString())
    }
}
