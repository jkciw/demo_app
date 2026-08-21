# Meshtastic + Columba Technical Reconnaissance

Date: 2026-08-19

Scope: Phase A source reconnaissance for the controlled Android conference demo described in `codex_handoff_meshtastic_reticulum_demo.md`. No demo application code has been written yet.

## Executive decision

- The proposed two-APK design is technically sound.
- Build the Meshtastic APK as a fresh app over the official Meshtastic SDK. Do not fork the stock Meshtastic Android app.
- Build the Reticulum APK as a small new screen/entry flow inside a preserved Columba fork. Use Columba's `pythonBackend` and `noSentry` flavors. Do not use the experimental Kotlin Reticulum backend.
- Do not share transport code between the APKs. Share only visual tokens and, at most, a shallow demo-state vocabulary.
- Two gates must be cleared before production implementation: accept the Meshtastic SDK's GPL-3.0-or-later distribution obligations, and reproduce the Columba Python/RNode BLE path on the actual four phones after fixing the current build-tool pin.

## Source baseline

| Project | Repository | Inspected revision | State |
|---|---|---|---|
| Meshtastic SDK | <https://github.com/meshtastic/meshtastic-sdk> | `4e29007db4437e99ddc4cd655491be851cc3f8da` (`main`, 2026-08-18) | Latest commit prepares 0.1.1 release notes; latest published release shown by the repo is 0.1.0. |
| Columba | <https://github.com/torlando-tech/columba> | `467726a210359117f94d250a814ff50331856c99` (`main`, 2026-08-17), tag `v2.2.3-beta` | Current source has Python and Kotlin backend flavors; releases explicitly recommend Python. |

The revisions above should be recorded as upstream pins when the real repositories are created. Snapshot or moving branch dependencies should not be used for conference builds.

## MESHTASTIC

### Repository and modules

Relevant modules from `settings.gradle.kts`:

- `:core` — `RadioClient`, protocol engine, packet/state APIs.
- `:transport-ble` — Kable-based BLE GATT transport.
- `:storage-sqldelight` — persistent nodes/channels/session storage.
- `:testing` — test transport and fixtures.
- `:transport-tcp` and `:transport-serial` — not needed for the phone demo.
- `:samples:parity-android-app` — useful Android integration reference, not a base app to fork.
- `:bom` — dependency version alignment.

Published dependencies currently documented by the repository are `org.meshtastic:sdk-core:0.1.0`, `org.meshtastic:sdk-transport-ble:0.1.0`, and `org.meshtastic:sdk-storage-sqldelight:0.1.0`. The README warns that `-SNAPSHOT` artifacts are mutable.

### Build and Android floor

- Gradle wrapper: 9.6.1.
- Kotlin: 2.4.10.
- Java toolchain: 21.
- Android Gradle Plugin: 9.3.1.
- Android `minSdk`: 26; compile/target SDK: 37.
- Important libraries: coroutines 1.11.0, Kable 0.44.3, SQLDelight 2.3.2, Meshtastic protobufs 2.7.26.

The API 26 floor is non-negotiable for an app consuming the published Android artifacts. Confirm all M1/M2 demo phones are Android 8.0 or newer.

### BLE transport

Exact implementation:

- `transport-ble/src/commonMain/kotlin/org/meshtastic/sdk/transport/ble/BleTransport.kt`
  - `class BleTransport(peripheral: Peripheral, address: String, ...) : RadioTransport`
- `transport-ble/src/androidMain/kotlin/org/meshtastic/sdk/transport/ble/BleTransport.android.kt`
  - `fun BleTransport(address: String, builderAction: PeripheralBuilder.() -> Unit = {}): BleTransport`
- `transport-ble/src/commonMain/kotlin/org/meshtastic/sdk/transport/ble/BleConstants.kt`
  - `MESH_SERVICE_UUID = 6ba1b218-15a8-461f-9fa8-5dcae273eafd`
- `transport-ble/src/commonMain/kotlin/org/meshtastic/sdk/transport/ble/internal/DrainCoordinator.kt`

The SDK does not own scanning. The app uses Kable `Scanner`, filters advertisements by `BleConstants.MESH_SERVICE_UUID`, and creates a `Peripheral`, or reconnects directly with the persisted Android Bluetooth address:

```kotlin
val transport = BleTransport(address = persistedAddress) {
    autoConnectIf { true }
}
```

The address factory is the appropriate conference path after initial provisioning. It requests MTU 517 and high connection priority during the handshake, then returns to balanced priority. `BleTransport` writes `TORADIO` with write-with-response, observes `FROMNUM`, and drains `FROMRADIO` until empty.

Android requirements:

- API 31+: `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` runtime permissions.
- API 30 and below: legacy Bluetooth plus location permission rules.
- API 34+: a foreground service of type `connectedDevice` is required if the BLE link must remain alive while the app is backgrounded. The SDK deliberately does not host that service.
- Pairing/bonding remains an OS operation. Provision each station before the conference; the public flow should never expose the scanner.

### Connection API

Construct the client with the BLE transport and production storage:

```kotlin
val client = RadioClient.Builder()
    .transport(transport)
    .storage(sqlDelightStorageProvider)
    .build()

client.connect()
```

`RadioClient.connect()` performs transport connection and the Meshtastic configuration handshake. `disconnect()` is suspending, idempotent, and should be called by the lifecycle owner.

Observable state in `core/src/commonMain/kotlin/org/meshtastic/sdk/RadioClient.kt`:

- `connection: StateFlow<ConnectionState>` — `Disconnected`, `Connecting`, `Configuring`, `Connected`, and reconnect/error states.
- `ownNode: StateFlow<NodeInfo?>` — local node after handshake.
- `channels: StateFlow<List<Channel>?>`.
- `nodes: Flow<NodeChange>` — initial snapshot followed by deltas.
- `packets: Flow<MeshPacket>` — decoded inbound packets.
- `events: Flow<MeshEvent>` — queue, transport, packet-drop, and other advisory events.
- `nodeSnapshot(): Map<NodeId, NodeInfo>` — one-shot sender lookup.

For Android SQLDelight storage, initialize `AndroidContextHolder.context` with the application context and construct `SqlDelightStorageProvider(filesDir.absolutePath)`.

### Send API

The V1 call is:

```kotlin
val handle = client.sendText(
    text = "CODEX TEST",
    to = NodeId.BROADCAST,
    channel = ChannelIndex(0),
)
```

Exact signature:

```kotlin
fun sendText(
    text: String,
    to: NodeId = NodeId.BROADCAST,
    channel: ChannelIndex = ChannelIndex(0),
    replyId: Int = 0,
): MessageHandle
```

`sendText` encodes UTF-8 as `PortNum.TEXT_MESSAGE_APP`, sets `want_ack = true`, and delegates to `RadioClient.send(MeshPacket)`. Payloads are limited to 233 bytes.

`MessageHandle.state: StateFlow<SendState>` moves from `Queued` to `Sent` and then `Acked`, `Delivered`, or `Failed`; `await(): SendOutcome` provides a one-shot result. Preserve the packet/message ID in debug logs.

Important accuracy rule: a broadcast ACK means the firmware overheard at least one neighbor relay the packet. It does not prove that M2 or B1 displayed it. The public UI may say “relayed by mesh” for that state; it may only say “received on M2” after the M2 app observes the packet or sends an explicit application reply.

### Receive API

Collect `RadioClient.packets` and filter decoded text packets:

```kotlin
client.packets.collect { packet ->
    if (packet.decoded?.portnum == PortNum.TEXT_MESSAGE_APP) {
        val text = packet.decoded.payload.utf8()
        val senderNodeNumber = packet.from
    }
}
```

Map `packet.from` to a friendly sender using the maintained node snapshot/`nodes` flow. The internal source path is:

```text
BleTransport FROMNUM notification
  -> DrainCoordinator reads FROMRADIO
  -> MeshEngine routeNormalEnvelope(FromRadio)
  -> processInboundMeshPacket(MeshPacket)
  -> packets.tryEmit(packet)
  -> RadioClient.packets collector
```

This is already the correct abstraction boundary; the demo must not reimplement protobuf framing or GATT characteristics.

### Meshtastic risks

1. **GPL distribution obligation:** the SDK is GPL-3.0-or-later. A distributed APK linked with it should be planned as a GPL-covered combined work, including corresponding-source and notice obligations. Get legal confirmation and decide where the complete buildable source will be offered before distributing conference APKs. This is not a reason to copy SDK code into the app; copying would not remove the obligation.
2. **Pre-1.0 churn:** the documented release is 0.1.0 and `main` is preparing 0.1.1 while already containing some forward-looking `@since 0.2.0` documentation. Pin an immutable release/commit and compile the proof of concept before stabilizing app wrappers.
3. **Reconnect semantics:** use one `RadioClient` per assigned radio, persist the station's real address, enable/own reconnect deliberately, and expose a simple retry state.
4. **BLE background rules:** keep the participant flow foregrounded. Add a connected-device foreground service only if real device testing shows it is necessary.
5. **Protocol claims:** relay ACK is not recipient-specific delivery.

## COLUMBA / RETICULUM

### Repository and relevant modules

- `:app` — Compose UI, view models, services, product flavors.
- `:data` — Room database, conversation repository, encrypted identity storage.
- `:domain` — shared domain models/contracts.
- `:rns-api` — backend-neutral Kotlin API, including `RnsLxmf`.
- `:rns-host` — service/process host, backend selection, Android RNode bridges.
- `:rns-ipc` — AIDL client/server adapters between UI and `:reticulum` process.
- `:rns-backend-py` — Chaquopy implementation over pinned Python Reticulum/LXMF.
- `:rns-backend-kt` — native Kotlin implementation; do not use for this demo.
- `:rns-stats`, `:micron` — supporting status/telemetry functionality.

### Build and Android floor

- Gradle wrapper: 9.4.1.
- Kotlin: 2.3.20.
- Java toolchain floor: 21; source comments say CI uses JDK 25.
- Android compile SDK 36, target SDK 35, `minSdk` 24.
- Compose 1.7.5 / BOM 2026.05.01, Hilt 2.59.2, Room 2.8.4, coroutines 1.11.0.
- Python backend: Chaquopy 17.0.0 with CPython 3.11; ABIs `armeabi-v7a`, `arm64-v8a`, and `x86_64`.

For a conference artifact use `noSentryPythonBackend`, which removes Sentry rather than merely disabling upload. Do not invoke an ambiguous task such as `assembleNoSentryDebug`; qualify the RNS flavor.

### Python/Kotlin backend status

The source and release workflow are explicit:

- `pythonBackend`: recommended; base app ID `network.columba.app`; official Python protocol implementations via Chaquopy.
- `kotlinBackend`: default only for Gradle's flavor selection convenience; separate `.kt` app ID and “Columba (Kotlin)” label; release assets call it `EXPERIMENTAL-reticulum-kt`, AI-generated, work in progress, and not completely safety-verified.

Use `pythonBackend` even though `kotlinBackend` has `isDefault = true` in `app/build.gradle.kts`.

Python protocol pins from `rns-backend-py/PINNED_VERSIONS.md`:

| Component | Version/pin | License |
|---|---|---|
| Reticulum fork | 1.4.2, `5b3a6ee4f25e2925cf84d4a2b108e6a708fbd395` | MIT-style Reticulum License |
| LXMF fork | 1.1.0, `8912186e48b482a76bf04e2ac4b6c8940991aecc` | MIT-style Reticulum License |
| ble-reticulum | 0.2.2, `07d941304c9a1dc3a8e58087b3b974ff3d229e56` | MIT |
| Chaquopy | 17.0.0 | MIT/open source from 12.0.1 onward |

`cryptography>=42.0.0` and `u-msgpack-python` are not pinned to immutable versions in the current Gradle pip block. Freeze the resolved dependency set for the release candidate and generate a complete third-party notices report.

### UI to send call chain

Exact normal message path:

```text
app/.../ui/screens/MessagingScreen.kt
  MessageInputBar(onSendClick)
  -> MessagingViewModel.sendMessage(destinationHash, messageText)
     app/.../viewmodel/MessagingViewModel.kt
  -> loadIdentityIfNeeded()
  -> RnsLxmf.getLxmfIdentity()
  -> RnsLxmf.sendLxmfMessageWithMethod(...)
     rns-api/.../RnsLxmf.kt
  -> BoundRnsLxmf
     rns-host/.../ipc/BoundRnsLxmf.kt
  -> ReticulumServiceConnection / RnsBackendClient
  -> ClientRnsLxmf
     rns-ipc/.../client/ClientRnsLxmf.kt
  -> AIDL IRnsLxmf
  -> ServerRnsLxmf
     rns-ipc/.../server/ServerRnsLxmf.kt
  -> PythonRnsLxmf.sendLxmfMessageWithMethod()
     rns-backend-py/.../PythonRnsLxmf.kt
  -> dispatchLxmessage()
  -> Python LXMF.LXMessage(...)
  -> LXMRouter.handle_outbound(lxmessage)
  -> RNS interface / RNode
```

`ProcessAwareBackendModule` injects the local backend only inside the `:reticulum` process. The UI process receives `BoundRnsBackend`; `ReticulumService.onBind()` exposes the AIDL server. The demo screen should preserve this process seam rather than instantiate Python directly.

### Smallest LXMF send API

The smallest existing contract is `rns-api/src/main/java/network/columba/app/rns/api/RnsLxmf.kt`:

```kotlin
suspend fun sendLxmfMessage(
    destinationHash: ByteArray,
    content: String,
    sourceIdentity: Identity,
    imageData: ByteArray? = null,
    imageFormat: String? = null,
    fileAttachments: List<Pair<String, ByteArray>>? = null,
): Result<MessageReceipt>
```

It chooses direct delivery in the Python backend. For parity with the current UI, call `sendLxmfMessageWithMethod(..., deliveryMethod = DeliveryMethod.DIRECT, tryPropagationOnFail = true)` and observe `RnsLxmf.observeDeliveryStatus()`.

The demo screen can inject the existing `RnsLxmf`, load the running LXMF identity with `getLxmfIdentity()`, and pass one of two provisioned destination hashes (`RET-BRAVO` or B2). Do not introduce a new Reticulum protocol facade.

### Receive to UI call chain

```text
RNode bytes
  -> ColumbaRNodeInterface / Python RNS Transport.inbound
  -> Python LXMRouter delivery callback
  -> event_bridge.py
  -> PythonEventBridge.handleLxmfDelivery(PyObject)
  -> PythonEventBridge messages SharedFlow<ReceivedMessage>
  -> PythonRnsLxmf.observeMessages()
  -> ServerRnsLxmf AIDL callback
  -> ClientRnsLxmf.callbackFlow
  -> BoundRnsLxmf.observeMessages()
  -> app/service/MessageCollector.kt
  -> ConversationRepository.saveMessage(...)
  -> Room MessageDao / PagingSource invalidation
  -> MessagingViewModel.messages
  -> MessagingScreen.collectAsLazyPagingItems()
```

The backend-neutral receive API is:

```kotlin
fun observeMessages(): Flow<ReceivedMessage>
```

`ReceivedMessage` includes source and destination hashes, content, timestamp, and delivery metadata. For the first minimal screen, the safest route is to keep `MessageCollector` and Room intact and display the existing conversation paging flow. A narrower screen-local collector is possible later, but it must not race with or duplicate service persistence.

### RNode BLE implementation

For non-TCP RNode configurations, `rns-backend-py/.../RnsConfigFile.kt` emits:

```text
type = ColumbaRNodeInterface
connection_mode = ble | classic | usb
```

Runtime path:

```text
PythonRnsRuntime.start()
  -> event_bridge.deploy_bundled_interfaces(configDir)
  -> event_bridge.set_rnode_bridge(KotlinRNodeBridge)
  -> RNS.Reticulum(configDir)
  -> deployed ColumbaRNodeInterface.py
  -> KotlinRNodeBridge.connectWithResult(target, "ble")
  -> Android BluetoothGatt Nordic UART Service
```

Exact relevant files:

- `rns-backend-py/src/main/python/columba_rnode_interface.py` — Columba RNS interface, KISS/RNode protocol, reconnect/read/write loop.
- `rns-backend-py/src/main/python/event_bridge.py` — deploys the interface and exposes Kotlin callbacks.
- `rns-backend-py/src/main/kotlin/network/columba/app/rns/backend/py/PythonRnsRuntime.kt` — configures Python, RNS, LXMF, and bridges.
- `rns-host/src/main/kotlin/network/columba/app/rns/host/rnode/KotlinRNodeBridge.kt` — Android BLE/Classic bridge.

`KotlinRNodeBridge` uses the Nordic UART Service: service `6e400001-b5a3-f393-e0a9-e50e24dcca9e`, RX/write `6e400002-...`, TX/notify `6e400003-...`. It requests MTU 512, enables encrypted notifications, chunks writes to MTU minus 3, and buffers notification bytes for Python. It is a singleton, so one app process controls one RNode at a time, which matches the fixed-station design.

For `connection_mode = tcp`, config uses upstream `RNodeInterface` instead of the Columba BLE bridge.

### Identity storage

- `data/.../db/entity/LocalIdentityEntity.kt` stores identity/destination hashes and encrypted key material in Room.
- `data/.../crypto/IdentityKeyProvider.kt` encrypts/decrypts the 64-byte identity key through Android Keystore/device or device-plus-password protection, caches plaintext only in memory, and clears it when the app backgrounds.
- Application startup places the decrypted key into `ReticulumConfig.deliveryIdentityKey`.
- `PythonRnsRuntime.start()` calls `RNS.Identity.from_bytes(key)` and registers the delivery identity with `LXMRouter`.

Provision RET-A and RET-B identities once, back them up securely, and hide identity-management UI. Destination hashes for RET-B and B2 should be build/station configuration, not participant input.

### Columba risks

1. **Current `main` build-tool regression:** `./gradlew :app:assembleNoSentryPythonBackendDebug` fails during task creation because Gradle 9.4.1 is paired with `org.gradle.toolchains.foojay-resolver-convention` 0.10.0, which references removed `JvmVendorSpec.IBM_SEMERU`. Updating the resolver to 1.0.0 in a disposable clone cleared that error. This should be a small, isolated first fork commit and ideally submitted upstream.
2. **Build not completed in this environment:** after the temporary resolver update, configuration reached Android task dependency resolution but stopped because no Android SDK is installed on this machine. The actual project must be built with SDK 36 and tested before UI changes.
3. **Hardware proof remains mandatory:** source contains the Python RNode BLE bridge, deployment, reconnect loop, and tests, but `PythonRnsTransportAdmin.reconnectRNodeInterface()` still logs that Python RNode support is an “on-device follow-up.” Treat real R1/R2 BLE reconnect, send, receive, app restart, radio power-cycle, and airplane-mode tests as release gates.
4. **Preserve service/IPC/lifecycle code:** bypassing `ReticulumService`, the AIDL seam, `MessageCollector`, or identity startup is more likely to destabilize the known-good stack than to simplify it.
5. **Package size and ABI:** Chaquopy packages CPython/native wheels per ABI. Produce per-ABI arm64 conference APKs when the actual phones allow it; keep a universal build only as a fallback.
6. **MPL obligations:** Columba is MPL-2.0. Retain notices and make source for modifications to MPL-covered files available when distributing APKs. New files may use compatible terms, but modifying Columba files keeps those files under MPL. Confirm final obligations with counsel.
7. **Offline runtime:** use `noSentry`; verify Wi-Fi/cellular-off startup does not wait on update checks, map/network features, propagation nodes, or analytics. The RNS/LXMF/RNode message path itself is local.

## Verification performed

| Check | Result |
|---|---|
| Meshtastic `:core:jvmTest` at inspected commit | Passed: `BUILD SUCCESSFUL`, 13 actionable tasks, Gradle 9.6.1. The optional Develocity remote build cache had a local TLS trust error and was disabled; compilation/tests continued locally. |
| Columba recommended debug build, unmodified commit | Failed before compilation due to Gradle 9.4.1 + Foojay resolver 0.10.0 incompatibility. |
| Columba build after temporary Foojay 1.0.0 change | Passed the original failure, then stopped because this host has no Android SDK. The temporary edit was not made in this workspace. |
| BLE/RNode/radio tests | Not performed; require M1/M2/R1/R2, LT1/LT2/LT3/Heltec, and B1/B2. |

## Recommended implementation plan

### 0. Repository and compliance setup

1. Create `meshtastic-demo/` as a fresh Git repository.
2. Create `reticulum-demo/` as a Columba fork with `upstream` pointing to `torlando-tech/columba`.
3. Record the inspected upstream SHAs and dependency pins.
4. Decide that the Meshtastic APK/source distribution will comply with GPL-3.0-or-later before adding the dependency.
5. Add `docs/THIRD_PARTY_NOTICES.md` and an automated resolved-dependency/license report before release.

### 1. Meshtastic proof of concept

1. Create a minimal single-activity Compose app with `minSdk 26`.
2. Add released/pinned `sdk-core`, `sdk-transport-ble`, and `sdk-storage-sqldelight` artifacts.
3. Add station build flavors `meshA` and `meshB` containing labels and provisioned radio identifiers; keep secrets out of source (Meshtastic addresses are identifiers, not secrets).
4. Implement `MeshtasticSession` owning one `BleTransport`, one `RadioClient`, and its coroutine scope.
5. Show `connection`, send `CODEX TEST` using broadcast/channel 0, collect `packets`, and display sender/text.
6. Test M1 -> LT1 -> LT2 -> M2 and confirm B1 also sees the channel message with Wi-Fi/cellular off.
7. Test app restart, radio power-cycle, Bluetooth toggle, permission denial/recovery, and 30-minute idle operation.

### 2. Reticulum proof of concept

1. Fork the inspected Columba revision; preserve history and add `upstream`.
2. In the first isolated commit, update Foojay resolver 0.10.0 to 1.0.0 and verify all CI/build variants affected by the change.
3. Install Android SDK 36 and build/launch `:app:assembleNoSentryPythonBackendDebug` before changing UI.
4. Reconfirm stock Columba Python-backend RNode BLE messaging on R1/R2 and B2.
5. Add a new minimal Compose route; do not remove old UI. Inject existing `RnsLxmf`/repositories and retain `ReticulumService`, IPC, `MessageCollector`, Room, and identity lifecycle.
6. Provide two fixed targets on RET-A (`RET-BRAVO`, `BASE STATION`) and the appropriate fixed counterpart on RET-B. Store validated 16-byte destination hashes in station configuration.
7. Send `CODEX TEST`, show actual delivery-status vocabulary, and display inbound Room-backed messages.
8. Only after hardware proof, make the demo route the launcher/default and hide existing configuration/navigation from participants.

### 3. Shared participant experience

After both hardware paths pass, implement the common `START -> COMPOSE -> SENDING -> RESULT -> EXPLANATION -> START OVER` visual language. Keep protocol-specific status mapping:

- Meshtastic: connected, queued, sent, mesh relay acknowledged, observed on receiver/replied.
- Reticulum: RNode/interface online, LXMF submitted, delivered/failed according to `DeliveryStatusUpdate`, observed inbound.

Never map “submitted” or a Meshtastic broadcast relay ACK to “received by the named phone.”

## Files to create or modify first

Meshtastic fresh app:

```text
meshtastic-demo/settings.gradle.kts
meshtastic-demo/build.gradle.kts
meshtastic-demo/app/build.gradle.kts
meshtastic-demo/app/src/main/AndroidManifest.xml
meshtastic-demo/app/src/main/.../MeshtasticSession.kt
meshtastic-demo/app/src/main/.../MeshDemoViewModel.kt
meshtastic-demo/app/src/main/.../MeshProofScreen.kt
meshtastic-demo/app/src/meshA/.../StationConfig.kt
meshtastic-demo/app/src/meshB/.../StationConfig.kt
```

Columba fork, in order:

```text
reticulum-demo/settings.gradle.kts                  # Foojay 1.0.0 compatibility fix
reticulum-demo/app/src/main/.../DemoMessagingViewModel.kt
reticulum-demo/app/src/main/.../DemoMessagingScreen.kt
reticulum-demo/app/src/main/.../DemoStationConfig.kt
reticulum-demo/app/src/retA/.../DemoStationConfig.kt
reticulum-demo/app/src/retB/.../DemoStationConfig.kt
reticulum-demo/app/src/main/.../navigation/...       # add route only; preserve stock routes initially
```

Do not modify `rns-backend-py`, `rns-ipc`, `rns-host`, the RNode bridge, or identity persistence for the first Reticulum proof. If hardware testing exposes a fault there, isolate and document that change separately.

