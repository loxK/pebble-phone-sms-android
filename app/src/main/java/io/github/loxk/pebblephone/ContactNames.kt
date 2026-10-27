// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.loxk.pebblephone

import android.content.Context
import android.database.ContentObserver
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import java.util.concurrent.atomic.AtomicInteger

/**
 * The display name behind each phone number of the contacts, read in one query and kept until
 * the contacts or the SIM country change. Numbers meet on [PhoneNumbers.key].
 */
object ContactNames {
    @Volatile
    private var directory: Pair<String, Map<String, String>>? = null
    private var observing = false

    // Bumped by every change: a directory read while one happened is not kept.
    private val changes = AtomicInteger()

    private val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            changes.incrementAndGet()
            directory = null
        }
    }

    fun of(context: Context, numbers: Collection<String>): Map<String, String> {
        val country = PhoneNumbers.country(context)
        val names = directory(context, country)
        return numbers.mapNotNull { number -> names[PhoneNumbers.key(number, country)]?.let { number to it } }.toMap()
    }

    @Synchronized
    private fun directory(context: Context, country: String): Map<String, String> {
        directory?.takeIf { it.first == country }?.let { return it.second }
        // Android only lets an app observe what it may read: the contacts permission comes first.
        if (!observing) {
            context.applicationContext.contentResolver
                .registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
            observing = true
        }
        val seen = changes.get()
        val names = mutableMapOf<String, String>()
        context.contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.NUMBER, Phone.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val number = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                    val name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    names.putIfAbsent(PhoneNumbers.key(number, country), name)
                }
            }
        if (changes.get() == seen) directory = country to names
        return names
    }
}
