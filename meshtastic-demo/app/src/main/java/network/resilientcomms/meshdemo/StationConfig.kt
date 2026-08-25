/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package network.resilientcomms.meshdemo

enum class StationRole(
    val storageId: String,
    val stationName: String,
    val radioFallbackName: String,
    val transactionAssetName: String,
    val presenceSlot: Int,
) {
    ALPHA(
        storageId = "alpha",
        stationName = "Alice",
        radioFallbackName = "Alice's radio",
        transactionAssetName = "regtest_transactions_alpha.txt",
        presenceSlot = 0,
    ),
    BRAVO(
        storageId = "bravo",
        stationName = "Bob",
        radioFallbackName = "Bob's radio",
        transactionAssetName = "regtest_transactions_bravo.txt",
        presenceSlot = 1,
    ),
    CHARLIE(
        storageId = "charlie",
        stationName = "Charlie",
        radioFallbackName = "Charlie's radio",
        transactionAssetName = "regtest_transactions_charlie.txt",
        presenceSlot = 2,
    ),
    DANA(
        storageId = "dana",
        stationName = "Dana",
        radioFallbackName = "Dana's radio",
        transactionAssetName = "regtest_transactions_dana.txt",
        presenceSlot = 3,
    ),
    ;

    companion object {
        fun fromStorageId(value: String?): StationRole? = entries.firstOrNull { it.storageId == value }
    }
}
