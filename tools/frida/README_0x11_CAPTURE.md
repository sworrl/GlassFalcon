# Manual Frida Capture of 0x11/0x43 Signature Computation

This guide walks you through capturing live 0x11/0x43 signature data from GO4 running on your Pixel 8 Pro.

## Prerequisites

- Pixel 8 Pro (P8: 38151FDJG003MJ) with Frida server running
- DJI GO 4 installed and able to connect to aircraft
- Aircraft powered on and connected via RC240 USB
- Frida CLI (`pip install frida-tools`)

## Step 1: Connect to Phone via ADB WiFi

```bash
./tools/phone.sh            # Connects P8 over WiFi ADB, returns IP
# Note the IP address, e.g., 192.168.13.163
```

Verify connection:
```bash
adb devices
# Should show: 38151FDJG003MJ connected
```

## Step 2: Check Frida Server on Phone

```bash
adb shell "ps aux | grep frida"
# Should show frida-server running
# If not, start it: adb shell "/data/local/tmp/frida-server &"
```

## Step 3: Start GO4 App

```bash
adb shell am start -n dji.go.v4/.MainActivity
# Wait 10 seconds for the app to fully load and connect to the aircraft
```

Verify GO4 is running:
```bash
adb shell "ps aux | grep dji.go.v4"
```

## Step 4: Run the Frida Hook

```bash
IP="192.168.13.163"  # From step 1
frida -H $IP:27042 -n dji.go.v4 -l tools/frida/hook_0x11_signature.js
```

You should see:
```
[*] Loading 0x11 signature hooks...
[+] libDJIFlySafeCore.so @ ...
[+] Found SHA256Signature export: ...
[*] Hooks installed. Waiting for frames...
```

## Step 5: Trigger Frame Capture

In the GO4 app, do ONE of:
- **Start telemetry flowing** — connect the aircraft (should start sending 0x11 frames automatically)
- **Arm the drone** — the frames will come continuously during connection
- **Wait 5-10 seconds** — the app sends them even before takeoff

You should see output like:

```
[0x11-SIG] Frame 1: SHA256Signature inputs
  Key (first 64 chars): <key data>
  Key hex: 
    00 01 02 03 04 05 06 07 08 09 0a 0b 0c 0d 0e 0f
    10 11 12 13 14 15 16 17 18 19 1a 1b 1c 1d 1e 1f
    ...
  Message (first 64 chars): <message data>
  Message hex:
    <nonce bytes>
    ...
[0x11-SIG] SHA256Signature output (32 B):
    00 11 22 33 44 55 66 77 88 99 aa bb cc dd ee ff
    ...
[0x11-SIG] 84-byte frame assembled:
    50 00 00 00 <nonce> d3 00 63 06 bd 44 fe 08 ... <signature>
```

## Step 6: Analysis

Once you have 5 frames captured, copy the output and analyze:

### Check the message format
```
Message should be 48 bytes: [32-byte nonce] || [16-byte device-token]
Nonce: random (different each frame)
Device token: d3006306bd44fe08200bfd10025716a5 (same every frame)
```

### Check the signature
```
32 bytes, should be: HMAC-SHA256(key, message)
```

### Extract the key
```
The "Key" parameter passed to SHA256Signature should be your signing key
Log this exact value — it's what we need to replicate offline
```

## Troubleshooting

**"libDJIFlySafeCore.so not loaded"**
- The library loads lazily when GO4 connects
- Try connecting the aircraft, waiting 5 seconds, then running the hook

**"SHA256Signature not found in exports"**
- The function might be inline or inlined
- Try attaching to `GetRequestParamsAndSignature` instead (the outer function)

**"Connection refused"**
- Frida server not running: `adb shell "/data/local/tmp/frida-server &"`
- Wrong IP/port: re-run `./tools/phone.sh` to get current IP

**No output after 30 seconds**
- GO4 might not be sending frames yet
- Try powering on the aircraft to trigger telemetry

## What to Report

Once you have the capture, save the output and report:

1. **5 complete frames** with:
   - Key (hex)
   - Message/input (hex)
   - Signature/output (hex)

2. **Analysis:**
   - Is message format: nonce || device_token? (48 bytes)
   - Do all signatures look random/high-entropy? (no patterns)
   - Do all nonces differ? (different every frame)
   - Does device token stay same? (d3006306... every frame)

3. **Algorithm verification:**
   - Try computing: HMAC-SHA256(key, message) offline
   - Does it match the captured signature?

## Next: Offline Replication

Once you confirm the algorithm and key, you can:

```python
import hmac
import hashlib

key = bytes.fromhex("...")  # From Frida capture
nonce = bytes.fromhex("...")  # Frame nonce
device_token = bytes.fromhex("d3006306bd44fe08200bfd10025716a5")

message = nonce + device_token
signature = hmac.new(key, message, hashlib.sha256).digest()

# signature should match the captured 32 bytes
```

Once this works offline, GlassFalcon can generate valid frames!
