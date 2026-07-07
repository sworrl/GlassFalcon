# RSA Private Key Extraction Guide

## Status

Autonomous extraction attempted on P10: **No LSPosed module detected on device**

## Path Forward: Two Options

### Option A: Compile & Deploy LSPosed Module (15 minutes)

**Files Ready:**
- `/tmp/GlassFalconRSAExtractor.java` — Complete LSPosed hook module

**Steps:**
```bash
1. Open Android Studio
2. Create new Android Module: GlassFalconRSAExtractor
3. Copy GlassFalconRSAExtractor.java to src/main/java/
4. Build → Release APK
5. Open LSPosed app on P8/P10
6. Install from disk → select APK
7. Enable for package: dji.go.v4
8. Reboot or restart GO4
9. Connect aircraft & start GO4
10. Check logcat: adb logcat | grep "GF_RSA"
11. Copy [PRIVKEY_B64] output
```

**Result:** Base64-encoded PKCS8 RSA private key in logcat

### Option B: Use Frida Script (If Frida Available)

**File Ready:**
- `/tmp/extract_rsa_key.js` — Frida injection script

**Requirements:**
```bash
frida-server running on device (adb push frida-server /data/local/tmp/)
frida command-line tool installed locally
```

**Steps:**
```bash
# Start Frida server on device
adb push ~/frida-server /data/local/tmp/
adb shell chmod +x /data/local/tmp/frida-server
adb shell /data/local/tmp/frida-server &

# Inject hook into GO4
frida -H 192.168.13.200:37997 -l /tmp/extract_rsa_key.js -p $(adb shell pidof dji.go.v4)

# Start aircraft connection in GO4
# Watch for key output in Frida console
```

**Result:** Key output in Frida session

### Option C: Direct Memory Dump (Most Reliable - No Dependencies)

**Steps:**
```bash
1. Root device confirmed (✓ P8)
2. Start GO4 with aircraft connected
3. Let GO4 perform auth frame generation
4. Access /proc/[GO4_PID]/maps to find heap
5. Dump memory: cat /proc/[PID]/mem > dump.bin
6. Search dump for PKCS8 magic bytes: 30820x (ASN.1 SEQUENCE)
7. Extract key bytes
8. Convert to base64
```

---

## What We Know About the Key

**Format:** PKCS8 (ASN.1 DER encoded)
- Magic bytes: `30 82` (SEQUENCE header)
- Followed by length, version, algorithm OID (RSA), and key material

**Size:** Likely 1200-2400 bytes
- 2048-bit RSA private key = ~1200 bytes
- 4096-bit RSA private key = ~2400 bytes

**Base64 Length:** 1600-3200 characters
- When encoded to base64, multiply by 4/3

**Location in Memory:**
- Loaded when `java.security.Signature.initSign()` is called with private key
- Lives in Java heap (managed memory)
- Persists for duration of GO4 process
- May be copied to stack during signing operations

---

## Extraction Scripts Location

All tools ready to deploy:

```
/tmp/GlassFalconRSAExtractor.java      (LSPosed module source)
/tmp/extract_rsa_key.js                (Frida injection script)
/tmp/auto_extract_key.sh               (Automated logcat monitor)
/tmp/build_complete_map.py             (Analysis tools)
```

---

## Manual Verification Once Extracted

Once you have base64 key:

```bash
# Decode to see structure
echo "<<BASE64_KEY>>" | base64 -d | xxd | head

# Should show ASN.1 SEQUENCE magic:
# 00000000: 3082 xxxx .... (PKCS8 header)

# Verify it's RSA:
# 00000010: 0201 0006 092a 8648 86f7 0d01 0101 0500
# ↑ This is the RSA OID
```

---

## Integration into GlassFalcon

Once extracted:

```kotlin
// In Mavic2Auth.kt
private val RSA_PRIVATE_KEY_B64 = "<<PASTE_BASE64_HERE>>"

// Test by running:
// ./gradlew -x lint assembleDebug

// Deploy and test on P10:
// adb -s 192.168.13.200:37997 install -r app/build/outputs/apk/debug/GlassFalcon*.apk
// adb shell am start -n dev.glassfalcon/.MainActivity
// Try to generate frame via Dev tab
```

---

## Status Summary

| Step | Status | Tool | Time |
|------|--------|------|------|
| Code ready | ✅ | Mavic2Auth.kt | - |
| APK built | ✅ | gradle | - |
| APK deployed | ✅ | adb | - |
| LSPosed module | ⏳ | Needs compilation | 10 min |
| Key extraction | ⏳ | LSPosed/Frida/Memory | 15 min |
| Code integration | ⏳ | Edit + rebuild | 5 min |
| Flight test | ⏳ | P10 + aircraft | 30 min |

---

## Next Step

**Choose one extraction method and execute it.** All tools are ready; just need to deploy and run.

The fastest path: **Option A (LSPosed)** if you have Android Studio available.

Alternative: **Option B (Frida)** if frida-server is already on device.

Fallback: **Option C (Memory dump)** works even without additional tools (just needs root access verified).
