// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.loxk.pebblephone

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem

/**
 * The call shown on the watch. [CallService] reports it in full once the watch is associated
 * as a companion device. Without that association, a call placed from the watch is followed
 * through the phone state alone and can only be hung up.
 */
object CallTracker {
    data class Snapshot(
        val state: Int = CallStates.NONE,
        val name: String = "",
        val number: String = "",
        val connectedAt: Long = 0,
        val muted: Boolean = false,
        val route: Int = 0,
        val hangUpOnly: Boolean = false,
    ) {
        fun toDictionary(seq: Int? = null): PebbleDictionary = buildMap {
            put(Keys.CMD, PebbleDictionaryItem.UInt8(Cmd.CALL_STATE))
            if (seq != null) put(Keys.SEQ, PebbleDictionaryItem.Int32(seq))
            put(Keys.STATE, PebbleDictionaryItem.UInt8(state))
            put(Keys.NAME, PebbleDictionaryItem.Text(String(name.utf8Prefix(NAME_BYTES))))
            put(Keys.ADDRESS, PebbleDictionaryItem.Text(String(number.utf8Prefix(NUMBER_BYTES))))
            put(Keys.ELAPSED, PebbleDictionaryItem.Int32(elapsedSeconds()))
            put(Keys.FLAGS, PebbleDictionaryItem.Int32(flags()))
        }

        private fun elapsedSeconds() =
            if (connectedAt > 0) ((System.currentTimeMillis() - connectedAt) / 1000).toInt() else 0

        private fun flags(): Int {
            var flags = CallFlags.route(route)
            if (muted) flags = flags or CallFlags.MUTED
            if (hangUpOnly) flags = flags or CallFlags.HANG_UP_ONLY
            return flags
        }
    }

    // Watch buffers are 48 and 32 bytes with their terminator.
    private const val NAME_BYTES = 47
    private const val NUMBER_BYTES = 31

    @Volatile
    var current = Snapshot()
        private set

    @Volatile
    var service: CallService? = null

    @Volatile
    private var phoneStateCallback: TelephonyCallback? = null

    fun publish(snapshot: Snapshot) {
        if (snapshot == current) return
        current = snapshot
        Watch.broadcast(snapshot.toDictionary())
    }

    /**
     * Places the call. Throws SecurityException without CALL_PHONE. Returns whether the
     * watch will hear about the call afterwards: through [CallService], or through the phone
     * state, which only tells when the call is over.
     */
    fun dial(context: Context, number: String, name: String?): Boolean {
        val telecom = context.getSystemService(TelecomManager::class.java)
        val extras = Bundle()
        Sims.forCall(context)?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        telecom.placeCall(Uri.fromParts("tel", number, null), extras)
        if (Capabilities.callControl(context)) return true
        if (!followPhoneState(context)) return false
        publish(Snapshot(CallStates.DIALING, name.orEmpty(), number, hangUpOnly = true))
        return true
    }

    fun hangUp(context: Context) {
        val service = service
        if (service != null) {
            service.hangUp()
        } else if (Capabilities.has(context, Manifest.permission.ANSWER_PHONE_CALLS)) {
            @Suppress("DEPRECATION")
            context.getSystemService(TelecomManager::class.java).endCall()
        }
    }

    fun toggleMute() {
        service?.toggleMute()
    }

    fun cycleAudioRoute() {
        service?.cycleAudioRoute()
    }

    /**
     * Follows the call through the phone state, which does not tell dialing from connected:
     * the watch keeps showing the call as dialing until the phone goes idle again.
     */
    private fun followPhoneState(context: Context): Boolean {
        if (phoneStateCallback != null) return true
        if (!Capabilities.has(context, Manifest.permission.READ_PHONE_STATE)) return false
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val handler = Handler(Looper.getMainLooper())
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            // Registering reports the current state at once, before Telecom placed the call.
            private var first = true

            private val giveUp = Runnable { stop(this) }

            override fun onCallStateChanged(state: Int) {
                val initial = first
                first = false
                when (state) {
                    TelephonyManager.CALL_STATE_IDLE -> if (!initial) stop(this)
                    else -> handler.removeCallbacks(giveUp)
                }
            }

            private fun stop(callback: TelephonyCallback) {
                handler.removeCallbacks(giveUp)
                telephony.unregisterTelephonyCallback(callback)
                if (phoneStateCallback === callback) phoneStateCallback = null
                publish(Snapshot())
            }

            fun arm() {
                handler.postDelayed(giveUp, PLACE_TIMEOUT_MS)
            }
        }
        try {
            telephony.registerTelephonyCallback(context.mainExecutor, callback)
        } catch (e: SecurityException) {
            return false
        }
        phoneStateCallback = callback
        // A call Telecom never places leaves the phone idle: the watch is told after a while.
        callback.arm()
        return true
    }

    // Time for Telecom to take a placed call off hook.
    private const val PLACE_TIMEOUT_MS = 15_000L
}
