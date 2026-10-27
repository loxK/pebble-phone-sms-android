// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.loxk.pebblephone

import android.app.Application
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker

class PhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Contacts and messages only go to the Pebble app the user picked on the setup screen,
        // never to whichever app claims to be one.
        DefaultPebbleAndroidAppPicker.getInstance(this).enableAutoSelect = false
        Watch.init(this)
    }
}
