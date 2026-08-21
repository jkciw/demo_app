# Codex Handoff — Meshtastic + Reticulum Demo Apps

## Project: Resilient Communications Demo for Conference Booth

You are taking over implementation of a controlled Android demo experience for two LoRa-based communication stacks:

1. **Meshtastic**
2. **Reticulum / LXMF using Columba**

The goal is **not** to build generic messaging applications. These are tightly controlled conference demo applications installed on four dedicated Android phones.

The user already has the hardware and underlying radio links working.

---

## 1. Physical Hardware Setup

There are four Android phones.

### Meshtastic side

#### Phone M1
- Android phone
- Paired over BLE to:
  - LilyGO T3 — call this `LT1`
- `LT1` is flashed with Meshtastic firmware

#### Phone M2
- Android phone
- Paired over BLE to:
  - LilyGO T3 — call this `LT2`
- `LT2` is flashed with Meshtastic firmware

#### Laptop Meshtastic base node
- Seeed XIAO + SX1262
- Connected to laptop
- Flashed with Meshtastic firmware
- Call this node `B1`

Topology:

```text
Phone M1
   │ BLE
   ▼
LT1
   │
   │ LoRa / Meshtastic
   ▼
LT2
   │ BLE
   ▼
Phone M2

B1/XIAO is a third Meshtastic node associated with the laptop.
```

Logical network:

```text
M1 ───────── M2
 \           /
  \         /
      B1
```

---

## 2. Reticulum Side

#### Phone R1
- Android phone
- Paired over BLE to:
  - LilyGO T3 — call this `LT3`
- LT3 is running the RNode firmware required for Reticulum

#### Phone R2
- Android phone
- Paired over BLE to:
  - Heltec LoRa board
- Heltec is running the required RNode firmware

#### Laptop Reticulum base node
- Second Seeed XIAO + SX1262
- Connected to laptop
- Configured as Reticulum/RNode
- Call this node `B2`

Topology:

```text
Phone R1
   │ BLE
   ▼
LT3
   │
   │ LoRa / Reticulum
   ▼
Heltec
   │ BLE
   ▼
Phone R2

B2/XIAO is a third Reticulum node connected to the laptop.
```

Logical network:

```text
R1 ───────── R2
 \
  \
   B2
```

For Reticulum, R1 → R2 and R1 → B2 should be treated as separate destination-oriented interactions.

---

## 3. Existing Working State

The user has already tested Reticulum manually.

Known-good pieces include:

- Columba on Android
- BLE connection to RNodes
- Reticulum running on laptop
- LXMF working
- messages sent:
  - phone → laptop
  - laptop → phone
  - phone-to-phone topology tested
- LilyGO and Heltec radio activity visibly occurs during transmission

Do **not** redesign or replace Reticulum itself.

Preserve the working Reticulum/LXMF/RNode stack.

---

## 4. Core Product Requirement

We do **not** want participants to operate:

- stock Meshtastic app
- stock Columba UI
- Android settings
- BLE pairing UI
- radio configuration
- node settings
- channels
- Reticulum interface configuration
- CLI tools

We want a purpose-built, simplified conference interface.

The participant experience should essentially be:

```text
PICK UP PHONE
    ↓
START
    ↓
TYPE MESSAGE
    ↓
SEND
    ↓
SEE MESSAGE ARRIVE ON OTHER PHONE
    ↓
SEE A SIMPLE VISUAL EXPLANATION
```

The app should guide the user and make it difficult to leave the intended workflow.

---

## 5. Important Architectural Constraint

A phone **cannot choose between Meshtastic and Reticulum**.

The network is determined by the physical radio paired to that phone.

Example:

```text
M1
 ↓ BLE
LT1
 ↓
Meshtastic firmware
```

Therefore M1 is always a Meshtastic station.

Likewise:

```text
R1
 ↓ BLE
LT3
 ↓
RNode / Reticulum
```

Therefore R1 is always a Reticulum station.

Do NOT implement a screen such as:

```text
Choose Network

[Meshtastic]
[Reticulum]
```

That would be technically misleading.

Instead, there are fixed station roles.

---

## 6. Proposed Software Architecture

We are currently leaning toward **two separate APKs sharing the same visual design**.

### APK 1

```text
MeshDemo.apk
```

Installed on:

- M1
- M2

Architecture:

```text
Custom Compose UI
        │
Demo workflow/state machine
        │
Meshtastic SDK
        │
BLE transport
        │
LT1/LT2
```

### APK 2

```text
ReticulumDemo.apk
```

Installed on:

- R1
- R2

Architecture:

```text
Custom Compose UI
        │
Demo workflow/state machine
        │
Columba domain / RNS abstraction
        │
Columba RNS backend
        │
Reticulum + LXMF
        │
RNode BLE
        │
LT3 / Heltec
```

The two APKs should look as though they are parts of one coordinated demo system.

---

## 7. Meshtastic Implementation Direction

Do **not** fork the entire official Meshtastic Android application unless there is a compelling technical reason.

Inspect and use:

`https://github.com/meshtastic/meshtastic-sdk`

The SDK is intended for third-party Meshtastic clients.

Primary tasks:

- discover/connect to a known Meshtastic node over BLE
- monitor connection state
- send text packets
- receive text packets
- obtain sender information if available
- track ACK/delivery state if useful
- support channel 0 / primary channel initially
- keep implementation minimal

Likely API area to inspect:

```text
RadioClient
sendText()
connectionState
myNode
packet/message flows
BLE transport
```

The app should not reproduce Meshtastic configuration screens.

---

## 8. Meshtastic Communication Model

For the public demo, treat Meshtastic primarily as a shared-channel interaction.

Preferred flow:

```text
M1 sends:
"Hello everyone"

        ↓ LoRa

M2 receives it
B1 also receives it
```

Conceptually:

```text
M1 ─┐
    ├── OPEN-SOURCE-HUB channel
M2 ─┤
B1 ─┘
```

For version 1, it is acceptable to simply use the primary/default configured channel if changing channels adds unnecessary complexity.

The devices can be configured beforehand.

---

## 9. Reticulum Implementation Direction

Repository:

`https://github.com/torlando-tech/columba`

Do **not** reimplement Reticulum or LXMF.

The current Columba structure contains modules similar to:

```text
app/
data/
domain/

rns-api/
rns-host/
rns-ipc/

rns-backend-py/
rns-backend-kt/
```

Inspect the current repository rather than assuming the exact names above are unchanged.

The intended direction is:

```text
OUR UI
   ↓
existing Columba domain / RNS abstraction
   ↓
existing known-good backend
   ↓
Reticulum
   ↓
LXMF
   ↓
RNode BLE
```

---

## 10. Reticulum Backend Preference

Prefer the backend that uses the official Python Reticulum/LXMF implementation, assuming this remains the recommended stable Columba configuration.

Historically/currently this has been the backend using:

```text
Chaquopy
+ official Python Reticulum
+ official Python LXMF
```

There is/has also been a Kotlin Reticulum implementation.

Do **not** migrate this demo to an experimental Reticulum implementation just to obtain a cleaner architecture.

Reliability at the conference matters more than architectural purity.

Before coding, verify the current Columba repository and release documentation.

---

## 11. First Task on Reticulum

Before changing UI, trace the exact existing message flow inside Columba.

We need to identify:

### Sending

Starting from the existing Columba Compose screen/button:

```text
UI
 ↓
ViewModel
 ↓
Use case/repository
 ↓
RNS/LXMF abstraction
 ↓
backend
 ↓
RNode
```

Document the exact classes and methods involved.

Find the smallest public/internal API needed to do something equivalent to:

```text
sendMessage(
    destination = knownIdentity,
    text = "Hello"
)
```

### Receiving

Trace:

```text
RNode
 ↓
RNS/LXMF backend
 ↓
repository/domain
 ↓
ViewModel/state
 ↓
UI
```

Find the observable/state flow/callback through which received LXMF messages become visible.

---

## 12. Do Not Strip Columba Prematurely

Initially:

1. Clone Columba.
2. Build the normal project.
3. Confirm it launches.
4. Confirm current RNode BLE communication still works.
5. Identify the send path.
6. Identify the receive path.
7. Add a **new minimal demo screen**.
8. Make that screen send/receive real LXMF.
9. Only after that, remove/hide unwanted existing UI.

Do not start by deleting large parts of the application.

---

## 13. Desired Visual Design

Both APKs should share approximately the same structure.

Example Meshtastic home:

```text
┌────────────────────────────────┐
│ RESILIENT COMMS LAB            │
│                                │
│ MESHTASTIC                     │
│                                │
│ ● Radio connected              │
│   MESH-ALPHA                   │
│                                │
│ Communicate without Internet   │
│                                │
│       [ START ]                │
└────────────────────────────────┘
```

Reticulum equivalent:

```text
┌────────────────────────────────┐
│ RESILIENT COMMS LAB            │
│                                │
│ RETICULUM                      │
│                                │
│ ● RNode connected              │
│   RET-ALPHA                    │
│                                │
│ Communicate without Internet   │
│                                │
│       [ START ]                │
└────────────────────────────────┘
```

---

## 14. Message Screen

Meshtastic example:

```text
┌────────────────────────────────┐
│ MESHTASTIC                     │
│                                │
│ Send a message                 │
│                                │
│ ┌────────────────────────────┐ │
│ │ Hello from Bitcoin Asia    │ │
│ └────────────────────────────┘ │
│                                │
│          [ SEND ]              │
└────────────────────────────────┘
```

Reticulum example:

```text
┌────────────────────────────────┐
│ RETICULUM                      │
│                                │
│ Send to                        │
│                                │
│ ● RET-BRAVO                    │
│ ● BASE STATION                 │
│                                │
│ ┌────────────────────────────┐ │
│ │ Hello                      │ │
│ └────────────────────────────┘ │
│                                │
│          [ SEND ]              │
└────────────────────────────────┘
```

---

## 15. After Send

Show an educational visualization.

Meshtastic:

```text
Phone M1
   ↓ Bluetooth
LILYGO LT1
   ↓ LoRa
LILYGO LT2
   ↓ Bluetooth
Phone M2

✓ MESSAGE RECEIVED
```

Reticulum:

```text
Your Identity
      ↓
Reticulum
      ↓
Destination / path
      ↓
RET-BRAVO

✓ MESSAGE RECEIVED
```

These visualizations do not initially need to be a literal real-time packet tracer.

They may represent actual application state such as:

- radio connected
- sending
- packet submitted
- ACK received
- destination received/replied

Do not fabricate states that the protocol/API cannot actually confirm.

---

## 16. Station Roles

Each installation should have a fixed station role.

For example:

```kotlin
enum class StationRole {
    MESH_A,
    MESH_B,
    RET_A,
    RET_B
}
```

Or use build flavors/configuration.

Example:

```text
MESH_A
Node name: MESH-ALPHA
Radio: LT1

MESH_B
Node name: MESH-BRAVO
Radio: LT2

RET_A
Identity: RET-ALPHA
Radio: LT3

RET_B
Identity: RET-BRAVO
Radio: Heltec
```

Avoid user-editable station configuration in the public UI.

---

## 17. Important UX Requirement

Participants should not be able to accidentally break the station.

Ideally hide or prevent access to:

- radio configuration
- frequency/region settings
- BLE device selection after setup
- Reticulum interface configuration
- identity deletion
- Meshtastic channel editing
- Android navigation where practical

Do not implement kiosk/device-owner complexity in phase 1 unless trivial.

First make messaging reliable.

---

## 18. Pairing Strategy

For development, manual BLE pairing/configuration is acceptable.

For conference deployment, the goal is that each phone automatically reconnects to its assigned radio.

Example:

```text
M1 → LT1
M2 → LT2
R1 → LT3
R2 → Heltec
```

Persist whichever identifiers/addresses the respective libraries use.

If Android BLE MAC randomization/device identifiers make this unsuitable, document the actual mechanism supported by each stack.

---

## 19. No Internet Requirement

The demo must work with:

```text
Wi-Fi OFF
Cellular OFF
```

Bluetooth remains ON.

The app itself must not require a backend server or Internet connection.

Do not introduce Firebase, cloud databases, REST APIs, analytics SDKs, or remote dependencies at runtime.

Build-time Maven/Gradle downloads are fine.

---

## 20. Initial Scope

Do NOT overbuild.

Version 1 only needs:

### Meshtastic

```text
connect
send
receive
show sender/message
reset demo
```

### Reticulum

```text
connect to configured RNode
load configured identity
send LXMF to known destination
receive LXMF
show sender/message
reset demo
```

Everything else is secondary.

---

## 21. Development Order

Use this order.

### Phase A — Repository reconnaissance

Inspect:

```text
meshtastic/meshtastic-sdk
torlando-tech/columba
```

Provide:

1. module structure
2. Android minimum versions
3. build tooling
4. exact Meshtastic BLE classes
5. exact Meshtastic send/receive methods
6. exact Columba send path
7. exact Columba receive path
8. exact Columba RNode/BLE implementation
9. dependency/license constraints

Do not guess.

### Phase B — Meshtastic proof of concept

Create the smallest Android application capable of:

```text
Phone
 ↓ BLE
Meshtastic node
 ↓ LoRa
another Meshtastic node
```

Success criteria:

1. app detects/connects to paired LT1
2. screen says `Connected`
3. tap button:
   `Send test message`
4. LT2/M2 receives:
   `CODEX TEST`
5. inbound message can also be displayed by custom app

No polished UI yet.

### Phase C — Reticulum proof of concept

Prefer building from/forking Columba.

Add a minimal screen:

```text
RNode: Connected

Destination:
RET-BRAVO

Message:
[ CODEX TEST ]

[ SEND ]
```

Success criteria:

```text
R1
 ↓
LT3
 ↓ LoRa
Heltec
 ↓
R2
```

and custom UI on R2 shows the LXMF message.

Also test:

```text
R1 → B2 laptop
```

using the existing Reticulum setup.

### Phase D — Shared visual language

After both transports work:

- extract common design elements
- create same typography/layout
- same START/SEND/RECEIVED/reset flow
- protocol-specific explanation screens

Do not force transport implementations into a shared abstraction unless it actually simplifies development.

A shallow interface such as:

```kotlin
interface DemoMessenger {
    val state: StateFlow<DemoState>
    suspend fun send(...)
}
```

may be reasonable.

But do not spend time creating an elaborate clean architecture.

---

## 22. Useful State Machine

Consider something like:

```text
BOOTING
   ↓
RADIO_CONNECTING
   ↓
READY
   ↓
COMPOSING
   ↓
SENDING
   ↓
SENT
   ↓
RECEIVED / ACKNOWLEDGED
   ↓
EXPLANATION
   ↓
RESET
```

Also support:

```text
RADIO_DISCONNECTED
ERROR
```

These need clear recovery actions.

Example:

```text
Radio disconnected

Check that the attached radio is powered.

[ RETRY ]
```

---

## 23. Demo Reset

This is important at a conference.

After approximately one participant interaction, the app should easily return to the beginning.

Provide a prominent final button:

```text
[ START OVER ]
```

Potentially auto-reset after an inactivity timeout later.

Do not implement auto-reset until messaging is stable.

---

## 24. Logging

During development, log enough information to diagnose:

- BLE connection
- selected radio
- Meshtastic packet ID
- send result
- received packet
- sender node
- Reticulum destination hash
- LXMF delivery state
- RNode state

Use standard Android logging.

Do not expose verbose diagnostics in public UI.

A hidden/debug screen is fine later.

---

## 25. Repository Strategy

Do not modify upstream repositories directly without preserving history.

Recommended workspace:

```text
resilient-comms-demo/
    meshtastic-demo/
    reticulum-demo/
    docs/
```

For Reticulum, if a fork is required:

```text
reticulum-demo/
    <Columba fork>
```

Keep upstream remote configured.

Example:

```bash
git remote -v
```

should ultimately show a fork/origin and upstream where appropriate.

---

## 26. Licensing

Before copying Columba source or Meshtastic code:

- inspect repository licenses
- document obligations
- retain required notices
- identify whether distribution of conference APKs imposes any source/license requirements

Do not postpone this until the end.

---

## 27. Avoid These Approaches

Do NOT:

1. Use Android Intent to merely open the standard Meshtastic app.
2. Use Android Intent to merely open Columba.
3. Implement Reticulum from scratch.
4. Reimplement Meshtastic protobuf/BLE if official SDK already solves it.
5. Create a cloud backend.
6. Make the participant pair radios.
7. Expose radio configuration.
8. Attempt dynamic switching between Meshtastic and Reticulum on one phone.

---

## 28. Key Architectural Principle

The physical radio determines the network.

```text
M1 + LT1 = Meshtastic

M2 + LT2 = Meshtastic

R1 + LT3 = Reticulum

R2 + Heltec = Reticulum
```

The app is only an **experience/control layer** over that fixed network.

---

## 29. What the Participant Should Learn

The app is educational.

Meshtastic should communicate roughly:

```text
Phone
  ↓ Bluetooth
LoRa node
  ↓
shared Meshtastic channel
  ↓
other LoRa nodes
```

Reticulum should communicate roughly:

```text
Phone identity
  ↓
RNode
  ↓
Reticulum network
  ↓
destination identity
```

Do not claim stronger architectural distinctions than the protocols actually guarantee.

Keep explanations technically accurate.

---

## 30. Laptop Nodes

The laptop nodes are secondary but useful.

### Meshtastic B1

Potential roles:

- third receiver in shared channel
- logging node
- eventual dashboard input

### Reticulum B2

Potential roles:

- separate LXMF destination
- automated response destination
- logging / Reticulum status

Do not make laptop dashboard development part of the first milestone.

---

## 31. First Deliverable Expected From Codex

Before writing large amounts of code, produce a concise technical reconnaissance report containing:

```text
MESHTASTIC

Repository:
SDK/module:
BLE transport:
Connection API:
Send API:
Receive API:
State APIs:
Minimum Android:
Risks:


COLUMBA / RETICULUM

Repository:
Relevant modules:
UI → send call chain:
receive → UI call chain:
RNode BLE implementation:
Identity storage:
LXMF send API:
LXMF receive API:
Python/Kotlin backend status:
Minimum Android:
Risks:


RECOMMENDED IMPLEMENTATION PLAN

Meshtastic:
...

Reticulum:
...

Files to create/modify first:
...
```

Use exact paths, classes and function names from the repositories.

Do not rely on assumptions from this handoff where the current source code can answer the question.

---

## 32. Then Start With Meshtastic

Unless repository inspection reveals an unexpected blocker, implementation order should be:

```text
1. Meshtastic minimal Android project
2. BLE connect
3. Send CODEX TEST
4. Receive message
5. Commit
6. Reticulum/Columba build
7. Trace LXMF send path
8. Add minimal custom Reticulum screen
9. Send CODEX TEST
10. Receive message
11. Commit
```

Make small, reviewable commits.

Suggested commit style:

```text
meshtastic: add BLE connection proof of concept

meshtastic: add text send and receive

columba: document LXMF message path

columba: add minimal demo messaging screen
```

---

## 33. Definition of First Major Milestone

We are done with the first major milestone when all of the following work:

```text
M1 custom app
  ↓
LT1
  ↓ LoRa
LT2
  ↓
M2 custom app
```

and:

```text
R1 custom app
  ↓
LT3
  ↓ LoRa
Heltec
  ↓
R2 custom app
```

with:

- Wi-Fi off
- cellular off
- BLE on
- no stock Meshtastic UI required
- no stock Columba messaging UI required
- real messages sent over LoRa
- messages visible in our controlled UI

Only after that should we spend substantial effort on the polished participant experience.

---

## 34. Git Setup and Repository Workflow

Set up Git before making code changes.

The goals are:

- preserve clean upstream history
- isolate Meshtastic and Reticulum work
- make it easy to review/revert experiments
- keep the Columba fork synchronized with upstream
- avoid large unreviewable commits

### 34.1 Recommended top-level workspace

Use a parent workspace such as:

```text
resilient-comms-demo/
├── meshtastic-demo/
├── reticulum-demo/
└── docs/
```

The parent directory may itself be a Git repository if useful for shared documentation and scripts, but do **not** accidentally nest unrelated Git repositories and then commit their `.git` internals.

A simple approach is:

```bash
mkdir -p resilient-comms-demo
cd resilient-comms-demo
mkdir docs
```

---

### 34.2 Meshtastic Demo Git Setup

The Meshtastic demo should ideally be its own fresh repository.

Example:

```bash
cd resilient-comms-demo

mkdir meshtastic-demo
cd meshtastic-demo

git init
git branch -M main
```

Create the Android project here.

Add an appropriate `.gitignore` before the first commit.

At minimum, Android/Gradle exclusions should cover items such as:

```text
.gradle/
.idea/
local.properties
build/
**/build/
*.iml
.DS_Store
captures/
.externalNativeBuild/
.cxx/
```

Do not commit:

- signing keystores
- API tokens
- local SDK paths
- generated build output
- IDE caches
- device-specific secrets

Initial commit:

```bash
git add .
git commit -m "chore: initialize meshtastic demo app"
```

If a remote repository has already been created:

```bash
git remote add origin <YOUR_MESHTASTIC_DEMO_REPO_URL>
git push -u origin main
```

Do not add `meshtastic/meshtastic-sdk` itself as an `upstream` remote unless Codex explicitly decides to vendor or fork that repository.

Prefer consuming the SDK as a dependency.

If the SDK must temporarily be checked out locally for inspection, keep it outside the app repository or use a clearly documented sibling directory.

---

### 34.3 Reticulum / Columba Git Setup

For Reticulum, preserve the Columba history.

Preferred model:

```text
YOUR FORK
   ↑
 origin

UPSTREAM COLUMBA
   ↑
 upstream
```

Clone the user's fork if one exists:

```bash
cd resilient-comms-demo

git clone <YOUR_COLUMBA_FORK_URL> reticulum-demo
cd reticulum-demo
```

Then add the official Columba repository as `upstream`:

```bash
git remote add upstream https://github.com/torlando-tech/columba.git
```

Verify:

```bash
git remote -v
```

Expected shape:

```text
origin    <YOUR_COLUMBA_FORK_URL> (fetch)
origin    <YOUR_COLUMBA_FORK_URL> (push)
upstream  https://github.com/torlando-tech/columba.git (fetch)
upstream  https://github.com/torlando-tech/columba.git (push)
```

Do not push to `upstream`.

Fetch current upstream state before development:

```bash
git fetch upstream
git fetch origin
```

Inspect upstream's default branch:

```bash
git remote show upstream
```

If upstream uses `main`, synchronize the local base branch:

```bash
git switch main
git pull --ff-only origin main
git merge --ff-only upstream/main
git push origin main
```

If a fast-forward is not possible, stop and inspect why rather than creating an unnecessary merge commit.

---

### 34.4 Never Develop Directly on `main`

Use short-lived feature branches.

Examples:

```text
codex/meshtastic-ble-poc
codex/meshtastic-send-receive
codex/columba-recon
codex/columba-demo-screen
codex/shared-demo-ui
```

Example:

```bash
git switch -c codex/columba-recon
```

For Meshtastic:

```bash
git switch -c codex/meshtastic-ble-poc
```

The exact `codex/` prefix is not mandatory, but use a consistent naming convention.

---

### 34.5 Commit Discipline

Prefer small commits that each do one understandable thing.

Good examples:

```text
docs: document meshtastic sdk integration points

meshtastic: add ble connection proof of concept

meshtastic: add text send support

meshtastic: display received text packets

columba: document lxmf send and receive path

columba: add minimal conference demo screen

columba: route incoming lxmf messages to demo state

ui: add shared resilient comms visual language
```

Avoid commits like:

```text
update stuff

working version

many fixes

final
```

Before each commit:

```bash
git status
git diff
```

Then stage intentionally:

```bash
git add <specific-files>
git diff --cached
git commit -m "<clear commit message>"
```

Do not routinely use:

```bash
git add .
```

without first checking what will be included.

---

### 34.6 Preserve Known-Good Milestones

Tag or branch known-good hardware milestones.

Examples:

```bash
git tag meshtastic-poc-v1
git tag reticulum-poc-v1
```

or:

```text
milestone/meshtastic-poc
milestone/reticulum-poc
```

Useful milestone definitions:

#### Meshtastic POC

```text
custom app
→ BLE
→ LT1
→ LoRa
→ LT2
→ custom app
```

#### Reticulum POC

```text
custom UI
→ Columba/RNS stack
→ LT3
→ LoRa
→ Heltec
→ custom UI
```

Push tags when appropriate:

```bash
git push origin --tags
```

---

### 34.7 Keeping Columba in Sync

Before starting a new Reticulum feature branch:

```bash
git fetch upstream

git switch main
git merge --ff-only upstream/main
git push origin main
```

Then create the new feature branch:

```bash
git switch -c codex/<feature-name>
```

If upstream changes during active work, prefer rebasing the feature branch when the branch is local/private:

```bash
git fetch upstream
git rebase upstream/main
```

Do not rewrite history on branches already shared with others unless explicitly coordinated.

---

### 34.8 Meshtastic SDK Version Pinning

Do not track an unspecified moving SDK version.

Once the first working SDK revision/version is identified, pin it.

Document:

```text
Meshtastic SDK version:
Meshtastic SDK commit:
Date tested:
Android version tested:
LT1 firmware version:
LT2 firmware version:
```

If using a Maven artifact, pin the dependency version in Gradle.

If using a Git checkout or composite build temporarily, pin the commit hash in documentation.

Do not silently move to a newer SDK during the conference stabilization phase.

---

### 34.9 Columba Revision Pinning

Record the exact Columba baseline before modifications.

Run:

```bash
git rev-parse HEAD
git log -1 --oneline
```

Add the result to:

```text
docs/BUILD_BASELINE.md
```

That file should eventually contain something like:

```text
Columba upstream commit:
Columba branch:
Reticulum backend:
Reticulum version:
LXMF version:
Chaquopy version:
Android version tested:
RNode firmware version:
```

This matters because the working Reticulum setup should remain reproducible.

---

### 34.10 Recommended Documentation Files

Create:

```text
docs/
├── ARCHITECTURE.md
├── BUILD_BASELINE.md
├── HARDWARE_MAP.md
├── TEST_MATRIX.md
└── CODEX_NOTES.md
```

Suggested purposes:

#### `ARCHITECTURE.md`

Document:

```text
M1 → LT1 → Meshtastic
M2 → LT2 → Meshtastic
R1 → LT3 → Reticulum
R2 → Heltec → Reticulum
B1 → XIAO → Meshtastic
B2 → XIAO → Reticulum
```

and the app architecture.

#### `BUILD_BASELINE.md`

Pin:

- upstream commits
- dependency versions
- Android/Gradle versions
- firmware versions

#### `HARDWARE_MAP.md`

Record:

- phone role
- radio role
- BLE name/address or stable identifier
- firmware
- node name/identity

Do not put private cryptographic secrets in this file.

#### `TEST_MATRIX.md`

Track tests such as:

```text
[ ] M1 connects to LT1
[ ] M2 connects to LT2
[ ] M1 → M2 message succeeds
[ ] M2 → M1 message succeeds
[ ] Meshtastic works with Wi-Fi off
[ ] R1 connects to LT3
[ ] R2 connects to Heltec
[ ] R1 → R2 LXMF succeeds
[ ] R2 → R1 LXMF succeeds
[ ] R1 → B2 succeeds
[ ] Reticulum works with Wi-Fi off
```

#### `CODEX_NOTES.md`

Codex should record:

- discoveries
- unresolved questions
- exact source paths examined
- temporary workarounds
- risks
- next steps

Keep this updated rather than relying only on conversational context.

---

### 34.11 Branch Before Risky Experiments

Before modifying substantial Columba internals:

```bash
git status
git commit
```

Then create a branch:

```bash
git switch -c experiment/<name>
```

Examples:

```text
experiment/columba-python-api-direct
experiment/rnode-reconnect
experiment/kiosk-mode
```

If the experiment fails, discard the branch rather than polluting the main feature branch.

---

### 34.12 Do Not Commit Generated APKs by Default

APK/AAB files should normally remain build artifacts.

Do not commit:

```text
*.apk
*.aab
```

to the source repository unless there is a deliberate release-artifact policy.

Use GitHub Releases or another artifact location later if needed.

For local testing, record the exact commit from which an APK was built.

---

### 34.13 Release / Conference Stabilization Branch

Once both transports work and UI polishing begins, create a stabilization branch:

```bash
git switch -c release/conference-demo
```

During stabilization:

- no dependency upgrades unless necessary
- no Reticulum backend migration
- no Meshtastic SDK upgrade without retesting
- no major architecture refactor
- only bug fixes, UX fixes, and reliability fixes

Before generating conference APKs, tag the exact build:

```bash
git tag conference-demo-v1
git push origin conference-demo-v1
```

If further fixes are required:

```text
conference-demo-v1.1
conference-demo-v1.2
```

---

### 34.14 Codex Git Rules

Codex must:

1. Run `git status` before changing files.
2. Identify the current branch.
3. Never assume it is safe to work directly on `main`.
4. Create a feature branch for each significant task.
5. Review `git diff` before committing.
6. Keep commits small and focused.
7. Never commit secrets, keystores, credentials, or `local.properties`.
8. Never force-push unless explicitly instructed.
9. Never rewrite upstream history.
10. Never push to the official Columba or Meshtastic upstream repositories.
11. Record important dependency/upstream commit hashes.
12. Keep the tree clean at meaningful milestones.

At the end of each implementation phase, report:

```text
Branch:
HEAD commit:
Working tree status:
Commits added:
Files changed:
Tests performed:
Known issues:
```

This Git discipline is part of the deliverable, not optional housekeeping.

