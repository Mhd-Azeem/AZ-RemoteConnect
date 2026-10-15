# AZ RemoteConnect

Cross-platform remote access software for Android and Windows.

> Status: foundation scaffold. Remote control, streaming, and session authentication are **not yet implemented**. Do not use for real remote access.

## Targets

- Android APK (Kotlin, Jetpack Compose)
- Standalone Windows x64 EXE (.NET 8 WPF)
- Signaling service (Node.js + TypeScript)
- Shared protocol, configuration, and documentation
- GitHub Actions CI for source checks and platform builds

## Intended connection directions

Android → Windows, Windows → Android, Android → Android, Windows → Windows.

## Safety requirements

Every remote session must be authenticated and authorized. Android screen capture requires user consent; Android remote input requires a user-enabled accessibility service. No stealth installation, silent capture, or approval bypass. Credentials must never be committed to the repository.

## Development milestones

1. Scaffold and CI (initial phase)
2. Authenticated Windows-to-Windows LAN proof of concept
3. Android capture/viewer and explicitly enabled remote input
4. Signaling, WebRTC, STUN/TURN and secure internet access
5. Transfers, quality controls, signed updates and release hardening

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/CODEX_HANDOFF.md](docs/CODEX_HANDOFF.md), and [docs/SECURITY.md](docs/SECURITY.md).
