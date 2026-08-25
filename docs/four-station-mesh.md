# Four-station Meshtastic development track

This branch expands the proven v0.3.3 conference baseline without changing the
stable `main` release until all six radios can be tested together.

## Target hardware

| Logical role | Hardware | Host connection | Visitor-facing |
| --- | --- | --- | --- |
| Alice | Android phone + LilyGo | BLE | Yes |
| Bob | Android phone + LilyGo | BLE | Yes |
| Charlie | Android phone + LilyGo | BLE | Yes |
| Dana | Android phone + LilyGo | BLE | Yes |
| Gateway | Seeed XIAO | Laptop USB serial | Yes, as one logical Gateway node |
| Observer | Seeed XIAO | Laptop USB serial | No; reception diversity and manual backup |

The Gateway is the only receiver allowed to transmit presence, Bitcoin slot
grants, chunk acknowledgements, results, and service responses. The Observer
feeds received packets to the collector but remains silent unless an operator
explicitly promotes it.

## Branch and release policy

- `main` remains the hardware-proven stable release.
- `four-station-mesh` receives the four-phone and dual-receiver work.
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

## Required software behaviour

1. One universal APK supports Alice, Bob, Charlie, and Dana.
2. Each identity owns an independent presigned Regtest transaction queue.
3. Presence frames are staggered across the four phones and Gateway.
4. The collector accepts one or two Meshtastic serial ports.
5. Packets heard by both XIAOs appear only once in the dashboard.
6. Only the primary Gateway transmits protocol replies.
7. Observer promotion is manual and visible only to the operator.
8. The existing one-Gateway topology remains fully functional.

## Hardware acceptance gate

Do not merge this branch into `main` until the additional hardware is present
and all of these checks pass:

- Direct-message ring: Alice → Bob → Charlie → Dana → Alice.
- One broadcast from each phone appears once on the dashboard.
- Both XIAOs hear common traffic without duplicate dashboard entries.
- Four concurrent Bitcoin requests complete in FIFO order.
- Removing the primary Gateway is visible to the operator.
- Promoting the Observer restores messaging and a new Bitcoin relay.
- All four phone BLE links remain stable with screens off for at least 30
  minutes.

If any acceptance check fails, keep v0.3.3 on `main` as the conference fallback.
