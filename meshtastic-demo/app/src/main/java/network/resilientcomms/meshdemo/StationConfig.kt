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
        stationName = "Alice",
        radioFallbackName = "Alice's radio",
        transactionAssetName = "regtest_transactions_alpha.txt",
    ),
    BRAVO(
        storageId = "bravo",
        stationName = "Bob",
        radioFallbackName = "Bob's radio",
        transactionAssetName = "regtest_transactions_bravo.txt",
    ),
    ;

    companion object {
        fun fromStorageId(value: String?): StationRole? = entries.firstOrNull { it.storageId == value }
    }
}
