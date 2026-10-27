// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.loxk.pebblephone

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker
import io.rebble.pebblekit2.client.DefaultPebbleInfoRetriever
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.model.ConnectedWatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the setup screen shows, rebuilt by [MainActivity.render]. */
data class Setup(
    val summary: String = "",
    val pebbleDone: Boolean = false,
    val pebbleStatus: String = "",
    val canLink: Boolean = false,
    val linked: Boolean = false,
    val canOpen: Boolean = false,
    val permissionsDone: Boolean = false,
    val permissionsStatus: String = "",
    val callsDone: Boolean = false,
    val callsStatus: String = "",
)

/** Setup screen: Pebble app link, permissions, and the companion association for call controls. */
class MainActivity : ComponentActivity() {
    private val scope = MainScope()
    private val picker by lazy { DefaultPebbleAndroidAppPicker.getInstance(this) }
    private val watchInfo by lazy { DefaultPebbleInfoRetriever(this) }
    private var watchesJob: Job? = null

    private var linkedApp: String? = null
    private var eligibleApps: List<String> = emptyList()
    private var connectedWatches: List<ConnectedWatch> = emptyList()

    private var setup by mutableStateOf(Setup())
    private var choice by mutableStateOf<Choice?>(null)

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            // Once a permission was denied twice Android shows no dialog at all: the
            // app settings page is the only way left to grant it.
            val silenced = results.filterValues { !it }.keys.any { !shouldShowRequestPermissionRationale(it) }
            if (silenced) {
                Toast.makeText(this, R.string.permissions_settings, Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                )
            }
            refresh()
        }

    private val requestBluetooth =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pairWatch()
        }

    private val confirmAssociation =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhoneTheme {
                App(
                    choice = choice,
                    onChoiceClosed = { choice = null },
                    setup = setup,
                    actions = SetupActions(
                        link = ::pickPebbleApp,
                        open = ::openOnWatch,
                        grant = { requestPermissions.launch(Capabilities.missing(this).toTypedArray()) },
                        pair = ::pairWatch,
                    ),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        followWatches()
    }

    override fun onPause() {
        watchesJob?.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** The watch list only comes through once a Pebble app is linked; it updates as watches come and go. */
    private fun followWatches() {
        watchesJob?.cancel()
        watchesJob = scope.launch {
            linkedApp = picker.getCurrentlySelectedApp()
            if (linkedApp == null) return@launch
            watchInfo.getConnectedWatches().flowOn(Dispatchers.IO).collect {
                connectedWatches = it
                render()
            }
        }
    }

    private fun refresh() {
        scope.launch {
            eligibleApps = picker.getAllEligibleApps()
            linkedApp = picker.getCurrentlySelectedApp()
            if (linkedApp == null) connectedWatches = emptyList()
            render()
        }
    }

    private fun render() {
        val linked = linkedApp
        val missing = Capabilities.missing(this)
        val callControl = Capabilities.callControl(this)
        val required = listOf(linked != null, missing.isEmpty()).count { !it }
        setup = Setup(
            summary = when {
                required > 0 -> resources.getQuantityString(R.plurals.summary_todo, required, required)
                callControl -> getString(R.string.summary_ready)
                else -> getString(R.string.summary_optional)
            },
            pebbleDone = linked != null,
            pebbleStatus = when {
                linked != null && connectedWatches.isNotEmpty() ->
                    getString(R.string.pebble_linked_watch, appLabel(linked), connectedWatches.joinToString { it.name })
                linked != null -> getString(R.string.pebble_linked_no_watch, appLabel(linked))
                eligibleApps.isEmpty() -> getString(R.string.pebble_missing)
                else -> getString(R.string.pebble_not_linked)
            },
            canLink = eligibleApps.isNotEmpty(),
            linked = linked != null,
            canOpen = linked != null && connectedWatches.isNotEmpty(),
            permissionsDone = missing.isEmpty(),
            permissionsStatus = if (missing.isEmpty()) {
                getString(R.string.permissions_granted)
            } else {
                resources.getQuantityString(R.plurals.permissions_missing, missing.size, missing.size)
            },
            callsDone = callControl,
            callsStatus = if (callControl) {
                getString(R.string.calls_ready, associatedWatchName() ?: "Pebble")
            } else {
                getString(R.string.calls_not_paired)
            },
        )
    }

    private fun associatedWatchName(): String? {
        val manager = getSystemService(CompanionDeviceManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.myAssociations
                .firstOrNull { it.deviceProfile == AssociationRequest.DEVICE_PROFILE_WATCH }
                ?.displayName?.toString()
        } else {
            // Older releases only list addresses.
            null
        }
    }

    private fun openOnWatch() {
        scope.launch {
            val results = withContext(Dispatchers.IO) { Watch.openApp() }
            val message = when {
                results == null -> getString(R.string.pebble_test_unreachable)
                results.values.any { it == TransmissionResult.Success } ->
                    getString(R.string.pebble_test_ok, connectedWatches.joinToString { it.name })
                else -> getString(R.string.pebble_test_failed, results.values.joinToString())
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun pickPebbleApp() {
        val apps = picker.getAllEligibleApps()
        if (apps.size == 1) {
            selectPebbleApp(apps.single())
            return
        }
        choice = Choice(getString(R.string.pebble_pick), apps.map { appLabel(it) }) { selectPebbleApp(apps[it]) }
    }

    private fun selectPebbleApp(packageName: String) {
        scope.launch {
            picker.selectApp(packageName)
            refresh()
            followWatches()
        }
    }

    private fun appLabel(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    private fun pairWatch() {
        if (!Capabilities.has(this, Manifest.permission.BLUETOOTH_CONNECT)) {
            requestBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        val watches = bondedPebbles()
        when (watches.size) {
            0 -> Toast.makeText(this, R.string.calls_no_watch, Toast.LENGTH_LONG).show()
            1 -> associate(watches.single())
            else -> choice = Choice(getString(R.string.calls_pick_watch), watches.map { deviceName(it) }) {
                associate(watches[it])
            }
        }
    }

    @Suppress("MissingPermission")
    private fun bondedPebbles(): List<BluetoothDevice> =
        getSystemService(BluetoothManager::class.java).adapter?.bondedDevices.orEmpty()
            .filter { deviceName(it).startsWith("Pebble", ignoreCase = true) }

    @Suppress("MissingPermission")
    private fun deviceName(device: BluetoothDevice): String = device.name ?: device.address

    /**
     * Associates the watch with this app under the watch profile. Android then grants the
     * companion watch role, which carries MANAGE_ONGOING_CALLS and lets [CallService] bind.
     */
    private fun associate(device: BluetoothDevice) {
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(device.address).build())
            .addDeviceFilter(
                BluetoothLeDeviceFilter.Builder()
                    .setScanFilter(ScanFilter.Builder().setDeviceAddress(device.address).build())
                    .build(),
            )
            .setDeviceProfile(AssociationRequest.DEVICE_PROFILE_WATCH)
            .setSingleDevice(true)
            .build()
        val manager = getSystemService(CompanionDeviceManager::class.java)
        try {
            requestAssociation(manager, request)
        } catch (e: SecurityException) {
            associationFailed(e.message)
        }
    }

    private fun requestAssociation(manager: CompanionDeviceManager, request: AssociationRequest) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.associate(request, mainExecutor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) = confirm(intentSender)

                override fun onAssociationCreated(associationInfo: AssociationInfo) = refresh()

                override fun onFailure(error: CharSequence?) = associationFailed(error)
            })
        } else {
            @Suppress("DEPRECATION")
            manager.associate(request, object : CompanionDeviceManager.Callback() {
                @Deprecated("Deprecated in Java")
                override fun onDeviceFound(intentSender: IntentSender) = confirm(intentSender)

                override fun onFailure(error: CharSequence?) = associationFailed(error)
            }, null)
        }
    }

    private fun confirm(intentSender: IntentSender) {
        confirmAssociation.launch(IntentSenderRequest.Builder(intentSender).build())
    }

    private fun associationFailed(error: CharSequence?) {
        Toast.makeText(this, getString(R.string.calls_pair_failed, error ?: ""), Toast.LENGTH_LONG).show()
    }
}
