// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.loxk.pebblephone

import android.Manifest
import android.content.Context
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/**
 * The SIM cards texts and calls from the watch go out from. With a single SIM, or none
 * readable, everything is left to the phone.
 */
object Sims {
    data class Sim(val id: Int, val label: String)

    /** Active SIM cards in slot order, empty without READ_PHONE_STATE. */
    fun active(context: Context): List<Sim> {
        if (!Capabilities.has(context, Manifest.permission.READ_PHONE_STATE)) return emptyList()
        val subscriptions = try {
            context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList
        } catch (e: SecurityException) {
            null
        }
        return subscriptions.orEmpty().sortedBy { it.simSlotIndex }.map {
            Sim(it.subscriptionId, "SIM ${it.simSlotIndex + 1} · ${it.displayName}")
        }
    }

    /** True when the phone has no default SIM for texts and asks every time. */
    fun smsAsks(context: Context): Boolean =
        SmsManager.getDefaultSmsSubscriptionId() !in active(context).map { it.id }

    /** True when the phone has no default SIM for calls and asks every time. */
    fun callAsks(context: Context): Boolean = try {
        context.getSystemService(TelecomManager::class.java).getDefaultOutgoingPhoneAccount("tel") == null
    } catch (e: SecurityException) {
        false
    }

    /**
     * Subscription for a text answering [thread], 0 for a new message, or null to let the phone
     * pick. A phone that asks every time would show its picker on the phone while the watch
     * waits: the first SIM goes out instead.
     */
    fun forSms(context: Context, thread: Long, messages: Messages): Int? {
        val ids = active(context).map { it.id }
        if (ids.size < 2) return null
        val choice = Settings.sim(context, Settings.SMS_SIM)
        val chosen = when (choice) {
            Settings.SIM_THREAD -> if (thread > 0) messages.subscriptionOf(thread) else null
            Settings.SIM_PHONE -> null
            else -> choice.toIntOrNull()
        }
        if (chosen in ids) return chosen
        val default = SmsManager.getDefaultSmsSubscriptionId()
        return if (default in ids) default else ids.first()
    }

    /** Account to place a call from, or null to let the phone pick, as [forSms] does. */
    fun forCall(context: Context): PhoneAccountHandle? {
        val ids = active(context).map { it.id }
        if (ids.size < 2) return null
        val chosen = Settings.sim(context, Settings.CALL_SIM).toIntOrNull()?.takeIf { it in ids }
        val wanted = chosen ?: if (callAsks(context)) ids.first() else return null
        val telecom = context.getSystemService(TelecomManager::class.java)
        val telephony = context.getSystemService(TelephonyManager::class.java)
        return try {
            telecom.callCapablePhoneAccounts.firstOrNull { telephony.getSubscriptionId(it) == wanted }
        } catch (e: SecurityException) {
            null
        }
    }
}
