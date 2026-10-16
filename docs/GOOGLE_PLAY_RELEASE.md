# Android distribution and Google Play preparation

## Two variants

- **viewerDebug APK** (application ID `com.azeem.azremoteconnect.viewer`): does not declare an Accessibility service. Intended for sideloading, phone → Windows controlling, and approved Android screen viewing. An Android phone hosting with the viewer cannot receive remote touch gestures.
- **fullRelease AAB** (application ID `com.azeem.azremoteconnect`): includes an optional Accessibility service to allow a paired and individually approved remote controller to perform touch gestures on that Android phone. Requires a Play Console Accessibility API declaration, disclosure and consent, review and stable signing. The full AAB is **unsigned** if release-signing secrets are missing.

Both variants still request the usual Android system screen-sharing prompt when the user shares their phone screen. Viewer-only reduces one specific sideloaded-app Play Protect risk; it does not guarantee installation approval or app safety.

## Consent design

The normal Share button shows a screen-capture disclosure and requires an affirmative Continue action **before** Android MediaProjection permission. The full edition separately shows a dedicated Accessibility disclosure and requires "I agree: enable touch". The Android host honors remote gestures only when the user has affirmatively opted into touch control **for that particular session**, Android's service is enabled, the pair code matches, and the host approves the incoming remote session. Remote touch is never required for screen viewing.

## Create stable Android upload credentials (developer action)

You must own and back up a private, stable signing key. Do not commit keys or passwords to Git or share them in chat.

Use Java `keytool` on your own trusted computer:

```powershell
keytool -genkeypair -v -keystore azremote-upload.jks -storetype PKCS12 -alias azremote -keyalg RSA -keysize 3072 -validity 10000
```

Store the key and passwords offline. On your Windows computer, copy its Base64 representation:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\azremote-upload.jks")) | Set-Clipboard
```

In GitHub repository **Settings → Secrets and variables → Actions**, create these **repository secrets**, not variables:

- `AZ_KEYSTORE_B64`: Base64 of the keystore.
- `AZ_KEYSTORE_PASSWORD`: keystore password.
- `AZ_KEY_ALIAS`: key alias (e.g. `azremote`).
- `AZ_KEY_PASSWORD`: key password.

The publishing workflow restores the key only during the job and signs `fullRelease` when **all four secrets** are present. It publishes the full AAB only in that case; it does not fabricate credentials or release an unsigned bundle as production-ready.

For an installable, consistently release-signed APK, a follow-up workflow should use the same developer-owned signing key. Never upload your private keystore to a public artifact.

## Play Console checklist

1. Create a Play Console app and enrol in **Play App Signing**.
2. Supply a public privacy policy and accurate Data Safety answers; verify this repo's `PRIVACY.md` is accurate for the final architecture.
3. Complete the **AccessibilityService API declaration** and provide demo footage of the voluntary permission flow and user controls. This is a remote control utility, **not** a disability-support accessibility tool; never set `isAccessibilityTool=true` to bypass review.
4. Declare foreground-service screen capture use appropriately, explain why the app needs notifications, and review permissions against current Play policy.
5. Upload the signed `fullRelease` AAB to an internal test track. Do not assume publication or Play Protect approval until Google's review confirms it.
6. Test Android → Windows, Windows → Android, hotspot networks, rotation, denial/revocation and session termination. Ensure the host can stop a live session.
7. Do not expose TCP 48765 directly to the public internet: the current six-hex-digit certificate verification prefix is only 24 bits, and the transport is a preview.

## Existing blocked APKs

A newer version or a differently signed build is **not** a bypass for Play Protect. If Play Protect still blocks the **viewer** APK, stop and review the security scan/report; do not disable Play Protect to install it. If the **full** build is required on Android, use a legitimate distribution route with Play Console declarations and review.

Sources: https://developers.google.com/android/play-protect/warning-dev-guidance and https://support.google.com/googleplay/android-developer/answer/10964491
