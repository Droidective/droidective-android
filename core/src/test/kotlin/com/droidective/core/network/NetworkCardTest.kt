package com.droidective.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

class NetworkCardTest {
    private val home =
        NetworkNow(Transport.Wifi, "Home 5G", localIp = "192.168.1.23", publicIp = "203.0.113.7")

    @Test
    fun theTitleIsTheWifiNameOrTheKindOfConnection() {
        assertEquals("Home 5G", NetworkCards.title(home))
        assertEquals("Wi-Fi", NetworkCards.title(home.copy(wifiName = null)), "name not told")
        assertEquals("Mobile data", NetworkCards.title(NetworkNow(Transport.Cellular)))
        assertEquals("Offline", NetworkCards.title(NetworkNow(Transport.None)))
        assertEquals(
            "Mobile data",
            NetworkCards.title(NetworkNow(Transport.Cellular, wifiName = "stale")),
            "a name left over from Wi-Fi isn't shown on mobile data",
        )
    }

    @Test
    fun theAddressesShownAreTheOnesChosenTheFirstLargest() {
        assertEquals(
            listOf(
                NetworkLine("Local", "192.168.1.23", main = true),
                NetworkLine("Public", "203.0.113.7", main = false),
            ),
            NetworkCards.lines(home, NetworkCard()),
        )
        assertEquals(
            listOf(NetworkLine("Public", "203.0.113.7", main = true)),
            NetworkCards.lines(home, NetworkCard(showLocal = false)),
        )
        assertEquals(
            listOf(NetworkLine("Local", "192.168.1.23", main = true)),
            NetworkCards.lines(home, NetworkCard(showLocal = false, showPublic = false)),
            "nothing chosen shows the local address rather than an empty widget",
        )
    }

    @Test
    fun anAddressNotKnownSaysSo() {
        val lookupFailed = home.copy(publicIp = null)
        assertEquals("Not found", NetworkCards.lines(lookupFailed, NetworkCard())[1].value)
        val offline = NetworkNow(Transport.None)
        assertEquals(listOf("—", "—"), NetworkCards.lines(offline, NetworkCard()).map { it.value })
    }

    @Test
    fun aTapCopiesTheChosenAddress() {
        assertEquals("192.168.1.23", NetworkCards.copied(home, NetworkCard()))
        assertEquals("203.0.113.7", NetworkCards.copied(home, NetworkCard(copies = Copies.Public)))
        assertNull(NetworkCards.copied(NetworkNow(Transport.None), NetworkCard()))
    }

    @Test
    fun anOldFileReadsAsItDidAndTheChoicesRoundTrip() {
        assertEquals(NetworkCard(), Json.decodeFromString(NetworkCard.serializer(), "{}"))
        val chosen = NetworkCard(showName = false, showPublic = false, copies = Copies.Public)
        val json = Json.encodeToString(NetworkCard.serializer(), chosen)
        assertEquals(chosen, Json.decodeFromString(NetworkCard.serializer(), json))
    }
}
