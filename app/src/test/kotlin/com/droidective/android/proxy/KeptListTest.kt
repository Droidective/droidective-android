package com.droidective.android.proxy

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.droidective.core.net.proxy.Mock
import com.droidective.core.net.proxy.MockHeader
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The mocks, the breakpoints: read once, changed one at a time, kept in order. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class KeptListTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val applied = mutableListOf<List<Mock>>()
    private val menu =
        Mock(
            "m1",
            "https://api.example.com/menu",
            method = "GET",
            status = 503,
            headers = listOf(MockHeader("Retry-After", "30")),
            body = "{\"error\":\"closed\"}",
        )
    private val orders = Mock("m2", "https://api.example.com/orders/*")

    private fun list() = KeptList(FILE, Mock.serializer(), Mock::id) { applied += it }

    @Test
    fun aNewOneGoesAfterTheRestAndAChangedOneStaysInPlace() {
        val mocks = list()
        mocks.save(context, menu)
        mocks.save(context, orders)
        mocks.save(context, menu.copy(enabled = false))

        assertEquals(listOf(menu.copy(enabled = false), orders), mocks.items.value)
    }

    @Test
    fun whatIsKeptIsWhatTheNextRunReads() {
        list().apply {
            save(context, menu)
            save(context, orders)
            remove(context, "m2")
        }

        assertEquals(listOf(menu), list().apply { load(context) }.items.value)
    }

    @Test
    fun everyChangeIsHandedOnAtOnce() {
        val mocks = list()
        mocks.save(context, menu)
        mocks.remove(context, "m1")

        assertEquals(listOf(listOf(menu), emptyList()), applied)
    }

    @Test
    fun nothingKeptYetIsAnEmptyList() {
        assertTrue(list().apply { load(context) }.items.value.isEmpty())
    }

    private companion object {
        const val FILE = "kept-list-test.json"
    }
}
