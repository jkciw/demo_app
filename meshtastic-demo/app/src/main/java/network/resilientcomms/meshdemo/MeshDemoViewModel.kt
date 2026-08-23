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
        withSession { beginStationSelection() }
    }

    fun cancelStationChange() = withSession { cancelStationSelection() }

    fun selectRadio(address: String) = withSession { selectRadio(address) }
    fun scanRadios() = withSession { scanAgain() }
    fun changeRadio() = withSession { changeRadio() }
    fun cancelRadioChange() = withSession { cancelRadioChange() }
    fun permissionRequired() = demoApplication.markPermissionRequired()
    fun updateDraft(value: String) = withSession { updateDraft(value) }
    fun start() = withSession { start() }
    fun selectRecipient(id: String) = withSession { selectRecipient(id) }
    fun returnToContacts() = withSession { returnToContacts() }
    fun openOperator() = withSession { openOperator() }
    fun returnToOperator() = withSession { returnToOperator() }
    fun refreshPresence() = withSession { refreshPresence() }
    fun reconnectRadio() = withSession { reconnectRadio() }
    fun openBitcoin() = withSession { openBitcoin() }
    fun returnHome() = withSession { returnHome() }
    fun send() = withSession { send() }
    fun startOver() = withSession { startOver() }
    fun relayNextBitcoinTransaction() = withSession { relayNextBitcoinTransaction() }
    fun resetBitcoinQueue() = withSession { resetBitcoinQueue() }

    private fun withSession(action: MeshtasticSession.() -> Unit) =
        demoApplication.withRadioSession(action)
}
