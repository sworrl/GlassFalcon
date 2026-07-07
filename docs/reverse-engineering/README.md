# GlassFalcon Reverse Engineering Notes

All reverse engineering findings, protocol analysis, and key extraction research for the Mavic 2 (wm240) authentication handshake and DUML protocol.

## Critical Documents

### 1. **0x11 Handshake Discovery** ([2026-07-06-0x11-handshake-discovery.md](2026-07-06-0x11-handshake-discovery.md))
The definitive technical breakdown of the 30m altitude/distance cap enforcement mechanism.

**Key findings:**
- 84-byte `0x11/0x43` authentication frame structure
- Device token: `d3006306bd44fe08200bfd10025716a5` (static per aircraft)
- RSA-SHA256 signature (per-frame, non-replayable)
- Frame must be sent continuously (~1 Hz) during flight or aircraft locks to 30m

**Status:** Frame structure confirmed, signature algorithm confirmed, RSA key embedded in Mavic2Auth.kt

---

### 2. **RSA Key Extraction Guide** ([RSA_KEY_EXTRACTION_GUIDE.md](RSA_KEY_EXTRACTION_GUIDE.md))
Methods to extract and validate the RSA private key used to sign authentication frames.

**Two approaches:**
- **LSPosed** (preferred): Hook DJI GO 4 at runtime to capture live signing operations
- **Firmware RE**: Binary analysis of libDJIFlySafeCore.so to locate key derivation

**Current status:** Key extracted and embedded in Mavic2Auth.kt; key source (per-device vs per-model) requires validation on second aircraft

---

### 3. **Unlock Session Analysis** ([2026-07-06-unlock-session/](2026-07-06-unlock-session/))
Raw capture data and forensic analysis from a live DJI GO 4 unlock session.

**Contents:**
- `all_0x11_frames.log.txt` — 114 valid captured 0x11/0x43 frames (hex)
- `findings.md` — Frame cardinality analysis and signature patterns
- `ANALYSIS_SUMMARY.md` — High-level summary of what each frame revealed
- `GO4_FORENSICS_COMPLETE.md` — Kprobe capture setup and raw trace data

**How to use:**
- Frames are available for replay (FrameReplay.kt) as a fallback
- Signatures prove non-replayability of static keys
- Patterns confirm RSA-SHA256 algorithm

---

### 4. **DJI GO 4 Security Analysis** ([DJI-GO4-Security-Analysis.md](DJI-GO4-Security-Analysis.md))
Forensic breakdown of GO 4's authentication flow, including:
- Connection handshake sequence
- Which commands are sent at what time
- How the authentication context is established
- What hooks/breakpoints matter for key extraction

---

## Implementation Files

All these materials are consumed by three source files in the final product:

| File | Purpose | Status |
|------|---------|--------|
| `Mavic2Auth.kt` | RSA-SHA256 frame generation | ✅ Implemented, key embedded |
| `FrameReplay.kt` | Captured frame replay (fallback) | ✅ 114 frames available |
| `RootShell.kt` + `KprobeCapture.kt` | In-kernel capture tools | ✅ For future protocol work |

---

## Key Questions and Status

### Q: Is the RSA key per-device or shared across all Mavic 2s?

**Current assumption:** Per-model (shared, since it came from the app)

**Evidence:**
- Key was extracted from DJI GO 4 app via dynamic instrumentation
- All Mavic 2 Pros use the same GO 4 binary
- Device token is static per aircraft, suggesting per-model architecture

**Validation needed:** Test key on a second Mavic 2 Pro aircraft
- If it works → key is per-model (shared globally)
- If it fails → key is per-device or per-account (need per-aircraft extraction)

---

### Q: How to extract the key from a different aircraft?

**Method 1: LSPosed (Easiest)**
1. Install LSPosed on a rooted P8 with GO 4
2. Run the hook module from RSA_KEY_EXTRACTION_GUIDE.md
3. Extract base64 key from logcat
4. Compare against current key in Mavic2Auth.kt

**Method 2: Firmware Reverse Engineering**
1. Download firmware from aircraft via DJI Assistant 2
2. Extract AIRT module (Flight Control firmware)
3. Analyze with Ghidra to locate RSA key storage or derivation code
4. See dji-firmware-tools README for extraction tools

---

### Q: What if the key changes after a firmware update?

**Risk:** Firmware updates may roll new RSA keys (DJI controls this)

**Mitigation:**
1. FrameReplay.kt provides bootstrap (works until RSA signing is needed)
2. Monitor for firmware version changes
3. If key changes, re-extract via LSPosed and update Mavic2Auth.kt
4. Consider making key injectable at runtime (vs hardcoded)

---

## Timeline

| Date | Event | Status |
|------|-------|--------|
| 2026-06-27 | First hint of 30m cap persistence despite parameter writes | Discovery |
| 2026-07-05 | Full-flight capture proves cap is FC-enforced, not parameter-based | Confirmed |
| 2026-07-06 | Wide acc_write capture recovers full 0x11/0x43 frame structure | Confirmed |
| 2026-07-06 | RSA-SHA256 algorithm identified (HMAC hypothesis disproven) | Confirmed |
| 2026-07-06 | Device token recognized as static; key extracted from GO 4 | Confirmed |
| 2026-07-07 | Mavic2Auth.kt, FrameReplay.kt, RootShell.kt moved to main repo | ✅ Done |

---

## Next Steps for Flight Testing

1. **Pre-flight:**
   - Verify build with `JAVA_HOME=/path/to/android-studio/jbr ./gradlew assembleDebug`
   - Install APK on phone with Mavic 2 + RC240

2. **Initial ground test:**
   - Connect to aircraft via USB AOA
   - Check logs for `Mavic2Auth: sending 0x11/0x43 frame` messages
   - Confirm frames are generated at ~1 Hz

3. **Flight test (controlled field):**
   - Arm and take off (should arm normally)
   - Climb to 35m altitude (should succeed if 30m cap is lifted)
   - Extend to 40m horizontal distance (should succeed)
   - Land normally
   - Monitor for `Mavic2Auth ERROR` messages

4. **Validation:**
   - If aircraft stays locked at 30m → key extraction or signature computation failed
   - If aircraft arms but refuses high alt/distance → frame not being sent continuously
   - If aircraft unlocks → **SUCCESS** — 0x11/0x43 auth works

---

## File Locations

```
GlassFalcon/
├── docs/reverse-engineering/              # This directory
│   ├── 2026-07-06-0x11-handshake-discovery.md
│   ├── RSA_KEY_EXTRACTION_GUIDE.md
│   ├── ANALYSIS_SUMMARY.md
│   ├── DJI-GO4-Security-Analysis.md
│   ├── findings.md
│   └── 2026-07-06-unlock-session/         # Captured frames + raw traces
│       ├── all_0x11_frames.log.txt        # 114 valid frames (hex)
│       ├── findings.md
│       ├── ANALYSIS_SUMMARY.md
│       ├── GO4_FORENSICS_COMPLETE.md
│       └── ...trace files...
│
├── android/app/src/main/kotlin/dev/glassfalcon/core/
│   ├── Mavic2Auth.kt                      # RSA frame generator (uses key below)
│   ├── FrameReplay.kt                     # Fallback frame replay
│   ├── RootShell.kt
│   └── KprobeCapture.kt
│
└── RSA_PRIVATE_KEY.*                      # Extracted key material
    ├── RSA_PRIVATE_KEY.pem
    └── RSA_PRIVATE_KEY.der
```

---

## Security Notes

⚠️ **This repository contains DJI's proprietary RSA private key.** Store securely and treat as sensitive intellectual property.

- **Do not commit to public repositories** (this repo is open-source by design, but understand the implications)
- **Do not distribute the key** separately from GlassFalcon
- **Do not use for purposes outside flight testing** of your own aircraft
- **Monitor for firmware updates** that may invalidate the key

---

## References

- `docs/2026-07-06-0x11-handshake-discovery.md` — Original discovery document (main repo)
- `android/app/src/main/kotlin/dev/glassfalcon/core/Mavic2Auth.kt` — Implementation
- `android/app/src/main/kotlin/dev/glassfalcon/core/FrameReplay.kt` — Fallback frames
- `AUTH_KEYS.md` — Key material summary (main repo root)

---

## Contributing RE Findings

If you discover new protocol details, firmware extraction methods, or validation results:

1. Add findings to the appropriate `.md` file in this directory
2. Include date, aircraft model, firmware version, and methodology
3. Update the Timeline section above
4. Commit with message referencing the discovery

Example:
```
docs: 0x11 key validation on second Mavic 2 (serial 163DF7X0018C99)

Tested RSA_PRIVATE_KEY.pem on second wm240 aircraft:
- Frame generation: PASS
- Auth handshake: PASS
- 30m cap lift: PASS

Confirms key is per-model (shared across all Mavic 2 Pros).
Firmware version: 01.00.0797 (same as extraction aircraft).
```
