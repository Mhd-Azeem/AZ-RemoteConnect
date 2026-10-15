# AZ RemoteConnect

Open-source-in-progress remote access suite targeting Android and Windows.

**Current status:** buildable starter apps and signaling-service skeleton; remote control, video streaming, end-to-end authentication and file transfer are **not implemented**. The server is deliberately a local-only placeholder. Do not expose it publicly.

## Project layout

- `android/` — Android APK starter (Kotlin)
- `windows/` — Windows desktop starter (WPF / .NET 8)
- `server/` — local-only signaling skeleton (Node.js)
- `protocol/` — wire protocol draft
- `docs/` — architecture, security, Codex handoff
- `.github/workflows/` — automated build and validation

## Build

Android: `gradle -p android assembleDebug` (JDK 17, Android SDK 35 and Gradle 8.11.1). Output `android/app/build/outputs/apk/debug/app-debug.apk`.

Windows: `dotnet publish windows/AZRemoteConnect.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:EnableWindowsTargeting=true` (Windows runner recommended).

Server: `cd server && npm install && npm run build && npm test`. Its example WebSocket listener defaults to localhost and is NOT production-ready.

See `docs/CODEX_HANDOFF.md` for the next development tasks. No APK/EXE builds or releases have been verified yet.
