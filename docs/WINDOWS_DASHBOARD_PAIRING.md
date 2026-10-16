# AZ RemoteConnect — Blue Windows Dashboard, Temporary Six-Digit Pairing

## Screenshot-inspired Windows controls

The Windows WPF dashboard includes the sidebar, device connection card, six-digit code entry, two touch modes, full-screen live phone preview, quick actions, local Windows hosting and a connection status footer. The preview shows actual frames from a connected host, **not** an Android mock device, and displays a disconnected state until successful host approval.

- **Screen Touch**: maps input into the real letterboxed Android frame; an Android host with active Accessibility consent receives tap gestures.
- **Touchpad**: translates Windows mouse movement into relative pointer commands; taps are dispatched only if the Android host has opted into remote touch.
- **Keyboard**: sends an optional text-replacement command to the currently focused Android editable field; requires the full Android edition and remote-touch consent. Not every Android application supports this Accessibility action.
- **Audio Streaming** and **File Transfer**: present as feature cards but do not claim to implement Android→Windows audio or file transfer. The Windows host can send local Windows playback audio to an Android client using existing functionality.
- **Full Screen**: shows the live stream at original aspect ratio; Esc or double-click exits. Touch and touchpad commands remain active.

## Windows → Android phone: no fingerprint entry

1. Open the Android **full** edition if you need remote touch, or the **viewer** edition if you only need screen viewing. Tap **Share this phone**, read the privacy disclosure and accept Android's screen sharing prompt.
2. Read the **six-digit temporary pairing code** from the active Android host notification (or Refresh host code). Each newly started sharing service creates a new code. The phone must actively approve each incoming session.
3. In the Windows dashboard, enter the Android phone's IP address and **only the six-digit pairing code**. Default port: 48765.
4. On the **first connection to that IP and port**, Windows displays a one-time trust-on-first-use warning. Continue **only when you trust the device and the local network**. This is a confirmation, **not** an additional manual verification code.
5. Approve the Windows viewer's request in the Android host's notification. The Windows client stores the **full TLS certificate fingerprint internally**, without asking you to transcribe it.
6. A later TLS identity change at the same address automatically **blocks** connection. Use **Forget phone trust** only after verifying that you reinstalled the same trusted host or deliberately replaced the device.

## Security limitations

**WARNING:** First-use TLS trust is vulnerable to an active on-path attacker impersonating the host. A six-digit pairing code is not equivalent to a cryptographically authenticated key exchange. Prefer a private LAN/hotspot or trusted private VPN and **never** expose TCP 48765 directly to the public internet. Subsequent connections use the saved full SHA-256 certificate fingerprint for identity continuity, but this does not secure an already-compromised first pairing.

Remote touch on an Android device requires the **full** Android edition with separately enabled Accessibility service and explicit touch-control consent. The viewer-only edition does not grant incoming remote touch even though the Windows dashboard displays touch controls. Google Play Protect may block the sideloaded full APK; do not disable Play Protect.

Phone → Windows connections from existing Android builds still use the Android viewer's separate six-character certificate verification entry. The Windows dashboard displays that compatibility code **only when you start PC hosting**; removing manual verification from Windows → Android does not silently remove the Android client's existing security model.

The native Windows application is unsigned and local networking requires Windows Firewall to permit connections on trusted Private networks only. GitHub CI compilation is not evidence that a remote session has been tested end-to-end on physical devices.
