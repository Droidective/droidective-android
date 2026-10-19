package com.droidective.core.shell

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResultFrameTest {
    private fun roundTrip(result: ShellResult): ShellResult {
        val bytes = ByteArrayOutputStream().also { ResultFrame.write(result, it) }.toByteArray()
        return ResultFrame.read(ByteArrayInputStream(bytes))
    }

    @Test
    fun carriesWhatABinderReplyCouldnt() {
        val flags = "namespace/flag_with_a_long_name=true\n".repeat(60_000)
        assertEquals(ShellResult(0, flags, ""), roundTrip(ShellResult(0, flags, "")))
    }

    @Test
    fun keepsTheExitCodeAndBothStreams() {
        val result = ShellResult(255, "", "Exception occurred while executing 'put':\r\n")
        assertEquals(result, roundTrip(result))
        assertEquals(ShellResult(-1, "", ""), roundTrip(ShellResult(-1, "", "")))
    }

    @Test
    fun keepsTextThatIsntAscii() {
        val result = ShellResult(0, "Café ☕ 日本語 \u0000 end", "ü")
        assertEquals(result, roundTrip(result))
    }

    @Test
    fun aWriterThatDiedPartWayIsAnErrorNotAnEmptyResult() {
        val bytes =
            ByteArrayOutputStream()
                .also { ResultFrame.write(ShellResult(0, "out", "err"), it) }
                .toByteArray()
        listOf(0, 3, 4, 9, bytes.size - 1).forEach { cut ->
            assertFailsWith<IOException>("cut at $cut") {
                ResultFrame.read(ByteArrayInputStream(bytes.copyOf(cut)))
            }
        }
    }

    @Test
    fun aCorruptSizeFailsBeforeAllocating() {
        val bytes =
            ByteArrayOutputStream()
                .also {
                    DataOutputStream(it).apply {
                        writeInt(0)
                        writeInt(Int.MAX_VALUE)
                    }
                }
                .toByteArray()
        assertFailsWith<IOException> { ResultFrame.read(ByteArrayInputStream(bytes)) }
    }
}
