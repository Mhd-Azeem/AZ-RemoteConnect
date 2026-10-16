# AZ RemoteConnect v0.5 preview

## Android dashboard and session interface
The blue Home dashboard shows a small live thumbnail (not a gesture surface).
After the remote host accepts pairing, AZ RemoteConnect opens a dedicated remote
session page. Session controls include Screen Touch or Touchpad, fullscreen,
auto-rotation, a keyboard text field, special keys and shortcuts, scrolling,
PC audio control, and a route back to Home without dropping the session.

## Device manager / history
Choose **Devices** in Android navigation. Each successfully paired device is
saved locally by IP address with last connection time and connection count.
Rename or forget entries; choose Connect to reuse the address.

**Pairing codes are never saved.** Enter the host's current code and accept
the request at the host for every new session. Trusted devices are optional:
while connected, choose Settings > Trust connected device and confirm the full
SHA-256 fingerprint displayed in the dialog. On later connections the complete
saved fingerprint is checked; a mismatch blocks the connection rather than
silently trusting a new certificate. Windows host certificates are currently
ephemeral; restarting the Windows host may legitimately change its fingerprint,
requiring you to forget that old trust entry and re-pair after independently
verifying the new host.

## Advanced mouse / keyboard
Touchpad drags the relative pointer; a tap left-clicks, a double tap
double-clicks, a stationary long hold followed by movement can drag, and a
two-finger slide scrolls. Screen Touch uses absolute coordinates and can drag.
Keyboard accepts Unicode text and special keys via the Windows input API.
The Windows secure-attention sequence (Ctrl+Alt+Delete) is not supported.

## Reconnection
Unexpected loss of an approved session triggers a bounded reconnect attempt
with up to three retries. Authentication or denial errors never trigger
automatic retries. **Every reconnect still requires the host's approval.**
If the host pairing code changes, re-enter the new one.

## Performance
The Windows host's JPEG stream now offers Smooth (target ~30 fps / max long
edge 1280px), Balanced (~20 fps / 1600px), and Data Saver (~12 fps / 960px)
profiles. CPU, audio activity, hotspot conditions, and image complexity
determine actual delivered frame rates. The host streams system playback
audio using WASAPI loopback and Android plays mono PCM16; stable low-latency
synchronization and hardware H.264 encoding are not implemented in this
preview. These are targets, not measured frame-rate guarantees.

## Security / scope
The Android Viewer APK can control Windows and view an Android-hosted screen,
but it does **not** request Accessibility access or support Android remote
touch as the controlled phone. Android remote touch requires the separately
consented full edition and may need Play Store review.
Direct LAN/VPN access only; never forward port 48765 to the public internet.
A first-use short certificate code is only 24 bits of verification: compare
host codes over a trusted channel, and explicitly trust a full fingerprint
for repeat connections. Pairing and approval remain mandatory.

GitHub Actions passing only confirms compilation, not device-to-device
performance, Play Protect approval, or security certification.
