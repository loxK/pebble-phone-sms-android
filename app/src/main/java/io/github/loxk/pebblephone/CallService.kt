// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.loxk.pebblephone

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService

/**
 * Bound by Telecom for every call once the watch is associated as a companion device
 * (MANAGE_ONGOING_CALLS). It shows no UI: the phone's own dialer keeps doing that.
 */
class CallService : InCallService() {
    // Written by Telecom on the main thread, read by watch requests on another.
    @Volatile
    private var call: Call? = null

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = follow()

        override fun onDetailsChanged(call: Call, details: Call.Details) = follow()
    }

    override fun onCreate() {
        super.onCreate()
        CallTracker.service = this
    }

    override fun onDestroy() {
        if (CallTracker.service === this) CallTracker.service = null
        CallTracker.publish(CallTracker.Snapshot())
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        call.registerCallback(callback)
        follow()
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        follow()
    }

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) = publish()

    fun hangUp() {
        call?.disconnect()
    }

    fun toggleMute() {
        setMuted(!(audioState()?.isMuted ?: false))
    }

    fun cycleAudioRoute() {
        val state = audioState() ?: return
        val routes = ROUTE_ORDER.filter { state.supportedRouteMask and it != 0 }
        if (routes.size < 2) return
        @Suppress("DEPRECATION")
        setAudioRoute(routes[(routes.indexOf(state.route) + 1) % routes.size])
    }

    /**
     * Shows the call the phone's own screen puts first, so that hanging up from the watch ends
     * that one: a call ringing, then one being set up, then the one in progress, then one on
     * hold. A second call dialled while the first waits on hold takes over, and so does a
     * call ringing during another.
     */
    private fun follow() {
        call = calls.minByOrNull { rank(it.details.state) }
        if (call == null) CallTracker.publish(CallTracker.Snapshot()) else publish()
    }

    private fun rank(state: Int) = when (state) {
        Call.STATE_RINGING, Call.STATE_SIMULATED_RINGING -> 0
        Call.STATE_NEW, Call.STATE_SELECT_PHONE_ACCOUNT, Call.STATE_DIALING, Call.STATE_CONNECTING,
        Call.STATE_PULLING_CALL,
        -> 1
        Call.STATE_ACTIVE -> 2
        Call.STATE_HOLDING -> 3
        // Screened by the phone before it rings: nothing to answer yet.
        Call.STATE_AUDIO_PROCESSING -> 4
        else -> 5
    }

    private fun publish() {
        val call = call ?: return
        val details = call.details
        val audio = audioState()
        CallTracker.publish(
            CallTracker.Snapshot(
                state = when (details.state) {
                    Call.STATE_RINGING, Call.STATE_SIMULATED_RINGING -> CallStates.RINGING
                    Call.STATE_ACTIVE -> CallStates.ACTIVE
                    Call.STATE_HOLDING -> CallStates.HOLDING
                    Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> CallStates.ENDED
                    else -> CallStates.DIALING
                },
                name = details.contactDisplayName ?: details.callerDisplayName.orEmpty(),
                number = details.handle?.schemeSpecificPart.orEmpty(),
                connectedAt = details.connectTimeMillis,
                muted = audio?.isMuted ?: false,
                route = audio?.route ?: 0,
            ),
        )
    }

    @Suppress("DEPRECATION")
    private fun audioState(): CallAudioState? = callAudioState

    private companion object {
        val ROUTE_ORDER = listOf(
            CallAudioState.ROUTE_EARPIECE,
            CallAudioState.ROUTE_WIRED_HEADSET,
            CallAudioState.ROUTE_SPEAKER,
            CallAudioState.ROUTE_BLUETOOTH,
        )
    }
}
