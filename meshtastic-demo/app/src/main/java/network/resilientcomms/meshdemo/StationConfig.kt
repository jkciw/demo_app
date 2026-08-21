/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package network.resilientcomms.meshdemo

enum class StationRole(
    val storageId: String,
    val stationName: String,
    val radioFallbackName: String,
    val transactionAssetName: String,
) {
    ALPHA(
        storageId = "alpha",
        stationName = "MESH-ALPHA",
        radioFallbackName = "Alpha radio",
        transactionAssetName = "regtest_transactions_alpha.txt",
    ),
    BRAVO(
        storageId = "bravo",
        stationName = "MESH-BRAVO",
        radioFallbackName = "Bravo radio",
        transactionAssetName = "regtest_transactions_bravo.txt",
    ),
    ;

    companion object {
        fun fromStorageId(value: String?): StationRole? = entries.firstOrNull { it.storageId == value }
    }
}
