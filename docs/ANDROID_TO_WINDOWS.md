# Android phone → Windows laptop (LAN preview)

1. Install and open the Windows AZ RemoteConnect host executable on the laptop.
2. Press **Start hosting**. Note the six-digit code and **full SHA-256 TLS certificate fingerprint**; share them only directly between your own devices.
3. Find the Windows laptop's local IP address with `ipconfig` (typically an IPv4 address like 192.168.1.25). Connect both devices to the same trusted Wi-Fi or hotspot network. Windows Firewall must allow inbound TCP **48765** on Private networks only.
4. Open AZ RemoteConnect on Android. Enter the IP, six-digit code, and full fingerprint. Press **Connect**.
5. Approve the incoming connection on the laptop. The remote screen should appear on the phone. Drag to move and touch to click.
6. Disconnect on either device when finished.

**Security:** The Android viewer pins the exact Windows host certificate fingerprint before sending the pairing code. Do not expose TCP 48765 to the internet. The Windows host generates a fresh certificate/code when hosting restarts; refresh both values on the phone.

**Limitations:** experimental, no keyboard support, no secure system desktops, no internet relay, no audio, no file transfer, JPEG-based screen capture (~4 FPS), Windows primary and multi-monitor coordinates require real-device validation. Neither Android nor Windows build and runtime tests have been verified as of this source commit.
