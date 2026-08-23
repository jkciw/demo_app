# Meshtastic Bitcoin Regtest Demo

[Download the latest Android APK](https://github.com/jkciw/demo_app/releases/latest/download/Meshtastic-Conference-Demo.apk)

Current conference build: **v0.2.0 — BLE reliability**. The release page includes a plain-language
change summary and a SHA-256 checksum alongside the universal APK.

This is the complete application package. Android offers **Install** on a new phone and **Update**
when the same signing identity is already installed; an update preserves the app's existing radio,
identity, and transaction-queue state.

This repository contains the two coordinated parts of the conference demo:

- `meshtastic-demo/` — One universal Android application with runtime Alice/Bob
  station roles and independent bundled Bitcoin regtest transaction queues.
- `laptop-dashboard/` — Serial gateway, Bitcoin Core regtest relay, and browser
  dashboard used by the laptop-connected Meshtastic node.

## Repository layout

```text
demo_app/
├── meshtastic-demo/     Universal Android application
├── laptop-dashboard/    Gateway, regtest tooling, and dashboard
├── docs/                Project notes and technical documentation
└── README.md             Repository overview
```

Project-specific setup and operating instructions live in each project's own
`README.md`.

Development uses signed commits, a conference-ready `main` branch, short-lived feature branches,
and an automated universal APK build and release. See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the
exact Git, verification, checkpoint tagging, and release workflow.

Generated APKs, build directories, dependencies, local environment files,
Bitcoin regtest data, gateway state, and logs are intentionally excluded from
Git.

## License

This project is distributed under the [GNU General Public License v3.0 or later](LICENSE).
