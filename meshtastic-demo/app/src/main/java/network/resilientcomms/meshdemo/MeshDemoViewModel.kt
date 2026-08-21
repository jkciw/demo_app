/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package network.resilientcomms.meshdemo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

class MeshDemoViewModel(application: Application) : AndroidViewModel(application) {
    private val session = MeshtasticSession(application, viewModelScope)
    val uiState = session.state

    fun connect() = session.connect()
    fun permissionRequired() = session.permissionRequired()
    fun updateDraft(value: String) = session.updateDraft(value)
    fun start() = session.start()
    fun send() = session.send()
    fun startOver() = session.startOver()
    fun relayNextBitcoinTransaction() = session.relayNextBitcoinTransaction()
    fun resetBitcoinQueue() = session.resetBitcoinQueue()

    override fun onCleared() {
        session.close()
        super.onCleared()
    }
}
