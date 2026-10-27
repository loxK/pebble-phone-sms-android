// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.loxk.pebblephone

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ProtocolTest {
    @Test
    fun utf8PrefixKeepsShortStringsWhole() {
        assertContentEquals("Bonjour".toByteArray(), "Bonjour".utf8Prefix(10))
    }

    @Test
    fun utf8PrefixNeverSplitsACharacter() {
        // "é" takes two bytes: four bytes hold "aé" and only half of the second "é".
        assertEquals("aé", String("aéé".utf8Prefix(4)))
        assertEquals("a", String("aé".utf8Prefix(2)))
        // A four-byte emoji is dropped whole rather than cut.
        assertEquals("ok", String("ok👍".utf8Prefix(5)))
    }

    @Test
    fun recordLayoutMatchesTheWatch() {
        val bytes = Record(flags = 3, id = 0x01020304, time = 1_000_000L, title = "Al", detail = "", extra = "6").encode()
        val expected = byteArrayOf(
            3,
            4, 3, 2, 1,
            0xe8.toByte(), 3, 0, 0,
            'A'.code.toByte(), 'l'.code.toByte(), 0,
            0,
            '6'.code.toByte(), 0,
        )
        assertContentEquals(expected, bytes)
    }

    @Test
    fun recordStringsAreCutToTheWatchBuffers() {
        val bytes = Record(title = "x".repeat(100)).encode()
        // flags, id, time, then the title cut to its limit and its terminator.
        assertEquals(0, bytes[9 + Record.TITLE_BYTES].toInt())
        assertEquals(9 + Record.TITLE_BYTES + 1 + 1 + 1, bytes.size)
    }

    @Test
    fun pageFillsTheBudgetAndStopsBeforeOverflowing() {
        val records = List(10) { Record(title = "contact $it") }
        val size = records.first().encode().size
        val page = page(records, offset = 0, budget = size * 3 + 1)
        assertEquals(size * 3, page.size)
    }

    @Test
    fun pageStartsAtTheOffset() {
        val records = List(4) { Record(id = it) }
        val size = records.first().encode().size
        val page = page(records, offset = 2, budget = 1000)
        assertEquals(size * 2, page.size)
        assertEquals(2, page[1].toInt())
    }

    @Test
    fun pageAlwaysTakesOneRecord() {
        val records = listOf(Record(detail = "y".repeat(100)))
        assertEquals(records.first().encode().size, page(records, offset = 0, budget = 10).size)
    }

    @Test
    fun pagePastTheEndIsEmpty() {
        assertEquals(0, page(listOf(Record()), offset = 5, budget = 1000).size)
    }
}
