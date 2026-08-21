# Meshtastic Bitcoin Regtest Demo

This repository contains the two coordinated parts of the conference demo:

- `meshtastic-demo/` — Android Alpha and Bravo applications that send bundled,
  pre-signed Bitcoin regtest transactions over Meshtastic.
- `laptop-dashboard/` — Serial gateway, Bitcoin Core regtest relay, and browser
  dashboard used by the laptop-connected Meshtastic node.

## Repository layout

```text
demo_app/
├── meshtastic-demo/     Android applications
├── laptop-dashboard/    Gateway, regtest tooling, and dashboard
├── docs/                Project notes and technical documentation
└── README.md             Repository overview
```

Project-specific setup and operating instructions live in each project's own
`README.md`.

Generated APKs, build directories, dependencies, local environment files,
Bitcoin regtest data, gateway state, and logs are intentionally excluded from
Git.
