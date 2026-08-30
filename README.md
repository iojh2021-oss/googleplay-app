# Blind Phone Radar

Offline Android prototype for assistive proximity awareness focused on nearby radio-visible devices.

## Core design

- BLE scanning on the user's phone only.
- No camera.
- No server or cloud dependency.
- Real device MAC addresses are not retained; an ephemeral in-memory key is used per process.
- RSSI is filtered over multiple samples and converted to a **range estimate**, not presented as exact distance.
- Phone detection is explicitly heuristic: a BLE advertisement cannot reliably prove that the device is a phone.
- UWB and Wi-Fi RTT capability detection is included as the next ranging layer. Android's modern ranging APIs can expose distance/angle/RSSI when the peer technology and device hardware support it.
- Persian TTS gives a simple nearest-device warning.

## Important engineering limitation

A phone with no participating app, no compatible ranging session, and no visible radio advertisement cannot be reliably detected by another phone. BLE RSSI also cannot provide meter-accurate distance in arbitrary real-world conditions. UWB/RTT ranging requires a compatible peer or access point and the appropriate protocol/session. The product therefore reports confidence and a range band rather than pretending RSSI is a radar.

## Build

GitHub Actions builds debug and release APKs from the `main` branch and uploads both as an artifact.

## Planned industrial layers

1. Calibrated per-device RSSI profiles.
2. Robust median/EMA/Kalman filtering.
3. Temporal track management for many simultaneous devices.
4. Device-type classification without storing identifiers.
5. API 36+ Android Ranging integration for UWB/BLE/RTT where supported.
6. UWB multicast ranging for compatible peers.
7. Wi-Fi NAN RTT / Wi-Fi RTT where supported.
8. Haptic directional patterns and accessibility service integration.
9. Battery-aware scan duty cycling and foreground service operation.
10. Automated hardware test matrix and field calibration tools.
