package com.droidective.core.crashes

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

class CrashScrubTest {
    @Test
    fun anotherAppsActivityNameIsTakenOut() {
        assertEquals(
            "Key \"<name>\" was already used. If you are using LazyColumn/Row please make sure " +
                "you provide a unique key for each item.",
            CrashScrub.text(
                "Key \"com.google.android.apps.chrome.Main\" was already used. If you are using " +
                    "LazyColumn/Row please make sure you provide a unique key for each item."
            ),
        )
    }

    @Test
    fun aTwoPartPackageNameIsTakenOut() {
        assertEquals("Package <name> not found", CrashScrub.text("Package com.whatsapp not found"))
    }

    @Test
    fun platformAndOwnNamesStay() {
        val message =
            "Attempt to invoke virtual method 'int java.lang.String.length()' on a null object " +
                "reference in com.droidective.android.proxy.TrafficProxy via android.os.Handler"
        assertEquals(message, CrashScrub.text(message))
    }

    @Test
    fun aTypeMemberStays() {
        assertEquals("String.format failed", CrashScrub.text("String.format failed"))
    }

    @Test
    fun urlsAreTakenOutWholeWithTheirQuery() {
        assertEquals(
            "GET <url> returned 500",
            CrashScrub.text("GET https://api.example.com/v1/users?token=abc returned 500"),
        )
        assertEquals("Can't open <url>", CrashScrub.text("Can't open content://media/external/1"))
    }

    @Test
    fun hostNamesAndFileNamesAreTakenOut() {
        assertEquals(
            "Unable to resolve host \"<name>\": No address associated with hostname",
            CrashScrub.text(
                "Unable to resolve host \"api.example.com\": No address associated with hostname"
            ),
        )
        assertEquals("<name> is not an APK", CrashScrub.text("base.apk is not an APK"))
        assertEquals("GET <name> timed out", CrashScrub.text("GET example.com/v1/users timed out"))
    }

    @Test
    fun absolutePathsAreTakenOut() {
        assertEquals(
            "<path>: open failed: EACCES (Permission denied)",
            CrashScrub.text(
                "/data/user/0/com.example.notes/files/db.sqlite: open failed: EACCES " +
                    "(Permission denied)"
            ),
        )
        assertEquals(
            "Couldn't read (<path>)",
            CrashScrub.text("Couldn't read (/storage/emulated/0/Download/My.apk)"),
        )
        assertEquals(
            "Install from <path> failed",
            CrashScrub.text("Install from /data/app/~~QmFzZQ==/com.example-1==/base.apk failed"),
        )
    }

    @Test
    fun slashesInWordsAndNumbersStay() {
        assertEquals("read and/or write 1/2", CrashScrub.text("read and/or write 1/2"))
    }

    @Test
    fun ipAddressesAreTakenOutWithTheirPort() {
        assertEquals(
            "failed to connect to /<ip> (port 8080) from /<ip> after 10000ms",
            CrashScrub.text(
                "failed to connect to /10.0.2.2 (port 8080) from /192.168.1.23:41234 after 10000ms"
            ),
        )
        assertEquals("bind <ip> failed", CrashScrub.text("bind 127.0.0.1:9090 failed"))
        assertEquals("listen on <ip>", CrashScrub.text("listen on ::1"))
        assertEquals("peer <ip> left", CrashScrub.text("peer fe80::1c2b:3aff:fe4d:5e6f left"))
        assertEquals(
            "peer <ip>",
            CrashScrub.text("peer 2001:0db8:85a3:0000:0000:8a2e:0370:7334"),
        )
    }

    @Test
    fun clockTimesAndVersionsStay() {
        assertEquals("at 12:30:45 on 0.21.1", CrashScrub.text("at 12:30:45 on 0.21.1"))
    }

    @Test
    fun emailAddressesAreTakenOut() {
        assertEquals(
            "Signed in as <email>",
            CrashScrub.text("Signed in as dev.name+x@example.co.uk"),
        )
    }

    @Test
    fun textWithNothingToTakeOutIsUnchanged() {
        val message = "lateinit property access has not been initialized"
        assertEquals(message, CrashScrub.text(message))
    }

    @Test
    fun anyIpv4AddressIsTakenOut() = runTest {
        checkAll(Arb.int(0..255), Arb.int(0..255), Arb.int(0..255), Arb.int(0..255)) { a, b, c, d ->
            val address = "$a.$b.$c.$d"
            assertFalse(address in CrashScrub.text("connect to $address refused"))
        }
    }

    @Test
    fun scrubbingTwiceChangesNothingMore() = runTest {
        checkAll(Arb.string(0..80)) { text ->
            val once = CrashScrub.text(text)
            assertEquals(once, CrashScrub.text(once))
        }
    }
}
