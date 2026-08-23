/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package network.resilientcomms.meshdemo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Owns the sole Meshtastic RadioClient for the lifetime of the foreground service.
 * Activity and ViewModel recreation must never tear down the BLE connection.
 */
class RadioConnectionService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    private lateinit var demoApplication: MeshDemoApplication
    private lateinit var session: MeshtasticSession
    private var notificationJob: Job? = null
    private var bondReceiverRegistered = false
    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
            session.onBondStateChanged(
                address = device.address,
                previousState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR),
                currentState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR),
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        demoApplication = application as MeshDemoApplication
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Preparing the radio connection…"))

        session = MeshtasticSession(application, serviceScope)
        ContextCompat.registerReceiver(
            this,
            bondStateReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        bondReceiverRegistered = true
        demoApplication.attachSession(session)
        notificationJob = session.state
            .onEach { state ->
                demoApplication.publishState(state)
                notificationManager().notify(NOTIFICATION_ID, notification(state.statusText))
            }
            .launchIn(serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_CONNECT) session.connect()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (bondReceiverRegistered) {
            unregisterReceiver(bondStateReceiver)
            bondReceiverRegistered = false
        }
        notificationJob?.cancel()
        demoApplication.detachSession(session)
        serviceScope.launch {
            session.shutdown()
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager().createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Meshtastic radio connection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps the conference radio connected over Bluetooth"
                setShowBadge(false)
            },
        )
    }

    private fun notification(status: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("${demoApplication.selectedStationRole?.stationName ?: "Meshtastic"} radio link")
            .setContentText(status)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "meshtastic_radio_connection"
        private const val NOTIFICATION_ID = 4101

        fun connect(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RadioConnectionService::class.java).setAction(ACTION_CONNECT),
            )
        }

        private const val ACTION_CONNECT = "network.resilientcomms.meshdemo.action.CONNECT"
    }
}
