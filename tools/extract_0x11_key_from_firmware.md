# Extract 0x11 Signing Key from FC Firmware

If the FC firmware validates 0x11/0x43 signatures, the signing key must be stored in the firmware binary.

## Step 1: Get Your FC Firmware Version

```bash
adb shell dumpsys | grep -i version     # Quick check via DJI app
# OR
glassfalcon$ send_cmd 0x00 0x01       # Version inquiry via DUML
```

Your FC is: **v01.00.0790** (confirm in captures or GlassFalcon Device tab)

## Step 2: Download the Firmware File

### Option A: From DJI Assistant 2 (Direct)
- Install DJI Assistant 2 on Windows/Mac
- Check for updates (will trigger firmware download)
- Intercept the download with `mitmproxy` or Wireshark
- Firmware is typically cached in: `C:\Users\<user>\AppData\Local\DJI\...`

### Option B: From DJI CDN (Community Mirror)
- Search for: `wm240_00_v01.00.0790.tar.gz` or similar
- Community archives: 
  - https://github.com/o-gs/dji-firmware-tools/wiki/Firmware-sources
  - https://archive.org/ (search "dji mavic 2 firmware")

### Option C: Pull from Aircraft (via DUML)
```bash
# Use dji-firmware-tools to request firmware module from FC via DUML
# See research/dji-firmware-tools/comm_og_service_tool.py
cd ~/Documents/GitHub/DJI/research/dji-firmware-tools
./comm_og_service_tool.py --port /dev/ttyACM0 -b 115200 WM240 firmware dump --module 0100
```

## Step 3: Extract the Module

Firmware is typically a `.tar.gz` containing `.sig` (signed, encrypted IMAH v2 modules):

```bash
cd ~/Documents/GitHub/DJI
tar -xzf wm240_00_v01.00.0790.tar.gz
ls -la *.sig
# Should see: fcu_0100_v01.00.0790.sig (flight controller module)
```

## Step 4: Decrypt the .sig File

DJI firmware modules are encrypted with published IMAH v2 keys:

```bash
cd research/dji-firmware-tools
python3 dji_imah_fwsig.py -vvv -f ../fcu_0100_v01.00.0790.sig
# Output: fcu_0100_v01.00.0790.sig.extracted/ (unpacked binary + metadata)
```

## Step 5: Search for the Signing Key in the Binary

The 0x11 signature validation key is likely:
- A 32-byte constant (HMAC-SHA256 key)
- Stored near validation functions
- Possibly labeled in debug symbols

### Search Strategy:

```bash
# 1. Look for your known device token in the binary
od -A x -t x1 fcu_0100_v01.00.0790.sig.extracted | grep "d3 00 63 06"

# 2. Look for constant patterns around HMAC/SHA functions
strings fcu_0100_v01.00.0790.sig.extracted | grep -i hmac | head -20

# 3. Use binwalk to identify sections
binwalk fcu_0100_v01.00.0790.sig.extracted

# 4. Disassemble with ARM tools
arm-none-eabi-objdump -d fcu_0100_v01.00.0790.sig.extracted | grep -A 5 -B 5 "0x11" | head -100
```

### IDA/Ghidra Approach (Best):

1. Load the extracted binary in IDA or Ghidra
2. Search for strings: "0x11", "SHA256", "HMAC", "signature", "d3006306"
3. Find the validation function
4. Trace backwards to find where the signing key is loaded/used
5. Set a breakpoint on the key load to identify its address

Example IDA search pattern:
```
.rodata or .data section
Search for: d3 00 63 06 bd 44 fe 08 20 0b fd 10 02 57 16 a5  (your device token)
Look 64 bytes before/after for a 32-byte constant (the key)
```

## Step 6: Extract the Key

Once you find the key in the binary:

```bash
# Extract specific bytes from binary
dd if=fcu_0100_v01.00.0790.sig.extracted bs=1 skip=<offset> count=32 | hexdump -C
# This gives you: xx xx xx xx ... (32 bytes)
```

## Step 7: Verify It Works

With the extracted key, test offline:

```bash
python3 tools/verify_0x11_signature.py \
  --key <extracted-key-hex> \
  --nonce <captured-nonce-hex> \
  --device-token d3006306bd44fe08200bfd10025716a5 \
  --signature <captured-signature-hex>
```

If it returns **SIGNATURE VERIFIED**, you've found the key!

## What This Tells Us

- **If key is static per-model** → all Mavic 2 Pros share the same 0x11 key
- **If key is per-device** → the key is probably derived at pairing time and stored in NVMemory, not firmware
- **If key matches DJI SDK whitebox key** → it's the master key, extractable from GO4's libDJIFlySafeCore.so

## Next: Generate Valid Frames

Once you have the key, GlassFalcon can:

```python
import hmac
import hashlib
import struct
import os

# Your extracted data
key = bytes.fromhex("<key-from-firmware>")
device_token = bytes.fromhex("d3006306bd44fe08200bfd10025716a5")

def generate_0x11_0x43_frame():
    """Generate a valid 0x11/0x43 authentication frame"""
    nonce = os.urandom(32)
    message = nonce + device_token
    signature = hmac.new(key, message, hashlib.sha256).digest()
    
    frame = struct.pack("<I", 0x50000000)  # length header
    frame += nonce                          # random nonce
    frame += device_token                   # static device token
    frame += signature                      # per-frame HMAC
    
    return frame  # 84 bytes, ready to send as 0x11/0x43 payload
```

Then GlassFalcon can fly without the 30m limit!

## Firmware Files to Check

Your specific firmware:
- Aircraft FC: v01.00.0790 (serial: 163DF7X0018B66)
- RC240: v01.00.0770
- Camera: WM240 (Hasselblad)

Start with **fcu_0100_v01.00.0790.sig** (flight controller = where the limit is enforced)
