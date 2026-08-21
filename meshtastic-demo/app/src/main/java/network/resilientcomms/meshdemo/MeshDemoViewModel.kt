/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package network.resilientcomms.meshdemo

import android.app.Application
import androidx.lifecycle.AndroidViewModel

class MeshDemoViewModel(application: Application) : AndroidViewModel(application) {
    private val demoApplication = application as MeshDemoApplication
    val uiState = demoApplication.uiState
    val hasSelectedStation: Boolean get() = demoApplication.selectedStationRole != null

    fun connect() = RadioConnectionService.connect(demoApplication)

    fun selectStation(role: StationRole) {
        if (uiState.value.isBitcoinRelayActive) return
        demoApplication.selectStationRole(role)
        withSession { selectStation(role) }
    }

    fun changeStation() {
        if (uiState.value.isBitcoinRelayActive) return
        demoApplication.clearStationRole()
        withSession { clearStation() }
    }

    fun selectRadio(address: String) = withSession { selectRadio(address) }
    fun changeRadio() = withSession { changeRadio() }
    fun permissionRequired() = demoApplication.markPermissionRequired()
    fun updateDraft(value: String) = withSession { updateDraft(value) }
    fun start() = withSession { start() }
    fun send() = withSession { send() }
    fun startOver() = withSession { startOver() }
    fun relayNextBitcoinTransaction() = withSession { relayNextBitcoinTransaction() }
    fun resetBitcoinQueue() = withSession { resetBitcoinQueue() }

    private fun withSession(action: MeshtasticSession.() -> Unit) =
        demoApplication.withRadioSession(action)
}
