/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package network.resilientcomms.meshdemo

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val viewModel: MeshDemoViewModel by viewModels()
    private var initialConnectAttempted = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (hasBluetoothPermissions()) viewModel.connect() else viewModel.permissionRequired()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MeshDemoApp(
                viewModel = viewModel,
                onSelectStation = ::selectStation,
                onRetryConnection = ::ensurePermissionsAndConnect,
                onOpenBluetoothSettings = ::openBluetoothSettings,
            )
        }
        handleNotificationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (!initialConnectAttempted && viewModel.hasSelectedStation) {
            initialConnectAttempted = true
            ensurePermissionsAndConnect()
        }
    }

    private fun selectStation(role: StationRole) {
        viewModel.selectStation(role)
        if (!viewModel.uiState.value.isConnected) ensurePermissionsAndConnect()
    }

    private fun ensurePermissionsAndConnect() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) viewModel.connect() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun openBluetoothSettings() {
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent?.action != RadioConnectionService.ACTION_OPEN_DIRECT_MESSAGE) return
        val senderNodeNumber = intent.getIntExtra(
            RadioConnectionService.EXTRA_SENDER_NODE_NUMBER,
            Int.MIN_VALUE,
        )
        val senderName = intent.getStringExtra(RadioConnectionService.EXTRA_SENDER_NAME).orEmpty()
        if (senderNodeNumber != Int.MIN_VALUE && senderName.isNotBlank()) {
            viewModel.openDirectMessage(senderNodeNumber, senderName)
        }
        intent.action = null
    }

    private fun requiredPermissions(): List<String> =
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            // Android 8–11 add nothing: bonded devices can be inspected and connected
            // without Location, and active discovery is deliberately skipped.
        }

    private fun hasBluetoothPermissions(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
}
