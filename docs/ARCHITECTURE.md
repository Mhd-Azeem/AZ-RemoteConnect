# Architecture

## Applications
- Windows WPF host/viewer, native screen capture and input after explicit session authorization.
- Android host/viewer; MediaProjection consent and user-enabled AccessibilityService for control.
- Signaling: TLS WebSocket auth, pairing, session lifecycle, abuse protections.
- Transport: WebRTC media/data over authenticated encrypted connections; STUN/TURN as required.

## Delivery strategy
Start Windows-to-Windows LAN with authenticated pairing. Then Android viewer and host, followed by internet signaling and TURN. An ephemeral GitHub Actions runner is NOT an internet signaling server. Real production infrastructure is deferred until configured.

## Non-goals for foundation
No functional remote connections; no silent screen capture; no embedded static access credentials; no accessibility-service bypass; no unattended Android access claims.
