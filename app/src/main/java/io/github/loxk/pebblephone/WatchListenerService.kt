// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.loxk.pebblephone

import android.Manifest
import android.companion.CompanionDeviceManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.os.SystemClock
import android.util.Log
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.zip.CRC32

/** Bound by the Pebble app while the watchapp is open; answers its requests. */
class WatchListenerService : BasePebbleListenerService() {
    // The Pebble app unbinds this service when the watchapp closes, while a request
    // such as an SMS send may still be running: the helpers outlive it.
    private val contacts by lazy { Contacts(applicationContext) }
    private val history by lazy { CallHistory(applicationContext) }
    private val mms by lazy { Mms(applicationContext) }
    private val messages by lazy { Messages(applicationContext, contacts, mms) }
    private val photos by lazy { Photos(applicationContext, contacts, messages, mms) }

    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        if (watchappUUID != Watch.APP_UUID) return ReceiveResult.Nack
        val request = Request(data)
        // Ack right away: a reply can take a while and goes out as a separate message.
        val received = SystemClock.elapsedRealtime()
        Watch.scope.launch {
            val started = SystemClock.elapsedRealtime()
            val reply = try {
                handle(request, watch)
            } catch (e: SecurityException) {
                Log.w(TAG, "Command ${request.cmd} refused", e)
                reply(request, Status.NO_PERMISSION)
            } catch (e: Exception) {
                Log.e(TAG, "Command ${request.cmd} failed", e)
                reply(request, Status.FAILED)
            }
            val handled = SystemClock.elapsedRealtime()
            if (reply != null) Watch.send(watch, reply)
            Log.d(
                TAG,
                "cmd ${request.cmd}: queued ${started - received} ms, handled ${handled - started} ms, " +
                    "sent ${SystemClock.elapsedRealtime() - handled} ms",
            )
        }
        return ReceiveResult.Ack
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID == Watch.APP_UUID) Watch.opened(watch)
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID == Watch.APP_UUID) Watch.closed(watch)
    }

    private suspend fun handle(request: Request, watch: WatchIdentifier): PebbleDictionary? =
        when (request.cmd) {
            Cmd.HELLO -> {
                Watch.opened(watch)
                Watch.setBudget(watch, request.budget)
                reply(request, Status.OK) + mapOf(
                    Keys.FLAGS to PebbleDictionaryItem.Int32(Capabilities.flags(this)),
                    Keys.SIZE to PebbleDictionaryItem.Int32(Settings.textSize(this, Settings.MENU_SIZE).wire),
                    Keys.BODY_SIZE to PebbleDictionaryItem.Int32(Settings.textSize(this, Settings.MESSAGE_SIZE).wire),
                ) + glance()
            }
            Cmd.FAVORITES -> list(request, watch, Manifest.permission.READ_CONTACTS) { contacts.favorites() }
            Cmd.LETTERS -> list(request, watch, Manifest.permission.READ_CONTACTS) { contacts.letters() }
            Cmd.CONTACTS -> list(request, watch, Manifest.permission.READ_CONTACTS) {
                contacts.byLetter(request.text.orEmpty())
            }
            Cmd.SEARCH -> {
                // The query goes back next to the page: the page leaves it room.
                val query = String(contacts.latin(request.text.orEmpty()).utf8Prefix(QUERY_BYTES))
                list(request, watch, Manifest.permission.READ_CONTACTS, reserve = QUERY_BYTES + 8) {
                    contacts.search(request.text.orEmpty())
                } + (Keys.TEXT to PebbleDictionaryItem.Text(query))
            }
            Cmd.PHOTO -> photo(request, watch)
            Cmd.REPLIES -> {
                val replies = Replies.load(this).map { Record(detail = it) }
                reply(request, Status.OK) + mapOf(
                    Keys.TOTAL to PebbleDictionaryItem.Int32(replies.size),
                    Keys.ITEMS to PebbleDictionaryItem.Bytes(page(replies, 0, Watch.budget(watch))),
                )
            }
            Cmd.NUMBERS -> list(request, watch, Manifest.permission.READ_CONTACTS) {
                contacts.numbers(request.id.toLong())
            }
            Cmd.CALLS -> list(request, watch, Manifest.permission.READ_CALL_LOG) { history.recent() }
            Cmd.THREADS -> list(request, watch, Manifest.permission.READ_SMS) { messages.threads() }
            Cmd.MESSAGES -> list(request, watch, Manifest.permission.READ_SMS) {
                messages.messages(request.id.toLong())
            }
            Cmd.MESSAGE_BODY -> messageBody(request, watch)
            Cmd.OPEN_THREAD -> openThread(request)
            Cmd.DIAL -> dial(request)
            Cmd.SEND_SMS -> sendSms(request)
            Cmd.HANG_UP -> {
                CallTracker.hangUp(this)
                null
            }
            Cmd.MUTE -> {
                CallTracker.toggleMute()
                null
            }
            Cmd.AUDIO_ROUTE -> {
                CallTracker.cycleAudioRoute()
                null
            }
            Cmd.CALL_STATE -> CallTracker.current.toDictionary(request.seq)
            else -> {
                Log.w(TAG, "Unknown command ${request.cmd}")
                null
            }
        }

    private fun list(
        request: Request,
        watch: WatchIdentifier,
        permission: String,
        reserve: Int = 0,
        records: () -> List<Record>,
    ): PebbleDictionary {
        if (!Capabilities.has(this, permission)) return reply(request, Status.NO_PERMISSION)
        val all = records()
        val items = page(all, request.offset, Watch.budget(watch) - reserve)
        val stamp = stamp(all)
        // The watch already shows this list: it keeps every page it loaded and the radio stays quiet.
        if (request.offset == 0 && request.flags == stamp) {
            Log.d(TAG, "cmd ${request.cmd}: page unchanged, not sent")
            return reply(request, Status.OK) + (Keys.FLAGS to PebbleDictionaryItem.Int32(stamp))
        }
        return reply(request, Status.OK) + mapOf(
            Keys.OFFSET to PebbleDictionaryItem.Int32(request.offset),
            Keys.TOTAL to PebbleDictionaryItem.Int32(all.size),
            Keys.ITEMS to PebbleDictionaryItem.Bytes(items),
            Keys.FLAGS to PebbleDictionaryItem.Int32(stamp),
        )
    }

    /** Fingerprint of a whole list, never 0, which means none. */
    private fun stamp(records: List<Record>): Int {
        val crc = CRC32()
        records.forEach { crc.update(it.encode()) }
        crc.update(records.size)
        return crc.value.toInt().takeIf { it != 0 } ?: 1
    }

    /**
     * One page of a contact photo or of a message picture: the watch asks for the next offset
     * until it has [Keys.TOTAL] bytes.
     */
    private fun photo(request: Request, watch: WatchIdentifier): PebbleDictionary {
        val picture = request.flags and PHOTO_PICTURE != 0
        val permission = if (picture) Manifest.permission.READ_SMS else Manifest.permission.READ_CONTACTS
        if (!Capabilities.has(this, permission)) return reply(request, Status.NO_PERMISSION)
        val width = request.flags and 0xfff
        val format = request.flags shr 12 and 0xf
        val bytes = if (picture) {
            photos.picture(request.id.toLong(), width, request.flags shr 16 and 0xfff, format)
        } else {
            photos.encoded(request.id.toLong(), request.address, width, format)
        } ?: return reply(request, Status.NOT_FOUND)
        val offset = request.offset.coerceIn(0, bytes.size)
        val end = minOf(bytes.size, offset + (Watch.budget(watch) - PHOTO_OVERHEAD).coerceAtLeast(1))
        return reply(request, Status.OK) + mapOf(
            Keys.OFFSET to PebbleDictionaryItem.Int32(offset),
            Keys.TOTAL to PebbleDictionaryItem.Int32(bytes.size),
            Keys.ITEMS to PebbleDictionaryItem.Bytes(bytes.copyOfRange(offset, end)),
        )
    }

    private fun messageBody(request: Request, watch: WatchIdentifier): PebbleDictionary {
        if (!Capabilities.has(this, Manifest.permission.READ_SMS)) return reply(request, Status.NO_PERMISSION)
        val body = messages.body(request.id.toLong()) ?: return reply(request, Status.NOT_FOUND)
        val text = String(body.utf8Prefix(Watch.budget(watch) - 16))
        return reply(request, Status.OK) + (Keys.TEXT to PebbleDictionaryItem.Text(text))
    }

    private fun dial(request: Request): PebbleDictionary {
        val number = request.address
        if (number.isNullOrBlank()) return reply(request, Status.FAILED)
        if (!Capabilities.has(this, Manifest.permission.CALL_PHONE)) return reply(request, Status.NO_PERMISSION)
        val name = if (Capabilities.has(this, Manifest.permission.READ_CONTACTS)) contacts.nameOf(number) else null
        val followed = CallTracker.dial(this, number, name)
        return reply(request, Status.OK) + (Keys.FLAGS to PebbleDictionaryItem.Int32(if (followed) DIAL_FOLLOWED else 0))
    }

    /**
     * Opens the conversation in the default SMS app. Android lets a background app start an
     * activity only once it is associated with the watch, as for call control: without that
     * the launch would be dropped without a word.
     */
    private fun openThread(request: Request): PebbleDictionary {
        val number = request.address
        if (number.isNullOrBlank()) return reply(request, Status.FAILED)
        val associated = getSystemService(CompanionDeviceManager::class.java).myAssociations.isNotEmpty()
        if (!associated) return reply(request, Status.NO_PERMISSION)
        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))
            .setPackage(Telephony.Sms.getDefaultSmsPackage(this))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            reply(request, Status.OK)
        } catch (e: ActivityNotFoundException) {
            reply(request, Status.FAILED)
        }
    }

    private suspend fun sendSms(request: Request): PebbleDictionary {
        val number = request.address
        val text = request.text
        if (number.isNullOrBlank() || text.isNullOrBlank()) return reply(request, Status.FAILED)
        // A sender that writes its name instead of a number takes no reply.
        if (number.any { it.isLetter() }) return reply(request, Status.FAILED)
        if (!Capabilities.has(this, Manifest.permission.SEND_SMS)) return reply(request, Status.NO_PERMISSION)
        // Id is the conversation answered, 0 for a new message.
        val subscription = Sims.forSms(this, request.id.toLong(), messages)
        return reply(request, messages.send(number, text, subscription))
    }

    /** The launcher event, absent when there is none to show. */
    private fun glance(): PebbleDictionary =
        Glance.latest(this, history, messages)
            ?.let { mapOf(Keys.ITEMS to PebbleDictionaryItem.Bytes(it.encode())) }
            .orEmpty()

    private fun reply(request: Request, status: Int): PebbleDictionary = mapOf(
        Keys.CMD to PebbleDictionaryItem.UInt8(request.cmd),
        Keys.SEQ to PebbleDictionaryItem.Int32(request.seq),
        Keys.STATUS to PebbleDictionaryItem.Int32(status),
    )

    private companion object {
        const val TAG = "WatchListener"
        // Flags of the DIAL reply: the watch will get CALL_STATE updates for this call.
        const val DIAL_FOLLOWED = 1 shl 0
        // Longest query echoed by SEARCH, the watch title buffer less its terminator.
        const val QUERY_BYTES = 47
        // PHOTO flags: a message picture, its height in bits 16-27.
        const val PHOTO_PICTURE = 1 shl 28
        // Room for the tuples sent along with a photo page.
        const val PHOTO_OVERHEAD = 48
    }
}
