// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.loxk.pebblephone

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import androidx.core.content.ContextCompat

object Capabilities {
    const val CONTACTS = 1 shl 0
    const val CALL_LOG = 1 shl 1
    const val DIAL = 1 shl 2
    const val READ_SMS = 1 shl 3
    const val SEND_SMS = 1 shl 4
    const val CALL_CONTROL = 1 shl 5

    /** Permissions the setup screen asks for. */
    val RUNTIME_PERMISSIONS = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
    )

    fun has(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun missing(context: Context) = RUNTIME_PERMISSIONS.filterNot { has(context, it) }

    /**
     * Granted along with the companion watch role, once the watch is associated. It is an app-op
     * permission: the permission itself stays denied and Telecom checks the operation instead.
     */
    fun callControl(context: Context): Boolean {
        val op = AppOpsManager.permissionToOp(Manifest.permission.MANAGE_ONGOING_CALLS) ?: return false
        val appOps = context.getSystemService(AppOpsManager::class.java)
        return appOps.checkOpNoThrow(op, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    fun flags(context: Context): Int {
        var flags = 0
        if (has(context, Manifest.permission.READ_CONTACTS)) flags = flags or CONTACTS
        if (has(context, Manifest.permission.READ_CALL_LOG)) flags = flags or CALL_LOG
        if (has(context, Manifest.permission.CALL_PHONE)) flags = flags or DIAL
        if (has(context, Manifest.permission.READ_SMS)) flags = flags or READ_SMS
        if (has(context, Manifest.permission.SEND_SMS)) flags = flags or SEND_SMS
        if (callControl(context)) flags = flags or CALL_CONTROL
        return flags
    }
}
