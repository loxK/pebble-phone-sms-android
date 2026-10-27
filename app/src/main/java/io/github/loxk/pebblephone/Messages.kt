// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.loxk.pebblephone

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.BaseColumns
import android.provider.Telephony.Mms as MmsTable
import android.provider.Telephony.Sms
import android.provider.Telephony.Threads
import android.telephony.SmsManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class Messages(private val context: Context, private val contacts: Contacts, private val mms: Mms) {
    private val resolver get() = context.contentResolver

    /** The latest message of a conversation: when, and a line of it. */
    private data class Latest(val time: Long, val preview: String)

    /**
     * One person's conversation. Android keeps one thread per notation of a number, so the
     * same person can have several: the most recent comes first and names the conversation.
     */
    private class Conversation(val threads: List<Long>, val address: String, val unread: Boolean)

    /**
     * Conversations with one other person, most recent first, SMS and RCS alike, the threads of
     * one number together. Group conversations stay out: a reply sent as SMS could only reach
     * one of their members.
     */
    private fun conversations(): List<Conversation> {
        val addresses = canonicalAddresses()
        val country = PhoneNumbers.country(context)
        val people = LinkedHashMap<String, MutableList<Triple<Long, String, Boolean>>>()
        resolver.query(
            Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build(),
            arrayOf(Threads._ID, Threads.RECIPIENT_IDS, Threads.READ, Threads.MESSAGE_COUNT),
            null,
            null,
            "${Threads.DATE} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val recipient = cursor.getString(1).orEmpty().trim().toLongOrNull() ?: continue
                if (cursor.getInt(3) == 0) continue
                val address = addresses[recipient] ?: continue
                people.getOrPut(PhoneNumbers.key(address, country)) { mutableListOf() } +=
                    Triple(cursor.getLong(0), address, cursor.getInt(2) == 0)
            }
        }
        val merged = people.values.count { it.size > 1 }
        if (merged > 0) Log.d(TAG, "${people.size} conversations, $merged of them over several threads")
        return people.values.map { threads ->
            Conversation(threads.map { it.first }, threads.first().second, threads.any { it.third })
        }
    }

    // Threads of the person last opened: the pages of one conversation and its reply ask for
    // them in turn, and a new notation of a number is rare enough to wait a little.
    @Volatile
    private var lastGroup: Pair<Long, List<Long>>? = null

    /** Every thread of the person [thread] belongs to. */
    private fun threadsWith(thread: Long): List<Long> {
        val now = SystemClock.elapsedRealtime()
        lastGroup?.let { (at, threads) -> if (thread in threads && now - at < GROUP_TTL_MS) return threads }
        val threads = conversations().firstOrNull { thread in it.threads }?.threads ?: listOf(thread)
        lastGroup = now to threads
        return threads
    }

    fun threads(limit: Int = THREAD_LIMIT): List<Record> {
        val conversations = conversations().take(limit)
        if (conversations.isEmpty()) return emptyList()
        val latest = latest(conversations.flatMap { it.threads })
        val names = if (Capabilities.has(context, Manifest.permission.READ_CONTACTS)) {
            contacts.namesOf(conversations.map { it.address })
        } else {
            emptyMap()
        }
        return conversations.mapNotNull { conversation ->
            val message = conversation.threads.mapNotNull { latest[it] }.maxByOrNull { it.time } ?: return@mapNotNull null
            Record(
                id = conversation.threads.first().toInt(),
                flags = if (conversation.unread) THREAD_UNREAD else 0,
                time = message.time,
                title = names[conversation.address] ?: conversation.address,
                detail = String(message.preview.oneLine().utf8Prefix(PREVIEW_BYTES)),
                extra = conversation.address,
            )
        }
    }

    /** Addresses of the conversations, by the ids the threads table names them with. */
    private fun canonicalAddresses(): Map<Long, String> {
        val addresses = mutableMapOf<Long, String>()
        resolver.query(CANONICAL_ADDRESSES, arrayOf(ID, ADDRESS), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) addresses[cursor.getLong(0)] = cursor.getString(1).orEmpty()
        }
        return addresses
    }

    /** The latest SMS and the latest MMS of each thread, whichever is newer. */
    /**
     * The latest SMS and the latest MMS of each thread, whichever is newer. The scans read ids
     * and dates only, so they run to the oldest message when a thread is quiet; the texts of
     * the messages kept come after, in one query.
     */
    private fun latest(threads: List<Long>): Map<Long, Latest> {
        val within = threads.joinToString(",")
        val newestSms = mutableMapOf<Long, Pair<Long, Long>>()
        resolver.query(
            Sms.CONTENT_URI,
            arrayOf(Sms.THREAD_ID, Sms._ID, Sms.DATE),
            "${Sms.THREAD_ID} IN ($within) AND ${Sms.TYPE} != ?",
            arrayOf(Sms.MESSAGE_TYPE_DRAFT.toString()),
            "${Sms.DATE} DESC",
        )?.use { cursor ->
            while (newestSms.size < threads.size && cursor.moveToNext()) {
                newestSms.putIfAbsent(cursor.getLong(0), cursor.getLong(1) to cursor.getLong(2))
            }
        }
        val newestMms = mutableMapOf<Long, Pair<Long, Long>>()
        resolver.query(
            MmsTable.CONTENT_URI,
            arrayOf(MmsTable.THREAD_ID, MmsTable._ID, MmsTable.DATE),
            "${MmsTable.THREAD_ID} IN ($within) AND ${MmsTable.MESSAGE_BOX} != ?",
            arrayOf(MmsTable.MESSAGE_BOX_DRAFTS.toString()),
            "${MmsTable.DATE} DESC",
        )?.use { cursor ->
            while (newestMms.size < threads.size && cursor.moveToNext()) {
                newestMms.putIfAbsent(cursor.getLong(0), cursor.getLong(1) to cursor.getLong(2) * 1000)
            }
        }

        val smsWins = newestSms.filter { (thread, sms) -> sms.second >= (newestMms[thread]?.second ?: Long.MIN_VALUE) }
        val mmsWins = newestMms.filterKeys { it !in smsWins }
        val bodies = mutableMapOf<Long, String>()
        if (smsWins.isNotEmpty()) {
            resolver.query(
                Sms.CONTENT_URI,
                arrayOf(Sms._ID, Sms.BODY),
                "${Sms._ID} IN (${smsWins.values.joinToString(",") { it.first.toString() }})",
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) bodies[cursor.getLong(0)] = cursor.getString(1).orEmpty()
            }
        }
        val contents = mms.contents(mmsWins.values.map { it.first })
        val latest = mutableMapOf<Long, Latest>()
        for ((thread, sms) in smsWins) latest[thread] = Latest(sms.second, bodies[sms.first].orEmpty())
        for ((thread, message) in mmsWins) {
            latest[thread] = Latest(message.second, contents[message.first]?.let { preview(it) }.orEmpty())
        }
        return latest
    }

    /** The text of a message, after a tag naming what else it holds. */
    private fun preview(content: Mms.Content): String {
        val tag = when {
            content.picture != null -> context.getString(R.string.mms_picture)
            content.attachment -> context.getString(R.string.mms_attachment)
            else -> return content.text
        }
        return if (content.text.isEmpty()) tag else "$tag ${content.text}"
    }

    /** The latest SMS or RCS received since [since], in milliseconds, with the start of its text. */
    fun lastReceived(since: Long): Record? {
        val sms = resolver.query(
            Sms.CONTENT_URI,
            arrayOf(Sms.ADDRESS, Sms.BODY, Sms.DATE),
            "${Sms.TYPE} = ? AND ${Sms.DATE} >= ?",
            arrayOf(Sms.MESSAGE_TYPE_INBOX.toString(), since.toString()),
            "${Sms.DATE} DESC",
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            Record(time = cursor.getLong(2), title = cursor.getString(0).orEmpty(), detail = cursor.getString(1).orEmpty())
        }
        val received = resolver.query(
            MmsTable.CONTENT_URI,
            arrayOf(MmsTable._ID, MmsTable.DATE),
            "${MmsTable.MESSAGE_BOX} = ? AND ${MmsTable.DATE} >= ?",
            arrayOf(MmsTable.MESSAGE_BOX_INBOX.toString(), (since / 1000).toString()),
            "${MmsTable.DATE} DESC",
        )?.use { if (it.moveToFirst()) it.getLong(0) to it.getLong(1) * 1000 else null }
        val record = if (received != null && received.second > (sms?.time ?: Long.MIN_VALUE)) {
            val (id, time) = received
            val content = mms.contents(listOf(id))[id]
            Record(time = time, title = mms.sender(id).orEmpty(), detail = content?.let { preview(it) }.orEmpty())
        } else {
            sms
        } ?: return null
        val name = if (Capabilities.has(context, Manifest.permission.READ_CONTACTS)) contacts.nameOf(record.title) else null
        return record.copy(
            title = name ?: record.title,
            detail = String(record.detail.oneLine().utf8Prefix(PREVIEW_BYTES)),
        )
    }

    /**
     * Messages of one thread, most recent first. An MMS goes by its id negated, so that
     * [body] and [pictureOf] tell it from an SMS, and a picture carries its size in extra.
     */
    fun messages(thread: Long, limit: Int = MESSAGE_LIMIT): List<Record> {
        val within = threadsWith(thread).joinToString(",")
        val records = mutableListOf<Record>()
        resolver.query(
            Sms.CONTENT_URI,
            arrayOf(Sms._ID, Sms.BODY, Sms.DATE, Sms.TYPE),
            "${Sms.THREAD_ID} IN ($within) AND ${Sms.TYPE} != ?",
            arrayOf(Sms.MESSAGE_TYPE_DRAFT.toString()),
            "${Sms.DATE} DESC",
        )?.use { cursor ->
            while (records.size < limit && cursor.moveToNext()) {
                val body = cursor.getString(1).orEmpty()
                var flags = if (cursor.getInt(3) == Sms.MESSAGE_TYPE_INBOX) 0 else MESSAGE_SENT
                if (!body.fitsIn(Record.DETAIL_BYTES)) flags = flags or MESSAGE_CUT
                records += Record(flags = flags, id = cursor.getInt(0), time = cursor.getLong(2), detail = body)
            }
        }
        val rows = mutableListOf<Triple<Long, Long, Boolean>>()
        resolver.query(
            MmsTable.CONTENT_URI,
            arrayOf(MmsTable._ID, MmsTable.DATE, MmsTable.MESSAGE_BOX),
            "${MmsTable.THREAD_ID} IN ($within) AND ${MmsTable.MESSAGE_BOX} != ?",
            arrayOf(MmsTable.MESSAGE_BOX_DRAFTS.toString()),
            "${MmsTable.DATE} DESC",
        )?.use { cursor ->
            while (rows.size < limit && cursor.moveToNext()) {
                rows += Triple(cursor.getLong(0), cursor.getLong(1) * 1000, cursor.getInt(2) != MmsTable.MESSAGE_BOX_INBOX)
            }
        }
        val contents = mms.contents(rows.map { it.first })
        for ((id, time, sent) in rows) {
            val content = contents[id] ?: continue
            var flags = if (sent) MESSAGE_SENT else 0
            val size = content.picture?.let { mms.pictureSize(it) }
            if (size != null) flags = flags or MESSAGE_PICTURE
            val body = if (size == null) preview(content) else content.text
            if (!body.fitsIn(Record.DETAIL_BYTES)) flags = flags or MESSAGE_CUT
            records += Record(
                flags = flags,
                id = -id.toInt(),
                time = time,
                detail = body,
                extra = size?.let { "${it.width}x${it.height}" }.orEmpty(),
            )
        }
        return records.sortedByDescending { it.time }.take(limit)
    }

    /** Subscription of the latest message of [thread], null when unknown. */
    fun subscriptionOf(thread: Long): Int? {
        val within = threadsWith(thread).joinToString(",")
        val fromSms = resolver.query(
            Sms.CONTENT_URI,
            arrayOf(Sms.SUBSCRIPTION_ID, Sms.DATE),
            "${Sms.THREAD_ID} IN ($within) AND ${Sms.TYPE} != ?",
            arrayOf(Sms.MESSAGE_TYPE_DRAFT.toString()),
            "${Sms.DATE} DESC",
        )?.use { if (it.moveToFirst()) it.getInt(0) to it.getLong(1) else null }
        val fromMms = resolver.query(
            MmsTable.CONTENT_URI,
            arrayOf(MmsTable.SUBSCRIPTION_ID, MmsTable.DATE),
            "${MmsTable.THREAD_ID} IN ($within) AND ${MmsTable.MESSAGE_BOX} != ?",
            arrayOf(MmsTable.MESSAGE_BOX_DRAFTS.toString()),
            "${MmsTable.DATE} DESC",
        )?.use { if (it.moveToFirst()) it.getInt(0) to it.getLong(1) * 1000 else null }
        val latest = listOfNotNull(fromSms, fromMms).maxByOrNull { it.second } ?: return null
        return latest.first.takeIf { it >= 0 }
    }

    fun body(id: Long): String? {
        if (id < 0) return mms.contents(listOf(-id))[-id]?.text
        return resolver.query(Sms.CONTENT_URI, arrayOf(Sms.BODY), "${Sms._ID} = ?", arrayOf(id.toString()), null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    /** The picture part of a message listed by [messages], null for an SMS. */
    fun pictureOf(id: Long): Long? = if (id < 0) mms.contents(listOf(-id))[-id]?.picture else null

    /**
     * Sends [text] to [address] from [subscription], or the phone's default SIM when null, and
     * waits for the radio to report every part. Android files the message in the Sent box on
     * its own when this app is not the default SMS app. Answers [Status.OK] once every part
     * left, [Status.FAILED] when the radio refused one, or [Status.PENDING] when it has not
     * said within [SEND_TIMEOUT]: the text may still leave, so it must not read as failed and
     * be sent again.
     */
    suspend fun send(address: String, text: String, subscription: Int?): Int {
        val default = context.getSystemService(SmsManager::class.java)
        val sms = if (subscription != null) default.createForSubscriptionId(subscription) else default
        val destination = PhoneNumbers.key(context, address)
        val parts = sms.divideMessage(text)
        val action = "$ACTION_SENT.${sendCounter.incrementAndGet()}"
        val results = Channel<Int>(parts.size)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                results.trySend(resultCode)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val sentIntents = ArrayList(
                parts.indices.map { part ->
                    PendingIntent.getBroadcast(
                        context,
                        part,
                        Intent(action).setPackage(context.packageName),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
                    )
                },
            )
            sms.sendMultipartTextMessage(destination, null, parts, sentIntents, null)
            return withTimeoutOrNull(SEND_TIMEOUT) {
                (1..parts.size).all { results.receive() == Activity.RESULT_OK }
            }.let { sent -> if (sent == null) Status.PENDING else if (sent) Status.OK else Status.FAILED }
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // Already dropped with the context.
            }
        }
    }

    private fun String.oneLine() = replace(WHITESPACE, " ").trim()

    private companion object {
        const val TAG = "Messages"
        const val THREAD_LIMIT = 60
        const val GROUP_TTL_MS = 30_000L
        // A conversation row shows one line of its latest message: the rest would only fill the watch heap.
        const val PREVIEW_BYTES = 60
        const val MESSAGE_LIMIT = 50
        const val THREAD_UNREAD = 1 shl 0
        const val MESSAGE_SENT = 1 shl 0
        const val MESSAGE_CUT = 1 shl 1
        const val MESSAGE_PICTURE = 1 shl 2
        val CANONICAL_ADDRESSES: Uri = Uri.parse("content://mms-sms/canonical-addresses")
        const val ID = BaseColumns._ID
        const val ADDRESS = "address"
        const val ACTION_SENT = "io.github.loxk.pebblephone.SMS_SENT"
        val SEND_TIMEOUT = 60.seconds
        val WHITESPACE = Regex("\\s+")
        val sendCounter = AtomicInteger()
    }
}
