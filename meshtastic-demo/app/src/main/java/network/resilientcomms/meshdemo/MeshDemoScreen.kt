/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package network.resilientcomms.meshdemo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Navy = Color(0xFF101827)
private val Panel = Color(0xFF18243A)
private val Cyan = Color(0xFF43D9C3)
private val Muted = Color(0xFFAEBBD0)
private val Danger = Color(0xFFFF8C82)

@Composable
fun MeshDemoApp(
    viewModel: MeshDemoViewModel,
    onRetryConnection: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    MaterialTheme {
        Surface(color = Navy, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 28.dp),
            ) {
                Header()
                Spacer(Modifier.height(26.dp))
                when (state.step) {
                    DemoStep.HOME -> HomeScreen(
                        state = state,
                        onStart = viewModel::start,
                        onRetry = onRetryConnection,
                        onRelayBitcoin = viewModel::relayNextBitcoinTransaction,
                        onResetBitcoin = viewModel::resetBitcoinQueue,
                    )
                    DemoStep.COMPOSE -> ComposeScreen(state, viewModel::updateDraft, viewModel::send)
                    DemoStep.RESULT -> ResultScreen(state, viewModel::startOver)
                }
                if (state.received.isNotEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    Inbox(state.received)
                }
            }
        }
    }
}

@Composable
private fun Header() {
    Text("RESILIENT COMMS LAB", color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text("MESHTASTIC", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black)
    Text(StationConfig.stationName, color = Muted, fontSize = 15.sp)
}

@Composable
private fun HomeScreen(
    state: MeshDemoState,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onRelayBitcoin: () -> Unit,
    onResetBitcoin: () -> Unit,
) {
    StatusCard(state)
    Spacer(Modifier.height(20.dp))
    BitcoinRelayCard(state, onRelayBitcoin, onResetBitcoin)
    Spacer(Modifier.height(36.dp))
    Text(
        "Communicate without Internet",
        color = Color.White,
        fontSize = 25.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        "Your phone talks to ${StationConfig.radioName} over Bluetooth. The radio carries messages across the LoRa mesh.",
        color = Muted,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )
    Spacer(Modifier.height(36.dp))
    if (state.isConnected) {
        PrimaryButton("START", onStart, enabled = true)
    } else {
        PrimaryButton("RETRY CONNECTION", onRetry, enabled = state.radioStatus != RadioStatus.NOT_PROVISIONED)
        if (state.radioStatus == RadioStatus.NOT_PROVISIONED) {
            Spacer(Modifier.height(12.dp))
            Text("This station build needs its fixed BLE address before installation.", color = Danger)
        }
    }
}

@Composable
private fun BitcoinRelayCard(
    state: MeshDemoState,
    onRelay: () -> Unit,
    onReset: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("BITCOIN / REGTEST", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            Text(
                if (state.bitcoinRemaining > 0) {
                    "Signed transaction ${state.bitcoinQueueIndex + 1} of ${state.bitcoinQueueTotal}"
                } else {
                    "Transaction queue complete"
                },
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${state.bitcoinRemaining} remaining · laptop gateway !2303a141",
                color = Muted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                state.bitcoinStatusText,
                color = bitcoinStatusColor(state.bitcoinRelayProgress),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (state.bitcoinTotalChunks > 0) {
                Spacer(Modifier.height(5.dp))
                Text(
                    "Chunk ${state.bitcoinCurrentChunk} / ${state.bitcoinTotalChunks}",
                    color = Muted,
                    fontSize = 12.sp,
                )
            }
            state.bitcoinTxid?.let { txid ->
                Spacer(Modifier.height(10.dp))
                Text("TXID", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(txid, color = Color.White, fontSize = 11.sp, lineHeight = 16.sp)
            }
            state.bitcoinBlockHeight?.let { height ->
                Spacer(Modifier.height(6.dp))
                Text("CONFIRMED · BLOCK $height", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(18.dp))
            if (state.bitcoinRemaining > 0) {
                val label = when {
                    state.isBitcoinRelayActive -> "RELAY IN PROGRESS"
                    state.bitcoinRelayProgress == BitcoinRelayProgress.FAILED -> "RETRY SIGNED TRANSACTION"
                    else -> "RELAY NEXT SIGNED TRANSACTION"
                }
                PrimaryButton(label, onRelay, state.canRelayBitcoin)
            } else {
                PrimaryButton("RESET DEVELOPMENT QUEUE", onReset, !state.isBitcoinRelayActive)
            }
        }
    }
}

@Composable
private fun StatusCard(state: MeshDemoState) {
    val healthy = state.isConnected
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(12.dp)
                    .height(12.dp)
                    .background(if (healthy) Cyan else Danger, RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(state.statusText, color = Color.White, fontWeight = FontWeight.Bold)
                Text("Radio: ${StationConfig.radioName}  •  Primary channel", color = Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ComposeScreen(
    state: MeshDemoState,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
) {
    Text("Send a message", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Text("Everyone on the configured primary channel can receive it.", color = Muted, lineHeight = 22.sp)
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = state.draft,
        onValueChange = onDraftChanged,
        label = { Text("Message") },
        supportingText = { Text("${state.draftBytes} / $MAX_TEXT_BYTES bytes") },
        minLines = 4,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = Cyan,
            focusedBorderColor = Cyan,
            unfocusedBorderColor = Muted,
            focusedLabelColor = Cyan,
            unfocusedLabelColor = Muted,
            focusedSupportingTextColor = Muted,
            unfocusedSupportingTextColor = Muted,
            focusedContainerColor = Panel,
            unfocusedContainerColor = Panel,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(24.dp))
    PrimaryButton("SEND OVER MESH", onSend, state.canSend)
}

@Composable
private fun ResultScreen(state: MeshDemoState, onStartOver: () -> Unit) {
    Text("Message journey", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(24.dp))
    JourneyNode("THIS PHONE", StationConfig.stationName)
    JourneyArrow("Bluetooth")
    JourneyNode("LILYGO RADIO", StationConfig.radioName)
    JourneyArrow("LoRa / primary channel")
    JourneyNode("MESHTASTIC MESH", "Nearby configured nodes")
    Spacer(Modifier.height(24.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(state.sendStatusText.ifBlank { "Preparing message…" }, color = statusColor(state), fontWeight = FontWeight.Bold)
            state.lastPacketId?.let { Text("Packet $it", color = Muted, fontSize = 13.sp) }
            if (state.sendProgress == SendProgress.RELAYED_BY_MESH) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "A relay confirms that at least one mesh node retransmitted it; it does not identify a particular recipient.",
                    color = Muted,
                    fontSize = 13.sp,
                )
            }
        }
    }
    Spacer(Modifier.height(28.dp))
    PrimaryButton("START OVER", onStartOver, state.sendProgress !in setOf(SendProgress.QUEUED, SendProgress.SENT_TO_RADIO))
}

@Composable
private fun JourneyNode(title: String, detail: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Text(detail, color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun JourneyArrow(label: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("↓", color = Cyan, fontSize = 22.sp, textAlign = TextAlign.Center)
        Text(label, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun Inbox(messages: List<ReceivedText>) {
    Text("RECEIVED ON THIS PHONE", color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(10.dp))
    messages.forEach { message ->
        Card(
            colors = CardDefaults.cardColors(containerColor = Panel),
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(message.sender, color = Cyan, fontWeight = FontWeight.Bold)
                Text(message.text, color = Color.White, fontSize = 17.sp)
                Text("Packet ${message.packetId}", color = Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit, enabled: Boolean) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Navy),
        modifier = Modifier.fillMaxWidth().height(54.dp),
    ) {
        Text(label, fontWeight = FontWeight.Black)
    }
}

private fun statusColor(state: MeshDemoState): Color =
    if (state.sendProgress == SendProgress.FAILED) Danger else Cyan

private fun bitcoinStatusColor(progress: BitcoinRelayProgress): Color =
    if (progress == BitcoinRelayProgress.FAILED) Danger else Cyan
