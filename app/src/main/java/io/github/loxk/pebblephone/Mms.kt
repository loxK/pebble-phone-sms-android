// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.loxk.pebblephone

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.provider.Telephony.Mms as MmsTable
import android.util.Size
import java.io.IOException

/**
 * The MMS table. Google Messages files RCS chats there as well, each message an MMS holding a
 * text part, a picture or both. Its dates are in seconds where SMS dates are in milliseconds.
 */
class Mms(private val context: Context) {
    private val resolver get() = context.contentResolver

    /** The text parts of a message joined, its first picture part, and whether it holds anything else. */
    data class Content(val text: String, val picture: Long?, val attachment: Boolean)

    fun contents(messages: Collection<Long>): Map<Long, Content> {
        if (messages.isEmpty()) return emptyMap()
        val texts = mutableMapOf<Long, MutableList<String>>()
        val pictures = mutableMapOf<Long, Long>()
        val attachments = mutableSetOf<Long>()
        resolver.query(
            MmsTable.Part.CONTENT_URI,
            arrayOf(MmsTable.Part._ID, MmsTable.Part.MSG_ID, MmsTable.Part.CONTENT_TYPE, MmsTable.Part.TEXT),
            "${MmsTable.Part.MSG_ID} IN (${messages.joinToString(",")})",
            null,
            MmsTable.Part.SEQ,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val part = cursor.getLong(0)
                val message = cursor.getLong(1)
                val type = cursor.getString(2).orEmpty().lowercase()
                when {
                    type == TEXT -> texts.getOrPut(message) { mutableListOf() } += cursor.getString(3) ?: partText(part)
                    type.startsWith(IMAGE) -> pictures.putIfAbsent(message, part)
                    // The layout of the slides, not something to show.
                    type != SMIL -> attachments += message
                }
            }
        }
        return messages.associateWith {
            Content(texts[it].orEmpty().joinToString("\n").trim(), pictures[it], it in attachments)
        }
    }

    /** The sender of a received message. */
    fun sender(message: Long): String? =
        resolver.query(
            MmsTable.Addr.getAddrUriForMessage(message.toString()),
            arrayOf(MmsTable.Addr.ADDRESS),
            "${MmsTable.Addr.TYPE} = ?",
            arrayOf(ADDRESS_FROM.toString()),
            null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }

    /** Width and height of a picture part, as shown: the decoder turns photos upright. */
    fun pictureSize(part: Long): Size? {
        synchronized(sizes) { sizes[part] }?.let { return it }
        var size: Size? = null
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, partUri(part))) { _, info, _ ->
                size = info.size
                throw HeaderRead()
            }
        } catch (e: HeaderRead) {
            // Only the header was wanted.
        } catch (e: IOException) {
            return null
        }
        size?.let { synchronized(sizes) { sizes[part] = it } }
        return size
    }

    /** A picture part decoded straight to [width] by [height], in memory the encoder can read. */
    fun picture(part: Long, width: Int, height: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, partUri(part))) { decoder, _, _ ->
            decoder.setTargetSize(width, height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: IOException) {
        null
    }

    /**
     * Text parts too long for the table live in a file. A text file sent as an attachment
     * lands there too, whatever its size: only its start is read.
     */
    private fun partText(part: Long): String = try {
        resolver.openInputStream(partUri(part))?.use { String(it.readNBytes(PART_TEXT_BYTES), Charsets.UTF_8) }.orEmpty()
    } catch (e: IOException) {
        ""
    }

    private fun partUri(part: Long) = ContentUris.withAppendedId(MmsTable.Part.CONTENT_URI, part)

    private class HeaderRead : RuntimeException()

    companion object {
        // Parts never change once stored: their sizes are read once per process.
        private const val SIZE_CACHE = 200
        private val sizes = object : LinkedHashMap<Long, Size>(SIZE_CACHE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Size>) = size > SIZE_CACHE
        }

        // PduHeaders.FROM, hidden from the SDK.
        private const val ADDRESS_FROM = 137
        private const val TEXT = "text/plain"
        // Far more than the watch shows of a message, which is its inbox, 4 KB at most.
        private const val PART_TEXT_BYTES = 8192
        private const val IMAGE = "image/"
        private const val SMIL = "application/smil"
    }
}
