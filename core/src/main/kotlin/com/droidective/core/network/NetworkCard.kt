package com.droidective.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How the phone is connected, as the network widget names it. */
enum class Transport(val title: String) {
    Wifi("Wi-Fi"),
    Cellular("Mobile data"),
    Ethernet("Ethernet"),
    Vpn("VPN"),
    Other("Connected"),
    None("Offline"),
}

/**
 * The phone's network now, as the widget reads it.
 *
 * @property wifiName null when it isn't Wi-Fi, or Android didn't say: it tells only the shell, or
 *   an app with location access.
 * @property publicIp the address servers see, null until it's been looked up or when that failed.
 */
data class NetworkNow(
    val transport: Transport,
    val wifiName: String? = null,
    val localIp: String? = null,
    val publicIp: String? = null,
)

/** One fact about the network that a slot shows as its label, and a tap on it copies. */
@Serializable
enum class NetworkFact(val title: String, val symbol: String) {
    @SerialName("local-ip") LocalIp("IP address", "lan"),
    @SerialName("public-ip") PublicIp("Public IP", "public"),
    @SerialName("name") Name("Wi-Fi name", "wifi"),
}

/** Which address a tap on the widget copies. */
@Serializable
enum class Copies(val title: String) {
    @SerialName("local") Local("Local IP"),
    @SerialName("public") Public("Public IP"),
}

/**
 * What one network widget shows, as the person chose it. Every field added later defaults to how
 * widgets drew before it, so an old file reads the same.
 *
 * @property showName the Wi-Fi name, or the kind of connection, on top.
 * @property showLocal the address on this network, such as 192.168.1.23.
 * @property showPublic the address servers see, looked up from api.ipify.org.
 */
@Serializable
data class NetworkCard(
    val showName: Boolean = true,
    val showLocal: Boolean = true,
    val showPublic: Boolean = true,
    val copies: Copies = Copies.Local,
)

/** One address the widget draws: [main] is the first, drawn largest. */
data class NetworkLine(val label: String, val value: String, val main: Boolean)

/** How a network widget reads: its title, its addresses, and what a tap copies. */
object NetworkCards {
    /** What's on top: the Wi-Fi network's name, or the kind of connection. */
    fun title(now: NetworkNow): String =
        now.wifiName?.takeIf { now.transport == Transport.Wifi } ?: now.transport.title

    /**
     * The addresses [card] shows, the first drawn largest. With both turned off it shows the local
     * one anyway: a widget showing nothing is a widget that looks broken.
     */
    fun lines(now: NetworkNow, card: NetworkCard): List<NetworkLine> {
        val offline = now.transport == Transport.None
        val local = NetworkLine("Local", now.localIp ?: NONE, main = false)
        val public =
            NetworkLine(
                "Public",
                now.publicIp ?: if (offline) NONE else UNKNOWN,
                main = false,
            )
        val shown =
            listOfNotNull(local.takeIf { card.showLocal }, public.takeIf { card.showPublic })
                .ifEmpty { listOf(local) }
        return shown.mapIndexed { index, line -> line.copy(main = index == 0) }
    }

    /** The address a tap copies, or null when it isn't known. */
    fun copied(now: NetworkNow, card: NetworkCard): String? =
        when (card.copies) {
            Copies.Local -> now.localIp
            Copies.Public -> now.publicIp
        }

    /**
     * What a slot showing [fact] says now: the address or the name, or null when it isn't known.
     */
    fun fact(now: NetworkNow, fact: NetworkFact): String? =
        when (fact) {
            NetworkFact.LocalIp -> now.localIp
            NetworkFact.PublicIp -> now.publicIp
            NetworkFact.Name -> title(now)
        }

    private const val NONE = "—"
    private const val UNKNOWN = "Not found"
}
