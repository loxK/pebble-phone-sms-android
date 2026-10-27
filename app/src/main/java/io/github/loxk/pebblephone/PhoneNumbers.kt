// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.loxk.pebblephone

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.concurrent.ConcurrentHashMap

/**
 * One form per phone number, whatever its notation: the international one, read after the
 * country of the SIM. "12.34.56", "123456" and "+687 123456" all become "+687123456" on a
 * New Caledonian SIM. Short codes only lose their separators, sender names stay as they are.
 */
object PhoneNumbers {
    // Parsing a number takes a while, and the same addresses come back with every list.
    private val keys = ConcurrentHashMap<String, String>()

    fun country(context: Context): String {
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return ""
        return telephony.simCountryIso.ifEmpty { telephony.networkCountryIso }.uppercase()
    }

    fun key(number: String, country: String): String =
        keys.getOrPut("$country/$number") {
            if (number.any { it.isLetter() }) {
                number.trim()
            } else {
                PhoneNumberUtils.formatNumberToE164(number, country) ?: PhoneNumberUtils.normalizeNumber(number)
            }
        }

    fun key(context: Context, number: String): String = key(number, country(context))
}
