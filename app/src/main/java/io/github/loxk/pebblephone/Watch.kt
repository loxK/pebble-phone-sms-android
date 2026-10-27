// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.loxk.pebblephone

import android.content.Context
import android.util.Log
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.client.PebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Outgoing side of the link with the watchapp. */
object Watch {
    val APP_UUID: UUID = UUID.fromString("d24d4185-63c5-417a-951a-77d06d5e9720")

    private const val TAG = "Watch"
    // Fits the smallest inbox a watch opens, used until its HELLO tells better.
    private const val DEFAULT_BUDGET = 900

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var sender: PebbleSender
    // AppMessage carries one message at a time per watch.
    private val mutex = Mutex()
    private val budgets = ConcurrentHashMap<WatchIdentifier, Int>()
    private val open = ConcurrentHashMap.newKeySet<WatchIdentifier>()

    fun init(context: Context) {
        sender = DefaultPebbleSender(context.applicationContext)
    }

    fun opened(watch: WatchIdentifier) {
        open += watch
    }

    fun closed(watch: WatchIdentifier) {
        open -= watch
        budgets -= watch
    }

    fun budget(watch: WatchIdentifier) = budgets[watch] ?: DEFAULT_BUDGET

    fun setBudget(watch: WatchIdentifier, budget: Int) {
        if (budget > 0) budgets[watch] = budget
    }

    suspend fun send(watch: WatchIdentifier, data: PebbleDictionary) {
        val result = mutex.withLock { sender.sendDataToPebble(APP_UUID, data, listOf(watch)) }
        val outcome = result?.get(watch)
        if (outcome != TransmissionResult.Success) {
            Log.w(TAG, "Message to $watch not delivered: ${outcome ?: "Pebble app unreachable"}")
        }
    }

    /** Starts the watchapp on every connected watch; null when the Pebble app is unreachable. */
    suspend fun openApp(): Map<WatchIdentifier, TransmissionResult>? = sender.startAppOnTheWatch(APP_UUID)

    /** Sends to every watch where the app is open. */
    fun broadcast(data: PebbleDictionary) {
        scope.launch {
            open.forEach { send(it, data) }
        }
    }
}
