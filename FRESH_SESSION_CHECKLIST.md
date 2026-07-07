# Fresh Session Checklist

Use this checklist when starting a new session (e.g., with Fable 5) to verify the repo is self-contained.

## ✅ Critical for Compilation

- [x] `android/build.gradle.kts` — Top-level build config
- [x] `android/app/build.gradle.kts` — App module config
- [x] `android/app/src/main/kotlin/dev/glassfalcon/core/Mavic2Auth.kt` — RSA frame generator
- [x] `android/app/src/main/kotlin/dev/glassfalcon/core/FrameReplay.kt` — Fallback frames
- [x] `android/app/src/main/kotlin/dev/glassfalcon/core/RootShell.kt` — Kprobe utils
- [x] `android/app/src/main/kotlin/dev/glassfalcon/core/KprobeCapture.kt` — Kprobe module
- [x] `android/sdk/` — DUML SDK module (entire directory)

**Build status:** ✅ Compiles cleanly with `JAVA_HOME=/android-studio/jbr ./gradlew assembleDebug`

---

## ✅ Critical for Running

- [x] `RSA_PRIVATE_KEY.pem` — RSA private key (PEM format)
- [x] `RSA_PRIVATE_KEY.der` — RSA private key (DER format)
- [x] Embedded in `Mavic2Auth.kt` — base64-encoded PKCS#8 key
- [x] Device token in `Mavic2Auth.kt` — `d3006306bd44fe08200bfd10025716a5` (wm240 Mavic 2)
- [x] 114 captured frames in `FrameReplay.kt` — fallback for testing

**Flight readiness:** ✅ Can connect, generate auth frames, and attempt 30m cap lift

---

## ✅ Documentation & References

- [x] `README.md` — Main project README (expanded with AI/voice details)
- [x] `AUTH_KEYS.md` — Key material summary
- [x] `docs/2026-07-06-0x11-handshake-discovery.md` — Original protocol discovery
- [x] `docs/reverse-engineering/README.md` — Master RE guide
- [x] `docs/reverse-engineering/RSA_KEY_EXTRACTION_GUIDE.md` — How to extract key
- [x] `docs/reverse-engineering/ANALYSIS_SUMMARY.md` — High-level findings
- [x] `docs/reverse-engineering/DJI-GO4-Security-Analysis.md` — Forensic analysis
- [x] `docs/reverse-engineering/findings.md` — Frame analysis details
- [x] `docs/reverse-engineering/2026-07-06-unlock-session/` — Raw captures (114 frames)

**Understanding the auth system:** ✅ Complete

---

## ✅ Tools & Utilities

- [x] `tools/RE_TOOLS_README.md` — How to use extraction tools
- [x] `tools/re-extraction/extract_keychain.py` — Frida hook for key extraction
- [x] `tools/re-extraction/hook_dex.py` — Java-level function tracing
- [x] `tools/re-extraction/hook_waes.py` — AES monitoring
- [x] `tools/install.sh` — APK installation helper
- [x] `tools/find-drone.sh` — USB device detection

**Key extraction capability:** ✅ Ready (if rooted phone available)

---

## ⚠️ Known Limitations (NOT in Repo)

These are external dependencies—document them if you hit issues:

**Device fleet (from memory):**
- P8 (Pixel 8): `38151FDJG003MJ` — primary dev phone
- P10 (Pixel 10): `58160DLCQ004QQ` — test device
- Mavic 2 Pro (wm240): primary test aircraft

**Firmware versions tested:**
- Aircraft FW: `01.00.0797` (last known good)
- RC240 FW: `01.00.0770`
- GO4: v4.3.64

**Android Studio setup:**
- JDK location: `/home/reaver/android-studio/jbr`
- Set `JAVA_HOME` to this path for Gradle 8.4 compatibility

---

## 🚀 First Steps in Fresh Session

1. **Verify checkout:**
   ```bash
   git clone https://github.com/sworrl/GlassFalcon.git
   cd GlassFalcon
   ```

2. **Check critical files exist:**
   ```bash
   ls android/app/src/main/kotlin/dev/glassfalcon/core/Mavic2Auth.kt
   ls RSA_PRIVATE_KEY.pem
   ls docs/reverse-engineering/README.md
   ```

3. **Build:**
   ```bash
   JAVA_HOME=/path/to/android-studio/jbr ./gradlew assembleDebug
   ```

4. **Understand the auth system:**
   - Read: `docs/reverse-engineering/README.md`
   - Deep dive: `docs/2026-07-06-0x11-handshake-discovery.md`
   - Implementation: `android/app/src/main/kotlin/dev/glassfalcon/core/Mavic2Auth.kt`

5. **Deploy and test:**
   ```bash
   adb install android/app/build/outputs/apk/debug/*.apk
   # Connect aircraft, check logs for "Mavic2Auth: sending 0x11/0x43 frame"
   ```

---

## ✅ Independent from GlassFalcon-RE

**NO longer needed:**
- GlassFalcon-RE repo
- Separate RE tools directory
- Manual key extraction (key is already embedded)
- External documentation lookups

**Everything is here in GlassFalcon.**

---

## ✅ Fable 5 Ready

This repo works with any Claude model. No model-specific dependencies. Fable 5 can:
- Read all source code (Kotlin, Python, protobuf)
- Understand DUML protocol (docs provided)
- Modify Mavic2Auth.kt if key needs updating
- Debug build issues (Gradle, Kotlin compilation)
- Run extraction tools if needed
- Understand device fleet setup

No context from previous sessions required.

---

Last verified: 2026-07-07
Repo status: **SELF-CONTAINED** ✅
