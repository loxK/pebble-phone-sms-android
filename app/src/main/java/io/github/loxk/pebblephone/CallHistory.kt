// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.loxk.pebblephone

import android.content.Context
import android.provider.CallLog.Calls

class CallHistory(private val context: Context) {

    fun recent(limit: Int = LIMIT): List<Record> {
        val records = mutableListOf<Record>()
        context.contentResolver.query(
            Calls.CONTENT_URI,
            arrayOf(Calls.NUMBER, Calls.CACHED_NAME, Calls.TYPE, Calls.DATE),
            null,
            null,
            "${Calls.DATE} DESC",
        )?.use { cursor ->
            while (records.size < limit && cursor.moveToNext()) {
                val number = cursor.getString(0).orEmpty()
                val name = cursor.getString(1)?.takeIf { it.isNotBlank() }
                records += Record(
                    flags = watchType(cursor.getInt(2)),
                    time = cursor.getLong(3),
                    title = name ?: number.ifEmpty { context.getString(R.string.private_number) },
                    detail = number,
                )
            }
        }
        return records
    }

    /** The latest missed call since [since], in milliseconds. */
    fun lastMissed(since: Long): Record? =
        context.contentResolver.query(
            Calls.CONTENT_URI,
            arrayOf(Calls.NUMBER, Calls.CACHED_NAME, Calls.DATE),
            "${Calls.TYPE} = ? AND ${Calls.DATE} >= ?",
            arrayOf(Calls.MISSED_TYPE.toString(), since.toString()),
            "${Calls.DATE} DESC",
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val number = cursor.getString(0).orEmpty()
            Record(
                time = cursor.getLong(2),
                title = cursor.getString(1)?.takeIf { it.isNotBlank() }
                    ?: number.ifEmpty { context.getString(R.string.private_number) },
            )
        }

    // The watch knows incoming, outgoing, missed and rejected calls.
    private fun watchType(type: Int) = when (type) {
        Calls.ANSWERED_EXTERNALLY_TYPE -> Calls.INCOMING_TYPE
        Calls.VOICEMAIL_TYPE -> Calls.MISSED_TYPE
        Calls.BLOCKED_TYPE -> Calls.REJECTED_TYPE
        else -> type
    }

    private companion object {
        const val LIMIT = 100
    }
}
