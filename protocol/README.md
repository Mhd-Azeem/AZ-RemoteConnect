# Protocol draft

No production protocol is implemented. Future messages must be versioned, schema-validated and authenticated. Design separate message types for device registration, pairing request/approval, SDP offers/answers, ICE candidates, session revocation, and audit events. Screen/video and input data must use authenticated encrypted channels. Never trust a device ID alone as authentication.
