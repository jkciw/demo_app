/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package network.resilientcomms.meshdemo

import android.app.Application
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.meshtastic.sdk.storage.sqldelight.AndroidContextHolder

class MeshDemoApplication : Application() {
    private val stationPreferences by lazy {
        getSharedPreferences(STATION_PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    private val sessionLock = Any()
    private val pendingActions = mutableListOf<MeshtasticSession.() -> Unit>()
    private var session: MeshtasticSession? = null
    private val _uiState = MutableStateFlow(
        MeshDemoState(statusText = "Choose this phone's station"),
    )
    val uiState: StateFlow<MeshDemoState> = _uiState.asStateFlow()
    val selectedStationRole: StationRole?
        get() = StationRole.fromStorageId(
            stationPreferences.getString(SELECTED_STATION_KEY, null),
        )

    override fun onCreate() {
        super.onCreate()
        AndroidContextHolder.context = applicationContext
        val role = selectedStationRole
        _uiState.value = MeshDemoState(
            stationRole = role,
            statusText = if (role == null) "Choose this phone's station" else "Starting radio service…",
        )
    }

    internal fun attachSession(newSession: MeshtasticSession) {
        val actions = synchronized(sessionLock) {
            session = newSession
            pendingActions.toList().also { pendingActions.clear() }
        }
        actions.forEach { action -> newSession.action() }
    }

    internal fun detachSession(oldSession: MeshtasticSession) {
        synchronized(sessionLock) {
            if (session === oldSession) session = null
        }
        _uiState.update {
            if (selectedStationRole == null) {
                it.copy(radioStatus = RadioStatus.DISCONNECTED, statusText = "Choose this phone's station")
            } else {
                it.copy(radioStatus = RadioStatus.RECONNECTING, statusText = "Radio service restarting…")
            }
        }
    }

    internal fun publishState(state: MeshDemoState) {
        _uiState.value = state
    }

    fun withRadioSession(action: MeshtasticSession.() -> Unit) {
        val activeSession = synchronized(sessionLock) {
            session ?: run {
                pendingActions += action
                null
            }
        }
        activeSession?.action()
    }

    fun markPermissionRequired() {
        _uiState.update {
            it.copy(
                radioStatus = RadioStatus.PERMISSION_REQUIRED,
                statusText = "Bluetooth permission is required",
            )
        }
    }

    fun selectStationRole(role: StationRole) {
        stationPreferences.edit().putString(SELECTED_STATION_KEY, role.storageId).apply()
        _uiState.update {
            it.copy(
                stationRole = role,
                isChoosingStation = false,
                statusText = "${role.stationName} selected · ready to connect",
            )
        }
    }

    fun clearStationRole() {
        stationPreferences.edit().remove(SELECTED_STATION_KEY).apply()
        _uiState.update {
            it.copy(
                stationRole = null,
                isChoosingStation = false,
                step = DemoStep.HOME,
                statusText = "Choose this phone's station",
                bitcoinQueueIndex = 0,
                bitcoinQueueTotal = 0,
                bitcoinRelayProgress = BitcoinRelayProgress.IDLE,
                bitcoinStatusText = "Select a station queue",
                bitcoinSession = null,
                bitcoinCurrentChunk = 0,
                bitcoinTotalChunks = 0,
                bitcoinTxid = null,
                bitcoinBlockHeight = null,
            )
        }
    }

    private companion object {
        const val STATION_PREFERENCES_NAME = "conference_station_setup"
        const val SELECTED_STATION_KEY = "selected_station"
    }
}
