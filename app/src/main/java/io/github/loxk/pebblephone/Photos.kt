// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.loxk.pebblephone

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.provider.ContactsContract.Contacts as ContactsTable

/**
 * Contact photos and message pictures in the watch's own pixel formats, so the watch copies
 * bytes instead of decoding an image it has no memory for.
 */
class Photos(private val context: Context, private val contacts: Contacts, private val messages: Messages, private val mms: Mms) {
    // Pages of one photo are asked one after the other: keep the last few encoded.
    private val cache = object : LinkedHashMap<String, ByteArray>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) = size > CACHE_SIZE
    }

    /** The photo of a contact, or of the contact owning [number] when [contactId] is 0. */
    fun encoded(contactId: Long, number: String?, size: Int, format: Int): ByteArray? {
        if (size !in 8..MAX_SIZE) return null
        val key = "$contactId/$number/$size/$format"
        synchronized(cache) { cache[key] }?.let { return it }
        val id = if (contactId != 0L) contactId else number?.let { contacts.idOf(it) } ?: return null
        val bitmap = load(id, size) ?: return null
        return encode(bitmap, format, circle = true).also { bytes -> synchronized(cache) { cache[key] = bytes } }
    }

    /** The picture of message [messageId], as [Messages.messages] numbers it, [width] by [height]. */
    fun picture(messageId: Long, width: Int, height: Int, format: Int): ByteArray? {
        if (width !in 8..MAX_PICTURE_SIDE || height !in 8..MAX_PICTURE_SIDE) return null
        val key = "picture/$messageId/$width/$height/$format"
        synchronized(cache) { cache[key] }?.let { return it }
        val part = messages.pictureOf(messageId) ?: return null
        val decoded = mms.picture(part, width, height) ?: return null
        val bitmap = opaque(decoded, width, height)
        return encode(bitmap, format, circle = false).also { bytes -> synchronized(cache) { cache[key] = bytes } }
    }

    private fun encode(bitmap: Bitmap, format: Int, circle: Boolean): ByteArray {
        val bytes = if (format == FORMAT_MONO) mono(bitmap, circle) else color(bitmap, circle)
        bitmap.recycle()
        return bytes
    }

    private fun load(contactId: Long, size: Int): Bitmap? {
        val uri = ContentUris.withAppendedId(ContactsTable.CONTENT_URI, contactId)
        val source = ContactsTable.openContactPhotoInputStream(context.contentResolver, uri, true)
            ?.use { BitmapFactory.decodeStream(it) } ?: return null
        val side = minOf(source.width, source.height)
        val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        // createScaledBitmap hands back its input when the size already matches.
        val scaled = Bitmap.createScaledBitmap(square, size, size, true)
        if (square !== source && square !== scaled) square.recycle()
        if (source !== scaled) source.recycle()
        return opaque(scaled, size, size)
    }

    /** Transparent images go on white, as the contacts and messaging apps show them. */
    private fun opaque(image: Bitmap, width: Int, height: Int): Bitmap {
        val opaque = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply {
            drawColor(Color.WHITE)
            drawBitmap(image, 0f, 0f, null)
        }
        image.recycle()
        return opaque
    }

    private fun insideCircle(x: Int, y: Int, size: Int): Boolean {
        val center = (size - 1) / 2f
        val dx = x - center
        val dy = y - center
        val radius = size / 2f
        return dx * dx + dy * dy <= radius * radius
    }

    /**
     * One byte per pixel, 0bAARRGGBB, dithered to two bits per channel; with [circle], transparent
     * outside the inscribed circle.
     */
    private fun color(bitmap: Bitmap, circle: Boolean): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val count = width * height
        val pixels = IntArray(count).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
        val channels = Array(3) { channel ->
            FloatArray(count) { (pixels[it] shr (16 - channel * 8) and 0xff).toFloat() }
        }
        val levels = Array(3) { IntArray(count) }
        for (channel in 0 until 3) dither(channels[channel], width, height, 3, levels[channel])
        return ByteArray(count) { i ->
            val alpha = if (!circle || insideCircle(i % width, i / width, width)) 3 else 0
            (alpha shl 6 or (levels[0][i] shl 4) or (levels[1][i] shl 2) or levels[2][i]).toByte()
        }
    }

    /**
     * One bit per pixel, 1 for white, least significant bit first, rows padded to bytes; with
     * [circle], white outside the inscribed circle.
     */
    private fun mono(bitmap: Bitmap, circle: Boolean): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
        val luma = FloatArray(width * height) {
            val p = pixels[it]
            0.299f * (p shr 16 and 0xff) + 0.587f * (p shr 8 and 0xff) + 0.114f * (p and 0xff)
        }
        val levels = IntArray(width * height)
        dither(luma, width, height, 1, levels)
        val rowBytes = (width + 7) / 8
        val out = ByteArray(rowBytes * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (levels[y * width + x] == 1 || (circle && !insideCircle(x, y, width))) {
                    val index = y * rowBytes + x / 8
                    out[index] = (out[index].toInt() or (1 shl (x % 8))).toByte()
                }
            }
        }
        return out
    }

    /** Floyd–Steinberg error diffusion of 0..255 values down to 0..[maxLevel]. */
    private fun dither(values: FloatArray, width: Int, height: Int, maxLevel: Int, out: IntArray) {
        val step = 255f / maxLevel
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val level = (values[i] / step + 0.5f).toInt().coerceIn(0, maxLevel)
                out[i] = level
                val error = values[i] - level * step
                if (x + 1 < width) values[i + 1] += error * 7 / 16
                if (y + 1 < height) {
                    if (x > 0) values[i + width - 1] += error * 3 / 16
                    values[i + width] += error * 5 / 16
                    if (x + 1 < width) values[i + width + 1] += error * 1 / 16
                }
            }
        }
    }

    companion object {
        const val FORMAT_COLOR = 0
        const val FORMAT_MONO = 1
        private const val MAX_SIZE = 144
        private const val MAX_PICTURE_SIDE = 400
        private const val CACHE_SIZE = 6
    }
}
