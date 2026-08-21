# Meshtastic Bitcoin Regtest Demo

This repository contains the two coordinated parts of the conference demo:

- `meshtastic-demo/` — One universal Android application with runtime Alpha/Bravo
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
and an automated universal APK build. See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the exact Git,
verification, checkpoint tagging, and remote artifact workflow.

Generated APKs, build directories, dependencies, local environment files,
Bitcoin regtest data, gateway state, and logs are intentionally excluded from
Git.
