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
import android.content.pm.PackageManager
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
    private var directMessageNotificationJob: Job? = null
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
        createNotificationChannels()
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
        directMessageNotificationJob = session.incomingDirectMessages
            .onEach(::notifyDirectMessage)
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
        directMessageNotificationJob?.cancel()
        demoApplication.detachSession(session)
        serviceScope.launch {
            session.shutdown()
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    private fun createNotificationChannels() {
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
        notificationManager().createNotificationChannel(
            NotificationChannel(
                DIRECT_MESSAGE_CHANNEL_ID,
                "Direct messages",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alerts when another conference participant sends a direct message"
                enableVibration(true)
                setShowBadge(true)
            },
        )
    }

    private fun notifyDirectMessage(message: ReceivedText) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val openConversation = PendingIntent.getActivity(
            this,
            directMessageNotificationId(message),
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_OPEN_DIRECT_MESSAGE
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_SENDER_NODE_NUMBER, message.senderNodeNumber)
                putExtra(EXTRA_SENDER_NAME, message.sender)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, DIRECT_MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("New message from ${message.sender}")
            .setContentText(message.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.text))
            .setContentIntent(openConversation)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(DIRECT_MESSAGE_NOTIFICATION_GROUP)
            .build()
        notificationManager().notify(directMessageNotificationId(message), notification)
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
        private const val DIRECT_MESSAGE_CHANNEL_ID = "meshtastic_direct_messages_v1"
        private const val DIRECT_MESSAGE_NOTIFICATION_GROUP = "meshtastic_direct_messages"
        private const val NOTIFICATION_ID = 4101

        internal const val ACTION_OPEN_DIRECT_MESSAGE =
            "network.resilientcomms.meshdemo.action.OPEN_DIRECT_MESSAGE"
        internal const val EXTRA_SENDER_NODE_NUMBER = "sender_node_number"
        internal const val EXTRA_SENDER_NAME = "sender_name"

        fun connect(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RadioConnectionService::class.java).setAction(ACTION_CONNECT),
            )
        }

        private const val ACTION_CONNECT = "network.resilientcomms.meshdemo.action.CONNECT"
    }
}

private fun directMessageNotificationId(message: ReceivedText): Int =
    5_000 + ("${message.senderNodeNumber}:${message.packetId}".hashCode() and 0x0fff)
