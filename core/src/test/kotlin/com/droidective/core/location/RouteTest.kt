package com.droidective.core.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteTest {
    // Trafalgar Square to Big Ben, and on to the London Eye: a short walk.
    private val trafalgar = Coordinates(51.508039, -0.128069)
    private val bigBen = Coordinates(51.500729, -0.124625)
    private val eye = Coordinates(51.503324, -0.119543)

    @Test
    fun distancesAreAsTheMapsHaveThem() {
        // London to Paris is 343.5 km as the crow flies.
        val paris = Coordinates(48.856600, 2.352200)
        val london = Coordinates(51.507400, -0.127800)
        assertEquals(343_500.0, Geo.distance(london, paris), 1_000.0)
        assertEquals(0.0, Geo.distance(london, london))
    }

    @Test
    fun bearingsReadClockwiseFromNorth() {
        val here = Coordinates(0.0, 0.0)
        assertEquals(0.0, Geo.bearing(here, Coordinates(1.0, 0.0)), 0.001)
        assertEquals(90.0, Geo.bearing(here, Coordinates(0.0, 1.0)), 0.001)
        assertEquals(180.0, Geo.bearing(here, Coordinates(-1.0, 0.0)), 0.001)
        assertEquals(270.0, Geo.bearing(here, Coordinates(0.0, -1.0)), 0.001)
    }

    @Test
    fun halfwayIsHalfTheDistanceFromEachEnd() {
        val middle = Geo.along(trafalgar, bigBen, 0.5)
        val whole = Geo.distance(trafalgar, bigBen)
        assertEquals(whole / 2, Geo.distance(trafalgar, middle), 0.01)
        assertEquals(whole / 2, Geo.distance(middle, bigBen), 0.01)
        assertEquals(trafalgar, Geo.along(trafalgar, trafalgar, 0.5))
    }

    @Test
    fun theFixMovesAlongEachLegAtTheSpeedGiven() {
        val route = Route(listOf(trafalgar, bigBen, eye), Pace.Walk.metresPerSecond, loop = false)
        val first = Geo.distance(trafalgar, bigBen)
        val start = route.at(0.0)
        assertEquals(trafalgar, start.where)
        assertEquals(Geo.bearing(trafalgar, bigBen), start.bearing)
        assertEquals(1.4, start.metresPerSecond)
        // A minute in, 84 metres from the start along the first leg.
        assertEquals(84.0, Geo.distance(trafalgar, route.at(60.0).where), 0.01)
        // Past Big Ben, on the second leg, facing the Eye.
        val later = route.at((first + 50) / 1.4)
        assertEquals(Geo.bearing(bigBen, eye), later.bearing)
        assertEquals(50.0, Geo.distance(bigBen, later.where), 0.01)
        assertFalse(later.arrived)
    }

    @Test
    fun aRouteThatDoesNotLoopStopsAtItsEnd() {
        val route = Route(listOf(trafalgar, bigBen, eye), Pace.Drive.metresPerSecond, loop = false)
        val end = route.at(route.metres / Pace.Drive.metresPerSecond + 600)
        assertEquals(eye, end.where)
        assertEquals(0.0, end.metresPerSecond)
        assertTrue(end.arrived)
    }

    @Test
    fun aLoopGoesBackToTheStartAndRoundAgain() {
        val route = Route(listOf(trafalgar, bigBen, eye), 10.0, loop = true)
        val round = route.metres / 10.0
        assertEquals(
            Geo.distance(trafalgar, bigBen) +
                Geo.distance(bigBen, eye) +
                Geo.distance(eye, trafalgar),
            route.metres,
            0.001,
        )
        val once = route.at(30.0).where
        val twice = route.at(round + 30.0).where
        assertEquals(once.latitude, twice.latitude, 1e-9)
        assertEquals(once.longitude, twice.longitude, 1e-9)
        assertFalse(route.at(round * 3.5).arrived)
    }

    @Test
    fun aRouteSaysHowFarAndHowLong() {
        val walk = Route(listOf(trafalgar, bigBen, eye), Pace.Walk.metresPerSecond, loop = false)
        assertEquals("3 places · 1.3 km · 16 min", walk.summary())
        val short = Route(listOf(trafalgar, Geo.along(trafalgar, bigBen, 0.1)), 1.4, loop = true)
        assertEquals("2 places · 169 m · 2 min a round", short.summary())
        val far =
            Route(listOf(Coordinates(51.5074, -0.1278), Coordinates(48.8566, 2.3522)), 13.9, false)
        assertEquals("2 places · 343.6 km · 6 h 52 min", far.summary())
    }

    @Test
    fun aRouteNeedsTwoPlacesAndASpeed() {
        assertFailsWith<IllegalArgumentException> { Route(listOf(trafalgar), 1.0, loop = false) }
        assertFailsWith<IllegalArgumentException> {
            Route(listOf(trafalgar, bigBen), 0.0, loop = false)
        }
    }

    @Test
    fun placesAreReadALineEachInAnyForm() {
        val points =
            Route.points(
                """
                51.508039, -0.128069
                geo:51.500729,-0.124625

                https://www.google.com/maps/@51.503324,-0.119543,17z
                """
            )
        assertEquals(listOf(trafalgar, bigBen, eye), points)
        assertNull(Route.points("51.5, -0.12\nthe London Eye"))
    }

    @Test
    fun aGpxTrackIsReadWhole() {
        val gpx =
            """
            <?xml version="1.0"?>
            <gpx version="1.1" creator="test"><trk><trkseg>
              <trkpt lat="51.508039" lon="-0.128069"><ele>20</ele></trkpt>
              <trkpt lon="-0.124625" lat="51.500729"/>
              <trkpt lat='51.503324' lon='-0.119543'></trkpt>
            </trkseg></trk></gpx>
            """
        assertEquals(listOf(trafalgar, bigBen, eye), Route.points(gpx))
    }
}
