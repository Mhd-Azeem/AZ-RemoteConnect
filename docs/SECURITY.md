# Security requirements

- Require affirmative host consent for every attended session.
- Use secure device identity, authenticated peers, short-lived session authorization and revocation.
- Encrypt signaling in transit (WSS) and end-to-end transport; verify identity binding.
- Never authenticate remote control solely through a visible device ID.
- Provide explicit UI indicators, a stop control and auditable session events.
- Android screen capture needs MediaProjection consent; remote input needs a user-enabled accessibility service. Do not silently enable either.
- Enforce command authorization on every input, clipboard and transfer operation.
- Use GitHub encrypted secrets for signing materials; never commit keystores, passwords or tokens.
- Keep development signaling on loopback only until properly hardened.
- Treat TURN credentials as temporary and rate-limited; never ship permanent shared secrets in the APK/EXE.
