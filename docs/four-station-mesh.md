# Four-station Meshtastic development track

This branch expands the proven v0.3.3 conference baseline without changing the
stable `main` release until the complete five-radio topology can be tested
together.

## Target hardware

| Logical role | Hardware | Host connection | Visitor-facing |
| --- | --- | --- | --- |
| Alice | Android phone + LilyGo | BLE | Yes |
| Bob | Android phone + LilyGo | BLE | Yes |
| Charlie | Android phone + LilyGo | BLE | Yes |
| Dana | Android phone + LilyGo | BLE | Yes |
| Gateway | Seeed XIAO | Laptop USB serial | Yes, as the only active Gateway node |

The Gateway is the only laptop-connected radio. It transmits presence, Bitcoin
slot grants, chunk acknowledgements, results, and service responses. A second
XIAO may be kept powered off as a preconfigured physical spare, but is not part
of the live topology.

The app and collector do not rewrite radio firmware settings. Before the
five-radio acceptance run, use the Meshtastic web client to configure all four
LilyGo radios, the active Gateway XIAO, and the spare XIAO with the same primary
channel, `ShortFast` modem preset, and `TW` region. The dashboard then provides
an independent check of the active Gateway's reported preset and region.

## Branch and release policy

- `main` remains the hardware-proven stable release.
- `four-station-mesh` receives the four-phone and ShortFast optimization work.
- Every branch push runs tests and uploads a private Actions artifact.
- A public branch APK is published only through a manual **preview** workflow.
- Preview releases are GitHub prereleases and never replace the stable `latest`
  release.
- Preview `versionName` values must include a qualifier such as
  `0.4.0-beta.1`.

Run a build-only workflow from GitHub Actions, or with:

```sh
gh workflow run android-apk.yml \
  --ref four-station-mesh \
  -f release_channel=artifact
```

Publish a public preview only when the branch contains a prerelease Android
version:

```sh
gh workflow run android-apk.yml \
  --ref four-station-mesh \
  -f release_channel=preview
```

## Current preview status

The `0.4.0-beta.1` software preview now includes all four phone identities,
three direct-message contacts per phone, four staggered presence slots, and 20
fresh independently funded Regtest transactions per identity. It remains
compatible with the single-Gateway collector. Bitcoin frames use two
200-character chunks for the current 191-byte transactions, and Gateway replies
are paced at one-second intervals for the faster conference preset.

The complete five-radio acceptance run remains pending until the additional
phone hardware is available. That run is a merge gate, not an assumption made
by the preview software.

## Required software behaviour

1. One universal APK supports Alice, Bob, Charlie, and Dana.
2. Each identity owns an independent presigned Regtest transaction queue.
3. Presence frames are staggered across the four phones and Gateway.
4. The collector accepts exactly one Meshtastic Gateway serial port.
5. The dashboard reports the preset and region read from that radio.
6. Signed transactions use two chunks under the current Regtest bundle.
7. Gateway replies are paced for ShortFast without removing retry protection.
8. The second XIAO remains an offline physical spare.

## Hardware acceptance gate

Do not merge this branch into `main` until the additional hardware is present
and all of these checks pass:

- Direct-message ring: Alice → Bob → Charlie → Dana → Alice.
- One broadcast from each phone appears once on the dashboard.
- The dashboard reports `ShortFast / TW` from the connected Gateway.
- Four concurrent Bitcoin requests complete in FIFO order.
- Each current 191-byte transaction completes in two chunks.
- The spare XIAO can replace the Gateway through a cable swap and dashboard restart.
- All four phone BLE links remain stable with screens off for at least 30
  minutes.

If any acceptance check fails, keep v0.3.3 on `main` as the conference fallback.
