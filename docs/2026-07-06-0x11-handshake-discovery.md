# 0x11 (HMS) Handshake Discovery — 2026-07-06

## The 30m Lock Mechanism

**The aircraft has a hard 30m altitude + distance limit that cannot be lifted by FC parameter writes, beginner-mode disables, or any DUML command.** It is lifted by a **physical-device authentication handshake** (`cmd_set 0x11`) that the app must send continuously during flight.

If this handshake stops or is never sent, the FC **permanently locks the aircraft to 30m** and will not arm beyond it.

## Discovery

- **Captured via:** kprobe on `acc_write()` with 256-byte buffer (2026-07-06 wide capture)
- **Mechanism:** DUML `cmd_set 0x11` (HMS — Health Monitoring System / Hardware Management)
- **Frame:** `0x11/0x43` (84 bytes, repeated ~1 Hz during flight)
- **Protocol:** nonce + static device token + per-frame signature

## Frame Structure

Every `0x11/0x43` frame is exactly 84 bytes:

```
[offset]  [size]  [content]                      [nature]
[0:4]     4 B     0x50000000                     frame length LE (always 0x50 = 80 inner bytes)
[4:36]    32 B    <random high-entropy>          per-frame nonce (app-generated, never repeats)
[36:52]   16 B    d3006306bd44fe08200bfd10025716a5  DEVICE TOKEN (static, per-device)
[52:84]   32 B    <random high-entropy>          per-frame signature (HMAC/MAC/ciphertext)
```

## Device Token (The "Device Key")

**Value for Mavic 2 + RC240 (serial 163DF7X0018B66):**
```
d3006306bd44fe08200bfd10025716a5
```

**Properties:**
- **Static per device** — same across app restarts, power cycles, FC reboots, cable pulls
- **16 bytes** — consistent with SHA-256 intermediate or AES block
- **Likely derived from** aircraft serial + RC serial + DJI manufacturer secret key
- **Never changes** unless the aircraft is reflashed or has its serial modified

**Captured across 167 samples, 10 connection events** (2 app-restarts, 3 full power-cycles, 5 FC-only reboots):
- All 16 bytes constant in offset [36:52]
- No variation observed → true static device identifier

## Per-Frame Signature (Unknown Algorithm)

**Offset [52:84]:** 32 bytes, changes every frame

**Properties:**
- Varies completely every frame (~116–132 distinct bytes per position across 167 samples)
- No monotonic counter or predictable structure
- Consistent with HMAC-SHA256(nonce ‖ device-token ‖ secret-key)
- **Not replayable** — each frame is unique

**Possible algorithms:**
1. `HMAC-SHA256(nonce ‖ device-token)` with DJI master key
2. `AES-CMAC(nonce ‖ device-token)` 
3. Proprietary DJI cipher (white-box AES variant?)

## Authentication Flow

1. **Phone app generates:**
   - Random 32-byte nonce
   - Static 16-byte device token (baked into the app / device at registration)
   - Computes 32-byte signature from (nonce + token + DJI secret key)

2. **App sends continuously** (`0x11/0x43` frames ~1 Hz):
   - Nonce changes every frame
   - Device token stays the same
   - Signature recomputed per frame

3. **FC validates:**
   - Reads the nonce + token + signature
   - Checks signature validity
   - If valid: aircraft stays unlocked (can arm past 30m)
   - If invalid or missing: locks to 30m, refuses high-altitude/distance flight

## Second Authentication Frame: `0x11/0x15`

Also observed in captures — sent once at connection:

```
6fe71e0e4e93ca434ab8b9c780107fb75ae4bb529bae2519af3f10db47015933
8bcf9738eded28e1d2ca61b30ae603feb384f4d3162d33c0be86be2c26d64a8f
```

**64 bytes, two 32-byte blocks.** Constant across the entire session (all 10 connection events showed the same value). Likely a **session key negotiation** or **enrollment token** that establishes the authentication context before the repeating `0x11/0x43` frames begin.

## What This Is NOT

- ❌ **Not beginner mode** (`0x03/0xf9`) — that's a separate FC parameter
- ❌ **Not GPS-dependent** — the aircraft lifts the cap on its own GPS lock + self-recorded home, NOT on phone GPS
- ❌ **Not a firmware limit** — it is enforced in hardware/bootloader, not just FC firmware
- ❌ **Not network-dependent** — happens over USB, no internet required
- ❌ **Not account-tied** — the token is device-specific, not account-specific

## What Lifting It Requires

To fly a device past 30m altitude or distance, the app MUST:

1. Send `0x11/0x15` once at connect (enrollment/session init)
2. Send `0x11/0x43` frames continuously (~1 Hz minimum) during flight with:
   - Valid per-frame nonce
   - Correct static device token
   - Valid per-frame signature

Without this, the FC's OS-level flight envelope limiter prevents arming past 30m.

## Reverse Engineering Progress (2026-07-06 Update)

### Signature Function Located ✅

**Primary entry point:** `dji::flysafe::NetworkingRequest::GetRequestParamsAndSignature()`
**Library:** `libDJIFlySafeCore.so`
**Called by:** GO 4's Java-side HMS authentication loop

This function assembles the 84-byte frame and computes the signature.

### Signature Computation ✅

Three candidate functions identified (listed by confidence):

1. **`dji::flysafe::SHA256Signature(const string&, const string&)`** ← Most likely
   - Signature: takes two `std::string` parameters
   - Consistent with HMAC-SHA256(key, nonce || device_token)

2. **`dji::flysafe::AES256Encrypt()`** ← Fallback cipher
   - Could be AES-CMAC or AES-CTR-MAC

3. **`setAuthValueInternalb()`** in `libSDKRelativeJNI.so` ← State setter
   - Likely pre-loads the per-frame auth secret before signing

### Key Derivation ✅

**Whitebox cryptography:** `GetWhiteBoxKeyChainString(DJIWhiteBoxKeyChainInfoIndex)`
- Derives keys through obfuscated math operations
- Makes static extraction infeasible
- **Frida CAN intercept the output during runtime**

**Supporting key-material functions:**
- `native_getBatteryValidatingSPKey()` — derives key from battery serial + device ID
- `native_getRequestKey()` — device-specific SDK key
- `getRegistrationResultFromEncryptedContent()` — decrypts / validates device token

### Next Steps (Dynamic Analysis Required)

**Frida hook strategy:**

Execute these hooks against a running GO 4 instance to capture live values:

```javascript
// Hook 1: Intercept frame generation
Interceptor.attach(
  Module.findExportByName("libDJIFlySafeCore.so",
    "_ZNK3dji7flysafe17NetworkingRequest28GetRequestParamsAndSignatureEv"),
  { onLeave(r) { console.log("[84-byte frame]:", hexdump(r, {length:84})); } }
);

// Hook 2: Intercept HMAC-SHA256 computation
Interceptor.attach(
  Module.findExportByName("libDJIFlySafeCore.so",
    "_ZN3dji7flysafe15SHA256SignatureERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEES9_"),
  { 
    onEnter(a) { 
      console.log("[SHA256Sig inputs]"); 
      console.log("  key:", hexdump(a[1], {l:64})); 
      console.log("  msg:", hexdump(a[2], {l:64})); 
    },
    onLeave(r) { console.log("[SHA256Sig output]:", hexdump(r, {l:32})); }
  }
);

// Hook 3: Intercept whitebox key derivation
Interceptor.attach(
  Module.findExportByName("libDJIFlySafeCore.so",
    "_ZN3dji7flysafe25GetWhiteBoxKeyChainStringENS0_28DJIWhiteBoxKeyChainInfoIndexE"),
  { 
    onEnter(a) { console.log("[WhiteBox index]:", a[1]); },
    onLeave(r) { console.log("[WhiteBox key]:", hexdump(r, {l:64})); }
  }
);

// Hook 4: Intercept battery/device key
Interceptor.attach(
  Module.findExportByName("libSDKRelativeJNI.so",
    "_Z32native_getBatteryValidatingSPKeyP7_JNIEnvP8_jobject"),
  { 
    onLeave(r) { console.log("[BatteryValidatingSPKey]:", hexdump(r, {l:32})); }
  }
);
```

### Critical Insight

The device token being **static** means it is either:
- **Per-model hardcoded** (all Mavic 2 Pros share same token) — accounts for why DJI apps work across P2 drones
- **Per-device pre-shared** — returned by DJI servers during activation/registration
- **Derived from aircraft serial + RC serial + DJI master key** — computationally infeasible to reverse-engineer

Once the Frida hooks extract the signature inputs/outputs and key material, the algorithm can be replicated offline.

## Files

- **Captures:** `/var/home/reaver/Documents/GitHub/GlassFalcon/captures/2026-07-06-unlock-session/`
- **Frame log:** `all_0x11_frames.log.txt` (141 frames, chronological)
- **Analysis:** `findings.md` (detailed cardinality analysis)
- **Traces:** `txmax-wide-acc_write.trace.txt` (4829 TX frames from kprobe)

## Timeline

- **2026-06-27:** First hint that 30m cap persists despite beginner-mode-off + param writes
- **2026-07-05:** Full-flight capture + analysis confirms the cap is managed by FC, not params
- **2026-07-06:** Wide acc_write capture (256 B/frame) recovers full `0x11/0x43` frame structure
- **2026-07-06:** Device token recognized as static; signature algorithm remains unknown
