/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package network.resilientcomms.meshdemo

data object StationConfig {
    val stationName: String = BuildConfig.STATION_NAME
    val radioName: String = BuildConfig.RADIO_NAME
    val bleAddress: String = BuildConfig.MESHTASTIC_BLE_ADDRESS.trim()
    val bitcoinQueueId: String = BuildConfig.BITCOIN_QUEUE_ID
}
