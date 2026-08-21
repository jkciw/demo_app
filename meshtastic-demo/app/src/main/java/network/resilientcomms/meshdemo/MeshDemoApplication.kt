/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package network.resilientcomms.meshdemo

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.meshtastic.sdk.storage.sqldelight.AndroidContextHolder

class MeshDemoApplication : Application() {
    private val sessionLock = Any()
    private val pendingActions = mutableListOf<MeshtasticSession.() -> Unit>()
    private var session: MeshtasticSession? = null
    private val _uiState = MutableStateFlow(
        MeshDemoState(statusText = "Starting radio service…"),
    )
    val uiState: StateFlow<MeshDemoState> = _uiState.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        AndroidContextHolder.context = applicationContext
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
            it.copy(
                radioStatus = RadioStatus.RECONNECTING,
                statusText = "Radio service restarting…",
            )
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
}
