// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.loxk.pebblephone

import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import java.io.ByteArrayOutputStream

/** AppMessage keys, numbered as in the watchapp package.json. See PROTOCOL.md. */
object Keys {
    const val CMD = 1u
    const val SEQ = 2u
    const val ID = 3u
    const val OFFSET = 4u
    const val TOTAL = 5u
    const val ITEMS = 6u
    const val TEXT = 7u
    const val ADDRESS = 8u
    const val NAME = 9u
    const val STATE = 10u
    const val FLAGS = 11u
    const val ELAPSED = 12u
    const val STATUS = 13u
    const val BUDGET = 14u
    const val SIZE = 15u
    const val BODY_SIZE = 16u
}

object Cmd {
    const val HELLO = 1
    const val FAVORITES = 2
    const val LETTERS = 3
    const val CONTACTS = 4
    const val NUMBERS = 5
    const val CALLS = 6
    const val THREADS = 7
    const val MESSAGES = 8
    const val MESSAGE_BODY = 9
    const val DIAL = 10
    const val SEND_SMS = 11
    const val HANG_UP = 12
    const val MUTE = 13
    const val AUDIO_ROUTE = 14
    const val CALL_STATE = 15
    const val PHOTO = 16
    const val SEARCH = 17
    const val REPLIES = 18
    const val OPEN_THREAD = 19
}

object Status {
    const val OK = 0
    const val NO_PERMISSION = 1
    const val NOT_FOUND = 2
    const val FAILED = 3
    const val PENDING = 4
}

object CallStates {
    const val NONE = 0
    const val DIALING = 1
    const val RINGING = 2
    const val ACTIVE = 3
    const val HOLDING = 4
    const val ENDED = 5
}

object CallFlags {
    const val MUTED = 1 shl 0
    const val HANG_UP_ONLY = 1 shl 8

    fun route(route: Int) = (route and 0x0f) shl 4
}

/** A request from the watch. Numbers arrive as 32-bit values whatever their size on the watch. */
class Request(private val data: PebbleDictionary) {
    val cmd get() = int(Keys.CMD)
    val seq get() = int(Keys.SEQ)
    val id get() = int(Keys.ID)
    val offset get() = int(Keys.OFFSET).coerceAtLeast(0)
    val budget get() = int(Keys.BUDGET)
    val flags get() = int(Keys.FLAGS)
    val text get() = string(Keys.TEXT)
    val address get() = string(Keys.ADDRESS)

    private fun int(key: UInt): Int = when (val item = data[key]) {
        is PebbleDictionaryItem.Int32 -> item.value
        is PebbleDictionaryItem.UInt32 -> item.value.toInt()
        is PebbleDictionaryItem.Int16 -> item.value.toInt()
        is PebbleDictionaryItem.UInt16 -> item.value.toInt()
        is PebbleDictionaryItem.Int8 -> item.value.toInt()
        is PebbleDictionaryItem.UInt8 -> item.value.toInt()
        else -> 0
    }

    private fun string(key: UInt): String? = (data[key] as? PebbleDictionaryItem.Text)?.value
}

/** One row of a list sent to the watch. Strings are cut to what a watch row can show. */
data class Record(
    val flags: Int = 0,
    val id: Int = 0,
    val time: Long = 0,
    val title: String = "",
    val detail: String = "",
    val extra: String = "",
) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(flags)
        out.writeInt32(id)
        out.writeInt32((time / 1000).toInt())
        out.writeString(title, TITLE_BYTES)
        out.writeString(detail, DETAIL_BYTES)
        out.writeString(extra, EXTRA_BYTES)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeInt32(value: Int) {
        for (shift in 0 until 32 step 8) write(value ushr shift and 0xff)
    }

    private fun ByteArrayOutputStream.writeString(value: String, limit: Int) {
        write(value.utf8Prefix(limit))
        write(0)
    }

    companion object {
        const val TITLE_BYTES = 40
        const val DETAIL_BYTES = 120
        const val EXTRA_BYTES = 31
    }
}

/**
 * Fills a page from [offset] with as many records as fit in [budget] bytes.
 * Always takes at least one record so that the watch keeps moving.
 */
fun page(records: List<Record>, offset: Int, budget: Int): ByteArray {
    val out = ByteArrayOutputStream()
    for (record in records.drop(offset)) {
        val bytes = record.encode()
        if (out.size() > 0 && out.size() + bytes.size > budget) break
        out.write(bytes)
    }
    return out.toByteArray()
}

/** The longest prefix of this string that fits in [limit] bytes of UTF-8 without splitting a character. */
fun String.utf8Prefix(limit: Int): ByteArray {
    val bytes = toByteArray(Charsets.UTF_8)
    if (bytes.size <= limit) return bytes
    var end = limit
    while (end > 0 && (bytes[end].toInt() and 0xc0) == 0x80) end--
    return bytes.copyOf(end)
}

fun String.fitsIn(limit: Int) = toByteArray(Charsets.UTF_8).size <= limit
