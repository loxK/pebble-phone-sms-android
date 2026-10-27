// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.loxk.pebblephone

import android.content.Context
import android.icu.text.Transliterator
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Contacts as ContactsTable
import android.provider.ContactsContract.PhoneLookup
import android.telephony.PhoneNumberUtils
import java.text.Normalizer

class Contacts(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val toLatin by lazy { Transliterator.getInstance("Any-Latin; Latin-ASCII") }

    // The phonetic name is what Android builds from the phonetic fields of the contact sheet.
    private data class Contact(val id: Long, val name: String, val phonetic: String = "")

    /**
     * Starred contacts, with the label of their main number as detail and the number as extra.
     * One query over the phone numbers of starred contacts, the main number of each first.
     */
    fun favorites(): List<Record> {
        val records = LinkedHashMap<Long, Record>()
        resolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.TYPE, Phone.LABEL),
            "${Phone.STARRED} = 1",
            null,
            "${Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC, ${Phone.CONTACT_ID}, " +
                "${Phone.IS_SUPER_PRIMARY} DESC, ${Phone.IS_PRIMARY} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                if (id in records) continue
                val name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                val label = Phone.getTypeLabel(context.resources, cursor.getInt(3), cursor.getString(4))
                records[id] = Record(id = id.toInt(), title = name, detail = label.toString(), extra = cursor.getString(2).orEmpty())
            }
        }
        return records.values.toList()
    }

    /** One record per initial: the number of contacts as id, their first names as detail. */
    fun letters(): List<Record> =
        query("${ContactsTable.HAS_PHONE_NUMBER} = 1")
            .groupBy { initial(it.name) }
            .toSortedMap(compareBy<String> { it == "#" }.thenBy { it })
            .map { (letter, contacts) ->
                val preview = contacts.take(PREVIEW_NAMES).joinToString(", ") { it.name.substringBefore(' ') }
                Record(id = contacts.size, title = letter, detail = preview)
            }

    fun byLetter(letter: String): List<Record> =
        query("${ContactsTable.HAS_PHONE_NUMBER} = 1")
            .filter { initial(it.name) == letter }
            .map { Record(id = it.id.toInt(), title = it.name) }

    /**
     * Contacts matching a dictated query. Every word of the query has to match a word of the
     * name, of its phonetic spelling or of a nickname, ignoring case and accents, as a whole
     * word, a prefix, or with one letter off.
     */
    fun search(query: String): List<Record> {
        val terms = words(query)
        if (terms.isEmpty()) return emptyList()
        val nicknames = nicknames()
        return query("${ContactsTable.HAS_PHONE_NUMBER} = 1")
            .mapNotNull { contact ->
                val known = words(contact.name) + words(contact.phonetic) +
                    nicknames[contact.id].orEmpty().flatMap { words(it) }
                score(terms, known)?.let { contact to it }
            }
            .sortedByDescending { it.second }
            .take(SEARCH_LIMIT)
            .map { (contact, _) -> Record(id = contact.id.toInt(), title = contact.name) }
    }

    fun numbers(contactId: Long): List<Record> {
        val records = mutableListOf<Record>()
        val seen = mutableSetOf<String>()
        resolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.NUMBER, Phone.TYPE, Phone.LABEL, Phone.IS_SUPER_PRIMARY),
            "${Phone.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            "${Phone.IS_SUPER_PRIMARY} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val number = cursor.getString(0) ?: continue
                if (!seen.add(PhoneNumberUtils.normalizeNumber(number))) continue
                val label = Phone.getTypeLabel(context.resources, cursor.getInt(1), cursor.getString(2))
                records += Record(title = label.toString(), detail = number)
            }
        }
        return records
    }

    /** Display name of a phone number, when it belongs to a contact. */
    fun nameOf(number: String): String? = namesOf(listOf(number))[number]

    /** Display names of many numbers at once, from [ContactNames]. */
    fun namesOf(numbers: Collection<String>): Map<String, String> = ContactNames.of(context, numbers)

    /** Contact owning a phone number. */
    fun idOf(number: String): Long? = lookup(number, PhoneLookup._ID)?.toLongOrNull()

    private fun lookup(number: String, column: String): String? {
        if (number.isBlank()) return null
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return resolver.query(uri, arrayOf(column), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    /** Nicknames by contact. */
    private fun nicknames(): Map<Long, List<String>> {
        val nicknames = mutableMapOf<Long, MutableList<String>>()
        resolver.query(
            Data.CONTENT_URI,
            arrayOf(Data.CONTACT_ID, Nickname.NAME),
            "${Data.MIMETYPE} = ?",
            arrayOf(Nickname.CONTENT_ITEM_TYPE),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                nicknames.getOrPut(cursor.getLong(0)) { mutableListOf() } += name
            }
        }
        return nicknames
    }

    private fun query(selection: String): List<Contact> {
        val contacts = mutableListOf<Contact>()
        resolver.query(
            ContactsTable.CONTENT_URI,
            arrayOf(ContactsTable._ID, ContactsTable.DISPLAY_NAME_PRIMARY, ContactsTable.PHONETIC_NAME),
            selection,
            null,
            "${ContactsTable.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                contacts += Contact(cursor.getLong(0), name, cursor.getString(2).orEmpty())
            }
        }
        return contacts
    }

    private fun initial(name: String): String {
        val first = Normalizer.normalize(name.trim().take(1), Normalizer.Form.NFD).firstOrNull()
        return if (first != null && first.isLetter()) first.uppercase() else "#"
    }

    /**
     * The query in plain Latin letters. Speech recognition models that guess the language
     * sometimes write a lone first name in another script ("Давид" for David).
     */
    fun latin(text: String): String = transliterate(text).trim().trimEnd { !it.isLetterOrDigit() }

    // Requests run concurrently and a transliterator is not safe to share between threads.
    private fun transliterate(text: String): String = synchronized(toLatin) { toLatin.transliterate(text) }

    private fun words(text: String): List<String> =
        transliterate(text)
            .lowercase()
            .split(SEPARATORS)
            .filter { it.isNotEmpty() }

    /** Sum of the best match of each term, or null when one term matches nothing. */
    private fun score(terms: List<String>, name: List<String>): Int? {
        var total = 0
        for (term in terms) {
            val best = name.maxOfOrNull { word ->
                when {
                    word == term -> 3
                    word.startsWith(term) -> 2
                    term.length >= 4 && closeEnough(word, term) -> 1
                    else -> 0
                }
            } ?: 0
            if (best == 0) return null
            total += best
        }
        return total
    }

    /** True when one letter added, removed or changed turns a into b. */
    private fun closeEnough(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++
                j++
                continue
            }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> {
                    i++
                    j++
                }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }

    private companion object {
        const val PREVIEW_NAMES = 3
        const val SEARCH_LIMIT = 30
        val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
    }
}
