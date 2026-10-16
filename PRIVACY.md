# AZ RemoteConnect — Privacy Notice (Development Preview)

**Last updated:** 2026-10-16

AZ RemoteConnect provides user-authorized remote screen viewing and, in the full Android edition, optional remote touch control.

## Data and device access

- **Screen capture (Android MediaProjection):** only starts after the user chooses to share their screen and accepts Android's screen capture confirmation. Visible screen content, including notifications and private information, can be seen by the **specific remote peer whom the phone user approves**. The remote peer could record that content independently.
- **Accessibility service (full edition only):** optional. This service is used to dispatch taps and swipes from an approved peer. The implementation does not read the contents of Accessibility events or upload their text. Android regards this as a sensitive permission because the capability could access interface content.
- **Network:** screen frames and remote input are exchanged over a TLS-encrypted direct session with the approved device on port 48765. The current application has **no developer-operated cloud relay or analytics service**. Network metadata such as peer IP addresses may be visible to the devices and network operators.
- **Audio:** when sharing a Windows computer, the Windows app may transmit system playback audio to the receiving Android device. The Android phone-host feature does not capture phone audio in this preview.
- **Pairing and authorization:** a pairing code, short certificate verification string, and approval at the hosting device are used. The short six-hex-character verification value is weaker than a full certificate fingerprint; do not expose the service directly to the public internet.
- **Local storage:** the current remote-screen and audio streams are not intentionally recorded to a developer-controlled server. The software may write local diagnostic errors; remote users may separately capture content outside the app.

## User controls

The hosting user can decline any incoming session and can stop screen sharing via the app or Android's foreground service notification. Accessibility can be disabled under Android Settings > Accessibility at any time. Screen sharing still works in view-only mode. Permissions can be revoked in device settings.

## Third parties and distribution

An Android installation is subject to Android / Google Play Protect checks. The app uses operating-system components for capture and network encryption. Google and network operators may process data according to their own policies. An untrusted remote device can view or act on data only after a connection is approved, but approving an untrusted controller is risky.

## Contact and support

Project and source: https://github.com/Mhd-Azeem/AZ-RemoteConnect

This notice describes the open-source development preview; review and update it before production distribution, particularly if servers, telemetry, account features, or third-party SDKs are later added.
