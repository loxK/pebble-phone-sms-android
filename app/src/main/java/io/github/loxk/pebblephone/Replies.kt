// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.loxk.pebblephone

import android.content.Context
import androidx.core.content.edit

/** Quick replies offered on the watch, edited on the setup screen, one per line. */
object Replies {
    const val MAX = 10

    private const val PREFERENCES = "replies"
    private const val KEY = "lines"

    fun load(context: Context): List<String> {
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null)
        val lines = stored?.lines() ?: context.resources.getStringArray(R.array.default_replies).toList()
        return clean(lines)
    }

    fun save(context: Context, text: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            putString(KEY, clean(text.lines()).joinToString("\n"))
        }
    }

    // Each reply is cut to what a watch record carries, so the editor shows what gets sent.
    private fun clean(lines: List<String>) =
        lines.map { String(it.trim().utf8Prefix(Record.DETAIL_BYTES)) }.filter { it.isNotEmpty() }.take(MAX)
}
