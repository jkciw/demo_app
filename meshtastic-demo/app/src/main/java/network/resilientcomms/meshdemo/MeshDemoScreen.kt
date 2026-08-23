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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
            state.step == DemoStep.COMPOSE -> viewModel.returnToContacts()
            else -> viewModel.returnHome()
        }
    }
    MaterialTheme {
        Surface(color = Navy, modifier = Modifier.fillMaxSize()) {
            when {
                state.stationRole == null || state.isChoosingStation -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                ) {
                    if (state.stationRole == null) {
                        Header(state)
                        Spacer(Modifier.height(26.dp))
                    } else {
                        SubpageTopBar(state, viewModel::cancelStationChange)
                        Spacer(Modifier.height(22.dp))
                    }
                    StationSelectionScreen(state.stationRole, onSelectStation)
                }

                state.step == DemoStep.COMPOSE -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                ) {
                    SubpageTopBar(state, viewModel::returnToContacts)
                    Spacer(Modifier.height(14.dp))
                    RecipientExperienceScreen(
                        state = state,
                        onDraftChanged = viewModel::updateDraft,
                        onSend = viewModel::send,
                        onOpenBitcoin = viewModel::openBitcoin,
                        modifier = Modifier.weight(1f),
                    )
                }

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                ) {
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
                    DemoStep.COMPOSE -> Unit
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
    ExperienceSheet(state, onStart, onOpenBitcoin)
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
private fun ExperienceSheet(state: MeshDemoState, onMessage: () -> Unit, onBitcoin: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121F33)),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("CHOOSE AN EXPERIENCE", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ExperienceTile("↗", "Send a mesh\nmessage", Cyan, enabled = true, onClick = onMessage)
                ExperienceTile(
                    symbol = "₿",
                    title = if (state.isGatewayAvailable) "Relay\nBitcoin" else "Gateway\nnot visible",
                    accent = BitcoinOrange,
                    enabled = state.canOpenBitcoin,
                    onClick = onBitcoin,
                )
            }
            if (!state.isGatewayAvailable) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Bitcoin relay becomes available when the laptop Gateway appears on the mesh.",
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun ExperienceTile(
    symbol: String,
    title: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
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
                RadioStatus.NO_PAIRED_RADIO,
                RadioStatus.PAIRING_REQUIRED,
                -> {
                    Text(
                        if (state.radioStatus == RadioStatus.PAIRING_REQUIRED) "Radio pairing was lost" else "No paired radio found",
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                    )
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

    Spacer(Modifier.height(18.dp))
    Text(
        "APP VERSION ${BuildConfig.VERSION_NAME} · BUILD ${BuildConfig.VERSION_CODE}",
        color = Muted,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
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
                RadioStatus.NO_PAIRED_RADIO,
                RadioStatus.PAIRING_REQUIRED,
                -> {
                    Text(
                        if (state.radioStatus == RadioStatus.PAIRING_REQUIRED) {
                            "The attached radio is no longer paired"
                        } else {
                            "No paired Meshtastic radios found"
                        },
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
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
    val pairingRequired = state.radioStatus == RadioStatus.PAIRING_REQUIRED
    val failed = state.radioStatus == RadioStatus.ERROR || pairingRequired
    Text("Reconnect attached radio", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Black)
    Spacer(Modifier.height(7.dp))
    Text(
        "The app is rebuilding the Bluetooth session without changing this phone’s identity or selected radio. If needed, pairing happens once inside this connection.",
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
                    pairingRequired -> "Pairing required"
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
        pairingRequired -> {
            Text(
                "Keep the attached radio awake, press the button below, then enter the PIN shown on its display. Automatic retries stay off if pairing fails.",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton("PAIR ATTACHED RADIO", onRetry, enabled = true)
        }
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
                if (state.isGatewayAvailable) {
                    "${state.bitcoinRemaining} remaining · Gateway ${state.gatewayNodeNumber?.asNodeId()} online"
                } else {
                    "${state.bitcoinRemaining} remaining · waiting for Gateway presence"
                },
                color = if (state.isGatewayAvailable) Muted else Danger,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                if (!state.isGatewayAvailable && !state.isBitcoinRelayActive) {
                    "Gateway is not currently visible on the mesh"
                } else {
                    state.bitcoinStatusText
                },
                color = if (!state.isGatewayAvailable && !state.isBitcoinRelayActive) {
                    Danger
                } else {
                    bitcoinStatusColor(state.bitcoinRelayProgress)
                },
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
                    !state.isGatewayAvailable -> "WAITING FOR GATEWAY"
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
    Text("Choose how to use the mesh", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Black)
    Spacer(Modifier.height(8.dp))
    Text(
        "Each path demonstrates a different kind of communication without relying on cellular service.",
        color = Muted,
        lineHeight = 22.sp,
    )
    Spacer(Modifier.height(22.dp))

    state.recipients.firstOrNull { it.kind == RecipientKind.PERSON }?.let { recipient ->
        ExperienceLabel("PERSON TO PERSON", "A familiar conversation")
        Spacer(Modifier.height(9.dp))
        RecipientCard(recipient, onSelectRecipient)
        Spacer(Modifier.height(20.dp))
    }

    state.recipients.firstOrNull { it.kind == RecipientKind.GATEWAY }?.let { recipient ->
        ExperienceLabel("GATEWAY SERVICES", "Reach the laptop and internet edge")
        Spacer(Modifier.height(9.dp))
        RecipientCard(recipient, onSelectRecipient)
        Spacer(Modifier.height(20.dp))
    }

    state.recipients.firstOrNull { it.kind == RecipientKind.EVERYONE }?.let { recipient ->
        ExperienceLabel("MESH ANNOUNCEMENT", "One message for every listening node")
        Spacer(Modifier.height(9.dp))
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
private fun ExperienceLabel(title: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Black)
        Text(description, color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun RecipientCard(recipient: MeshRecipient, onSelectRecipient: (String) -> Unit) {
    val accent = when (recipient.kind) {
        RecipientKind.PERSON -> Cyan
        RecipientKind.GATEWAY -> BitcoinOrange
        RecipientKind.EVERYONE -> Danger
    }
    val experienceTitle = when (recipient.kind) {
        RecipientKind.PERSON -> "MESSAGE ${recipient.displayName.uppercase()}"
        RecipientKind.GATEWAY -> "USE GATEWAY"
        RecipientKind.EVERYONE -> "BROADCAST TO EVERYONE"
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.09f)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.42f), RoundedCornerShape(20.dp)),
    ) {
        TextButton(
            onClick = { onSelectRecipient(recipient.id) },
            enabled = recipient.isAvailable,
            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
            contentPadding = PaddingValues(17.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(48.dp).background(accent.copy(alpha = 0.18f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            when (recipient.kind) {
                                RecipientKind.PERSON -> recipient.displayName.take(1)
                                RecipientKind.GATEWAY -> "▣"
                                RecipientKind.EVERYONE -> "◎"
                            },
                            color = accent,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                        )
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(modifier = Modifier.width(205.dp)) {
                        Text(experienceTitle, fontSize = 15.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(2.dp))
                        Text(recipient.description, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
                Text(if (recipient.isAvailable) "›" else "…", color = accent, fontSize = 24.sp)
            }
        }
    }
}

private data class ConversationLine(
    val key: String,
    val sender: String,
    val text: String,
    val outgoing: Boolean,
    val recordedAtMs: Long,
)

private fun conversationLines(state: MeshDemoState, recipient: MeshRecipient): List<ConversationLine> {
    val outgoing = state.sent
        .filter {
            if (recipient.isBroadcast) it.isBroadcast else !it.isBroadcast && it.recipientNodeNumber == recipient.nodeNumber
        }
        .map {
            ConversationLine(
                key = "sent-${it.packetId}",
                sender = "You",
                text = it.text,
                outgoing = true,
                recordedAtMs = it.recordedAtMs,
            )
        }
    val incoming = state.received
        .filter {
            if (recipient.isBroadcast) it.isBroadcast else !it.isBroadcast && it.senderNodeNumber == recipient.nodeNumber
        }
        .map {
            ConversationLine(
                key = "received-${it.packetId}",
                sender = it.sender,
                text = it.text,
                outgoing = false,
                recordedAtMs = it.recordedAtMs,
            )
        }
    return (outgoing + incoming).sortedBy(ConversationLine::recordedAtMs)
}

@Composable
private fun RecipientExperienceScreen(
    state: MeshDemoState,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onOpenBitcoin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state.selectedRecipient?.kind) {
        RecipientKind.PERSON -> ConversationScreen(state, onDraftChanged, onSend, modifier)
        RecipientKind.GATEWAY -> GatewayServicesScreen(state, onDraftChanged, onSend, onOpenBitcoin, modifier)
        RecipientKind.EVERYONE -> BroadcastScreen(state, onDraftChanged, onSend, modifier)
        null -> Unit
    }
}

@Composable
private fun ConversationScreen(
    state: MeshDemoState,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recipient = state.selectedRecipient ?: return
    val messages = conversationLines(state, recipient)
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, state.lastPacketId) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(44.dp).background(Cyan.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(recipient.displayName.take(1), color = Cyan, fontSize = 18.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(recipient.displayName, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Black)
                Text(
                    if (recipient.isAvailable) "Directly addressed conversation" else "Not currently visible on the mesh",
                    color = if (recipient.isAvailable) Muted else Danger,
                    fontSize = 12.sp,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        MessageRouteCard(state, recipient)
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Text(
                        if (recipient.isBroadcast) {
                            "Write the first public message to everyone on the conference channel."
                        } else {
                            "Start the conversation with ${recipient.displayName}."
                        },
                        color = Muted,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 26.dp),
                    )
                }
            } else {
                items(messages, key = ConversationLine::key) { message ->
                    ConversationBubble(message)
                }
            }
        }
        SendStatusLine(state)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.draft,
                onValueChange = onDraftChanged,
                placeholder = { Text("Message ${recipient.displayName}") },
                supportingText = { Text("${state.draftBytes} / $MAX_TEXT_BYTES bytes") },
                minLines = 1,
                maxLines = 4,
                enabled = recipient.isAvailable,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Cyan,
                    focusedBorderColor = Cyan,
                    unfocusedBorderColor = Muted,
                    focusedPlaceholderColor = Muted,
                    unfocusedPlaceholderColor = Muted,
                    focusedSupportingTextColor = Muted,
                    unfocusedSupportingTextColor = Muted,
                    focusedContainerColor = Panel,
                    unfocusedContainerColor = Panel,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = onSend,
                enabled = state.canSend,
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Navy),
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.width(74.dp).height(56.dp),
            ) {
                Text(if (recipient.isBroadcast) "POST" else "SEND", fontSize = 11.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun GatewayServicesScreen(
    state: MeshDemoState,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onOpenBitcoin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recipient = state.selectedRecipient ?: return
    val messages = conversationLines(state, recipient)
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, state.lastPacketId) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        ExperienceHeader(
            symbol = "▣",
            title = "Gateway Services",
            subtitle = if (recipient.isAvailable) "LoRa bridge online" else "Waiting for the laptop Gateway",
            accent = BitcoinOrange,
        )
        Spacer(Modifier.height(12.dp))
        MessageRouteCard(state, recipient)
        Spacer(Modifier.height(10.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = BitcoinOrange.copy(alpha = 0.10f)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().border(
                1.dp,
                BitcoinOrange.copy(alpha = 0.35f),
                RoundedCornerShape(14.dp),
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("BIG SCREEN MESSAGE", color = BitcoinOrange, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Send a message to the laptop display. The Gateway is a service endpoint, not another person.",
                    color = Color.White,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Text(
                        "Gateway activity will appear here.",
                        color = Muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    )
                }
            } else {
                items(messages, key = ConversationLine::key) { message ->
                    GatewayActivityCard(message)
                }
            }
        }
        SendStatusLine(state)
        OutlinedButton(
            onClick = onOpenBitcoin,
            enabled = state.canOpenBitcoin && !state.isBitcoinRelayActive,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = BitcoinOrange),
            modifier = Modifier.fillMaxWidth().height(44.dp),
        ) {
            Text("RELAY A SIGNED BITCOIN TRANSACTION  →", fontSize = 10.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(8.dp))
        MessageComposer(
            state = state,
            placeholder = "Message for the big screen",
            actionLabel = "DISPLAY",
            accent = BitcoinOrange,
            onDraftChanged = onDraftChanged,
            onAction = onSend,
        )
    }
}

@Composable
private fun BroadcastScreen(
    state: MeshDemoState,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recipient = state.selectedRecipient ?: return
    val messages = conversationLines(state, recipient)
    val listState = rememberLazyListState()
    var reviewing by remember { mutableStateOf(false) }
    LaunchedEffect(messages.size, state.lastPacketId) {
        reviewing = false
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        ExperienceHeader(
            symbol = "◎",
            title = "Mesh Announcement",
            subtitle = "One message for every listening node",
            accent = Danger,
        )
        Spacer(Modifier.height(12.dp))
        MessageRouteCard(state, recipient)
        Spacer(Modifier.height(10.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = Danger.copy(alpha = 0.10f)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, Danger.copy(alpha = 0.42f), RoundedCornerShape(14.dp)),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("VISIBLE TO EVERYONE", color = Danger, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Alice, Bob and the Gateway can receive this announcement. It is not a private conversation.",
                    color = Color.White,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Text(
                        "No public announcements yet.",
                        color = Muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    )
                }
            } else {
                items(messages, key = ConversationLine::key) { message ->
                    AnnouncementCard(message)
                }
            }
        }
        SendStatusLine(state)
        if (reviewing) {
            BroadcastReview(
                text = state.draft,
                canSend = state.canSend,
                onCancel = { reviewing = false },
                onConfirm = {
                    reviewing = false
                    onSend()
                },
            )
        } else {
            MessageComposer(
                state = state,
                placeholder = "Announcement to everyone",
                actionLabel = "REVIEW",
                accent = Danger,
                onDraftChanged = onDraftChanged,
                onAction = { reviewing = true },
            )
        }
    }
}

@Composable
private fun ExperienceHeader(symbol: String, title: String, subtitle: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(46.dp).background(accent.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(symbol, color = accent, fontSize = 18.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Black)
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun GatewayActivityCard(message: ConversationLine) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(13.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier.size(8.dp).background(if (message.outgoing) BitcoinOrange else Cyan, CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    if (message.outgoing) "SENT TO LAPTOP DISPLAY" else "GATEWAY RESPONSE",
                    color = if (message.outgoing) BitcoinOrange else Cyan,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.height(3.dp))
                Text(message.text, color = Color.White, fontSize = 14.sp, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
private fun AnnouncementCard(message: ConversationLine) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Danger.copy(alpha = if (message.outgoing) 0.12f else 0.07f)),
        shape = RoundedCornerShape(13.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(
                if (message.outgoing) "YOU · EVERYONE" else "${message.sender.uppercase()} · EVERYONE",
                color = Danger,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(3.dp))
            Text(message.text, color = Color.White, fontSize = 14.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun SendStatusLine(state: MeshDemoState) {
    if (state.sendProgress == SendProgress.IDLE || state.sendStatusText.isBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(statusColor(state), CircleShape))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(state.sendStatusText, color = statusColor(state), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            state.lastPacketId?.let { Text("Mesh packet $it", color = Muted, fontSize = 10.sp) }
        }
    }
}

@Composable
private fun MessageComposer(
    state: MeshDemoState,
    placeholder: String,
    actionLabel: String,
    accent: Color,
    onDraftChanged: (String) -> Unit,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = state.draft,
            onValueChange = onDraftChanged,
            placeholder = { Text(placeholder) },
            supportingText = { Text("${state.draftBytes} / $MAX_TEXT_BYTES bytes") },
            minLines = 1,
            maxLines = 4,
            enabled = state.selectedRecipient?.isAvailable == true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = accent,
                focusedBorderColor = accent,
                unfocusedBorderColor = Muted,
                focusedPlaceholderColor = Muted,
                unfocusedPlaceholderColor = Muted,
                focusedSupportingTextColor = Muted,
                unfocusedSupportingTextColor = Muted,
                focusedContainerColor = Panel,
                unfocusedContainerColor = Panel,
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Button(
            onClick = onAction,
            enabled = state.canSend,
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Navy),
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.width(78.dp).height(56.dp),
        ) {
            Text(actionLabel, fontSize = 10.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun BroadcastReview(
    text: String,
    canSend: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Danger.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Danger.copy(alpha = 0.45f), RoundedCornerShape(14.dp)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("REVIEW PUBLIC ANNOUNCEMENT", color = Danger, fontSize = 10.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(6.dp))
            Text("“$text”", color = Color.White, fontSize = 14.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text("EDIT", fontSize = 10.sp, fontWeight = FontWeight.Black)
                }
                Button(
                    onClick = onConfirm,
                    enabled = canSend,
                    colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Navy),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("BROADCAST", fontSize = 10.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun MessageRouteCard(state: MeshDemoState, recipient: MeshRecipient) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel.copy(alpha = 0.76f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("HOW IT TRAVELS", color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(3.dp))
            Text(
                "${state.stationName} phone  →  Bluetooth  →  ${state.radioDisplayName}",
                color = Color.White,
                fontSize = 11.sp,
            )
            Text(
                when (recipient.kind) {
                    RecipientKind.PERSON -> "LoRa addressed packet  →  ${recipient.displayName}"
                    RecipientKind.GATEWAY -> "LoRa addressed packet  →  Gateway  →  Laptop"
                    RecipientKind.EVERYONE -> "LoRa primary channel  →  Every listening node"
                },
                color = when (recipient.kind) {
                    RecipientKind.PERSON -> Muted
                    RecipientKind.GATEWAY -> BitcoinOrange
                    RecipientKind.EVERYONE -> Danger
                },
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ConversationBubble(message: ConversationLine) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (message.outgoing) Cyan.copy(alpha = 0.16f) else Panel,
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(0.82f),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                Text(message.sender.uppercase(), color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(3.dp))
                Text(message.text, color = Color.White, fontSize = 15.sp, lineHeight = 20.sp)
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
    RadioStatus.PAIRING_REQUIRED,
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
    when (progress) {
        BitcoinRelayProgress.FAILED -> Danger
        BitcoinRelayProgress.REQUESTING_GATEWAY,
        BitcoinRelayProgress.QUEUED,
        -> BitcoinOrange
        else -> Cyan
    }
