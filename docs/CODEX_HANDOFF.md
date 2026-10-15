# Codex handoff — next implementation plan

Repository: Mhd-Azeem/AZ-RemoteConnect. Work on a new feature branch based on the initial foundation. Preserve all existing scaffold files.

1. Validate the existing GitHub Actions Android APK and Windows EXE builds; fix any compile errors before adding features.
2. Add stable device identities, connection approval UI and mutually authenticated pairing. Do not trust human-readable IDs as secrets.
3. Deliver an actually functional Windows-to-Windows LAN session: native screen capture, bounded streaming, keyboard/mouse input only after host approval, visible session indicators, disconnect and clean teardown.
4. Add reliability tests for disconnect, denied requests and unauthorized commands.
5. Implement Android viewer, then Android MediaProjection host with consent, then explicitly enabled AccessibilityService input. Check Android 15 limitations and foreground service types.
6. Introduce WebRTC signaling with authenticated WSS, STUN/TURN and network-change handling. Do not deploy until environment secrets and security review are configured.
7. Add file transfer, clipboard, quality controls, updater, repeatable code signing, release workflow and integration tests.

Mandatory: never claim working screen sharing merely because starter apps compile. Do not add stealth, hidden monitoring, consent bypass or static default passwords. Include documented manual tests and link to workflow artifacts for each milestone.
