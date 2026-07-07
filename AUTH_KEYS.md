# GlassFalcon Authentication Keys

## Files Moved from GlassFalcon-RE

### Core Authentication Modules
- **Mavic2Auth.kt** — RSA-SHA256 0x11/0x43 auth frame generator
- **FrameReplay.kt** — Fallback captured frame replay (114 valid frames from real GO4 session)
- **RootShell.kt** — Kprobe capture and root shell utilities
- **KprobeCapture.kt** — In-kernel hook capture for protocol analysis

### Key Material

**RSA Private Key** (Extracted from Mavic2Auth.kt):
- **Format**: PKCS#8
- **Files**:
  - `RSA_PRIVATE_KEY.pem` — PEM-encoded (human-readable)
  - `RSA_PRIVATE_KEY.der` — DER-encoded (binary)

**Device Token** (wm240 Mavic 2 Pro):
```
d3006306bd44fe08200bfd10025716a5
```
This token is:
- Static per aircraft (invariant across power cycles)
- Embedded in firmware as device serial identifier
- Used in every 0x11/0x43 authentication frame

## Build Status

✅ Build successful with all auth components:
```bash
JAVA_HOME=/path/to/android-studio/jbr ./gradlew assembleDebug
```

## Security Notes

The RSA private key here is **DJI's per-device signing key**, extracted from the firmware. Store securely.

Captured frames in FrameReplay.kt are from an actual Mavic 2 + GO4 session and serve as:
1. Fallback when RSA signing is unavailable
2. Reference for frame structure validation
3. Bootstrap for initial flights (replays rotate through 114 known-good frames)
