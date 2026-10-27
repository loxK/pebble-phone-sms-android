// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.loxk.pebblephone

import android.content.Context
import androidx.core.content.edit

/** A text size on the watch, sent with the HELLO reply. Values as in PROTOCOL.md. */
enum class TextSize(val wire: Int) {
    SYSTEM(0),
    SMALL(1),
    MEDIUM(2),
    LARGE(3),
    EXTRA_LARGE(4),
}

/** What the watch launcher shows under the app name. */
enum class GlanceEvent {
    LATEST,
    MISSED_CALL,
    SMS,
    NONE,
}

object Settings {
    private const val PREFERENCES = "settings"
    const val MENU_SIZE = "menu_size"
    const val MESSAGE_SIZE = "message_size"
    const val SMS_SIM = "sms_sim"
    const val CALL_SIM = "call_sim"
    private const val GLANCE = "glance"
    private const val WALLPAPER_COLORS = "wallpaper_colors"

    // A SIM setting holds one of these, or the subscription id of a SIM.
    const val SIM_PHONE = "phone"
    const val SIM_THREAD = "thread"

    fun textSize(context: Context, key: String): TextSize {
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(key, null)
        return TextSize.entries.firstOrNull { it.name == stored } ?: TextSize.SYSTEM
    }

    fun setTextSize(context: Context, key: String, size: TextSize) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { putString(key, size.name) }
    }

    /** Texts answer from the SIM of their conversation, calls follow the phone. */
    fun sim(context: Context, key: String): String =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(key, null)
            ?: if (key == SMS_SIM) SIM_THREAD else SIM_PHONE

    fun setSim(context: Context, key: String, value: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { putString(key, value) }
    }

    /** Material You colors, on by default; off, the app takes the colors of its icon. */
    fun wallpaperColors(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(WALLPAPER_COLORS, true)

    fun setWallpaperColors(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { putBoolean(WALLPAPER_COLORS, on) }
    }

    fun glance(context: Context): GlanceEvent {
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(GLANCE, null)
        return GlanceEvent.entries.firstOrNull { it.name == stored } ?: GlanceEvent.LATEST
    }

    fun setGlance(context: Context, event: GlanceEvent) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { putString(GLANCE, event.name) }
    }
}
