# Reverse Engineering Tools

Tools for extracting DJI authentication keys, capturing DUML traffic, and analyzing protocol behavior.

## Quick Start

### Need the RSA key for a different aircraft?

**Fastest method (LSPosed):**

1. **Prerequisites:**
   - Rooted Android phone (Pixel 8+ with LSPosed preferred)
   - DJI GO 4 installed
   - GlassFalcon installed

2. **Extract the key:**
   ```bash
   # On your PC, with phone connected:
   adb shell
   
   # Inside adb shell, run Frida to hook GO4:
   frida -U -l re-extraction/extract_keychain.py -f com.dji.go
   
   # Watch logcat for output:
   adb logcat | grep "GF_RSA_EXTRACT\|WhiteBox"
   ```

3. **Save the output:**
   - Copy base64-encoded PKCS#8 key from logcat
   - Replace `RSA_PRIVATE_KEY_B64` in `Mavic2Auth.kt`
   - Rebuild and test

---

## Available Tools

### Frida Hooks (`tools/re-extraction/`)

#### `extract_keychain.py`
Hook DJI GO 4's cryptographic functions to extract key material at runtime.

**What it captures:**
- RSA private key (from whitebox crypto derivation)
- Device token
- Session keys
- HMAC/signature material

**Usage:**
```bash
frida -U -l extract_keychain.py -f com.dji.go
```

**Output:** Logs to logcat with tags:
- `GF_RSA_EXTRACT` — RSA private key dumps
- `GF_DEVICE_TOKEN` — Device token values
- `GF_WHITEBOX` — Whitebox crypto key derivation

---

#### `hook_waes.py`
Hook AES encryption/decryption functions to monitor firmware encryption.

**Use case:** Understanding how firmware is encrypted during transmission.

**Usage:**
```bash
frida -U -l hook_waes.py -f com.dji.go
```

---

#### `hook_dex.py`
Hook Java-level functions in GO 4 (not typically needed for RSA extraction).

**Use case:** Tracing high-level authentication flow without native hooks.

**Usage:**
```bash
frida -U -l hook_dex.py -f com.dji.go
```

---

## Workflow: Extract Key from Second Aircraft

### Scenario: You have a second Mavic 2 Pro and want to verify if the RSA key is per-device or shared.

**Step 1: Prepare the device**
```bash
# On rooted P8 with LSPosed:
adb shell

# Inside shell, verify LSPosed is active:
ls /data/adb/lsposed/

# Verify GO4 is installed:
pm list packages | grep dji.go
# Output: com.dji.go
```

**Step 2: Run the extractor**
```bash
# From your PC:
cd GlassFalcon/

# Start Frida hook:
frida -U -l tools/re-extraction/extract_keychain.py -f com.dji.go
```

**Step 3: Trigger key derivation**
```bash
# In another terminal, watch logcat:
adb logcat | grep "GF_RSA\|GF_DEVICE"

# Meanwhile, in GO4 on the phone:
# 1. Connect to the second aircraft (USB or WiFi)
# 2. Open the Flight HUD
# 3. The app will derive the RSA key during startup
# 4. Logcat will show the extracted key
```

**Step 4: Validate the result**
```bash
# Copy the key from logcat output
# Compare against current Mavic2Auth.kt:

# If keys are identical:
#   ✅ Key is per-model (shared across all Mavic 2s)
#   No changes needed, current implementation is correct

# If keys are different:
#   ⚠️ Key is per-device
#   Update Mavic2Auth.kt with the new key for THIS aircraft
#   OR switch to a dynamic key injection mechanism
```

---

## Workflow: Capture Live DUML Traffic (Kprobe)

### Scenario: You want to capture raw DUML frames to analyze a new command or verify protocol behavior.

**Prerequisites:**
- Rooted P8 with kernel kprobe support
- GlassFalcon dev build

**Step 1: Enable kprobe capture**
```bash
adb shell

# Inside shell:
# Check if kprobe is enabled:
cat /sys/kernel/debug/tracing/events/uprobes/enable

# Enable all uprobes:
echo 1 > /sys/kernel/debug/tracing/events/uprobes/enable

# Or in GlassFalcon Dev tab: tap "Start Kprobe Capture"
```

**Step 2: Trigger the action**
```bash
# In GlassFalcon:
# 1. Connect to aircraft
# 2. Perform the action (e.g., takeoff, camera zoom, gimbal move)
# 3. Dev tab will show "Capture in progress"
```

**Step 3: Extract the capture**
```bash
# Pull the capture file from device:
adb pull /sdcard/GlassFalcon/kprobe_capture.log .

# Analyze with Python:
python3 << 'EOF'
import struct

with open("kprobe_capture.log", "rb") as f:
    data = f.read()
    
# Parse DUML frames (start with 0x55)
frames = []
i = 0
while i < len(data) - 2:
    if data[i] == 0x55:
        # Found frame start
        length = (data[i+1] | (data[i+2] << 8)) & 0x3ff
        frame = data[i:i+length+4]
        frames.append(frame.hex())
        i += length + 4
    else:
        i += 1

print(f"Captured {len(frames)} DUML frames")
for idx, frame in enumerate(frames[:5]):  # Print first 5
    print(f"  Frame {idx}: {frame}")
EOF
```

---

## Troubleshooting

### "GF_RSA_EXTRACT not appearing in logcat"

1. **Verify LSPosed is active:**
   ```bash
   adb shell ls /data/adb/lsposed/
   ```

2. **Verify GO 4 is running:**
   ```bash
   adb shell pidof com.dji.go
   # Should return a PID (process ID)
   ```

3. **Check for errors in Frida hook:**
   ```bash
   # Re-run with verbose output:
   frida -U -l tools/re-extraction/extract_keychain.py -f com.dji.go --debug
   ```

4. **Verify hook targets exist in this GO 4 version:**
   ```bash
   # Dump symbols from libDJIFlySafeCore.so:
   adb shell nm /data/app/com.dji.go-*/lib/arm64-v8a/libDJIFlySafeCore.so | grep -i rsa
   ```

---

### "Key extraction succeeded but it's different from Mavic2Auth.kt"

This means the key is **per-device**, not per-model.

**Action:**
1. Save the new key from logcat
2. Update `Mavic2Auth.kt`:
   ```kotlin
   private val RSA_PRIVATE_KEY_B64: String = "NEW_KEY_HERE"
   ```
3. Test flight on the second aircraft
4. If it works, the key is confirmed per-device
5. Document the finding in `docs/reverse-engineering/findings.md`

---

## Advanced: Custom Frida Hooks

To hook additional functions, modify `extract_keychain.py`:

```python
# Example: Hook AES encryption
import frida

def on_message(message, data):
    if message['type'] == 'send':
        print(f"[*] {message['payload']}")

session = frida.get_usb_device().attach('com.dji.go')
script = session.create_script("""
Interceptor.attach(Module.findExportByName("libc.so", "AES_encrypt"), {
    onEnter(args) {
        console.log("[AES_encrypt] in_data:", hexdump(args[0], {length: 16}));
        console.log("[AES_encrypt] key:", hexdump(args[2], {length: 32}));
    }
});
""")
script.on('message', on_message)
script.load()
```

---

## References

- `docs/reverse-engineering/RSA_KEY_EXTRACTION_GUIDE.md` — Detailed extraction guide
- `docs/reverse-engineering/2026-07-06-unlock-session/` — Example extraction session logs
- `android/app/src/main/kotlin/dev/glassfalcon/core/Mavic2Auth.kt` — Where to paste the key

---

## Safety & Ethics

⚠️ **These tools are for authorized testing only:**
- Use on **your own aircraft** only
- Comply with all local regulations
- Do not reverse-engineer firmware for circumventing safety features
- Report security vulnerabilities responsibly to DJI

---

## Support

For questions or issues:
1. Check the error message in `docs/reverse-engineering/RSA_KEY_EXTRACTION_GUIDE.md`
2. Search captured logs in `docs/reverse-engineering/2026-07-06-unlock-session/`
3. Document your findings in `docs/reverse-engineering/findings.md` for future reference
