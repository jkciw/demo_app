/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package network.resilientcomms.meshdemo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Navy = Color(0xFF101827)
private val Panel = Color(0xFF18243A)
private val Cyan = Color(0xFF43D9C3)
private val Muted = Color(0xFFAEBBD0)
private val Danger = Color(0xFFFF8C82)
private val BitcoinOrange = Color(0xFFF7931A)

@Composable
fun MeshDemoApp(
    viewModel: MeshDemoViewModel,
    onSelectStation: (StationRole) -> Unit,
    onRetryConnection: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    BackHandler(enabled = state.isChoosingStation || state.step != DemoStep.HOME) {
        when {
            state.isChoosingStation -> viewModel.cancelStationChange()
            state.step == DemoStep.RADIO_SELECTION -> viewModel.cancelRadioChange()
            state.step == DemoStep.RADIO_RECONNECT -> viewModel.returnToOperator()
            state.step == DemoStep.COMPOSE || state.step == DemoStep.RESULT -> viewModel.returnToContacts()
            else -> viewModel.returnHome()
        }
    }
    MaterialTheme {
        Surface(color = Navy, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 28.dp),
            ) {
                if (state.stationRole == null || state.isChoosingStation) {
                    if (state.stationRole == null) {
                        Header(state)
                        Spacer(Modifier.height(26.dp))
                    } else {
                        SubpageTopBar(state, viewModel::cancelStationChange)
                        Spacer(Modifier.height(22.dp))
                    }
                    StationSelectionScreen(state.stationRole, onSelectStation)
                    return@Column
                }
                when (state.step) {
                    DemoStep.HOME -> HomeScreen(
                        state = state,
                        onStart = viewModel::start,
                        onOpenBitcoin = viewModel::openBitcoin,
                        onRetry = onRetryConnection,
                        onScanAgain = viewModel::scanRadios,
                        onSelectRadio = viewModel::selectRadio,
                        onOpenOperator = viewModel::openOperator,
                        onOpenBluetoothSettings = onOpenBluetoothSettings,
                    )
                    DemoStep.CONTACTS -> {
                        SubpageTopBar(state, viewModel::returnHome)
                        Spacer(Modifier.height(22.dp))
                        ContactsScreen(state, viewModel::selectRecipient)
                    }
                    DemoStep.COMPOSE -> {
                        SubpageTopBar(state, viewModel::returnToContacts)
                        Spacer(Modifier.height(22.dp))
                        ComposeScreen(state, viewModel::updateDraft, viewModel::send)
                    }
                    DemoStep.BITCOIN -> {
                        SubpageTopBar(state, viewModel::returnHome)
                        Spacer(Modifier.height(22.dp))
                        BitcoinScreen(
                            state = state,
                            onRelay = viewModel::relayNextBitcoinTransaction,
                            onReset = viewModel::resetBitcoinQueue,
                            onBack = viewModel::returnHome,
                        )
                    }
                    DemoStep.RESULT -> {
                        SubpageTopBar(state, viewModel::returnToContacts)
                        Spacer(Modifier.height(22.dp))
                        ResultScreen(
                            state = state,
                            onContinue = viewModel::continueConversation,
                            onChooseContact = viewModel::returnToContacts,
                            onHome = viewModel::startOver,
                        )
                    }
                    DemoStep.OPERATOR -> {
                        SubpageTopBar(state, viewModel::returnHome)
                        Spacer(Modifier.height(22.dp))
                        OperatorScreen(
                            state = state,
                            onRefreshPresence = viewModel::refreshPresence,
                            onReconnect = viewModel::reconnectRadio,
                            onChangeRadio = viewModel::changeRadio,
                            onChangeStation = viewModel::changeStation,
                            onResetBitcoinQueue = viewModel::resetBitcoinQueue,
                        )
                    }
                    DemoStep.RADIO_SELECTION -> {
                        SubpageTopBar(state, viewModel::cancelRadioChange)
                        Spacer(Modifier.height(22.dp))
                        RadioSelectionScreen(
                            state = state,
                            onScanAgain = viewModel::scanRadios,
                            onSelectRadio = viewModel::selectRadio,
                        )
                    }
                    DemoStep.RADIO_RECONNECT -> {
                        SubpageTopBar(state, viewModel::returnToOperator)
                        Spacer(Modifier.height(22.dp))
                        RadioReconnectScreen(
                            state = state,
                            onRetry = viewModel::reconnectRadio,
                            onDone = viewModel::returnToOperator,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(state: MeshDemoState) {
    Text("RESILIENT COMMS LAB", color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text("MESHTASTIC", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black)
    Text(state.stationName, color = Muted, fontSize = 15.sp)
}

@Composable
private fun SubpageTopBar(state: MeshDemoState, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onBack,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
        ) {
            Text("‹  BACK", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Black)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("MESHTASTIC", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
            Text(state.stationName, color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun StationSelectionScreen(
    currentRole: StationRole?,
    onSelectStation: (StationRole) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("THIS PHONE", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            Text("Choose a station", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "The choice sets this phone's identity and its independent signed-transaction queue.",
                color = Muted,
                lineHeight = 21.sp,
            )
            Spacer(Modifier.height(20.dp))
            StationRole.entries.forEach { role ->
                OutlinedButton(
                    onClick = { onSelectStation(role) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(role.stationName, fontWeight = FontWeight.Black)
                        Text(
                            if (role == currentRole) {
                                "Current station · ${role.stationName.lowercase().replaceFirstChar(Char::uppercase)} transaction queue"
                            } else {
                                "${role.stationName.lowercase().replaceFirstChar(Char::uppercase)} transaction queue"
                            },
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    state: MeshDemoState,
    onStart: () -> Unit,
    onOpenBitcoin: () -> Unit,
    onRetry: () -> Unit,
    onScanAgain: () -> Unit,
    onSelectRadio: (String) -> Unit,
    onOpenOperator: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
) {
    LandingTopBar(state, onOpenOperator)
    if (state.radioStatus in RADIO_SETUP_STATES) {
        Spacer(Modifier.height(20.dp))
        StatusCard(state)
        Spacer(Modifier.height(14.dp))
        RadioSetupCard(
            state = state,
            onScanAgain = onScanAgain,
            onSelectRadio = onSelectRadio,
            onOpenBluetoothSettings = onOpenBluetoothSettings,
        )
        return
    }

    if (!state.isConnected) {
        Spacer(Modifier.height(20.dp))
        StatusCard(state)
        Spacer(Modifier.height(14.dp))
        ConnectionGate(state, onRetry)
        return
    }

    Spacer(Modifier.height(18.dp))
    ImmersiveMeshHero()
    Spacer(Modifier.height(14.dp))
    ExperienceSheet(onStart, onOpenBitcoin)
}

@Composable
private fun LandingTopBar(state: MeshDemoState, onOpenOperator: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("RESILIENT COMMS", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.pointerInput(onOpenOperator) {
                detectTapGestures(onLongPress = { onOpenOperator() })
            }.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (state.isConnected) Cyan else Danger, CircleShape),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                if (state.isConnected) "RADIO READY" else "RADIO OFFLINE",
                color = if (state.isConnected) Muted else Danger,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ImmersiveMeshHero() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(340.dp)
            .background(
                brush = Brush.linearGradient(
                    listOf(
                        Color(0xFF193D42),
                        Color(0xFF17263B),
                        Color(0xFF2F2530),
                    ),
                ),
                shape = RoundedCornerShape(28.dp),
            ),
    ) {
        Column(Modifier.padding(24.dp)) {
            Text("NO INTERNET REQUIRED", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(12.dp))
            Text(
                "The mesh is\nyour network.",
                color = Color.White,
                fontSize = 39.sp,
                lineHeight = 41.sp,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Bluetooth gets it to the radio.\nLoRa takes it from there.",
                color = Muted,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
        }
        MeshOrbit(Modifier.align(Alignment.BottomEnd).padding(22.dp))
    }
}

@Composable
private fun MeshOrbit(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(126.dp)
            .border(1.dp, Cyan.copy(alpha = 0.34f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(58.dp)
                .background(Cyan, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("⌁", color = Navy, fontSize = 25.sp, fontWeight = FontWeight.Black)
        }
        Box(Modifier.align(Alignment.TopEnd).size(13.dp).background(BitcoinOrange, CircleShape))
        Box(Modifier.align(Alignment.BottomStart).size(11.dp).background(Cyan, CircleShape))
    }
}

@Composable
private fun ExperienceSheet(onMessage: () -> Unit, onBitcoin: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121F33)),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("CHOOSE AN EXPERIENCE", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ExperienceTile("↗", "Send a mesh\nmessage", Cyan, onMessage)
                ExperienceTile("₿", "Relay\nBitcoin", BitcoinOrange, onBitcoin)
            }
        }
    }
}

@Composable
private fun ExperienceTile(symbol: String, title: String, accent: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Navy),
        contentPadding = PaddingValues(16.dp),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.width(133.dp).height(136.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(symbol, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Text(title, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun ConnectionGate(state: MeshDemoState, onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("PREPARING THE DEMO", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            Text(state.statusText, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("The visitor experiences will appear when the attached radio is ready.", color = Muted, lineHeight = 21.sp)
            Spacer(Modifier.height(18.dp))
            PrimaryButton("RETRY CONNECTION", onRetry, enabled = true)
        }
    }
}

@Composable
private fun RadioSetupCard(
    state: MeshDemoState,
    onScanAgain: () -> Unit,
    onSelectRadio: (String) -> Unit,
    onOpenBluetoothSettings: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("ATTACHED RADIO", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            when (state.radioStatus) {
                RadioStatus.SCANNING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = Cyan,
                            strokeWidth = 3.dp,
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            "Finding your paired Meshtastic radio…",
                            color = Color.White,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Keep the radio powered on and close to this phone.", color = Muted, lineHeight = 21.sp)
                }
                RadioStatus.NO_PAIRED_RADIO -> {
                    Text("No paired radio found", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Pair the attached Meshtastic radio in Android Bluetooth settings, then scan again.",
                        color = Muted,
                        lineHeight = 21.sp,
                    )
                    Spacer(Modifier.height(18.dp))
                    PrimaryButton("OPEN BLUETOOTH SETTINGS", onOpenBluetoothSettings, enabled = true)
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton("SCAN AGAIN", onScanAgain, enabled = true)
                }
                RadioStatus.SELECTION_REQUIRED -> {
                    Text("Choose this phone’s radio", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Only paired Meshtastic radios appear here. Pair a replacement in Android Bluetooth settings first, then return and scan again.",
                        color = Muted,
                        lineHeight = 20.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    state.discoveredRadios.forEach { radio ->
                        OutlinedButton(
                            onClick = { onSelectRadio(radio.address) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(radio.displayName, fontWeight = FontWeight.Bold)
                                Text(
                                    radio.rssi?.let { "Paired · ${signalLabel(it)}" } ?: "Paired in Android",
                                    color = Muted,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                    SecondaryButton("SCAN AGAIN", onScanAgain, enabled = true)
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun OperatorScreen(
    state: MeshDemoState,
    onRefreshPresence: () -> Unit,
    onReconnect: () -> Unit,
    onChangeRadio: () -> Unit,
    onChangeStation: () -> Unit,
    onResetBitcoinQueue: () -> Unit,
) {
    Text("Operator console", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Black)
    Spacer(Modifier.height(7.dp))
    Text(
        "Conference setup and live mesh diagnostics. Visitors do not need this screen.",
        color = Muted,
        lineHeight = 21.sp,
    )
    Spacer(Modifier.height(18.dp))
    StatusCard(state)

    Spacer(Modifier.height(14.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("PHONE + RADIO", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(11.dp))
            OperatorValue("Phone identity", state.stationName)
            OperatorValue("Attached radio", state.radioDisplayName)
            OperatorValue("BLE address", state.selectedRadioAddress ?: "Not selected")
            OperatorValue("Mesh node", state.ownNodeNumber?.asNodeId() ?: "Waiting for radio configuration")
        }
    }

    Spacer(Modifier.height(14.dp))
    PresenceDiagnostics(state)
    Spacer(Modifier.height(14.dp))
    PrimaryButton("REFRESH NETWORK PRESENCE", onRefreshPresence, state.isConnected)
    Spacer(Modifier.height(10.dp))
    SecondaryButton(
        "RECONNECT ATTACHED RADIO",
        onReconnect,
        state.selectedRadioAddress != null && !state.isBitcoinRelayActive,
    )
    Spacer(Modifier.height(10.dp))
    SecondaryButton("CHANGE RADIO", onChangeRadio, !state.isBitcoinRelayActive)
    Spacer(Modifier.height(10.dp))
    SecondaryButton("CHANGE PHONE IDENTITY", onChangeStation, !state.isBitcoinRelayActive)

    Spacer(Modifier.height(22.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("BITCOIN QUEUE", color = BitcoinOrange, fontSize = 11.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            Text(
                "${state.bitcoinRemaining} of ${state.bitcoinQueueTotal} signed transactions remaining",
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            SecondaryButton("RESET THIS PHONE'S QUEUE", onResetBitcoinQueue, !state.isBitcoinRelayActive)
        }
    }
}

@Composable
private fun RadioSelectionScreen(
    state: MeshDemoState,
    onScanAgain: () -> Unit,
    onSelectRadio: (String) -> Unit,
) {
    Text("Change attached radio", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Black)
    Spacer(Modifier.height(7.dp))
    Text(
        "Choose which paired Meshtastic radio this phone should control. The phone remains ${state.stationName}.",
        color = Muted,
        lineHeight = 21.sp,
    )
    Spacer(Modifier.height(18.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = Cyan.copy(alpha = 0.10f)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Cyan.copy(alpha = 0.25f), RoundedCornerShape(16.dp)),
    ) {
        Text(
            "Only radios already paired in Android appear here. Pair a replacement first, then return and tap Scan Again.",
            color = Color.White,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(16.dp),
        )
    }
    Spacer(Modifier.height(14.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("PAIRED RADIOS", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(12.dp))
            when (state.radioStatus) {
                RadioStatus.SCANNING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = Cyan,
                            strokeWidth = 3.dp,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Checking paired radios…", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("This takes only a moment.", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
                RadioStatus.SELECTION_REQUIRED -> {
                    state.discoveredRadios.forEach { radio ->
                        val wasAttached = radio.address == state.selectedRadioAddress
                        OutlinedButton(
                            onClick = { onSelectRadio(radio.address) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            contentPadding = PaddingValues(15.dp),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(radio.displayName, fontWeight = FontWeight.Bold)
                                    Text(
                                        if (wasAttached) "Previously attached" else "Paired in Android",
                                        color = Muted,
                                        fontSize = 12.sp,
                                    )
                                }
                                Text("SELECT  ›", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Black)
                            }
                        }
                    }
                }
                RadioStatus.NO_PAIRED_RADIO -> {
                    Text("No paired Meshtastic radios found", color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text("Complete pairing in Android, return here, and scan again.", color = Muted, fontSize = 13.sp)
                }
                RadioStatus.PERMISSION_REQUIRED -> {
                    Text("Bluetooth permission is required", color = Danger, fontWeight = FontWeight.Bold)
                }
                RadioStatus.ERROR -> {
                    Text(state.statusText, color = Danger, fontWeight = FontWeight.Bold)
                }
                else -> {
                    Text(state.statusText, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    SecondaryButton("SCAN AGAIN", onScanAgain, state.radioStatus != RadioStatus.SCANNING)
}

@Composable
private fun RadioReconnectScreen(
    state: MeshDemoState,
    onRetry: () -> Unit,
    onDone: () -> Unit,
) {
    val complete = state.radioStatus == RadioStatus.CONNECTED
    val failed = state.radioStatus == RadioStatus.ERROR
    Text("Reconnect attached radio", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Black)
    Spacer(Modifier.height(7.dp))
    Text(
        "The app is rebuilding the Bluetooth session without changing this phone’s identity or selected radio.",
        color = Muted,
        lineHeight = 21.sp,
    )
    Spacer(Modifier.height(18.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!complete && !failed) {
                CircularProgressIndicator(
                    modifier = Modifier.size(42.dp),
                    color = Cyan,
                    strokeWidth = 4.dp,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(if (complete) Cyan else Danger.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (complete) "✓" else "!",
                        color = if (complete) Navy else Danger,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                when {
                    complete -> "Radio reconnected"
                    failed -> "Reconnect failed"
                    else -> "Rebuilding radio link…"
                },
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(7.dp))
            Text(state.statusText, color = if (failed) Danger else Muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(7.dp))
            Text(
                "${state.stationName}  •  ${state.radioDisplayName}",
                color = Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    when {
        complete -> PrimaryButton("RETURN TO OPERATOR CONSOLE", onDone, enabled = true)
        failed -> PrimaryButton("TRY RECONNECT AGAIN", onRetry, enabled = true)
        else -> Text(
            "You can use Back to return to the Operator Console while reconnection continues.",
            color = Muted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PresenceDiagnostics(state: MeshDemoState) {
    val nowMs = System.currentTimeMillis()
    val conflicts = presenceConflicts(state.presences, nowMs)
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("LIVE PRESENCE", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(7.dp))
            Text(
                "Identity follows each app, even when Alice and Bob exchange radios.",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(12.dp))
            ConferenceIdentity.entries.forEach { identity ->
                val presence = latestPresence(identity, state.presences, nowMs)
                val conflict = identity in conflicts
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(identity.displayName, color = Color.White, fontWeight = FontWeight.Bold)
                        Text(
                            presence?.nodeNumber?.asNodeId() ?: "No fresh announcement",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    }
                    Text(
                        when {
                            conflict -> "CONFLICT"
                            presence != null -> "ACTIVE"
                            else -> "WAITING"
                        },
                        color = if (conflict) Danger else if (presence != null) Cyan else Muted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
            if (conflicts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Two active apps claim the same identity. Set one phone to the other identity, then refresh.",
                    color = Danger,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

@Composable
private fun OperatorValue(label: String, value: String) {
    Text(label.uppercase(), color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Black)
    Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(9.dp))
}

private fun Int.asNodeId(): String = "!${toUInt().toString(16)}"

@Composable
private fun BitcoinScreen(
    state: MeshDemoState,
    onRelay: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    Text("Relay a Bitcoin transaction", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(10.dp))
    Text(
        "A signed Regtest transaction is already stored on this phone. LoRa carries it to the laptop gateway, which submits it to Bitcoin Core.",
        color = Muted,
        fontSize = 15.sp,
        lineHeight = 23.sp,
    )
    Spacer(Modifier.height(20.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("TRANSACTION JOURNEY", color = BitcoinOrange, fontSize = 11.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(12.dp))
            Text(
                "SIGNED TX  →  LORA MESH  →  LAPTOP  →  BITCOIN CORE",
                color = Color.White,
                fontSize = 12.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    BitcoinRelayCard(state, onRelay, onReset)
    Spacer(Modifier.height(16.dp))
    SecondaryButton("BACK TO DEMOS", onBack, enabled = !state.isBitcoinRelayActive)
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
                Text("Radio: ${state.radioDisplayName}  •  Primary channel", color = Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ContactsScreen(
    state: MeshDemoState,
    onSelectRecipient: (String) -> Unit,
) {
    Text("Who do you want to reach?", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Black)
    Spacer(Modifier.height(8.dp))
    Text(
        "Choose one person for a direct message, send something to the Gateway, or deliberately broadcast to everyone.",
        color = Muted,
        lineHeight = 22.sp,
    )
    Spacer(Modifier.height(22.dp))
    state.recipients.filter { it.kind != RecipientKind.EVERYONE }.forEach { recipient ->
        RecipientCard(recipient, onSelectRecipient)
        Spacer(Modifier.height(12.dp))
    }
    Text("GROUP", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Black)
    Spacer(Modifier.height(10.dp))
    state.recipients.firstOrNull { it.kind == RecipientKind.EVERYONE }?.let { recipient ->
        RecipientCard(recipient, onSelectRecipient)
    }
    if (state.received.isNotEmpty()) {
        Spacer(Modifier.height(26.dp))
        Text("RECENTLY RECEIVED", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(10.dp))
        state.received.take(3).forEach { message ->
            val matchingRecipient = if (message.isBroadcast) {
                state.recipients.firstOrNull { it.kind == RecipientKind.EVERYONE }
            } else {
                state.recipients.firstOrNull { it.nodeNumber == message.senderNodeNumber }
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = Panel),
                modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (message.isBroadcast) "${message.sender} · broadcast" else message.sender,
                        color = Cyan,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(message.text, color = Color.White, fontSize = 16.sp)
                    if (matchingRecipient?.isAvailable == true) {
                        Spacer(Modifier.height(7.dp))
                        TextButton(
                            onClick = { onSelectRecipient(matchingRecipient.id) },
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text("REPLY  →", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipientCard(recipient: MeshRecipient, onSelectRecipient: (String) -> Unit) {
    val accent = when (recipient.kind) {
        RecipientKind.PERSON -> Cyan
        RecipientKind.GATEWAY -> BitcoinOrange
        RecipientKind.EVERYONE -> Muted
    }
    OutlinedButton(
        onClick = { onSelectRecipient(recipient.id) },
        enabled = recipient.isAvailable,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
        contentPadding = PaddingValues(16.dp),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(42.dp).background(accent.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when (recipient.kind) {
                            RecipientKind.PERSON -> recipient.displayName.take(1)
                            RecipientKind.GATEWAY -> "▣"
                            RecipientKind.EVERYONE -> "◎"
                        },
                        color = accent,
                        fontWeight = FontWeight.Black,
                    )
                }
                Spacer(Modifier.width(13.dp))
                Column(modifier = Modifier.width(205.dp)) {
                    Text(recipient.displayName, fontSize = 17.sp, fontWeight = FontWeight.Black)
                    Text(recipient.description, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
            Text(if (recipient.isAvailable) "›" else "…", color = accent, fontSize = 24.sp)
        }
    }
}

@Composable
private fun ComposeScreen(
    state: MeshDemoState,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
) {
    val recipient = state.selectedRecipient ?: return
    Text("Message ${recipient.displayName}", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Black)
    Spacer(Modifier.height(8.dp))
    Text(
        if (recipient.isBroadcast) {
            "Public conference broadcast · every node on the channel may read it."
        } else {
            "Directly addressed to ${recipient.displayName} across the Meshtastic network."
        },
        color = if (recipient.isBroadcast) BitcoinOrange else Muted,
        lineHeight = 22.sp,
    )
    ConversationPreview(state, recipient)
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
    PrimaryButton(
        if (recipient.isBroadcast) "BROADCAST TO EVERYONE" else "SEND TO ${recipient.displayName.uppercase()}",
        onSend,
        state.canSend,
    )
}

@Composable
private fun ConversationPreview(state: MeshDemoState, recipient: MeshRecipient) {
    val incoming = state.received.firstOrNull {
        if (recipient.isBroadcast) it.isBroadcast else !it.isBroadcast && it.senderNodeNumber == recipient.nodeNumber
    }
    val outgoing = state.sent.firstOrNull {
        if (recipient.isBroadcast) it.isBroadcast else !it.isBroadcast && it.recipientNodeNumber == recipient.nodeNumber
    }
    if (incoming == null && outgoing == null) return
    Spacer(Modifier.height(20.dp))
    Text("RECENT EXCHANGE", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
    Spacer(Modifier.height(8.dp))
    outgoing?.let {
        MessageBubble(label = "YOU → ${it.recipient.uppercase()}", text = it.text, outgoing = true)
    }
    incoming?.let {
        MessageBubble(label = if (it.isBroadcast) "${it.sender.uppercase()} → EVERYONE" else it.sender.uppercase(), text = it.text)
    }
}

@Composable
private fun MessageBubble(label: String, text: String, outgoing: Boolean = false) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (outgoing) Cyan.copy(alpha = 0.12f) else Panel),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp),
    ) {
        Column(Modifier.padding(13.dp)) {
            Text(label, color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(4.dp))
            Text(text, color = Color.White, fontSize = 15.sp)
        }
    }
}

@Composable
private fun ResultScreen(
    state: MeshDemoState,
    onContinue: () -> Unit,
    onChooseContact: () -> Unit,
    onHome: () -> Unit,
) {
    val recipient = state.selectedRecipient
    Text("Message journey", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(24.dp))
    JourneyNode("THIS PHONE", state.stationName)
    JourneyArrow("Bluetooth")
    JourneyNode("MESHTASTIC RADIO", state.radioDisplayName)
    JourneyArrow(if (recipient?.isBroadcast == true) "LoRa / public channel" else "LoRa / direct packet")
    JourneyNode(
        if (recipient?.isBroadcast == true) "EVERYONE" else recipient?.displayName?.uppercase() ?: "MESHTASTIC MESH",
        if (recipient?.kind == RecipientKind.GATEWAY) "Laptop and conference display" else "Meshtastic destination",
    )
    Spacer(Modifier.height(24.dp))
    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(state.sendStatusText.ifBlank { "Preparing message…" }, color = statusColor(state), fontWeight = FontWeight.Bold)
            state.lastPacketId?.let { Text("Packet $it", color = Muted, fontSize = 13.sp) }
            if (state.sendProgress == SendProgress.RELAYED_BY_MESH) {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (recipient?.isBroadcast == true) {
                        "The mesh relayed this public broadcast; it does not identify every listener."
                    } else {
                        "The destination confirmed the direct packet journey."
                    },
                    color = Muted,
                    fontSize = 13.sp,
                )
            }
        }
    }
    Spacer(Modifier.height(28.dp))
    val canNavigate = state.sendProgress !in setOf(SendProgress.QUEUED, SendProgress.SENT_TO_RADIO)
    PrimaryButton("SEND ANOTHER MESSAGE", onContinue, canNavigate)
    Spacer(Modifier.height(10.dp))
    SecondaryButton("CHOOSE ANOTHER CONTACT", onChooseContact, canNavigate)
    Spacer(Modifier.height(10.dp))
    TextButton(onClick = onHome, enabled = canNavigate, modifier = Modifier.fillMaxWidth()) {
        Text("BACK TO DEMOS", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Black)
    }
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

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit, enabled: Boolean) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().height(50.dp),
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

private val RADIO_SETUP_STATES = setOf(
    RadioStatus.SCANNING,
    RadioStatus.NO_PAIRED_RADIO,
    RadioStatus.SELECTION_REQUIRED,
)

private fun signalLabel(rssi: Int): String = when {
    rssi >= -60 -> "strong signal"
    rssi >= -75 -> "medium signal"
    else -> "weak signal"
}

private fun statusColor(state: MeshDemoState): Color =
    if (state.sendProgress == SendProgress.FAILED) Danger else Cyan

private fun bitcoinStatusColor(progress: BitcoinRelayProgress): Color =
    if (progress == BitcoinRelayProgress.FAILED) Danger else Cyan
