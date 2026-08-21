# Git workflow

`main` is the last conference-ready state. Normal development happens on a short-lived branch,
passes the Android workflow, and is merged back only after hardware validation when the change
touches Bluetooth, Meshtastic transport, or transaction relay behavior.

## Start a change

```shell
git switch main
git pull --ff-only
git switch -c feat/short-description
```

Use `fix/`, `feat/`, or `docs/` followed by a short hyphenated description. Keep unrelated laptop,
Android, and Reticulum work in separate commits where practical.

## Verify and sign

For Android changes:

```shell
cd meshtastic-demo
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
cd ..
git diff --check
git status --short
git add <files>
git diff --cached --stat
git diff --cached --check
git commit -S -m "Describe the completed change"
git log --show-signature -1
```

Push the branch and open a pull request:

```shell
git push -u origin HEAD
```

For a time-critical, already hardware-tested conference fix, a signed commit may be made directly
on `main`; run the same verification commands first.

## Conference checkpoints

Create a signed annotated tag only for a build that has been exercised on the physical phones,
radios, serial gateway, dashboard, and Bitcoin Regtest node:

```shell
git switch main
git pull --ff-only
git tag -s meshtastic-v0.1.0 -m "Meshtastic conference checkpoint v0.1.0"
git push origin main meshtastic-v0.1.0
```

Increment the tag for later checkpoints. Never move or replace a published checkpoint tag.

## Remote APK builds

The `Android APK` GitHub Actions workflow runs unit tests, lint, and the universal debug build on
pull requests. Pushes to `main`, matching tags, and manual runs also publish a downloadable APK and
SHA-256 file for 30 days.

Mainline artifacts deliberately require the repository secret
`ANDROID_DEBUG_KEYSTORE_BASE64`. It must contain the Mac's existing Android debug keystore so a
remote artifact has the same signing identity and can update conference phones without uninstalling
the app. Copy the encoded key directly to the clipboard without printing it:

```shell
base64 -i "$HOME/.android/debug.keystore" | pbcopy
```

In `github.com/jkciw/demo_app`, open **Settings → Secrets and variables → Actions**, create the
repository secret `ANDROID_DEBUG_KEYSTORE_BASE64`, and paste the clipboard value. Treat that secret
as a conference-demo signing credential. If the local debug keystore changes, remote APKs will no
longer update installations signed with the previous key.
