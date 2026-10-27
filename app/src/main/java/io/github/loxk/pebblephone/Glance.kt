// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.loxk.pebblephone

import android.Manifest
import android.content.Context
import kotlin.time.Duration.Companion.days

/**
 * The event the watchapp writes under its name in the launcher when it closes, sent with the
 * HELLO reply. Older than [WINDOW], an event would already have left the launcher.
 */
object Glance {
    private const val MISSED_CALL = 1
    private const val SMS = 2
    private val WINDOW = 1.days

    fun latest(context: Context, history: CallHistory, messages: Messages): Record? {
        val choice = Settings.glance(context)
        val since = System.currentTimeMillis() - WINDOW.inWholeMilliseconds
        val call = if (
            choice in setOf(GlanceEvent.LATEST, GlanceEvent.MISSED_CALL) &&
            Capabilities.has(context, Manifest.permission.READ_CALL_LOG)
        ) {
            history.lastMissed(since)?.copy(flags = MISSED_CALL)
        } else {
            null
        }
        val sms = if (
            choice in setOf(GlanceEvent.LATEST, GlanceEvent.SMS) &&
            Capabilities.has(context, Manifest.permission.READ_SMS)
        ) {
            messages.lastReceived(since)?.copy(flags = SMS)
        } else {
            null
        }
        return listOfNotNull(call, sms).maxByOrNull { it.time }
    }
}
